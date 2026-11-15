package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(AppConfigDeploymentEnabledIntegrationTest.Profile.class)
class AppConfigDeploymentEnabledIntegrationTest {
    private static final String TYPE = "AWS::AppConfig::Deployment";

    @Inject
    CloudFormationResourceRegistry registry;

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.appconfig.enabled", "true");
        }
    }

    @Test
    void registryUsesTheConfiguredAppConfigEnablement() {
        assertEquals(true, registry.forType(TYPE).isPresent());
        assertEquals(false, registry.isServiceDisabled(TYPE));
        assertEquals("floci.services.appconfig.enabled", registry.serviceEnabledFlag(TYPE));
    }
}
