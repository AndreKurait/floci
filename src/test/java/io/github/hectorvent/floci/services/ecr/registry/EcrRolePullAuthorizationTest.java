package io.github.hectorvent.floci.services.ecr.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.AuthConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.PrincipalOrganization.OrganizationContext;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EcrRolePullAuthorizationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::111111111111:role/pull";
    private static final String SOURCE = "arn:aws:ecs:us-east-1:111111111111:task/cluster/task";
    private static final String REPOSITORY = "arn:aws:ecr:us-east-1:111111111111:repository/image";
    private static final String READ = """
            {"Statement":{"Effect":"Allow","Action":["ecr:BatchGetImage","ecr:GetDownloadUrlForLayer"],"Resource":"*"}}
            """;
    private IamService iam;
    private RequestContext request;
    private IamRole role;
    private MutableClock clock;
    private EcrRegistryAuthorization authorization;
    private AccountAwareStorageBackend<EcrRegistryAuthorization.TokenRecord> storage;

    @BeforeEach
    void setUp() {
        iam = mock(IamService.class);
        request = mock(RequestContext.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        role = new IamRole("AROAPULL", "pull", "/", ROLE, "{}");
        when(iam.findRole(ACCOUNT, "pull")).thenReturn(Optional.of(role));
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(READ)));
        clock = new MutableClock();
        storage = AccountAwareStorageBackend.inMemory(ACCOUNT);
        authorization = new EcrRegistryAuthorization(storage, iam,
                new IamPolicyEvaluator(new ObjectMapper()), request, config, clock);
    }

    @Test
    void scopedPullRefreshesOrganizationConditionsAfterMembershipChanges() {
        String policy = """
                {"Statement":{"Effect":"Allow","Action":["ecr:BatchGetImage","ecr:GetDownloadUrlForLayer"],
                "Resource":"*","Condition":{"StringEquals":{"aws:PrincipalOrgID":"o-example"}}}}
                """;
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(policy)));
        when(iam.organizationContextOf(ACCOUNT))
                .thenReturn(Optional.of(new OrganizationContext("o-example", ACCOUNT, "o-example/r-root/")));
        try (EcrRegistryAuthorization.PullLease lease = lease(null)) {
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header(lease), REGION);
            assertDoesNotThrow(() -> authorization.requirePullPermissions(identity, REPOSITORY, null, Map.of()));
            when(iam.organizationContextOf(ACCOUNT)).thenReturn(Optional.empty());
            assertEquals(403, assertThrows(AwsException.class,
                    () -> authorization.requirePullPermissions(identity, REPOSITORY, null, Map.of())).getHttpStatus());
        }
    }

    @Test
    void scopedCredentialIsEphemeralAndRevokedAfterPull() {
        String header;
        try (EcrRegistryAuthorization.PullLease lease = lease(null)) {
            assertEquals("localhost:4566", lease.credentials().getRegistryAddress());
            header = header(lease);
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header, REGION);
            assertEquals(ROLE, identity.caller().principalArn());
            assertEquals(SOURCE, identity.pullScope().sourceArn());
            assertDoesNotThrow(() -> authorization.requirePermission(identity,
                    "ecr:BatchGetImage", REPOSITORY, null));
            assertTrue(storage.isEmpty());
            verifyNoInteractions(request);
        }
        assertEquals(401, assertThrows(AwsException.class,
                () -> authorization.authenticate(header, REGION)).getHttpStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ecr:PutImage", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
            "ecr:CompleteLayerUpload", "ecr:ListImages"})
    void pullCredentialNeverGrantsWritesOrListingEvenWithBroadRolePolicy(String action) {
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(
                "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}")));
        try (EcrRegistryAuthorization.PullLease lease = lease(null)) {
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header(lease), REGION);
            assertEquals(403, assertThrows(AwsException.class,
                    () -> authorization.requirePermission(identity, action, REPOSITORY, null)).getHttpStatus());
        }
    }

    @Test
    void scopeRejectsOtherRepositoryAccountAndRegion() {
        try (EcrRegistryAuthorization.PullLease lease = lease(null)) {
            String header = header(lease);
            assertThrows(AwsException.class, () -> authorization.authenticate(header, "us-west-2"));
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header, REGION);
            for (String resource : List.of(REPOSITORY + "-other",
                    REPOSITORY.replace(ACCOUNT, "222222222222"), REPOSITORY.replace(REGION, "us-west-2"))) {
                assertThrows(AwsException.class,
                        () -> authorization.requirePermission(identity, "ecr:BatchGetImage", resource, null));
            }
        }
    }

    @Test
    void rejectsInsufficientPoliciesBeforeIssuingAnyCredential() {
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of("""
                {"Statement":{"Effect":"Allow","Action":"ecr:BatchGetImage","Resource":"*"}}
                """)));
        assertEquals(403, assertThrows(AwsException.class, () -> lease(null)).getHttpStatus());
        assertTrue(storage.isEmpty());
        verifyNoInteractions(request);
    }

    @Test
    void crossAccountRequiresIdentityAndRepositoryGrants() {
        String foreign = REPOSITORY.replace(ACCOUNT, "222222222222");
        String policy = "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"" + ROLE
                + "\"},\"Action\":\"ecr:*\",\"Resource\":\"*\"}}";
        assertThrows(AwsException.class, () -> issue(ROLE, SOURCE, foreign, null));
        try (EcrRegistryAuthorization.PullLease lease = issue(ROLE, SOURCE, foreign, policy)) {
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header(lease), REGION);
            assertDoesNotThrow(() -> authorization.requirePermission(identity, "ecr:BatchGetImage", foreign, policy));
            assertThrows(AwsException.class,
                    () -> authorization.requirePermission(identity, "ecr:BatchGetImage", foreign, null));
        }
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
        assertThrows(AwsException.class, () -> issue(ROLE, SOURCE, foreign, policy));
    }

    @Test
    void retainedIdentityRechecksCurrentPolicyBoundaryAndScp() {
        try (EcrRegistryAuthorization.PullLease lease = lease(null)) {
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header(lease), REGION);
            when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
            assertThrows(AwsException.class,
                    () -> authorization.requirePermission(identity, "ecr:BatchGetImage", REPOSITORY, null));
            when(iam.resolvePrincipalContext(ROLE)).thenReturn(new CallerContext(List.of(READ), null,
                    "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"s3:*\",\"Resource\":\"*\"}}"));
            assertThrows(AwsException.class,
                    () -> authorization.requirePermission(identity, "ecr:BatchGetImage", REPOSITORY, null));
            when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(READ)));
            when(iam.effectiveScpLevels(ACCOUNT)).thenReturn(List.of(List.of(
                    "{\"Statement\":{\"Effect\":\"Deny\",\"Action\":\"ecr:*\",\"Resource\":\"*\"}}")));
            assertThrows(AwsException.class,
                    () -> authorization.requirePermission(identity, "ecr:BatchGetImage", REPOSITORY, null));
        }
    }

    @Test
    void roleDeletionAndRecreationInvalidatePreviouslyIssuedCredential() {
        try (EcrRegistryAuthorization.PullLease lease = lease(null)) {
            String header = header(lease);
            when(iam.findRole(ACCOUNT, "pull")).thenReturn(Optional.empty());
            assertThrows(AwsException.class, () -> authorization.authenticate(header, REGION));
            when(iam.findRole(ACCOUNT, "pull")).thenReturn(Optional.of(role));
            role.setRoleId("AROARECREATED");
            assertThrows(AwsException.class, () -> authorization.authenticate(header, REGION));
        }
    }

    @Test
    void expiresAtFiveMinutesWithoutPersistingCredential() {
        try (EcrRegistryAuthorization.PullLease lease = lease(null)) {
            clock.now = clock.now.plusSeconds(300);
            assertThrows(AwsException.class, () -> authorization.authenticate(header(lease), REGION));
            assertTrue(storage.isEmpty());
        }
    }

    @Test
    void rejectedSourceAccountAndRolePathCannotUseAmbientRequestPrincipal() {
        assertThrows(AwsException.class, () -> issue(ROLE, SOURCE.replace(ACCOUNT, "222222222222"), REPOSITORY, null));
        assertThrows(AwsException.class, () -> issue(ROLE.replace("role/pull", "user/pull"), SOURCE, REPOSITORY, null));
        assertThrows(AwsException.class, () -> issue(ROLE.replace("role/pull", "role/other/pull"), SOURCE, REPOSITORY, null));
        verifyNoInteractions(request);
    }

    @Test
    void concurrentLeasesForDifferentPrincipalsDoNotShareCredentialOrRevocation() {
        String secondArn = ROLE.replace("role/pull", "role/second");
        IamRole second = new IamRole("AROASECOND", "second", "/", secondArn, "{}");
        when(iam.findRole(ACCOUNT, "second")).thenReturn(Optional.of(second));
        when(iam.resolvePrincipalContext(secondArn)).thenReturn(CallerContext.of(List.of(READ)));
        CompletableFuture<EcrRegistryAuthorization.PullLease> first = CompletableFuture.supplyAsync(() -> lease(null));
        CompletableFuture<EcrRegistryAuthorization.PullLease> other = CompletableFuture.supplyAsync(
                () -> issue(secondArn, SOURCE, REPOSITORY, null));
        try (EcrRegistryAuthorization.PullLease one = first.join();
             EcrRegistryAuthorization.PullLease two = other.join()) {
            String firstHeader = header(one);
            String secondHeader = header(two);
            assertNotEquals(firstHeader, secondHeader);
            assertEquals(ROLE, authorization.authenticate(firstHeader, REGION).caller().principalArn());
            assertEquals(secondArn, authorization.authenticate(secondHeader, REGION).caller().principalArn());
            one.close();
            assertThrows(AwsException.class, () -> authorization.authenticate(firstHeader, REGION));
            assertEquals(secondArn, authorization.authenticate(secondHeader, REGION).caller().principalArn());
        }
    }

    @Test
    void layerHeadRequiresDownloadPermissionRatherThanGrantingAnotherAction() {
        try (EcrRegistryAuthorization.PullLease lease = lease(null)) {
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header(lease), REGION);
            assertDoesNotThrow(() -> authorization.requirePermission(identity,
                    "ecr:BatchCheckLayerAvailability", REPOSITORY, null));
            when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of("""
                    {"Statement":{"Effect":"Allow","Action":"ecr:BatchGetImage","Resource":"*"}}
                    """)));
            assertThrows(AwsException.class,
                    () -> authorization.requirePermission(identity, "ecr:BatchCheckLayerAvailability", REPOSITORY, null));
        }
    }

    private EcrRegistryAuthorization.PullLease lease(String policy) {
        return issue(ROLE, SOURCE, REPOSITORY, policy);
    }

    private EcrRegistryAuthorization.PullLease issue(String principal, String source, String repository, String policy) {
        return authorization.issueRolePull(principal, source, repository, policy, Map.of(), "localhost:4566");
    }

    private static String header(EcrRegistryAuthorization.PullLease lease) {
        AuthConfig credentials = lease.credentials();
        return "Basic " + Base64.getEncoder().encodeToString(
                (credentials.getUsername() + ":" + credentials.getPassword()).getBytes(StandardCharsets.UTF_8));
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-19T18:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
