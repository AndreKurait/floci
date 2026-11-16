package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecr.registry.RegistryHttpClient;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.BatchGetImageResponse;
import software.amazon.awssdk.services.ecr.model.EcrException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class EcrCrossAccountRepositoryIntegrationTest {

    private static final String OWNER = "111111111111";
    private static final String CALLER = "000000000000";
    private static final String REGION = "us-east-1";
    private static final List<String> PULL_ACTIONS = List.of("ecr:BatchGetImage",
            "ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer");
    private static final String DIGEST = "sha256:" + "a".repeat(64);
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
    private RegistryHttpClient registry;
    private EcrClient owner;
    private EcrClient admin;
    private EcrClient caller;
    private AccessKey access;
    private String user;
    private String principal;
    private String name;
    private String arn;

    @BeforeEach
    void setup() throws Exception {
        name = "cross-account-" + UUID.randomUUID();
        user = "EcrCrossAccount" + UUID.randomUUID();
        principal = iam.createUser(user, "/").getArn();
        access = iam.createAccessKey(user);
        owner = client(OWNER, "test");
        admin = client("test", "test");
        caller = client(access.getAccessKeyId(), access.getSecretAccessKey());
        registry = mock(RegistryHttpClient.class);
        when(manager.tryEnsureStarted()).thenReturn(true);
        when(manager.httpClient()).thenReturn(registry);
        when(manager.internalRepoName(anyString(), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(0) + "/" + call.getArgument(1) + "/" + call.getArgument(2));
        when(manager.getRepositoryUri(anyString(), anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(0) + ".dkr.ecr." + call.getArgument(1)
                        + ".localhost:4566/" + call.getArgument(2));
        when(registry.catalogStrict()).thenReturn(List.of(OWNER + "/" + REGION + "/" + name));
        when(registry.getManifest(anyString(), anyString(), any()))
                .thenReturn(new RegistryHttpClient.ManifestResult(DIGEST, "{\"schemaVersion\":2}",
                        "application/vnd.oci.image.manifest.v1+json"));
        arn = owner.createRepository(r -> r.repositoryName(name)).repository().repositoryArn();
        identity("Allow");
    }

    @AfterEach
    void cleanup() {
        try {
            owner.deleteRepository(r -> r.repositoryName(name).force(true));
            iam.deleteUserPolicy(user, "Pull");
            iam.deleteAccessKey(user, access.getAccessKeyId());
            iam.deleteUser(user);
        } finally {
            owner.close();
            admin.close();
            caller.close();
        }
    }

    @Test
    void ownerGrantAllowsNativeBatchGetImageAndKeepsCallerNamespaceEmpty() throws Exception {
        repositoryPolicy("Allow", principal);
        AccountAwareStorageBackend<Repository> repositories = repositories();
        String key = REGION + "::" + OWNER + "::" + name;
        Repository before = repositories.getForAccount(OWNER, key).orElseThrow();
        when(manager.getRepositoryUri(OWNER, REGION, name)).thenReturn("updated.example/" + name);
        BatchGetImageResponse response = pull();
        assertTrue(response.failures().isEmpty());
        assertEquals(OWNER, response.images().getFirst().registryId());
        assertEquals(DIGEST, response.images().getFirst().imageId().imageDigest());
        verify(registry).getManifest(eq(OWNER + "/" + REGION + "/" + name), eq("latest"), any());
        Repository after = repositories.getForAccount(OWNER, key).orElseThrow();
        assertEquals(before.getRepositoryGeneration(), after.getRepositoryGeneration());
        assertEquals("updated.example/" + name, after.getRepositoryUri());
        assertTrue(repositories.getForAccount(CALLER, key).isEmpty());
        when(registry.headBlob(OWNER + "/" + REGION + "/" + name, DIGEST)).thenReturn(123L);
        when(manager.blobUrl("updated.example/" + name, DIGEST)).thenReturn("https://example.test/layer");
        assertEquals(123L, caller.batchCheckLayerAvailability(r -> r.registryId(OWNER)
                .repositoryName(name).layerDigests(DIGEST)).layers().getFirst().layerSize());
        assertEquals("https://example.test/layer", caller.getDownloadUrlForLayer(r -> r.registryId(OWNER)
                .repositoryName(name).layerDigest(DIGEST)).downloadUrl());
    }

    @Test
    void staleForeignAliasCannotEraseOwnerPolicyDuringNativePull() throws Exception {
        repositoryPolicy("Allow", principal);
        AccountAwareStorageBackend<Repository> repositories = repositories();
        String key = REGION + "::" + OWNER + "::" + name;
        Repository before = new Repository(repositories.getForAccount(OWNER, key).orElseThrow());
        Repository stale = new Repository(before);
        stale.setRepositoryGeneration("stale-foreign-alias");
        stale.setRepositoryPolicyText(null);
        stale.setTags(Map.of());
        repositories.putAllAtomicallyForAccount(CALLER, Map.of(key, stale));
        try {
            assertTrue(pull().failures().isEmpty());
            Repository after = repositories.getForAccount(OWNER, key).orElseThrow();
            assertEquals(before.getRepositoryPolicyText(), after.getRepositoryPolicyText());
            assertEquals(before.getRepositoryGeneration(), after.getRepositoryGeneration());
            repositoryPolicy("Deny", principal);
            clearInvocations(registry);
            denied();
        } finally {
            repositories.deleteForAccount(CALLER, key);
        }
    }

    @Test
    void identityGrantWithoutRepositoryGrantIsDeniedBeforeRegistryAccess() {
        denied();
    }

    @Test
    void grantToDifferentPrincipalDoesNotPermitPull() throws Exception {
        repositoryPolicy("Allow", "arn:aws:iam::000000000000:user/someone-else");
        denied();
    }

    @Test
    void repositoryExplicitDenyOverridesIdentityGrant() throws Exception {
        repositoryPolicy("Deny", principal);
        denied();
    }

    @Test
    void ownerGrantWithoutCallerIdentityPermissionIsDenied() throws Exception {
        repositoryPolicy("Allow", principal);
        iam.putUserPolicy(user, "Pull", "{\"Version\":\"2012-10-17\",\"Statement\":[]}");
        denied();
    }

    @Test
    void identityExplicitDenyOverridesOwnerGrant() throws Exception {
        repositoryPolicy("Allow", principal);
        identity("Deny");
        denied();
    }

    @Test
    void sameNameInCallerAccountDoesNotSelectCallerRepository() throws Exception {
        admin.createRepository(r -> r.repositoryName(name));
        try {
            repositoryPolicy("Allow", principal);
            assertEquals(OWNER, pull().images().getFirst().registryId());
            verify(registry).getManifest(eq(OWNER + "/" + REGION + "/" + name), eq("latest"), any());
            assertEquals(CALLER, admin.describeRepositories(r -> r.repositoryNames(name))
                    .repositories().getFirst().registryId());
        } finally {
            admin.deleteRepository(r -> r.repositoryName(name).force(true));
        }
    }

    private EcrClient client(String key, String secret) {
        return EcrClient.builder().endpointOverride(endpoint).region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret))).build();
    }

    private BatchGetImageResponse pull() {
        return caller.batchGetImage(r -> r.registryId(OWNER).repositoryName(name)
                .imageIds(i -> i.imageTag("latest")));
    }

    private void denied() {
        EcrException failure = assertThrows(EcrException.class, this::pull);
        assertEquals("AccessDeniedException", failure.awsErrorDetails().errorCode());
        verifyNoInteractions(registry);
    }

    private void identity(String effect) throws Exception {
        Map<String, Object> statement = Map.of("Effect", effect, "Action", PULL_ACTIONS, "Resource", arn);
        iam.putUserPolicy(user, "Pull", mapper.writeValueAsString(
                Map.of("Version", "2012-10-17", "Statement", List.of(statement))));
    }

    private void repositoryPolicy(String effect, String allowedPrincipal) throws Exception {
        Map<String, Object> statement = Map.of("Effect", effect, "Action", PULL_ACTIONS,
                "Resource", arn, "Principal", Map.of("AWS", allowedPrincipal));
        String policy = mapper.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(statement)));
        owner.setRepositoryPolicy(r -> r.repositoryName(name).policyText(policy));
    }

    private AccountAwareStorageBackend<Repository> repositories() {
        return storage.create("ecr", "repositories.json", new TypeReference<>() {});
    }
}
