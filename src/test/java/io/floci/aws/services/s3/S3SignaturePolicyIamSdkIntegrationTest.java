package io.floci.aws.services.s3;

import io.floci.aws.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3SignaturePolicyIamSdkIntegrationTest extends S3SignaturePolicySdkContract {
}
