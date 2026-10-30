package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.ParkingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2SecurityGroupPublicationRaceTest {
    @Test
    void publishedInterfaceCannotReferenceSuccessfullyDeletedGroup() throws Exception {
        MemoryFactory factory = new MemoryFactory();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "race", "race", vpc).getGroupId();
        factory.interfaces.parkNextWrite();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<NetworkInterface> created = executor.submit(() -> service.createNetworkInterface(
                    region, subnet, "race", null, List.of(), List.of(group), List.of()));
            assertTrue(factory.interfaces.awaitParked(), "Creator must reach the ENI publication boundary");
            AtomicReference<Thread> deletingThread = new AtomicReference<>();
            Future<AwsException> deleted = executor.submit(() -> {
                deletingThread.set(Thread.currentThread());
                try {
                    service.deleteSecurityGroup(region, group);
                    return null;
                } catch (AwsException error) {
                    return error;
                }
            });
            await().atMost(5, TimeUnit.SECONDS).until(() -> deleted.isDone()
                    || deletingThread.get() != null && deletingThread.get().getState() == Thread.State.BLOCKED);
            factory.interfaces.release();
            NetworkInterface networkInterface = created.get(5, TimeUnit.SECONDS);
            AwsException error = deleted.get(5, TimeUnit.SECONDS);
            assertNotNull(error, "Deletion must observe the creator's published group reference");
            assertEquals("DependencyViolation", error.getErrorCode());
            assertTrue(networkInterface.getGroups().stream().anyMatch(value -> group.equals(value.getGroupId())));
            assertEquals(1, service.describeSecurityGroups(region, List.of(group), List.of(), Map.of()).size());
        } finally {
            factory.interfaces.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void existingInterfacePreventsDeletion() {
        Ec2Service service = service(new MemoryFactory());
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "positive", "positive", vpc).getGroupId();
        service.createNetworkInterface(region, subnet, "positive", null, List.of(), List.of(group), List.of());
        AwsException error = assertThrows(AwsException.class, () -> service.deleteSecurityGroup(region, group));
        assertEquals("DependencyViolation", error.getErrorCode());
        assertEquals(1, service.describeSecurityGroups(region, List.of(group), List.of(), Map.of()).size());
    }

    @Test
    void creationAfterDeletionRejectsMissingGroup() {
        Ec2Service service = service(new MemoryFactory());
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "deleted", "deleted", vpc).getGroupId();
        service.deleteSecurityGroup(region, group);
        AwsException error = assertThrows(AwsException.class, () -> service.createNetworkInterface(
                region, subnet, "rejected", null, List.of(), List.of(group), List.of()));
        assertEquals("InvalidSecurityGroupID.NotFound", error.getErrorCode());
    }

    private static Ec2Service service(MemoryFactory factory) {
        return new Ec2Service(mockConfig(), mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), factory);
    }

    private static EmulatorConfig mockConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(true);
        when(ec2.mockPublicAddresses()).thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        return config;
    }

    private static final class MemoryFactory extends StorageFactory {
        private final ParkingStore<NetworkInterface> interfaces = new ParkingStore<>();

        private MemoryFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String service, String file,
                                                        TypeReference<Map<String, V>> type) {
            if ("ec2-network-interfaces.json".equals(file)) {
                return (AccountAwareStorageBackend<V>) new AccountAwareStorageBackend<>(interfaces, null, "000000000000");
            }
            return AccountAwareStorageBackend.inMemory("000000000000");
        }
    }
}
