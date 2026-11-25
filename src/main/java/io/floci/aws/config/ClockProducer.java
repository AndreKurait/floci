package io.floci.aws.config;

import io.quarkus.arc.DefaultBean;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import java.time.Clock;

@ApplicationScoped
public class ClockProducer {

    @Produces
    @ApplicationScoped
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Produces
    @DefaultBean
    @Identifier("appconfig")
    public Clock appConfigClock() {
        return Clock.systemUTC();
    }
}
