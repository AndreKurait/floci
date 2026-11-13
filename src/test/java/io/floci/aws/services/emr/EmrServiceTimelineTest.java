package io.floci.aws.services.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.emr.model.EmrCluster;
import io.floci.aws.services.emr.model.EmrInstanceGroup;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.ManualClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The instants EMR stamps and the one it measures a terminated cluster's instance retention against all
 * come from the service's clock, so they are compared on one timeline.
 */
class EmrServiceTimelineTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final Instant START = Instant.parse("2031-03-04T05:06:07.008Z");
    private static final Duration TERMINATED_INSTANCE_RETENTION = Duration.ofDays(30);

    private final ManualClock clock = new ManualClock(START);
    private final List<Runnable> wakeups = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EmrService service = newService();

    @Test
    void listInstances_terminatedCluster_listsItsInstancesUntilThirtyDaysAfterItEnded() {
        EmrCluster requested = new EmrCluster();
        requested.setName("retention");
        requested.setKeepJobFlowAliveWhenNoSteps(true);
        requested.setSteps(new ArrayList<>());
        requested.setInstanceGroups(new ArrayList<>(List.of(group("MASTER", 1), group("CORE", 2))));
        String id = service.runJobFlow(requested, REGION).getId();
        runWakeups();
        clock.advance(Duration.ofHours(1));
        service.terminateJobFlows(List.of(id));
        runWakeups();
        Instant ended = START.plus(Duration.ofHours(1));
        assertEquals("TERMINATED", service.describeCluster(id).getState());
        assertEquals(ended, service.describeCluster(id).getEndDateTime());

        clock.advance(TERMINATED_INSTANCE_RETENTION);
        assertEquals(3, listedInstances(id), "thirty days after it ended the instances are still listed");

        clock.advance(Duration.ofMillis(1));
        assertEquals(0, listedInstances(id), "one millisecond later they are gone");
    }

    @Test
    void securityConfigurationAndBlockPublicAccess_creationTimes_areReadFromTheClock() {
        assertEquals(START, service.createSecurityConfiguration("tl-sec", "{}").getCreationDateTime());

        clock.advance(Duration.ofMinutes(7));
        assertEquals(START.plus(Duration.ofMinutes(7)), service.getBlockPublicAccess(REGION).getCreationDateTime());

        clock.advance(Duration.ofMinutes(7));
        service.putBlockPublicAccess(REGION, objectMapper.createObjectNode()
                .put("BlockPublicSecurityGroupRules", false));
        assertEquals(START.plus(Duration.ofMinutes(14)), service.getBlockPublicAccess(REGION).getCreationDateTime());
    }

    private int listedInstances(String clusterId) {
        JsonNode answer = (JsonNode) new EmrHandler(service, objectMapper).handle("ListInstances",
                objectMapper.createObjectNode().put("ClusterId", clusterId)
                        .set("InstanceStates", objectMapper.createArrayNode().add("TERMINATED")),
                REGION).getEntity();
        return answer.path("Instances").size();
    }

    /** Runs the wakeups the service set, the ones they set in turn included, as its timer would once due. */
    private void runWakeups() {
        while (!wakeups.isEmpty()) {
            wakeups.remove(0).run();
        }
    }

    private EmrService newService() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().emr().defaultReleaseLabel()).thenReturn("emr-7.5.0");
        when(config.services().emr().clusterStartupDelaySeconds()).thenReturn(0);
        when(config.services().emr().stepDurationSeconds()).thenReturn(0);
        when(config.services().emr().clusterTerminationDelaySeconds()).thenReturn(0);
        when(config.services().emr().imageTemplate()).thenReturn(Optional.empty());
        when(config.services().emr().hiveImageTemplate()).thenReturn(Optional.empty());
        when(config.services().emr().dockerNetwork()).thenReturn(Optional.empty());
        return new EmrService(new InMemoryStorageFactory(ACCOUNT), config, new RegionResolver(REGION, ACCOUNT),
                mock(EmrEventPublisher.class), clock, (due, task) -> wakeups.add(task));
    }

    private static EmrInstanceGroup group(String type, int count) {
        EmrInstanceGroup group = new EmrInstanceGroup();
        group.setInstanceGroupType(type);
        group.setInstanceType("m5.xlarge");
        group.setRequestedInstanceCount(count);
        return group;
    }
}
