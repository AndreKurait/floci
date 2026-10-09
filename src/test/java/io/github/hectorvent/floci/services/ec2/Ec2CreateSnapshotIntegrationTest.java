package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class Ec2CreateSnapshotIntegrationTest {

    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "us-east-1";

    @Test
    void volumeSnapshotRoundTripsThroughRegistrationAndSourceVolumeDeletion() {
        String volumeId = volume();
        String marker = UUID.randomUUID().toString();
        ValidatableResponse created = request("CreateSnapshot")
                .formParam("VolumeId", volumeId)
                .formParam("Description", "backup <metadata> & tags")
                .formParam("TagSpecification.1.ResourceType", "snapshot")
                .formParam("TagSpecification.1.Tag.1.Key", "Purpose")
                .formParam("TagSpecification.1.Tag.1.Value", marker)
                .formParam("TagSpecification.1.Tag.2.Key", "EmptyValue")
                .post("/").then().statusCode(200)
                .body("CreateSnapshotResponse.requestId", notNullValue())
                .body("CreateSnapshotResponse.snapshotId", matchesPattern("snap-[0-9a-f]{17}"))
                .body("CreateSnapshotResponse.volumeId", equalTo(volumeId))
                .body("CreateSnapshotResponse.volumeSize", equalTo("10"))
                .body("CreateSnapshotResponse.ownerId", equalTo(ACCOUNT))
                .body("CreateSnapshotResponse.status", equalTo("completed"))
                .body("CreateSnapshotResponse.progress", equalTo("100%"))
                .body("CreateSnapshotResponse.encrypted", equalTo("true"))
                .body("CreateSnapshotResponse.description", equalTo("backup <metadata> & tags"))
                .body("CreateSnapshotResponse.tagSet.item[0].key", equalTo("Purpose"))
                .body("CreateSnapshotResponse.tagSet.item[0].value", equalTo(marker))
                .body("CreateSnapshotResponse.tagSet.item[1].key", equalTo("EmptyValue"))
                .body("CreateSnapshotResponse.tagSet.item[1].value", emptyOrNullString());
        String snapshotId = created.extract().path("CreateSnapshotResponse.snapshotId");
        String startTime = created.extract().path("CreateSnapshotResponse.startTime");
        assertNotNull(Instant.parse(startTime));

        request("DeleteVolume").formParam("VolumeId", volumeId).post("/").then().statusCode(200);
        String imageId = request("RegisterImage").formParam("Name", "snapshot-" + marker)
                .formParam("Architecture", "x86_64").formParam("RootDeviceName", "/dev/xvda")
                .formParam("BlockDeviceMapping.1.DeviceName", "/dev/xvda")
                .formParam("BlockDeviceMapping.1.Ebs.SnapshotId", snapshotId)
                .formParam("BlockDeviceMapping.1.Ebs.VolumeSize", "10")
                .post("/").then().statusCode(200).extract().path("RegisterImageResponse.imageId");
        try {
            String body = request("DescribeSnapshots").formParam("SnapshotId.1", snapshotId)
                    .formParam("Owner.1", "self")
                    .formParam("Filter.1.Name", "tag:Purpose").formParam("Filter.1.Value.1", marker)
                    .formParam("Filter.2.Name", "tag-key").formParam("Filter.2.Value.1", "EmptyValue")
                    .post("/").then().statusCode(200)
                    .body("DescribeSnapshotsResponse.snapshotSet.item.snapshotId", equalTo(snapshotId))
                    .body("DescribeSnapshotsResponse.snapshotSet.item.volumeId", equalTo(volumeId))
                    .body("DescribeSnapshotsResponse.snapshotSet.item.volumeSize", equalTo("10"))
                    .body("DescribeSnapshotsResponse.snapshotSet.item.ownerId", equalTo(ACCOUNT))
                    .body("DescribeSnapshotsResponse.snapshotSet.item.status", equalTo("completed"))
                    .body("DescribeSnapshotsResponse.snapshotSet.item.startTime", equalTo(startTime))
                    .body("DescribeSnapshotsResponse.snapshotSet.item.description",
                            equalTo("backup <metadata> & tags"))
                    .extract().asString();
            assertFalse(body.contains("SourceOnly"), "Source volume tags must not be inherited");
            request("DescribeSnapshots").formParam("SnapshotId.1", snapshotId)
                    .formParam("Filter.1.Name", "tag:Purpose").formParam("Filter.1.Value.1", "wrong")
                    .post("/").then().statusCode(200)
                    .body("DescribeSnapshotsResponse.snapshotSet", emptyOrNullString());
            request("DescribeImages").formParam("ImageId.1", imageId).post("/").then().statusCode(200)
                    .body("DescribeImagesResponse.imagesSet.item.blockDeviceMapping.item.ebs.snapshotId",
                            equalTo(snapshotId));
        } finally {
            request("DeregisterImage").formParam("ImageId", imageId)
                    .formParam("DeleteAssociatedSnapshots", "true").post("/").then().statusCode(200);
        }
    }

    @Test
    void foreignAccountAndOtherRegionCannotSnapshotTheSourceOrReadItsSnapshot() {
        String volumeId = volume();
        try {
            request("CreateSnapshot", "444455556666", REGION).formParam("VolumeId", volumeId)
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidVolume.NotFound"));
            request("CreateSnapshot", ACCOUNT, "us-west-2").formParam("VolumeId", volumeId)
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidVolume.NotFound"));

            String snapshotId = request("CreateSnapshot").formParam("VolumeId", volumeId)
                    .post("/").then().statusCode(200).extract().path("CreateSnapshotResponse.snapshotId");
            for (RequestSpecification other : new RequestSpecification[] {
                    request("DescribeSnapshots", "444455556666", REGION),
                    request("DescribeSnapshots", ACCOUNT, "us-west-2")}) {
                other.formParam("Filter.1.Name", "snapshot-id").formParam("Filter.1.Value.1", snapshotId)
                        .post("/").then().statusCode(200)
                        .body("DescribeSnapshotsResponse.snapshotSet", emptyOrNullString());
            }
            request("DescribeSnapshots").formParam("SnapshotId.1", snapshotId)
                    .formParam("Owner.1", "444455556666").post("/").then().statusCode(200)
                    .body("DescribeSnapshotsResponse.snapshotSet", emptyOrNullString());
        } finally {
            request("DeleteVolume").formParam("VolumeId", volumeId).post("/").then().statusCode(200);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,not-a-volume,InvalidVolumeID.Malformed", "true,not-a-volume,InvalidVolumeID.Malformed",
            "false,vol-0123456789abcdef0,InvalidVolume.NotFound",
            "true,vol-0123456789abcdef0,InvalidVolume.NotFound"})
    void invalidSourceKeepsItsErrorBeforeDryRun(boolean dryRun, String volumeId, String code) {
        request("CreateSnapshot").formParam("VolumeId", volumeId).formParam("DryRun", dryRun)
                .post("/").then().statusCode(400).body("Response.Errors.Error.Code", equalTo(code));
    }

    @Test
    void missingVolumeIsRejected() {
        request("CreateSnapshot").post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
    }

    @Test
    void dryRunValidatesWithoutCreatingSnapshotMetadata() {
        String volumeId = volume();
        try {
            request("CreateSnapshot").formParam("VolumeId", volumeId).formParam("DryRun", true)
                    .post("/").then().statusCode(412)
                    .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));
            request("DescribeSnapshots").formParam("Filter.1.Name", "volume-id")
                    .formParam("Filter.1.Value.1", volumeId).post("/").then().statusCode(200)
                    .body("DescribeSnapshotsResponse.snapshotSet", emptyOrNullString());
        } finally {
            request("DeleteVolume").formParam("VolumeId", volumeId).post("/").then().statusCode(200);
        }
    }

    @ParameterizedTest
    @CsvSource({"Location,local,UnsupportedOperation", "OutpostArn,unsupported,UnsupportedOperation",
            "TagSpecification.1.ResourceType,volume,InvalidParameterValue"})
    void unsupportedPlacementAndWrongTagResourceDoNotCreateASnapshot(String parameter, String value, String code) {
        String volumeId = volume();
        try {
            request("CreateSnapshot").formParam("VolumeId", volumeId).formParam(parameter, value)
                    .post("/").then().statusCode(400).body("Response.Errors.Error.Code", equalTo(code));
            request("DescribeSnapshots").formParam("Filter.1.Name", "volume-id")
                    .formParam("Filter.1.Value.1", volumeId).post("/").then().statusCode(200)
                    .body("DescribeSnapshotsResponse.snapshotSet", emptyOrNullString());
        } finally {
            request("DeleteVolume").formParam("VolumeId", volumeId).post("/").then().statusCode(200);
        }
    }

    private String volume() {
        return request("CreateVolume").formParam("AvailabilityZone", REGION + "a")
                .formParam("Size", "10").formParam("VolumeType", "gp2").formParam("Encrypted", "true")
                .formParam("TagSpecification.1.ResourceType", "volume")
                .formParam("TagSpecification.1.Tag.1.Key", "SourceOnly")
                .formParam("TagSpecification.1.Tag.1.Value", "volume")
                .post("/").then().statusCode(200).extract().path("CreateVolumeResponse.volumeId");
    }

    private RequestSpecification request(String action) {
        return request(action, ACCOUNT, REGION);
    }

    private RequestSpecification request(String action, String account, String region) {
        return given().header("Authorization",
                "AWS4-HMAC-SHA256 Credential=" + account + "/20261009/" + region + "/ec2/aws4_request")
                .formParam("Action", action).formParam("Version", "2016-11-15");
    }
}
