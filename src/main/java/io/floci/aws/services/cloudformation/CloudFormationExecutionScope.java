package io.floci.aws.services.cloudformation;

import io.floci.aws.services.iam.IamService.CallerArns;
import io.floci.aws.services.iam.model.CallerContext;

import java.util.List;
import java.util.Optional;

/** Verified caller snapshots for stack operations, without retained credentials or inherited threads. */
public final class CloudFormationExecutionScope {
    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

    private CloudFormationExecutionScope() {}

    public record Identity(CallerArns caller, CallerContext policies) {
        public Identity {
            if (caller == null || policies == null) {
                throw new IllegalArgumentException("Verified caller and policies are required");
            }
            List<List<String>> levels = policies.scpLevels() == null ? null
                    : policies.scpLevels().stream().map(List::copyOf).toList();
            policies = new CallerContext(List.copyOf(policies.identityPolicies()),
                    policies.sessionPolicyDocument(), policies.boundaryPolicyDocument(), levels,
                    policies.principalArn());
        }
    }

    public static Optional<Identity> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    static void run(Identity identity, Runnable operation) {
        Identity previous = CURRENT.get();
        if (identity == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(identity);
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
