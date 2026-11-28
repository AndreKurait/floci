package io.floci.aws.services.ec2;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.CreateNetworkInterfaceRequest;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceIpv6Address;
import software.amazon.awssdk.services.ec2.model.NetworkInterface;
import software.amazon.awssdk.services.ec2.model.NetworkInterfaceIpv6Address;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** IPv6 addresses on network interfaces, driven through the SDK so the wire names are the SDK's own. */
@QuarkusTest
class Ec2AssignIpv6IntegrationTest {
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void assignAndUnassignReportOnlyWhatTheyChanged() {
        Fixture fixture = fixture();
        String eni = create(fixture, request -> { });
        String named = fixture.prefix() + "abcd";

        assertEquals(List.of(named), fixture.client().assignIpv6Addresses(request -> request
                .networkInterfaceId(eni).ipv6Addresses(named)).assignedIpv6Addresses());
        assertEquals(List.of(), fixture.client().assignIpv6Addresses(request -> request
                .networkInterfaceId(eni).ipv6Addresses(named)).assignedIpv6Addresses());
        List<String> drawn = fixture.client().assignIpv6Addresses(request -> request
                .networkInterfaceId(eni).ipv6AddressCount(1)).assignedIpv6Addresses();
        assertEquals(1, drawn.size());
        assertEquals(List.of(named, drawn.getFirst()), addresses(describe(fixture, eni)));

        assertEquals(List.of(named), fixture.client().unassignIpv6Addresses(request -> request
                .networkInterfaceId(eni).ipv6Addresses(named, fixture.prefix() + "ffff")).unassignedIpv6Addresses());
        assertEquals(drawn, addresses(describe(fixture, eni)));
    }

    @Test
    void assignNeedsEitherAListOrACount() {
        Fixture fixture = fixture();
        String eni = create(fixture, request -> { });

        Ec2Exception neither = assertThrows(Ec2Exception.class, () -> fixture.client()
                .assignIpv6Addresses(request -> request.networkInterfaceId(eni)));
        Ec2Exception both = assertThrows(Ec2Exception.class, () -> fixture.client()
                .assignIpv6Addresses(request -> request.networkInterfaceId(eni)
                        .ipv6Addresses(fixture.prefix() + "1234").ipv6AddressCount(1)));

        Ec2Exception listAndZero = assertThrows(Ec2Exception.class, () -> fixture.client()
                .assignIpv6Addresses(request -> request.networkInterfaceId(eni)
                        .ipv6Addresses(fixture.prefix() + "1234").ipv6AddressCount(0)));


        assertEquals("MissingParameter", neither.awsErrorDetails().errorCode());
        for (Ec2Exception error : List.of(both, listAndZero)) {
            assertEquals("InvalidParameterCombination", error.awsErrorDetails().errorCode());
        }
        assertEquals(List.of(), addresses(describe(fixture, eni)));
    }

    @Test
    void aCountBeyondWhatAnInterfaceHoldsIsRefused() {
        Fixture fixture = fixture();
        String eni = create(fixture, request -> { });

        Ec2Exception error = assertThrows(Ec2Exception.class, () -> fixture.client()
                .assignIpv6Addresses(request -> request.networkInterfaceId(eni).ipv6AddressCount(100000)));

        assertEquals("InvalidParameterValue", error.awsErrorDetails().errorCode());
        assertEquals(List.of(), addresses(describe(fixture, eni)));
    }

    @Test
    void aReservedAddressIsRefused() {
        Fixture fixture = fixture();
        String eni = create(fixture, request -> { });

        for (String reserved : List.of(fixture.network(), fixture.prefix() + "1", fixture.prefix() + "3")) {
            Ec2Exception error = assertThrows(Ec2Exception.class, () -> fixture.client()
                    .assignIpv6Addresses(request -> request.networkInterfaceId(eni).ipv6Addresses(reserved)));
            assertEquals("InvalidParameterValue", error.awsErrorDetails().errorCode(), reserved);
        }
        assertEquals(List.of(), addresses(describe(fixture, eni)));
    }

