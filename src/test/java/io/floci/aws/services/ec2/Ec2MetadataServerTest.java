package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.GroupIdentifier;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceNetworkInterface;
import io.floci.aws.services.ec2.model.Placement;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.ec2instanceconnect.InstanceConnectKeys;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

        assertTrue(Ec2MetadataServer.instanceIdentityDocument(instance, "000000000000", "us-west-2a")
                .contains("\"architecture\":\"arm64\""));

        instance.setArchitecture(null);
        assertTrue(Ec2MetadataServer.instanceIdentityDocument(instance, "000000000000", "us-west-2a")
                .contains("\"architecture\":\"x86_64\""));
    }

    @Test
    void identityDocumentOfAnInstanceWithoutPlacementReportsTheConfiguredZone() {
        Instance instance = new Instance();
        instance.setInstanceId("i-china");
        instance.setRegion("cn-north-1");

        assertTrue(Ec2MetadataServer.instanceIdentityDocument(instance, "000000000000", "cn-north-1b")
                .contains("\"availabilityZone\":\"cn-north-1b\""));
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
