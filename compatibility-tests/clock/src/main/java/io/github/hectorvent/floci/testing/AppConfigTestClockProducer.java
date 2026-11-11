package io.github.hectorvent.floci.testing;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.nio.file.Path;
import java.time.Clock;

/** Included only by the compat-clock Maven profile, never by a release build. */
@ApplicationScoped
public class AppConfigTestClockProducer {

    @Produces
    @Identifier("appconfig")
    public Clock appConfigClock() {
        String file = System.getenv("FLOCI_TEST_APPCONFIG_CLOCK_FILE");
        if (file == null || file.isBlank()) {
            return Clock.systemUTC();
        }
        FileClock clock = new FileClock(Path.of(file));
        clock.instant();
        return clock;
    }
}
