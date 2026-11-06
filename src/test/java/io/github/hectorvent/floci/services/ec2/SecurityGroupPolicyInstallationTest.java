package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.model.Frame;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class SecurityGroupPolicyInstallationTest {
    @Test
    void installationsOverlapWithinTheBoundAndAdmissionWaitsForAllNamespaces() throws Exception {
        CountDownLatch firstBatch = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Set<String> finished = ConcurrentHashMap.newKeySet();
        SecurityGroupFirewallManager manager = manager(10, (helper, quarantine) -> {
            assertFalse(quarantine);
            started.incrementAndGet();
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            firstBatch.countDown();
            try {
                await(release);
                finished.add(helper);
            } finally {
                active.decrementAndGet();
            }
        });
        try (ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> result = caller.submit(manager::reconcileAll);
            try {
                assertTrue(firstBatch.await(20, TimeUnit.SECONDS), "independent namespaces must overlap");
                assertEquals(8, started.get(), "remaining installations must stay queued");
                assertFalse(result.isDone(), "admission must wait for the entire policy generation");
            } finally {
                release.countDown();
            }
            result.get(20, TimeUnit.SECONDS);
        }
        assertEquals(10, finished.size());
        assertEquals(8, peak.get());
    }

    @Test
    void endpointsSharingANamespaceKeepTheirInstallationsSerial() throws Exception {
        CountDownLatch sharedEntered = new CountDownLatch(1);
        CountDownLatch otherEntered = new CountDownLatch(1);
        AtomicInteger activeShared = new AtomicInteger();
        AtomicInteger sharedCalls = new AtomicInteger();
        SecurityGroupFirewallManager manager = manager(3, (helper, quarantine) -> {
            assertFalse(quarantine);
            if (helper.equals("helper-shared")) {
                assertEquals(1, activeShared.incrementAndGet(), "one namespace has one policy writer");
                try {
                    sharedCalls.incrementAndGet();
                    sharedEntered.countDown();
                    await(otherEntered);
                } finally {
                    activeShared.decrementAndGet();
                }
            } else {
                await(sharedEntered);
                otherEntered.countDown();
            }
        }, index -> index < 2 ? "helper-shared" : "helper-other");
        try (ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
            caller.submit(manager::reconcileAll).get(20, TimeUnit.SECONDS);
        }
        assertEquals(2, sharedCalls.get());
    }

    @Test
    void failedGenerationWaitsForDelayedInstallerBeforeQuarantiningEveryNamespace() throws Exception {
        CountDownLatch delayedStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Set<String> quarantined = ConcurrentHashMap.newKeySet();
        SecurityGroupFirewallManager manager = manager(2, (helper, quarantine) -> {
            if (quarantine) {
                assertEquals(0, release.getCount(), "quarantine cannot race an outstanding installer");
                quarantined.add(helper);
            } else if (helper.equals("helper-0")) {
                throw new IllegalStateException("deliberate rule installation failure");
            } else {
                delayedStarted.countDown();
                await(release);
                assertTrue(quarantined.isEmpty(), "a late success must not overwrite quarantine");
            }
        });
        try (ExecutorService caller = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> result = caller.submit(manager::reconcileAll);
            try {
                assertTrue(delayedStarted.await(20, TimeUnit.SECONDS));
                assertFalse(result.isDone());
                assertTrue(quarantined.isEmpty());
            } finally {
                release.countDown();
            }
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> result.get(20, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
        }
        assertEquals(Set.of("helper-0", "helper-1"), quarantined);
    }

    private static SecurityGroupFirewallManager manager(int count, BiConsumer<String, Boolean> installation) {
        return manager(count, installation, index -> "helper-" + index);
    }

    private static SecurityGroupFirewallManager manager(int count, BiConsumer<String, Boolean> installation,
                                                       IntFunction<String> namespace) {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
        when(dns.getServerIp()).thenReturn(Optional.empty());
        SecurityGroupFirewallManager manager = spy(new SecurityGroupFirewallManager(docker,
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class),
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), dns));
        doNothing().when(manager).reconcileAll();
        Map<String, Boolean> scripts = new ConcurrentHashMap<>();
        for (int index = 0; index < count; index++) {
            String helper = namespace.apply(index);
            when(docker.inspectContainerCmd(helper).exec().getState().getRunning()).thenReturn(true);
            CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
            when(docker.copyArchiveToContainerCmd(helper)).thenReturn(copy);
            when(copy.withTarInputStream(any())).thenAnswer(call -> {
                InputStream archive = call.getArgument(0);
                scripts.put(helper, !new String(archive.readAllBytes(), StandardCharsets.UTF_8).contains("add rule"));
                return copy;
            });
            ExecCreateCmd create = mock(ExecCreateCmd.class, RETURNS_SELF);
            ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
            when(docker.execCreateCmd(helper)).thenReturn(create);
            when(create.exec()).thenReturn(created);
            when(created.getId()).thenReturn(helper);
            ExecStartCmd start = mock(ExecStartCmd.class, RETURNS_SELF);
            when(docker.execStartCmd(helper)).thenReturn(start);
            when(start.exec(any())).thenAnswer(call -> {
                installation.accept(helper, scripts.get(helper));
                ResultCallback.Adapter<Frame> callback = call.getArgument(0);
                callback.onComplete();
                return callback;
            });
            when(docker.inspectExecCmd(helper).exec().getExitCodeLong()).thenReturn(0L);
            SecurityGroup group = new SecurityGroup();
            group.setGroupId("sg-test");
            manager.register(new SecurityGroupNftCompiler.Endpoint("111111111111", "us-east-1", "vpc-test",
                    "eni-" + index, "10.0.0." + (index + 2), "172.18.0." + (index + 2),
                    Set.of("sg-test"), List.of(group)), helper, Map.of());
        }
        doCallRealMethod().when(manager).reconcileAll();
        return manager;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test installer was not released");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }
}
