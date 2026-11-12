package io.floci.aws.services.apigateway;

import io.floci.aws.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import java.util.HashMap;
import java.util.Map;

/** Reuses the native five-action controls with real persistent files and byte comparisons. */
@QuarkusTest
@TestProfile(ApiGatewayDocumentationVersionPersistentIamIntegrationTest.Profile.class)
class ApiGatewayDocumentationVersionPersistentIamIntegrationTest extends ApiGatewayDocumentationVersionTagIamIntegrationTest {
    public static class Profile extends IamEnforcementProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.storage.mode", "persistent");
            overrides.put("floci.storage.persistent-path", "target/apigateway-documentation-iam-persistent");
            overrides.put("floci.storage.wal.compaction-interval-ms", "9223372036854775807");
            return overrides;
        }
    }
}
