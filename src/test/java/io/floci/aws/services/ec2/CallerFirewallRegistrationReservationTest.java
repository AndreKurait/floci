package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.ec2.model.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real registration and owner continuation, mocked Docker/nft peripherals. */
@Timeout(15)
class CallerFirewallRegistrationReservationTest {
    @Test
    void liveCapturedHelperCannotBeReregisteredOrSatisfyStrictRemoval() {
        try (Harness h = new Harness()) {
            AtomicInteger caller = new AtomicInteger();
            SecurityGroupFirewallManager.OwnedCleanup cleanup = h.firewall.captureOwnerCleanup(new Instance(), h.eni, () -> true,
                    () -> List.of(new SecurityGroupFirewallManager.CleanupStep(caller::incrementAndGet, true)));
            assertThrows(IllegalStateException.class, () -> h.firewall.register(h.endpoint, h.helper, Map.of()));
            assertThrows(IllegalStateException.class, () -> h.firewall.register(
                    h.endpoint("eni-other", "172.19.0.9"), h.helper, Map.of()));
            assertTrue(h.firewall.vpcForClient("172.19.0.2").isPresent());
            h.failRemoval.set(true);
            assertSame(h.removalFailure, assertThrows(IllegalStateException.class, cleanup::run));
            assertFalse(h.helperGone.get());
            assertEquals(0, caller.get());
            assertEquals(1, h.removals.get());
            h.failRemoval.set(false);
            h.firewall.retireDnsOrigins();
            assertTrue(h.helperGone.get());
            assertEquals(2, h.removals.get());
            assertEquals(1, caller.get());
        }
    }

