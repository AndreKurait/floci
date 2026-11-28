package io.floci.aws.services.ec2;

import io.floci.aws.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.floci.aws.services.ec2.model.GroupIdentifier;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceNetworkInterface;
import io.floci.aws.services.ec2.model.Placement;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.ec2instanceconnect.InstanceConnectKeys;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2MetadataServerTest {

    @Test
    void reconciliationAndTerminationPreserveOtherInstances() {
        Ec2MetadataServer server = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        Instance first = new Instance();
        first.setInstanceId("i-first");
        Instance second = new Instance();
        second.setInstanceId("i-second");
        server.reconcileContainerAddresses(Set.of("192.0.2.1", "192.0.2.2"), first);
        server.reconcileContainerAddresses(Set.of("192.0.2.2", "192.0.2.3"), second);
        server.reconcileContainerAddresses(Set.of("192.0.2.4"), first);
        assertTrue(server.registeredContainer("192.0.2.1").isEmpty());
        assertEquals(second, server.registeredContainer("192.0.2.2").orElseThrow());
        server.unregisterInstance(null);
        assertEquals(first, server.registeredContainer("192.0.2.4").orElseThrow());
        server.unregisterInstance(first);
        assertTrue(server.registeredContainer("192.0.2.4").isEmpty());
        assertEquals(second, server.registeredContainer("192.0.2.3").orElseThrow());
    }

    @Test
    void aPendingContainerWhoseAddressIsUnknownMatchesNoRequest() {
        Ec2MetadataServer server = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        Instance instance = new Instance();
        instance.setInstanceId("i-host");
        // Docker inspection failed or the container has no address yet: nothing to match.
        server.registerPendingContainer("starting", "task/a", () -> null, instance);
        assertTrue(server.instanceForAddress("127.0.0.1") == null);
        assertEquals(1, server.pendingContainerCount());
        server.registerPendingContainer("running", "task/b", () -> "192.0.2.10", instance);
        assertEquals(instance, server.instanceForAddress("192.0.2.10"));
        assertEquals(1, server.pendingContainerCount());
    }

    @Test
    void releasingATaskUnmapsItsAddressWhicheverInstanceIsAskedFirst() {
        Ec2MetadataServer server = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        Instance other = new Instance();
        other.setInstanceId("i-other");
        Instance owning = new Instance();
        owning.setInstanceId("i-owning");
        server.registerContainer("192.0.2.20", "i-owning", owning, "task/a");
        // The manager asks every instance it knows; the first one asked does not hold the address.
        server.unregisterContainer("192.0.2.20", other, "task/a");
        server.unregisterContainer("192.0.2.20", owning, "task/a");
        assertTrue(server.registeredContainer("192.0.2.20").isEmpty());
    }

    @Test
    void dnsOriginFollowsAddressReconciliationAndInstanceRemoval() {
        Ec2MetadataServer server = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        Instance instance = dnsInstance("111111111111", "us-west-2", "vpc-one");
        ClientVpc expected = new ClientVpc("111111111111", "us-west-2", "vpc-one");
        server.reconcileContainerAddresses(Set.of("192.0.2.1", "2001:db8::1"), instance);
        assertEquals(Optional.of(expected), server.vpcForClient("192.0.2.1"));
        assertEquals(Optional.of(expected), server.vpcForClient("2001:db8::1"));
        server.reconcileContainerAddresses(Set.of("192.0.2.2"), instance);
        assertTrue(server.vpcForClient("192.0.2.1").isEmpty());
        assertTrue(server.vpcForClient("2001:db8::1").isEmpty());
        assertEquals(Optional.of(expected), server.vpcForClient("192.0.2.2"));
        server.unregisterInstance(instance);
        assertTrue(server.vpcForClient("192.0.2.2").isEmpty());
    }

    @Test
    void dnsOriginPreservesReplacementOwnerWhenOldInstanceCleansUp() {
        Ec2MetadataServer server = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        Instance first = dnsInstance("111111111111", "us-west-2", "vpc-one");
        Instance replacement = dnsInstance("222222222222", "us-east-1", "vpc-two");
        server.registerContainer("192.0.2.1", "first", first);
        server.registerContainer("192.0.2.1", "replacement", replacement);
        server.unregisterContainer("192.0.2.1", first);
        server.unregisterInstance(first);
        assertEquals(Optional.of(new ClientVpc("222222222222", "us-east-1", "vpc-two")),
                server.vpcForClient("192.0.2.1"));
        server.rollbackContainerRegistration("192.0.2.1", replacement);
        assertTrue(server.vpcForClient("192.0.2.1").isEmpty());
    }

    @Test
    void dnsOriginDoesNotInferMissingIdentityOrTrustAMetadataRelay() {
        Ec2MetadataServer server = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        server.trustForwarder("192.0.2.1");
        assertTrue(server.vpcForClient("192.0.2.1").isEmpty());
        assertTrue(server.vpcForClient(null).isEmpty());
        assertTrue(server.vpcForClient("").isEmpty());
        for (Instance incomplete : List.of(dnsInstance(null, "us-east-1", "vpc-one"),
                dnsInstance("111111111111", "", "vpc-one"),
                dnsInstance("111111111111", "us-east-1", null))) {
            server.registerContainer("192.0.2.1", "incomplete", incomplete);
            assertTrue(server.vpcForClient("192.0.2.1").isEmpty());
        }
    }

    private static Instance dnsInstance(String owner, String region, String vpc) {
        Instance instance = new Instance();
        instance.setInstanceId("i-dns");
        instance.setOwnerId(owner);
        instance.setRegion(region);
        instance.setVpcId(vpc);
        return instance;
    }

    @Test
    void instanceMetadataListsTagKeys() {
        Instance instance = new Instance();
        instance.setTags(List.of(
                new Tag("Environment", "dev"),
                new Tag("Service", "orders")));

        assertEquals("Environment\nService", Ec2MetadataServer.instanceTagKeys(instance));
    }

    @Test
    void instanceMetadataReturnsTagValue() {
        Instance instance = new Instance();
        instance.setTags(List.of(
                new Tag("Environment", "dev"),
                new Tag("Service", "orders")));

        assertEquals("orders", Ec2MetadataServer.instanceTagValue(instance, "Service").orElseThrow());
    }

    @Test
    void instanceMetadataReturnsEmptyValueForEmptyTag() {
        Instance instance = new Instance();
        instance.setTags(List.of(new Tag("Owner", null)));

        assertTrue(Ec2MetadataServer.instanceTagValue(instance, "Owner").isPresent());
        assertEquals("", Ec2MetadataServer.instanceTagValue(instance, "Owner").orElseThrow());
    }

    @Test
    void instanceMetadataReturnsMissingForUnknownTag() {
        Instance instance = new Instance();
        instance.setTags(List.of(new Tag("Environment", "dev")));

        assertTrue(Ec2MetadataServer.instanceTagValue(instance, "Missing").isEmpty());
    }

    @Test
    void identityDocumentUsesInstanceArchitectureWithX8664Fallback() {
        Instance instance = new Instance();
        instance.setInstanceId("i-arm");
        instance.setArchitecture("arm64");
        instance.setImageId("ami-arm");
        instance.setInstanceType("t4g.medium");
        instance.setPlacement(new Placement("us-west-2a"));
        instance.setPrivateIpAddress("10.0.0.10");
        instance.setRegion("us-west-2");

        assertEquals("arm64", new JsonObject(Ec2MetadataServer.instanceIdentityDocument(
                instance, "000000000000", "us-west-2a")).getString("architecture"));

        instance.setArchitecture(null);
        assertEquals("x86_64", new JsonObject(Ec2MetadataServer.instanceIdentityDocument(
                instance, "000000000000", "us-west-2a")).getString("architecture"));
    }

    @Test
    void identityDocumentOfAnInstanceWithoutPlacementReportsTheConfiguredZone() {
        Instance instance = new Instance();
        instance.setInstanceId("i-china");
        instance.setRegion("cn-north-1");

        assertEquals("cn-north-1b", new JsonObject(Ec2MetadataServer.instanceIdentityDocument(
                instance, "000000000000", "cn-north-1b")).getString("availabilityZone"));
    }

    @Test
    void staleContainerUnregisterDoesNotRemoveCurrentRegistration() {
        Ec2MetadataServer server = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        Instance oldInstance = new Instance();
        oldInstance.setInstanceId("i-old");
        Instance currentInstance = new Instance();
        currentInstance.setInstanceId("i-current");

        server.registerContainer("192.168.215.7", oldInstance.getInstanceId(), oldInstance);
        server.registerContainer("192.168.215.7", currentInstance.getInstanceId(), currentInstance);
        server.unregisterContainer("192.168.215.7", oldInstance);

        assertEquals(
                currentInstance,
                server.registeredContainer("192.168.215.7").orElseThrow());
    }

    @Test
    void ipv6KeysAnswerTheInterfaceByMacAndNothingForAnIpv4OnlyInterface() {
        InstanceNetworkInterface primary = new InstanceNetworkInterface();
        primary.setMacAddress("02:aa:bb:cc:dd:ee");
        primary.setIpv6Addresses(List.of("2600:1f18:abcd:ef01::1234"));
        primary.setSubnetIpv6CidrBlocks(List.of("2600:1f18:abcd:ef01::/64"));
        primary.setVpcIpv6CidrBlocks(List.of("2600:1f18:abcd:ef00::/56"));
        InstanceNetworkInterface ipv4Only = new InstanceNetworkInterface();
        ipv4Only.setMacAddress("02:11:22:33:44:55");
        ipv4Only.setDeviceIndex(1);
        Instance instance = new Instance();
        instance.setNetworkInterfaces(new ArrayList<>(List.of(primary, ipv4Only)));

        assertEquals("2600:1f18:abcd:ef01::1234", Ec2MetadataServer.primaryIpv6(instance));
        assertEquals("02:aa:bb:cc:dd:ee/\n02:11:22:33:44:55/", Ec2MetadataServer.macListing(instance));
        assertEquals("2600:1f18:abcd:ef01::1234", Ec2MetadataServer.interfaceValues(
                instance, "02:AA:BB:CC:DD:EE", InstanceNetworkInterface::getIpv6Addresses).orElseThrow());
        assertEquals("2600:1f18:abcd:ef01::/64", Ec2MetadataServer.interfaceValues(
                instance, "02:aa:bb:cc:dd:ee", InstanceNetworkInterface::getSubnetIpv6CidrBlocks).orElseThrow());
        assertEquals("2600:1f18:abcd:ef00::/56", Ec2MetadataServer.interfaceValues(
                instance, "02:aa:bb:cc:dd:ee", InstanceNetworkInterface::getVpcIpv6CidrBlocks).orElseThrow());
        assertTrue(Ec2MetadataServer.interfaceValues(
                instance, "02:11:22:33:44:55", InstanceNetworkInterface::getIpv6Addresses).isEmpty());
        assertTrue(Ec2MetadataServer.interfaceValues(
                instance, "02:00:00:00:00:00", InstanceNetworkInterface::getIpv6Addresses).isEmpty());

        Instance noIpv6 = new Instance();
        noIpv6.setNetworkInterfaces(new ArrayList<>(List.of(ipv4Only)));
        assertEquals(null, Ec2MetadataServer.primaryIpv6(noIpv6));
    }

    @Test
    void eachInterfaceAnswersItsOwnIpv4CategoriesByMacIncludingAnAttachedDevice() {
        InstanceNetworkInterface primary = new InstanceNetworkInterface();
        primary.setMacAddress("02:aa:bb:cc:dd:ee");
        primary.setNetworkInterfaceId("eni-0primary");
        primary.setPrivateIpAddress("10.1.0.10");
        InstanceNetworkInterface attached = new InstanceNetworkInterface();
        attached.setMacAddress("02:11:22:33:44:55");
        attached.setDeviceIndex(1);
        attached.setNetworkInterfaceId("eni-0attached");
        attached.setPrivateIpAddress("172.31.5.9");
        attached.setPrivateDnsName("ip-172-31-5-9.eu-west-1.compute.internal");
        attached.setOwnerId("111111111111");
        attached.setSubnetId("subnet-0customer");
        attached.setVpcId("vpc-0customer");
        GroupIdentifier group = new GroupIdentifier();
        group.setGroupId("sg-0customer");
        group.setGroupName("customer");
        attached.setGroups(new ArrayList<>(List.of(group)));
        Instance instance = new Instance();
        instance.setNetworkInterfaces(new ArrayList<>(List.of(primary, attached)));
        String mac = "02:11:22:33:44:55";

        assertEquals("02:aa:bb:cc:dd:ee/\n02:11:22:33:44:55/", Ec2MetadataServer.macListing(instance));
        Map<String, String> expected = Map.of(
                "device-number", "1", "interface-id", "eni-0attached", "local-ipv4s", "172.31.5.9",
                "mac", mac, "owner-id", "111111111111", "security-group-ids", "sg-0customer",
                "security-groups", "customer", "subnet-id", "subnet-0customer", "vpc-id", "vpc-0customer",
                "local-hostname", "ip-172-31-5-9.eu-west-1.compute.internal");
        expected.forEach((category, value) -> assertEquals(value, Ec2MetadataServer.interfaceValues(
                instance, mac, Ec2MetadataServer.INTERFACE_CATEGORIES.get(category)).orElseThrow(), category));
        assertEquals("0", Ec2MetadataServer.interfaceValues(instance, "02:AA:BB:CC:DD:EE",
                Ec2MetadataServer.INTERFACE_CATEGORIES.get("device-number")).orElseThrow());
        assertTrue(Ec2MetadataServer.interfaceValues(instance, "02:aa:bb:cc:dd:ee",
                Ec2MetadataServer.INTERFACE_CATEGORIES.get("owner-id")).isEmpty());
        assertEquals(String.join("\n", new TreeSet<>(expected.keySet())),
                Ec2MetadataServer.interfaceListing(instance, mac).orElseThrow());
        assertTrue(Ec2MetadataServer.interfaceListing(instance, "02:00:00:00:00:00").isEmpty());
    }

    @Test
    void refreshingAnInstanceServesItsCurrentRecordAtEveryAddress() {
        Ec2MetadataServer server = new Ec2MetadataServer(null, null, null, (InstanceConnectKeys) null);
        Instance launched = new Instance();
        launched.setInstanceId("i-0guest");
        Instance other = new Instance();
        other.setInstanceId("i-0other");
        server.reconcileContainerAddresses(Set.of("192.0.2.10", "192.0.2.11"), launched);
        server.reconcileContainerAddresses(Set.of("192.0.2.12"), other);
        Instance attached = new Instance();
        attached.setInstanceId("i-0guest");

        server.refreshInstance(attached);

        assertTrue(server.registeredContainer("192.0.2.10").orElseThrow() == attached);
        assertTrue(server.registeredContainer("192.0.2.11").orElseThrow() == attached);
        assertTrue(server.registeredContainer("192.0.2.12").orElseThrow() == other);
        server.refreshInstance(null);
    }

    @Test
    void unregisteredContainerMessageExplainsMissingEc2Record() {
        String message = Ec2MetadataServer.unregisteredContainerMessage("172.17.0.9");

        assertTrue(message.startsWith("Instance not found"));
        assertTrue(message.contains("172.17.0.9"));
        assertTrue(message.contains("RunInstances"));
        assertTrue(message.contains("SSM managed instance"));
    }

}
