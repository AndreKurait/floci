package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import java.util.HashMap;
import java.util.Map;

/** Reuses the native five-action controls with real wal files and byte comparisons. */
@QuarkusTest
@TestProfile(ApiGatewayDocumentationVersionWalIamIntegrationTest.Profile.class)
class ApiGatewayDocumentationVersionWalIamIntegrationTest extends ApiGatewayDocumentationVersionTagIamIntegrationTest {
    public static class Profile extends IamEnforcementProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.storage.mode", "wal");
            overrides.put("floci.storage.persistent-path", "target/apigateway-documentation-iam-wal");
            overrides.put("floci.storage.wal.compaction-interval-ms", "9223372036854775807");
            return overrides;
        }
    }
}
