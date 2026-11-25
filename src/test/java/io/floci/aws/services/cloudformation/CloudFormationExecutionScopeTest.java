package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.cloudformation.CloudFormationExecutionScope.Identity;
import io.floci.aws.services.cloudformation.provisioners.ProvisionContext;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.IamService.CallerArns;
import io.floci.aws.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudFormationExecutionScopeTest {
    private static CallerArns caller(String account) {
        String arn = "arn:aws:iam::" + account + ":user/operator";
        return new CallerArns(arn, arn, "User", "user-id", "operator", false);
    }

    @Test
    void scopeRestoresNestedCallerAndClearsAfterFailure() {
        Identity identity = new Identity(caller("123456789012"), CallerContext.of(List.of("policy")));
        assertThrows(IllegalStateException.class, () -> CloudFormationExecutionScope.run(identity, () -> {
            ProvisionContext context = new ProvisionContext(null, "us-west-2", "123456789012", "stack");
            assertSame(identity, context.executionIdentity().orElseThrow());
            CloudFormationExecutionScope.run(null, () -> assertTrue(context.executionIdentity().isEmpty()));
            assertSame(identity, context.withPriorPhysicalId("old").executionIdentity().orElseThrow());
            throw new IllegalStateException("operation failed");
        }));
        assertTrue(CloudFormationExecutionScope.current().isEmpty());
    }

    @Test
    void identityCopiesMutablePoliciesAndServiceControlPolicies() {
        List<String> policies = new ArrayList<>(List.of("allow"));
        List<String> level = new ArrayList<>(List.of("scp"));
        Identity identity = new Identity(caller("123456789012"),
                new CallerContext(policies, "session", "boundary", List.of(level)));
        policies.clear();
        level.clear();
        assertEquals(List.of("allow"), identity.policies().identityPolicies());
        assertEquals(List.of(List.of("scp")), identity.policies().scpLevels());
        assertEquals("session", identity.policies().sessionPolicyDocument());
        assertEquals("boundary", identity.policies().boundaryPolicyDocument());
    }

    @Test
    void executorThreadDoesNotInheritOrRetainCaller() throws Exception {
        Identity identity = new Identity(caller("123456789012"), CallerContext.of(List.of()));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CloudFormationExecutionScope.run(identity, () -> {
                try {
                    assertTrue(executor.submit(() -> CloudFormationExecutionScope.current().isEmpty()).get());
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            executor.submit(() -> CloudFormationExecutionScope.run(identity,
                    () -> assertSame(identity, CloudFormationExecutionScope.current().orElseThrow()))).get();
            assertTrue(executor.submit(() -> CloudFormationExecutionScope.current().isEmpty()).get());
        }
    }

    @Test
    void captureRejectsUnverifiedAndCrossAccountCallers() {
        RequestContext request = mock(RequestContext.class);
        IamService iam = mock(IamService.class);
        GetStackOutputRoleAuthorizer authorizer = new GetStackOutputRoleAuthorizer(iam, null, null, null, request);
        when(request.getAccessKeyId()).thenReturn("key");
        assertNull(authorizer.captureCaller());
        verifyNoInteractions(iam);
        when(request.isSignatureVerifiedFor("key")).thenReturn(true);
        when(request.getAccountId()).thenReturn("123456789012");
        when(iam.resolveCallerArns("key")).thenReturn(Optional.of(caller("999999999999")));
        when(iam.resolveCallerContext("key")).thenReturn(CallerContext.of(List.of()));
        assertNull(authorizer.captureCaller());
    }

    @Test
    void captureKeepsVerifiedCallerButNeverInventsRootPolicies() {
        RequestContext request = mock(RequestContext.class);
        IamService iam = mock(IamService.class);
        GetStackOutputRoleAuthorizer authorizer = new GetStackOutputRoleAuthorizer(iam, null, null, null, request);
        when(request.getAccessKeyId()).thenReturn("key");
        when(request.isSignatureVerifiedFor("key")).thenReturn(true);
        when(request.getAccountId()).thenReturn("123456789012");
        when(iam.resolveCallerArns("key")).thenReturn(Optional.of(caller("123456789012")));
        when(iam.resolveCallerContext("key")).thenReturn(CallerContext.of(List.of("allow")));
        assertEquals(caller("123456789012"), authorizer.captureCaller().caller());
        when(iam.resolveCallerContext("key")).thenReturn(null);
        assertNull(authorizer.captureCaller());
    }

    @Test
    void captureAttachesImmutableScpCeilingAndDownstreamEvaluationDenies() {
        RequestContext request = mock(RequestContext.class);
        IamService iam = mock(IamService.class);
        GetStackOutputRoleAuthorizer authorizer = new GetStackOutputRoleAuthorizer(iam, null, null, null, request);
        String allow = "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}]}";
        String deny = "{\"Statement\":[{\"Effect\":\"Deny\",\"Action\":\"s3:PutObject\",\"Resource\":\"*\"}]}";
        List<String> policies = new ArrayList<>(List.of(allow, deny));
        List<List<String>> levels = new ArrayList<>(List.of(policies));
        CallerContext callerPolicies = CallerContext.of(List.of(allow));
        when(request.getAccessKeyId()).thenReturn("key");
        when(request.isSignatureVerifiedFor("key")).thenReturn(true);
        when(request.getAccountId()).thenReturn("123456789012");
        when(iam.resolveCallerArns("key")).thenReturn(Optional.of(caller("123456789012")));
        when(iam.resolveCallerContext("key")).thenReturn(callerPolicies);
        when(iam.effectiveScpLevels("123456789012")).thenReturn(levels);
        Identity identity = authorizer.captureCaller();
        policies.clear();
        levels.clear();
        IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());
        assertEquals(IamPolicyEvaluator.Decision.ALLOW, evaluator.evaluate(callerPolicies,
                List.of(), "s3:PutObject", "arn:aws:s3:::bucket/key", Map.of()));
        assertEquals(IamPolicyEvaluator.Decision.DENY, evaluator.evaluate(identity.policies(),
                List.of(), "s3:PutObject", "arn:aws:s3:::bucket/key", Map.of()));
        assertEquals(List.of(List.of(allow, deny)), identity.policies().scpLevels());
        verify(iam).effectiveScpLevels("123456789012");
    }
}
