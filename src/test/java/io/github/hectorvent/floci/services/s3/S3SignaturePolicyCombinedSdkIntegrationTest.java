package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.S3IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class S3SignaturePolicyCombinedSdkIntegrationTest extends S3SignaturePolicySdkContract {
}
