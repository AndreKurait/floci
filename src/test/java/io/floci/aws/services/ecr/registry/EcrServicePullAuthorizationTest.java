package io.floci.aws.services.ecr.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EcrServicePullAuthorizationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String SOURCE = "arn:aws:emr-serverless:us-east-1:111111111111:/applications/app";
    private static final String REPOSITORY = "arn:aws:ecr:us-east-1:111111111111:repository/image";
    private static final String POLICY = """
            {"Statement":{"Effect":"Allow","Principal":{"Service":"emr-serverless.amazonaws.com"},
            "Action":["ecr:BatchGetImage","ecr:GetDownloadUrlForLayer","ecr:DescribeImages"],"Resource":"*",
            "Condition":{"ArnEquals":{"aws:SourceArn":"%s"},"StringEquals":{"aws:SourceAccount":"111111111111"},
            "Bool":{"aws:PrincipalIsAWSService":"true"}}}}
            """.formatted(SOURCE);
    private EcrRegistryAuthorization authorization;
    private IamService iam;
    private RequestContext request;
    private AccountAwareStorageBackend<EcrRegistryAuthorization.TokenRecord> storage;

    @BeforeEach
    void setUp() {
        iam = mock(IamService.class);
        request = mock(RequestContext.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        storage = AccountAwareStorageBackend.inMemory(ACCOUNT);
        authorization = authorization(config, Clock.systemUTC());
    }

    @Test
    void typedServiceGrantUsesApplicationSourceWithoutAnIamRoleOrPersistentToken() {
        try (EcrRegistryAuthorization.PullLease lease = issue(SOURCE, REPOSITORY, POLICY, Map.of())) {
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header(lease), REGION);
            assertEquals("emr-serverless.amazonaws.com", identity.pullScope().servicePrincipal());
            assertEquals(SOURCE, identity.pullScope().sourceArn());
            assertNull(identity.caller());
            assertNull(identity.policies());
            for (String action : List.of("ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer",
                    "ecr:BatchCheckLayerAvailability", "ecr:DescribeImages")) {
                assertDoesNotThrow(() -> authorization.requirePermission(identity, action, REPOSITORY, POLICY));
            }
            assertTrue(storage.isEmpty());
            verifyNoInteractions(iam, request);
        }
    }

    @Test
    void serviceGrantCanNameCrossAccountRepositoryButCannotBorrowSourceAccount() {
        String foreignRepository = REPOSITORY.replace(ACCOUNT, "222222222222");
        try (EcrRegistryAuthorization.PullLease lease = issue(SOURCE, foreignRepository, POLICY, Map.of())) {
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header(lease), REGION);
            assertDoesNotThrow(() -> authorization.requirePermission(identity,
                    "ecr:BatchGetImage", foreignRepository, POLICY));
        }
        String foreignSource = SOURCE.replace(ACCOUNT, "222222222222");
        assertThrows(AwsException.class, () -> issue(foreignSource, foreignRepository, POLICY, Map.of()));
    }

    @Test
    void currentRepositoryPolicyAndTagsAreRecheckedForRetainedIdentity() {
        String taggedPolicy = POLICY.replace("\"Bool\":", """
                "StringLike":{"ecr:ResourceTag/purpose":"workload"},"Bool":
                """);
        try (EcrRegistryAuthorization.PullLease lease = issue(SOURCE, REPOSITORY, taggedPolicy,
                Map.of("ecr:ResourceTag/purpose", List.of("workload")))) {
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header(lease), REGION);
            assertThrows(AwsException.class, () -> authorization.requirePermission(identity,
                    "ecr:BatchGetImage", REPOSITORY, null));
            assertThrows(AwsException.class, () -> authorization.requirePermission(identity,
                    "ecr:BatchGetImage", REPOSITORY, taggedPolicy,
                    Map.of("ecr:ResourceTag/purpose", List.of("other"))));
        }
    }

    @Test
    void wrongPrincipalAndMissingReadActionCannotIssueCredential() {
        for (String policy : List.of(
                POLICY.replace("emr-serverless.amazonaws.com", "ops.emr-serverless.amazonaws.com"),
                POLICY.replace("emr-serverless.amazonaws.com", "lambda.amazonaws.com"),
                POLICY.replace("ecr:DescribeImages", "ecr:ListImages"),
                POLICY.replace("ecr:GetDownloadUrlForLayer", "ecr:ListImages"),
                POLICY.replace("ecr:BatchGetImage", "ecr:ListImages"),
                "{\"Statement\":{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"arn:aws:iam::111111111111:role/job\"},"
                        + "\"Action\":\"ecr:*\",\"Resource\":\"*\"}}")) {
            assertThrows(AwsException.class, () -> issue(SOURCE, REPOSITORY, policy, Map.of()));
        }
        assertThrows(AwsException.class, () -> issue(SOURCE, REPOSITORY, null, Map.of()));
        verifyNoInteractions(iam, request);
    }

    @Test
    void explicitServiceDenyOverridesAllow() {
        String policy = """
                {"Statement":[{"Effect":"Allow","Principal":{"Service":"emr-serverless.amazonaws.com"},
                "Action":"ecr:*","Resource":"*"},{"Effect":"Deny","Principal":"*",
                "Action":"ecr:GetDownloadUrlForLayer","Resource":"*"}]}
                """;
        assertThrows(AwsException.class, () -> issue(SOURCE, REPOSITORY, policy, Map.of()));
    }

    @Test
    void observedIamPrincipalKeysCannotMasqueradeAsServiceContext() {
        String policy = POLICY.replace("\"Bool\":", """
                "ArnLike":{"aws:PrincipalArn":"arn:aws:iam::*:role/admin"},"Bool":
                """);
        assertThrows(AwsException.class, () -> issue(SOURCE, REPOSITORY, policy,
                Map.of("aws:PrincipalArn", List.of("arn:aws:iam::111111111111:role/admin"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ecr:PutImage", "ecr:UploadLayerPart", "ecr:ListImages", "ecr:GetAuthorizationToken"})
    void issuedCredentialCannotWriteListOrIssueExternalTokens(String action) {
        try (EcrRegistryAuthorization.PullLease lease = issue(SOURCE, REPOSITORY, POLICY, Map.of())) {
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header(lease), REGION);
            assertThrows(AwsException.class,
                    () -> authorization.requirePermission(identity, action, REPOSITORY, POLICY));
        }
    }

    @Test
    void scopeCannotBeRetargetedAndCredentialIsRevokedOnClose() {
        String header;
        try (EcrRegistryAuthorization.PullLease lease = issue(SOURCE, REPOSITORY, POLICY, Map.of())) {
            header = header(lease);
            assertThrows(AwsException.class, () -> authorization.authenticate(header, "us-west-2"));
            EcrRegistryAuthorization.Identity identity = authorization.authenticate(header, REGION);
            for (String other : List.of(REPOSITORY + "-other", REPOSITORY.replace(ACCOUNT, "222222222222"))) {
                assertThrows(AwsException.class,
                        () -> authorization.requirePermission(identity, "ecr:BatchGetImage", other, POLICY));
            }
        }
        assertThrows(AwsException.class, () -> authorization.authenticate(header, REGION));
    }

    @Test
    void credentialExpiresAfterFiveMinutes() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        Clock issued = Clock.fixed(Instant.parse("2026-11-19T00:00:00Z"), ZoneOffset.UTC);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(issued.instant());
        authorization = authorization(config, clock);
        try (EcrRegistryAuthorization.PullLease lease = issue(SOURCE, REPOSITORY, POLICY, Map.of())) {
            when(clock.instant()).thenReturn(issued.instant().plusSeconds(300));
            assertThrows(AwsException.class, () -> authorization.authenticate(header(lease), REGION));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "arn:aws:s3:::image", "arn:aws:emr-serverless:us-east-1:111111111111:/applications/",
            "arn:aws:emr-serverless:us-east-1:111111111111:/applications/app/jobruns/run",
            "arn:aws:emr-serverless:us-west-2:111111111111:/applications/app",
            "arn:aws-cn:emr-serverless:cn-north-1:111111111111:/applications/app"})
    void onlySameRegionApplicationArnCanIssueServiceCredential(String source) {
        String unconditional = """
                {"Statement":{"Effect":"Allow","Principal":{"Service":"emr-serverless.amazonaws.com"},
                "Action":["ecr:BatchGetImage","ecr:GetDownloadUrlForLayer","ecr:DescribeImages"],"Resource":"*"}}
                """;
        try (EcrRegistryAuthorization.PullLease lease = issue(SOURCE, REPOSITORY, unconditional, Map.of())) {
            assertNotNull(authorization.authenticate(header(lease), REGION));
        }
        assertThrows(AwsException.class, () -> issue(source, REPOSITORY, unconditional, Map.of()));
    }

    private EcrRegistryAuthorization authorization(EmulatorConfig config, Clock clock) {
        return new EcrRegistryAuthorization(storage, iam, new IamPolicyEvaluator(new ObjectMapper()),
                request, config, clock);
    }

    private EcrRegistryAuthorization.PullLease issue(String source, String resource, String policy,
                                                     Map<String, List<String>> context) {
        return authorization.issueEmrServerlessPull(source, resource, policy, context, "localhost:4566");
    }

    private static String header(EcrRegistryAuthorization.PullLease lease) {
        return "Basic " + Base64.getEncoder().encodeToString(
                ("AWS:" + lease.credentials().getPassword()).getBytes(StandardCharsets.UTF_8));
    }
}
