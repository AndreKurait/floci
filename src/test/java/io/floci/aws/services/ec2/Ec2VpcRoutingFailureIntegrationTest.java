package io.floci.aws.services.ec2;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.ec2.net.VpcRoutingManager;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;
import software.amazon.awssdk.services.ec2.model.NatGateway;
import software.amazon.awssdk.services.ec2.model.Route;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class Ec2VpcRoutingFailureIntegrationTest {
    @Inject
    Ec2Service ec2;

    @InjectMock
    VpcRoutingManager routing;

    @Test
    void aRouteWriteReportsRuntimeFailureAndCanBeReconciledAgain() {
        when(routing.enabled()).thenReturn(true);
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "111111112233", Region.US_EAST_1)) {
            String vpc = client.createVpc(request -> request.cidrBlock("10.135.0.0/16")).vpc().vpcId();
            String table = client.createRouteTable(request -> request.vpcId(vpc)).routeTable().routeTableId();
            String gateway = client.createInternetGateway().internetGateway().internetGatewayId();
            try {
                client.attachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc));
                client.createRoute(request -> request.routeTableId(table)
                        .destinationCidrBlock("0.0.0.0/0").gatewayId(gateway));
                doThrow(new IllegalStateException("Cannot program the owned network namespace"))
                        .when(routing).reconcile();
                Ec2Exception failure = assertThrows(Ec2Exception.class, () -> client.replaceRoute(
                        request -> request.routeTableId(table).destinationCidrBlock("0.0.0.0/0").gatewayId(gateway)));
                assertEquals(500, failure.statusCode());

                doNothing().when(routing).reconcile();
                client.replaceRoute(request -> request.routeTableId(table)
                        .destinationCidrBlock("0.0.0.0/0").gatewayId(gateway));
                Route recovered = client.describeRouteTables(request -> request.routeTableIds(table))
                        .routeTables().getFirst().routes().stream()
                        .filter(route -> "0.0.0.0/0".equals(route.destinationCidrBlock())).findFirst().orElseThrow();
                assertEquals(gateway, recovered.gatewayId());
                assertEquals("active", recovered.stateAsString());
            } finally {
                doNothing().when(routing).reconcile();
                client.deleteRouteTable(request -> request.routeTableId(table));
                client.detachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc));
                client.deleteInternetGateway(request -> request.internetGatewayId(gateway));
                client.deleteVpc(request -> request.vpcId(vpc));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void internetGatewayChangesReconcileOnlyWhenRoutingIsEnabled(boolean enabled) {
        when(routing.enabled()).thenReturn(enabled);
        when(routing.hasInternetGateway(anyString(), anyString())).thenReturn(true);
        doNothing().when(routing).reconcile();
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), "111111112244", Region.US_EAST_1)) {
            String vpc = client.createVpc(request -> request.cidrBlock("10.136.0.0/16")).vpc().vpcId();
            String gateway = client.createInternetGateway().internetGateway().internetGatewayId();
            try {
                clearInvocations(routing);
                client.attachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc));
                verify(routing, times(enabled ? 1 : 0)).reconcile();
                client.detachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc));
                verify(routing, times(enabled ? 2 : 0)).reconcile();
            } finally {
                client.detachInternetGateway(request -> request.internetGatewayId(gateway).vpcId(vpc));
                client.deleteInternetGateway(request -> request.internetGatewayId(gateway));
                client.deleteVpc(request -> request.vpcId(vpc));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void natDeletionRetiresRuntimeBeforeReleasingAddresses(boolean enabled) {
        String account = "111111112255";
        String region = "us-east-1";
        when(routing.enabled()).thenReturn(enabled);
        when(routing.hasInternetGateway(anyString(), anyString())).thenReturn(true);
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1)) {
            String vpc = client.createVpc(request -> request.cidrBlock("10.137.0.0/16")).vpc().vpcId();
            String subnet = client.createSubnet(request -> request.vpcId(vpc)
                    .cidrBlock("10.137.1.0/24")).subnet().subnetId();
            String allocation = client.allocateAddress().allocationId();
            String nat = client.createNatGateway(request -> request.subnetId(subnet)
                    .allocationId(allocation)).natGateway().natGatewayId();
            String table = client.createRouteTable(request -> request.vpcId(vpc)).routeTable().routeTableId();
            String association = client.describeAddresses(request -> request.allocationIds(allocation))
                    .addresses().getFirst().associationId();
            try {
                client.createRoute(request -> request.routeTableId(table)
                        .destinationCidrBlock("0.0.0.0/0").natGatewayId(nat));
                clearInvocations(routing);
                doThrow(new IllegalStateException("Owned NAT namespace cannot retire"))
                        .when(routing).prepareNatDeletion(account, region, nat);
                if (enabled) {
                    Ec2Exception failure = assertThrows(Ec2Exception.class,
                            () -> client.deleteNatGateway(request -> request.natGatewayId(nat)));
                    assertEquals(500, failure.statusCode());
                    assertEquals("available", client.describeNatGateways(request -> request.natGatewayIds(nat))
                            .natGateways().getFirst().stateAsString());
                    assertEquals(allocation, client.describeNatGateways(request -> request.natGatewayIds(nat))
                            .natGateways().getFirst().natGatewayAddresses().getFirst().allocationId());
                    assertEquals(association, client.describeAddresses(request -> request.allocationIds(allocation))
                            .addresses().getFirst().associationId());
                    verify(routing, never()).natDeleted(account, region, nat);
                }
                doNothing().when(routing).prepareNatDeletion(account, region, nat);
                clearInvocations(routing);
                client.deleteNatGateway(request -> request.natGatewayId(nat));
                assertNull(client.describeAddresses(request -> request.allocationIds(allocation))
                        .addresses().getFirst().associationId());
                InOrder order = inOrder(routing);
                if (enabled) {
                    order.verify(routing).prepareNatDeletion(account, region, nat);
                    order.verify(routing).natDeleted(account, region, nat);
                } else {
                    verify(routing, never()).prepareNatDeletion(account, region, nat);
                    verify(routing, never()).natDeleted(account, region, nat);
                }
                Route retired = client.describeRouteTables(request -> request.routeTableIds(table))
                        .routeTables().getFirst().routes().stream()
                        .filter(route -> "0.0.0.0/0".equals(route.destinationCidrBlock()))
                        .findFirst().orElseThrow();
                assertEquals("blackhole", retired.stateAsString());
            } finally {
                doNothing().when(routing).prepareNatDeletion(account, region, nat);
                if (client.describeNatGateways().natGateways().stream()
                        .anyMatch(gateway -> nat.equals(gateway.natGatewayId()))) {
                    client.deleteNatGateway(request -> request.natGatewayId(nat));
                }
                client.deleteRouteTable(request -> request.routeTableId(table));
                client.releaseAddress(request -> request.allocationId(allocation));
                client.deleteSubnet(request -> request.subnetId(subnet));
                client.deleteVpc(request -> request.vpcId(vpc));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void natCreationCompletesCurrentDeclarationWithoutResurrectingDeletion(int outcome) {
        String account = "111111112266";
        String region = "us-east-1";
        when(routing.enabled()).thenReturn(true);
        when(routing.hasInternetGateway(anyString(), anyString())).thenReturn(true);
        doNothing().when(routing).reconcile();
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1)) {
            String vpc = client.createVpc(request -> request.cidrBlock("10.138.0.0/16")).vpc().vpcId();
            String subnet = client.createSubnet(request -> request.vpcId(vpc)
                    .cidrBlock("10.138.1.0/24")).subnet().subnetId();
            String allocation = client.allocateAddress().allocationId();
            try {
                doAnswer(invocation -> {
                    // The emulator model and SDK response model share the NatGateway name.
                    io.floci.aws.services.ec2.model.NatGateway gateway = invocation.getArgument(2);
                    String id = gateway.getNatGatewayId();
                    assertEquals("pending", client.describeNatGateways(request -> request.natGatewayIds(id))
                            .natGateways().getFirst().stateAsString());
                    client.createTags(request -> request.resources(id)
                            .tags(tag -> tag.key("during-runtime").value("preserved")));
                    if (outcome >= 2) {
                        client.deleteNatGateway(request -> request.natGatewayId(id));
                    }
                    if (outcome % 2 == 1) {
                        throw new IllegalStateException("Runtime setup failed");
                    }
                    return null;
                }).when(routing).createNat(eq(account), eq(region), any());
                NatGateway created = client.createNatGateway(
                        request -> request.subnetId(subnet).allocationId(allocation)).natGateway();
                verify(routing).createNat(eq(account), eq(region), any());
                if (outcome >= 2) {
                    assertEquals("deleted", created.stateAsString());
                    assertTrue(client.describeNatGateways().natGateways().stream()
                            .noneMatch(gateway -> created.natGatewayId().equals(gateway.natGatewayId())));
                    assertNull(client.describeAddresses(request -> request.allocationIds(allocation))
                            .addresses().getFirst().associationId());
                } else {
                    assertEquals(outcome == 0 ? "available" : "failed", created.stateAsString());
                    assertTrue(created.tags().stream().anyMatch(tag -> "during-runtime".equals(tag.key())
                            && "preserved".equals(tag.value())));
                    if (outcome == 1) {
                        assertEquals("InternalError", created.failureCode());
                    }
                }
            } finally {
                doNothing().when(routing).createNat(anyString(), anyString(), any());
                doNothing().when(routing).prepareNatDeletion(anyString(), anyString(), anyString());
                for (NatGateway gateway
                        : client.describeNatGateways().natGateways()) {
                    client.deleteNatGateway(request -> request.natGatewayId(gateway.natGatewayId()));
                }
                client.releaseAddress(request -> request.allocationId(allocation));
                client.deleteSubnet(request -> request.subnetId(subnet));
                client.deleteVpc(request -> request.vpcId(vpc));
            }
        }
    }


    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void secondaryAddressesRespectPacketRuntimeShapeBeforeTakingAllocations(int phase) {
        String account = "111111112277";
        String region = "us-east-1";
        boolean enabled = phase != 0;
        when(routing.enabled()).thenReturn(enabled);
        when(routing.hasInternetGateway(anyString(), anyString())).thenReturn(true);
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1)) {
            String vpc = client.createVpc(request -> request.cidrBlock("10.139.0.0/16")).vpc().vpcId();
            String subnet = client.createSubnet(request -> request.vpcId(vpc)
                    .cidrBlock("10.139.1.0/24")).subnet().subnetId();
            String primary = client.allocateAddress().allocationId();
            String secondary = client.allocateAddress().allocationId();
            try {
                if (phase == 2) {
                    doAnswer(invocation -> {
                        // The emulator model and SDK response model share the NatGateway name.
                        io.floci.aws.services.ec2.model.NatGateway gateway = invocation.getArgument(2);
                        assertEquals("pending", gateway.getState());
                        assertSecondaryUpdate(client, account, region, gateway.getNatGatewayId(), secondary, true);
                        return null;
                    }).when(routing).createNat(eq(account), eq(region), any());
                }
                NatGateway created = client.createNatGateway(request -> request.subnetId(subnet)
                        .allocationId(primary)).natGateway();
                if (phase != 2) {
                    assertSecondaryUpdate(client, account, region, created.natGatewayId(), secondary, enabled);
                }
                assertEquals("available", client.describeNatGateways(
                        request -> request.natGatewayIds(created.natGatewayId()))
                        .natGateways().getFirst().stateAsString());
                // An empty update preserves the primary and remains usable in packet mode.
                RequestScopes.runAs(account, region, () -> ec2.updateNatGatewaySecondaryAddresses(
                        region, created.natGatewayId(), List.of(), List.of(), null));
                assertEquals(created.natGatewayAddresses().getFirst().associationId(), client.describeAddresses(
                        request -> request.allocationIds(primary)).addresses().getFirst().associationId());
                assertNull(client.describeAddresses(request -> request.allocationIds(secondary))
                        .addresses().getFirst().associationId());
            } finally {
                doNothing().when(routing).createNat(anyString(), anyString(), any());
                for (NatGateway gateway : client.describeNatGateways().natGateways()) {
                    client.deleteNatGateway(request -> request.natGatewayId(gateway.natGatewayId()));
                }
                client.releaseAddress(request -> request.allocationId(primary));
                client.releaseAddress(request -> request.allocationId(secondary));
                client.deleteSubnet(request -> request.subnetId(subnet));
                client.deleteVpc(request -> request.vpcId(vpc));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentNatCompletionCannotReclaimReallocatedAddress(boolean setupFails) throws Exception {
        String account = "111111112288";
        String region = "us-east-1";
        when(routing.enabled()).thenReturn(true);
        when(routing.hasInternetGateway(anyString(), anyString())).thenReturn(true);
        CompletableFuture<String> setupEntered = new CompletableFuture<>();
        CountDownLatch finishSetup = new CountDownLatch(1);
        AtomicBoolean firstSetup = new AtomicBoolean(true);
        try (Ec2Client client = AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1);
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String vpc = client.createVpc(request -> request.cidrBlock("10.140.0.0/16")).vpc().vpcId();
            String subnet = client.createSubnet(request -> request.vpcId(vpc)
                    .cidrBlock("10.140.1.0/24")).subnet().subnetId();
            String allocation = client.allocateAddress().allocationId();
            try {
                doAnswer(invocation -> {
                    if (firstSetup.compareAndSet(true, false)) {
                        // The emulator model and SDK response model share the NatGateway name.
                        io.floci.aws.services.ec2.model.NatGateway gateway = invocation.getArgument(2);
                        setupEntered.complete(gateway.getNatGatewayId());
                        assertTrue(finishSetup.await(20, TimeUnit.SECONDS), "Setup was not released");
                        if (setupFails) {
                            throw new IllegalStateException("Delayed runtime setup failed");
                        }
                    }
                    return null;
                }).when(routing).createNat(eq(account), eq(region), any());
                Future<NatGateway> creating = executor.submit(() -> client.createNatGateway(
                        request -> request.subnetId(subnet).allocationId(allocation)).natGateway());
                try {
                    String deleting = setupEntered.get(20, TimeUnit.SECONDS);
                    assertEquals("pending", client.describeNatGateways(request -> request.natGatewayIds(deleting))
                            .natGateways().getFirst().stateAsString());
                    client.deleteNatGateway(request -> request.natGatewayId(deleting));
                    assertNull(client.describeAddresses(request -> request.allocationIds(allocation))
                            .addresses().getFirst().associationId());
                    NatGateway replacement = client.createNatGateway(request -> request.subnetId(subnet)
                            .allocationId(allocation)).natGateway();
                    assertEquals("available", replacement.stateAsString());
                    String replacementAssociation = replacement.natGatewayAddresses().getFirst().associationId();
                    finishSetup.countDown();
                    assertEquals("deleted", creating.get(20, TimeUnit.SECONDS).stateAsString());
                    assertEquals(List.of(replacement.natGatewayId()), client.describeNatGateways().natGateways()
                            .stream().map(NatGateway::natGatewayId).toList());
                    assertEquals(replacementAssociation, client.describeAddresses(
                            request -> request.allocationIds(allocation)).addresses().getFirst().associationId());
                } finally {
                    finishSetup.countDown();
                    creating.get(20, TimeUnit.SECONDS);
                }
            } finally {
                finishSetup.countDown();
                doNothing().when(routing).createNat(anyString(), anyString(), any());
                for (NatGateway gateway : client.describeNatGateways().natGateways()) {
                    client.deleteNatGateway(request -> request.natGatewayId(gateway.natGatewayId()));
                }
                client.releaseAddress(request -> request.allocationId(allocation));
                client.deleteSubnet(request -> request.subnetId(subnet));
                client.deleteVpc(request -> request.vpcId(vpc));
            }
        }
    }

    private void assertSecondaryUpdate(Ec2Client client, String account, String region, String nat,
                                       String secondary, boolean enabled) {
        if (enabled) {
            AwsException failure = assertThrows(AwsException.class, () -> RequestScopes.runAs(account, region,
                    () -> ec2.updateNatGatewaySecondaryAddresses(region, nat, List.of(secondary), List.of(), null)));
            assertEquals("UnsupportedOperation", failure.getErrorCode());
            assertNull(client.describeAddresses(request -> request.allocationIds(secondary))
                    .addresses().getFirst().associationId());
            assertEquals(1, client.describeNatGateways(request -> request.natGatewayIds(nat))
                    .natGateways().getFirst().natGatewayAddresses().size());
        } else {
            RequestScopes.runAs(account, region, () -> ec2.updateNatGatewaySecondaryAddresses(
                    region, nat, List.of(secondary), List.of(), null));
            assertEquals(2, client.describeNatGateways(request -> request.natGatewayIds(nat))
                    .natGateways().getFirst().natGatewayAddresses().size());
        }
    }

}
