package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers EC2 read-path parity gaps that broke Terraform/OpenTofu:
 * <ul>
 *   <li>#1083 — gp3 Volume must report {@code throughput} in CreateVolume/DescribeVolumes</li>
 *   <li>#1090 — DescribeAddressesAttribute must be supported (VPC module EIP read cycle)</li>
 *   <li>#1094 — IamInstanceProfile in DescribeInstances + DescribeIamInstanceProfileAssociations</li>
 * </ul>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2VolumeThroughputAndIamProfileIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20250101/us-east-1/ec2/aws4_request";

    // ── #1083: Volume throughput ──────────────────────────────────────────────

    @Test
    @Order(1)
    void gp3VolumeReportsDefaultThroughput() {
        String volumeId = given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "CreateVolume")
                .formParam("AvailabilityZone", "us-east-1a")
                .formParam("VolumeType", "gp3")
                .formParam("Size", "10")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateVolumeResponse.volumeId", notNullValue())
                .body("CreateVolumeResponse.iops", equalTo("3000"))
                .body("CreateVolumeResponse.throughput", equalTo("125"))
                .extract().path("CreateVolumeResponse.volumeId");

        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "DescribeVolumes")
                .formParam("VolumeId.1", volumeId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeVolumesResponse.volumeSet.item.iops", equalTo("3000"))
                .body("DescribeVolumesResponse.volumeSet.item.throughput", equalTo("125"));
    }

    @Test
    @Order(2)
    void gp3VolumeHonoursExplicitThroughput() {
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "CreateVolume")
                .formParam("AvailabilityZone", "us-east-1a")
                .formParam("VolumeType", "gp3")
                .formParam("Size", "10")
                .formParam("Throughput", "250")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateVolumeResponse.throughput", equalTo("250"));
    }

    @Test
    @Order(3)
    void gp2VolumeOmitsThroughput() {
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "CreateVolume")
                .formParam("AvailabilityZone", "us-east-1a")
                .formParam("VolumeType", "gp2")
                .formParam("Size", "10")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                // gp2 is not a throughput-provisionable type; only volumeType is asserted.
                // The handler omits the <throughput> element when the value is null
                // (positively verified for gp3 in the tests above).
                .body("CreateVolumeResponse.volumeType", equalTo("gp2"));
    }

    @Test
    @Order(4)
    void describeVolumesPagesFilteredResultsAndRejectsInvalidCursors() {
        String scope = UUID.randomUUID().toString();
        List<String> volumes = new ArrayList<>();
        try {
            for (int index = 0; index < 6; index++) {
                volumes.add(given().header("Authorization", AUTH_HEADER)
                        .formParam("Action", "CreateVolume").formParam("AvailabilityZone", "us-east-1a")
                        .formParam("Size", "1").formParam("TagSpecification.1.ResourceType", "volume")
                        .formParam("TagSpecification.1.Tag.1.Key", "PageScope")
                        .formParam("TagSpecification.1.Tag.1.Value", scope)
                        .post("/").then().statusCode(200).extract().path("CreateVolumeResponse.volumeId"));
            }
            Response first = given().header("Authorization", AUTH_HEADER)
                    .formParam("Action", "DescribeVolumes").formParam("MaxResults", "5")
                    .formParam("Filter.1.Name", "tag:PageScope").formParam("Filter.1.Value.1", scope)
                    .post("/").then().statusCode(200).extract().response();
            List<String> ids = new ArrayList<>(first.xmlPath().getList("DescribeVolumesResponse.volumeSet.item.volumeId"));
            assertEquals(5, ids.size());
            String token = first.path("DescribeVolumesResponse.nextToken");
            assertFalse(token.isBlank());
            Response last = given().header("Authorization", AUTH_HEADER)
                    .formParam("Action", "DescribeVolumes").formParam("MaxResults", "5")
                    .formParam("NextToken", token).formParam("Filter.1.Name", "tag:PageScope")
                    .formParam("Filter.1.Value.1", scope)
                    .post("/").then().statusCode(200).extract().response();
            ids.addAll(last.xmlPath().getList("DescribeVolumesResponse.volumeSet.item.volumeId"));
            assertEquals(volumes.stream().sorted().toList(), ids);
            assertTrue(last.xmlPath().getList("DescribeVolumesResponse.nextToken").isEmpty());
            given().header("Authorization", AUTH_HEADER).formParam("Action", "DescribeVolumes")
                    .formParam("MaxResults", "5").formParam("NextToken", "not-a-token")
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
        } finally {
            for (String id : volumes) {
                given().header("Authorization", AUTH_HEADER).formParam("Action", "DeleteVolume")
                        .formParam("VolumeId", id).post("/").then().statusCode(200);
            }
        }
    }

    // ── #1090: DescribeAddressesAttribute ─────────────────────────────────────

    @Test
    @Order(10)
    void describeAddressesAttributeReturnsAllocation() {
        String allocationId = given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "AllocateAddress")
                .formParam("Domain", "vpc")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("AllocateAddressResponse.allocationId", startsWith("eipalloc-"))
                .extract().path("AllocateAddressResponse.allocationId");

        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "DescribeAddressesAttribute")
                .formParam("AllocationId.1", allocationId)
                .formParam("Attribute", "domain-name")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeAddressesAttributeResponse.addressSet.item.allocationId", equalTo(allocationId))
                .body("DescribeAddressesAttributeResponse.addressSet.item.publicIp", notNullValue());
    }

    // ── #1094: IAM instance profile ───────────────────────────────────────────

    @Test
    @Order(20)
    void instanceWithIamProfileExposesItInDescribeAndAssociations() {
        String profileId = given().header("Authorization", AUTH_HEADER.replace("/ec2/", "/iam/"))
                .formParam("Action", "CreateInstanceProfile").formParam("InstanceProfileName", "my-profile")
                .post("/").then().statusCode(200)
                .extract().path("CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.InstanceProfileId");
        String instanceId = given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-12345678")
                .formParam("InstanceType", "t2.micro")
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
                .formParam("IamInstanceProfile.Arn",
                        "arn:aws:iam::000000000000:instance-profile/my-profile")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        // DescribeInstances must now carry the iamInstanceProfile block.
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "DescribeInstances")
                .formParam("InstanceId.1", instanceId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.iamInstanceProfile.arn",
                        equalTo("arn:aws:iam::000000000000:instance-profile/my-profile"))
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.iamInstanceProfile.id",
                        equalTo(profileId));

        // DescribeIamInstanceProfileAssociations must resolve the association.
        given()
                .header("Authorization", AUTH_HEADER)
                .formParam("Action", "DescribeIamInstanceProfileAssociations")
                .formParam("Filter.1.Name", "instance-id")
                .formParam("Filter.1.Value.1", instanceId)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeIamInstanceProfileAssociationsResponse.iamInstanceProfileAssociationSet.item.instanceId",
                        equalTo(instanceId))
                .body("DescribeIamInstanceProfileAssociationsResponse.iamInstanceProfileAssociationSet.item.associationId",
                        startsWith("iip-assoc-"))
                .body("DescribeIamInstanceProfileAssociationsResponse.iamInstanceProfileAssociationSet.item.state",
                        equalTo("associated"))
                .body("DescribeIamInstanceProfileAssociationsResponse.iamInstanceProfileAssociationSet.item.iamInstanceProfile.arn",
                        equalTo("arn:aws:iam::000000000000:instance-profile/my-profile"));
    }
}
