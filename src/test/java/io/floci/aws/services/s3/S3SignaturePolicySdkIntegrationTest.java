package io.floci.aws.services.s3;

import io.floci.aws.testing.S3EnforceAuthProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(S3EnforceAuthProfile.class)
class S3SignaturePolicySdkIntegrationTest extends S3SignaturePolicySdkContract {
}
