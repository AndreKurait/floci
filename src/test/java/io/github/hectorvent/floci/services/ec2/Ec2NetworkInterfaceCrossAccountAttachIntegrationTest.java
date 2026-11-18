package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Attaching a network interface owned by another account, as the EC2 API Reference documents it.
 *
 * <p>CreateNetworkInterfacePermission "Grants an AWS-authorized account permission to attach the
 * specified network interface to an instance in their account", and DeleteNetworkInterfacePermission
 * documents that "By default, you cannot delete the permission if the account for which you're
 * removing the permission has attached the network interface to an instance. However, you can force
 * delete the permission, regardless of any attachment." The attachment names the instance's account
 * in {@code instanceOwnerId} ("The AWS account ID of the owner of the instance"), which
 * DescribeNetworkInterfaces filters on as {@code attachment.instance-owner-id}.
 *
 * <p>Each test builds its own interface and instance under accounts no other test uses. Mock mode
 * (floci.services.ec2.mock=true in test application.yml) needs no Docker.
 */
@QuarkusTest
class Ec2NetworkInterfaceCrossAccountAttachIntegrationTest {

    private static final String OWNER = "000000003521";
    private static final String GRANTEE = "000000003522";
    private static final String STRANGER = "000000003523";

    private static final String INSTANCE_INTERFACES =
            "DescribeInstancesResponse.reservationSet.item.instancesSet.item.networkInterfaceSet.item";
    private static final String INTERFACE_IDS = "DescribeNetworkInterfacesResponse.networkInterfaceSet.item.networkInterfaceId";

