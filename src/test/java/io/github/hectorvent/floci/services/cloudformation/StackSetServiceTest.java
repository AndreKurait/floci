package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackSet;
import io.github.hectorvent.floci.services.cloudformation.model.StackSetOperation;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StackSetServiceTest {

    private static final String SET = "ops-set";
    private static final String ACCOUNT = "222222222222";
    private static final String REGION = "us-east-1";
    private static final String TEMPLATE = "{\"Resources\":{}}";
    private static final Instant START = Instant.parse("2031-05-06T07:08:09Z");

    private ManualClock clock;
    private StackSetService service;

    @BeforeEach
    void setUp() {
        CloudFormationService cfnService = mock(CloudFormationService.class);
        when(cfnService.executeChangeSet(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(cfnService.deleteStack(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Stack created = new Stack();
        created.setStackName("StackSet-" + SET + "-" + ACCOUNT);
        created.setStackId("stack-id");
        created.setStatus("CREATE_COMPLETE");
        when(cfnService.describeStacks(anyString(), anyString())).thenReturn(List.of(created));

        clock = new ManualClock(START);
        service = new StackSetService(cfnService, new InMemoryStorageFactory("000000000000"), null,
                new RegionResolver("us-east-1", "000000000000"), clock);
        service.createStackSet(SET, TEMPLATE, null, null, null, "original");
    }

    @Test
    void aRefusedUpdateLeavesTheStackSetAndItsOperationsUntouched() {
        String otherTemplate = "{\"Resources\":{},\"Description\":\"changed\"}";

        AwsException refused = assertThrows(AwsException.class, () -> service.updateStackSet(
                SET, otherTemplate, null, null, null, "changed", null, Boolean.TRUE, null, null));

        assertEquals("InvalidOperationException", refused.getErrorCode());
        StackSet after = service.describeStackSet(SET);
        assertEquals(TEMPLATE, after.getTemplateBody());
        assertEquals("original", after.getDescription());
        assertThat(service.listStackSetOperations(SET), is(empty()));
    }

    @Test
    void operationTimestampsComeFromTheInjectedClock() {
        StackSetOperation create = service.createStackInstances(SET, List.of(ACCOUNT), List.of(REGION));
        assertEquals(START, create.getCreationTimestamp());
        assertEquals(START, create.getEndTimestamp());

        clock.advance(Duration.ofSeconds(90));
        StackSetOperation update = service.updateStackSet(SET, null, null, null, null, "later");
        assertEquals(START.plusSeconds(90), update.getCreationTimestamp());
        assertEquals(START.plusSeconds(90), update.getEndTimestamp());

        clock.advance(Duration.ofSeconds(1));
        StackSetOperation delete = service.deleteStackInstances(SET, List.of(ACCOUNT), List.of(REGION), false);
        assertEquals(START.plusSeconds(91), delete.getCreationTimestamp());
        assertEquals(START.plusSeconds(91), delete.getEndTimestamp());

        // Newest first, by the timeline the operations were stamped on.
        assertThat(service.listStackSetOperations(SET).stream().map(StackSetOperation::getAction).toList(),
                contains("DELETE", "UPDATE", "CREATE"));
    }
}
