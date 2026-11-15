package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.cloudformation.GetStackOutputRoleAuthorizer.ExecutionIdentity;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class CloudFormationExecutionScopeTest {

    private static ExecutionIdentity identity(boolean verified) {
        return new ExecutionIdentity(
                CallerArns.accountRoot("arn:aws:iam::123456789012:root", "123456789012"),
                CallerContext.of(List.of("{\"Statement\":[]}")), verified);
    }

    @Test
    void unsignedNestedScopeCannotBorrowVerifiedOuterIdentity() {
        ExecutionIdentity verified = identity(true);
        CloudFormationExecutionScope.run(verified, () -> {
            assertEquals(verified, CloudFormationExecutionScope.current().orElseThrow());
            CloudFormationExecutionScope.run(identity(false),
                    () -> assertTrue(CloudFormationExecutionScope.current().isEmpty()));
            assertEquals(verified, CloudFormationExecutionScope.current().orElseThrow());
        });
        assertTrue(CloudFormationExecutionScope.current().isEmpty());
    }

    @Test
    void executorReuseAfterFailureDoesNotRetainAuthority() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            assertThrows(Exception.class, () -> executor.submit(() ->
                    CloudFormationExecutionScope.run(identity(true), () -> {
                        assertTrue(CloudFormationExecutionScope.current().isPresent());
                        throw new IllegalStateException("Provider failure");
                    })).get());
            assertTrue(executor.submit(() -> CloudFormationExecutionScope.current().isEmpty()).get());
        }
    }

    @Test
    void childThreadsDoNotInheritProviderAuthority() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            CloudFormationExecutionScope.run(identity(true), () -> {
                try {
                    assertTrue(executor.submit(() -> CloudFormationExecutionScope.current().isEmpty()).get());
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
        }
    }

    @Test
    void permissionCeilingsAreCopiedAndLegacyIdentityIsNotVerified() {
        List<String> identityPolicies = new ArrayList<>(List.of("identity"));
        List<String> scp = new ArrayList<>(List.of("scp"));
        CallerContext policies = new CallerContext(identityPolicies, "session", "boundary",
                List.of(scp), "principal", List.of("managed-session"));
        ExecutionIdentity captured = new ExecutionIdentity(identity(true).caller(), policies, true);
        identityPolicies.clear();
        scp.clear();
        assertEquals(List.of("identity"), captured.policies().identityPolicies());
        assertEquals(List.of(List.of("scp")), captured.policies().scpLevels());
        assertEquals(List.of("managed-session", "session"), captured.policies().sessionPolicyDocuments());
        assertEquals("boundary", captured.policies().boundaryPolicyDocument());
        ExecutionIdentity legacy = new ExecutionIdentity(captured.caller(), captured.policies());
        assertFalse(legacy.signatureVerified());
        CloudFormationExecutionScope.run(legacy,
                () -> assertTrue(CloudFormationExecutionScope.current().isEmpty()));
    }
}
