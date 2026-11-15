package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.cloudformation.GetStackOutputRoleAuthorizer.ExecutionIdentity;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CloudFormationCapturedCallerPolicyTest {
    private static final String ACCOUNT = "123456789012";
    private static final String ROLE = "arn:aws:iam::123456789012:role/cfn";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}";
    private static final String SCP = "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"},"
            + "{\"Effect\":\"Deny\",\"Action\":\"s3:DeleteBucket\",\"Resource\":\"*\"}]}";
    private final IamService iam = mock(IamService.class);
    private final RequestContext request = mock(RequestContext.class);
    private final ServiceRoleAuthorizer roles = mock(ServiceRoleAuthorizer.class);
    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
    private final GetStackOutputRoleAuthorizer authorizer = new GetStackOutputRoleAuthorizer(iam,
            mock(AssumeRolePolicyEvaluator.class), evaluator, mock(EmulatorConfig.class), roles, request, null);

    @Test
    void verifiedCallerRetainsEffectiveScpAfterLeavingRequestAndOriginalListChanges() {
        List<String> level = new ArrayList<>(List.of(SCP));
        when(iam.effectiveScpLevels(ACCOUNT)).thenReturn(List.of(level));
        when(request.getAccessKeyId()).thenReturn("verified-key");
        when(request.isSignatureVerifiedFor("verified-key")).thenReturn(true);
        when(iam.resolveCallerArns("verified-key")).thenReturn(Optional.of(
                CallerArns.accountRoot("arn:aws:iam::123456789012:root", ACCOUNT)));
        when(iam.resolveCallerContext("verified-key")).thenReturn(CallerContext.of(List.of(ALLOW)));
        ExecutionIdentity captured = authorizer.captureCaller(ACCOUNT, "aws");
        level.clear();
        CloudFormationExecutionScope.run(captured, () -> assertScpApplied(
                CloudFormationExecutionScope.current().orElseThrow().policies()));
        assertTrue(CloudFormationExecutionScope.current().isEmpty());
    }

    @Test
    void selectedExecutionRoleRetainsItsAccountScpCeiling() {
        when(iam.effectiveScpLevels(ACCOUNT)).thenReturn(List.of(List.of(SCP)));
        when(roles.refusal(anyString(), anyString(), any(), anyString(), anyList())).thenReturn(Optional.empty());
        IamRole role = new IamRole("role-id", "cfn", "/", ROLE, "{}");
        when(iam.findRole(ACCOUNT, "cfn")).thenReturn(Optional.of(role));
        when(iam.resolvePrincipalContext(ROLE)).thenReturn(CallerContext.of(List.of(ALLOW)));
        ExecutionIdentity caller = new ExecutionIdentity(
                CallerArns.accountRoot("arn:aws:iam::123456789012:root", ACCOUNT),
                CallerContext.of(List.of(ALLOW)), true);
        ExecutionIdentity selected = authorizer.executionIdentity(ROLE, ACCOUNT,
                "arn:aws:cloudformation:us-east-1:123456789012:stack/test/id", "aws", caller);
        assertTrue(selected.signatureVerified());
        assertScpApplied(selected.policies());
        verify(iam).effectiveScpLevels(ACCOUNT);
    }

    private void assertScpApplied(CallerContext policies) {
        assertEquals(List.of(List.of(SCP)), policies.scpLevels());
        assertEquals(IamPolicyEvaluator.Decision.DENY,
                evaluator.evaluate(policies, null, "s3:DeleteBucket", "arn:aws:s3:::bucket", Map.of()));
        assertEquals(IamPolicyEvaluator.Decision.ALLOW,
                evaluator.evaluate(policies, null, "s3:ListBucket", "arn:aws:s3:::bucket", Map.of()));
    }
}
