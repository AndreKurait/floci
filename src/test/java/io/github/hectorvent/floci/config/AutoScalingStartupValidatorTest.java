package io.github.hectorvent.floci.config;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoScalingStartupValidatorTest {

    @Test
    void environmentMappingPreservesWhitespaceAndNonpositiveLimitForConsumerValidation() {
        String prefix = "floci.services.autoscaling";
        EmulatorConfig.AutoScalingServiceConfig mapped = new SmallRyeConfigBuilder()
                .withMapping(EmulatorConfig.AutoScalingServiceConfig.class, prefix)
                .withSources(new EnvConfigSource(Map.of(
                        "FLOCI_SERVICES_AUTOSCALING_MULTIPLE_CAPACITY_RESERVATION_SCOPES",
                        "123456789012/us-east-1, 123456789012/us-west-2 ",
                        "FLOCI_SERVICES_AUTOSCALING_MAX_CAPACITY_RESERVATION_IDS", "0"), 300))
                .build().getConfigMapping(EmulatorConfig.AutoScalingServiceConfig.class, prefix);
        assertEquals(List.of("123456789012/us-east-1", " 123456789012/us-west-2 "),
                mapped.multipleCapacityReservationScopes().orElseThrow());
        assertEquals(0, mapped.maxCapacityReservationIds());
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().autoscaling()).thenReturn(mapped);
        assertThrows(IllegalArgumentException.class,
                () -> new AutoScalingStartupValidator(config).onStart(new StartupEvent()));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0})
    void nonpositiveLimitIsRejectedByStartupObserver(int value) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> validator(value).onStart(new StartupEvent()));
        assertEquals("floci.services.autoscaling.max-capacity-reservation-ids must be positive",
                error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, Integer.MAX_VALUE})
    void positiveLimitIsAcceptedByStartupObserver(int value) {
        assertDoesNotThrow(() -> validator(value).onStart(new StartupEvent()));
    }

    private static AutoScalingStartupValidator validator(int value) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().autoscaling().maxCapacityReservationIds()).thenReturn(value);
        return new AutoScalingStartupValidator(config);
    }
}