    private static String auth(String account) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261118/us-east-1/ec2/aws4_request";
    }

    private static ValidatableResponse call(String account, String action, String... params) {
        RequestSpecification request = given()
                .formParam("Action", action)
                .header("Authorization", auth(account));
        for (int i = 0; i < params.length; i += 2) {
            request.formParam(params[i], params[i + 1]);
        }
        return request.when().post("/").then();
    }

    private static String defaultSubnet(String account) {
        String vpcId = call(account, "DescribeVpcs", "Filter.1.Name", "is-default", "Filter.1.Value.1", "true")
                .statusCode(200)
                .extract().path("DescribeVpcsResponse.vpcSet.item[0].vpcId");
        return call(account, "DescribeSubnets",
                "Filter.1.Name", "vpc-id", "Filter.1.Value.1", vpcId,
                "Filter.2.Name", "availability-zone", "Filter.2.Value.1", "us-east-1a")
                .statusCode(200)
                .extract().path("DescribeSubnetsResponse.subnetSet.item[0].subnetId");
    }

    /** The values at {@code path} as a list, also when the response holds one item or none. */
    private static List<String> values(ValidatableResponse response, String path) {
        return response.statusCode(200).extract().xmlPath().getList(path, String.class);
    }

    private static String ownersInterface() {
        return call(OWNER, "CreateNetworkInterface",
                "SubnetId", defaultSubnet(OWNER), "Description", "cross-account attach")
                .statusCode(200)
                .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");
    }

    /** A running instance of {@code account}: mock mode settles it on the first describe. */
    private static String runningInstance(String account) {
        String instanceId = call(account, "RunInstances",
                "ImageId", "ami-amazonlinux2023", "InstanceType", "t2.micro",
                "MinCount", "1", "MaxCount", "1", "SubnetId", defaultSubnet(account))
                .statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        call(account, "DescribeInstances", "InstanceId.1", instanceId)
                .statusCode(200)
                .body("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name",
                        equalTo("running"));
        return instanceId;
    }

    private static String grantInstanceAttach(String eni, String account) {
        return call(OWNER, "CreateNetworkInterfacePermission",
                "NetworkInterfaceId", eni, "AwsAccountId", account, "Permission", "INSTANCE-ATTACH")
                .statusCode(200)
                .extract().path("CreateNetworkInterfacePermissionResponse.interfacePermission"
                        + ".networkInterfacePermissionId");
    }

    private static String attach(String account, String eni, String instanceId) {
        return call(account, "AttachNetworkInterface",
                "NetworkInterfaceId", eni, "InstanceId", instanceId, "DeviceIndex", "1")
                .statusCode(200)
                .body("AttachNetworkInterfaceResponse.attachmentId", startsWith("eni-attach-"))
                .extract().path("AttachNetworkInterfaceResponse.attachmentId");
    }

    @Test
    void anAccountAttachesAnotherAccountsInterfaceOnlyOnceGrantedInstanceAttach() {
        String eni = ownersInterface();
        String instanceId = runningInstance(GRANTEE);

        call(GRANTEE, "AttachNetworkInterface", "NetworkInterfaceId", eni, "InstanceId", instanceId, "DeviceIndex", "1")
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfaceID.NotFound"));

        grantInstanceAttach(eni, GRANTEE);
        String attachmentId = attach(GRANTEE, eni, instanceId);

        call(OWNER, "DescribeNetworkInterfaces", "NetworkInterfaceId.1", eni)
                .statusCode(200)
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.ownerId", equalTo(OWNER))
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.status", equalTo("in-use"))
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.attachment.attachmentId",
                        equalTo(attachmentId))
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.attachment.instanceId",
                        equalTo(instanceId))
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.attachment.instanceOwnerId",
                        equalTo(GRANTEE));

        assertThat(values(call(GRANTEE, "DescribeInstances", "InstanceId.1", instanceId),
                INSTANCE_INTERFACES + ".networkInterfaceId"), hasItem(eni));
        call(GRANTEE, "DescribeInstances", "InstanceId.1", instanceId)
                .statusCode(200)
                .body(INSTANCE_INTERFACES + ".find { it.networkInterfaceId == '" + eni + "' }.ownerId",
                        equalTo(OWNER));

        // AWS documents no view of the interface for the account it is attached for, so it stays
        // another account's interface there, as every other action of that account treats it.
        call(GRANTEE, "DescribeNetworkInterfaces", "NetworkInterfaceId.1", eni)
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfaceID.NotFound"));
    }

    @Test
    void theGrantIsForTheAccountItNames() {
        String eni = ownersInterface();
        grantInstanceAttach(eni, GRANTEE);
        String strangersInstance = runningInstance(STRANGER);

        call(STRANGER, "AttachNetworkInterface",
                "NetworkInterfaceId", eni, "InstanceId", strangersInstance, "DeviceIndex", "1")
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfaceID.NotFound"));
        call(OWNER, "DescribeNetworkInterfaces", "NetworkInterfaceId.1", eni)
                .statusCode(200)
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.status", equalTo("available"));
    }

    @Test
    void theOwnerFiltersItsInterfacesByTheAccountOfTheInstanceTheyAreAttachedTo() {
        String eni = ownersInterface();
        grantInstanceAttach(eni, GRANTEE);
        attach(GRANTEE, eni, runningInstance(GRANTEE));

        assertThat(values(call(OWNER, "DescribeNetworkInterfaces",
                "Filter.1.Name", "attachment.instance-owner-id", "Filter.1.Value.1", GRANTEE), INTERFACE_IDS),
                hasItem(eni));
        assertThat(values(call(OWNER, "DescribeNetworkInterfaces",
                "Filter.1.Name", "attachment.instance-owner-id", "Filter.1.Value.1", STRANGER), INTERFACE_IDS),
                not(hasItem(eni)));
    }

    @Test
    void aPermissionInUseIsDeletedOnlyWithForceAndTheAttachmentStays() {
        String eni = ownersInterface();
        String permissionId = grantInstanceAttach(eni, GRANTEE);
        String instanceId = runningInstance(GRANTEE);
        String attachmentId = attach(GRANTEE, eni, instanceId);

        call(OWNER, "DeleteNetworkInterfacePermission", "NetworkInterfacePermissionId", permissionId)
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("OperationNotPermitted"));
        call(OWNER, "DescribeNetworkInterfacePermissions", "NetworkInterfacePermissionId.1", permissionId)
                .statusCode(200)
                .body("DescribeNetworkInterfacePermissionsResponse.networkInterfacePermissions.item.awsAccountId",
                        equalTo(GRANTEE));

        call(OWNER, "DeleteNetworkInterfacePermission",
                "NetworkInterfacePermissionId", permissionId, "Force", "true")
                .statusCode(200)
                .body("DeleteNetworkInterfacePermissionResponse.return", equalTo("true"));
        call(OWNER, "DescribeNetworkInterfacePermissions", "NetworkInterfacePermissionId.1", permissionId)
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfacePermissionID.NotFound"));
        call(OWNER, "DescribeNetworkInterfaces", "NetworkInterfaceId.1", eni)
                .statusCode(200)
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.attachment.attachmentId",
                        equalTo(attachmentId))
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.attachment.instanceOwnerId",
                        equalTo(GRANTEE));
    }

    @Test
    void aPermissionNotInUseIsDeletedAndNoLongerLetsTheAccountAttach() {
        String eni = ownersInterface();
        String permissionId = grantInstanceAttach(eni, GRANTEE);

        call(OWNER, "DeleteNetworkInterfacePermission",
                "NetworkInterfacePermissionId", permissionId, "DryRun", "true")
                .statusCode(412)
                .body("Response.Errors.Error.Code", equalTo("DryRunOperation"));
        call(GRANTEE, "DeleteNetworkInterfacePermission", "NetworkInterfacePermissionId", permissionId)
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfacePermissionID.NotFound"));
        call(OWNER, "DeleteNetworkInterfacePermission", "NetworkInterfacePermissionId", permissionId)
                .statusCode(200)
                .body("DeleteNetworkInterfacePermissionResponse.return", equalTo("true"));

        call(GRANTEE, "AttachNetworkInterface",
                "NetworkInterfaceId", eni, "InstanceId", runningInstance(GRANTEE), "DeviceIndex", "1")
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfaceID.NotFound"));
        call(OWNER, "DeleteNetworkInterfacePermission", "NetworkInterfacePermissionId", permissionId)
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfacePermissionID.NotFound"));
    }

    @Test
    void aDeletePermissionRequestOutsideTheModelIsRefused() {
        call(OWNER, "DeleteNetworkInterfacePermission")
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("MissingParameter"));
        call(OWNER, "DeleteNetworkInterfacePermission", "NetworkInterfacePermissionId", "perm-0123")
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidNetworkInterfacePermissionID.Malformed"));
    }

    @Test
    void theInstancesOwnAccountKeepsItsRecordWhenTheInterfaceIsReleased() {
        String eni = ownersInterface();
        grantInstanceAttach(eni, GRANTEE);
        String instanceId = runningInstance(GRANTEE);
        String attachmentId = attach(GRANTEE, eni, instanceId);

        call(OWNER, "DetachNetworkInterface", "AttachmentId", attachmentId)
                .statusCode(200)
                .body("DetachNetworkInterfaceResponse.return", equalTo("true"));
        assertThat(values(call(GRANTEE, "DescribeInstances", "InstanceId.1", instanceId),
                INSTANCE_INTERFACES + ".networkInterfaceId"), not(hasItem(eni)));

        attach(GRANTEE, eni, instanceId);
        call(GRANTEE, "TerminateInstances", "InstanceId.1", instanceId).statusCode(200);
        call(OWNER, "DescribeNetworkInterfaces", "NetworkInterfaceId.1", eni)
                .statusCode(200)
                .body("DescribeNetworkInterfacesResponse.networkInterfaceSet.item.status", equalTo("available"))
                .body(not(containsString("<attachment>")));
    }
}
