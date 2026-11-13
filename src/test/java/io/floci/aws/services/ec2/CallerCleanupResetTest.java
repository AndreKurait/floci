package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.FlociCertificateAuthority;
import io.floci.aws.core.common.ContainerTeardown;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.ServiceRegistry;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.lifecycle.EmulatorInfoController;
import io.floci.aws.lifecycle.InitLifecycleState;
import io.floci.aws.services.dynamodb.backend.DynamoDbBackendSelector;
import jakarta.enterprise.inject.Instance;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockMakers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** Real reset controller and continuation locks; Docker, storage wipe and unrelated services are mocks. */
@Timeout(20)
class CallerCleanupResetTest {
    @Test
    void unarmedOwnerRefusesTheActualResetBeforeStorageAndCancellationReopensAdmission() {
        try (Harness h = new Harness()) {
            SecurityGroupFirewallManager.OwnedCleanup cleanup = h.capture(() -> { }, true);
            EmulatorInfoController controller = h.controller(List.of(h.firewall));
            assertThrows(IllegalStateException.class, controller::reset);
            verify(h.storage, never()).clearAll();
            cleanup.cancelBeforeStart();
            assertEquals(200, controller.reset().getStatus());
            AtomicInteger after = new AtomicInteger();
            h.capture(after::incrementAndGet, true).run();
            assertEquals(1, after.get());
        }
    }

