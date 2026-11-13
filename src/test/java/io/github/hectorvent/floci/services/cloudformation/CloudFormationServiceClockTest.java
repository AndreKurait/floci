package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.cloudformation.model.ChangeSet;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.ManualClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudFormationServiceClockTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String STACK = "clocked";
    private static final String TEMPLATE = "{\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}";
    private static final Instant START = Instant.parse("2031-05-06T07:08:09Z");

    private ManualClock clock;
    private CloudFormationService service;

    @BeforeEach
    void setUp() {
        CfnResourceDispatcher provisioner = mock(CfnResourceDispatcher.class);
        when(provisioner.referenceValue(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(provisioner.provision(anyString(), anyString(), any(), any(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                    StackResource resource = new StackResource();
                    resource.setLogicalId(invocation.getArgument(0));
                    resource.setResourceType(invocation.getArgument(1));
                    resource.setPhysicalId(invocation.getArgument(0) + "-id");
                    resource.setStatus("CREATE_COMPLETE");
                    return resource;
                });
        clock = new ManualClock(START);
        service = CloudFormationServiceFixture.create(provisioner, mock(S3Service.class), clock,
                new InMemoryStorageFactory(ACCOUNT));
    }

    @AfterEach
    void stopService() {
        service.stop();
    }

    @Test
    void aChangeSetItsEventsAndItsResourcesAreStampedFromTheInjectedClock() throws Exception {
        ChangeSet changeSet = service.createChangeSet(STACK, "create", "CREATE", TEMPLATE, null,
                Map.of(), List.of(), Map.of(), REGION, ACCOUNT);
        assertEquals(START, changeSet.getCreationTime());

        clock.advance(Duration.ofMinutes(5));
        service.executeChangeSet(STACK, "create", REGION, ACCOUNT).get(30, TimeUnit.SECONDS);
        Instant executed = START.plus(Duration.ofMinutes(5));

        List<StackEvent> events = service.describeStackEvents(STACK, REGION);
        assertThat(events, is(not(empty())));
        for (StackEvent event : events) {
            String what = event.getLogicalResourceId() + " " + event.getResourceStatus();
            assertFalse(event.getTimestamp().isBefore(START), what);
            assertFalse(event.getTimestamp().isAfter(executed), what);
        }
        List<StackResource> resources = service.describeStackResources(STACK, REGION);
        assertEquals(1, resources.size());
        assertEquals(executed, resources.get(0).getTimestamp());
    }
}
