package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceNetworkInterfaceSpecification;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagDescription;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2InterfaceTagLifecycleIntegrationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagsFollowInterfaceRetentionAndExplicitDeletion(boolean deleteOnTermination) throws Exception {
        try (Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            String vpc = client.createVpc(b -> b.cidrBlock("10.232.0.0/24")).vpc().vpcId();
            try (AutoCloseable deleteVpc = () -> client.deleteVpc(b -> b.vpcId(vpc))) {
                String subnet = client.createSubnet(b -> b.vpcId(vpc).cidrBlock("10.232.0.0/24"))
                        .subnet().subnetId();
                try (AutoCloseable deleteSubnet = () -> client.deleteSubnet(b -> b.subnetId(subnet))) {
                    Instance launched = client.runInstances(
                            b -> b.imageId("ami-1234567890abcdef0").instanceType("t3.micro").minCount(1).maxCount(1)
                                    .networkInterfaces(InstanceNetworkInterfaceSpecification.builder().subnetId(subnet)
                                            .deviceIndex(0).deleteOnTermination(deleteOnTermination).build()))
                            .instances().getFirst();
                    String host = launched.instanceId();
                    String eni = launched.networkInterfaces().getFirst().networkInterfaceId();
                    try (AutoCloseable cleanup = () -> {
                        client.terminateInstances(b -> b.instanceIds(host));
                        deleteIfPresent(client, eni);
                    }) {
                        client.createTags(b -> b.resources(eni).tags(Tag.builder().key("lifetime").value("eni").build()));
                        if (!deleteOnTermination) {
                            assertEquals("InvalidParameterValue", assertThrows(Ec2Exception.class,
                                    () -> client.deleteNetworkInterface(b -> b.networkInterfaceId(eni)))
                                    .awsErrorDetails().errorCode());
                        }
                        assertEquals(1, client.describeTags(b -> b.filters(Filter.builder()
                                .name("resource-id").values(eni).build())).tags().size());
                        client.terminateInstances(b -> b.instanceIds(host));
                        assertEquals(deleteOnTermination, client.describeTags(b -> b.filters(Filter.builder()
                                .name("resource-id").values(eni).build())).tags().isEmpty());
                        if (!deleteOnTermination) {
                            assertEquals("eni", client.describeNetworkInterfaces(b -> b.networkInterfaceIds(eni))
                                    .networkInterfaces().getFirst().tagSet().getFirst().value());
                            client.deleteNetworkInterface(b -> b.networkInterfaceId(eni));
                        }
                        assertTrue(client.describeTags(b -> b.filters(Filter.builder()
                                .name("resource-id").values(eni).build())).tags().isEmpty());
                        assertEquals("InvalidNetworkInterfaceID.NotFound", assertThrows(Ec2Exception.class,
                                () -> client.describeNetworkInterfaces(b -> b.networkInterfaceIds(eni)))
                                .awsErrorDetails().errorCode());
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void templateAndRequestTagUnionIsValidatedBeforeAllocation(boolean override) throws Exception {
        assertInterfaceTagUnion(override, false);
    }

    @Test
    void overlongTemplateValueIsRejectedBeforeAllocationEvenWhenRequestOverridesIt() throws Exception {
        assertInterfaceTagUnion(true, true);
    }

    private void assertInterfaceTagUnion(boolean override, boolean invalidTemplate) throws Exception {
        try (Ec2Client client = Ec2Client.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            String vpc = client.createVpc(b -> b.cidrBlock("10.233.0.0/24")).vpc().vpcId();
            try (AutoCloseable deleteVpc = () -> client.deleteVpc(b -> b.vpcId(vpc))) {
                String subnet = client.createSubnet(b -> b.vpcId(vpc).cidrBlock("10.233.0.0/24"))
                        .subnet().subnetId();
                try (AutoCloseable deleteSubnet = () -> client.deleteSubnet(b -> b.subnetId(subnet))) {
                    List<Tag> templateTags = new ArrayList<>();
                    for (int i = 0; i < 50; i++) {
                        templateTags.add(Tag.builder().key("key" + i)
                                .value(invalidTemplate && i == 0 ? "x".repeat(257) : "template").build());
                    }
                    String template = client.createLaunchTemplate(b -> b.launchTemplateName("tag-union-" + UUID.randomUUID())
                            .launchTemplateData(d -> d.imageId("ami-1234567890abcdef0").instanceType("t3.micro")
                                    .tagSpecifications(t -> t.resourceType("network-interface").tags(templateTags))))
                            .launchTemplate().launchTemplateId();
                    try (AutoCloseable deleteTemplate = () -> client.deleteLaunchTemplate(b -> b.launchTemplateId(template));
                         AutoCloseable cleanupInstances = () -> {
                             List<String> ids = client.describeInstances(b -> b.filters(Filter.builder()
                                             .name("subnet-id").values(subnet).build())).reservations().stream()
                                     .flatMap(reservation -> reservation.instances().stream())
                                     .map(Instance::instanceId).toList();
                             if (!ids.isEmpty()) {
                                 client.terminateInstances(b -> b.instanceIds(ids));
                             }
                         }) {
                        int volumesBefore = client.describeVolumes().volumes().size();
                        if (override && !invalidTemplate) {
                            Instance instance = client.runInstances(b -> b.launchTemplate(t -> t.launchTemplateId(template))
                                            .subnetId(subnet).minCount(1).maxCount(1)
                                            .tagSpecifications(t -> t.resourceType("network-interface")
                                                    .tags(Tag.builder().key("key0").value("request").build())))
                                    .instances().getFirst();
                            String eni = instance.networkInterfaces().getFirst().networkInterfaceId();
                            List<TagDescription> tags =
                                    client.describeTags(b -> b.filters(Filter.builder().name("resource-id").values(eni).build()))
                                            .tags();
                            assertEquals(50, tags.size());
                            assertEquals("request", tags.stream().filter(t -> t.key().equals("key0"))
                                    .findFirst().orElseThrow().value());
                        } else {
                            Ec2Exception error = assertThrows(Ec2Exception.class,
                                    () -> client.runInstances(b -> b.launchTemplate(t -> t.launchTemplateId(template))
                                            .subnetId(subnet).minCount(1).maxCount(1)
                                            .tagSpecifications(t -> t.resourceType("network-interface")
                                                    .tags(Tag.builder().key(override ? "key0" : "additional")
                                                            .value("request").build()))));
                            assertEquals(invalidTemplate ? "InvalidParameterValue" : "TagLimitExceeded",
                                    error.awsErrorDetails().errorCode());
                            assertTrue(client.describeInstances(b -> b.filters(Filter.builder().name("subnet-id")
                                            .values(subnet).build())).reservations().stream()
                                    .flatMap(reservation -> reservation.instances().stream()).findAny().isEmpty());
                            assertEquals(volumesBefore, client.describeVolumes().volumes().size());
                            assertTrue(client.describeNetworkInterfaces(b -> b.filters(Filter.builder().name("subnet-id")
                                    .values(subnet).build())).networkInterfaces().isEmpty());
                        }
                    }
                }
            }
        }
    }

    private static void deleteIfPresent(Ec2Client client, String interfaceId) {
        try {
            client.deleteNetworkInterface(b -> b.networkInterfaceId(interfaceId));
        } catch (Ec2Exception expected) {
            // Delete-on-termination and the explicit-delete assertion may already have removed it.
            if (!"InvalidNetworkInterfaceID.NotFound".equals(expected.awsErrorDetails().errorCode())) {
                throw expected;
            }
        }
    }
}
