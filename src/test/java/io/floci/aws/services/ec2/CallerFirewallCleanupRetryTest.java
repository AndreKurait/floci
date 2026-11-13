package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.core.common.docker.PortAllocator;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceNetworkInterface;
import io.floci.aws.services.ec2.model.InstanceState;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real caller and firewall cleanup control; Docker and external coordinators are mocks.
 * An image callback proves only workload absence. The separately drained, actually constructed
 * EC2 executor establishes when its submitted task has finished, without exposing its Future.
 */
@Timeout(15)
class CallerFirewallCleanupRetryTest {

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void ec2SuccessfulHelperRemovalCompletesTheOwningInstanceCleanup() throws Exception {
        try (Harness h = new Harness("ec2-success", false, false)) {
            h.newEc2Owner();
            h.terminateAndDrain();
            h.snapshot("owner-task-finished");

            assertTrue(h.callback.await(1, TimeUnit.SECONDS));
            assertEquals(1, h.helperAttempts.get());
            assertEquals(1, h.callbacks.get());
            assertTrue(h.workloadGone);
            h.assertEc2Cleanup();
            h.firewall.retireDnsOrigins();
            assertEquals(1, h.helperAttempts.get(), "observer must not repeat a completed removal");
        }
    }

    @Test
    void ec2HelperRetryAlsoCompletesTheInterruptedOwningInstanceCleanup() throws Exception {
        try (Harness h = new Harness("ec2-retry", true, false)) {
            h.newEc2Owner();
            h.terminateAndDrain();
            h.snapshot("first-helper-failure-owner-task-finished");

            assertTrue(h.firstHelperFailure.await(1, TimeUnit.SECONDS));
            assertTrue(h.callback.await(1, TimeUnit.SECONDS));
            assertEquals(1, h.callbacks.get(), "workload absence permits the image callback");
            assertTrue(h.workloadGone);
            assertEquals(1, h.helperAttempts.get());
            assertFalse(h.helperGone);
            h.firewall.retireDnsOrigins();
            h.snapshot("observer-retry-finished");
            assertEquals(2, h.helperAttempts.get());
            assertTrue(h.helperGone);
            assertEquals(1, h.callbacks.get());

            // Positive recovery assertions remain unchanged when run against the before tree.
            h.assertEc2Cleanup();
        }
    }

    @Test
    void ec2HelperRetryDoesNotQualifyAnUncertainWorkloadForItsImageCallback() throws Exception {
        try (Harness h = new Harness("ec2-workload-unknown", true, true)) {
            h.newEc2Owner();
            h.terminateAndDrain();
            h.snapshot("workload-and-first-helper-removal-failed");

            assertTrue(h.firstHelperFailure.await(1, TimeUnit.SECONDS));
            assertFalse(h.workloadGone);
            assertEquals(0, h.callbacks.get());
            h.firewall.retireDnsOrigins();
            h.snapshot("helper-gone-workload-still-uncertain");

            assertEquals(2, h.helperAttempts.get());
            assertTrue(h.helperGone);
            assertFalse(h.workloadGone);
            assertEquals(0, h.callbacks.get());
            assertEquals(1, h.events.stream().filter("workload-removal-failed"::equals).count());
        }
    }

    @Test
    void ecsSuccessfulHelperReleaseDeletesTheExactOwnedEniOnce() throws Exception {
        try (Harness h = new Harness("ecs-success", false, false)) {
            h.newEcsOwner();
            h.ecs.releaseTaskNetwork(h.task, REGION);
            h.ecs.releaseTaskNetwork(h.task, REGION);
            h.snapshot("release-and-idempotent-repeat-finished");

            assertTrue(h.workloadGone);
            assertEquals(1, h.helperAttempts.get());
            h.assertEcsCleanup();
            assertTrue(h.events.indexOf("helper-removed") < h.events.indexOf("eni-hold-released"));
            assertTrue(h.events.indexOf("eni-hold-released") < h.events.indexOf("eni-deleted"));
        }
    }

