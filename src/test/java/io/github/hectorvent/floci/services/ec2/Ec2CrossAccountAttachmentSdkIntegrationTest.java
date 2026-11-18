package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2CrossAccountAttachmentSdkIntegrationTest {
    private static final String OWNER = "000000003541";
    private static final String GRANTEE = "000000003542";

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void grantAttachForceRevokeAndDetachRetainEachAccountsRecords() {
        try (Ec2Client owner = client(OWNER); Ec2Client grantee = client(GRANTEE)) {
            String eni = owner.createNetworkInterface(request -> request.subnetId(subnet(owner)))
                    .networkInterface().networkInterfaceId();
            String instance = null;
            String attachment = null;
            String permission = null;
            try {
                instance = grantee.runInstances(request -> request.imageId("ami-amazonlinux2023")
                        .instanceType("t2.micro").minCount(1).maxCount(1).subnetId(subnet(grantee)))
                        .instances().getFirst().instanceId();
                String target = instance;
                grantee.describeInstances(request -> request.instanceIds(target));
                Ec2Exception denied = assertThrows(Ec2Exception.class, () -> grantee.attachNetworkInterface(
                        request -> request.networkInterfaceId(eni).instanceId(target).deviceIndex(1)));
                assertEquals("InvalidNetworkInterfaceID.NotFound", denied.awsErrorDetails().errorCode());
                permission = owner.createNetworkInterfacePermission(request -> request.networkInterfaceId(eni)
                        .awsAccountId(GRANTEE).permission("INSTANCE-ATTACH"))
                        .interfacePermission().networkInterfacePermissionId();
                String grant = permission;
                attachment = grantee.attachNetworkInterface(request -> request.networkInterfaceId(eni)
                        .instanceId(target).deviceIndex(1)).attachmentId();
                NetworkInterface held = owner.describeNetworkInterfaces(request -> request.networkInterfaceIds(eni))
                        .networkInterfaces().getFirst();
                assertEquals(OWNER, held.ownerId());
                assertEquals(GRANTEE, held.attachment().instanceOwnerId());
                assertEquals(attachment, held.attachment().attachmentId());
                assertTrue(owner.describeNetworkInterfaces(request -> request.filters(Filter.builder()
                                .name("attachment.instance-owner-id").values(GRANTEE).build()))
                        .networkInterfaces().stream().anyMatch(value -> eni.equals(value.networkInterfaceId())));
                assertEquals(OWNER, grantee.describeInstances(request -> request.instanceIds(target))
                        .reservations().getFirst().instances().getFirst().networkInterfaces().stream()
                        .filter(value -> eni.equals(value.networkInterfaceId())).findFirst().orElseThrow().ownerId());
                Ec2Exception inUse = assertThrows(Ec2Exception.class, () -> owner.deleteNetworkInterfacePermission(
                        request -> request.networkInterfacePermissionId(grant)));
                assertEquals("OperationNotPermitted", inUse.awsErrorDetails().errorCode());
                assertTrue(owner.deleteNetworkInterfacePermission(request -> request
                        .networkInterfacePermissionId(grant).force(true)).returnValue());
                permission = null;
                assertEquals(attachment, owner.describeNetworkInterfaces(request -> request.networkInterfaceIds(eni))
                        .networkInterfaces().getFirst().attachment().attachmentId());
                String attached = attachment;
                owner.detachNetworkInterface(request -> request.attachmentId(attached));
                attachment = null;
                assertTrue(grantee.describeInstances(request -> request.instanceIds(target))
                        .reservations().getFirst().instances().getFirst().networkInterfaces().stream()
                        .noneMatch(value -> eni.equals(value.networkInterfaceId())));
                Ec2Exception revoked = assertThrows(Ec2Exception.class, () -> grantee.attachNetworkInterface(
                        request -> request.networkInterfaceId(eni).instanceId(target).deviceIndex(1)));
                assertEquals("InvalidNetworkInterfaceID.NotFound", revoked.awsErrorDetails().errorCode());
            } finally {
                if (attachment != null) {
                    String attached = attachment;
                    owner.detachNetworkInterface(request -> request.attachmentId(attached));
                }
                if (permission != null) {
                    String grant = permission;
                    owner.deleteNetworkInterfacePermission(request -> request.networkInterfacePermissionId(grant));
                }
                if (instance != null) {
                    String target = instance;
                    grantee.terminateInstances(request -> request.instanceIds(target));
                }
                owner.deleteNetworkInterface(request -> request.networkInterfaceId(eni));
            }
        }
    }

    private Ec2Client client(String account) {
        return Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }

    private static String subnet(Ec2Client client) {
        String vpc = client.describeVpcs(request -> request.filters(Filter.builder()
                .name("is-default").values("true").build())).vpcs().getFirst().vpcId();
        return client.describeSubnets(request -> request.filters(
                Filter.builder().name("vpc-id").values(vpc).build(),
                Filter.builder().name("availability-zone").values("us-east-1a").build()))
                .subnets().getFirst().subnetId();
    }
}
