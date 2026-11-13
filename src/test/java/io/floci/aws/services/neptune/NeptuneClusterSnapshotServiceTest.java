package io.floci.aws.services.neptune;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.neptune.container.NeptuneContainerHandle;
import io.floci.aws.services.neptune.container.NeptuneContainerManager;
import io.floci.aws.services.neptune.model.NeptuneCluster;
import io.floci.aws.services.neptune.model.NeptuneClusterSettings;
import io.floci.aws.services.neptune.model.NeptuneClusterSnapshot;
import io.floci.aws.services.neptune.model.NeptuneDbType;
import io.floci.aws.services.neptune.proxy.NeptuneProxyManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NeptuneClusterSnapshotServiceTest {
    private static final String ARCHIVE = Base64.getEncoder().encodeToString(
            "{\"snapshot\":1}".getBytes(StandardCharsets.UTF_8));
    private final NeptuneContainerManager containers = mock(NeptuneContainerManager.class);
    private final NeptuneProxyManager proxies = mock(NeptuneProxyManager.class);
    private final EmulatorConfig config = mock(EmulatorConfig.class);
    private final EmulatorConfig.NeptuneServiceConfig neptune = mock(EmulatorConfig.NeptuneServiceConfig.class);
    private final RegionResolver resolver = mock(RegionResolver.class);
    private final StorageFactory factory = mock(StorageFactory.class);
    private String account = "111111111111";
    private String region = "us-east-1";
    private NeptuneService service;

    @TempDir
    Path directory;

    @BeforeEach
    void setUp() {
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        when(config.services()).thenReturn(services);
        when(services.neptune()).thenReturn(neptune);
        when(config.hostname()).thenReturn(Optional.of("localhost"));
        when(neptune.dbType()).thenReturn("gremlin");
        when(neptune.defaultImage()).thenReturn("gremlin-test");
        when(neptune.proxyBasePort()).thenReturn(18182);
        when(neptune.proxyMaxPort()).thenReturn(18282);
        when(resolver.getAccountId()).thenAnswer(invocation -> account);
        when(resolver.getDefaultAccountId()).thenReturn(account);
        when(resolver.getRegion()).thenAnswer(invocation -> region);
        when(resolver.getDefaultRegion()).thenReturn(region);
        when(resolver.buildArn(anyString(), anyString(), anyString())).thenAnswer(invocation ->
                "arn:aws:" + invocation.getArgument(0) + ":" + invocation.getArgument(1)
                        + ":" + account + ":" + invocation.getArgument(2));
        when(factory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                AccountAwareStorageBackend.inMemory("111111111111"));
        when(containers.tryStart(anyString(), anyString(), any(NeptuneDbType.class), anyString(), anyString()))
                .thenAnswer(invocation -> new NeptuneContainerHandle("container-" + invocation.getArgument(0),
                        invocation.getArgument(0), "localhost", 8182));
        when(containers.snapshotGraph(any())).thenReturn(ARCHIVE);
        service = new NeptuneService(config, resolver, containers, proxies, factory);
    }

    @Test
    void explicitMockSnapshotRetainsCapturedSettingsAfterSourceMutationAndDeletion() {
        when(neptune.mock()).thenReturn(true);
        NeptuneCluster source = service.createDbCluster("source", "1.3.2.1", true);
        source.setBackupRetentionPeriod(5);
        source.setPreferredBackupWindow("02:00-03:00");
        source.setCopyTagsToSnapshot(true);
        service.addTagsToResource(source.getDbClusterArn(), Map.of("source", "retained"));
        NeptuneClusterSnapshot snapshot = service.createDbClusterSnapshot("Snap", "source", Map.of("own", "tag"));
        assertTrue(snapshot.metadataOnly());
        assertNull(snapshot.archiveBase64());
        assertEquals("snap", snapshot.dbClusterSnapshotIdentifier());
        assertEquals(Map.of("source", "retained", "own", "tag"), snapshot.tags());
        source.setBackupRetentionPeriod(9);
        service.deleteDbCluster("source");
        NeptuneCluster target = restore("target", snapshot.dbClusterSnapshotArn());
        assertEquals(5, target.getBackupRetentionPeriod());
        assertEquals("02:00-03:00", target.getPreferredBackupWindow());
        assertTrue(target.getDbClusterMembers().isEmpty());
        assertNotEquals(snapshot.dbClusterArn(), target.getDbClusterArn());
        assertEquals(Map.of("restored", "yes"), target.getTags());
        assertEquals(snapshot, service.deleteDbClusterSnapshot(snapshot.dbClusterSnapshotArn()));
        assertFalse(service.hasClusterSnapshot("snap"));
        assertEquals("DBClusterSnapshotNotFoundFault",
                assertThrows(AwsException.class, () -> service.getDbClusterSnapshot("snap")).getErrorCode());
        verify(containers, never()).snapshotGraph(any());
        verify(containers, never()).restoreGraph(any(), anyString());
    }

    @Test
    void archiveIsLoadedBeforeTargetPublicationAndRemainsIndependentOfSource() {
        NeptuneCluster source = service.createDbCluster("source", null, false);
        NeptuneClusterSnapshot snapshot = service.createDbClusterSnapshot("snapshot", source.getDbClusterArn(), Map.of());
        assertFalse(snapshot.metadataOnly());
        assertEquals(ARCHIVE, snapshot.archiveBase64());
        service.deleteDbCluster("source");
        doAnswer(invocation -> {
            assertFalse(service.hasCluster("target"), "A partially loaded graph must not be available");
            return null;
        }).when(containers).restoreGraph(any(), eq(ARCHIVE));
        NeptuneCluster target = restore("target", "snapshot");
        assertEquals("container-target", target.getContainerId());
        assertEquals("available", target.getStatus());
        assertSame(snapshot, service.getDbClusterSnapshot("snapshot"));
        verify(containers).restoreGraph(any(), eq(ARCHIVE));
    }

    @Test
    void missingTargetBackendRollsBackAndPreservesSourceAndSnapshot() {
        NeptuneCluster source = service.createDbCluster("source", null, false);
        NeptuneClusterSnapshot snapshot = service.createDbClusterSnapshot("snapshot", "source", Map.of("kept", "yes"));
        when(containers.tryStart(eq("target"), anyString(), any(NeptuneDbType.class), anyString(), anyString()))
                .thenReturn(null);
        AwsException failure = assertThrows(AwsException.class, () -> restore("target", "snapshot"));
        assertEquals("InvalidRestoreFault", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
        assertFalse(service.hasCluster("target"));
        assertSame(source, service.getDbCluster("source"));
        assertSame(snapshot, service.getDbClusterSnapshot("snapshot"));
        assertEquals(ARCHIVE, snapshot.archiveBase64());
        verify(containers).stopByClusterId("111111111111-us-east-1-target");
        verify(containers, never()).restoreGraph(any(), anyString());
        assertEquals(18183, service.createDbCluster("next", null, false).getProxyPort());
    }

    @Test
    void failedArchiveLoadNeverPublishesEvenADeletionProtectedTarget() {
        service.createDbCluster("source", null, false);
        NeptuneClusterSnapshot snapshot = service.createDbClusterSnapshot("snapshot", "source", Map.of());
        doThrow(new IllegalStateException("invalid GraphSON")).when(containers).restoreGraph(any(), anyString());
        NeptuneClusterSettings protectedSettings = new NeptuneClusterSettings(null, null, null, null, null,
                null, null, null, null, null, null, true, null, null, null, null, null, null);
        AwsException failure = assertThrows(AwsException.class, () -> service.restoreDbClusterFromSnapshot(
                "target", "snapshot", null, null, protectedSettings, Map.of()));
        assertEquals("InvalidRestoreFault", failure.getErrorCode());
        assertFalse(service.hasCluster("target"));
        assertSame(snapshot, service.getDbClusterSnapshot("snapshot"));
        verify(containers).stopByClusterId("111111111111-us-east-1-target");
    }

    @Test
    void failedCaptureAndNeo4jNeverProduceAnAvailableSnapshot() {
        service.createDbCluster("source", null, false);
        when(containers.snapshotGraph(any())).thenThrow(new IllegalStateException("backend not reachable"));
        assertEquals("InvalidDBClusterStateFault", assertThrows(AwsException.class,
                () -> service.createDbClusterSnapshot("failed", "source", Map.of())).getErrorCode());
        assertFalse(service.hasClusterSnapshot("failed"));
        when(neptune.dbType()).thenReturn("neo4j");
        assertEquals("InvalidDBClusterStateFault", assertThrows(AwsException.class,
                () -> service.createDbClusterSnapshot("unsupported", "source", Map.of())).getErrorCode());
        assertFalse(service.hasClusterSnapshot("unsupported"));
    }

    @Test
    void identifiersCannotCrossAccountRegionOrArnOwnership() {
        service.createDbCluster("source", null, false);
        NeptuneClusterSnapshot snapshot = service.createDbClusterSnapshot("snapshot", "source", Map.of());
        account = "222222222222";
        assertFalse(service.hasClusterSnapshot("snapshot"));
        assertFalse(service.hasClusterSnapshot(snapshot.dbClusterSnapshotArn()));
        assertThrows(AwsException.class, () -> restore("foreign", snapshot.dbClusterSnapshotArn()));
        account = "111111111111";
        region = "us-west-2";
        assertFalse(service.hasClusterSnapshot("snapshot"));
        assertThrows(AwsException.class, () -> service.deleteDbClusterSnapshot(snapshot.dbClusterSnapshotArn()));
        region = "us-east-1";
        assertSame(snapshot, service.getDbClusterSnapshot("SNAPSHOT"));
        assertFalse(service.hasClusterSnapshot(snapshot.dbClusterSnapshotArn().replace("arn:aws:", "arn:aws-cn:")));
    }

    @Test
    void snapshotAndArchiveReloadTogetherAndDeletingThemDoesNotDeleteCluster() {
        Path file = directory.resolve("snapshots.json");
        TypeReference<Map<String, NeptuneClusterSnapshot>> type = new TypeReference<>() {};
        PersistentStorage<String, NeptuneClusterSnapshot> persistent = new PersistentStorage<>(file, type);
        when(factory.create(eq("neptune"), eq("neptune-cluster-snapshots.json"), any()))
                .thenAnswer(invocation -> new AccountAwareStorageBackend<>(persistent, null, "111111111111"));
        service = new NeptuneService(config, resolver, containers, proxies, factory);
        service.createDbCluster("source", null, false);
        NeptuneClusterSnapshot snapshot = service.createDbClusterSnapshot("snapshot", "source", Map.of("one", "two"));
        PersistentStorage<String, NeptuneClusterSnapshot> reopened = new PersistentStorage<>(file, type);
        reopened.load();
        assertEquals(Set.of("111111111111/us-east-1/snapshot"), reopened.keys());
        assertEquals(snapshot, reopened.get("111111111111/us-east-1/snapshot").orElseThrow());
        service.deleteDbClusterSnapshot("snapshot");
        reopened.load();
        assertTrue(reopened.isEmpty());
        assertTrue(service.hasCluster("source"));
    }

    @Test
    void sourceDeletionWaitsForItsArchiveCaptureWithoutResurrectingTheCluster() throws Exception {
        NeptuneCluster source = service.createDbCluster("source", null, false);
        CountDownLatch capturing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> captureThread = new AtomicReference<>();
        AtomicReference<Thread> deleteThread = new AtomicReference<>();
        when(containers.snapshotGraph(any())).thenAnswer(invocation -> {
            captureThread.set(Thread.currentThread());
            capturing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return ARCHIVE;
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<NeptuneClusterSnapshot> capture = executor.submit(() ->
                    service.createDbClusterSnapshot("snapshot", source.getDbClusterArn(), Map.of()));
            assertTrue(capturing.await(5, TimeUnit.SECONDS));
            Future<?> deletion = executor.submit(() -> {
                deleteThread.set(Thread.currentThread());
                service.deleteDbCluster("source");
            });
            try {
                await().atMost(Duration.ofSeconds(5)).until(() -> {
                    Thread thread = deleteThread.get();
                    ThreadInfo info = thread == null ? null
                            : ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
                    return deletion.isDone() || info != null
                            && info.getLockOwnerId() == captureThread.get().threadId();
                });
                assertFalse(deletion.isDone(), "The source backend must remain live while being archived");
            } finally {
                release.countDown();
            }
            assertEquals(ARCHIVE, capture.get(5, TimeUnit.SECONDS).archiveBase64());
            deletion.get(5, TimeUnit.SECONDS);
            assertFalse(service.hasCluster("source"));
            assertEquals(ARCHIVE, service.getDbClusterSnapshot("snapshot").archiveBase64());
        }
    }

    @Test
    void metadataOnlyAndRealArchivesCannotBeRestoredUnderTheOtherMode() {
        when(neptune.mock()).thenReturn(true);
        service.createDbCluster("metadata", null, false);
        service.createDbClusterSnapshot("metadata-snapshot", "metadata", Map.of());
        when(neptune.mock()).thenReturn(false);
        assertEquals("InvalidRestoreFault", assertThrows(AwsException.class,
                () -> restore("real-target", "metadata-snapshot")).getErrorCode());
        assertFalse(service.hasCluster("real-target"));
        service.createDbCluster("real", null, false);
        service.createDbClusterSnapshot("real-snapshot", "real", Map.of());
        when(neptune.mock()).thenReturn(true);
        assertEquals("InvalidRestoreFault", assertThrows(AwsException.class,
                () -> restore("metadata-target", "real-snapshot")).getErrorCode());
        assertFalse(service.hasCluster("metadata-target"));
        assertEquals(ARCHIVE, service.getDbClusterSnapshot("real-snapshot").archiveBase64());
    }

    @Test
    void failingSnapshotCommitPublishesNeitherMetadataNorArchive() {
        AccountAwareStorageBackend<NeptuneClusterSnapshot> storage =
                spy(AccountAwareStorageBackend.<NeptuneClusterSnapshot>inMemory("111111111111"));
        when(factory.create(eq("neptune"), eq("neptune-cluster-snapshots.json"), any()))
                .thenAnswer(invocation -> storage);
        service = new NeptuneService(config, resolver, containers, proxies, factory);
        NeptuneCluster source = service.createDbCluster("source", null, false);
        doThrow(new IllegalStateException("snapshot storage unavailable")).when(storage)
                .putAllAtomicallyForAccount(anyString(), any());
        assertThrows(IllegalStateException.class, () -> service.createDbClusterSnapshot("snapshot", "source", Map.of()));
        assertFalse(service.hasClusterSnapshot("snapshot"));
        assertSame(source, service.getDbCluster("source"));
    }

    @Test
    void failingRestoreCommitRemovesTheLoadedTargetAndKeepsItsSnapshot() {
        AccountAwareStorageBackend<NeptuneCluster> storage =
                spy(AccountAwareStorageBackend.<NeptuneCluster>inMemory("111111111111"));
        when(factory.create(eq("neptune"), eq("neptune-clusters.json"), any()))
                .thenAnswer(invocation -> storage);
        service = new NeptuneService(config, resolver, containers, proxies, factory);
        NeptuneCluster source = service.createDbCluster("source", null, false);
        NeptuneClusterSnapshot snapshot = service.createDbClusterSnapshot("snapshot", "source", Map.of());
        doThrow(new IllegalStateException("cluster storage unavailable")).when(storage)
                .putAllAtomicallyForAccount(anyString(), any());
        assertThrows(IllegalStateException.class, () -> restore("target", "snapshot"));
        assertFalse(service.hasCluster("target"));
        assertSame(source, service.getDbCluster("source"));
        assertSame(snapshot, service.getDbClusterSnapshot("snapshot"));
        verify(containers).restoreGraph(any(), eq(ARCHIVE));
        verify(containers).stopByClusterId("111111111111-us-east-1-target");
        verify(proxies).stopProxy("111111111111-us-east-1-target");
    }

    private NeptuneCluster restore(String target, String snapshot) {
        return service.restoreDbClusterFromSnapshot(target, snapshot, null, null,
                NeptuneClusterSettings.defaults(), Map.of("restored", "yes"));
    }
}