    @Test
    void unknownSideEffectRetainsResetRefusalAndNeverRepeatsThePrimitive() {
        try (Harness h = new Harness()) {
            AtomicInteger attempts = new AtomicInteger();
            SecurityGroupFirewallManager.OwnedCleanup cleanup = h.capture(() -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("primitive reply unavailable");
            }, false);
            assertThrows(IllegalStateException.class, cleanup::run);
            EmulatorInfoController controller = h.controller(List.of(h.firewall));
            assertThrows(IllegalStateException.class, controller::reset);
            h.firewall.retireDnsOrigins();
            assertThrows(IllegalStateException.class, cleanup::run);
            assertEquals(1, attempts.get());
            verify(h.storage, never()).clearAll();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"earlier-hook", "later-hook", "storage"})
    void actualResetFailureResumesAdmissionEvenWhenTheFirewallBeforeHookDidNotRun(String failing) {
        try (Harness h = new Harness()) {
            Resettable fault = mock(Resettable.class);
            if (failing.equals("storage")) {
                doThrow(new IllegalStateException("wipe unavailable")).when(h.storage).clearAll();
            } else {
                doThrow(new IllegalStateException("hook unavailable")).when(fault).beforeReset();
            }
            List<Resettable> services = failing.equals("earlier-hook")
                    ? List.of(fault, h.firewall) : List.of(h.firewall, fault);
            assertThrows(IllegalStateException.class, h.controller(services)::reset);
            AtomicInteger after = new AtomicInteger();
            h.capture(after::incrementAndGet, true).run();
            assertEquals(1, after.get());
            h.firewall.checkReset();
        }
    }

    @Test
    void captureAfterInitialResetCheckIsRefusedBeforeAnyWipeAndFinallyResumesTheManager() {
        try (Harness h = new Harness()) {
            AtomicReference<SecurityGroupFirewallManager.OwnedCleanup> late = new AtomicReference<>();
            Resettable racing = mock(Resettable.class);
            doAnswer(ignored -> {
                late.set(h.capture(() -> { }, true));
                return null;
            }).when(racing).checkReset();
            assertThrows(IllegalStateException.class, h.controller(List.of(h.firewall, racing))::reset);
            verify(h.storage, never()).clearAll();
            assertTrue(late.get() != null);
            late.get().run();
            h.firewall.checkReset();
            AtomicInteger after = new AtomicInteger();
            h.capture(after::incrementAndGet, true).run();
            assertEquals(1, after.get());
        }
    }

    @Test
    void actualResetDrainsAContinuationAdmittedBetweenCheckAndBeforeHook() throws Exception {
        try (Harness h = new Harness()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            CountDownLatch beforeHook = new CountDownLatch(1);
            AtomicInteger finished = new AtomicInteger();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            AtomicReference<Future<?>> owner = new AtomicReference<>();
            Resettable racing = mock(Resettable.class);
            doAnswer(ignored -> {
                SecurityGroupFirewallManager.OwnedCleanup cleanup = h.capture(() -> {
                    entered.countDown();
                    await(finish);
                    finished.incrementAndGet();
                }, true);
                owner.set(executor.submit(cleanup));
                await(entered);
                return null;
            }).when(racing).checkReset();
            doAnswer(invocation -> {
                beforeHook.countDown();
                invocation.callRealMethod();
                return null;
            }).when(h.firewall).beforeReset();
            try {
                Future<?> reset = executor.submit(() -> h.controller(List.of(h.firewall, racing)).reset());
                assertTrue(beforeHook.await(5, TimeUnit.SECONDS));
                assertFalse(reset.isDone());
                verify(h.storage, never()).clearAll();
                Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> {
                    try {
                        h.capture(() -> { }, true).cancelBeforeStart();
                        return false;
                    } catch (IllegalStateException paused) {
                        return true;
                    }
                });
                finish.countDown();
                owner.get().get(5, TimeUnit.SECONDS);
                reset.get(5, TimeUnit.SECONDS);
                assertEquals(1, finished.get());
                verify(h.storage).clearAll();
                AtomicInteger after = new AtomicInteger();
                h.capture(after::incrementAndGet, true).run();
                assertEquals(1, after.get());
            } finally {
                finish.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void independentReadyOwnerCompletesWhileAnotherMetadataPhaseStillFails() {
        try (Harness h = new Harness()) {
            AtomicInteger failures = new AtomicInteger();
            AtomicInteger completed = new AtomicInteger();
            SecurityGroupFirewallManager.OwnedCleanup failing = h.capture(() -> {
                failures.incrementAndGet();
                throw new IllegalStateException("metadata temporarily unavailable");
            }, true);
            assertThrows(IllegalStateException.class, failing::run);
            h.capture(completed::incrementAndGet, true).run();
            h.firewall.retireDnsOrigins();
            assertEquals(1, completed.get());
            assertEquals(2, failures.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private static final class Harness implements AutoCloseable {
        private final StorageFactory storage = mock(StorageFactory.class);
        private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        private final SecurityGroupFirewallManager firewall;

        private Harness() {
            DockerClient docker = mock(DockerClient.class);
            ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
            when(docker.listContainersCmd()).thenReturn(listing);
            when(listing.exec()).thenReturn(List.of());
            firewall = spy(new SecurityGroupFirewallManager(docker, mock(ContainerBuilder.class),
                    mock(ContainerLifecycleManager.class), config, mock(EmbeddedDnsServer.class)));
            doNothing().when(firewall).reconcileAll();
        }

        private SecurityGroupFirewallManager.OwnedCleanup capture(Runnable action, boolean retry) {
            return firewall.captureOwnerCleanup(new Object(), null, () -> true,
                    () -> List.of(new SecurityGroupFirewallManager.CleanupStep(action, retry)));
        }

        @SuppressWarnings("unchecked")
        private EmulatorInfoController controller(List<Resettable> values) {
            Instance<Resettable> services = mock(Instance.class);
            when(services.iterator()).thenAnswer(ignored -> values.iterator());
            Instance<ContainerTeardown> teardowns = mock(Instance.class);
            when(teardowns.iterator()).thenAnswer(ignored -> List.<ContainerTeardown>of().iterator());
            return new EmulatorInfoController(mock(ServiceRegistry.class), mock(InitLifecycleState.class), storage,
                    services, teardowns, mock(FlociCertificateAuthority.class, withSettings().mockMaker(MockMakers.INLINE)), config,
                    mock(DynamoDbBackendSelector.class));
        }

        @Override
        public void close() {
            firewall.stopDnsOriginObserver();
        }
    }
}
