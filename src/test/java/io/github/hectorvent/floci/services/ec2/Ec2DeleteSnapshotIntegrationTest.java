package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class Ec2DeleteSnapshotIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(721000000000L);
    private static final String REGION = "us-east-1";

    @Test
    void deletePreservesTheSourceVolumeAndHonorsDryRunAccountAndRegion() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String other = Long.toString(ACCOUNTS.incrementAndGet());
        String volume = volume(owner);
        String snapshot = snapshot(owner, volume);
        request("DeleteSnapshot", owner).formParam("SnapshotId", snapshot).formParam("DryRun", true)
                .post("/").then().statusCode(412)
                .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));
        request("DeleteSnapshot", other).formParam("SnapshotId", snapshot)
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.NotFound"));
        request("DeleteSnapshot", owner, "us-west-2").formParam("SnapshotId", snapshot)
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.NotFound"));
        request("DescribeSnapshots", owner).formParam("SnapshotId.1", snapshot)
                .post("/").then().statusCode(200)
                .body("DescribeSnapshotsResponse.snapshotSet.item.volumeId", equalTo(volume));
        delete(owner, snapshot);
        request("DescribeSnapshots", owner).formParam("SnapshotId.1", snapshot)
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.NotFound"));
        request("DeleteSnapshot", owner).formParam("SnapshotId", snapshot)
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.NotFound"));
        request("DescribeVolumes", owner).formParam("VolumeId.1", volume)
                .post("/").then().statusCode(200)
                .body("DescribeVolumesResponse.volumeSet.item.size", equalTo("8"));
        request("DeleteVolume", owner).formParam("VolumeId", volume).post("/").then().statusCode(200);
    }

    @Test
    void registeredRootSnapshotIsProtectedUntilEveryReferencingImageIsDeregistered() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String volume = volume(owner);
        String snapshot = snapshot(owner, volume);
        String first = image(owner, snapshot, "first");
        String second = image(owner, snapshot, "second");
        for (String image : new String[] {first, second}) {
            request("DeleteSnapshot", owner).formParam("SnapshotId", snapshot)
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.InUse"));
            request("DeregisterImage", owner).formParam("ImageId", image).post("/").then().statusCode(200);
        }
        delete(owner, snapshot);
        request("DeleteVolume", owner).formParam("VolumeId", volume).post("/").then().statusCode(200);
    }

    @Test
    void invalidIdentifiersDoNotDeleteExistingSnapshots() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String volume = volume(owner);
        String snapshot = snapshot(owner, volume);
        request("DeleteSnapshot", owner).post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
        request("DeleteSnapshot", owner).formParam("SnapshotId", "snapshot-wrong")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidSnapshotID.Malformed"));
        delete(owner, snapshot);
        request("DeleteVolume", owner).formParam("VolumeId", volume).post("/").then().statusCode(200);
    }

    private String volume(String owner) {
        return request("CreateVolume", owner).formParam("AvailabilityZone", REGION + "a")
                .formParam("Size", 8).post("/").then().statusCode(200).extract().path("CreateVolumeResponse.volumeId");
    }

    private String snapshot(String owner, String volume) {
        return request("CreateSnapshot", owner).formParam("VolumeId", volume).post("/").then().statusCode(200)
                .extract().path("CreateSnapshotResponse.snapshotId");
    }

    private String image(String owner, String snapshot, String name) {
        return request("RegisterImage", owner).formParam("Name", name).formParam("RootDeviceName", "/dev/sda1")
                .formParam("Architecture", "x86_64").formParam("VirtualizationType", "hvm")
                .formParam("BlockDeviceMapping.1.DeviceName", "/dev/sda1")
                .formParam("BlockDeviceMapping.1.Ebs.SnapshotId", snapshot)
                .post("/").then().statusCode(200).extract().path("RegisterImageResponse.imageId");
    }

    private void delete(String owner, String snapshot) {
        request("DeleteSnapshot", owner).formParam("SnapshotId", snapshot).post("/").then().statusCode(200)
                .body("DeleteSnapshotResponse.return", equalTo("true"));
    }

    private RequestSpecification request(String action, String owner) {
        return request(action, owner, REGION);
    }

    private RequestSpecification request(String action, String owner, String region) {
        return given().formParam("Action", action).header("Authorization",
                "AWS4-HMAC-SHA256 Credential=" + owner + "/20261010/" + region + "/ec2/aws4_request");
    }
}
