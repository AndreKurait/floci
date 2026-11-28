package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(15)
class SecurityGroupDnsRetirementSnapshotTest {
    @Test
    void ownerCleanupCompletingBeforeObserverRetirementDoesNotRequarantineRemovedHelper() throws Exception {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        SecurityGroupFirewallManager manager = SecurityGroupDnsFixture.manager(docker,
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), lifecycle, null);
        manager.register(new SecurityGroupNftCompiler.Endpoint(
                "111111111111", "us-east-1", "vpc-observer", "eni-observer",
                "10.0.1.10", "172.18.0.3", Set.of("sg"), List.of(), List.of(),
                false, Set.of()), "helper-observer", Map.of());
        AtomicBoolean helperRemoved = new AtomicBoolean();
        AtomicBoolean copiedAfterRemoval = new AtomicBoolean();
        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd("helper-observer")).thenAnswer(invocation -> {
            if (helperRemoved.get()) {
                copiedAfterRemoval.set(true);
            }
            return copy;
        });
        doAnswer(invocation -> {
            helperRemoved.set(true);
            return null;
        }).when(lifecycle).removeIfExistsStrict("helper-observer");
        CompletableFuture<Void> observed = new CompletableFuture<>();
        Thread observer = Thread.ofPlatform().unstarted(() -> {
            try {
                manager.retireDnsOrigins();
                observed.complete(null);
            } catch (Throwable failure) {
                observed.completeExceptionally(failure);
            }
        });
        try {
            synchronized (manager) {
                observer.start();
                await().atMost(Duration.ofSeconds(5)).until(() ->
                        observer.getState() == Thread.State.BLOCKED
                                && Arrays.stream(observer.getStackTrace()).anyMatch(frame ->
                                frame.getClassName().equals(SecurityGroupFirewallManager.class.getName())
                                        && frame.getMethodName().equals("retireEndpoints")));
                manager.unregister("eni-observer");
                assertTrue(helperRemoved.get(), "The owner must finish removal before the observer resumes");
            }
            observed.get(5, TimeUnit.SECONDS);
            assertFalse(copiedAfterRemoval.get(), "A stale observation must not install policy into a removed helper");
            verify(lifecycle).removeIfExistsStrict("helper-observer");
            assertTrue(manager.vpcForClient("172.18.0.3").isEmpty());
        } finally {
            observer.join(5000);
        }
    }
}
