package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

@QuarkusTest
class Ec2MetadataEndpointIntegrationTest {
    private static final String ACCOUNT = "357924681358";
    private static final String REGION = "us-east-1";
    private static final String INSTANCE_ID = "/latest/meta-data/instance-id";

    @Inject Ec2Service ec2;

    @Test
    void disabledEndpointRefusesReadsAndTokensUntilEnabled() throws Exception {
        String instanceId = launch("disabled");
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            startFixture(fixture, instanceId);
            assertEquals(404, fixture.get(INSTANCE_ID, null).statusCode());
            assertEquals(404, fixture.putToken("60").statusCode());
            assertEquals(404, fixture.send("HEAD", INSTANCE_ID, null).statusCode());
            assertEquals(404, fixture.get("/latest/user-data", null).statusCode());

            modifyEndpoint(instanceId, "enabled");
            HttpResponse<String> metadata = fixture.get(INSTANCE_ID, null);
            assertEquals(200, metadata.statusCode());
            assertEquals(instanceId, metadata.body());
            assertEquals(200, fixture.putToken("60").statusCode());
        } finally {
            terminate(instanceId);
        }
    }

    @Test
    void existingTokensCannotBypassADisabledEndpointIncludingFromAnUnregisteredAddress() throws Exception {
        String instanceId = launch("enabled");
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            startFixture(fixture, instanceId);
            String token = fixture.putToken("60").body();
            assertEquals(instanceId, fixture.get(INSTANCE_ID, token).body());

            modifyEndpoint(instanceId, "disabled");
            assertEquals(404, fixture.get(INSTANCE_ID, token).statusCode());
            assertEquals(404, fixture.putToken("60").statusCode());

            Instance stored = ec2.findInstanceForAccount(ACCOUNT, REGION, instanceId).orElseThrow();
            fixture.server().unregisterContainer(Ec2MetadataTestFixture.CALLER_IP, stored);
            assertEquals(404, fixture.get(INSTANCE_ID, token).statusCode());
        } finally {
            terminate(instanceId);
        }
    }

    private void startFixture(Ec2MetadataTestFixture fixture, String instanceId) throws Exception {
        Instance stored = ec2.findInstanceForAccount(ACCOUNT, REGION, instanceId).orElseThrow();
        fixture.start(null, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), stored);
    }

    private static String launch(String endpoint) {
        return request("RunInstances").formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", "1").formParam("MaxCount", "1")
                .formParam("MetadataOptions.HttpEndpoint", endpoint).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    private static void modifyEndpoint(String instanceId, String endpoint) {
        request("ModifyInstanceMetadataOptions").formParam("InstanceId", instanceId)
                .formParam("HttpEndpoint", endpoint).post("/").then().statusCode(200)
                .body("ModifyInstanceMetadataOptionsResponse.instanceMetadataOptions.httpEndpoint", equalTo(endpoint));
    }

    private static void terminate(String instanceId) {
        request("TerminateInstances").formParam("InstanceId.1", instanceId).post("/").then().statusCode(200);
    }

    private static RequestSpecification request(String action) {
        return given().header("Authorization", EnforcementFixtures.auth(ACCOUNT, REGION, "ec2"))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action);
    }
}
