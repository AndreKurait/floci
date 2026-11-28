package io.floci.aws.services.s3;

import io.floci.aws.testing.S3IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class S3SignaturePolicyCombinedSdkIntegrationTest extends S3SignaturePolicySdkContract {
}
