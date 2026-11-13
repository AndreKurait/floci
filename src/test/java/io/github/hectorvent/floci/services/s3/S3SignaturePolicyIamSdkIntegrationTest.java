package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3SignaturePolicyIamSdkIntegrationTest extends S3SignaturePolicySdkContract {
}
