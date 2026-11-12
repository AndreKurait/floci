package io.floci.aws.services.ecr;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.ecr.model.Image;
import io.floci.aws.services.ecr.model.ImageIdentifier;
import io.floci.aws.services.ecr.model.ImageMetadata;
import io.floci.aws.services.ecr.model.LifecyclePolicyPreview;
import io.floci.aws.services.ecr.model.Repository;
import io.floci.aws.services.ecr.registry.RegistryHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.services.ecr.EcrLifecyclePreviewSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EcrLifecyclePreviewStateTest {

    @Test
    void completedPreviewRetainsPolicyAndAllManifestBytesAcrossUnrelatedParentWrites() throws Exception {
        try (EcrLifecyclePreviewSupport fixture = fixture()) {
            fixture.create();
            fixture.push("old", "prod-old");
            fixture.push("new", "prod-new");
            Map<String, String> bodies = fixture.registry.imageBodies(PHYSICAL);
            Map<String, String> tags = fixture.registry.imageTags(PHYSICAL);
            String policy = countPolicy();
            fixture.start(policy);
            LifecyclePolicyPreview snapshot = fixture.repositories.get(KEY).orElseThrow().getLifecyclePolicyPreview();
            fixture.service.tagResource(NAME, null, Map.of("new-tag", "value"), REGION);
            fixture.service.putImageTagMutability(NAME, null, "IMMUTABLE", REGION);
            fixture.service.putImageScanningConfiguration(NAME, null, true, REGION);
            fixture.service.putLifecyclePolicy(NAME, null, policy, REGION);
            fixture.service.setRepositoryPolicy(NAME, null, "{\"Statement\":[]}", REGION);
            fixture.service.describeRepositories(List.of(NAME), null, REGION);
            fixture.service.deleteLifecyclePolicy(NAME, null, REGION);
            fixture.service.deleteRepositoryPolicy(NAME, null, REGION);
            fixture.service.untagResource(NAME, null, List.of("new-tag"), REGION);
            fixture.service.reconcileFromCatalog(List.of(PHYSICAL));
            assertSame(snapshot, fixture.repositories.get(KEY).orElseThrow().getLifecyclePolicyPreview());
            assertEquals(bodies, fixture.registry.imageBodies(PHYSICAL));
            assertEquals(tags, fixture.registry.imageTags(PHYSICAL));
            int reads = fixture.registry.traffic.size();
            when(fixture.manager.httpClient()).thenReturn(null);
            assertEquals(policy, fixture.get().policy());
            assertEquals(1, fixture.get().expiringCount());
            assertEquals(reads, fixture.registry.traffic.size());
        }
    }

    @Test
    void refusedAtomicPreviewPublicationLeavesThePriorDetachedParentAndSnapshot() throws Exception {
        FaultStore<Repository> rows = new FaultStore<>();
        try (EcrLifecyclePreviewSupport fixture = new EcrLifecyclePreviewSupport(rows, new InMemoryStorage<>())) {
            fixture.create();
            fixture.start(countPolicy());
            Repository before = rows.get(ACCOUNT + "/" + KEY).orElseThrow();
            rows.failPut = true;
            assertThrows(IllegalStateException.class, () -> fixture.start(countPolicy()));
            assertSame(before, rows.get(ACCOUNT + "/" + KEY).orElseThrow());
            assertEquals("COMPLETE", fixture.get().status());
            assertSame(before.getLifecyclePolicyPreview(), fixture.repositories.get(KEY).orElseThrow().getLifecyclePolicyPreview());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aNativeRetagCannotInventTheFirstPushTimeOfAnExistingDockerDigest(boolean lazyDescribe) throws Exception {
        try (EcrLifecyclePreviewSupport fixture = fixture()) {
            fixture.create();
            String manifest = fixture.manifest("direct-docker");
            String digest = fixture.manager.httpClient().putManifest(PHYSICAL, "docker", EcrLifecyclePreviewRegistry.OCI, manifest);
            if (lazyDescribe) {
                fixture.service.describeImages(NAME, List.of(new ImageIdentifier("docker", null)), null, REGION);
            }
            fixture.service.putImage(NAME, manifest, EcrLifecyclePreviewRegistry.OCI, "native-retag", null, null, REGION);
            ImageMetadata metadata = fixture.metadata.get(KEY + "::" + digest).orElseThrow();
            assertFalse(metadata.isNativeFirstPush());
            assertEquals("ServerException", assertThrows(AwsException.class,
                    () -> fixture.start(countPolicy())).getErrorCode());
            assertNull(fixture.repositories.get(KEY).orElseThrow().getLifecyclePolicyPreview());
        }
    }

    @Test
    void nativeRetagsPreserveTheFirstTimestampAndBatchDeleteRemovesDigestOnlyMetadata() throws Exception {
        try (EcrLifecyclePreviewSupport fixture = fixture()) {
            fixture.create();
            Image image = fixture.push("first", null);
            String digest = image.getImageId().getImageDigest();
            ImageMetadata first = fixture.metadata.get(KEY + "::" + digest).orElseThrow();
            fixture.service.putImage(NAME, image.getImageManifest(), EcrLifecyclePreviewRegistry.OCI, "retag", null, null, REGION);
            assertSame(first, fixture.metadata.get(KEY + "::" + digest).orElseThrow());
            assertTrue(first.isNativeFirstPush());
            EcrService.BatchDeleteImageResult deleted = fixture.service.batchDeleteImage(NAME,
                    List.of(new ImageIdentifier(null, digest)), null, REGION);
            assertTrue(deleted.failures().isEmpty());
            assertTrue(fixture.metadata.get(KEY + "::" + digest).isEmpty());
            assertTrue(fixture.registry.imageBodies(PHYSICAL).isEmpty());
        }
    }

    @Test
    void inFlightGetUsesLocalProgressAndASecondStartIsRefusedWhileTagWritesArePreserved() throws Exception {
        try (EcrLifecyclePreviewSupport fixture = fixture()) {
            fixture.create();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean first = new AtomicBoolean(true);
            fixture.registry.beforeRequest = request -> {
                if (request.equals("GET /v2/_catalog") && first.compareAndSet(true, false)) {
                    entered.countDown();
                    await(release);
                }
            };
            String policy = countPolicy();
            CompletableFuture<EcrLifecyclePreviews.View> start = CompletableFuture.supplyAsync(() -> fixture.start(policy));
            try {
                await(entered);
                assertEquals("IN_PROGRESS", fixture.get().status());
                assertEquals(policy, fixture.get().policy());
                assertEquals("LifecyclePolicyPreviewInProgressException", assertThrows(AwsException.class,
                        () -> fixture.start(policy)).getErrorCode());
                fixture.service.tagResource(NAME, null, Map.of("during", "kept"), REGION);
            } finally {
                release.countDown();
            }
            assertEquals("COMPLETE", start.get(10, TimeUnit.SECONDS).status());
            assertEquals("kept", fixture.repositories.get(KEY).orElseThrow().getTags().get("during"));
        }
    }

    @Test
    void oldPendingPreviewIsInvisibleAfterDeletionAndRecreation() throws Exception {
        try (EcrLifecyclePreviewSupport fixture = fixture()) {
            fixture.create();
            String oldGeneration = fixture.repositories.get(KEY).orElseThrow().getRepositoryGeneration();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean first = new AtomicBoolean(true);
            fixture.registry.beforeRequest = request -> {
                if (request.equals("GET /v2/_catalog") && first.compareAndSet(true, false)) {
                    entered.countDown();
                    await(release);
                }
            };
            String policy = countPolicy();
            CompletableFuture<EcrLifecyclePreviews.View> old = CompletableFuture.supplyAsync(() -> fixture.start(policy));
            try {
                await(entered);
                fixture.service.deleteRepository(NAME, null, true, REGION);
                fixture.create();
                assertNotEquals(oldGeneration, fixture.repositories.get(KEY).orElseThrow().getRepositoryGeneration());
                assertEquals("LifecyclePolicyPreviewNotFoundException", assertThrows(AwsException.class, fixture::get).getErrorCode());
                assertEquals("COMPLETE", fixture.start(policy).status());
            } finally {
                release.countDown();
            }
            assertThrows(ExecutionException.class, () -> old.get(10, TimeUnit.SECONDS));
            assertEquals("COMPLETE", fixture.get().status());
        }
    }

    @Test
    void failedMetadataCleanupRefusesParentDeletionAndPreservesItsPreview() throws Exception {
        FaultStore<ImageMetadata> metadata = new FaultStore<>();
        try (EcrLifecyclePreviewSupport fixture = new EcrLifecyclePreviewSupport(new InMemoryStorage<>(), metadata)) {
            fixture.create();
            fixture.push("owned", "prod");
            fixture.start(countPolicy());
            Repository parent = fixture.repositories.get(KEY).orElseThrow();
            metadata.failDelete = true;
            assertThrows(IllegalStateException.class, () -> fixture.service.deleteRepository(NAME, null, true, REGION));
            assertSame(parent, fixture.repositories.get(KEY).orElseThrow());
            assertEquals("COMPLETE", fixture.get().status());
            assertThrows(AwsException.class, fixture::create);
        }
    }

    @Test
    void refusedParentDeleteAfterMetadataCleanupLeavesTheExplicitSeparateStoreBoundary() throws Exception {
        FaultStore<Repository> repositories = new FaultStore<>();
        InMemoryStorage<String, ImageMetadata> metadata = new InMemoryStorage<>();
        try (EcrLifecyclePreviewSupport fixture = new EcrLifecyclePreviewSupport(repositories, metadata)) {
            fixture.create();
            fixture.push("owned", "prod");
            fixture.start(countPolicy());
            Repository parent = fixture.repositories.get(KEY).orElseThrow();
            repositories.failDelete = true;
            assertThrows(IllegalStateException.class, () -> fixture.service.deleteRepository(NAME, null, true, REGION));
            assertSame(parent, fixture.repositories.get(KEY).orElseThrow());
            assertTrue(metadata.keys().isEmpty());
            assertEquals("COMPLETE", fixture.get().status());
            assertThrows(AwsException.class, fixture::create);
        }
    }

    @Test
    void foreignRegistryAndCorruptOuterOwnerInnerArnCannotProvidePreviewTags() throws Exception {
        InMemoryStorage<String, Repository> rows = new InMemoryStorage<>();
        try (EcrLifecyclePreviewSupport fixture = new EcrLifecyclePreviewSupport(rows, new InMemoryStorage<>())) {
            Repository owned = fixture.create();
            assertEquals(Map.of("team", "blue", "empty", ""), fixture.service.lifecyclePreviewTags(NAME, ACCOUNT, REGION));
            assertTrue(fixture.service.lifecyclePreviewTags(NAME, "111111111111", REGION).isEmpty());
            assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                    () -> fixture.service.startLifecyclePolicyPreview(NAME, "111111111111", countPolicy(), REGION)).getErrorCode());
            Repository corrupt = new Repository(owned);
            corrupt.setRepositoryArn(owned.getRepositoryArn().replace(ACCOUNT, "111111111111"));
            rows.put(ACCOUNT + "/" + KEY, corrupt);
            Set<String> before = Set.copyOf(rows.keys());
            assertTrue(fixture.service.lifecyclePreviewTags(NAME, null, REGION).isEmpty());
            assertEquals("RepositoryNotFoundException", assertThrows(AwsException.class, fixture::get).getErrorCode());
            assertEquals(before, rows.keys());
            assertSame(corrupt, rows.get(ACCOUNT + "/" + KEY).orElseThrow());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"put", "batch-delete", "describe"})
    void queuedNativeWritersCannotChangeTheRecreatedLifetime(String operation) throws Exception {
        try (EcrLifecyclePreviewSupport fixture = fixture()) {
            fixture.create();
            Image original = fixture.push("old", "prod");
            String originalDigest = original.getImageId().getImageDigest();
            CountDownLatch captured = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<Thread> writerThread = new AtomicReference<>();
            AtomicBoolean first = new AtomicBoolean(true);
            when(fixture.manager.tryEnsureStarted()).thenAnswer(call -> {
                if (Thread.currentThread() == writerThread.get() && first.compareAndSet(true, false)) {
                    captured.countDown();
                    await(release);
                }
                return true;
            });
            CompletableFuture<Object> writer = CompletableFuture.supplyAsync(() -> {
                writerThread.set(Thread.currentThread());
                return switch (operation) {
                    case "put" -> fixture.service.putImage(NAME, original.getImageManifest(),
                            EcrLifecyclePreviewRegistry.OCI, "late", null, null, REGION);
                    case "batch-delete" -> fixture.service.batchDeleteImage(NAME,
                            List.of(new ImageIdentifier(null, originalDigest)), null, REGION);
                    default -> fixture.service.describeImages(NAME,
                            List.of(new ImageIdentifier("prod", null)), null, REGION);
                };
            });
            Map<String, String> before;
            Repository recreated;
            try {
                await(captured);
                fixture.service.deleteRepository(NAME, null, true, REGION);
                recreated = fixture.create();
                fixture.push("new", "prod");
                fixture.start(countPolicy());
                before = fixture.registry.imageBodies(PHYSICAL);
            } finally {
                release.countDown();
            }
            if ("batch-delete".equals(operation)) {
                EcrService.BatchDeleteImageResult result = (EcrService.BatchDeleteImageResult) writer.get(10, TimeUnit.SECONDS);
                assertTrue(result.imageIds().isEmpty());
                assertFalse(result.failures().isEmpty());
            } else {
                assertThrows(ExecutionException.class, () -> writer.get(10, TimeUnit.SECONDS));
            }
            assertEquals(before, fixture.registry.imageBodies(PHYSICAL));
            assertEquals(recreated.getRepositoryGeneration(), fixture.repositories.get(KEY).orElseThrow().getRepositoryGeneration());
            assertTrue(fixture.metadata.scan(k -> k.startsWith(KEY + "::")).stream()
                    .allMatch(row -> recreated.getRepositoryGeneration().equals(row.getRepositoryGeneration())));
            assertEquals("COMPLETE", fixture.get().status());
        }
    }

    @Test
    void finalPruneKeepsNewCreationOutsideThePruneWindow() throws Exception {
        try (EcrLifecyclePreviewSupport fixture = fixture()) {
            fixture.create();
            CountDownLatch pruning = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch creating = new CountDownLatch(1);
            doAnswer(call -> {
                pruning.countDown();
                await(release);
                return null;
            }).when(fixture.manager).pruneStorage();
            CompletableFuture<Repository> deleted = CompletableFuture.supplyAsync(
                    () -> fixture.service.deleteRepository(NAME, null, true, REGION));
            CompletableFuture<Repository> created;
            try {
                await(pruning);
                created = CompletableFuture.supplyAsync(() -> {
                    creating.countDown();
                    return fixture.create();
                });
                await(creating);
                assertThrows(TimeoutException.class, () -> created.get(100, TimeUnit.MILLISECONDS));
                assertTrue(fixture.repositories.get(KEY).isEmpty());
            } finally {
                release.countDown();
            }
            deleted.get(10, TimeUnit.SECONDS);
            assertNotNull(created.get(10, TimeUnit.SECONDS).getRepositoryGeneration());
            fixture.push("after-prune", "prod");
            assertEquals(1, fixture.registry.imageBodies(PHYSICAL).size());
            verify(fixture.manager, times(1)).pruneStorage();
        }
    }

    @Test
    void paginationTokensAreBoundToTheSnapshotScopeAndFilter() throws Exception {
        try (EcrLifecyclePreviewSupport fixture = fixture()) {
            fixture.create();
            fixture.push("first", "prod-first");
            fixture.push("second", "prod-second");
            fixture.push("third", "prod-third");
            String policy = countPolicy();
            fixture.start(policy);
            EcrLifecyclePreviews.View page = fixture.service.getLifecyclePolicyPreview(NAME, null,
                    List.of(), null, 1, null, REGION);
            assertNotNull(page.nextToken());
            assertEquals(2, page.expiringCount());
            assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                    () -> fixture.service.getLifecyclePolicyPreview(NAME, null, List.of(), "TAGGED", 1,
                            page.nextToken(), REGION)).getErrorCode());
            fixture.service.createRepository("other-preview", null, null, null, null, null, null, REGION);
            fixture.service.startLifecyclePolicyPreview("other-preview", null, policy, REGION);
            assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                    () -> fixture.service.getLifecyclePolicyPreview("other-preview", null, List.of(), null, 1,
                            page.nextToken(), REGION)).getErrorCode());
            fixture.start(policy);
            assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                    () -> fixture.service.getLifecyclePolicyPreview(NAME, null, List.of(), null, 1,
                            page.nextToken(), REGION)).getErrorCode());
            assertEquals(3, fixture.registry.imageBodies(PHYSICAL).size());
        }
    }

    @Test
    void batchDeleteRechecksBareNamespaceOwnershipAfterWaitingForItsPhysicalLock() throws Exception {
        try (EcrLifecyclePreviewSupport fixture = fixture()) {
            Repository original = new Repository(fixture.create());
            original.setRegistryRepositoryName(NAME);
            fixture.repositories.put(KEY, original);
            RegistryHttpClient http = mock(RegistryHttpClient.class);
            CountDownLatch resolved = new CountDownLatch(1);
            when(http.catalogStrict()).thenReturn(List.of(NAME));
            EcrService racing = spy(fixture.service);
            doAnswer(call -> {
                resolved.countDown();
                return call.callRealMethod();
            }).when(racing).lockRepositoryWrites(NAME);
            when(fixture.manager.httpClient()).thenReturn(http);
            fixture.service.lockRepositoryWrites(NAME);
            CompletableFuture<EcrService.BatchDeleteImageResult> deletion = CompletableFuture.supplyAsync(
                    () -> racing.batchDeleteImage(NAME,
                            List.of(new ImageIdentifier(null, "sha256:" + "a".repeat(64))), null, REGION));
            try {
                await(resolved);
                Repository other = new Repository(original);
                other.setRegistryId("111111111111");
                other.setRepositoryArn(AwsArnUtils.Arn.of("ecr", REGION, "111111111111", "repository/" + NAME).toString());
                fixture.repositories.putForAccount("111111111111", REGION + "::111111111111::" + NAME, other);
            } finally {
                fixture.service.unlockRepositoryWrites(NAME);
            }
            EcrService.BatchDeleteImageResult result = deletion.get(10, TimeUnit.SECONDS);
            assertTrue(result.imageIds().isEmpty());
            assertFalse(result.failures().isEmpty());
            verify(http, never()).deleteManifest(anyString(), anyString());
            assertSame(original.getLifecyclePolicyPreview(), fixture.repositories.get(KEY).orElseThrow().getLifecyclePolicyPreview());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void describeOverlappingDeletionReturnsItsSnapshotWithoutRestoringOrChangingTheNewLifetime(boolean recreate)
            throws Exception {
        try (EcrLifecyclePreviewSupport fixture = fixture()) {
            Repository original = fixture.create();
            fixture.start(countPolicy());
            LifecyclePolicyPreview oldPreview = fixture.repositories.get(KEY).orElseThrow().getLifecyclePolicyPreview();
            AtomicBoolean replaceDuringRefresh = new AtomicBoolean(true);
            AtomicReference<Repository> replacement = new AtomicReference<>();
            when(fixture.manager.getRepositoryUri(anyString(), anyString(), anyString())).thenAnswer(call -> {
                if (replaceDuringRefresh.compareAndSet(true, false)) {
                    fixture.service.deleteRepository(NAME, null, true, REGION);
                    if (recreate) {
                        fixture.create();
                        fixture.start(countPolicy());
                        replacement.set(fixture.repositories.get(KEY).orElseThrow());
                    }
                }
                return "registry.invalid/captured-before-deletion";
            });
            List<Repository> described = fixture.service.describeRepositories(List.of(NAME), null, REGION);
            assertEquals(1, described.size());
            assertEquals(original.getRepositoryGeneration(), described.getFirst().getRepositoryGeneration());
            assertEquals(oldPreview, described.getFirst().getLifecyclePolicyPreview());
            if (recreate) {
                Repository current = fixture.repositories.get(KEY).orElseThrow();
                assertSame(replacement.get(), current);
                assertNotEquals(original.getRepositoryGeneration(), current.getRepositoryGeneration());
                assertNotEquals(oldPreview.previewId(), current.getLifecyclePolicyPreview().previewId());
                assertEquals(current.getLifecyclePolicyPreview().results(), fixture.get().results());
            } else {
                assertTrue(fixture.repositories.get(KEY).isEmpty());
            }
        }
    }

    private static EcrLifecyclePreviewSupport fixture() throws Exception {
        return new EcrLifecyclePreviewSupport(new InMemoryStorage<>(), new InMemoryStorage<>());
    }
}
