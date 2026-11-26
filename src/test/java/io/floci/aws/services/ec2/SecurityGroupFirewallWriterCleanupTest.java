package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.ec2.net.VpcRoutingManager;
import io.floci.aws.testing.ThreadStates;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class SecurityGroupFirewallWriterCleanupTest {
    @TempDir
    Path persistent;

    private DockerClient docker;
    private ContainerLifecycleManager lifecycle;
    private PolicyWriterJournal journal;
    private VpcRoutingManager routing;
    private SecurityGroupFirewallManager manager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.storage().persistentPath()).thenReturn(persistent.toString());
        docker = SecurityGroupDnsFixture.policyDocker(mock(DockerClient.class, RETURNS_DEEP_STUBS));
        lifecycle = mock(ContainerLifecycleManager.class);
        journal = new PolicyWriterJournal(config);
        manager = spy(new SecurityGroupFirewallManager(docker, mock(ContainerBuilder.class),
                lifecycle, config, mock(EmbeddedDnsServer.class), journal));
        doNothing().when(manager).reconcileAll();
        doNothing().when(manager).admit(anyString(), anyBoolean());
        routing = mock(VpcRoutingManager.class);
        manager.vpcRouting = mock(Instance.class);
        when(manager.vpcRouting.get()).thenReturn(routing);
    }

    @Test
    void journalCleanupWaitsForAnObserverPolicyWriter() throws Exception {
        register("helper-old", "172.18.0.2");
        CountDownLatch removing = new CountDownLatch(1);
        CountDownLatch finishRemoval = new CountDownLatch(1);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch finishWriting = new CountDownLatch(1);
        doAnswer(ignored -> {
            removing.countDown();
            assertTrue(finishRemoval.await(10, TimeUnit.SECONDS));
            return null;
        }).when(lifecycle).removeIfExistsStrict("helper-old");
        CopyArchiveToContainerCmd copy = docker.copyArchiveToContainerCmd("helper-old");
        doAnswer(ignored -> {
            writing.countDown();
            assertTrue(finishWriting.await(10, TimeUnit.SECONDS));
            return null;
        }).when(copy).exec();
        AtomicReference<Throwable> removalFailure = new AtomicReference<>();
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread removal = runAndCapture(() -> manager.removeNamespaceStrict("eni-a"), removalFailure);
        Thread writer = null;
        try {
            assertTrue(removing.await(5, TimeUnit.SECONDS));
            writer = runAndCapture(manager::retireDnsOrigins, writerFailure);
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            finishRemoval.countDown();
            ThreadStates.awaitState(removal, Set.of(Thread.State.BLOCKED), Duration.ofSeconds(5),
                    "namespace cleanup waiting for the observer policy writer");
        } finally {
            finishRemoval.countDown();
            finishWriting.countDown();
            removal.join(5000);
            if (writer != null) {
                writer.join(5000);
            }
        }

        assertFalse(removal.isAlive());
        assertNotNull(writer);
        assertFalse(writer.isAlive());
        assertNull(removalFailure.get());
        assertNull(writerFailure.get());
        assertTrue(journal.helpers().isEmpty());
    }

    private void register(String helper, String address) {
        journal.initialize(helper);
        manager.register(new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", "vpc-a",
                "eni-a", "10.0.1.10", address, Set.of(), List.of()), helper, Map.of());
    }

    private static Thread runAndCapture(Runnable action, AtomicReference<Throwable> failure) {
        return Thread.ofPlatform().start(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.set(error);
            }
        });
    }
}
