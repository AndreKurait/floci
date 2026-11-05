package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.testing.S3EnforceAuthProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(S3EnforceAuthProfile.class)
class S3SignaturePolicySdkIntegrationTest extends S3SignaturePolicySdkContract {
}
