package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;

import java.time.Instant;
import java.util.Map;

/** One request's detached stack lifetime, including a name that was absent when authorized. */
public record CloudFormationDriftSnapshot(
        String requestedName, String accountId, String region, String stackName, String stackId,
        Instant creationTime, Instant lastUpdatedTime, String status, String templateBody,
        Map<String, String> parameters, Map<String, String> tags, Map<String, Resource> resources,
        String roleArn, String parentId, String originFailure, boolean operationInProgress) {

    public CloudFormationDriftSnapshot {
        parameters = Map.copyOf(parameters);
        tags = Map.copyOf(tags);
        resources = Map.copyOf(resources);
    }

    public record Resource(String logicalId, String physicalId, String type, String status) {
    }

    public void requirePresent() {
        if (stackId == null) {
            throw new AwsException("ValidationError", "Stack with id " + requestedName + " does not exist", 400);
        }
    }

    void requireDetectionOrigin() {
        if (originFailure != null) {
            throw new AwsException("ValidationError", originFailure, 400);
        }
    }
}
