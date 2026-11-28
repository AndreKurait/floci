package io.floci.aws.services.ecr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.ecr.registry.RegistryHttpClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.BatchCheckLayerAvailabilityResponse;
import software.amazon.awssdk.services.ecr.model.GetLifecyclePolicyPreviewResponse;
import software.amazon.awssdk.services.ecr.model.ImageIdentifier;
import software.amazon.awssdk.services.ecr.model.InvalidParameterException;
import software.amazon.awssdk.services.ecr.model.Layer;
import software.amazon.awssdk.services.ecr.model.LifecyclePolicyNotFoundException;
import software.amazon.awssdk.services.ecr.model.LifecyclePolicyPreviewNotFoundException;
import software.amazon.awssdk.services.ecr.model.RepositoryNotFoundException;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Native SDK control-plane cases backed by a content-checking local registry protocol fixture. */
@QuarkusTest
class EcrLifecyclePreviewIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";

    @TestHTTPResource("/")
    URI endpoint;

    @InjectMock
    EcrRegistryManager manager;

    private EcrLifecyclePreviewRegistry registry;
    private final List<Owned> owned = new ArrayList<>();

    @BeforeEach
    void registryFixture() throws Exception {
        registry = new EcrLifecyclePreviewRegistry();
        when(manager.tryEnsureStarted()).thenReturn(true);
        when(manager.httpClient()).thenReturn(new RegistryHttpClient(registry.endpoint()));
        when(manager.internalRepoName(anyString(), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(0) + "/" + call.getArgument(1) + "/" + call.getArgument(2));
        when(manager.getRepositoryUri(anyString(), anyString(), anyString()))
                .thenAnswer(call -> registry.endpoint() + "/" + call.getArgument(0)
                        + "/" + call.getArgument(1) + "/" + call.getArgument(2));
        doAnswer(call -> {
            registry.delete(call.getArgument(0));
            return null;
        }).when(manager).deleteRepositoryStorageByInternalName(anyString());
    }

    @AfterEach
    void cleanup() {
        try {
            for (Owned fixture : owned.reversed()) {
                fixture.close();
            }
            assertTrue(registry.failures.isEmpty(), registry.failures.toString());
        } finally {
            owned.clear();
            registry.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-north-1", "us-gov-west-1"})
    void nativeCountPreviewPreservesPolicyBytesAndImagesAcrossPartitions(String region) throws Exception {
        Owned fixture = repository(region);
        Pushed oldest = fixture.push("oldest", "prod-v1");
        fixture.push("middle", "prod-v2");
        fixture.push("newest", "prod-v3");
        String policy = policy("tagged", List.of("prod"), null, "imageCountMoreThan", 2);
        Instant before = Instant.now();
        int writesBefore = writes();
        assertEquals(policy, fixture.client.startLifecyclePolicyPreview(r -> r.repositoryName(fixture.name)
                .lifecyclePolicyText(policy)).lifecyclePolicyText());
        GetLifecyclePolicyPreviewResponse preview = fixture.get();
        assertEquals("COMPLETE", preview.statusAsString());
        assertEquals(policy, preview.lifecyclePolicyText());
        assertEquals(ACCOUNT, preview.registryId());
        assertEquals(fixture.name, preview.repositoryName());
        assertEquals(Set.of(oldest.digest()), digests(preview));
        assertEquals(1, preview.summary().expiringImageTotalCount());
        assertEquals("EXPIRE", preview.previewResults().getFirst().action().typeAsString());
        assertEquals(1, preview.previewResults().getFirst().appliedRulePriority());
        assertEquals(List.of("prod-v1"), preview.previewResults().getFirst().imageTags());
        assertFalse(preview.previewResults().getFirst().imagePushedAt().isBefore(oldest.before()));
        assertFalse(preview.previewResults().getFirst().imagePushedAt().isAfter(before));
        assertEquals(3, registry.imageCount(fixture.storage));
        assertEquals(writesBefore, writes(), "Preview must not write to the image registry");
        assertThrows(LifecyclePolicyNotFoundException.class,
                () -> fixture.client.getLifecyclePolicy(r -> r.repositoryName(fixture.name)));
        assertTrue(fixture.arn.contains(":" + region + ":" + ACCOUNT + ":repository/"));
    }

    @Test
    void patternsMatchAllRequiredTagsAndDeduplicateSharedDigests() throws Exception {
        Owned fixture = repository("us-east-1");
        Pushed oldest = fixture.push("old", "prod-v1");
        fixture.tag(oldest, "release-old");
        Pushed newest = fixture.push("new", "prod-v2");
        fixture.tag(newest, "release-new");
        fixture.push("single-match", "prod-only");
        fixture.start(policy("tagged", null, List.of("prod-*", "release-*"), "imageCountMoreThan", 1));
        GetLifecyclePolicyPreviewResponse preview = fixture.get();
        assertEquals(Set.of(oldest.digest()), digests(preview));
        assertEquals(Set.of("prod-v1", "release-old"), Set.copyOf(preview.previewResults().getFirst().imageTags()));
        assertEquals(1, preview.previewResults().size());
        assertEquals(3, registry.imageCount(fixture.storage));
    }

    @Test
    void nativeDigestOnlyImagesParticipateInUntaggedCountSelection() throws Exception {
        Owned fixture = repository("us-east-1");
        Pushed oldest = fixture.push("untagged-old", null);
        fixture.push("untagged-new", null);
        fixture.push("tagged", "prod");
        fixture.start(policy("untagged", null, null, "imageCountMoreThan", 1));
        GetLifecyclePolicyPreviewResponse preview = fixture.get();
        assertEquals(Set.of(oldest.digest()), digests(preview));
        assertTrue(preview.previewResults().getFirst().imageTags().isEmpty());
        assertEquals(1, preview.summary().expiringImageTotalCount());
    }

    @Test
    void storedAgePolicyAndEmptySiblingKeepTheirIndependentPolicyAndImages() throws Exception {
        Owned populated = repository("us-east-1");
        Owned empty = repository("us-east-1");
        populated.push("fresh", "prod-v1");
        String age = policy("any", null, null, "sinceImagePushed", 30);
        populated.client.putLifecyclePolicy(r -> r.repositoryName(populated.name).lifecyclePolicyText(age));
        populated.client.startLifecyclePolicyPreview(r -> r.repositoryName(populated.name));
        assertTrue(populated.get().previewResults().isEmpty());
        assertEquals(age, populated.get().lifecyclePolicyText());
        String count = policy("tagged", List.of("prod"), null, "imageCountMoreThan", 1);
        empty.start(count);
        assertTrue(empty.get().previewResults().isEmpty());
        assertEquals(0, empty.get().summary().expiringImageTotalCount());
        assertEquals(age, populated.client.getLifecyclePolicy(r -> r.repositoryName(populated.name)).lifecyclePolicyText());
        assertEquals(1, registry.imageCount(populated.storage));
    }

    @Test
    void selectorsFiltersAndPaginationKeepTheGlobalExpiryCount() throws Exception {
        Owned fixture = repository("us-east-1");
        Pushed oldest = fixture.push("first", "prod-v1");
        Pushed middle = fixture.push("second", "prod-v2");
        fixture.push("third", "prod-v3");
        fixture.start(policy("tagged", List.of("prod"), null, "imageCountMoreThan", 1));
        GetLifecyclePolicyPreviewResponse first = fixture.client.getLifecyclePolicyPreview(
                r -> r.repositoryName(fixture.name).maxResults(1));
        assertEquals(1, first.previewResults().size());
        assertNotNull(first.nextToken());
        GetLifecyclePolicyPreviewResponse second = fixture.client.getLifecyclePolicyPreview(
                r -> r.repositoryName(fixture.name).maxResults(1).nextToken(first.nextToken()));
        assertEquals(1, second.previewResults().size());
        assertNull(second.nextToken());
        assertEquals(Set.of(oldest.digest(), middle.digest()), Set.of(
                first.previewResults().getFirst().imageDigest(), second.previewResults().getFirst().imageDigest()));
        GetLifecyclePolicyPreviewResponse tag = fixture.client.getLifecyclePolicyPreview(r -> r.repositoryName(fixture.name)
                .imageIds(ImageIdentifier.builder().imageTag("prod-v1").build()));
        assertEquals(Set.of(oldest.digest()), digests(tag));
        assertEquals(2, tag.summary().expiringImageTotalCount());
        GetLifecyclePolicyPreviewResponse untagged = fixture.client.getLifecyclePolicyPreview(
                r -> r.repositoryName(fixture.name).filter(f -> f.tagStatus("UNTAGGED")));
        assertTrue(untagged.previewResults().isEmpty());
        assertEquals(2, untagged.summary().expiringImageTotalCount());
        assertThrows(InvalidParameterException.class, () -> fixture.client.getLifecyclePolicyPreview(
                r -> r.repositoryName(fixture.name).maxResults(1)
                        .imageIds(ImageIdentifier.builder().imageDigest(oldest.digest()).build())));
    }

    @Test
    void modeledMissingResourcesAndPoliciesAreSeparate() {
        Owned fixture = repository("us-east-1");
        assertThrows(LifecyclePolicyPreviewNotFoundException.class, fixture::get);
        assertThrows(LifecyclePolicyNotFoundException.class,
                () -> fixture.client.startLifecyclePolicyPreview(r -> r.repositoryName(fixture.name)));
        String missing = fixture.name + "-missing";
        assertThrows(RepositoryNotFoundException.class,
                () -> fixture.client.getLifecyclePolicyPreview(r -> r.repositoryName(missing)));
        assertThrows(RepositoryNotFoundException.class,
                () -> fixture.client.startLifecyclePolicyPreview(r -> r.repositoryName(missing)));
    }

    @Test
    void failedInputDoesNotReplaceACompletedPreview() throws Exception {
        Owned fixture = repository("us-east-1");
        String policy = policy("any", null, null, "sinceImagePushed", 30);
        fixture.start(policy);
        assertThrows(InvalidParameterException.class, () -> fixture.client.startLifecyclePolicyPreview(
                r -> r.repositoryName(fixture.name).lifecyclePolicyText("invalid".repeat(20))));
        assertEquals(policy, fixture.get().lifecyclePolicyText());
        assertTrue(fixture.get().previewResults().isEmpty());
    }

    private Owned repository(String region) {
        Owned fixture = new Owned(region);
        owned.add(fixture);
        return fixture;
    }

    private int writes() {
        return (int) registry.traffic.stream().filter(row -> !row.startsWith("GET ") && !row.startsWith("HEAD ")).count();
    }

    private static Set<String> digests(GetLifecyclePolicyPreviewResponse response) {
        return response.previewResults().stream().map(row -> row.imageDigest()).collect(Collectors.toSet());
    }

    static String policy(String status, List<String> prefixes, List<String> patterns, String countType, int count)
            throws Exception {
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("tagStatus", status);
        selection.put("countType", countType);
        selection.put("countNumber", count);
        if (prefixes != null) {
            selection.put("tagPrefixList", prefixes);
        }
        if (patterns != null) {
            selection.put("tagPatternList", patterns);
        }
        if ("sinceImagePushed".equals(countType)) {
            selection.put("countUnit", "days");
        }
        return "  " + JSON.writeValueAsString(Map.of("rules", List.of(Map.of("rulePriority", 1,
                "description", "A bounded native SDK preview contract fixture", "selection", selection,
                "action", Map.of("type", "expire"))))) + "\n";
    }

    private final class Owned implements AutoCloseable {
        private final String name = "preview-" + UUID.randomUUID().toString().substring(0, 12);
        private final String storage;
        private final EcrClient client;
        private final String arn;
        private final Set<String> uploadedDigests = new HashSet<>();

        private Owned(String region) {
            storage = ACCOUNT + "/" + region + "/" + name;
            client = EcrClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                    .build();
            arn = client.createRepository(r -> r.repositoryName(name)).repository().repositoryArn();
        }

        private Pushed push(String label, String tag) throws Exception {
            byte[] tar = new byte[1024];
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
                gzip.write(tar);
            }
            byte[] layer = compressed.toByteArray();
            byte[] config = JSON.writeValueAsBytes(Map.of("architecture", "amd64", "os", "linux",
                    "config", Map.of("Labels", Map.of("fixture", label)),
                    "rootfs", Map.of("type", "layers", "diff_ids", List.of(EcrLifecyclePreviewRegistry.digest(tar)))));
            upload(config);
            upload(layer);
            String manifest = JSON.writeValueAsString(Map.of("schemaVersion", 2,
                    "mediaType", EcrLifecyclePreviewRegistry.OCI,
                    "config", Map.of("mediaType", "application/vnd.oci.image.config.v1+json",
                            "digest", EcrLifecyclePreviewRegistry.digest(config), "size", config.length),
                    "layers", List.of(Map.of("mediaType", "application/vnd.oci.image.layer.v1.tar+gzip",
                            "digest", EcrLifecyclePreviewRegistry.digest(layer), "size", layer.length))));
            Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            String digest = client.putImage(r -> r.repositoryName(name).imageTag(tag).imageManifest(manifest)
                    .imageManifestMediaType(EcrLifecyclePreviewRegistry.OCI)).image().imageId().imageDigest();
            assertEquals(EcrLifecyclePreviewRegistry.digest(manifest.getBytes(StandardCharsets.UTF_8)), digest);
            return new Pushed(digest, manifest, before);
        }

        private void tag(Pushed image, String tag) {
            assertEquals(image.digest(), client.putImage(r -> r.repositoryName(name).imageTag(tag)
                    .imageManifest(image.manifest()).imageManifestMediaType(EcrLifecyclePreviewRegistry.OCI))
                    .image().imageId().imageDigest());
        }

        private void upload(byte[] payload) {
            String digest = EcrLifecyclePreviewRegistry.digest(payload);
            if (uploadedDigests.contains(digest)) {
                BatchCheckLayerAvailabilityResponse available = client.batchCheckLayerAvailability(
                        r -> r.repositoryName(name).layerDigests(digest));
                assertTrue(available.failures().isEmpty());
                assertEquals(1, available.layers().size());
                Layer layer = available.layers().getFirst();
                assertEquals(digest, layer.layerDigest());
                assertEquals((long) payload.length, layer.layerSize());
                assertEquals("AVAILABLE", layer.layerAvailabilityAsString());
                return;
            }
            String id = client.initiateLayerUpload(r -> r.repositoryName(name)).uploadId();
            client.uploadLayerPart(r -> r.repositoryName(name).uploadId(id).partFirstByte(0L)
                    .partLastByte((long) payload.length - 1).layerPartBlob(SdkBytes.fromByteArray(payload)));
            assertEquals(EcrLifecyclePreviewRegistry.digest(payload), client.completeLayerUpload(
                    r -> r.repositoryName(name).uploadId(id).layerDigests(EcrLifecyclePreviewRegistry.digest(payload)))
                    .layerDigest());
            uploadedDigests.add(digest);
        }

        private void start(String policy) {
            client.startLifecyclePolicyPreview(r -> r.repositoryName(name).lifecyclePolicyText(policy));
        }

        private GetLifecyclePolicyPreviewResponse get() {
            return client.getLifecyclePolicyPreview(r -> r.repositoryName(name));
        }

        @Override
        public void close() {
            try {
                client.deleteRepository(r -> r.repositoryName(name).force(true));
                assertThrows(RepositoryNotFoundException.class,
                        () -> client.describeRepositories(r -> r.repositoryNames(name)));
            } finally {
                client.close();
            }
        }
    }

    private record Pushed(String digest, String manifest, Instant before) {}
}
