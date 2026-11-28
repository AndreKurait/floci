package io.floci.aws.services.ec2;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2AttachmentSdkIntegrationTest {
    private static final String ACCOUNT = "000000003551";

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void attachAndDetachKeepStandaloneAndInstanceViewsConsistent() {
        try (Ec2Client client = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .build()) {
            String vpc = client.describeVpcs(request -> request.filters(Filter.builder()
                    .name("is-default").values("true").build())).vpcs().getFirst().vpcId();
            String subnet = client.describeSubnets(request -> request.filters(
                    Filter.builder().name("vpc-id").values(vpc).build(),
                    Filter.builder().name("availability-zone").values("us-east-1a").build()))
                    .subnets().getFirst().subnetId();
            String eni = client.createNetworkInterface(request -> request.subnetId(subnet))
                    .networkInterface().networkInterfaceId();
            String instance = null;
            String attachment = null;
            try {
                instance = client.runInstances(request -> request.imageId("ami-amazonlinux2023")
                        .instanceType("t2.micro").minCount(1).maxCount(1).subnetId(subnet))
                        .instances().getFirst().instanceId();
                String target = instance;
                client.describeInstances(request -> request.instanceIds(target));
                attachment = client.attachNetworkInterface(request -> request.networkInterfaceId(eni)
                        .instanceId(target).deviceIndex(1)).attachmentId();
                NetworkInterface attached = client.describeNetworkInterfaces(request -> request.networkInterfaceIds(eni))
                        .networkInterfaces().getFirst();
                assertEquals(ACCOUNT, attached.ownerId());
                assertEquals(target, attached.attachment().instanceId());
                assertEquals(attachment, attached.attachment().attachmentId());
                assertEquals(1, attached.attachment().deviceIndex());
                assertTrue(client.describeInstances(request -> request.instanceIds(target))
                        .reservations().getFirst().instances().getFirst().networkInterfaces().stream()
                        .anyMatch(value -> eni.equals(value.networkInterfaceId())
                                && attached.macAddress().equals(value.macAddress())
                                && attached.privateIpAddress().equals(value.privateIpAddress())));
                String attachedId = attachment;
                client.detachNetworkInterface(request -> request.attachmentId(attachedId));
                attachment = null;
                assertTrue(client.describeInstances(request -> request.instanceIds(target))
                        .reservations().getFirst().instances().getFirst().networkInterfaces().stream()
                        .noneMatch(value -> eni.equals(value.networkInterfaceId())));
                assertEquals("available", client.describeNetworkInterfaces(request -> request.networkInterfaceIds(eni))
                        .networkInterfaces().getFirst().statusAsString());
            } finally {
                if (attachment != null) {
                    String attachedId = attachment;
                    client.detachNetworkInterface(request -> request.attachmentId(attachedId));
                }
                if (instance != null) {
                    String target = instance;
                    client.terminateInstances(request -> request.instanceIds(target));
                }
                client.deleteNetworkInterface(request -> request.networkInterfaceId(eni));
            }
        }
    }
}
