package io.github.hectorvent.floci.services.autoscaling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingActivity;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Best-effort successful scaling events. Immutable events leave the group's lock through a
 * bounded in-memory queue. Queued events are not persisted or replayed after shutdown.
 */
@ApplicationScoped
public class AutoScalingEventPublisher {

    private static final Logger LOG = Logger.getLogger(AutoScalingEventPublisher.class);
    static final String SOURCE = "aws.autoscaling";
    static final String LAUNCH = "EC2 Instance Launch Successful";
    static final String TERMINATE = "EC2 Instance Terminate Successful";
    private static final int MAX_PENDING = 4096;

    private record Group(String arn, String name, String region, String account) {}

    private record Event(Group group, String instanceArn, String type, String detail, Instant time) {}

    private record Activity(String id, String cause, Instant started) {}

    final class Termination implements Consumer<Instance> {
        private final Group group;
        private final Map<String, CompletableFuture<Activity>> activities = new LinkedHashMap<>();

        private Termination(Group group, List<String> instanceIds) {
            this.group = group;
            instanceIds.forEach(id -> activities.put(id, new CompletableFuture<>()));
        }

        void activity(List<String> instanceIds, ScalingActivity activity) {
            if (activity == null) {
                return;
            }
            Activity captured = new Activity(activity.getActivityId(), activity.getCause(), activity.getStartTime());
            instanceIds.forEach(id -> {
                CompletableFuture<Activity> pendingActivity = activities.get(id);
                if (pendingActivity != null) {
                    pendingActivity.complete(captured);
                }
            });
        }

        @Override
        public void accept(Instance instance) {
            CompletableFuture<Activity> activity = activities.get(instance.getInstanceId());
            if (activity == null) {
                return;
            }
            activity.thenAccept(captured -> {
                try {
                    enqueue(group, instance, false, captured.id(), captured.cause(),
                            captured.started(), clock.instant());
                } catch (RuntimeException failure) {
                    LOG.warnv(failure, "Could not queue Auto Scaling termination event for {0}",
                            instance.getInstanceId());
                }
            });
        }
    }

    private final EventBridgeService eventBridge;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final BlockingQueue<Event> pending = new ArrayBlockingQueue<>(MAX_PENDING);
    private volatile boolean stopping;
    private Thread worker;

    @Inject
    public AutoScalingEventPublisher(EventBridgeService eventBridge, ObjectMapper mapper) {
        // Existing Auto Scaling activity constructors use the system UTC clock.
        this(eventBridge, mapper, Clock.systemUTC());
    }

    public AutoScalingEventPublisher(EventBridgeService eventBridge, ObjectMapper mapper, Clock clock) {
        this.eventBridge = eventBridge;
        this.mapper = mapper;
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        worker = new Thread(this::deliverInOrder, "autoscaling-event-publisher");
        worker.setDaemon(true);
        worker.start();
    }

    @PreDestroy
    void stop() {
        stopping = true;
        if (worker != null) {
            worker.interrupt();
        }
    }

    void launched(AutoScalingGroup group, Instance instance, ScalingActivity activity) {
        try {
            enqueue(snapshot(group), instance, true, activity.getActivityId(), activity.getCause(),
                    activity.getStartTime(), clock.instant());
        } catch (RuntimeException failure) {
            LOG.warnv(failure, "Could not queue Auto Scaling launch event for {0}", instance.getInstanceId());
        }
    }

    /**
     * Captures ownership before membership or the group is deleted. EC2 invokes this callback
     * only after the request succeeded and native teardown established that the container is gone.
     */
    Termination termination(AutoScalingGroup group, List<String> instanceIds) {
        try {
            return new Termination(snapshot(group), instanceIds);
        } catch (RuntimeException failure) {
            LOG.warnv(failure, "Could not capture Auto Scaling event ownership for {0}",
                    group.getAutoScalingGroupName());
            return null;
        }
    }

