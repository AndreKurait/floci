package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import io.floci.aws.services.iam.ScpProvider;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.services.iam.model.IamRole;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ServiceRoleAlarmAuthorizerTest {
    private static final String ACCOUNT = "771661204010";
    private static final String OTHER = "771661204011";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/service-role/appconfig-monitor";
    private static final String ALARM = "arn:aws:cloudwatch:us-east-1:" + ACCOUNT + ":alarm:health";
    private static final String ACTION = "cloudwatch:DescribeAlarms";
    private static final String TRUST = """
            {"Statement":{"Effect":"Allow","Principal":{"Service":"appconfig.amazonaws.com"},
            "Action":"sts:AssumeRole"}}""";
    private static final String ALLOW = """
            {"Statement":{"Effect":"Allow","Action":"cloudwatch:DescribeAlarms","Resource":"*"}}""";
    private static final String DENY = ALLOW.replace("Allow", "Deny");

    private IamService iam;
    private IamRole role;
    private EmulatorConfig.IamServiceConfig config;
    private ResourcePolicyProvider provider;
    private ScpProvider scps;
    private ServiceRoleCallAuthorizer authorizer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EmulatorConfig emulator = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        config = emulator.services().iam();
        when(config.enforcementEnabled()).thenReturn(true);
        iam = mock(IamService.class);
        role = new IamRole();
        role.setArn(ROLE);
        role.setRoleName("appconfig-monitor");
        role.setAssumeRolePolicyDocument(TRUST);
        when(iam.findRole(ACCOUNT, role.getRoleName())).thenReturn(Optional.of(role));
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(ALLOW)));
        provider = mock(ResourcePolicyProvider.class);
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(ignored -> List.of(provider).iterator());
        scps = mock(ScpProvider.class);
        Instance<ScpProvider> scpInstance = mock(Instance.class);
        when(scpInstance.isResolvable()).thenReturn(true);
        when(scpInstance.get()).thenReturn(scps);
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
        authorizer = new ServiceRoleCallAuthorizer(emulator, iam,
                new AssumeRolePolicyEvaluator(new ObjectMapper(), evaluator), evaluator, providers, scpInstance);
    }

    @Test
    void metricPermissionRequiresTheExactAlarmAndActualStoredTags() {
        String policy = """
                {"Statement":{"Effect":"Allow","Action":"cloudwatch:DescribeAlarms","Resource":"%s",
                "Condition":{"StringEquals":{"aws:ResourceTag/team":"platform"}}}}""".formatted(ALARM);
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(policy)));
        assertEquals(Decision.ALLOW, metric(ALARM, Map.of("aws:ResourceTag/team", List.of("platform"))));
        assertEquals(Decision.DENY, metric(ALARM, Map.of()));
        assertEquals(Decision.DENY, metric(ALARM, Map.of("aws:ResourceTag/team", List.of("other"))));
        assertEquals(Decision.DENY, metric(ALARM + "-other", Map.of("aws:ResourceTag/team", List.of("platform"))));
        assertEquals(Decision.DENY, wildcard());
    }

    @Test
    void actualRoleAndRequestScopeReplaceConflictingResourceContextKeysWithoutMutatingTheInput() {
        String policy = """
                {"Statement":{"Effect":"Allow","Action":"cloudwatch:DescribeAlarms","Resource":"%s",
                "Condition":{"StringEquals":{"aws:PrincipalArn":"%s","aws:PrincipalType":"AssumedRole",
                "aws:PrincipalAccount":"%s","aws:ResourceAccount":"%s","aws:RequestedRegion":"us-east-1"},
                "Bool":{"aws:PrincipalIsAWSService":"false"},
                "Null":{"aws:SourceArn":"true","aws:SourceAccount":"true"}}}}"""
                .formatted(ALARM, ROLE, ACCOUNT, ACCOUNT);
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(policy)));
        Map<String, List<String>> supplied = Map.of(
                "aws:PrincipalArn", List.of(ROLE + "-other"),
                "aws:PrincipalType", List.of("User"),
                "aws:PrincipalAccount", List.of(OTHER),
                "aws:ResourceAccount", List.of(OTHER),
                "aws:RequestedRegion", List.of("us-west-2"),
                "aws:PrincipalIsAWSService", List.of("true"));
        assertEquals(Decision.ALLOW, metric(ALARM, supplied));
        assertEquals(List.of(OTHER), supplied.get("aws:PrincipalAccount"));
        assertEquals(List.of("us-west-2"), supplied.get("aws:RequestedRegion"));
    }

    @Test
    void onlyTheExplicitWildcardEntryPointAdmitsACompositeRead() {
        assertEquals(Decision.DENY, authorizer.authorizeWithoutSourceContext(
                "appconfig", ROLE, ACTION, "*", ACCOUNT, "aws"));
        assertEquals(Decision.DENY, authorizer.authorize(
                "appconfig", ROLE, ACTION, "*", ALARM, ACCOUNT));
        assertEquals(Decision.ALLOW, wildcard());
        verifyNoInteractions(provider);
        verify(scps).effectiveScpLevels(ACCOUNT);
    }

    @ParameterizedTest
    @CsvSource({"aws,us-east-1", "aws-cn,cn-north-1", "aws-us-gov,us-gov-west-1"})
    void wildcardRequiresTheOwnerRolePartitionAndCarriesTheRequestedRegion(String partition, String region) {
        String actualRole = ROLE.replace("arn:aws:", "arn:" + partition + ":");
        role.setArn(actualRole);
        String policy = """
                {"Statement":{"Effect":"Allow","Action":"cloudwatch:DescribeAlarms","Resource":"*",
                "Condition":{"StringEquals":{"aws:RequestedRegion":"%s","aws:PrincipalArn":"%s",
                "aws:ResourceAccount":"%s"}}}}""".formatted(region, actualRole, ACCOUNT);
        when(iam.resolvePrincipalContext(actualRole)).thenReturn(CallerContext.of(List.of(policy)));
        assertEquals(Decision.ALLOW, authorizer.authorizeUnscopedWithoutSourceContext(
                "appconfig", actualRole, ACTION, ACCOUNT, region));
        assertEquals(Decision.DENY, authorizer.authorizeUnscopedWithoutSourceContext(
                "appconfig", actualRole, ACTION, OTHER, region));
        for (String foreign : List.of("us-east-1", "cn-north-1", "us-gov-west-1")) {
            if (!foreign.equals(region)) {
                assertEquals(Decision.DENY, authorizer.authorizeUnscopedWithoutSourceContext(
                        "appconfig", actualRole, ACTION, ACCOUNT, foreign));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity-missing", "identity-deny", "boundary-deny", "boundary-missing",
            "scp-deny", "scp-missing", "wrong-trust", "source-required"})
    void metricAndWildcardBothRetainTrustIdentityBoundaryAndScpRefusals(String kind) {
        switch (kind) {
            case "identity-missing" -> when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
            case "identity-deny" -> when(iam.resolvePrincipalContext(ROLE))
                    .thenReturn(CallerContext.of(List.of(ALLOW, DENY)));
            case "boundary-deny" -> when(iam.resolvePrincipalContext(ROLE))
                    .thenReturn(new CallerContext(List.of(ALLOW), null, DENY));
            case "boundary-missing" -> when(iam.resolvePrincipalContext(ROLE))
                    .thenReturn(new CallerContext(List.of(ALLOW), null, ALLOW.replace(ACTION, "s3:GetObject")));
            case "scp-deny" -> when(scps.effectiveScpLevels(ACCOUNT)).thenReturn(List.of(List.of(ALLOW), List.of(DENY)));
            case "scp-missing" -> when(scps.effectiveScpLevels(ACCOUNT))
                    .thenReturn(List.of(List.of(ALLOW), List.of(ALLOW.replace(ACTION, "s3:GetObject"))));
            case "wrong-trust" -> role.setAssumeRolePolicyDocument(TRUST.replace("appconfig.", "lambda."));
            case "source-required" -> role.setAssumeRolePolicyDocument("""
                    {"Statement":{"Effect":"Allow","Principal":{"Service":"appconfig.amazonaws.com"},
                    "Action":"sts:AssumeRole","Condition":{"Null":{"aws:SourceArn":"false"}}}}""");
            default -> throw new IllegalArgumentException(kind);
        }
        assertEquals(Decision.DENY, metric(ALARM, Map.of()));
        assertEquals(Decision.DENY, wildcard());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "arn:aws:iam::771661204010:role/other/appconfig-monitor",
            "arn:aws:iam:us-east-1:771661204010:role/service-role/appconfig-monitor",
            "arn:aws:sts::771661204010:assumed-role/appconfig-monitor/session"})
    void neitherEntryPointAcceptsAnUnresolvedOrWrongPathRole(String requested) {
        assertEquals(Decision.DENY, authorizer.authorizeWithoutSourceContext(
                "appconfig", requested, ACTION, ALARM, ACCOUNT, "aws", Map.of()));
        assertEquals(Decision.DENY, authorizer.authorizeUnscopedWithoutSourceContext(
                "appconfig", requested, ACTION, ACCOUNT, "us-east-1"));
        verify(iam, never()).resolvePrincipalContext(anyString());
    }

    @Test
    void disabledIamRetainsTheExistingBypassWithoutResolvingRoleOrAlarmPolicies() {
        when(config.enforcementEnabled()).thenReturn(false);
        assertEquals(Decision.ALLOW, authorizer.authorizeWithoutSourceContext(
                "appconfig", "missing", ACTION, "missing", ACCOUNT, "aws", Map.of()));
        assertEquals(Decision.ALLOW, authorizer.authorizeUnscopedWithoutSourceContext(
                "appconfig", "missing", ACTION, ACCOUNT, "us-east-1"));
        verifyNoInteractions(iam, provider, scps);
    }

    private Decision metric(String alarm, Map<String, List<String>> context) {
        return authorizer.authorizeWithoutSourceContext("appconfig", ROLE, ACTION, alarm, ACCOUNT, "aws", context);
    }

    private Decision wildcard() {
        return authorizer.authorizeUnscopedWithoutSourceContext("appconfig", ROLE, ACTION, ACCOUNT, "us-east-1");
    }
}
