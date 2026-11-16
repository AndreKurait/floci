package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

import java.util.HashMap;
import java.util.Map;

@QuarkusTest
@TestProfile(S3KmsPersistentMultipartSdkIntegrationTest.PersistentProfile.class)
class S3KmsPersistentMultipartSdkIntegrationTest extends S3KmsEnvelopeSdkIntegrationTest {

    public static class PersistentProfile extends IamEnforcementProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.storage.mode", "persistent");
            overrides.put("floci.storage.persistent-path", "target/kms-persistent-multipart-test");
            return overrides;
        }
    }
}