    @Test
    void theLaunchCreatedPrimaryInterfaceTakesAddresses() {
        Fixture fixture = fixture();
        String token = UUID.randomUUID().toString();
        cleanup.register(() -> fixture.client().describeInstances(request -> request.filters(
                Filter.builder().name("client-token").values(token).build())).reservations().forEach(reservation ->
                        reservation.instances().forEach(instance -> fixture.client().terminateInstances(
                                request -> request.instanceIds(instance.instanceId())))));
        String instance = fixture.client().runInstances(request -> request.imageId("ami-amazonlinux2023")
                .instanceType("t2.micro").minCount(1).maxCount(1).subnetId(fixture.subnet()).clientToken(token))
                .instances().getFirst().instanceId();
        String primary = fixture.client().describeInstances(request -> request.instanceIds(instance))
                .reservations().getFirst().instances().getFirst().networkInterfaces().getFirst().networkInterfaceId();

        List<String> assigned = fixture.client().assignIpv6Addresses(request -> request.networkInterfaceId(primary)
                .ipv6AddressCount(1)).assignedIpv6Addresses();

        assertEquals(assigned, addresses(describe(fixture, primary)));
        assertEquals(assigned, fixture.client().describeInstances(request -> request.instanceIds(instance))
                .reservations().getFirst().instances().getFirst().networkInterfaces().getFirst().ipv6Addresses()
                .stream().map(InstanceIpv6Address::ipv6Address).toList());
        fixture.client().unassignIpv6Addresses(request -> request.networkInterfaceId(primary).ipv6Addresses(assigned));
        assertEquals(List.of(), addresses(describe(fixture, primary)));
        assertEquals(List.of(), fixture.client().describeInstances(request -> request.instanceIds(instance))
                .reservations().getFirst().instances().getFirst().networkInterfaces().getFirst().ipv6Addresses());
    }

    @Test
    void prefixDelegationIsRefused() {
        Fixture fixture = fixture();
        String eni = create(fixture, request -> { });

        Ec2Exception error = assertThrows(Ec2Exception.class, () -> fixture.client()
                .assignIpv6Addresses(request -> request.networkInterfaceId(eni).ipv6PrefixCount(1)));

        assertEquals("UnsupportedOperation", error.awsErrorDetails().errorCode());
    }

    @Test
    void assignmentsShareOwnershipAndReleaseTheLastFreeAddress() {
        Fixture fixture = fixture(124);
        String first = create(fixture, request -> { });
        String second = create(fixture, request -> { });
        List<String> held = fixture.client().assignIpv6Addresses(request -> request
                .networkInterfaceId(first).ipv6AddressCount(11)).assignedIpv6Addresses();
        Ec2Exception exhausted = assertThrows(Ec2Exception.class, () -> fixture.client()
                .assignIpv6Addresses(request -> request.networkInterfaceId(second).ipv6AddressCount(1)));
        assertEquals("InsufficientFreeAddressesInSubnet", exhausted.awsErrorDetails().errorCode());
        Ec2Exception duplicate = assertThrows(Ec2Exception.class, () -> fixture.client()
                .assignIpv6Addresses(request -> request.networkInterfaceId(second)
                        .ipv6Addresses(held.getFirst().toUpperCase(Locale.ROOT))));
        assertEquals("InvalidIPAddress.InUse", duplicate.awsErrorDetails().errorCode());
        fixture.client().unassignIpv6Addresses(request -> request.networkInterfaceId(first)
                .ipv6Addresses(held.getFirst()));
        assertEquals(List.of(held.getFirst()), fixture.client().assignIpv6Addresses(request -> request
                .networkInterfaceId(second).ipv6AddressCount(1)).assignedIpv6Addresses());
        assertEquals(10, addresses(describe(fixture, first)).size());
    }

    @Test
    void createSharesTheAssignmentAllocatorAndReusesReleasedAddresses() {
        // A small local-only block makes exhaustion deterministic; this is not an AWS subnet-size claim.
        Fixture fixture = fixture(124);
        String first = create(fixture, request -> request.ipv6AddressCount(10));
        List<String> held = addresses(describe(fixture, first));
        assertEquals(10, held.size());
        String second = create(fixture, request -> request.ipv6AddressCount(1));
        String last = addresses(describe(fixture, second)).getFirst();
        assertEquals(fixture.prefix() + "e", last);
        fixture.client().unassignIpv6Addresses(request -> request.networkInterfaceId(first)
                .ipv6Addresses(held.getFirst()));
        assertEquals(List.of(held.getFirst()), fixture.client().assignIpv6Addresses(request -> request
                .networkInterfaceId(second).ipv6AddressCount(1)).assignedIpv6Addresses());
    }

