package io.github.hectorvent.floci.services.pipes;

import io.github.hectorvent.floci.testing.TestTime;
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
import static org.mockito.Mockito.withSettings;

/**
 * A {@link Vertx} whose periodic timers fire on a {@link TestTime}: first one interval after they
 * are set and then every interval, as Vert.x fires them, but only when the test advances the
 * timeline. Nothing else of Vert.x is stubbed.
 */
final class TimelineVertx {

    private TimelineVertx() {
    }

    static Vertx on(TestTime time) {
        Vertx vertx = mock(Vertx.class, withSettings().stubOnly());
        Map<Long, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
        AtomicLong ids = new AtomicLong();
        when(vertx.setPeriodic(anyLong(), any())).thenAnswer(call -> {
            long interval = call.getArgument(0);
            Handler<Long> tick = call.getArgument(1);
            long id = ids.incrementAndGet();
            timers.put(id, time.scheduler().scheduleAtFixedRate(
                    () -> tick.handle(id), interval, interval, TimeUnit.MILLISECONDS));
            return id;
        });
        when(vertx.cancelTimer(anyLong())).thenAnswer(call -> {
            ScheduledFuture<?> timer = timers.remove(call.<Long>getArgument(0));
            return timer != null && timer.cancel(false);
        });
        return vertx;
    }
}
