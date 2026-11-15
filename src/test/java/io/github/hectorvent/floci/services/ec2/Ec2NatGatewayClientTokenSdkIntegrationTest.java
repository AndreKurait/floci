package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateNatGatewayRequest;
import software.amazon.awssdk.services.ec2.model.CreateNatGatewayResponse;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.NatGateway;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2NatGatewayClientTokenSdkIntegrationTest {

    @Test
    void sdkReplayEchoesTheLiteralTokenAndPreservesCurrentTagsAndRefusedUpdateState() {
        try (Owned network = new Owned()) {
            CreateNatGatewayRequest request = network.request();
            CreateNatGatewayResponse first = network.create(request);
            assertEquals(request.clientToken(), first.clientToken());
            network.client.createTags(builder -> builder.resources(first.natGateway().natGatewayId())
                    .tags(Tag.builder().key("note").value("changed").build()));
            NatGateway current = network.gateways().getFirst();
            List<NetworkInterface> interfaces = network.interfaces();

            CreateNatGatewayResponse replay = network.create(request);

            assertEquals(request.clientToken(), replay.clientToken());
            assertEquals(current, replay.natGateway());
            assertEquals(Map.of("note", "changed"), replay.natGateway().tags().stream()
                    .collect(Collectors.toMap(Tag::key, Tag::value)));
            assertEquals("created", request.tagSpecifications().getFirst().tags().getFirst().value());
            Ec2Exception mismatch = assertThrows(Ec2Exception.class, () -> network.create(
                    request.toBuilder().privateIpAddress("10.199.1.41").build()));
            assertEquals(400, mismatch.statusCode());
            assertEquals("IdempotentParameterMismatch", mismatch.awsErrorDetails().errorCode());
            assertEquals(List.of(current), network.gateways());
            assertEquals(interfaces, network.interfaces());
        }
    }

    @Test
    void sdkTokenCanCreateANewGatewayAfterTheOriginalGatewayHasBeenDeleted() {
        try (Owned network = new Owned()) {
            CreateNatGatewayRequest request = network.request();
            CreateNatGatewayResponse first = network.create(request);
            CreateNatGatewayResponse replay = network.create(request);
            assertEquals(first.natGateway().natGatewayId(), replay.natGateway().natGatewayId());
            network.delete(first.natGateway().natGatewayId());
            assertTrue(network.gateways().isEmpty());
            assertTrue(network.interfaces().isEmpty());

            CreateNatGatewayResponse replacement = network.create(request);

            assertEquals(request.clientToken(), replacement.clientToken());
            assertNotEquals(first.natGateway().natGatewayId(), replacement.natGateway().natGatewayId());
            assertNotEquals(first.natGateway().natGatewayAddresses().getFirst().networkInterfaceId(),
                    replacement.natGateway().natGatewayAddresses().getFirst().networkInterfaceId());
            assertEquals("10.199.1.40", replacement.natGateway().natGatewayAddresses().getFirst().privateIp());
            assertEquals(List.of(replacement.natGateway()), network.gateways());
            assertEquals(1, network.interfaces().size());
            assertEquals(replacement.natGateway().natGatewayId(),
                    network.create(request).natGateway().natGatewayId());
        }
    }

    private static final class Owned implements AutoCloseable {
        private final Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "814560000001", Region.US_EAST_2);
        private final String token = "SDK-Nat-" + UUID.randomUUID();
        private final Set<String> knownGateways = new LinkedHashSet<>();
        private String vpc;
        private String subnet;

        private Owned() {
            try {
                vpc = client.createVpc(builder -> builder.cidrBlock("10.199.0.0/16")).vpc().vpcId();
                subnet = client.createSubnet(builder -> builder.vpcId(vpc).cidrBlock("10.199.1.0/24")
                        .availabilityZone("us-east-2a")).subnet().subnetId();
            } catch (RuntimeException failure) {
                try {
                    close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        private CreateNatGatewayResponse create(CreateNatGatewayRequest request) {
            CreateNatGatewayResponse response = client.createNatGateway(request);
            knownGateways.add(response.natGateway().natGatewayId());
            return response;
        }

        private void delete(String id) {
            client.deleteNatGateway(builder -> builder.natGatewayId(id));
            knownGateways.remove(id);
        }

        private CreateNatGatewayRequest request() {
            return CreateNatGatewayRequest.builder().subnetId(subnet).connectivityType("private")
                    .privateIpAddress("10.199.1.40").clientToken(token)
                    .tagSpecifications(TagSpecification.builder().resourceType("natgateway")
                            .tags(Tag.builder().key("note").value("created").build()).build())
                    .build();
        }

        private List<NatGateway> gateways() {
            return client.describeNatGateways(builder -> builder.filter(
                    Filter.builder().name("subnet-id").values(subnet).build())).natGateways();
        }

        private List<NetworkInterface> interfaces() {
            return client.describeNetworkInterfaces(builder -> builder.filters(
                    Filter.builder().name("subnet-id").values(subnet).build())).networkInterfaces();
        }

        @Override
        public void close() {
            RuntimeException failures = new IllegalStateException("Owned NAT SDK fixture cleanup failed");
            if (subnet != null) {
                Set<String> owned = new LinkedHashSet<>(knownGateways);
                try {
                    gateways().forEach(gateway -> owned.add(gateway.natGatewayId()));
                } catch (RuntimeException failure) {
                    failures.addSuppressed(failure);
                }
                for (String id : owned) {
                    try {
                        delete(id);
                    } catch (Ec2Exception failure) {
                        if (!"NatGatewayNotFound".equals(failure.awsErrorDetails().errorCode())) {
                            failures.addSuppressed(failure);
                        }
                    } catch (RuntimeException failure) {
                        failures.addSuppressed(failure);
                    }
                }
                try {
                    client.deleteSubnet(builder -> builder.subnetId(subnet));
                } catch (RuntimeException failure) {
                    failures.addSuppressed(failure);
                }
            }
            if (vpc != null) {
                try {
                    client.deleteVpc(builder -> builder.vpcId(vpc));
                } catch (RuntimeException failure) {
                    failures.addSuppressed(failure);
                }
            }
            client.close();
            if (failures.getSuppressed().length != 0) {
                throw failures;
            }
        }
    }
}