    @Test
    void exhaustedCreatePreservesInterfacesAndTheRequestedPrivateAddress() {
        Fixture fixture = fixture(124);
        String first = create(fixture, request -> request.ipv6AddressCount(0));
        fixture.client().assignIpv6Addresses(request -> request.networkInterfaceId(first).ipv6AddressCount(11));
        List<String> held = addresses(describe(fixture, first));
        Ec2Exception error = assertThrows(Ec2Exception.class, () -> create(fixture, request -> request
                .ipv6AddressCount(1).privateIpAddress("10.233.0.100").description("refused-ipv6-create")));
        assertEquals("InsufficientFreeAddressesInSubnet", error.awsErrorDetails().errorCode());
        assertEquals(held, addresses(describe(fixture, first)));
        assertEquals(List.of(), fixture.client().describeNetworkInterfaces(request -> request.filters(
                Filter.builder().name("description").values("refused-ipv6-create").build())).networkInterfaces());
        String ipv4 = create(fixture, request -> request.ipv6AddressCount(0).privateIpAddress("10.233.0.100"));
        assertEquals("10.233.0.100", describe(fixture, ipv4).privateIpAddress());
    }

    @Test
    void createRejectsCountsBeyondTheSharedInterfaceLimitBeforePersistence() {
        Fixture fixture = fixture();
        Ec2Exception error = assertThrows(Ec2Exception.class, () -> create(fixture, request -> request
                .ipv6AddressCount(1000).description("oversized-ipv6-create")));
        assertEquals("InvalidParameterValue", error.awsErrorDetails().errorCode());
        assertEquals(List.of(), fixture.client().describeNetworkInterfaces(request -> request.filters(
                Filter.builder().name("description").values("oversized-ipv6-create").build())).networkInterfaces());
        String valid = create(fixture, request -> request.ipv6AddressCount(2));
        assertEquals(2, addresses(describe(fixture, valid)).size());
    }

    @Test
    void launchPlansDistinctAddressesAndDoesNotAssignTheReservedLastAddress() {
        Fixture fixture = fixture(124);
        String holder = create(fixture, request -> request.ipv6AddressCount(8));
        enableAutomaticIpv6(fixture);
        String token = launchToken(fixture);
        List<Instance> launched = launch(fixture, token, 3, 3);
        List<String> allocated = launched.stream().flatMap(instance -> instance.networkInterfaces().stream())
                .flatMap(eni -> eni.ipv6Addresses().stream()).map(InstanceIpv6Address::ipv6Address).toList();
        assertEquals(List.of(fixture.prefix() + "c", fixture.prefix() + "d", fixture.prefix() + "e"), allocated);
        assertEquals(3, new HashSet<>(allocated).size());
        assertEquals(8, addresses(describe(fixture, holder)).size());
    }

    @Test
    void launchAdmitsAvailableMinimumAndReplaysWithoutAnotherAllocation() {
        Fixture fixture = fixture(124);
        create(fixture, request -> request.ipv6AddressCount(9));
        enableAutomaticIpv6(fixture);
        String token = launchToken(fixture);
        List<Instance> first = launch(fixture, token, 2, 4);
        assertEquals(2, first.size());
        List<Instance> replay = launch(fixture, token, 2, 4);
        assertEquals(first.stream().map(Instance::instanceId).toList(),
                replay.stream().map(Instance::instanceId).toList());
        assertEquals(first.stream().map(Instance::networkInterfaces).toList(),
                replay.stream().map(Instance::networkInterfaces).toList());
        Ec2Exception exhausted = assertThrows(Ec2Exception.class,
                () -> launch(fixture, launchToken(fixture), 1, 1));
        assertEquals("InsufficientFreeAddressesInSubnet", exhausted.awsErrorDetails().errorCode());
    }

    @Test
    void launchBelowMinimumLeavesNoInstancesVolumesOrTagsAndCanRetry() {
        Fixture fixture = fixture(124);
        String holder = create(fixture, request -> request.ipv6AddressCount(10));
        enableAutomaticIpv6(fixture);
        String token = launchToken(fixture);
        String marker = UUID.randomUUID().toString();
        int volumesBefore = fixture.client().describeVolumes().volumes().size();
        Ec2Exception error = assertThrows(Ec2Exception.class, () -> fixture.client().runInstances(request -> request
                .imageId("ami-amazonlinux2023").instanceType("t2.micro").minCount(2).maxCount(3)
                .subnetId(fixture.subnet()).clientToken(token).tagSpecifications(tags -> tags
                        .resourceType("instance").tags(tag -> tag.key("ipv6-batch").value(marker)))));
        assertEquals("InsufficientFreeAddressesInSubnet", error.awsErrorDetails().errorCode());
        assertEquals(List.of(), fixture.client().describeInstances(request -> request.filters(
                Filter.builder().name("client-token").values(token).build())).reservations());
        assertEquals(volumesBefore, fixture.client().describeVolumes().volumes().size());
        assertEquals(List.of(), fixture.client().describeTags(request -> request.filters(
                Filter.builder().name("value").values(marker).build())).tags());
        String released = addresses(describe(fixture, holder)).getFirst();
        fixture.client().unassignIpv6Addresses(request -> request.networkInterfaceId(holder).ipv6Addresses(released));
        assertEquals(2, launch(fixture, launchToken(fixture), 2, 3).size());
    }

