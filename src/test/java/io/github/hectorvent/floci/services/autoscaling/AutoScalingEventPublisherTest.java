package io.github.hectorvent.floci.services.autoscaling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingActivity;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoScalingEventPublisherTest {
    private static final String ACCOUNT = "815670000071";
    private static final String REGION = "cn-north-1";
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final EventBridgeService bus = mock(EventBridgeService.class);
    private final AutoScalingEventPublisher publisher =
            new AutoScalingEventPublisher(bus, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
    private final BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();

    @AfterEach
    void stop() {
        publisher.stop();
    }

    @Test
    void launchPreservesPartitionOwnershipPlacementAndDocumentedDetails() throws Exception {
        capture();
        publisher.start();
        Instance instance = instance();
        ScalingActivity activity = activity();
        publisher.launched(group(), instance, activity);

        Map<String, Object> event = take();
        assertEquals("aws.autoscaling", event.get("Source"));
        assertEquals("EC2 Instance Launch Successful", event.get("DetailType"));
        assertEquals(ACCOUNT, event.get("Account"));
        assertEquals(REGION, event.get("Region"));
        assertEquals("default", event.get("EventBusName"));
        assertEquals(NOW, event.get("Time"));
        JsonNode resources = mapper.valueToTree(event.get("Resources"));
        assertEquals(group().getAutoScalingGroupArn(), resources.get(0).asText());
        assertEquals("arn:aws-cn:ec2:cn-north-1:" + ACCOUNT + ":instance/i-owned", resources.get(1).asText());
        JsonNode detail = mapper.readTree((String) event.get("Detail"));
        assertEquals("i-owned", detail.path("EC2InstanceId").asText());
        assertEquals("owned-group", detail.path("AutoScalingGroupName").asText());
        assertEquals("activity-owned", detail.path("ActivityId").asText());
        assertEquals("desired capacity change", detail.path("Cause").asText());
        assertEquals("cn-north-1a", detail.path("Details").path("Availability Zone").asText());
        assertEquals("subnet-owned", detail.path("Details").path("Subnet ID").asText());
        assertEquals("EC2", detail.path("Origin").asText());
        assertEquals("AutoScalingGroup", detail.path("Destination").asText());
        assertEquals(NOW, Instant.parse(detail.path("StartTime").asText()));
        assertEquals(NOW, Instant.parse(detail.path("EndTime").asText()));
    }

    @Test
    void terminationKeepsCapturedGroupAfterDeletionAndMutation() throws Exception {
        capture();
        AutoScalingGroup group = group();
        AutoScalingEventPublisher.Termination completed = publisher.termination(group, List.of("i-owned"));
        completed.activity(List.of("i-owned"), activity());
        group.setAutoScalingGroupName("other");
        group.setAutoScalingGroupArn("invalid");
        group.setRegion("us-west-2");
        Instance instance = instance();
        instance.setState(InstanceState.terminated());
        completed.accept(instance);
        instance.setSubnetId("changed-after-queue");
        publisher.start();

        Map<String, Object> event = take();
        JsonNode detail = mapper.readTree((String) event.get("Detail"));
        assertEquals("EC2 Instance Terminate Successful", event.get("DetailType"));
        assertEquals("owned-group", detail.path("AutoScalingGroupName").asText());
        assertEquals("subnet-owned", detail.path("Details").path("Subnet ID").asText());
        assertEquals("AutoScalingGroup", detail.path("Origin").asText());
        assertEquals("EC2", detail.path("Destination").asText());
        assertEquals(REGION, event.get("Region"));
        assertEquals(ACCOUNT, event.get("Account"));
    }

    @Test
    void pendingLaunchAndUnfinishedTerminationDoNotReachBus() throws Exception {
        capture();
        publisher.start();
        Instance instance = instance();
        instance.setState(InstanceState.pending());
        publisher.launched(group(), instance, activity());
        instance.setState(InstanceState.shuttingDown());
        AutoScalingEventPublisher.Termination termination = publisher.termination(group(), List.of("i-owned"));
        termination.activity(List.of("i-owned"), activity());
        termination.accept(instance);

        assertNull(received.poll(100, TimeUnit.MILLISECONDS));
        verifyNoInteractions(bus);
    }

    @Test
    void wrongAccountOrRegionAndInvalidGroupDoNotReachBus() throws Exception {
        capture();
        publisher.start();
        Instance instance = instance();
        instance.setOwnerId("999999999999");
        publisher.launched(group(), instance, activity());
        instance.setOwnerId(ACCOUNT);
        instance.setRegion("us-east-1");
        publisher.launched(group(), instance, activity());
        AutoScalingGroup invalid = group();
        invalid.setAutoScalingGroupArn("invalid");
        instance.setState(InstanceState.terminated());
        assertNull(publisher.termination(invalid, List.of("i-owned")));

        assertNull(received.poll(100, TimeUnit.MILLISECONDS));
        verifyNoInteractions(bus);
    }

    @Test
    void busFailureDoesNotStopLaterEventsOrEscapeTheProducer() throws Exception {
        when(bus.putEvents(anyList(), eq(REGION))).thenThrow(new IllegalStateException("bus unavailable"))
                .thenAnswer(call -> {
                    List<Map<String, Object>> entries = call.getArgument(0);
                    received.add(entries.getFirst());
                    return new EventBridgeService.PutEventsResult(0, List.of());
                });
        publisher.start();

        assertDoesNotThrow(() -> publisher.launched(group(), instance(), activity()));
        assertDoesNotThrow(() -> publisher.launched(group(), instance(), activity()));

        assertNotNull(take());
        verify(bus, times(2)).putEvents(anyList(), eq(REGION));
    }

    @Test
    void nativeCompletionWaitsForActualActivityMetadata() throws Exception {
        capture();
        publisher.start();
        AutoScalingEventPublisher.Termination termination = publisher.termination(group(), List.of("i-owned"));
        Instance instance = instance();
        instance.setState(InstanceState.terminated());
        termination.accept(instance);
        assertNull(received.poll(100, TimeUnit.MILLISECONDS));

        termination.activity(List.of("i-owned"), activity());

        JsonNode detail = mapper.readTree((String) take().get("Detail"));
        assertEquals("activity-owned", detail.path("ActivityId").asText());
    }


    @ParameterizedTest
    @ValueSource(strings = {"launch", "native-first", "activity-first"})
    void completionDoesNotPrecedeActivityAcrossClockRollback(String transition) throws Exception {
        capture();
        publisher.start();
        ScalingActivity activity = activity();
        Instant started = NOW.plusSeconds(30);
        activity.setStartTime(started);
        Instance instance = instance();
        if ("launch".equals(transition)) {
            publisher.launched(group(), instance, activity);
        } else {
            instance.setState(InstanceState.terminated());
            AutoScalingEventPublisher.Termination termination =
                    publisher.termination(group(), List.of("i-owned"));
            if ("native-first".equals(transition)) {
                termination.accept(instance);
                assertNull(received.poll(100, TimeUnit.MILLISECONDS));
                termination.activity(List.of("i-owned"), activity);
            } else {
                termination.activity(List.of("i-owned"), activity);
                assertNull(received.poll(100, TimeUnit.MILLISECONDS));
                termination.accept(instance);
            }
        }

        Map<String, Object> event = take();
        JsonNode detail = mapper.readTree((String) event.get("Detail"));
        assertEquals(started, Instant.parse(detail.path("StartTime").asText()));
        assertEquals(started, Instant.parse(detail.path("EndTime").asText()));
        assertEquals(started, event.get("Time"));
    }

    private void capture() {
        when(bus.putEvents(anyList(), eq(REGION))).thenAnswer(call -> {
            List<Map<String, Object>> entries = call.getArgument(0);
            received.add(entries.getFirst());
            return new EventBridgeService.PutEventsResult(0, List.of());
        });
    }

    private Map<String, Object> take() throws InterruptedException {
        Map<String, Object> result = received.poll(3, TimeUnit.SECONDS);
        assertNotNull(result, "Expected a queued event to reach the default bus");
        return result;
    }

    private static AutoScalingGroup group() {
        AutoScalingGroup group = new AutoScalingGroup();
        group.setAutoScalingGroupName("owned-group");
        group.setRegion(REGION);
        group.setAutoScalingGroupArn("arn:aws-cn:autoscaling:cn-north-1:" + ACCOUNT
                + ":autoScalingGroup:owned:autoScalingGroupName/owned-group");
        return group;
    }

    private static Instance instance() {
        Instance instance = new Instance();
        instance.setInstanceId("i-owned");
        instance.setOwnerId(ACCOUNT);
        instance.setRegion(REGION);
        instance.setState(InstanceState.running());
        instance.setSubnetId("subnet-owned");
        Placement placement = new Placement();
        placement.setAvailabilityZone("cn-north-1a");
        instance.setPlacement(placement);
        return instance;
    }

    private static ScalingActivity activity() {
        ScalingActivity activity = new ScalingActivity();
        activity.setActivityId("activity-owned");
        activity.setStartTime(NOW);
        activity.setCause("desired capacity change");
        return activity;
    }
}
