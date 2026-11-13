package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.testing.TestTime;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.ServerWebSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The 10-minute idle timeout and the 2-hour maximum connection duration, checked once a minute,
 * driven on a controlled timeline with the configured durations.
 */
class WebSocketHandlerIdleTimeoutTest {
    private static final String CONNECTION = "connection";
    private static final Duration CHECK_PERIOD = Duration.ofMinutes(1);
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration MAX_DURATION = Duration.ofHours(2);
    private static final Duration TICK = Duration.ofMillis(1);
    private static final long TIMER_ID = 7L;

    private final TestTime time = TestTime.create();
    private final Vertx vertx = mock(Vertx.class);
    private final ServerWebSocket socket = mock(ServerWebSocket.class);
    private final WebSocketConnectionManager connections = new WebSocketConnectionManager(time.clock());
    private final List<Long> periods = new ArrayList<>();
    private ScheduledFuture<?> timer;
    private WebSocketHandler handler;

    @BeforeEach
    void setUp() {
        // Vert.x timers are stood in for by the timeline's scheduler, with the period the handler asks for
        when(vertx.setPeriodic(anyLong(), any())).thenAnswer(invocation -> {
            long period = invocation.getArgument(0);
            Handler<Long> check = invocation.getArgument(1);
            periods.add(period);
            timer = time.scheduler().scheduleAtFixedRate(() -> check.handle(TIMER_ID),
                    period, period, TimeUnit.MILLISECONDS);
            return TIMER_ID;
        });
        when(vertx.cancelTimer(TIMER_ID)).thenAnswer(invocation -> timer.cancel(false));
        handler = new WebSocketHandler(null, connections, null, null, null, null,
                mock(RegionResolver.class), mock(EmulatorConfig.class), new ObjectMapper(), vertx, null, null,
                time.clock());
        long connectedAt = time.clock().millis();
        connections.register(CONNECTION, new ConnectionInfo(CONNECTION, "api", "prod", "us-east-1",
                connectedAt, connectedAt, "127.0.0.1", ""), socket);
        scheduleIdleTimeout(connectedAt);
    }

    @Test
    void connectionIsCheckedOnceAMinute() {
        assertThat(periods, contains(CHECK_PERIOD.toMillis()));
    }

    @Test
    void idleConnectionIsClosedByTheFirstCheckPastTenMinutes() {
        // The check at exactly ten minutes sees an idle time that is not yet over the limit
        time.advance(IDLE_TIMEOUT.plus(CHECK_PERIOD).minus(TICK));
        verify(socket, never()).close();

        time.advance(TICK);
        verify(socket).close();
        assertThat(time.pendingTasks(), is(0));
    }

    @Test
    void activityRestartsTheIdleTimeout() {
        Duration activeAt = Duration.ofMinutes(9);
        time.advance(activeAt);
        connections.updateLastActiveAt(CONNECTION);

        time.advance(IDLE_TIMEOUT.plus(CHECK_PERIOD).minus(TICK));
        verify(socket, never()).close();

        time.advance(TICK);
        verify(socket).close();
    }

    @Test
    void activeConnectionIsClosedByTheFirstCheckPastTwoHours() {
        Duration activityInterval = Duration.ofMinutes(5);
        for (Duration elapsed = Duration.ZERO; elapsed.compareTo(MAX_DURATION) < 0;
                elapsed = elapsed.plus(activityInterval)) {
            time.advance(activityInterval);
            connections.updateLastActiveAt(CONNECTION);
        }
        // The check at exactly two hours sees a duration that is not yet over the limit
        time.advance(CHECK_PERIOD.minus(TICK));
        verify(socket, never()).close();

        time.advance(TICK);
        verify(socket).close();
        assertThat(time.pendingTasks(), is(0));
    }

    @Test
    void checksStopOnceTheConnectionIsGone() {
        connections.unregister(CONNECTION);

        time.advance(CHECK_PERIOD);

        assertThat(time.pendingTasks(), is(0));
        verify(socket, never()).close();
    }

    private void scheduleIdleTimeout(long connectedAt) {
        try {
            Method method = WebSocketHandler.class.getDeclaredMethod("scheduleIdleTimeout", String.class, long.class);
            method.setAccessible(true);
            method.invoke(handler, CONNECTION, connectedAt);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