    @Test
    void suppliedInterfaceKeepsItsAddressWhenAutomaticPoolIsFull() {
        Fixture fixture = fixture(124);
        String holder = create(fixture, request -> request.ipv6AddressCount(11));
        List<String> held = addresses(describe(fixture, holder));
        enableAutomaticIpv6(fixture);
        String token = launchToken(fixture);
        Instance instance = fixture.client().runInstances(request -> request.imageId("ami-amazonlinux2023")
                .instanceType("t2.micro").minCount(1).maxCount(1).clientToken(token)
                .networkInterfaces(eni -> eni.networkInterfaceId(holder).deviceIndex(0)))
                .instances().getFirst();
        assertEquals(held, instance.networkInterfaces().getFirst().ipv6Addresses().stream()
                .map(InstanceIpv6Address::ipv6Address).toList());
    }

    private static void enableAutomaticIpv6(Fixture fixture) {
        fixture.client().modifySubnetAttribute(request -> request.subnetId(fixture.subnet())
                .assignIpv6AddressOnCreation(value -> value.value(true)));
    }

    private String launchToken(Fixture fixture) {
        String token = UUID.randomUUID().toString();
        cleanup.register(() -> fixture.client().describeInstances(request -> request.filters(
                Filter.builder().name("client-token").values(token).build())).reservations().forEach(reservation ->
                        reservation.instances().forEach(instance -> fixture.client().terminateInstances(
                                request -> request.instanceIds(instance.instanceId())))));
        return token;
    }

    private static List<Instance> launch(Fixture fixture, String token, int minimum, int maximum) {
        return fixture.client().runInstances(request -> request.imageId("ami-amazonlinux2023")
                .instanceType("t2.micro").minCount(minimum).maxCount(maximum).subnetId(fixture.subnet())
                .clientToken(token)).instances();
    }

    private Fixture fixture() {
        return fixture(64);
    }

    private Fixture fixture(int ipv6PrefixLength) {
        Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "555566667790", Region.US_WEST_2);
        cleanup.register(client::close);
        String vpc = client.createVpc(request -> request.cidrBlock("10.233.0.0/16").amazonProvidedIpv6CidrBlock(true))
                .vpc().vpcId();
        cleanup.register(() -> client.deleteVpc(request -> request.vpcId(vpc)));
        String vpcBlock = client.describeVpcs(request -> request.vpcIds(vpc)).vpcs().getFirst()
                .ipv6CidrBlockAssociationSet().getFirst().ipv6CidrBlock();
        // The /56's network address is also the network address of its first smaller block.
        String subnetBlock = vpcBlock.substring(0, vpcBlock.indexOf('/')) + "/" + ipv6PrefixLength;
        String subnet = client.createSubnet(request -> request.vpcId(vpc).cidrBlock("10.233.0.0/24")
                .ipv6CidrBlock(subnetBlock)).subnet().subnetId();
        cleanup.register(() -> client.deleteSubnet(request -> request.subnetId(subnet)));
        String network = subnetBlock.substring(0, subnetBlock.indexOf('/'));
        return new Fixture(client, subnet, network, network.substring(0, network.indexOf("::") + 2));
    }

    private String create(Fixture fixture, Consumer<CreateNetworkInterfaceRequest.Builder> extra) {
        String eni = fixture.client().createNetworkInterface(request -> {
            request.subnetId(fixture.subnet());
            extra.accept(request);
        }).networkInterface().networkInterfaceId();
        cleanup.register(() -> fixture.client().deleteNetworkInterface(request -> request.networkInterfaceId(eni)));
        return eni;
    }

    private static NetworkInterface describe(Fixture fixture, String eni) {
        return fixture.client().describeNetworkInterfaces(request -> request.networkInterfaceIds(eni))
                .networkInterfaces().getFirst();
    }

    private static List<String> addresses(NetworkInterface ni) {
        return ni.ipv6Addresses().stream().map(NetworkInterfaceIpv6Address::ipv6Address).toList();
    }

    /**
     * {@code network} is the subnet's /64 network address; {@code prefix} is it written up to and including
     * {@code ::}, so a short host part can follow it.
     */
    private record Fixture(Ec2Client client, String subnet, String network, String prefix) { }
}
