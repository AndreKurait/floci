package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.model.NetworkInterface;
import io.floci.aws.services.ec2.model.NetworkInterfaceAttachment;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.container.EcsCredentialsProxy;
import io.floci.aws.services.ecs.container.EcsTaskLinkLocalAddresses;
import io.floci.aws.services.ecs.container.EcsTaskRoleCredentials;
import io.floci.aws.services.ecs.container.HostVolumePolicy;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.secretsmanager.SecretsManagerService;
import io.floci.aws.services.ssm.SsmService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.floci.aws.testing.ThreadStates.awaitBlockedOrDone;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Actual account scopes, EC2 service and persistent records; external network effects are mocks. */
@QuarkusTest
@Timeout(20)
class CallerServiceNetworkCleanupTest {
    private static final String OWNER = "111122223333";
    private static final String OTHER = "444455556666";
    private static final String REGION = "us-west-2";
    private static final String OTHER_REGION = "eu-west-1";
    private static final String ENI = "eni-cleanup-owned";
    private static final String KEY = REGION + "::" + ENI;
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    @Inject Instance<RequestContext> contexts;
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionFailureRetainsExactOwnerAndResumesAcrossAnotherAmbientAccount(boolean afterCommit) throws Exception {
        Fixture f = new Fixture();
        NetworkInterface owned = f.seed(OWNER, REGION, ENI);
        NetworkInterface foreign = f.seed(OTHER, REGION, ENI);
        NetworkInterface otherRegion = f.seed(OWNER, OTHER_REGION, ENI);
        JsonNode foreignBefore = JSON.valueToTree(foreign);
        JsonNode regionBefore = JSON.valueToTree(otherRegion);
        Object owner = new Object();
        Runnable cleanup = asOwner(() -> f.service.captureNetworkInterfaceCleanupForService(owner, REGION, ENI));
        f.raw.failure = afterCommit ? "after-delete" : "before-delete";
        assertThrows(IllegalStateException.class, () -> RequestScopes.runAs(OTHER, OTHER_REGION, cleanup));
        assertEquals("available", owned.getStatus());
        assertEquals(!afterCommit, f.interfaces.getForAccount(OWNER, KEY).isPresent());
        assertEquals(foreignBefore, JSON.valueToTree(f.interfaces.getForAccount(OTHER, KEY).orElseThrow()));
        assertEquals(regionBefore, JSON.valueToTree(f.interfaces.getForAccount(OWNER,
                OTHER_REGION + "::" + ENI).orElseThrow()));
        assertThrows(IllegalStateException.class, () -> asOwner(() ->
                f.service.captureNetworkInterfaceCleanupForService(new Object(), REGION, ENI)));
        RequestScopes.runAs(OTHER, OTHER_REGION, cleanup);
        RequestScopes.runAs(OTHER, OTHER_REGION, cleanup);
        assertFalse(f.interfaces.getForAccount(OWNER, KEY).isPresent());
        assertEquals(afterCommit ? 1 : 2, f.raw.deletes.get());
        verify(f.networks, times(1)).releasePrivateIp(REGION, "subnet-owned", "10.0.1.10");
        JsonNode durable = JSON.readTree(Files.readAllBytes(f.file));
        assertEquals(foreignBefore, durable.path(OTHER + "/" + KEY));
        assertEquals(regionBefore, durable.path(OWNER + "/" + OTHER_REGION + "::" + ENI));
        assertFalse(durable.has(OWNER + "/" + KEY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"replacement", "account", "subnet", "ip", "attached"})
    void changedStoredIdentityRefusesWithoutChangingFullCurrentOrForeignRecords(String mutation) throws Exception {
        Fixture f = new Fixture();
        NetworkInterface owned = f.seed(OWNER, REGION, ENI);
        f.seed(OTHER, REGION, ENI);
        Runnable cleanup = asOwner(() -> f.service.captureNetworkInterfaceCleanupForService(new Object(), REGION, ENI));
        switch (mutation) {
            case "replacement" -> f.seed(OWNER, REGION, ENI);
            case "account" -> owned.setOwnerId(OTHER);
            case "subnet" -> owned.setSubnetId("subnet-replacement");
            case "ip" -> owned.setPrivateIpAddress("10.0.1.99");
            case "attached" -> owned.setAttachment(new NetworkInterfaceAttachment());
            default -> throw new AssertionError(mutation);
        }
        f.interfaces.flush();
        JsonNode before = JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys());
        byte[] durableBefore = Files.readAllBytes(f.file);
        assertThrows(IllegalStateException.class, cleanup::run);
        assertThrows(IllegalStateException.class, cleanup::run);
        assertEquals(before, JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys()));
        assertArrayEquals(durableBefore, Files.readAllBytes(f.file));
        assertEquals(0, f.raw.deletes.get());
    }

    @Test
    void foreignLegacyCaptureRefusalPreservesRawKeyAndDurableBytes() throws Exception {
        Fixture f = new Fixture();
        NetworkInterface foreign = network(OTHER, ENI);
        f.raw.put(KEY, foreign);
        JsonNode before = JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys());
        byte[] durableBefore = Files.readAllBytes(f.file);
        assertThrows(IllegalStateException.class, () -> asOwner(() ->
                f.service.captureNetworkInterfaceCleanupForService(new Object(), REGION, ENI)));
        assertEquals(before, JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys()));
        assertArrayEquals(durableBefore, Files.readAllBytes(f.file));
        assertFalse(f.interfaces.getForAccount(OWNER, KEY).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "hold", "release", "attach", "detach"})
    void activeReservationRefusesActualPublicMutatorsWithoutChangingAnyRecord(String action) throws Exception {
        Fixture f = new Fixture();
        NetworkInterface owned = f.seed(OWNER, REGION, ENI);
        f.seed(OTHER, REGION, ENI);
        Runnable cleanup = asOwner(() -> f.service.captureNetworkInterfaceCleanupForService(new Object(), REGION, ENI));
        if (action.equals("detach")) {
            NetworkInterfaceAttachment attachment = new NetworkInterfaceAttachment();
            attachment.setAttachmentId("eni-attach-held");
            attachment.setInstanceId("i-held");
            owned.setAttachment(attachment);
            f.interfaces.flush();
        }
        JsonNode before = JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys());
        byte[] durableBefore = Files.readAllBytes(f.file);
        AwsException failure = assertThrows(AwsException.class, () -> RequestScopes.runAs(OWNER, REGION, () -> {
            switch (action) {
                case "delete" -> f.service.deleteNetworkInterface(REGION, ENI);
                case "hold" -> f.service.holdNetworkInterfaceForService(REGION, ENI);
                case "release" -> f.service.releaseNetworkInterfaceFromService(REGION, ENI);
                case "attach" -> f.service.attachNetworkInterface(REGION, ENI, "i-held", 1);
                case "detach" -> f.service.detachNetworkInterface(REGION, "eni-attach-held", true);
                default -> throw new AssertionError(action);
            }
        }));
        assertEquals("InvalidNetworkInterface.InUse", failure.getErrorCode());
        assertEquals(before, JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys()));
        assertArrayEquals(durableBefore, Files.readAllBytes(f.file));
        if (!action.equals("detach")) {
            cleanup.run();
            assertFalse(f.interfaces.getForAccount(OWNER, KEY).isPresent());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void privateAddressReleaseFailureIsUnknownAndNeverAutomaticallyRepeated(boolean afterEffect) {
        Fixture f = new Fixture();
        f.seed(OWNER, REGION, ENI);
        Runnable cleanup = asOwner(() -> f.service.captureNetworkInterfaceCleanupForService(new Object(), REGION, ENI));
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        doAnswer(ignored -> {
            attempts.incrementAndGet();
            if (afterEffect) {
                effects.incrementAndGet();
            }
            throw new IllegalStateException("address release outcome unavailable");
        }).when(f.networks).releasePrivateIp(REGION, "subnet-owned", "10.0.1.10");
        assertThrows(IllegalStateException.class, cleanup::run);
        assertFalse(f.interfaces.getForAccount(OWNER, KEY).isPresent());
        assertThrows(IllegalStateException.class, cleanup::run);
        assertEquals(1, attempts.get());
        assertEquals(afterEffect ? 1 : 0, effects.get());
        assertThrows(IllegalStateException.class, () -> asOwner(() ->
                f.service.captureNetworkInterfaceCleanupForService(new Object(), REGION, ENI)));
    }

    @Test
    void absentScopedInterfaceCompletesWithoutTouchingSameIdInAnotherAccount() {
        Fixture f = new Fixture();
        NetworkInterface foreign = f.seed(OTHER, REGION, ENI);
        JsonNode before = JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys());
        Object owner = new Object();
        Runnable cleanup = asOwner(() -> f.service.captureNetworkInterfaceCleanupForService(owner, REGION, ENI));
        assertSame(cleanup, asOwner(() -> f.service.captureNetworkInterfaceCleanupForService(owner, REGION, ENI)));
        cleanup.run();
        cleanup.run();
        assertEquals(before, JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys()));
        assertSame(foreign, f.interfaces.getForAccount(OTHER, KEY).orElseThrow());
        assertEquals(0, f.raw.deletes.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "helper-retry", "task-arn", "task-attachment", "task-eni"})
    void actualEcsCallerAndObserverKeepTheCapturedPersistentEniAndTaskIdentity(String scenario)
            throws Exception {
        boolean firstFailure = !scenario.equals("success");
        Fixture f = new Fixture();
        f.seed(OWNER, REGION, ENI);
        NetworkInterface foreign = f.seed(OTHER, REGION, ENI);
        JsonNode foreignBefore = JSON.valueToTree(foreign);
        DockerClient docker = mock(DockerClient.class);
        ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
        when(docker.listContainersCmd()).thenReturn(listing);
        when(listing.exec()).thenReturn(List.of());
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        SecurityGroupFirewallManager firewall = spy(new SecurityGroupFirewallManager(docker,
                mock(ContainerBuilder.class), lifecycle, config, mock(EmbeddedDnsServer.class)));
        doNothing().when(firewall).reconcileAll();
        firewall.register(new SecurityGroupNftCompiler.Endpoint(OWNER, REGION, "vpc-owned", ENI,
                "10.0.1.10", "172.19.0.4", Set.of(), List.of()), "helper-captured", Map.of());
        AtomicInteger removals = new AtomicInteger();
        IllegalStateException injected = new IllegalStateException("first strict helper removal refused");
        doAnswer(ignored -> {
            if (removals.incrementAndGet() == 1 && firstFailure) {
                throw injected;
            }
            return null;
        }).when(lifecycle).removeIfExistsStrict("helper-captured");
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:" + REGION + ":" + OWNER + ":task/cluster/owned");
        task.setAttachmentId("ecs-attachment-owned");
        task.setNetworkInterfaceId(ENI);
        task.setAttachmentStatus("ATTACHED");
        task.setLastStatus("STOPPED");
        task.setDesiredStatus("STOPPED");
        task.setContainers(List.of());
        EcsContainerManager ecs = new EcsContainerManager(mock(ContainerBuilder.class), lifecycle,
                mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                new RegionResolver(REGION, OWNER), mock(LaunchedContainerAwsEnv.class),
                mock(SsmService.class), mock(SecretsManagerService.class), mock(S3Service.class),
                mock(EcrRegistryManager.class), mock(HostVolumePolicy.class), f.service, firewall,
                mock(EcsTaskRoleCredentials.class), mock(EcsCredentialsProxy.class), new EcsTaskLinkLocalAddresses());
        try {
            if (firstFailure) {
                assertSame(injected, assertThrows(IllegalStateException.class,
                        () -> RequestScopes.runAs(OWNER, REGION, () -> ecs.releaseTaskNetwork(task, REGION))));
                assertEquals("ATTACHED", task.getAttachmentStatus());
                assertTrue(f.interfaces.getForAccount(OWNER, KEY).isPresent());
                assertEquals(0, f.raw.deletes.get());
                JsonNode retainedBefore = JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys());
                byte[] durableBefore = Files.readAllBytes(f.file);
                switch (scenario) {
                    case "task-arn" -> task.setTaskArn("arn:aws:ecs:" + REGION + ":" + OTHER + ":task/foreign");
                    case "task-attachment" -> task.setAttachmentId("ecs-replacement");
                    case "task-eni" -> task.setNetworkInterfaceId("eni-replacement");
                    default -> { }
                }
                RequestScopes.runAs(OTHER, OTHER_REGION, firewall::retireDnsOrigins);
                if (scenario.startsWith("task-")) {
                    assertEquals("ATTACHED", task.getAttachmentStatus());
                    assertEquals(retainedBefore, JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys()));
                    assertArrayEquals(durableBefore, Files.readAllBytes(f.file));
                    assertEquals(0, f.raw.deletes.get());
                    assertThrows(IllegalStateException.class, firewall::checkReset);
                    return;
                }
            } else {
                RequestScopes.runAs(OWNER, REGION, () -> ecs.releaseTaskNetwork(task, REGION));
            }
            RequestScopes.runAs(OTHER, OTHER_REGION, () -> ecs.releaseTaskNetwork(task, REGION));
            firewall.retireDnsOrigins();
            assertEquals("DELETED", task.getAttachmentStatus());
            assertFalse(f.interfaces.getForAccount(OWNER, KEY).isPresent());
            assertEquals(foreignBefore, JSON.valueToTree(f.interfaces.getForAccount(OTHER, KEY).orElseThrow()));
            assertEquals(foreignBefore, JSON.readTree(Files.readAllBytes(f.file)).path(OTHER + "/" + KEY));
            assertEquals(firstFailure ? 2 : 1, removals.get());
            assertEquals(1, f.raw.deletes.get());
            verify(f.networks, times(1)).releasePrivateIp(REGION, "subnet-owned", "10.0.1.10");
        } finally {
            firewall.stopDnsOriginObserver();
        }
    }

    @Test
    void reservedAvailableEniRejectsActualSuppliedInterfaceLaunchBeforePublishingAnInstance() throws Exception {
        Fixture f = new Fixture();
        String subnet = asOwner(() -> f.service.describeSubnets(REGION, List.of(), Map.of())
                .getFirst().getSubnetId());
        NetworkInterface row = asOwner(() -> f.service.createNetworkInterface(REGION, subnet,
                null, null, List.of(), List.of(), List.of()));
        String eni = row.getNetworkInterfaceId();
        Runnable cleanup = asOwner(() -> f.service.captureNetworkInterfaceCleanupForService(new Object(), REGION, eni));
        JsonNode before = JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys());
        byte[] durableBefore = Files.readAllBytes(f.file);
        AwsException failure = assertThrows(AwsException.class, () -> asOwner(() -> f.service.runInstances(REGION,
                "ami-1234567890abcdef0", "t3.micro", 1, 1, null, List.of(), null, null, List.of(),
                null, null, null, eni, 0)));
        assertEquals("InvalidNetworkInterface.InUse", failure.getErrorCode());
        assertEquals(before, JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys()));
        assertArrayEquals(durableBefore, Files.readAllBytes(f.file));
        assertTrue(f.otherStores.get("ec2-instances.json").scanAllAccountsWithRawKeys().isEmpty());
        cleanup.run();
        assertFalse(f.interfaces.getForAccount(OWNER, REGION + "::" + eni).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "hold", "release", "attach"})
    void callerAlreadyWaitingOnAllocationLockObservesTheNewCleanupReservation(String action) throws Exception {
        Fixture f = new Fixture();
        f.seed(OWNER, REGION, ENI);
        f.seed(OTHER, REGION, ENI);
        Field lockField = Ec2Service.class.getDeclaredField("privateIpAllocationLock");
        lockField.setAccessible(true);
        Object lock = lockField.get(f.service);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> caller = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        Future<?> request;
        Runnable cleanup;
        try {
            synchronized (lock) {
                request = executor.submit(() -> RequestScopes.runAs(OWNER, REGION, () -> {
                    caller.set(Thread.currentThread());
                    entered.countDown();
                    switch (action) {
                        case "delete" -> f.service.deleteNetworkInterface(REGION, ENI);
                        case "hold" -> f.service.holdNetworkInterfaceForService(REGION, ENI);
                        case "release" -> f.service.releaseNetworkInterfaceFromService(REGION, ENI);
                        case "attach" -> f.service.attachNetworkInterface(REGION, ENI, "i-held", 1);
                        default -> throw new AssertionError(action);
                    }
                }));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                awaitBlockedOrDone(caller, request);
                assertFalse(request.isDone());
                assertEquals(Thread.State.BLOCKED, caller.get().getState());
                cleanup = asOwner(() -> f.service.captureNetworkInterfaceCleanupForService(new Object(), REGION, ENI));
            }
            JsonNode before = JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys());
            byte[] durableBefore = Files.readAllBytes(f.file);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> request.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof AwsException);
            assertEquals("InvalidNetworkInterface.InUse", ((AwsException) failure.getCause()).getErrorCode());
            assertEquals(before, JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys()));
            assertArrayEquals(durableBefore, Files.readAllBytes(f.file));
            cleanup.run();
            assertFalse(f.interfaces.getForAccount(OWNER, KEY).isPresent());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void ownedLegacyCaptureValidatesBeforeMigratingAndCleansTheExactTupleOnce() throws Exception {
        Fixture f = new Fixture();
        NetworkInterface owned = network(OWNER, ENI);
        f.raw.put(KEY, owned);
        JsonNode complete = JSON.valueToTree(owned);
        Runnable cleanup = asOwner(() -> f.service.captureNetworkInterfaceCleanupForService(new Object(), REGION, ENI));
        assertFalse(f.raw.keys().contains(KEY));
        assertSame(owned, f.interfaces.getForAccount(OWNER, KEY).orElseThrow());
        assertEquals(complete, JSON.readTree(Files.readAllBytes(f.file)).path(OWNER + "/" + KEY));
        RequestScopes.runAs(OTHER, OTHER_REGION, cleanup);
        RequestScopes.runAs(OTHER, OTHER_REGION, cleanup);
        assertTrue(f.raw.keys().isEmpty());
        assertEquals(2, f.raw.deletes.get(), "one validated migration and one owned deletion");
        verify(f.networks, times(1)).releasePrivateIp(REGION, "subnet-owned", "10.0.1.10");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-owner", "wrong-id", "attached"})
    void malformedLegacyCaptureRefusesBeforeAnyRawOrDurableMutation(String fault) throws Exception {
        Fixture f = new Fixture();
        NetworkInterface legacy = network(OWNER, ENI);
        switch (fault) {
            case "missing-owner" -> legacy.setOwnerId(null);
            case "wrong-id" -> legacy.setNetworkInterfaceId("eni-different");
            case "attached" -> legacy.setAttachment(new NetworkInterfaceAttachment());
            default -> throw new AssertionError(fault);
        }
        f.raw.put(KEY, legacy);
        JsonNode before = JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys());
        byte[] durable = Files.readAllBytes(f.file);
        assertThrows(IllegalStateException.class, () -> asOwner(() ->
                f.service.captureNetworkInterfaceCleanupForService(new Object(), REGION, ENI)));
        assertEquals(before, JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys()));
        assertArrayEquals(durable, Files.readAllBytes(f.file));
        assertEquals(0, f.raw.deletes.get());
    }

    @Test
    void corruptScopedPresencePreventsOwnedLegacyFallbackAndPreservesBothRows() throws Exception {
        Fixture f = new Fixture();
        f.interfaces.putForAccount(OWNER, KEY, network(OTHER, ENI));
        f.raw.put(KEY, network(OWNER, ENI));
        JsonNode before = JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys());
        byte[] durable = Files.readAllBytes(f.file);
        assertThrows(IllegalStateException.class, () -> asOwner(() ->
                f.service.captureNetworkInterfaceCleanupForService(new Object(), REGION, ENI)));
        assertEquals(before, JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys()));
        assertArrayEquals(durable, Files.readAllBytes(f.file));
        assertEquals(0, f.raw.deletes.get());
    }

    @Test
    void scopedOwnerWinsOverForeignLegacyAndCleanupLeavesTheLegacyRecordUntouched() throws Exception {
        Fixture f = new Fixture();
        f.seed(OWNER, REGION, ENI);
        f.raw.put(KEY, network(OTHER, ENI));
        JsonNode foreign = JSON.valueToTree(f.raw.get(KEY).orElseThrow());
        byte[] beforeCapture = Files.readAllBytes(f.file);
        Runnable cleanup = asOwner(() -> f.service.captureNetworkInterfaceCleanupForService(new Object(), REGION, ENI));
        assertArrayEquals(beforeCapture, Files.readAllBytes(f.file));
        cleanup.run();
        cleanup.run();
        assertEquals(Set.of(KEY), f.raw.keys());
        assertEquals(foreign, JSON.valueToTree(f.raw.get(KEY).orElseThrow()));
        assertEquals(foreign, JSON.readTree(Files.readAllBytes(f.file)).path(KEY));
        assertEquals(1, f.raw.deletes.get());
        verify(f.networks, times(1)).releasePrivateIp(REGION, "subnet-owned", "10.0.1.10");
    }

    @Test
    void retryAfterCommittedOwnedDeletionDoesNotMigrateANewForeignLegacyRecord() throws Exception {
        Fixture f = new Fixture();
        f.seed(OWNER, REGION, ENI);
        Runnable cleanup = asOwner(() -> f.service.captureNetworkInterfaceCleanupForService(new Object(), REGION, ENI));
        f.raw.failure = "after-delete";
        assertThrows(IllegalStateException.class, cleanup::run);
        assertFalse(f.interfaces.getForAccount(OWNER, KEY).isPresent());
        f.raw.put(KEY, network(OTHER, ENI));
        JsonNode before = JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys());
        byte[] durable = Files.readAllBytes(f.file);
        RequestScopes.runAs(OTHER, OTHER_REGION, cleanup);
        RequestScopes.runAs(OTHER, OTHER_REGION, cleanup);
        assertEquals(before, JSON.valueToTree(f.interfaces.scanAllAccountsWithRawKeys()));
        assertArrayEquals(durable, Files.readAllBytes(f.file));
        assertEquals(1, f.raw.deletes.get());
        verify(f.networks, times(1)).releasePrivateIp(REGION, "subnet-owned", "10.0.1.10");
    }

    private <T> T asOwner(Supplier<T> action) {
        return RequestScopes.callAs(OWNER, REGION, action);
    }

    private static NetworkInterface network(String owner, String eni) {
        NetworkInterface row = new NetworkInterface();
        row.setNetworkInterfaceId(eni);
        row.setOwnerId(owner);
        row.setSubnetId("subnet-owned");
        row.setVpcId("vpc-owned");
        row.setPrivateIpAddress("10.0.1.10");
        row.setDescription("complete unchanged ownership sentinel");
        row.setStatus("in-use");
        return row;
    }

    private final class Fixture {
        private final Path file = directory.resolve("interfaces.json");
        private final FaultStorage raw = new FaultStorage(file);
        private final AccountAwareStorageBackend<NetworkInterface> interfaces =
                new AccountAwareStorageBackend<>(raw, contexts, "000000000000");
        private final VpcNetworkManager networks = mock(VpcNetworkManager.class);
        private final Ec2Service service;
        private final Map<String, AccountAwareStorageBackend<?>> otherStores = new HashMap<>();

        private Fixture() {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.defaultAccountId()).thenReturn("000000000000");
            when(config.services().ec2().mock()).thenReturn(true);
            when(config.services().ec2().mockPublicAddresses())
                    .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.DOCUMENTATION);
            StorageFactory factory = new StorageFactory(null, null) {
                @Override
                @SuppressWarnings("unchecked")
                public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                                TypeReference<Map<String, V>> type) {
                    if (fileName.equals("ec2-network-interfaces.json")) {
                        return (AccountAwareStorageBackend<V>) interfaces;
                    }
                    AccountAwareStorageBackend<V> store = new AccountAwareStorageBackend<>(new InMemoryStorage<>(),
                            contexts, "000000000000");
                    otherStores.put(fileName, store);
                    return store;
                }
            };
            service = new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                    mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                    factory, contexts, networks, null, null, null);
        }

        private NetworkInterface seed(String account, String region, String eni) {
            NetworkInterface row = network(account, eni);
            interfaces.putForAccount(account, region + "::" + eni, row);
            return row;
        }
    }

    private static final class FaultStorage extends PersistentStorage<String, NetworkInterface> {
        private final AtomicInteger deletes = new AtomicInteger();
        private String failure;

        private FaultStorage(Path file) {
            super(file, new TypeReference<Map<String, NetworkInterface>>() {});
        }

        @Override
        public synchronized void delete(String key) {
            deletes.incrementAndGet();
            String fault = failure;
            failure = null;
            if ("before-delete".equals(fault)) {
                throw new IllegalStateException("delete rejected before commit");
            }
            super.delete(key);
            if ("after-delete".equals(fault)) {
                throw new IllegalStateException("delete committed before reply failed");
            }
        }
    }
}