    @Test
    void replacementCannotEnterAfterGenerationCheckBeforeCallerCleanup() throws Exception {
        try (Harness h = new Harness()) {
            CountDownLatch identityChecked = new CountDownLatch(1);
            CountDownLatch continueCleanup = new CountDownLatch(1);
            AtomicInteger caller = new AtomicInteger();
            SecurityGroupFirewallManager.OwnedCleanup cleanup = h.firewall.captureOwnerCleanup(new Instance(), h.eni, () -> {
                identityChecked.countDown();
                await(continueCleanup);
                return true;
            }, () -> List.of(new SecurityGroupFirewallManager.CleanupStep(() -> {
                assertTrue(h.helperGone.get());
                caller.incrementAndGet();
            }, true)));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> attempt = executor.submit(cleanup);
                assertTrue(identityChecked.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> h.firewall.register(
                        h.endpoint(h.eni, "172.19.0.3"), "helper-replacement", Map.of()));
                assertTrue(h.firewall.vpcForClient("172.19.0.2").isPresent());
                assertFalse(h.firewall.vpcForClient("172.19.0.3").isPresent());
                assertEquals(0, caller.get());
                continueCleanup.countDown();
                attempt.get(5, TimeUnit.SECONDS);
                assertEquals(1, caller.get());
                assertEquals(1, h.removals.get());
                h.firewall.register(h.endpoint(h.eni, "172.19.0.3"), "helper-replacement", Map.of());
                assertTrue(h.firewall.vpcForClient("172.19.0.3").isPresent());
                verify(h.lifecycle, never()).removeIfExistsStrict("helper-replacement");
            } finally {
                continueCleanup.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void reservationLastsThroughCallerPhaseAfterHelperIsGone() throws Exception {
        try (Harness h = new Harness()) {
            CountDownLatch callerEntered = new CountDownLatch(1);
            CountDownLatch finishCaller = new CountDownLatch(1);
            AtomicInteger caller = new AtomicInteger();
            SecurityGroupFirewallManager.OwnedCleanup cleanup = h.firewall.captureOwnerCleanup(new Instance(), h.eni, () -> true,
                    () -> List.of(new SecurityGroupFirewallManager.CleanupStep(() -> {
                        assertTrue(h.helperGone.get());
                        callerEntered.countDown();
                        await(finishCaller);
                        caller.incrementAndGet();
                    }, true)));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> attempt = executor.submit(cleanup);
                assertTrue(callerEntered.await(5, TimeUnit.SECONDS));
                assertTrue(h.helperGone.get());
                assertThrows(IllegalStateException.class, () -> h.firewall.register(
                        h.endpoint(h.eni, "172.19.0.3"), "helper-replacement", Map.of()));
                assertThrows(IllegalStateException.class, h.firewall::checkReset);
                finishCaller.countDown();
                attempt.get(5, TimeUnit.SECONDS);
                assertEquals(1, caller.get());
                h.firewall.checkReset();
                h.firewall.register(h.endpoint(h.eni, "172.19.0.3"), "helper-replacement", Map.of());
                assertTrue(h.firewall.vpcForClient("172.19.0.3").isPresent());
            } finally {
                finishCaller.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void anotherOwnerCannotCaptureTheSamePendingEni() {
        try (Harness h = new Harness()) {
            AtomicInteger caller = new AtomicInteger();
            Instance owner = new Instance();
            SecurityGroupFirewallManager.OwnedCleanup cleanup = h.firewall.captureOwnerCleanup(owner, h.eni, () -> true,
                    () -> List.of(new SecurityGroupFirewallManager.CleanupStep(caller::incrementAndGet, true)));
            assertSame(cleanup, h.firewall.captureOwnerCleanup(owner, h.eni, () -> false,
                    () -> { throw new AssertionError("A repeat cannot replace the captured continuation"); }));
            assertThrows(IllegalStateException.class, () -> h.firewall.captureOwnerCleanup(
                    new Instance(), h.eni, () -> true, List::of));
            cleanup.run();
            cleanup.run();
            assertEquals(1, caller.get());
            assertEquals(1, h.removals.get());
        }
    }

    @Test
    void completedOldOwnerCannotAffectAReplacementButANewOwnerCanCompleteItsOwnCleanup() {
        try (Harness h = new Harness()) {
            AtomicInteger oldCalls = new AtomicInteger();
            Instance oldOwner = new Instance();
            h.firewall.captureOwnerCleanup(oldOwner, h.eni, () -> true,
                    () -> List.of(new SecurityGroupFirewallManager.CleanupStep(oldCalls::incrementAndGet, true))).run();
            h.firewall.register(h.endpoint(h.eni, "172.19.0.3"), "helper-replacement", Map.of());
            h.firewall.captureOwnerCleanup(oldOwner, h.eni, () -> false,
                    () -> { throw new AssertionError("Completed original owner cannot recapture new work"); }).run();
            assertTrue(h.firewall.vpcForClient("172.19.0.3").isPresent());
            verify(h.lifecycle, never()).removeIfExistsStrict("helper-replacement");
            AtomicInteger newCalls = new AtomicInteger();
            h.firewall.captureOwnerCleanup(new Instance(), h.eni, () -> true,
                    () -> List.of(new SecurityGroupFirewallManager.CleanupStep(newCalls::incrementAndGet, true))).run();
            assertEquals(1, oldCalls.get());
            assertEquals(1, newCalls.get());
            verify(h.lifecycle).removeIfExistsStrict("helper-replacement");
            assertFalse(h.firewall.vpcForClient("172.19.0.3").isPresent());
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
        private final String eni = "eni-owned";
        private final String helper = "helper-owned";
        private final AtomicBoolean helperGone = new AtomicBoolean();
        private final AtomicBoolean failRemoval = new AtomicBoolean();
        private final AtomicInteger removals = new AtomicInteger();
        private final IllegalStateException removalFailure = new IllegalStateException("strict removal failed");
        private final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        private final SecurityGroupFirewallManager firewall;
        private final SecurityGroupNftCompiler.Endpoint endpoint = endpoint(eni, "172.19.0.2");

        private Harness() {
            DockerClient docker = mock(DockerClient.class);
            ListContainersCmd listing = mock(ListContainersCmd.class, RETURNS_SELF);
            when(docker.listContainersCmd()).thenReturn(listing);
            when(listing.exec()).thenReturn(List.of());
            StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
            when(docker.stopContainerCmd(helper)).thenReturn(stop);
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
            firewall = spy(new SecurityGroupFirewallManager(docker, mock(ContainerBuilder.class),
                    lifecycle, config, mock(EmbeddedDnsServer.class)));
            doNothing().when(firewall).reconcileAll();
            doAnswer(ignored -> {
                removals.incrementAndGet();
                if (failRemoval.get()) {
                    throw removalFailure;
                }
                helperGone.set(true);
                return null;
            }).when(lifecycle).removeIfExistsStrict(helper);
            firewall.register(endpoint, helper, Map.of());
            assertTrue(firewall.vpcForClient("172.19.0.2").isPresent());
        }

        private SecurityGroupNftCompiler.Endpoint endpoint(String interfaceId, String transportAddress) {
            return new SecurityGroupNftCompiler.Endpoint("123456789012", "us-west-2", "vpc-owned",
                    interfaceId, "10.0.1.10", transportAddress, Set.of(), List.of());
        }

        @Override
        public void close() {
            firewall.stopDnsOriginObserver();
        }
    }
}
