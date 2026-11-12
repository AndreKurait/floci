package io.floci.aws.services.lambda;

import io.floci.aws.testing.TestTime;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A {@link Vertx} whose periodic timers run on a {@link TestTime} timeline, at the period the code asked for. */
public final class TimelineVertx {

    private TimelineVertx() {
    }

    /** Timers set on the returned mock fire inside {@code time.advance}, on the advancing thread. */
    public static Vertx timersOn(TestTime time) {
        Vertx vertx = mock(Vertx.class);
        AtomicLong ids = new AtomicLong();
        Map<Long, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
        when(vertx.setPeriodic(anyLong(), any())).thenAnswer(invocation -> {
            long period = invocation.getArgument(0);
            Handler<Long> handler = invocation.getArgument(1);
            long id = ids.incrementAndGet();
            timers.put(id, time.scheduler().scheduleAtFixedRate(() -> handler.handle(id),
                    period, period, TimeUnit.MILLISECONDS));
            return id;
        });
        when(vertx.cancelTimer(anyLong())).thenAnswer(invocation -> {
            ScheduledFuture<?> timer = timers.remove(invocation.<Long>getArgument(0));
            return timer != null && timer.cancel(false);
        });
        return vertx;
    }
}