    private static Group snapshot(AutoScalingGroup group) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(group.getAutoScalingGroupArn());
        if (!"autoscaling".equals(arn.service()) || arn.accountId().isBlank()
                || arn.region().isBlank() || !arn.region().equals(group.getRegion())) {
            throw new IllegalArgumentException("Auto Scaling group has inconsistent event ownership");
        }
        return new Group(group.getAutoScalingGroupArn(), group.getAutoScalingGroupName(),
                arn.region(), arn.accountId());
    }

    private void enqueue(Group group, Instance instance, boolean launch, String activityId,
                         String cause, Instant started, Instant ended) {
        if (!group.account().equals(instance.getOwnerId()) || !group.region().equals(instance.getRegion())) {
            throw new IllegalArgumentException("EC2 instance does not belong to the event's account and Region");
        }
        String expectedState = launch ? "running" : "terminated";
        if (instance.getState() == null || !expectedState.equals(instance.getState().getName())) {
            return;
        }
        String instanceId = instance.getInstanceId();
        // A backward wall-clock adjustment cannot put completion before its recorded activity start.
        Instant completed = ended.isBefore(started) ? started : ended;
        ObjectNode detail = mapper.createObjectNode();
        // AWS's successful scaling event examples retain InProgress in this member.
        detail.put("StatusCode", "InProgress");
        detail.put("Description", (launch ? "Launching a new EC2 instance: " : "Terminating EC2 instance: ")
                + instanceId);
        detail.put("AutoScalingGroupName", group.name());
        detail.put("ActivityId", activityId);
        ObjectNode placement = detail.putObject("Details");
        if (instance.getPlacement() != null && instance.getPlacement().getAvailabilityZone() != null) {
            placement.put("Availability Zone", instance.getPlacement().getAvailabilityZone());
        }
        if (instance.getSubnetId() != null) {
            placement.put("Subnet ID", instance.getSubnetId());
        }
        detail.put("RequestId", UUID.randomUUID().toString());
        detail.put("StatusMessage", "");
        detail.put("EndTime", completed.toString());
        detail.put("EC2InstanceId", instanceId);
        detail.put("StartTime", started.toString());
        detail.put("Cause", cause == null ? "" : cause);
        detail.put("Origin", launch ? "EC2" : "AutoScalingGroup");
        detail.put("Destination", launch ? "AutoScalingGroup" : "EC2");
        String instanceArn = AwsArnUtils.Arn.of("ec2", group.region(), group.account(),
                "instance/" + instanceId).toString();
        Event event = new Event(group, instanceArn, launch ? LAUNCH : TERMINATE, detail.toString(), completed);
        if (stopping || !pending.offer(event)) {
            LOG.warnv("Dropping Auto Scaling event for {0}: publisher is stopping or its queue is full", instanceId);
        }
    }

    private void deliverInOrder() {
        try {
            while (!stopping) {
                deliver(pending.take());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            int dropped = pending.size();
            pending.clear();
            if (dropped > 0) {
                LOG.warnv("Dropping {0} queued Auto Scaling event(s): publisher is stopping", dropped);
            }
        }
    }

    private void deliver(Event event) {
        try {
            Map<String, Object> entry = EventBridgeService.serviceEventEntry(
                    SOURCE, event.type(), event.detail(), event.group().arn());
            ((ArrayNode) entry.get("Resources")).add(event.instanceArn());
            entry.put("EventBusName", "default");
            entry.put("Account", event.group().account());
            entry.put("Region", event.group().region());
            entry.put("Time", event.time());
            RequestScopes.runAs(event.group().account(), event.group().region(), () -> {
                EventBridgeService.PutEventsResult result =
                        eventBridge.putEvents(List.of(entry), event.group().region());
                if (result.failedCount() > 0) {
                    LOG.warnv("EventBridge rejected Auto Scaling event for {0}", event.instanceArn());
                }
            });
        } catch (RuntimeException failure) {
            LOG.warnv(failure, "Could not deliver Auto Scaling event for {0}", event.instanceArn());
        }
    }
}
