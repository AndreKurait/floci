package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceRoleCallAuthorizerTest {

    private static final String ACCOUNT = "111122223333";
    private static final String OTHER = "444455556666";
    private static final String ROLE = "arn:aws:iam::" + ACCOUNT + ":role/service-role/events";
    private static final String RULE = "arn:aws:events:us-east-1:" + ACCOUNT + ":rule/bus/orders";
    private static final String FUNCTION = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:receiver:live";
    private static final String INVOKE = "lambda:InvokeFunction";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}";
    private static final String DENY = ALLOW.replace("Allow", "Deny");
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":{"Effect":"Allow",
            "Principal":{"Service":"events.amazonaws.com"},"Action":"sts:AssumeRole",
            "Condition":{"ArnEquals":{"aws:SourceArn":"%s"},
            "StringEquals":{"aws:SourceAccount":"%s"}}}}""".formatted(RULE, ACCOUNT);

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
        role.setRoleName("events");
        role.setAssumeRolePolicyDocument(TRUST);
        when(iam.findRole(ACCOUNT, "events")).thenReturn(Optional.of(role));
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(ALLOW)));
        provider = mock(ResourcePolicyProvider.class);
        Instance<ResourcePolicyProvider> providers = mock(Instance.class);
        when(providers.iterator()).thenAnswer(invocation -> List.of(provider).iterator());
        scps = mock(ScpProvider.class);
        Instance<ScpProvider> scpInstance = mock(Instance.class);
        when(scpInstance.isResolvable()).thenReturn(true);
        when(scpInstance.get()).thenReturn(scps);
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
        authorizer = new ServiceRoleCallAuthorizer(emulator, iam,
                new AssumeRolePolicyEvaluator(new ObjectMapper(), evaluator), evaluator, providers, scpInstance);
    }

    @ParameterizedTest
    @CsvSource({"aws,us-east-1,aws,us-west-2", "aws,us-east-1,aws-cn,cn-north-1",
            "aws,us-east-1,aws-us-gov,us-gov-west-1", "aws-cn,cn-north-1,aws,us-east-1",
            "aws-cn,cn-north-1,aws-cn,cn-northwest-1", "aws-cn,cn-north-1,aws-us-gov,us-gov-west-1",
            "aws-us-gov,us-gov-west-1,aws,us-east-1", "aws-us-gov,us-gov-west-1,aws-cn,cn-north-1",
            "aws-us-gov,us-gov-west-1,aws-us-gov,us-gov-east-1"})
    void sourceContextRequiresTargetInTheSamePartition(String sourcePartition, String sourceRegion,
                                                       String targetPartition, String targetRegion) {
        String executionRole = ROLE.replace("arn:aws:", "arn:" + sourcePartition + ":");
        String source = RULE.replace("arn:aws:", "arn:" + sourcePartition + ":")
                .replace("us-east-1", sourceRegion);
        String target = FUNCTION.replace("arn:aws:", "arn:" + targetPartition + ":")
                .replace("us-east-1", targetRegion);
        role.setArn(executionRole);
        role.setAssumeRolePolicyDocument(TRUST.replace(RULE, source));
        when(iam.resolvePrincipalContext(executionRole)).thenReturn(CallerContext.of(List.of(ALLOW)));
        assertEquals(sourcePartition.equals(targetPartition) ? Decision.ALLOW : Decision.DENY,
                authorizer.authorize("events", executionRole, INVOKE, target, source, ACCOUNT));
    }

    @Test
    void sourceContextPreservesCrossPartitionBypassWhenIamIsDisabled() {
        when(config.enforcementEnabled()).thenReturn(false);
        assertEquals(Decision.ALLOW, authorizer.authorize("events", ROLE, INVOKE,
                FUNCTION.replace("arn:aws:", "arn:aws-cn:"), RULE, ACCOUNT));
        verifyNoInteractions(iam);
    }

    private Decision decide(String target) {
        return authorizer.authorize("events", ROLE, INVOKE, target, RULE, ACCOUNT);
    }

    @Test
    void trustedRoleIdentityAllowsSameAccountWithoutResourceGrant() {
        assertEquals(Decision.ALLOW, decide(FUNCTION));
        verify(scps).effectiveScpLevels(ACCOUNT);
    }

    @Test
    void disabledIamDoesNotResolveRoles() {
        when(config.enforcementEnabled()).thenReturn(false);
        assertEquals(Decision.ALLOW, authorizer.authorize("events", "missing", INVOKE, FUNCTION, RULE, ACCOUNT));
        verifyNoInteractions(iam);
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-service", "wrong-rule", "wrong-account", "explicit-deny", "missing"})
    void trustRefusesWrongPrincipalSourceOrDeny(String change) {
        role.setAssumeRolePolicyDocument(switch (change) {
            case "wrong-service" -> TRUST.replace("events.amazonaws.com", "lambda.amazonaws.com");
            case "wrong-rule" -> TRUST.replace(RULE, RULE + "-other");
            case "wrong-account" -> TRUST.replace(ACCOUNT, OTHER);
            case "explicit-deny" -> TRUST.replace("Allow", "Deny");
            default -> null;
        });
        assertEquals(Decision.DENY, decide(FUNCTION));
        verify(iam, never()).resolvePrincipalContext(ROLE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "arn:aws:iam::111122223333:role/other/events",
            "arn:aws:iam::444455556666:role/service-role/events",
            "arn:aws:sts::111122223333:assumed-role/events/session"})
    void invalidForeignOrWrongPathRoleCannotBeAssumed(String requested) {
        assertEquals(Decision.DENY,
                authorizer.authorize("events", requested, INVOKE, FUNCTION, RULE, ACCOUNT));
    }

    @Test
    void removedRoleIsDenied() {
        when(iam.findRole(ACCOUNT, "events")).thenReturn(Optional.empty());
        assertEquals(Decision.DENY, decide(FUNCTION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity-missing", "identity-deny", "boundary-deny", "boundary-missing", "scp-deny", "scp-missing"})
    void identityBoundaryAndEachScpLevelConstrainRole(String kind) {
        switch (kind) {
            case "identity-missing" -> when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
            case "identity-deny" -> when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(ALLOW, DENY)));
            case "boundary-deny" -> when(iam.resolvePrincipalContext(ROLE))
                    .thenReturn(new CallerContext(List.of(ALLOW), null, DENY));
            case "boundary-missing" -> when(iam.resolvePrincipalContext(ROLE))
                    .thenReturn(new CallerContext(List.of(ALLOW), null, ALLOW.replace("\"*\"", "\"s3:GetObject\"")));
            case "scp-deny" -> when(scps.effectiveScpLevels(ACCOUNT)).thenReturn(List.of(List.of(ALLOW), List.of(DENY)));
            default -> when(scps.effectiveScpLevels(ACCOUNT))
                    .thenReturn(List.of(List.of(ALLOW), List.of(ALLOW.replace("\"*\"", "\"s3:GetObject\""))));
        }
        assertEquals(Decision.DENY, decide(FUNCTION));
    }

    @Test
    void crossAccountRequiresBothIdentityAndQualifiedResourceGrant() {
        String target = FUNCTION.replace(ACCOUNT, OTHER);
        assertEquals(Decision.DENY, decide(target));
        resourceGrant(target, target, "Allow");
        assertEquals(Decision.ALLOW, decide(target));
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of()));
        assertEquals(Decision.DENY, decide(target));
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(ALLOW)));
        resourceGrant(target, target.replace(":live", ""), "Allow");
        assertEquals(Decision.DENY, decide(target));
        resourceGrant(target, target, "Deny");
        assertEquals(Decision.DENY, decide(target));
    }

    @Test
    void roleCallHasRoleContextAndDoesNotInheritServiceSourceKeys() {
        String conditions = """
                {"Statement":{"Effect":"Allow","Action":"*","Resource":"*","Condition":{
                "StringEquals":{"aws:PrincipalArn":"%s","aws:PrincipalType":"AssumedRole",
                "aws:ResourceAccount":"%s","aws:RequestedRegion":"us-east-1"},
                "Bool":{"aws:PrincipalIsAWSService":"false"},"Null":{"aws:SourceArn":"true"}}}}"""
                .formatted(ROLE, ACCOUNT);
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(conditions)));
        assertEquals(Decision.ALLOW, decide(FUNCTION));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void notPrincipalExclusionOnlySparesRolesWithoutBoundary(boolean attached) {
        role.setPermissionsBoundaryArn(attached ? "arn:aws:iam::" + ACCOUNT + ":policy/limit" : null);
        when(iam.resolvePrincipalContext(ROLE))
                .thenReturn(new CallerContext(List.of(ALLOW), null, attached ? ALLOW : null));
        denyExceptAccountAndRole();
        assertEquals(attached ? Decision.DENY : Decision.ALLOW, decide(FUNCTION));
    }

    @Test
    void boundaryAttachmentStillAppliesWhenBoundaryDocumentIsUnavailable() {
        role.setPermissionsBoundaryArn("arn:aws:iam::" + ACCOUNT + ":policy/limit");
        denyExceptAccountAndRole();
        assertEquals(Decision.DENY, decide(FUNCTION));
    }

    private void denyExceptAccountAndRole() {
        String policy = """
                {"Statement":{"Effect":"Deny","Action":"lambda:InvokeFunction","Resource":"%s",
                "NotPrincipal":{"AWS":["arn:aws:iam::%s:root","%s"]}}}"""
                .formatted(FUNCTION, ACCOUNT, ROLE);
        when(provider.getResourcePolicies("lambda", FUNCTION))
                .thenReturn(List.of(new ResourcePolicyProvider.ResourcePolicy(policy, ACCOUNT)));
    }

    private void resourceGrant(String target, String permitted, String effect) {
        String policy = """
                {"Statement":{"Effect":"%s","Action":"lambda:InvokeFunction",
                "Resource":"%s","Principal":{"AWS":"%s"}}}""".formatted(effect, permitted, ROLE);
        when(provider.getResourcePolicies("lambda", target))
                .thenReturn(List.of(new ResourcePolicyProvider.ResourcePolicy(policy, OTHER)));
    }
}
