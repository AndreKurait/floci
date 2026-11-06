package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class Ec2RunInstancesPlacementIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(728000000000L);
    private static final String ZONE = "us-east-1b";

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void zoneIdSelectsTheSubnetAndOverridesATemplateZone(boolean template, boolean templateUsesId) {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String zoneId = zoneId(owner, ZONE);
        String templateId = null;
        String instanceId = null;
        try {
            RequestSpecification launch = runInstance(owner);
            if (template) {
                templateId = request("CreateLaunchTemplate", owner)
                        .formParam("LaunchTemplateName", "placement")
                        .formParam("LaunchTemplateData.ImageId", "ami-0abcdef1234567890")
                        .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                        .formParam("LaunchTemplateData.Placement."
                                        + (templateUsesId ? "AvailabilityZoneId" : "AvailabilityZone"),
                                templateUsesId ? zoneId : "us-east-1a")
                        .post("/").then().statusCode(200)
                        .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
                launch.formParam("LaunchTemplate.LaunchTemplateId", templateId);
            }
            if (!templateUsesId) {
                launch.formParam("Placement.AvailabilityZoneId", zoneId);
            }
            instanceId = launch.post("/").then().statusCode(200)
                    .body("RunInstancesResponse.instancesSet.item.placement.availabilityZone", equalTo(ZONE))
                    .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
            String subnetId = request("DescribeInstances", owner).formParam("InstanceId.1", instanceId)
                    .post("/").then().statusCode(200)
                    .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.placement.availabilityZone",
                            equalTo(ZONE))
                    .extract().path("DescribeInstancesResponse.reservationSet.item.instancesSet.item.subnetId");
            request("DescribeSubnets", owner).formParam("SubnetId.1", subnetId)
                    .post("/").then().statusCode(200)
                    .body("DescribeSubnetsResponse.subnetSet.item.availabilityZone", equalTo(ZONE));
        } finally {
            if (instanceId != null) {
                request("TerminateInstances", owner).formParam("InstanceId.1", instanceId)
                        .post("/").then().statusCode(200);
            }
            if (templateId != null) {
                request("DeleteLaunchTemplate", owner).formParam("LaunchTemplateId", templateId)
                        .post("/").then().statusCode(200);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"unknown,false", "unknown,true", "matching,false", "matching,true",
            "conflicting,false", "conflicting,true"})
    void invalidZoneSelectorsNeverAllocateAnInstance(String invalid, boolean dryRun) {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        RequestSpecification launch = runInstance(owner).formParam("DryRun", dryRun);
        String expectedError;
        if ("unknown".equals(invalid)) {
            launch.formParam("Placement.AvailabilityZoneId", "unpublished-az999");
            expectedError = "InvalidParameterValue";
        } else {
            launch.formParam("Placement.AvailabilityZoneId", zoneId(owner, ZONE))
                    .formParam("Placement.AvailabilityZone", "matching".equals(invalid) ? ZONE : "us-east-1a");
            expectedError = "InvalidParameterCombination";
        }
        launch.post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo(expectedError));
        request("DescribeInstances", owner).post("/").then().statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.size()", equalTo(0));
    }

    private static String zoneId(String owner, String name) {
        return request("DescribeAvailabilityZones", owner).formParam("ZoneName.1", name)
                .post("/").then().statusCode(200)
                .extract().path("DescribeAvailabilityZonesResponse.availabilityZoneInfo.item.zoneId");
    }

    private static RequestSpecification runInstance(String owner) {
        return request("RunInstances", owner).formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", 1).formParam("MaxCount", 1);
    }

    private static RequestSpecification request(String action, String owner) {
        return given().formParam("Action", action).header("Authorization", EnforcementFixtures.auth(owner, "ec2"));
    }
}
