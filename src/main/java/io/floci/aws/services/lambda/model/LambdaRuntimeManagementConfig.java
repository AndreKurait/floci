package io.floci.aws.services.lambda.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** Stored configuration only; it does not select an execution environment. */
@RegisterForReflection
public record LambdaRuntimeManagementConfig(String updateRuntimeOn, String runtimeVersionArn) {
    public static final LambdaRuntimeManagementConfig DEFAULT = new LambdaRuntimeManagementConfig("Auto", null);
}
