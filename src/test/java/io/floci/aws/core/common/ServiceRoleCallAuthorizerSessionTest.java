package io.floci.aws.core.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import io.floci.aws.services.iam.ScpProvider;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.iam.model.SessionCredential;
import io.floci.aws.testing.StorageFactories;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceRoleCallAuthorizerSessionTest {
    private static final String ACCOUNT = "111122223333";
    private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
    private static final String ACTION = "elasticloadbalancing:RegisterInstancesWithLoadBalancer";
    private static final String RESOURCE = "arn:aws:elasticloadbalancing:us-east-1:111122223333:loadbalancer/frontend";
    private static final String ALLOW = """
            {"Version":"2012-10-17","Statement":{"Effect":"Allow","Action":"*","Resource":"*"}}
            """;

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"allow", "identity-deny", "boundary-deny", "scp-deny"})
    @SuppressWarnings("unchecked")
    void realIssuedSessionCarriesIdentityAndIsRetired(String scenario) {
        EmulatorConfig config = StorageFactories.config(directory, ACCOUNT);
        when(config.services().iam().enforcementEnabled()).thenReturn(true);
        StorageFactory factory = StorageFactories.open(config, "memory");
        try {
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            IamService iam = spy(new IamService(factory, config, new RegionResolver("us-east-1", ACCOUNT), clock));
            String trust = """
                    {"Version":"2012-10-17","Statement":{"Effect":"Allow",
                    "Principal":{"Service":"codedeploy.amazonaws.com"},"Action":"sts:AssumeRole",
                    "Condition":{"DateEquals":{"aws:CurrentTime":"2026-11-20T00:00:00Z"}}}}
                    """;
            IamRole role = iam.createRole("deployment", "/service-role/", trust, null, 3600, null);
            String policy = """
                    {"Version":"2012-10-17","Statement":{"Effect":"Allow","Action":"*","Resource":"*",
                    "Condition":{"StringLike":{"aws:userid":"%s:floci-service-*"},
                    "DateEquals":{"aws:TokenIssueTime":"2026-11-20T00:00:00Z"},
                    "ArnEquals":{"aws:PrincipalArn":"%s"}}}}
                    """.formatted(role.getRoleId(), role.getArn());
            iam.putRolePolicy("deployment", "call", scenario.equals("identity-deny")
                    ? ALLOW.replace("Allow", "Deny") : policy);
            if (scenario.equals("boundary-deny")) {
                String boundary = iam.createPolicy("boundary", "/", null,
                        ALLOW.replace("Allow", "Deny"), Map.of()).getArn();
                iam.putRolePermissionsBoundary("deployment", boundary);
            }
            Instance<ResourcePolicyProvider> providers = mock(Instance.class);
            when(providers.iterator()).thenAnswer(ignored -> List.<ResourcePolicyProvider>of().iterator());
            Instance<ScpProvider> scps = mock(Instance.class);
            ScpProvider scp = mock(ScpProvider.class);
            when(scps.isResolvable()).thenReturn(true);
            when(scps.get()).thenReturn(scp);
            when(scp.effectiveScpLevels(ACCOUNT)).thenReturn(List.of(List.of(
                    scenario.equals("scp-deny") ? ALLOW.replace("Allow", "Deny") : ALLOW)));
            IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
            ServiceRoleCallAuthorizer authorizer = new ServiceRoleCallAuthorizer(config, iam,
                    new AssumeRolePolicyEvaluator(new ObjectMapper(), evaluator), evaluator, providers, scps, clock);
            assertEquals(scenario.equals("allow") ? Decision.ALLOW : Decision.DENY,
                    authorizer.authorizeWithoutSourceContext("codedeploy", role.getArn(), ACTION, RESOURCE,
                            ACCOUNT, "aws"));
            ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
            verify(iam).resolveCallerContext(key.capture(), any(Instant.class));
            assertTrue(iam.resolveCallerArns(key.getValue()).isEmpty());
            AccountAwareStorageBackend<SessionCredential> sessions = factory.create(
                    "iam", "iam-sessions.json", new TypeReference<>() {});
            assertTrue(sessions.getForAccount(ACCOUNT, key.getValue()).isEmpty());
            verify(iam).unregisterSession(anyString(), anyString());
        } finally {
            factory.shutdownAll();
        }
    }
}
