package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ssm.model.Command;
import io.github.hectorvent.floci.services.ssm.model.CommandInvocation;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import io.github.hectorvent.floci.testing.ManualClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SsmCommandDeliveryTimeoutTest {

    private static final String REGION = "us-east-1";
    private static final String INSTANCE = "i-0123456789abcdef0";
    private static final Instant START = Instant.parse("2031-05-06T07:08:09Z");
    private static final int TIMEOUT_SECONDS = 30;
    private static final int EXECUTION_TIMEOUT_SECONDS = 60;

    private final ObjectMapper mapper = new ObjectMapper();
    private ManualClock clock;
    private SsmCommandService service;

    @BeforeEach
    void managedNode() {
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getAccountId()).thenReturn("000000000000");
        when(regions.getRegion()).thenReturn(REGION);
        clock = new ManualClock(START);
        service = new SsmCommandService(new InMemoryStorageFactory("000000000000"), mapper, regions,
                mock(SsmDirectCommandExecutor.class), mock(SsmService.class), false, clock);
        service.updateInstanceInformation(mapper.createObjectNode().put("InstanceId", INSTANCE), REGION);
    }

    @AfterEach
    void stop() {
        service.stop();
    }

    @Test
    void anUndeliveredCommandTimesOutExactlyWhenItsDeliveryAndExecutionAllowanceEnds() {
        ObjectNode request = mapper.createObjectNode()
                .put("DocumentName", "AWS-RunShellScript").put("TimeoutSeconds", TIMEOUT_SECONDS);
        request.putArray("InstanceIds").add(INSTANCE);
        ObjectNode parameters = request.putObject("Parameters");
        parameters.putArray("commands").add("echo hello");
        parameters.putArray("executionTimeout").add(Integer.toString(EXECUTION_TIMEOUT_SECONDS));

        Command command = service.sendCommand(request, REGION);
        Instant deadline = START.plusSeconds(TIMEOUT_SECONDS + EXECUTION_TIMEOUT_SECONDS);
        assertEquals(START, command.getRequestedDateTime());
        assertEquals(deadline, command.getExpiresAfter());

        clock.advance(Duration.ofSeconds(TIMEOUT_SECONDS + EXECUTION_TIMEOUT_SECONDS).minusNanos(1));
        CommandInvocation waiting = service.getCommandInvocation(command.getCommandId(), INSTANCE, REGION);
        assertEquals("Pending", waiting.getStatus());
        assertNull(waiting.getExecutionEndDateTime());

        clock.advance(Duration.ofNanos(1));
        CommandInvocation expired = service.getCommandInvocation(command.getCommandId(), INSTANCE, REGION);
        assertEquals("TimedOut", expired.getStatus());
        assertEquals("Delivery Timed Out", expired.getStatusDetails());
        assertEquals(deadline, expired.getExecutionEndDateTime());
    }

    @Test
    void aManagedNodeIsRegisteredAndPingedOnTheInjectedClock() {
        assertEquals(START, service.describeInstanceInformation(REGION).getFirst().getRegistrationDate());

        clock.advance(Duration.ofMinutes(5));
        service.updateInstanceInformation(mapper.createObjectNode().put("InstanceId", INSTANCE), REGION);

        assertEquals(START, service.describeInstanceInformation(REGION).getFirst().getRegistrationDate());
        assertEquals(START.plus(Duration.ofMinutes(5)),
                service.describeInstanceInformation(REGION).getFirst().getLastPingDateTime());
    }
}
