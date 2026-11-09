package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.config.StepFunctionsProxyTlsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;

@QuarkusTest
@TestProfile(StepFunctionsProxyTlsProfile.class)
class StepFunctionsApiGatewayTlsIntegrationTest extends StepFunctionsApiGatewayIntegrationTest {
    @ConfigProperty(name = "floci.port")
    int publicPort;

    @BeforeEach
    void usePublicProxy() {
        RestAssured.port = publicPort;
    }
}
