package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.ParkingStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.hectorvent.floci.testing.ThreadStates.awaitBlockedOrDone;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2SecurityGroupPublicationRaceTest {
    @Test
    void deletingPublishedGroupWaitsForItsDefaultRule() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        service.describeSecurityGroups(region, List.of(), List.of(), Map.of());
        factory.rules.parkNextWrite();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SecurityGroup> created = executor.submit(() ->
                    service.createSecurityGroup(region, "default-rule-race", "race", null));
            assertTrue(factory.rules.awaitParked(), "Creator must reach default-rule publication");
            AtomicReference<Thread> deletingThread = new AtomicReference<>();
            Future<String> deleted = executor.submit(() -> {
                deletingThread.set(Thread.currentThread());
                return service.deleteSecurityGroup(region, null, "default-rule-race", false);
            });
            awaitBlockedOrDone(deletingThread, deleted);
            assertFalse(deleted.isDone(), "Deletion must wait until the default rule is persisted");
            factory.rules.release();
            SecurityGroup group = created.get(5, TimeUnit.SECONDS);
            assertEquals(group.getGroupId(), deleted.get(5, TimeUnit.SECONDS));
            assertTrue(service.describeSecurityGroups(region, List.of(group.getGroupId()),
                    List.of(), Map.of()).isEmpty());
            assertTrue(service.describeSecurityGroupRules(region, List.of(group.getGroupId()), List.of()).isEmpty(),
                    "A deleted group must not leave an orphaned default rule");
        } finally {
            factory.rules.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void publishedInterfaceCannotReferenceSuccessfullyDeletedGroup() throws Exception {
        TestStores factory = new TestStores();
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
        Ec2Service service = service(new TestStores());
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
        Ec2Service service = service(new TestStores());
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "deleted", "deleted", vpc).getGroupId();
        service.deleteSecurityGroup(region, group);
        AwsException error = assertThrows(AwsException.class, () -> service.createNetworkInterface(
                region, subnet, "rejected", null, List.of(), List.of(group), List.of()));
        assertEquals("InvalidSecurityGroupID.NotFound", error.getErrorCode());
    }

    @Test
    void publishedInstanceCannotReferenceSuccessfullyDeletedGroup() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "launch", "launch", vpc).getGroupId();
        factory.instances.parkNextWrite();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Reservation> created = executor.submit(() -> service.runInstances(region, "ami-test", "t3.micro",
                    1, 1, null, List.of(group), subnet, null, List.of(), null, null));
            assertTrue(factory.instances.awaitParked(), "Launch must reach instance publication");
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
            factory.instances.release();
            Reservation reservation = created.get(5, TimeUnit.SECONDS);
            AwsException error = deleted.get(5, TimeUnit.SECONDS);
            assertNotNull(error, "Deletion must observe the published instance reference");
            assertEquals("DependencyViolation", error.getErrorCode());
            assertEquals(group, reservation.getInstances().getFirst().getSecurityGroups().getFirst().getGroupId());
            assertEquals(1, service.describeSecurityGroups(region, List.of(group), List.of(), Map.of()).size());
        } finally {
            factory.instances.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void launchRechecksGroupDeletedAfterInitialResolutionBeforeAllocating() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "deleted-launch", "launch", vpc).getGroupId();
        int available = service.describeSubnets(region, List.of(subnet), Map.of()).getFirst().getAvailableIpAddressCount();
        factory.groups.parkNextRead();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<AwsException> created = executor.submit(() -> {
                try {
                    service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                            List.of(group), subnet, null, List.of(), null, null);
                    return null;
                } catch (AwsException error) {
                    return error;
                }
            });
            assertTrue(factory.groups.awaitParked(), "Launch must capture its original security group");
            service.deleteSecurityGroup(region, group);
            factory.groups.release();
            AwsException error = created.get(5, TimeUnit.SECONDS);
            assertNotNull(error, "Launch must reject the now-deleted captured group");
            assertEquals("InvalidGroup.NotFound", error.getErrorCode());
            assertTrue(service.describeInstances(region, List.of(), Map.of()).isEmpty());
            assertTrue(service.describeVolumes(region, List.of(), Map.of()).isEmpty());
            assertEquals(available,
                    service.describeSubnets(region, List.of(subnet), Map.of()).getFirst().getAvailableIpAddressCount());
        } finally {
            factory.groups.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void existingInstancePreventsDeletionUntilTerminated() {
        Ec2Service service = service(new TestStores());
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String group = service.createSecurityGroup(region, "existing-launch", "launch", vpc).getGroupId();
        String id = service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                List.of(group), subnet, null, List.of(), null, null).getInstances().getFirst().getInstanceId();
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSecurityGroup(region, group)).getErrorCode());
        service.terminateInstances(region, List.of(id));
        service.deleteSecurityGroup(region, group);
        assertTrue(service.describeSecurityGroups(region, List.of(), List.of(), Map.of()).stream()
                .noneMatch(value -> group.equals(value.getGroupId())));
    }

    @Test
    void modifyRejectsGroupDeletedAfterCapturedRead() throws Exception {
        TestStores factory = new TestStores();
        Ec2Service service = service(factory);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String original = service.createSecurityGroup(region, "original-modify", "original", vpc).getGroupId();
        String replacement = service.createSecurityGroup(region, "replacement-modify", "replacement", vpc).getGroupId();
        String instance = service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                List.of(original), subnet, null, List.of(), null, null).getInstances().getFirst().getInstanceId();
        factory.groups.parkNextRead();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AwsException> modified = executor.submit(() -> {
                try {
                    service.modifyInstanceGroups(region, instance, List.of(replacement));
                    return null;
                } catch (AwsException error) {
                    return error;
                }
            });
            assertTrue(factory.groups.awaitParked(), "Modifier must capture the replacement group before deletion");
            AtomicReference<Thread> deletingThread = new AtomicReference<>();
            Future<AwsException> deleted = executor.submit(() -> {
                deletingThread.set(Thread.currentThread());
                try {
                    service.deleteSecurityGroup(region, replacement);
                    return null;
                } catch (AwsException error) {
                    return error;
                }
            });
            await().atMost(5, TimeUnit.SECONDS).until(() -> deleted.isDone()
                    || deletingThread.get() != null && deletingThread.get().getState() == Thread.State.BLOCKED);
            factory.groups.release();
            AwsException modifyError = modified.get(5, TimeUnit.SECONDS);
            AwsException deleteError = deleted.get(5, TimeUnit.SECONDS);
            assertTrue(modifyError != null || deleteError != null,
                    "Deletion and a new assignment cannot both succeed for the captured group");
            if (deleteError == null) {
                assertEquals("InvalidGroup.NotFound", modifyError.getErrorCode());
                assertEquals(original, service.describeInstances(region, List.of(instance), Map.of()).getFirst()
                        .getInstances().getFirst().getSecurityGroups().getFirst().getGroupId());
            } else {
                assertEquals("DependencyViolation", deleteError.getErrorCode());
                assertNull(modifyError);
                assertEquals(replacement, service.describeInstances(region, List.of(instance), Map.of()).getFirst()
                        .getInstances().getFirst().getSecurityGroups().getFirst().getGroupId());
            }
        } finally {
            factory.groups.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void modifyExistingGroupMovesDependencyToReplacement() {
        Ec2Service service = service(new TestStores());
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String original = service.createSecurityGroup(region, "original-positive", "original", vpc).getGroupId();
        String replacement = service.createSecurityGroup(region, "replacement-positive", "replacement", vpc).getGroupId();
        String instance = service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                List.of(original), subnet, null, List.of(), null, null).getInstances().getFirst().getInstanceId();
        service.modifyInstanceGroups(region, instance, List.of(replacement));
        service.deleteSecurityGroup(region, original);
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSecurityGroup(region, replacement)).getErrorCode());
        assertEquals(replacement, service.describeInstances(region, List.of(instance), Map.of()).getFirst()
                .getInstances().getFirst().getSecurityGroups().getFirst().getGroupId());
    }

    @Test
    void modifyMissingGroupPreservesOriginalDependency() {
        Ec2Service service = service(new TestStores());
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String original = service.createSecurityGroup(region, "original-missing", "original", vpc).getGroupId();
        String instance = service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                List.of(original), subnet, null, List.of(), null, null).getInstances().getFirst().getInstanceId();
        assertEquals("InvalidGroup.NotFound", assertThrows(AwsException.class,
                () -> service.modifyInstanceGroups(region, instance, List.of("sg-missing"))).getErrorCode());
        assertEquals("DependencyViolation", assertThrows(AwsException.class,
                () -> service.deleteSecurityGroup(region, original)).getErrorCode());
    }

    @Test
    void pendingFirewallAssignmentBlocksOnlyItsGroupAndReleasesOnFailure() throws Exception {
        TestStores factory = new TestStores();
        EmulatorConfig config = mockConfig();
        Ec2ContainerManager manager = mock(Ec2ContainerManager.class);
        Ec2Service service = new Ec2Service(config, manager, mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), factory.storage);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String original = service.createSecurityGroup(region, "firewall-original", "original", vpc).getGroupId();
        String replacement = service.createSecurityGroup(region, "firewall-replacement", "replacement", vpc).getGroupId();
        String unrelated = service.createSecurityGroup(region, "firewall-unrelated", "unrelated", vpc).getGroupId();
        Instance instance = service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                List.of(original), subnet, null, List.of(), null, null).getInstances().getFirst();
        instance.setState(InstanceState.running());
        EmulatorConfig.NetworkConfig network = mock(EmulatorConfig.NetworkConfig.class);
        EmulatorConfig.SecurityGroupEnforcementConfig enforcement = mock(EmulatorConfig.SecurityGroupEnforcementConfig.class);
        when(config.network()).thenReturn(network);
        when(network.securityGroupEnforcement()).thenReturn(enforcement);
        when(enforcement.enabled()).thenReturn(true);
        when(config.services().ec2().mock()).thenReturn(false);
        CountDownLatch updating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IllegalStateException failure = new IllegalStateException("Owned firewall failure");
        doAnswer(invocation -> {
            updating.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            throw failure;
        }).when(manager).updateSecurityGroups(anyString(), anySet(), anyMap(), anyMap());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> modified = executor.submit(() -> assertThrows(IllegalStateException.class,
                    () -> service.modifyInstanceGroups(region, instance.getInstanceId(), List.of(replacement))));
            assertTrue(updating.await(5, TimeUnit.SECONDS), "Must reach actual firewall update call");
            Future<?> independent = executor.submit(() -> service.deleteSecurityGroup(region, unrelated));
            independent.get(5, TimeUnit.SECONDS);
            assertEquals("DependencyViolation", assertThrows(AwsException.class,
                    () -> service.deleteSecurityGroup(region, replacement)).getErrorCode());
            release.countDown();
            assertSame(failure, modified.get(5, TimeUnit.SECONDS));
            service.deleteSecurityGroup(region, replacement);
            assertEquals(original, service.describeInstances(region, List.of(instance.getInstanceId()), Map.of()).getFirst()
                    .getInstances().getFirst().getSecurityGroups().getFirst().getGroupId());
            assertEquals("DependencyViolation", assertThrows(AwsException.class,
                    () -> service.deleteSecurityGroup(region, original)).getErrorCode());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentFailedAssignmentsReleaseOnlyTheirOwnPendingReference() throws Exception {
        TestStores factory = new TestStores();
        EmulatorConfig config = mockConfig();
        Ec2ContainerManager manager = mock(Ec2ContainerManager.class);
        Ec2Service service = new Ec2Service(config, manager, mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), factory.storage);
        String region = "us-east-1";
        String vpc = service.createVpc(region, "10.0.0.0/16", false).getVpcId();
        String subnet = service.createSubnet(region, vpc, "10.0.1.0/24", region + "a").getSubnetId();
        String original = service.createSecurityGroup(region, "parallel-original", "original", vpc).getGroupId();
        String replacement = service.createSecurityGroup(region, "parallel-replacement", "replacement", vpc).getGroupId();
        List<Instance> instances = service.runInstances(region, "ami-test", "t3.micro", 2, 2, null,
                List.of(original), subnet, null, List.of(), null, null).getInstances();
        instances.forEach(instance -> instance.setState(InstanceState.running()));
        EmulatorConfig.NetworkConfig network = mock(EmulatorConfig.NetworkConfig.class);
        EmulatorConfig.SecurityGroupEnforcementConfig enforcement = mock(EmulatorConfig.SecurityGroupEnforcementConfig.class);
        when(config.network()).thenReturn(network);
        when(network.securityGroupEnforcement()).thenReturn(enforcement);
        when(enforcement.enabled()).thenReturn(true);
        when(config.services().ec2().mock()).thenReturn(false);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch firstRelease = new CountDownLatch(1);
        CountDownLatch secondRelease = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            boolean first = calls.incrementAndGet() == 1;
            (first ? firstEntered : secondEntered).countDown();
            assertTrue((first ? firstRelease : secondRelease).await(5, TimeUnit.SECONDS));
            throw new IllegalStateException("Owned parallel firewall failure");
        }).when(manager).updateSecurityGroups(anyString(), anySet(), anyMap(), anyMap());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> assertThrows(IllegalStateException.class,
                    () -> service.modifyInstanceGroups(region, instances.getFirst().getInstanceId(), List.of(replacement))));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> assertThrows(IllegalStateException.class,
                    () -> service.modifyInstanceGroups(region, instances.getLast().getInstanceId(), List.of(replacement))));
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            firstRelease.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals("DependencyViolation", assertThrows(AwsException.class,
                    () -> service.deleteSecurityGroup(region, replacement)).getErrorCode());
            secondRelease.countDown();
            second.get(5, TimeUnit.SECONDS);
            service.deleteSecurityGroup(region, replacement);
            assertEquals(2, calls.get());
        } finally {
            firstRelease.countDown();
            secondRelease.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Ec2Service service(TestStores factory) {
        return new Ec2Service(mockConfig(), mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), factory.storage);
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

    private static final class TestStores {
        private final ParkingStore<NetworkInterface> interfaces = new ParkingStore<>();
        private final ParkingStore<Instance> instances = new ParkingStore<>();
        private final ParkingStore<SecurityGroupRule> rules = new ParkingStore<>();
        private final ParkingStore<SecurityGroup> groups = new ParkingStore<>();
        private final InMemoryStorageFactory storage = new InMemoryStorageFactory("000000000000")
                .withStore("ec2-network-interfaces.json", interfaces)
                .withStore("ec2-instances.json", instances)
                .withStore("ec2-security-groups.json", groups)
                .withStore("ec2-security-group-rules.json", rules);
    }
}
