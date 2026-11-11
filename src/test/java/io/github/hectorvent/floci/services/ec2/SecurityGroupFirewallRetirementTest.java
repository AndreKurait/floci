package io.github.hectorvent.floci.services.ec2;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class SecurityGroupFirewallRetirementTest {
    private static final String QUARANTINE = """
            flush chain inet floci_sg ingress
            flush chain inet floci_sg egress
            """;

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
