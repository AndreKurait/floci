package io.floci.aws.services.scheduler;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.scheduler.model.Schedule;
import io.floci.aws.services.scheduler.model.Target;
import io.floci.aws.services.sqs.SqsService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The dispatcher in real time: its own thread and the system clock fire a schedule that is due.
 * {@code ScheduleDispatcherTest} covers when it ticks and what a tick does, on a controlled timeline.
 */
class ScheduleDispatcherWiringTest {

    @Test
    void aDueScheduleIsFiredByTheDispatchersOwnThread() {
        Instant due = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
        Schedule schedule = new Schedule();
        schedule.setName("wired");
        schedule.setGroupName("default");
        schedule.setArn("arn:aws:scheduler:eu-central-1:000000000000:schedule/default/wired");
        schedule.setState("ENABLED");
        schedule.setScheduleExpression("at("
                + DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneOffset.UTC).format(due) + ")");
        Target target = new Target();
        target.setArn("arn:aws:sqs:eu-central-1:000000000000:test-queue");
        target.setRoleArn("arn:aws:iam::000000000000:role/test");
        schedule.setTarget(target);
        schedule.setCreationDate(due.minusSeconds(60));
        SchedulerService schedulerService = mock(SchedulerService.class);
        when(schedulerService.listAllSchedules()).thenReturn(List.of(schedule));
        ScheduleInvoker invoker = mock(ScheduleInvoker.class);
        AtomicReference<String> firedOn = new AtomicReference<>();
        doAnswer(call -> {
            firedOn.set(Thread.currentThread().getName());
            return null;
        }).when(invoker).invoke(any(), any(), any(), anyInt());
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        when(config.services().scheduler().enabled()).thenReturn(true);
        when(config.services().scheduler().invocationEnabled()).thenReturn(true);
        when(config.services().scheduler().tickIntervalSeconds()).thenReturn(1L);
        ScheduleDispatcher dispatcher = new ScheduleDispatcher(schedulerService, invoker,
                mock(SqsService.class), config);

        dispatcher.onStart(null);
        try {
            verify(invoker, timeout(30_000)).invoke(eq(schedule), eq(due), any(), anyInt());
            assertEquals("scheduler-dispatcher", firedOn.get());
        } finally {
            dispatcher.onStop(null);
        }
    }
}
