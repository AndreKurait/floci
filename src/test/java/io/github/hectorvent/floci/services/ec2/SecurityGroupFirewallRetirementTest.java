package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.net.VpcRoutingManager;
import io.github.hectorvent.floci.services.elbv2.ElbV2VpcNetworkManager;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SecurityGroupFirewallRetirementTest {
    private static final String QUARANTINE = """
            flush chain inet floci_sg ingress
            flush chain inet floci_sg egress
            """;

    @Test
    void albShutdownRefreshesSurvivingPeerPoliciesOnceForTheWholeOwnedBatch() {
        SecurityGroupFirewallPeerFixture fixture = new SecurityGroupFirewallPeerFixture();
        SecurityGroupFirewallManager firewall = fixture.manager();
        for (int index = 0; index < 8; index++) {
            firewall.register(fixture.endpoint(index), fixture.helper(index), Map.of());
        }
        fixture.assertFinal(8);
        fixture.installs.clear();
        fixture.violations.clear();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.port()).thenReturn(4566);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("batch-run"));
        when(config.docker().extraLabels()).thenReturn(List.of());
        VpcNetworkManager networks = mock(VpcNetworkManager.class);
        when(networks.enabled()).thenReturn(true);
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(fixture.lifecycle.getDockerClient()).thenReturn(docker);
        List<Container> helpers = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Container helper = mock(Container.class);
            when(helper.getId()).thenReturn(fixture.helper(index));
            when(helper.getLabels()).thenReturn(Map.of(
                    ContainerStorageHelper.OWNER_LABEL, "batch-run/4566",
                    ContainerStorageHelper.SERVICE_LABEL, "elbv2",
                    ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true",
                    "io.floci.elbv2.network-interface", "eni-" + index,
                    "io.floci.elbv2.load-balancer", "alb"));
            helpers.add(helper);
        }
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec()).thenReturn(helpers);
        Ec2Service ec2 = mock(Ec2Service.class);
        ElbV2VpcNetworkManager albs =
                new ElbV2VpcNetworkManager(config, ec2, firewall, fixture.lifecycle, networks);

        albs.stopManagedContainers();

        assertEquals(8, fixture.installs.size(),
                "One quarantine per retired helper and one policy refresh per survivor");
        assertEquals(3, fixture.installs.stream().filter(install -> install[1].equals(QUARANTINE)).count());
        assertTrue(fixture.violations.isEmpty(), fixture.violations.toString());
        for (int index = 0; index < 3; index++) {
            verify(fixture.lifecycle, times(2)).removeIfExistsStrict(fixture.helper(index));
        }
        for (int index = 3; index < 8; index++) {
            verify(fixture.lifecycle, never()).removeIfExistsStrict(fixture.helper(index));
            for (int retired = 0; retired < 3; retired++) {
                assertFalse(fixture.installed.get(fixture.helper(index)).contains("172.18.0." + (retired + 2)));
            }
        }
        verifyNoInteractions(ec2);
    }

    @Test
    void retiringTogetherDoesNotReinstallPoliciesOnOtherRetiringHelpers() throws Exception {
        int count = 8;
        SecurityGroupFirewallPeerFixture fixture = new SecurityGroupFirewallPeerFixture();
        SecurityGroupFirewallManager manager = fixture.manager();
        for (int index = 0; index < count; index++) {
            manager.register(fixture.endpoint(index), fixture.helper(index), Map.of());
        }
        fixture.assertFinal(count);
        fixture.installs.clear();
        fixture.violations.clear();

        List<Throwable> failures = retireTogether(fixture, manager, count);

        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(count, fixture.installs.size(),
                "Each retiring helper needs its quarantine, not another complete peer policy");
        assertTrue(fixture.installs.stream().allMatch(install -> install[1].equals(QUARANTINE)));
        assertTrue(fixture.violations.isEmpty(), fixture.violations.toString());
        for (int index = 0; index < count; index++) {
            verify(fixture.lifecycle).removeIfExistsStrict(fixture.helper(index));
        }
    }

    @Test
    void survivingPeerLosesTheRetiredAddressBeforeItsHelperIsRemoved() {
        SecurityGroupFirewallPeerFixture fixture = new SecurityGroupFirewallPeerFixture();
        SecurityGroupFirewallManager manager = fixture.manager();
        manager.register(fixture.endpoint(0), fixture.helper(0), Map.of());
        manager.register(fixture.endpoint(1), fixture.helper(1), Map.of());
        fixture.assertFinal(2);
        doAnswer(ignored -> {
            assertFalse(fixture.installed.get(fixture.helper(1)).contains("172.18.0.2"),
                    "The retired transport address must not remain in a surviving peer's policy");
            return null;
        }).when(fixture.lifecycle).removeIfExistsStrict(fixture.helper(0));

        manager.unregister("eni-0");

        verify(fixture.lifecycle).removeIfExistsStrict(fixture.helper(0));
    }

    @Test
    void failedBatchQuarantinesTheSurvivorAndRetainsEveryRequestedCleanup() throws Exception {
        SecurityGroupFirewallPeerFixture fixture = new SecurityGroupFirewallPeerFixture();
        SecurityGroupFirewallManager manager = fixture.manager();
        for (int index = 0; index < 3; index++) {
            manager.register(fixture.endpoint(index), fixture.helper(index), Map.of());
        }
        fixture.assertFinal(3);
        fixture.failNextInstall.add(fixture.helper(2));

        List<Throwable> failures = retireTogether(fixture, manager, 2);

        assertEquals(2, failures.size(), failures.toString());
        assertTrue(failures.stream().allMatch(failure -> failure instanceof IllegalStateException));
        assertEquals(QUARANTINE, fixture.installed.get(fixture.helper(2)));
        verifyNoInteractions(fixture.lifecycle);

        manager.unregister("eni-0");
        manager.unregister("eni-1");

        verify(fixture.lifecycle).removeIfExistsStrict(fixture.helper(0));
        verify(fixture.lifecycle).removeIfExistsStrict(fixture.helper(1));
        assertFalse(fixture.installed.get(fixture.helper(2)).contains("172.18.0.2"));
        assertFalse(fixture.installed.get(fixture.helper(2)).contains("172.18.0.3"));
    }

    @Test
    void failedExplicitBatchRefreshRetainsEveryCleanupForIndividualRetry() {
        SecurityGroupFirewallPeerFixture fixture = new SecurityGroupFirewallPeerFixture();
        SecurityGroupFirewallManager manager = fixture.manager();
        for (int index = 0; index < 3; index++) {
            manager.register(fixture.endpoint(index), fixture.helper(index), Map.of());
        }
        fixture.assertFinal(3);
        fixture.failNextInstall.add(fixture.helper(2));

        assertThrows(IllegalStateException.class, () -> manager.unregisterAll(List.of("eni-0", "eni-1")));

        assertEquals(QUARANTINE, fixture.installed.get(fixture.helper(2)));
        verifyNoInteractions(fixture.lifecycle);
        manager.unregister("eni-0");
        manager.unregister("eni-1");
        verify(fixture.lifecycle).removeIfExistsStrict(fixture.helper(0));
        verify(fixture.lifecycle).removeIfExistsStrict(fixture.helper(1));
        assertFalse(fixture.installed.get(fixture.helper(2)).contains("172.18.0.2"));
        assertFalse(fixture.installed.get(fixture.helper(2)).contains("172.18.0.3"));
    }

    @Test
    void failedExplicitBatchPreparationKeepsEarlierAndFailedCleanupForRetry() {
        SecurityGroupFirewallPeerFixture fixture = new SecurityGroupFirewallPeerFixture();
        SecurityGroupFirewallManager manager = fixture.manager();
        for (int index = 0; index < 3; index++) {
            manager.register(fixture.endpoint(index), fixture.helper(index), Map.of());
        }
        fixture.assertFinal(3);
        VpcRoutingManager routing = mock(VpcRoutingManager.class);
        @SuppressWarnings("unchecked")
        Instance<VpcRoutingManager> routingInstance = mock(Instance.class);
        when(routingInstance.get()).thenReturn(routing);
        manager.vpcRouting = routingInstance;
        doThrow(new IllegalStateException("Route retirement failed")).doNothing()
                .when(routing).unregister("eni-1");

        assertThrows(IllegalStateException.class, () -> manager.unregisterAll(List.of("eni-0", "eni-1")));

        verifyNoInteractions(fixture.lifecycle);
        manager.unregister("eni-0");
        manager.unregister("eni-1");
        verify(fixture.lifecycle).removeIfExistsStrict(fixture.helper(0));
        verify(fixture.lifecycle).removeIfExistsStrict(fixture.helper(1));
        verify(fixture.lifecycle, never()).removeIfExistsStrict(fixture.helper(2));
        assertFalse(fixture.installed.get(fixture.helper(2)).contains("172.18.0.2"));
        assertFalse(fixture.installed.get(fixture.helper(2)).contains("172.18.0.3"));
    }

    private static List<Throwable> retireTogether(SecurityGroupFirewallPeerFixture fixture,
                                                  SecurityGroupFirewallManager manager, int count)
            throws InterruptedException {
        CountDownLatch firstQuarantine = new CountDownLatch(1);
        CountDownLatch releaseQuarantine = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(count);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        fixture.beforeInstall = (helper, script) -> {
            if (helper.equals(fixture.helper(0)) && script.equals(QUARANTINE)) {
                firstQuarantine.countDown();
                try {
                    assertTrue(releaseQuarantine.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
        };
        try {
            threads.add(retire(manager, 0, completed, failures));
            assertTrue(firstQuarantine.await(5, TimeUnit.SECONDS));
            for (int index = 1; index < count; index++) {
                threads.add(retire(manager, index, completed, failures));
            }
            await().atMost(Duration.ofSeconds(5)).until(() -> threads.stream().skip(1)
                    .allMatch(thread -> thread.getState() == Thread.State.BLOCKED));
            releaseQuarantine.countDown();
            assertTrue(completed.await(10, TimeUnit.SECONDS));
            return List.copyOf(failures);
        } finally {
            releaseQuarantine.countDown();
            for (Thread thread : threads) {
                thread.join(Duration.ofSeconds(10));
            }
        }
    }

    private static Thread retire(SecurityGroupFirewallManager manager, int index,
                                 CountDownLatch completed, ConcurrentLinkedQueue<Throwable> failures) {
        return Thread.ofVirtual().name("retire-" + index).start(() -> {
            try {
                manager.unregister("eni-" + index);
            } catch (Throwable error) {
                failures.add(error);
            } finally {
                completed.countDown();
            }
        });
    }
}
