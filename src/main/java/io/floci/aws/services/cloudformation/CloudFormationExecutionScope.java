package io.floci.aws.services.cloudformation;

import io.floci.aws.services.cloudformation.GetStackOutputRoleAuthorizer.ExecutionIdentity;

import java.util.Optional;

/**
 * The authenticated identity selected for a running stack operation. Account-only
 * internal scopes and legacy root fallbacks never provide provider authority.
 * No credentials are retained, and executor threads cannot inherit another task's identity.
 */
public final class CloudFormationExecutionScope {

    private static final ThreadLocal<ExecutionIdentity> CURRENT = new ThreadLocal<>();

    private CloudFormationExecutionScope() {}

    public static Optional<ExecutionIdentity> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    static void run(ExecutionIdentity identity, Runnable operation) {
        ExecutionIdentity previous = CURRENT.get();
        if (identity != null && identity.signatureVerified()
                && identity.caller() != null && identity.policies() != null) {
            CURRENT.set(identity);
        } else {
            CURRENT.remove();
        }
        try {
            operation.run();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
