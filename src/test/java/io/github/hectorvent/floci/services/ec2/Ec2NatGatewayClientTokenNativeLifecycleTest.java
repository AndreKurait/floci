package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.net.VpcRoutingManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Ec2NatGatewayClientTokenNativeLifecycleTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @Test
    void duplicateReturnsThePublishedPendingGatewayWhileItsSingleNativeStartIsBlocked() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> nativeId = new AtomicReference<>();
        doAnswer(invocation -> {
            nativeId.set(invocation.<NatGateway>getArgument(2).getNatGatewayId());
            entered.countDown();
            await(release);
            return null;
        }).when(fixture.routing).createNat(eq(ACCOUNT), eq(REGION), any());
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<NatGateway> first = workers.submit(fixture::create);
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                NatGateway replay = workers.submit(fixture::create).get(2, TimeUnit.SECONDS);
                assertEquals(nativeId.get(), replay.getNatGatewayId());
                assertEquals("pending", replay.getState());
                assertEquals(1, fixture.service.describeNatGateways(REGION, List.of(), Map.of()).size());
                assertEquals(replay.getNatGatewayAddresses().getFirst().getAssociationId(),
                        fixture.service.describeAddresses(REGION, List.of(fixture.allocation), Map.of())
                                .getFirst().getAssociationId());
                verify(fixture.routing, times(1)).createNat(eq(ACCOUNT), eq(REGION), any());
                release.countDown();
                NatGateway completed = first.get(10, TimeUnit.SECONDS);
                assertEquals(replay.getNatGatewayId(), completed.getNatGatewayId());
                assertEquals("available", completed.getState());
                assertEquals("available", fixture.create().getState());
                assertEquals("pending", replay.getState(), "The returned replay must be a detached snapshot");
                verify(fixture.routing, times(1)).createNat(eq(ACCOUNT), eq(REGION), any());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void deletionWaitsForAnInFlightNativeStartThenRetiresItsCompletedGateway() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch deleting = new CountDownLatch(1);
        AtomicReference<String> nativeId = new AtomicReference<>();
        List<String> events = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            nativeId.set(invocation.<NatGateway>getArgument(2).getNatGatewayId());
            entered.countDown();
            await(release);
            events.add("native-complete");
            return null;
        }).when(fixture.routing).createNat(eq(ACCOUNT), eq(REGION), any());
        doAnswer(invocation -> {
            events.add("prepare-delete");
            return null;
        }).when(fixture.routing).prepareNatDeletion(eq(ACCOUNT), eq(REGION), any());
        doAnswer(invocation -> {
            events.add("native-deleted");
            return null;
        }).when(fixture.routing).natDeleted(eq(ACCOUNT), eq(REGION), any());
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<NatGateway> first = workers.submit(fixture::create);
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                Future<NatGateway> deletion = workers.submit(() -> {
                    deleting.countDown();
                    return fixture.service.deleteNatGateway(REGION, nativeId.get());
                });
                assertTrue(deleting.await(10, TimeUnit.SECONDS));
                assertFalse(deletion.isDone());
                verify(fixture.routing, never()).prepareNatDeletion(eq(ACCOUNT), eq(REGION), any());
                release.countDown();
                assertEquals("available", first.get(10, TimeUnit.SECONDS).getState());
                assertEquals("deleted", deletion.get(10, TimeUnit.SECONDS).getState());
                assertEquals(List.of("native-complete", "prepare-delete", "native-deleted"), events);
                fixture.assertRetired();
                verify(fixture.routing, times(1)).createNat(eq(ACCOUNT), eq(REGION), any());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void deletionAfterRecordPublicationButBeforeNativeStartPreventsLateCreation() throws Exception {
        Fixture fixture = new Fixture();
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch returnFromCommit = new CountDownLatch(1);
        CountDownLatch deletionPrepared = new CountDownLatch(1);
        AtomicReference<String> id = new AtomicReference<>();
        fixture.factory.afterCommit.set(gateway -> {
            if (id.compareAndSet(null, gateway.getNatGatewayId())) {
                published.countDown();
                await(returnFromCommit);
            }
        });
        doAnswer(invocation -> {
            deletionPrepared.countDown();
            return null;
        }).when(fixture.routing).prepareNatDeletion(eq(ACCOUNT), eq(REGION), any());
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<NatGateway> creation = workers.submit(fixture::create);
            try {
                assertTrue(published.await(10, TimeUnit.SECONDS));
                Future<NatGateway> deletion = workers.submit(() ->
                        fixture.service.deleteNatGateway(REGION, id.get()));
                assertTrue(deletionPrepared.await(10, TimeUnit.SECONDS),
                        "Deletion owns native retirement before waiting for the allocation lock");
                returnFromCommit.countDown();
                assertEquals("deleted", deletion.get(10, TimeUnit.SECONDS).getState());
                NatGateway response = creation.get(10, TimeUnit.SECONDS);
                assertEquals(id.get(), response.getNatGatewayId());
                assertEquals("deleted", response.getState());
                fixture.assertRetired();
                verify(fixture.routing, never()).createNat(eq(ACCOUNT), eq(REGION), any());
                verify(fixture.routing, times(1)).natDeleted(ACCOUNT, REGION, id.get());
            } finally {
                returnFromCommit.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Controlled lifecycle callback was not released");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Controlled lifecycle callback interrupted", failure);
        }
    }

    private static final class Fixture {
        private final CommitFactory factory = new CommitFactory();
        private final VpcRoutingManager routing = mock(VpcRoutingManager.class);
        private final Ec2Service service;
        private final String subnet;
        private final String allocation;

        @SuppressWarnings("unchecked")
        private Fixture() throws Exception {
            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
            when(config.defaultAccountId()).thenReturn(ACCOUNT);
            when(config.services()).thenReturn(services);
            when(services.ec2()).thenReturn(ec2);
            when(ec2.mock()).thenReturn(true);
            when(ec2.mockPublicAddresses()).thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
            service = new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                    mock(AmiImageResolver.class), new Ec2ImageCatalog(), new Ec2InstanceTypeCatalog(), factory);
            String vpc = service.createVpc(REGION, "10.198.0.0/16", false).getVpcId();
            subnet = service.createSubnet(REGION, vpc, "10.198.1.0/24", REGION + "a").getSubnetId();
            allocation = service.allocateAddress(REGION).getAllocationId();
            when(routing.enabled()).thenReturn(true);
            when(routing.hasInternetGateway(REGION, vpc)).thenReturn(true);
            Instance<VpcRoutingManager> managers = mock(Instance.class);
            when(managers.get()).thenReturn(routing);
            Field field = Ec2Service.class.getDeclaredField("vpcRoutingManagers");
            field.setAccessible(true);
            field.set(service, managers);
        }

        private NatGateway create() {
            return service.createNatGateway(REGION, subnet, allocation, "public", "10.198.1.40",
                    List.of(), List.of(), null, List.of(), "Native-Token ");
        }

        private void assertRetired() {
            assertTrue(service.describeNatGateways(REGION, List.of(), Map.of()).isEmpty());
            assertNull(service.describeAddresses(REGION, List.of(allocation), Map.of())
                    .getFirst().getAssociationId());
            assertNull(service.describeAddresses(REGION, List.of(allocation), Map.of())
                    .getFirst().getNetworkInterfaceId());
        }
    }

    private static final class CommitFactory extends StorageFactory {
        private final AtomicReference<Consumer<NatGateway>> afterCommit =
                new AtomicReference<>(gateway -> {});

        private CommitFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                         TypeReference<Map<String, V>> type) {
            AccountAwareStorageBackend<V> backend = spy(AccountAwareStorageBackend.inMemory(ACCOUNT));
            if ("ec2-nat-gateways.json".equals(fileName)) {
                doAnswer(invocation -> {
                    invocation.callRealMethod();
                    Map<String, NatGateway> written = invocation.getArgument(0);
                    afterCommit.get().accept(new NatGateway(written.values().iterator().next()));
                    return null;
                }).when(backend).putAllAtomically(anyMap());
            }
            return backend;
        }
    }
}
