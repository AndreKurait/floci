package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
@TestProfile(Ec2ImageSnapshotPermissionIntegrationTest.CatalogProfile.class)
class Ec2ImageSnapshotPermissionIntegrationTest {

    public static class CatalogProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.image-catalog-path",
                    "src/test/resources/ec2/shared-image-catalog.yaml");
        }
    }

    private static final String OWNER = "333344445555";
    private static final String RECIPIENT = "666677778888";
    private static final String OTHER = "999900001111";
    private static final String SOURCE_REGION = "us-east-1";
    private static final String DESTINATION_REGION = "us-west-2";

    @Test
    void nestedPermissionsAllowIndependentCopyAndDoNotExposeSourceTags() {
        Source source = source(2);
        String copyId = null;
        try {
            modifyImage(source.imageId(), "Add", RECIPIENT).statusCode(200)
                    .body("ModifyImageAttributeResponse.return", equalTo("true"));
            for (String snapshotId : source.snapshotIds()) {
                modifySnapshot(snapshotId, "Add", RECIPIENT).statusCode(200)
                        .body("ModifySnapshotAttributeResponse.return", equalTo("true"));
                describeSnapshotPermissions(snapshotId).statusCode(200)
                        .body("DescribeSnapshotAttributeResponse.snapshotId", equalTo(snapshotId))
                        .body("DescribeSnapshotAttributeResponse.createVolumePermission.item.userId",
                                equalTo(RECIPIENT));
            }
            describeImagePermissions(source.imageId()).statusCode(200)
                    .body("DescribeImageAttributeResponse.imageId", equalTo(source.imageId()))
                    .body("DescribeImageAttributeResponse.requestId", notNullValue())
                    .body("DescribeImageAttributeResponse.launchPermission.item.userId", equalTo(RECIPIENT));

            copyId = copy(source.imageId()).post("/").then().statusCode(200)
                    .extract().path("CopyImageResponse.imageId");
            assertNotEquals(source.imageId(), copyId);
            ValidatableResponse described = request("DescribeImages", RECIPIENT, DESTINATION_REGION)
                    .formParam("ImageId.1", copyId).post("/").then().statusCode(200)
                    .body("DescribeImagesResponse.imagesSet.item.imageOwnerId", equalTo(RECIPIENT))
                    .body("DescribeImagesResponse.imagesSet.item.sourceImageId", equalTo(source.imageId()))
                    .body("DescribeImagesResponse.imagesSet.item.sourceImageRegion", equalTo(SOURCE_REGION))
                    .body("DescribeImagesResponse.imagesSet.item.tagSet", emptyOrNullString());
            List<String> copiedSnapshots = described.extract().xmlPath()
                    .getList("DescribeImagesResponse.imagesSet.item.blockDeviceMapping.item.ebs.snapshotId", String.class);
            assertEquals(2, copiedSnapshots.size());
            for (String snapshotId : copiedSnapshots) {
                assertFalse(source.snapshotIds().contains(snapshotId));
                request("DescribeSnapshots", RECIPIENT, DESTINATION_REGION)
                        .formParam("SnapshotId.1", snapshotId).post("/").then().statusCode(200)
                        .body("DescribeSnapshotsResponse.snapshotSet.item.ownerId", equalTo(RECIPIENT))
                        .body("DescribeSnapshotsResponse.snapshotSet.item.status", equalTo("completed"))
                        .body("DescribeSnapshotsResponse.snapshotSet.item.volumeSize", equalTo("10"))
                        .body("DescribeSnapshotsResponse.snapshotSet.item.tagSet", emptyOrNullString());
                request("DescribeSnapshotAttribute", RECIPIENT, DESTINATION_REGION)
                        .formParam("SnapshotId", snapshotId).formParam("Attribute", "createVolumePermission")
                        .post("/").then().statusCode(200)
                        .body("DescribeSnapshotAttributeResponse.createVolumePermission", emptyOrNullString());
                request("ModifySnapshotAttribute", RECIPIENT, DESTINATION_REGION)
                        .formParam("SnapshotId", snapshotId)
                        .formParam("CreateVolumePermission.Add.1.UserId", OTHER)
                        .post("/").then().statusCode(200);
            }
            request("DescribeImageAttribute", RECIPIENT, DESTINATION_REGION)
                    .formParam("ImageId", copyId).formParam("Attribute", "launchPermission")
                    .post("/").then().statusCode(200)
                    .body("DescribeImageAttributeResponse.launchPermission", emptyOrNullString());
            request("ModifyImageAttribute", RECIPIENT, DESTINATION_REGION)
                    .formParam("ImageId", copyId).formParam("LaunchPermission.Add.1.UserId", OTHER)
                    .post("/").then().statusCode(200);

            for (String snapshotId : source.snapshotIds()) {
                modifySnapshot(snapshotId, "Remove", RECIPIENT).statusCode(200);
            }
            modifyImage(source.imageId(), "Remove", RECIPIENT).statusCode(200);
            copy(source.imageId()).post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("AuthFailure"));
            request("DescribeImages", RECIPIENT, DESTINATION_REGION).formParam("ImageId.1", copyId)
                    .post("/").then().statusCode(200)
                    .body("DescribeImagesResponse.imagesSet.item.imageState", equalTo("available"));
            assertSourceUnchanged(source);
        } finally {
            if (copyId != null) {
                deregister(copyId, RECIPIENT, DESTINATION_REGION);
            }
            deregister(source.imageId(), OWNER, SOURCE_REGION);
        }
    }

    @Test
    void everySnapshotGrantIsRequiredBeforeAnyDestinationRowIsInserted() {
        Source source = source(2);
        try {
            int beforeImages = ownedImages(RECIPIENT, DESTINATION_REGION);
            int beforeSnapshots = ownedSnapshots(RECIPIENT, DESTINATION_REGION);
            modifySnapshot(source.snapshotIds().getFirst(), "Add", RECIPIENT).statusCode(200);
            copy(source.imageId()).post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("AuthFailure"));
            modifyImage(source.imageId(), "Add", RECIPIENT).statusCode(200);
            copy(source.imageId()).post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("AuthFailure"));
            assertEquals(beforeImages, ownedImages(RECIPIENT, DESTINATION_REGION));
            assertEquals(beforeSnapshots, ownedSnapshots(RECIPIENT, DESTINATION_REGION));
            assertSourceUnchanged(source);
        } finally {
            deregister(source.imageId(), OWNER, SOURCE_REGION);
        }
    }

    @Test
    void malformedLaterPermissionAndMixedSnapshotChangesAreAtomic() {
        Source source = source(1);
        try {
            for (String action : List.of("ModifyImageAttribute", "ModifySnapshotAttribute")) {
                boolean image = action.equals("ModifyImageAttribute");
                String field = image ? "LaunchPermission" : "CreateVolumePermission";
                request(action, OWNER, SOURCE_REGION)
                        .formParam(image ? "ImageId" : "SnapshotId",
                                image ? source.imageId() : source.snapshotIds().getFirst())
                        .formParam(field + ".Add.1.UserId", RECIPIENT)
                        .formParam(field + ".Add.2.UserId", "not-an-account")
                        .post("/").then().statusCode(400)
                        .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
            }
            request("ModifySnapshotAttribute", OWNER, SOURCE_REGION)
                    .formParam("SnapshotId", source.snapshotIds().getFirst())
                    .formParam("CreateVolumePermission.Add.1.UserId", RECIPIENT)
                    .formParam("CreateVolumePermission.Remove.1.UserId", OTHER)
                    .post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
            RequestSpecification tooMany = request("ModifySnapshotAttribute", OWNER, SOURCE_REGION)
                    .formParam("SnapshotId", source.snapshotIds().getFirst());
            for (int i = 1; i <= 501; i++) {
                tooMany.formParam("CreateVolumePermission.Add." + i + ".UserId", "%012d".formatted(i));
            }
            tooMany.post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
            assertNoPermissions(source);
        } finally {
            deregister(source.imageId(), OWNER, SOURCE_REGION);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "OperationType,add", "UserId.1,666677778888", "UserGroup.1,all",
            "CreateVolumePermission.Add.1.Group,all", "CreateVolumePermission.Add.1.OrganizationArn,example",
            "CreateVolumePermission.Add.1.UserId.extra,666677778888", "Attribute,productCodes"
    })
    void unsupportedSnapshotFormsDoNotApplyTheValidEntry(String field, String value) {
        Source source = source(1);
        try {
            request("ModifySnapshotAttribute", OWNER, SOURCE_REGION)
                    .formParam("SnapshotId", source.snapshotIds().getFirst())
                    .formParam("CreateVolumePermission.Add.1.UserId", RECIPIENT)
                    .formParam(field, value).post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("UnsupportedOperation"));
            assertNoPermissions(source);
        } finally {
            deregister(source.imageId(), OWNER, SOURCE_REGION);
        }
    }

    @Test
    void ownerRegionAndDryRunBoundPermissionChangesAndSharedDescriptions() {
        Source source = source(1);
        try {
            for (String action : List.of("DescribeImageAttribute", "ModifyImageAttribute")) {
                for (String account : List.of(RECIPIENT, OWNER)) {
                    RequestSpecification request = request(action, account,
                            account.equals(OWNER) ? DESTINATION_REGION : SOURCE_REGION)
                            .formParam("ImageId", source.imageId()).formParam("Attribute", "launchPermission");
                    if (action.startsWith("Modify")) {
                        request.formParam("LaunchPermission.Add.1.UserId", OTHER);
                    }
                    request.post("/").then().statusCode(400)
                            .body("Response.Errors.Error.Code", equalTo("InvalidAMIID.NotFound"));
                }
            }
            for (String action : List.of("DescribeSnapshotAttribute", "ModifySnapshotAttribute")) {
                RequestSpecification request = request(action, RECIPIENT, SOURCE_REGION)
                        .formParam("SnapshotId", source.snapshotIds().getFirst())
                        .formParam("Attribute", "createVolumePermission");
                if (action.startsWith("Modify")) {
                    request.formParam("CreateVolumePermission.Add.1.UserId", OTHER);
                }
                request.post("/").then().statusCode(400)
                        .body("Response.Errors.Error.Code", equalTo("InvalidSnapshot.NotFound"));
            }
            request("ModifyImageAttribute", OWNER, SOURCE_REGION)
                    .formParam("ImageId", source.imageId()).formParam("LaunchPermission.Add.1.UserId", RECIPIENT)
                    .formParam("DryRun", true).post("/").then().statusCode(412)
                    .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));
            request("ModifySnapshotAttribute", OWNER, SOURCE_REGION)
                    .formParam("SnapshotId", source.snapshotIds().getFirst())
                    .formParam("CreateVolumePermission.Add.1.UserId", RECIPIENT)
                    .formParam("DryRun", true).post("/").then().statusCode(412)
                    .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));
            assertNoPermissions(source);
            modifyImage(source.imageId(), "Add", RECIPIENT).statusCode(200);
            modifySnapshot(source.snapshotIds().getFirst(), "Add", RECIPIENT).statusCode(200);
            int beforeImages = ownedImages(RECIPIENT, DESTINATION_REGION);
            int beforeSnapshots = ownedSnapshots(RECIPIENT, DESTINATION_REGION);
            copy(source.imageId()).formParam("DryRun", true).post("/").then().statusCode(412)
                    .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));
            request("DescribeImages", RECIPIENT, SOURCE_REGION).formParam("ImageId.1", source.imageId())
                    .post("/").then().statusCode(200)
                    .body("DescribeImagesResponse.imagesSet.item.imageId", equalTo(source.imageId()))
                    .body("DescribeImagesResponse.imagesSet.item.imageOwnerId", equalTo(OWNER));
            assertEquals(beforeImages, ownedImages(RECIPIENT, DESTINATION_REGION));
            assertEquals(beforeSnapshots, ownedSnapshots(RECIPIENT, DESTINATION_REGION));
            assertEquals(0, ownedImages(RECIPIENT, SOURCE_REGION));
            assertSourceUnchanged(source);
        } finally {
            deregister(source.imageId(), OWNER, SOURCE_REGION);
        }
    }

    @Test
    void explicitUnsupportedCopyOptionsAndSharedTagsRefuseBeforeInsertion() {
        Source source = source(1);
        try {
            modifyImage(source.imageId(), "Add", RECIPIENT).statusCode(200);
            modifySnapshot(source.snapshotIds().getFirst(), "Add", RECIPIENT).statusCode(200);
            int beforeImages = ownedImages(RECIPIENT, DESTINATION_REGION);
            int beforeSnapshots = ownedSnapshots(RECIPIENT, DESTINATION_REGION);
            for (String option : List.of("Encrypted", "CopyImageTags", "KmsKeyId", "DestinationOutpostArn")) {
                copy(source.imageId()).formParam(option, "true").post("/").then().statusCode(400)
                        .body("Response.Errors.Error.Code", equalTo("UnsupportedOperation"));
            }
            request("CreateTags", OWNER, SOURCE_REGION).formParam("ResourceId.1", source.imageId())
                    .formParam("Tag.1.Key", "ec2:SharedTag/example").formParam("Tag.1.Value", "shared")
                    .post("/").then().statusCode(200);
            copy(source.imageId()).post("/").then().statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("UnsupportedOperation"));
            assertEquals(beforeImages, ownedImages(RECIPIENT, DESTINATION_REGION));
            assertEquals(beforeSnapshots, ownedSnapshots(RECIPIENT, DESTINATION_REGION));
        } finally {
            deregister(source.imageId(), OWNER, SOURCE_REGION);
        }
    }

    private record Source(String imageId, List<String> snapshotIds) {}

    private Source source(int devices) {
        List<String> snapshotIds = new ArrayList<>();
        for (int i = 0; i < devices; i++) {
            String volume = request("CreateVolume", OWNER, SOURCE_REGION)
                    .formParam("AvailabilityZone", SOURCE_REGION + "a").formParam("Size", 10)
                    .formParam("VolumeType", "gp2").post("/").then().statusCode(200)
                    .extract().path("CreateVolumeResponse.volumeId");
            try {
                snapshotIds.add(request("CreateSnapshot", OWNER, SOURCE_REGION).formParam("VolumeId", volume)
                        .formParam("TagSpecification.1.ResourceType", "snapshot")
                        .formParam("TagSpecification.1.Tag.1.Key", "PrivateSnapshotTag")
                        .formParam("TagSpecification.1.Tag.1.Value", "owner-only")
                        .post("/").then().statusCode(200).extract().path("CreateSnapshotResponse.snapshotId"));
            } finally {
                request("DeleteVolume", OWNER, SOURCE_REGION).formParam("VolumeId", volume)
                        .post("/").then().statusCode(200);
            }
        }
        RequestSpecification registration = request("RegisterImage", OWNER, SOURCE_REGION)
                .formParam("Name", "shared-source-release").formParam("RootDeviceName", "/dev/xvda");
        for (int i = 0; i < snapshotIds.size(); i++) {
            registration.formParam("BlockDeviceMapping." + (i + 1) + ".DeviceName", "/dev/xvd" + (char) ('a' + i))
                    .formParam("BlockDeviceMapping." + (i + 1) + ".Ebs.SnapshotId", snapshotIds.get(i));
        }
        String imageId = registration.post("/").then().statusCode(200)
                .extract().path("RegisterImageResponse.imageId");
        request("CreateTags", OWNER, SOURCE_REGION).formParam("ResourceId.1", imageId)
                .formParam("Tag.1.Key", "PrivateImageTag").formParam("Tag.1.Value", "owner-only")
                .post("/").then().statusCode(200);
        return new Source(imageId, snapshotIds);
    }

    private void assertSourceUnchanged(Source source) {
        request("DescribeImages", OWNER, SOURCE_REGION).formParam("ImageId.1", source.imageId())
                .post("/").then().statusCode(200)
                .body("DescribeImagesResponse.imagesSet.item.imageOwnerId", equalTo(OWNER))
                .body("DescribeImagesResponse.imagesSet.item.tagSet.item.key", equalTo("PrivateImageTag"))
                .body("DescribeImagesResponse.imagesSet.item.tagSet.item.value", equalTo("owner-only"));
        for (String snapshotId : source.snapshotIds()) {
            request("DescribeSnapshots", OWNER, SOURCE_REGION).formParam("SnapshotId.1", snapshotId)
                    .post("/").then().statusCode(200)
                    .body("DescribeSnapshotsResponse.snapshotSet.item.ownerId", equalTo(OWNER))
                    .body("DescribeSnapshotsResponse.snapshotSet.item.volumeSize", equalTo("10"))
                    .body("DescribeSnapshotsResponse.snapshotSet.item.tagSet.item.key",
                            equalTo("PrivateSnapshotTag"));
        }
    }

    private void assertNoPermissions(Source source) {
        describeImagePermissions(source.imageId()).statusCode(200)
                .body("DescribeImageAttributeResponse.launchPermission", emptyOrNullString());
        for (String snapshotId : source.snapshotIds()) {
            describeSnapshotPermissions(snapshotId).statusCode(200)
                    .body("DescribeSnapshotAttributeResponse.createVolumePermission", emptyOrNullString());
        }
    }

    private ValidatableResponse describeImagePermissions(String imageId) {
        return request("DescribeImageAttribute", OWNER, SOURCE_REGION).formParam("ImageId", imageId)
                .formParam("Attribute", "launchPermission").post("/").then();
    }

    private ValidatableResponse describeSnapshotPermissions(String snapshotId) {
        return request("DescribeSnapshotAttribute", OWNER, SOURCE_REGION).formParam("SnapshotId", snapshotId)
                .formParam("Attribute", "createVolumePermission").post("/").then();
    }

    private ValidatableResponse modifyImage(String imageId, String operation, String userId) {
        return request("ModifyImageAttribute", OWNER, SOURCE_REGION).formParam("ImageId", imageId)
                .formParam("LaunchPermission." + operation + ".1.UserId", userId).post("/").then();
    }

    private ValidatableResponse modifySnapshot(String snapshotId, String operation, String userId) {
        return request("ModifySnapshotAttribute", OWNER, SOURCE_REGION).formParam("SnapshotId", snapshotId)
                .formParam("CreateVolumePermission." + operation + ".1.UserId", userId).post("/").then();
    }

    private RequestSpecification copy(String imageId) {
        return request("CopyImage", RECIPIENT, DESTINATION_REGION).formParam("SourceRegion", SOURCE_REGION)
                .formParam("SourceImageId", imageId).formParam("Name", "copy-" + UUID.randomUUID());
    }

    private int ownedImages(String account, String region) {
        return request("DescribeImages", account, region).formParam("Owner.1", "self")
                .post("/").then().statusCode(200).extract().xmlPath()
                .getList("DescribeImagesResponse.imagesSet.item").size();
    }

    private int ownedSnapshots(String account, String region) {
        return request("DescribeSnapshots", account, region).formParam("Owner.1", "self")
                .post("/").then().statusCode(200).extract().xmlPath()
                .getList("DescribeSnapshotsResponse.snapshotSet.item").size();
    }

    private void deregister(String imageId, String account, String region) {
        request("DeregisterImage", account, region).formParam("ImageId", imageId)
                .formParam("DeleteAssociatedSnapshots", true).post("/").then().statusCode(200);
    }

    private RequestSpecification request(String action, String account, String region) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20261009/" + region + "/ec2/aws4_request")
                .formParam("Action", action).formParam("Version", "2016-11-15");
    }
}
