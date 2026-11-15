package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@QuarkusTest
@TestProfile(AppConfigTagOnCreateUnknownRegionIntegrationTest.ChinaProfile.class)
class AppConfigTagOnCreateUnknownRegionIntegrationTest {
    public static class ChinaProfile extends SignedIamEnforcementProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.default-region", "cn-north-1");
            overrides.put("floci.partitions.allow-unknown-regions", "true");
            return overrides;
        }
    }

    public static class GovProfile extends ChinaProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.default-region", "us-gov-west-1");
            return overrides;
        }
    }

    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @CsvSource({"CreateApplication,application", "CreateDeploymentStrategy,deploymentstrategy"})
    void unknownRegionUsesTheConfiguredPartitionForTheExactTagScope(String operation, String type) throws Exception {
        AppConfigTagOnCreateIntegrationTest fixture = new AppConfigTagOnCreateIntegrationTest();
        fixture.endpoint = endpoint;
        fixture.exerciseResourceScope(operation, type, Region.of("polygondwanaland-west-1"), partition());
    }

    String partition() {
        return "aws-cn";
    }
}
