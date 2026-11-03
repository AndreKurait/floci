package io.github.hectorvent.floci.config;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class AutoScalingStartupValidator {

    private final EmulatorConfig config;

    @Inject
    public AutoScalingStartupValidator(EmulatorConfig config) {
        this.config = config;
    }

    void onStart(@Observes StartupEvent ignored) {
        if (config.services().autoscaling().maxCapacityReservationIds() < 1) {
            throw new IllegalArgumentException(
                    "floci.services.autoscaling.max-capacity-reservation-ids must be positive");
        }
    }
}
