package io.floci.aws.services.ecr.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.AuthConfig;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.ecr.EcrService;
import io.floci.aws.services.ecr.model.Repository;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class EcrWorkloadImagePullTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String IMAGE = "localhost:4566/111111111111/us-east-1/image:latest";
    private static final String ROLE = "arn:aws:iam::111111111111:role/pull";
    private static final String SOURCE = "arn:aws:ecs:us-east-1:111111111111:task/cluster/task";
    private static final String RESOURCE = "arn:aws:ecr:us-east-1:111111111111:repository/image";
    private EcrService ecr;
    private IamService iam;
    private EmulatorConfig config;
    private EcrRegistryAuthorization authorization;
    private EcrWorkloadImagePull pulls;
    private Repository repository;
    private Function<Supplier<AuthConfig>, String> cachedPull;
    private Clock clock;
    private Instant started;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ecr = mock(EcrService.class);
        iam = mock(IamService.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        IamRole role = new IamRole("AROAPULL", "pull", "/", ROLE, "{}");
        when(iam.findRole(ACCOUNT, "pull")).thenReturn(Optional.of(role));
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of("""
                {"Statement":{"Effect":"Allow","Action":["ecr:BatchGetImage","ecr:GetDownloadUrlForLayer"],
                "Resource":"*","Condition":{"StringEquals":{"ecr:ResourceTag/purpose":"workload"}}}}
                """)));
        repository = new Repository();
        repository.getTags().put("purpose", "workload");
        when(ecr.registryRepository("image", ACCOUNT, REGION)).thenReturn(Optional.of(repository));
        clock = mock(Clock.class);
        started = Instant.parse("2026-11-19T00:00:00Z");
        when(clock.instant()).thenReturn(started);
        authorization = new EcrRegistryAuthorization(AccountAwareStorageBackend.inMemory(ACCOUNT),
                iam, new IamPolicyEvaluator(new ObjectMapper()), mock(RequestContext.class), config, clock);
        pulls = new EcrWorkloadImagePull(ecr, authorization);
        cachedPull = mock(Function.class);
    }

    @Test
    void currentPolicyIsCheckedBeforeCachedImageCanBeReturned() {
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
        assertThrows(AwsException.class, () -> pulls.withRolePull(IMAGE, ROLE, SOURCE, cachedPull));
        verifyNoInteractions(cachedPull);
    }

    @Test
    void transportGrantIsEvaluatedWithActualRegistryContextInsteadOfInventingIt() {
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of("""
                {"Statement":{"Effect":"Allow","Action":"ecr:*","Resource":"*",
                "Condition":{"Bool":{"aws:SecureTransport":"false"}}}}
                """)));
        assertEquals("image-id", pulls.withRolePull(IMAGE, ROLE, SOURCE, credential -> {
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header(credential), REGION);
            authorization.requirePullPermissions(identity, RESOURCE, null,
                    Map.of("aws:SecureTransport", List.of("false")));
            assertThrows(AwsException.class, () -> authorization.requirePullPermissions(
                    identity, RESOURCE, null, Map.of("aws:SecureTransport", List.of("true"))));
            return "image-id";
        }));
    }

    @Test
    void layerDenialIsCheckedEvenWhenDockerOnlyNeedsTheCachedImageManifest() {
        repository.setRepositoryPolicyText("""
                {"Statement":{"Effect":"Deny","Principal":"*","Action":"ecr:GetDownloadUrlForLayer","Resource":"*",
                "Condition":{"IpAddress":{"aws:SourceIp":"127.0.0.0/8"}}}}
                """);
        assertThrows(AwsException.class, () -> pulls.withRolePull(IMAGE, ROLE, SOURCE, credential -> {
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header(credential), REGION);
            Map<String, List<String>> context = Map.of("aws:SourceIp", List.of("127.0.0.1"),
                    "ecr:ResourceTag/purpose", List.of("workload"));
            authorization.requirePermission(identity, "ecr:BatchGetImage", RESOURCE,
                    repository.getRepositoryPolicyText(), context);
            authorization.requirePullPermissions(identity, RESOURCE, repository.getRepositoryPolicyText(), context);
            fail("Denied layer retrieval must not return a cached image");
            return "cached-image-id";
        }));
    }

    @Test
    void currentRepositoryDenialAndMissingRepositoryNeverEnterCache() {
        repository.setRepositoryPolicyText("""
                {"Statement":{"Effect":"Deny","Principal":"*","Action":"ecr:*","Resource":"*"}}
                """);
        assertThrows(AwsException.class, () -> pulls.withRolePull(IMAGE, ROLE, SOURCE, cachedPull));
        when(ecr.registryRepository("image", ACCOUNT, REGION)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(AwsException.class,
                () -> pulls.withRolePull(IMAGE, ROLE, SOURCE, cachedPull)).getHttpStatus());
        verifyNoInteractions(cachedPull);
    }

    @Test
    void currentTagsAreCheckedBeforeEveryCachedLaunch() {
        repository.getTags().put("purpose", "other");
        assertThrows(AwsException.class, () -> pulls.withRolePull(IMAGE, ROLE, SOURCE, cachedPull));
        verifyNoInteractions(cachedPull);
        repository.getTags().put("purpose", "workload");
        when(cachedPull.apply(any())).thenReturn("cached-image-id");
        assertEquals("cached-image-id", pulls.withRolePull(IMAGE, ROLE, SOURCE, cachedPull));
    }

    @Test
    void credentialIsRevokedAndClearedEvenIfPullFails() {
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<AuthConfig> retained = new AtomicReference<>();
        assertThrows(IllegalStateException.class, () -> pulls.withRolePull(IMAGE, ROLE, SOURCE, credentials -> {
            AuthConfig attempt = credentials.get();
            retained.set(attempt);
            header.set(header(attempt));
            assertNotNull(authorization.authenticate(header.get(), REGION));
            throw new IllegalStateException("Docker pull failed");
        }));
        assertNull(retained.get().getPassword());
        assertThrows(AwsException.class, () -> authorization.authenticate(header.get(), REGION));
    }

    @Test
    void configuredCredentialPathRemainsAvailableForNonEcrImages() {
        clearInvocations(iam);
        when(cachedPull.apply(null)).thenReturn("external-image");
        assertEquals("external-image", pulls.withRolePull("example.invalid/image:1", null, null, cachedPull));
        verifyNoInteractions(iam, ecr);
    }

    @Test
    void iamOffDefaultKeepsExistingPullPathWithoutInventingRole() {
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        clearInvocations(iam);
        when(cachedPull.apply(null)).thenReturn("existing-image");
        assertEquals("existing-image", pulls.withRolePull(IMAGE, null, null, cachedPull));
        verifyNoInteractions(iam, ecr);
    }

    @Test
    void emrServiceGrantChecksRepositoryBeforeCacheAndRevokesCredential() {
        String source = "arn:aws:emr-serverless:us-east-1:111111111111:/applications/app";
        repository.setRepositoryPolicyText("""
                {"Statement":{"Effect":"Allow","Principal":{"Service":"emr-serverless.amazonaws.com"},
                "Action":["ecr:BatchGetImage","ecr:GetDownloadUrlForLayer","ecr:DescribeImages"],"Resource":"*",
                "Condition":{"ArnEquals":{"aws:SourceArn":"%s"},"StringEquals":{"ecr:ResourceTag/purpose":"workload"}}}}
                """.formatted(source));
        AtomicReference<String> header = new AtomicReference<>();
        assertEquals("image-id", pulls.withEmrServerlessPull(IMAGE, source, credential -> {
            header.set(header(credential));
            assertNotNull(authorization.authenticate(header.get(), REGION));
            return "image-id";
        }));
        assertThrows(AwsException.class, () -> authorization.authenticate(header.get(), REGION));
        repository.setRepositoryPolicyText(null);
        assertThrows(AwsException.class, () -> pulls.withEmrServerlessPull(IMAGE, source, cachedPull));
        verifyNoInteractions(cachedPull);
    }

    @Test
    void emrDefaultAndExternalImagesNeedNoServiceIdentity() {
        when(cachedPull.apply(null)).thenReturn("image-id");
        assertEquals("image-id", pulls.withEmrServerlessPull("example.invalid/image:1", null, cachedPull));
        when(config.services().iam().enforcementEnabled()).thenReturn(false);
        assertEquals("image-id", pulls.withEmrServerlessPull(IMAGE, null, cachedPull));
        verifyNoInteractions(ecr);
    }

    @Test
    void roleCredentialRemainsUsableAfterWaitingBeyondLeaseExpiry() {
        assertEquals("image-id", pulls.withRolePull(IMAGE, ROLE, SOURCE, credential -> {
            when(clock.instant()).thenReturn(started.plusSeconds(301));
            assertNotNull(authorization.authenticate(header(credential), REGION));
            return "image-id";
        }));
    }

    @Test
    void emrCredentialRemainsUsableAfterWaitingBeyondLeaseExpiry() {
        String source = "arn:aws:emr-serverless:us-east-1:111111111111:/applications/app";
        repository.setRepositoryPolicyText("""
                {"Statement":{"Effect":"Allow","Principal":{"Service":"emr-serverless.amazonaws.com"},
                "Action":["ecr:BatchGetImage","ecr:GetDownloadUrlForLayer","ecr:DescribeImages"],"Resource":"*"}}
                """);
        assertEquals("image-id", pulls.withEmrServerlessPull(IMAGE, source, credential -> {
            when(clock.instant()).thenReturn(started.plusSeconds(301));
            assertNotNull(authorization.authenticate(header(credential), REGION));
            return "image-id";
        }));
    }

    @Test
    void eachAttemptReplacesAndRevokesItsCredentialAndEscapedSupplierCannotIssueAfterCompletion() {
        AtomicReference<Supplier<AuthConfig>> escaped = new AtomicReference<>();
        AtomicReference<String> lastHeader = new AtomicReference<>();
        assertEquals("image-id", pulls.withRolePull(IMAGE, ROLE, SOURCE, credentials -> {
            escaped.set(credentials);
            AuthConfig first = credentials.get();
            String firstHeader = header(first);
            assertNotNull(authorization.authenticate(firstHeader, REGION));
            AuthConfig second = credentials.get();
            assertNull(first.getPassword());
            assertThrows(AwsException.class, () -> authorization.authenticate(firstHeader, REGION));
            String secondHeader = header(second);
            assertNotNull(authorization.authenticate(secondHeader, REGION));
            when(clock.instant()).thenReturn(started.plusSeconds(301));
            AuthConfig third = credentials.get();
            assertNull(second.getPassword());
            lastHeader.set(header(third));
            assertNotNull(authorization.authenticate(lastHeader.get(), REGION));
            return "image-id";
        }));
        assertThrows(AwsException.class, () -> authorization.authenticate(lastHeader.get(), REGION));
        assertThrows(IllegalStateException.class, () -> escaped.get().get());
    }

    @Test
    void refreshRechecksRepositoryPolicyAndRevokesThePriorCredentialWhenDenied() {
        AtomicReference<String> header = new AtomicReference<>();
        AtomicReference<AuthConfig> issued = new AtomicReference<>();
        assertThrows(AwsException.class, () -> pulls.withRolePull(IMAGE, ROLE, SOURCE, credentials -> {
            AuthConfig first = credentials.get();
            issued.set(first);
            header.set(header(first));
            repository.setRepositoryPolicyText("""
                    {"Statement":{"Effect":"Deny","Principal":"*","Action":"ecr:*","Resource":"*"}}
                    """);
            credentials.get();
            return "must-not-launch";
        }));
        assertNull(issued.get().getPassword());
        assertThrows(AwsException.class, () -> authorization.authenticate(header.get(), REGION));
    }

    private static String header(Supplier<AuthConfig> credentials) {
        return header(credentials.get());
    }

    private static String header(AuthConfig credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (credentials.getUsername() + ":" + credentials.getPassword()).getBytes(StandardCharsets.UTF_8));
    }
}
