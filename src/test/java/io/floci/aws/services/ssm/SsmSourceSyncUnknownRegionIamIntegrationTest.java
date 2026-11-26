package io.floci.aws.services.ssm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

@QuarkusTest
@TestProfile(SsmSourceSyncUnknownRegionIamIntegrationTest.GovProfile.class)
class SsmSourceSyncUnknownRegionIamIntegrationTest {
    public static class GovProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true",
                    "floci.partitions.allow-unknown-regions", "true",
                    "floci.default-region", "us-gov-west-1");
        }
    }

    @Test
    void allowedUnknownRegionUsesTheConfiguredPartitionForEveryNamedOperation() {
        SsmSourceSyncIamIntegrationTest.verifyExactGrants("unknown-source-region-1", "aws-us-gov");
    }

    @Test
    void aPublishedRegionRetainsItsOwnPartitionUnderTheNoncommercialDefault() {
        SsmSourceSyncIamIntegrationTest.verifyExactGrants("us-east-1", "aws");
    }
}
