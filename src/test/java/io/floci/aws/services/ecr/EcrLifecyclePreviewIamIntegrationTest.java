package io.floci.aws.services.ecr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ecr.model.ImageMetadata;
import io.floci.aws.services.ecr.model.Repository;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.ecr.registry.RegistryHttpClient;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.testing.IamEnforcementProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.EcrException;
import software.amazon.awssdk.services.ecr.model.RepositoryNotFoundException;
import software.amazon.awssdk.services.ecr.model.Tag;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EcrLifecyclePreviewIamIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;
    @Inject
    StorageFactory storage;
    @Inject
    ObjectMapper mapper;
    @InjectMock
    EcrRegistryManager manager;
    private EcrLifecyclePreviewRegistry registry;
    private String user;
    private AccessKey access;
    private final List<Owned> owned = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
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
        user = "PreviewIam" + UUID.randomUUID().toString().substring(0, 10);
        iam.createUser(user, "/");
        access = iam.createAccessKey(user);
        policy("ecr:DescribeRepositories", "*", null);
    }

    @AfterEach
    void cleanup() {
        try {
            for (Owned value : owned.reversed()) {
                value.close();
            }
            iam.deleteUserPolicy(user, "Preview");
            iam.deleteAccessKey(user, access.getAccessKeyId());
            iam.deleteUser(user);
            assertTrue(registry.failures.isEmpty(), registry.failures.toString());
        } finally {
            owned.clear();
            registry.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-north-1", "us-gov-west-1"})
    void eachActionRequiresItsOwnGrantOnTheExactPartitionRepository(String region) throws Exception {
        Owned one = owned(region);
        Owned two = owned(region);
        String policyText = EcrLifecyclePreviewSupport.countPolicy();
        policy("ecr:StartLifecyclePolicyPreview", one.arn, null);
        one.caller.startLifecyclePolicyPreview(r -> r.repositoryName(one.name).lifecyclePolicyText(policyText));
        denied(() -> one.caller.getLifecyclePolicyPreview(r -> r.repositoryName(one.name)));
        denied(() -> two.caller.startLifecyclePolicyPreview(r -> r.repositoryName(two.name).lifecyclePolicyText(policyText)));
        policy("ecr:GetLifecyclePolicyPreview", one.arn, null);
        assertEquals("COMPLETE", one.caller.getLifecyclePolicyPreview(r -> r.repositoryName(one.name)).statusAsString());
        denied(() -> one.caller.startLifecyclePolicyPreview(r -> r.repositoryName(one.name).lifecyclePolicyText(policyText)));
        denied(() -> two.caller.getLifecyclePolicyPreview(r -> r.repositoryName(two.name)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws:ResourceTag/", "ecr:ResourceTag/"})
    void resourceTagConditionsKeepEmptyValuesAndReadCurrentTags(String prefix) throws Exception {
        Owned one = owned("us-east-1");
        String policyText = EcrLifecyclePreviewSupport.countPolicy();
        one.admin.tagResource(r -> r.resourceArn(one.arn).tags(Tag.builder().key("team").value("blue").build(),
                Tag.builder().key("empty").value("").build()));
        policy("ecr:*LifecyclePolicyPreview", one.arn,
                Map.of("StringEquals", Map.of(prefix + "team", "blue", prefix + "empty", "")));
        one.caller.startLifecyclePolicyPreview(r -> r.repositoryName(one.name).lifecyclePolicyText(policyText));
        one.caller.getLifecyclePolicyPreview(r -> r.repositoryName(one.name));
        one.admin.tagResource(r -> r.resourceArn(one.arn).tags(Tag.builder().key("team").value("red").build()));
        int traffic = registry.traffic.size();
        denied(() -> one.caller.getLifecyclePolicyPreview(r -> r.repositoryName(one.name)));
        denied(() -> one.caller.startLifecyclePolicyPreview(r -> r.repositoryName(one.name).lifecyclePolicyText(policyText)));
        assertEquals(traffic, registry.traffic.size());
        assertEquals(policyText, one.admin.getLifecyclePolicyPreview(r -> r.repositoryName(one.name)).lifecyclePolicyText());
    }

    @Test
    void deniedNativeLegacyRequestsPreserveRawBytesKeysGenerationAndPreview() throws Exception {
        Owned one = owned("us-east-1");
        AccountAwareStorageBackend<Repository> repositories = storage.create("ecr", "repositories.json", new TypeReference<>() {});
        AccountAwareStorageBackend<ImageMetadata> imageMetadata = storage.create("ecr", "image-metadata.json", new TypeReference<>() {});
        StorageBackend<String, Repository> raw = raw(repositories);
        String logical = "us-east-1::000000000000::" + one.name;
        String scoped = "000000000000/" + logical;
        Repository legacy = new Repository(raw.get(scoped).orElseThrow());
        legacy.setRepositoryGeneration(null);
        raw.put(logical, legacy);
        raw.delete(scoped);
        Set<String> keys = Set.copyOf(raw.keys());
        byte[] before = mapper.writeValueAsBytes(legacy);
        Map<String, ImageMetadata> oldImages = imageMetadata.scanAllAccountsWithRawKeys();
        String policyText = EcrLifecyclePreviewSupport.countPolicy();
        try {
            policy("ecr:*LifecyclePolicyPreview", one.arn,
                    Map.of("StringEquals", Map.of("ecr:ResourceTag/team", "denied")));
            int requests = registry.traffic.size();
            denied(() -> one.caller.getLifecyclePolicyPreview(r -> r.repositoryName(one.name)));
            denied(() -> one.caller.startLifecyclePolicyPreview(r -> r.repositoryName(one.name).lifecyclePolicyText(policyText)));
            assertEquals(requests, registry.traffic.size());
            assertEquals(keys, raw.keys());
            assertSame(legacy, raw.get(logical).orElseThrow());
            assertArrayEquals(before, mapper.writeValueAsBytes(legacy));
            assertEquals(oldImages, imageMetadata.scanAllAccountsWithRawKeys());
        } finally {
            raw.put(scoped, legacy);
            raw.delete(logical);
        }
    }

    private Owned owned(String region) {
        Owned result = new Owned(region);
        owned.add(result);
        return result;
    }

    private void policy(String action, String resource, Map<String, Object> condition) {
        try {
            Map<String, Object> statement = new LinkedHashMap<>();
            statement.put("Effect", "Allow");
            statement.put("Action", action);
            statement.put("Resource", resource);
            if (condition != null) {
                statement.put("Condition", condition);
            }
            iam.putUserPolicy(user, "Preview", mapper.writeValueAsString(
                    Map.of("Version", "2012-10-17", "Statement", List.of(statement))));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void denied(Runnable request) {
        EcrException failure = assertThrows(EcrException.class, request::run);
        assertEquals("AccessDeniedException", failure.awsErrorDetails().errorCode());
        assertEquals(400, failure.statusCode());
    }

    @SuppressWarnings("unchecked")
    private static <T> StorageBackend<String, T> raw(AccountAwareStorageBackend<T> scoped) throws Exception {
        Field delegate = AccountAwareStorageBackend.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        return (StorageBackend<String, T>) delegate.get(scoped);
    }

    private final class Owned implements AutoCloseable {
        final String name = "preview-iam-" + UUID.randomUUID().toString().substring(0, 12);
        final EcrClient admin;
        final EcrClient caller;
        final String arn;

        Owned(String region) {
            admin = EcrClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build();
            caller = EcrClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(access.getAccessKeyId(), access.getSecretAccessKey()))).build();
            arn = admin.createRepository(r -> r.repositoryName(name)).repository().repositoryArn();
        }

        @Override
        public void close() {
            try {
                admin.deleteRepository(r -> r.repositoryName(name).force(true));
                assertThrows(RepositoryNotFoundException.class, () -> admin.describeRepositories(r -> r.repositoryNames(name)));
            } finally {
                caller.close();
                admin.close();
            }
        }
    }
}
