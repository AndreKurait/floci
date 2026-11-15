package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.services.cloudformation.CloudFormationDriftSnapshot.Resource;

import java.util.Map;

/** A provider's immutable native read and the exact underlying caller permission it needs. */
public record CfnDriftProjection(Resource resource, Map<String, String> expected,
                                 Map<String, String> actual, boolean deleted, Permission permission) {

    public CfnDriftProjection {
        expected = Map.copyOf(expected);
        actual = Map.copyOf(actual);
    }

    public record Permission(String action, String resourceArn, String accountId,
                             String policyDocument, Map<String, String> tags) {
        public Permission {
            tags = Map.copyOf(tags);
        }
    }
}
