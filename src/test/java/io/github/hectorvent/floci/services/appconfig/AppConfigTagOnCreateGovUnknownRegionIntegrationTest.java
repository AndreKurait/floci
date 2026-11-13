package io.github.hectorvent.floci.services.appconfig;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(AppConfigTagOnCreateUnknownRegionIntegrationTest.GovProfile.class)
class AppConfigTagOnCreateGovUnknownRegionIntegrationTest extends AppConfigTagOnCreateUnknownRegionIntegrationTest {
    @Override
    String partition() {
        return "aws-us-gov";
    }
}
