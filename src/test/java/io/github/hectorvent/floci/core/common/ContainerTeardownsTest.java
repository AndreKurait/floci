package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.net.VpcRoutingManager;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.lambda.WarmPool;
import io.github.hectorvent.floci.testing.TestTime;
import io.github.hectorvent.floci.testing.TimelineDeadlineWaits;
import jakarta.enterprise.inject.Instance;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContainerTeardownsTest {

    private static final Logger LOG = Logger.getLogger(ContainerTeardownsTest.class);

    @Test
    void preparesEveryOwnerBeforeStoppingAnyContainersInDependencyOrder() {
        List<String> events = new ArrayList<>();
        ContainerTeardown storage = new ContainerTeardown() {
            @Override
            public void beginTeardown() {
                events.add("prepare storage");
            }

            @Override
            public void stopManagedContainers() {
                events.add("stop storage");
            }

            @Override
            public int teardownOrder() {
                return 1;
            }
        };
        ContainerTeardown drivers = new ContainerTeardown() {
            @Override
            public void beginTeardown() {
                events.add("prepare drivers");
            }

            @Override
            public void stopManagedContainers() {
                events.add("stop drivers");
            }
        };
        @SuppressWarnings("unchecked")
        Instance<ContainerTeardown> teardowns = mock(Instance.class);
        when(teardowns.iterator()).thenAnswer(invocation -> List.of(storage, drivers).iterator());

        ContainerTeardowns.stopAll(teardowns, LOG);

        assertEquals(List.of("prepare drivers", "prepare storage", "stop drivers", "stop storage"), events);
    }

    @Test
    void failedPreparationStillPreparesAndStopsEveryOwner() {
        ContainerTeardown failed = mock(ContainerTeardown.class);
        ContainerTeardown following = mock(ContainerTeardown.class);
        doThrow(new IllegalStateException("Could not stop admissions")).when(failed).beginTeardown();
        @SuppressWarnings("unchecked")
        Instance<ContainerTeardown> teardowns = mock(Instance.class);
        when(teardowns.iterator()).thenAnswer(invocation -> List.of(failed, following).iterator());

        ContainerTeardowns.stopAll(teardowns, LOG);

        verify(failed).stopManagedContainers();
        verify(following).beginTeardown();
        verify(following).stopManagedContainers();
    }

    @Test
    void workloadsRetireBeforeEc2AndVpcNetworkCleanup() {
        Ec2Service ec2 = mock(Ec2Service.class);
        VpcRoutingManager network = mock(VpcRoutingManager.class);
        EcsService ecs = mock(EcsService.class);
        WarmPool lambda = mock(WarmPool.class);
        List<String> stopped = new ArrayList<>();
        for (ContainerTeardown teardown : List.of(ec2, network, lambda, ecs)) {
            doCallRealMethod().when(teardown).teardownOrder();
        }
        doAnswer(ignored -> {
            stopped.add("lambda");
            return null;
        }).when(lambda).stopManagedContainers();
        doAnswer(ignored -> {
            assertTrue(stopped.contains("lambda"),
                    "The Lambda namespace batch must drain before per-task ECS retirement");
            stopped.add("ecs");
            return null;
        }).when(ecs).stopManagedContainers();
        doAnswer(ignored -> {
            assertTrue(stopped.containsAll(List.of("lambda", "ecs")),
                    "EC2 retirement must not reconcile the firewall of live Lambda and ECS workloads");
            stopped.add("ec2");
            return null;
        }).when(ec2).stopManagedContainers();
        doAnswer(ignored -> {
            assertTrue(stopped.containsAll(List.of("lambda", "ecs", "ec2")),
                    "VPC routes must remain available until their workloads retire");
            stopped.add("network");
            return null;
        }).when(network).stopManagedContainers();
        @SuppressWarnings("unchecked")
        Instance<ContainerTeardown> teardowns = mock(Instance.class);
        when(teardowns.iterator()).thenAnswer(ignored -> List.of(ec2, network, ecs, lambda).iterator());

        ContainerTeardowns.stopAll(teardowns, LOG);

        assertEquals(List.of("lambda", "ecs", "ec2", "network"), stopped);
    }

    @Test
    void preparesEveryServiceBeforeTheFirstDrain() {
        assertPreparesBeforeDraining(false);
    }

    @Test
    void failedPreparationStillPreparesAndDrainsEveryService() {
        assertPreparesBeforeDraining(true);
    }

    private static void assertPreparesBeforeDraining(boolean failPreparation) {
        List<String> phases = new ArrayList<>();
        ContainerTeardown first = teardown("first", phases, failPreparation);
        ContainerTeardown second = teardown("second", phases, false);
        @SuppressWarnings("unchecked")
        Instance<ContainerTeardown> teardowns = mock(Instance.class);
        when(teardowns.iterator()).thenAnswer(ignored -> List.of(first, second).iterator());

        ContainerTeardowns.stopAll(teardowns, LOG);

        assertEquals(List.of("prepare-first", "prepare-second", "stop-first", "stop-second"), phases);
    }

    private static ContainerTeardown teardown(String name, List<String> phases, boolean failPreparation) {
        return new ContainerTeardown() {
            @Override
            public void beginTeardown() {
                phases.add("prepare-" + name);
                if (failPreparation) {
                    throw new IllegalStateException("injected preparation failure");
                }
            }

            @Override
            public void stopManagedContainers() {
                phases.add("stop-" + name);
            }
        };
    }

    @Test
    void stopAllRunsATeardownWhoseContainersOthersUseAfterTheirs() {
        List<String> stopped = new ArrayList<>();
        ContainerTeardown storage = new ContainerTeardown() {
            @Override
            public void stopManagedContainers() {
                stopped.add("storage");
            }

            @Override
            public int teardownOrder() {
                return 1;
            }
        };
        ContainerTeardown drivers = () -> stopped.add("drivers");
        @SuppressWarnings("unchecked")
        Instance<ContainerTeardown> teardowns = mock(Instance.class);
        when(teardowns.iterator()).thenAnswer(invocation -> List.of(storage, drivers).iterator());

        ContainerTeardowns.stopAll(teardowns, LOG);

        assertEquals(List.of("drivers", "storage"), stopped);
    }

    @Test
    void admitsEveryOwnedStopBeforeWaitingForOtherContainers() throws Exception {
        List<Integer> resources = IntStream.range(0, 83).boxed().toList();
        CountDownLatch started = new CountDownLatch(resources.size());
        CountDownLatch release = new CountDownLatch(1);
        Set<Integer> stopped = ConcurrentHashMap.newKeySet();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<?> drain = caller.submit(() -> ContainerTeardowns.stopInParallel(resources, resource -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            stopped.add(resource);
        }, LOG));
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS),
                    "Queued containers must receive their stop before slower containers consume the shared deadline");
        } finally {
            release.countDown();
            try {
                drain.get(5, TimeUnit.SECONDS);
            } finally {
                caller.shutdownNow();
            }
        }
        assertEquals(Set.copyOf(resources), stopped);
    }

    @Test
    void failedStopDoesNotPreventTheOtherOwnedStops() {
        List<Integer> resources = IntStream.range(0, 83).boxed().toList();
        Set<Integer> attempted = ConcurrentHashMap.newKeySet();
        ContainerTeardowns.stopInParallel(resources, resource -> {
            attempted.add(resource);
            if (resource == 0) {
                throw new IllegalStateException("Docker removal failed");
            }
        }, LOG);
        assertEquals(Set.copyOf(resources), attempted);
    }

    @Test
    void anExpiredDeadlineDoesNotStartAnotherDrainBudget() {
        AtomicInteger attempts = new AtomicInteger();
        ContainerTeardowns.stopInParallel(List.of(1, 2),
                ignored -> attempts.incrementAndGet(), LOG, System.nanoTime() - 1);
        assertEquals(0, attempts.get());
    }

    @Test
    void stopsStillRunningAtTheFifteenSecondDeadlineAreInterruptedAndReported() throws Exception {
        TestTime time = TestTime.create();
        TimelineDeadlineWaits waits = new TimelineDeadlineWaits(time);
        Logger log = mock(Logger.class);
        HeldStops stops = new HeldStops(3);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> drain = caller.submit(
                    () -> ContainerTeardowns.stopInParallel(List.of(0, 1, 2), stops::stop, log, waits));
            stops.awaitStarted();
            waits.awaitWaiting(1);

            time.advance(Duration.ofSeconds(15).minusNanos(1));
            assertFalse(drain.isDone(), "the drain gave up before its fifteen seconds had passed");
            assertEquals(1, waits.waiting());
            assertEquals(Set.of(), stops.interrupted);

            time.advance(Duration.ofNanos(1));
            drain.get(5, TimeUnit.SECONDS);
            stops.awaitInterrupted();
            assertEquals(Set.of(0, 1, 2), stops.interrupted);
            verify(log, times(3)).warn("Container drain did not finish before the shared deadline");
        } finally {
            stops.release();
            caller.shutdownNow();
        }
    }

    @Test
    void stopsThatFinishOneNanosecondBeforeTheDeadlineAreNotReported() throws Exception {
        TestTime time = TestTime.create();
        TimelineDeadlineWaits waits = new TimelineDeadlineWaits(time);
        Logger log = mock(Logger.class);
        HeldStops stops = new HeldStops(3);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> drain = caller.submit(
                    () -> ContainerTeardowns.stopInParallel(List.of(0, 1, 2), stops::stop, log, waits));
            stops.awaitStarted();
            waits.awaitWaiting(1);
            time.advance(Duration.ofSeconds(15).minusNanos(1));

            stops.release();
            drain.get(5, TimeUnit.SECONDS);

            assertEquals(Set.of(0, 1, 2), stops.finished);
            assertEquals(Set.of(), stops.interrupted);
            verify(log, never()).warn(any());
            assertEquals(0, time.pendingTasks(), "a finished drain must leave no deadline behind");
        } finally {
            stops.release();
            caller.shutdownNow();
        }
    }

    @Test
    void aSharedDeadlineIsNotRestartedByTheTimeAlreadySpent() throws Exception {
        TestTime time = TestTime.create();
        TimelineDeadlineWaits waits = new TimelineDeadlineWaits(time);
        Logger log = mock(Logger.class);
        HeldStops stops = new HeldStops(1);
        long deadline = waits.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        time.advance(Duration.ofSeconds(2));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> drain = caller.submit(
                    () -> ContainerTeardowns.stopInParallel(List.of(0), stops::stop, log, deadline, waits));
            stops.awaitStarted();
            waits.awaitWaiting(1);

            time.advance(Duration.ofSeconds(3).minusNanos(1));
            assertFalse(drain.isDone());
            assertEquals(Set.of(), stops.interrupted);

            time.advance(Duration.ofNanos(1));
            drain.get(5, TimeUnit.SECONDS);
            stops.awaitInterrupted();
            verify(log).warn("Container drain did not finish before the shared deadline");
        } finally {
            stops.release();
            caller.shutdownNow();
        }
    }

    @Test
    void aDeadlineOneNanosecondAwayStillStartsEveryStopAndAnExpiredOneStartsNone() {
        TestTime time = TestTime.create();
        TimelineDeadlineWaits waits = new TimelineDeadlineWaits(time);
        Logger log = mock(Logger.class);
        Set<Integer> attempted = ConcurrentHashMap.newKeySet();

        ContainerTeardowns.stopInParallel(List.of(1, 2), attempted::add, log, waits.nanoTime() + 1, waits);
        assertEquals(Set.of(1, 2), attempted);
        verify(log, never()).warnv(any(String.class), any(Object.class));

        attempted.clear();
        ContainerTeardowns.stopInParallel(List.of(1, 2), attempted::add, log, waits.nanoTime(), waits);
        assertEquals(Set.of(), attempted);
        verify(log).warnv("Container drain deadline expired with {0} resource(s) pending", 2);
    }

    /** Stops that block until released or interrupted, and record which of the two ended them. */
    private static final class HeldStops {
        private final CountDownLatch started;
        private final CountDownLatch interruptions;
        private final CountDownLatch release = new CountDownLatch(1);
        private final Set<Integer> finished = ConcurrentHashMap.newKeySet();
        private final Set<Integer> interrupted = ConcurrentHashMap.newKeySet();

        HeldStops(int count) {
            this.started = new CountDownLatch(count);
            this.interruptions = new CountDownLatch(count);
        }

        void stop(Integer resource) {
            started.countDown();
            try {
                release.await();
                finished.add(resource);
            } catch (InterruptedException e) {
                interrupted.add(resource);
                interruptions.countDown();
            }
        }

        void awaitStarted() throws InterruptedException {
            assertTrue(started.await(5, TimeUnit.SECONDS), "every stop must start");
        }

        void awaitInterrupted() throws InterruptedException {
            assertTrue(interruptions.await(5, TimeUnit.SECONDS), "every unfinished stop must be interrupted");
        }

        void release() {
            release.countDown();
        }
    }
}
