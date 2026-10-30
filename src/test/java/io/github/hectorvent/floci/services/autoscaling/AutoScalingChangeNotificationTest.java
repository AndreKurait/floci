package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoScalingChangeNotificationTest {
    private static final AutoScalingGroupChanged CHANGE =
            new AutoScalingGroupChanged("123456789012", "eu-west-1", "group");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedLaunchSignalsOwningGroupWithoutPeriodicReconciliation(boolean compareAndSet) {
        AutoScalingService service = mock(AutoScalingService.class);
        AutoScalingReconciler reconciler = new AutoScalingReconciler(
                service, mock(Ec2Service.class), mock(ElbV2Service.class));
        AutoScalingGroup group = new AutoScalingGroup();
        group.setRegion(CHANGE.region());
        group.setAutoScalingGroupName(CHANGE.name());
        Instance instance = new Instance();
        instance.setOwnerId(CHANGE.accountId());
        instance.setInstanceId("i-signalled");
        instance.setState(InstanceState.pending());
        try {
            reconciler.watchPendingLaunch(group, instance);
            reconciler.watchPendingLaunch(group, instance);
            if (compareAndSet) {
                assertTrue(instance.compareAndSetState(instance.getState(), InstanceState.running()));
            } else {
                instance.setState(InstanceState.running());
            }
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    verify(service).describeAutoScalingGroups(CHANGE.region(), List.of(CHANGE.name())));
        } finally {
            reconciler.stop();
        }
    }

    @Test
    void mutationDuringAReadQueuesAnotherPassAndUnrelatedGroupsProgress() throws Exception {
        AutoScalingService service = mock(AutoScalingService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        when(service.describeAutoScalingGroups(CHANGE.region(), List.of(CHANGE.name()))).thenAnswer(call -> {
            if (reads.incrementAndGet() == 1) {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return List.of();
        });
        AutoScalingReconciler reconciler = new AutoScalingReconciler(
                service, mock(Ec2Service.class), mock(ElbV2Service.class));
        try {
            reconciler.onGroupChanged(CHANGE);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 1000; i++) {
                reconciler.onGroupChanged(CHANGE);
            }
            reconciler.onGroupChanged(new AutoScalingGroupChanged(CHANGE.accountId(), CHANGE.region(), "other"));
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    verify(service).describeAutoScalingGroups(CHANGE.region(), List.of("other")));
            assertEquals(1, reads.get());
            release.countDown();
            await().atMost(Duration.ofSeconds(2)).until(() -> reads.get() == 2);
        } finally {
            release.countDown();
            reconciler.stop();
        }
    }

    @Test
    void deletedGroupIsNotRecreatedAndFailureDoesNotDiscardAnotherGroup() {
        AutoScalingService service = mock(AutoScalingService.class);
        when(service.describeAutoScalingGroups(CHANGE.region(), List.of(CHANGE.name())))
                .thenThrow(new IllegalStateException("storage temporarily unavailable"));
        AutoScalingReconciler reconciler = spy(new AutoScalingReconciler(
                service, mock(Ec2Service.class), mock(ElbV2Service.class)));
        try {
            reconciler.onGroupChanged(CHANGE);
            reconciler.onGroupChanged(new AutoScalingGroupChanged(CHANGE.accountId(), CHANGE.region(), "deleted"));
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                    verify(service).describeAutoScalingGroups(CHANGE.region(), List.of("deleted")));
            verify(reconciler, never()).reconcile(any());
        } finally {
            reconciler.stop();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void successfulMutationsSignalButValidationFailureAndReconcilePersistenceDoNot() {
        Event<AutoScalingGroupChanged> events = mock(Event.class);
        AutoScalingService service = new AutoScalingService(null, events);
        service.regionResolver = new RegionResolver(CHANGE.region(), CHANGE.accountId());
        service.ec2Service = launchTemplateService();
        AutoScalingGroup group = service.createAutoScalingGroup(
                CHANGE.region(), CHANGE.name(), null, "lt-template", null, "1", null,
                0, 4, 1, 300, List.of(), List.of(), List.of(), List.of(),
                "EC2", 0, List.of(), null, null, null);
        service.setDesiredCapacity(CHANGE.region(), CHANGE.name(), 4);
        service.resumeProcesses(CHANGE.region(), CHANGE.name(), List.of());
        verify(events, times(3)).fire(CHANGE);
        clearInvocations(events);
        assertThrows(AwsException.class, () -> service.setDesiredCapacity(CHANGE.region(), CHANGE.name(), 5));
        assertTrue(service.saveAutoScalingGroupIfPresent(group));
        verifyNoInteractions(events);
    }

    @ParameterizedTest
    @ValueSource(strings = {"capacity", "instances", "targets", "tags"})
    @SuppressWarnings("unchecked")
    void mutationsWaitForOwningReconciliationWhileAnotherGroupProgresses(String mutation) throws Exception {
        Event<AutoScalingGroupChanged> events = mock(Event.class);
        AutoScalingService service = new AutoScalingService(null, events);
        service.regionResolver = new RegionResolver(CHANGE.region(), CHANGE.accountId());
        service.ec2Service = launchTemplateService();
        AutoScalingGroup group = service.createAutoScalingGroup(
                CHANGE.region(), CHANGE.name(), null, "lt-template", null, "1", null,
                0, 4, 1, 300, List.of(), List.of(), List.of(), List.of(),
                "EC2", 0, List.of(), null, null, null);
        service.createAutoScalingGroup(
                CHANGE.region(), "other", null, "lt-template", null, "1", null,
                0, 4, 1, 300, List.of(), List.of(), List.of(), List.of(),
                "EC2", 0, List.of(), null, null, null);
        clearInvocations(events);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<?> reconciliation = workers.submit(() -> service.reconcileIfPresent(group, () -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Runnable change = switch (mutation) {
                case "capacity" -> () -> service.setDesiredCapacity(CHANGE.region(), CHANGE.name(), 3);
                case "instances" -> () -> service.attachInstances(CHANGE.region(), CHANGE.name(), List.of("i-attached"));
                case "targets" -> () -> service.attachLoadBalancerTargetGroups(
                        CHANGE.region(), CHANGE.name(), List.of("target"));
                case "tags" -> () -> service.createOrUpdateTags(CHANGE.region(), CHANGE.name(), "auto-scaling-group",
                        Map.of("key", "value"), Map.of("key", true));
                default -> throw new AssertionError(mutation);
            };
            Future<?> update = workers.submit(() -> {
                started.countDown();
                change.run();
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> update.get(100, TimeUnit.MILLISECONDS));
            verifyNoInteractions(events);
            service.setDesiredCapacity(CHANGE.region(), "other", 2);
            verify(events).fire(new AutoScalingGroupChanged(CHANGE.accountId(), CHANGE.region(), "other"));
            release.countDown();
            reconciliation.get(2, TimeUnit.SECONDS);
            update.get(2, TimeUnit.SECONDS);
            verify(events).fire(CHANGE);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }
    private static Ec2Service launchTemplateService() {
        Ec2Service service = mock(Ec2Service.class);
        LaunchTemplateData data = new LaunchTemplateData();
        data.setImageId("ami-test");
        LaunchTemplate template = new LaunchTemplate();
        template.setData(data);
        when(service.describeLaunchTemplateVersions(CHANGE.region(), "lt-template", null, List.of("1")))
                .thenReturn(List.of(template));
        return service;
    }

}