    @Test
    void ecsReleaseAfterHelperRetryCompletesTheInterruptedEniCleanup() throws Exception {
        try (Harness h = new Harness("ecs-retry", true, false)) {
            h.newEcsOwner();
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> h.ecs.releaseTaskNetwork(h.task, REGION));
            assertSame(h.helperFailure, failure);
            h.snapshot("first-release-helper-failure");
            verify(h.ec2Service, never()).releaseNetworkInterfaceFromService(REGION, h.eni);
            verify(h.ec2Service, never()).deleteNetworkInterface(REGION, h.eni);

            h.firewall.retireDnsOrigins();
            assertEquals(2, h.helperAttempts.get());
            assertTrue(h.helperGone);
            h.ecs.releaseTaskNetwork(h.task, REGION);
            h.ecs.releaseTaskNetwork(h.task, REGION);
            h.snapshot("observer-and-owner-retries-finished");

            // A DELETED attachment alone is not evidence that the ENI was released.
            h.assertEcsCleanup();
        }
    }

    @Test
    void repeatedRealTerminationAfterCompletionDoesNotReopenStateOrRepeatCallback() throws Exception {
        try (Harness h = new Harness("ec2-repeated-complete", false, false)) {
            h.newEc2Owner();
            h.terminateAndDrain();
            h.assertEc2Cleanup();
            long completedAt = h.instance.getTerminatedAt();
            AtomicInteger repeatedCallback = new AtomicInteger();
            // The real manager is called again after its executor drained, as TerminateInstances does.
            h.ec2.terminate(h.instance, repeatedCallback::incrementAndGet);
            h.ec2.terminate(h.instance);
            h.firewall.retireDnsOrigins();
            h.snapshot("repeated-real-termination-finished");
            h.assertEc2Cleanup();
            assertEquals(completedAt, h.instance.getTerminatedAt());
            assertEquals(0, repeatedCallback.get(), "terminal state does not prove a fresh workload removal");
            assertEquals(1, h.callbacks.get());
            assertEquals(1, h.events.stream().filter("workload-removed"::equals).count());
            assertEquals(1, h.helperAttempts.get());
        }
    }

    @Test
    void realTerminationResumesAfterMetadataFailureWithoutRepeatingReleasedSshLease() throws Exception {
        try (Harness h = new Harness("ec2-metadata-retry", false, false)) {
            h.newEc2Owner();
            AtomicInteger attempts = new AtomicInteger();
            doAnswer(ignored -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("metadata rejected before removal");
                }
                h.events.add("bridge-metadata-unregistered");
                return null;
            }).when(h.metadata).unregisterContainer(h.bridgeIp, h.instance);
            h.terminateAndDrain();
            assertEquals("shutting-down", h.instance.getState().getName());
            assertEquals(1, h.callbacks.get());
            verify(h.ports, times(1)).release(h.sshPort);
            h.firewall.retireDnsOrigins();
            h.firewall.retireDnsOrigins();
            h.snapshot("metadata-retry-finished");
            assertEquals("terminated", h.instance.getState().getName());
            assertEquals(2, attempts.get());
            verify(h.ports, times(1)).release(h.sshPort);
            verify(h.metadata, times(1)).unregisterContainer(h.imdsIp, h.instance);
            verify(h.metadata, times(1)).unregisterInstance(h.instance);
            verify(h.vpcNetworks, times(1)).releasePrivateIp(REGION, h.subnet, h.privateIp);
            assertEquals(1, h.helperAttempts.get());
            assertEquals(1, h.callbacks.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ssh-before", "ssh-after", "ip-before", "ip-after"})
    void unknownLeaseReleaseOutcomeIsRetainedWithoutRepeatingTheRealCallerPhase(String fault) throws Exception {
        try (Harness h = new Harness("ec2-uncertain-" + fault, false, false)) {
            h.newEc2Owner();
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger effects = new AtomicInteger();
            Answer<Object> release = ignored -> {
                attempts.incrementAndGet();
                if (fault.endsWith("after")) {
                    effects.incrementAndGet();
                }
                throw new IllegalStateException("lease outcome unavailable");
            };
            if (fault.startsWith("ssh")) {
                doAnswer(release).when(h.ports).release(h.sshPort);
            } else {
                doAnswer(release).when(h.vpcNetworks).releasePrivateIp(REGION, h.subnet, h.privateIp);
            }
            h.terminateAndDrain();
            assertEquals(1, attempts.get());
            // Model a replacement lease after the possibly committed call. No observer may release it.
            int effectsBeforeRetry = effects.get();
            h.firewall.retireDnsOrigins();
            h.firewall.retireDnsOrigins();
            h.snapshot("uncertain-lease-retained");
            assertEquals(1, attempts.get());
            assertEquals(effectsBeforeRetry, effects.get());
            assertEquals("shutting-down", h.instance.getState().getName());
            assertEquals(0, h.instance.getTerminatedAt());
            assertEquals(1, h.callbacks.get(), "workload absence remains independent of bookkeeping");
            assertEquals(1, h.helperAttempts.get());
            assertThrows(IllegalStateException.class, h.firewall::checkReset);
        }
    }

    @Test
    void rejectedRealExecutorSubmissionCancelsOnlyTheUnarmedReservation() throws Exception {
        try (Harness h = new Harness("ec2-executor-rejected", false, false)) {
            h.newEc2Owner();
            h.ownerExecutor.shutdown();
            assertTrue(h.ownerExecutor.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> h.ec2.terminate(h.instance));
            h.firewall.checkReset();
            h.firewall.retireDnsOrigins();
            assertEquals(0, h.callbacks.get());
            assertEquals(0, h.helperAttempts.get());
            verify(h.ports, never()).release(h.sshPort);
        }
    }

    @Test
    void changedInstanceLeaseTupleRefusesTheCapturedRealCallerCleanup() throws Exception {
        try (Harness h = new Harness("ec2-owner-changed", false, false)) {
            h.newEc2Owner();
            CountDownLatch removing = new CountDownLatch(1);
            CountDownLatch removed = new CountDownLatch(1);
            RemoveContainerCmd removal = mock(RemoveContainerCmd.class, RETURNS_SELF);
            when(h.docker.removeContainerCmd(h.workload)).thenReturn(removal);
            doAnswer(ignored -> {
                removing.countDown();
                assertTrue(removed.await(5, TimeUnit.SECONDS));
                h.workloadGone = true;
                return null;
            }).when(removal).exec();
            try {
                h.ec2.terminate(h.instance, h.callbacks::incrementAndGet);
                assertTrue(removing.await(5, TimeUnit.SECONDS));
                synchronized (h.instance) {
                    h.instance.setPrivateIpAddress("10.0.1.99");
                }
                removed.countDown();
                h.ownerExecutor.shutdown();
                assertTrue(h.ownerExecutor.awaitTermination(5, TimeUnit.SECONDS));
                h.firewall.retireDnsOrigins();
                assertEquals("10.0.1.99", h.instance.getPrivateIpAddress());
                assertEquals("shutting-down", h.instance.getState().getName());
                verify(h.ports, never()).release(h.sshPort);
                verify(h.metadata, never()).unregisterInstance(h.instance);
                verify(h.vpcNetworks, never()).releasePrivateIp(REGION, h.subnet, h.privateIp);
                assertEquals(1, h.callbacks.get(), "workload removal alone permits the original callback");
                assertThrows(IllegalStateException.class, h.firewall::checkReset);
                h.snapshot("changed-owner-cleanup-refused");
            } finally {
                removed.countDown();
            }
        }
    }

    @Test
    void repeatedTerminateInstancesUsesTheCompletedRealManagerWithoutReopeningItsStoredInstance() throws Exception {
        try (Harness h = new Harness("ec2-service-repeated-complete", false, false)) {
            h.newEc2Owner();
            h.instance.setNetworkInterfaces(new ArrayList<>(h.instance.getNetworkInterfaces()));
            when(h.config.defaultAccountId()).thenReturn(ACCOUNT);
            when(h.config.services().ec2().mock()).thenReturn(false);
            AccountAwareStorageBackend<Instance> instances = AccountAwareStorageBackend.inMemory(ACCOUNT);
            instances.put(REGION + "::" + h.instance.getInstanceId(), h.instance);
            StorageFactory stores = new StorageFactory(null, null) {
                @Override
                @SuppressWarnings("unchecked")
                public <V> AccountAwareStorageBackend<V> create(String service, String file,
                                                                TypeReference<Map<String, V>> type) {
                    return file.equals("ec2-instances.json") ? (AccountAwareStorageBackend<V>) instances
                            : AccountAwareStorageBackend.inMemory(ACCOUNT);
                }
            };
            Ec2Service service = new Ec2Service(h.config, h.ec2, h.forwarding, mock(AmiImageResolver.class),
                    mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), stores);
            service.terminateInstances(REGION, List.of(h.instance.getInstanceId()));
            h.ownerExecutor.shutdown();
            assertTrue(h.ownerExecutor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals("terminated", h.instance.getState().getName());
            long completedAt = h.instance.getTerminatedAt();
            service.terminateInstances(REGION, List.of(h.instance.getInstanceId()));
            service.terminateInstances(REGION, List.of(h.instance.getInstanceId()));
            h.firewall.retireDnsOrigins();
            assertSame(h.instance, instances.get(REGION + "::" + h.instance.getInstanceId()).orElseThrow());
            assertEquals("terminated", h.instance.getState().getName());
            assertEquals(completedAt, h.instance.getTerminatedAt());
            assertEquals(1, h.events.stream().filter("workload-removed"::equals).count());
            assertEquals(1, h.helperAttempts.get());
            verify(h.ports, times(1)).release(h.sshPort);
            verify(h.metadata, times(1)).unregisterInstance(h.instance);
            verify(h.vpcNetworks, times(1)).releasePrivateIp(REGION, h.subnet, h.privateIp);
            h.snapshot("actual-service-repeated-termination-complete");
        }
    }

    private static final class Harness implements AutoCloseable {
        private final String test;
        private final String id = UUID.randomUUID().toString();
        private final String eni = "eni-" + id;
        private final String helper = "helper-" + id;
        private final String workload = "workload-" + id;
        private final String vpc = "vpc-" + id;
        private final String subnet = "subnet-" + id;
        private final String bridgeIp = "172.18.0.2";
        private final String imdsIp = "172.19.0.2";
        private final String privateIp = "10.0.1.10";
        private final int sshPort = 22117;
        private final boolean failFirstHelper;
        private final boolean failWorkload;
        private final IllegalStateException helperFailure = new IllegalStateException("controlled helper removal");
        private final AtomicInteger helperAttempts = new AtomicInteger();
        private final AtomicInteger callbacks = new AtomicInteger();
        private final CountDownLatch callback = new CountDownLatch(1);
        private final CountDownLatch firstHelperFailure = new CountDownLatch(1);
        private final List<String> events = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> snapshots = new ArrayList<>();
        private final DockerClient docker = mock(DockerClient.class);
        private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        private final PortAllocator ports = mock(PortAllocator.class);
        private final Ec2MetadataServer metadata = mock(Ec2MetadataServer.class);
        private final Ec2PortForwardManager forwarding = mock(Ec2PortForwardManager.class);
        private final VpcNetworkManager vpcNetworks = mock(VpcNetworkManager.class);
        private final Ec2Service ec2Service = mock(Ec2Service.class);
        private final SecurityGroupFirewallManager firewall;
        private volatile boolean workloadGone;
        private volatile boolean helperGone;
        private boolean ownerExecutorTerminated;
        private Ec2ContainerManager ec2;
        private EcsContainerManager ecs;
        private ExecutorService ownerExecutor;
        private Instance instance;
        private EcsTask task;

        private Harness(String test, boolean failFirstHelper, boolean failWorkload) {
            this.test = test;
            this.failFirstHelper = failFirstHelper;
            this.failWorkload = failWorkload;
            when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
            ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
            when(docker.listContainersCmd()).thenReturn(listing);
            when(listing.exec()).thenReturn(List.of());
            RemoveContainerCmd removal = mock(RemoveContainerCmd.class, RETURNS_SELF);
            when(docker.removeContainerCmd(workload)).thenReturn(removal);
            doAnswer(ignored -> {
                if (this.failWorkload) {
                    events.add("workload-removal-failed");
                    throw new IllegalStateException("controlled workload removal");
                }
                workloadGone = true;
                events.add("workload-removed");
                return null;
            }).when(removal).exec();
            firewall = spy(new SecurityGroupFirewallManager(docker, mock(ContainerBuilder.class),
                    lifecycle, config, mock(EmbeddedDnsServer.class)));
            doNothing().when(firewall).reconcileAll();
            doAnswer(ignored -> {
                int attempt = helperAttempts.incrementAndGet();
                events.add("helper-attempt-" + attempt + ":" + helper);
                if (this.failFirstHelper && attempt == 1) {
                    events.add("helper-removal-failed");
                    firstHelperFailure.countDown();
                    throw helperFailure;
                }
                helperGone = true;
                events.add("helper-removed");
                return null;
            }).when(lifecycle).removeIfExistsStrict(helper);
            firewall.register(new SecurityGroupNftCompiler.Endpoint(ACCOUNT, REGION, vpc, eni,
                    privateIp, bridgeIp, Set.of(), List.of()), helper, Map.of());
            assertTrue(firewall.vpcForClient(bridgeIp).isPresent(), "real registration must be admitted");
        }

        private void newEc2Owner() throws Exception {
            instance = new Instance();
            instance.setInstanceId("i-" + id);
            instance.setOwnerId(ACCOUNT);
            instance.setRegion(REGION);
            instance.setState(InstanceState.running());
            instance.setDockerContainerId(workload);
            instance.setContainerBridgeIp(bridgeIp);
            instance.setImdsSourceIp(imdsIp);
            instance.setSshHostPort(sshPort);
            instance.setVpcId(vpc);
            instance.setSubnetId(subnet);
            instance.setPrivateIpAddress(privateIp);
            InstanceNetworkInterface network = new InstanceNetworkInterface();
            network.setNetworkInterfaceId(eni);
            instance.setNetworkInterfaces(List.of(network));
            doAnswer(ignored -> {
                events.add("ports-unpublished");
                return null;
            }).when(forwarding).unpublishAll(instance);
            doAnswer(ignored -> {
                events.add("ssh-released");
                return null;
            }).when(ports).release(sshPort);
            doAnswer(ignored -> {
                events.add("bridge-metadata-unregistered");
                return null;
            }).when(metadata).unregisterContainer(bridgeIp, instance);
            doAnswer(ignored -> {
                events.add("imds-metadata-unregistered");
                return null;
            }).when(metadata).unregisterContainer(imdsIp, instance);
            doAnswer(ignored -> {
                events.add("instance-metadata-unregistered");
                return null;
            }).when(metadata).unregisterInstance(instance);
            doAnswer(ignored -> {
                events.add("private-ip-released");
                return null;
            }).when(vpcNetworks).releasePrivateIp(REGION, subnet, privateIp);
            ec2 = new Ec2ContainerManager(mock(ContainerBuilder.class), lifecycle,
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class), mock(DockerHostResolver.class),
                    docker, ports, config, metadata, forwarding, new RegionResolver(REGION, ACCOUNT),
                    mock(ContainerNetworkReachability.class), vpcNetworks, mock(ContainerReachableEndpoint.class),
                    firewall);
            // Observe the actual constructed executor. Do not replace it or inspect its private Future.
            Field executorField = Ec2ContainerManager.class.getDeclaredField("executor");
            executorField.setAccessible(true);
            ownerExecutor = (ExecutorService) executorField.get(ec2);
            snapshot("before-terminate");
        }

        private void terminateAndDrain() throws Exception {
            ec2.terminate(instance, () -> {
                callbacks.incrementAndGet();
                events.add("workload-gone-image-callback");
                callback.countDown();
            });
            ownerExecutor.shutdown();
            ownerExecutorTerminated = ownerExecutor.awaitTermination(5, TimeUnit.SECONDS);
            assertTrue(ownerExecutorTerminated, "the fixture's actual owner executor must finish without interruption");
            events.add("owner-executor-terminated");
        }

        private void newEcsOwner() throws Exception {
            task = new EcsTask();
            task.setTaskArn("arn:aws:ecs:" + REGION + ":" + ACCOUNT + ":task/cluster/" + id);
            task.setLastStatus("STOPPED");
            task.setDesiredStatus("STOPPED");
            task.setNetworkInterfaceId(eni);
            task.setAttachmentStatus("ATTACHED");
            task.setPrivateIpAddress(privateIp);
            task.setContainers(List.of());
            doAnswer(ignored -> {
                events.add("eni-hold-released");
                return null;
            }).when(ec2Service).releaseNetworkInterfaceFromService(REGION, eni);
            doAnswer(ignored -> {
                events.add("eni-deleted");
                return null;
            }).when(ec2Service).deleteNetworkInterface(REGION, eni);
            when(ec2Service.captureNetworkInterfaceCleanupForService(task, REGION, eni)).thenReturn(() -> {
                ec2Service.releaseNetworkInterfaceFromService(REGION, eni);
                ec2Service.deleteNetworkInterface(REGION, eni);
            });
            ecs = new EcsContainerManager(mock(ContainerBuilder.class), lifecycle,
                    mock(ContainerLogStreamer.class), mock(ContainerDetector.class), config,
                    new RegionResolver(REGION, ACCOUNT), mock(LaunchedContainerAwsEnv.class),
                    mock(SsmService.class), mock(SecretsManagerService.class), mock(S3Service.class),
                    mock(EcrRegistryManager.class), mock(HostVolumePolicy.class), ec2Service, firewall,
                    mock(EcsTaskRoleCredentials.class), mock(EcsCredentialsProxy.class), new EcsTaskLinkLocalAddresses());
            // Explicit mocked-peripheral precondition for direct releaseTaskNetwork, not a stopTask execution.
            docker.removeContainerCmd(workload).withForce(true).exec();
            snapshot("attached-task-after-mocked-workload-removal");
        }

        private void assertEc2Cleanup() {
            assertAll("Confirmed-gone instance completes its exact owner cleanup",
                    () -> assertEquals("terminated", instance.getState().getName()),
                    () -> assertTrue(instance.getTerminatedAt() > 0),
                    () -> verify(ports, times(1)).release(sshPort),
                    () -> verify(metadata, times(1)).unregisterContainer(bridgeIp, instance),
                    () -> verify(metadata, times(1)).unregisterContainer(imdsIp, instance),
                    () -> verify(metadata, times(1)).unregisterInstance(instance),
                    () -> verify(vpcNetworks, times(1)).releasePrivateIp(REGION, subnet, privateIp),
                    () -> verify(forwarding, times(1)).unpublishAll(instance));
        }

        private void assertEcsCleanup() {
            assertAll("A released attachment has a completed exact ENI cleanup",
                    () -> assertEquals("DELETED", task.getAttachmentStatus()),
                    () -> verify(ec2Service, times(1)).releaseNetworkInterfaceFromService(REGION, eni),
                    () -> verify(ec2Service, times(1)).deleteNetworkInterface(REGION, eni));
        }

        private void snapshot(String phase) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("phase", phase);
            row.put("events", List.copyOf(events));
            row.put("workloadGone", workloadGone);
            row.put("helperGone", helperGone);
            row.put("helperAttempts", helperAttempts.get());
            row.put("callbacks", callbacks.get());
            row.put("ownerExecutorTerminated", ownerExecutorTerminated);
            row.put("dnsOrigin", firewall.vpcForClient(bridgeIp).map(Object::toString).orElse(null));
            row.put("instance", instance == null ? null : MAPPER.valueToTree(instance));
            row.put("task", task == null ? null : MAPPER.valueToTree(task));
            snapshots.add(row);
        }

        @Override
        public void close() throws Exception {
            snapshot("finally-before-fixture-shutdown");
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("test", test);
            receipt.put("id", id);
            receipt.put("account", ACCOUNT);
            receipt.put("region", REGION);
            receipt.put("eni", eni);
            receipt.put("helper", helper);
            receipt.put("workload", workload);
            receipt.put("controlledFirstHelperFailure", failFirstHelper);
            receipt.put("controlledWorkloadFailure", failWorkload);
            receipt.put("snapshots", snapshots);
            receipt.put("scope", "Real EC2/ECS container-manager caller and real firewall state machine; "
                    + "mock Docker/peripherals, successful nft reconciliation assumed. ECS direct release only; "
                    + "no EcsService shutdown execution. Callback is workload absence, not cleanup completion.");
            try {
                Path directory = Path.of("target", "caller-cleanup-receipts");
                Files.createDirectories(directory);
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(test + "-" + id + ".json")
                        .toFile(), receipt);
            } finally {
                if (ec2 != null) {
                    ec2.stop();
                }
                firewall.stopDnsOriginObserver();
            }
        }
    }
}
