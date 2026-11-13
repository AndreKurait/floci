package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ec2.model.SpotPrice;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.testing.Ec2ServiceFixtures;
import io.github.hectorvent.floci.testing.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every date {@link Ec2Service} stamps or decides from comes from its injected clock, so each test
 * here moves a {@link ManualClock} and never waits.
 */
class Ec2ServiceTimelineTest {

    private static final String REGION = "us-east-1";
    private static final Instant START = Instant.parse("2031-03-04T05:06:07.890Z");

    private ManualClock clock;
    private Ec2Service service;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(START);
        service = Ec2ServiceFixtures.mockModeService(clock);
    }

    @Test
    void terminatedInstanceIsPrunedOnlyOnceMoreThanAnHourHasPassed() {
        Instance instance = launch();
        clock.advance(Duration.ofMinutes(7));
        service.terminateInstances(REGION, List.of(instance.getInstanceId()));
        assertEquals(START.plus(Duration.ofMinutes(7)).toEpochMilli(), instance.getTerminatedAt());

        clock.advance(Duration.ofHours(1));
        service.pruneTerminatedInstances();
        assertThat("an instance terminated exactly an hour ago is still describable",
                describedIds(instance), contains(instance.getInstanceId()));

        clock.advance(Duration.ofMillis(1));
        service.pruneTerminatedInstances();
        AwsException gone = assertThrows(AwsException.class, () -> describedIds(instance));
        assertEquals("InvalidInstanceID.NotFound", gone.getErrorCode());
    }

    @Test
    void launchAndCreationTimesAreReadFromTheClock() {
        Instance instance = launch();
        assertEquals(START, instance.getLaunchTime());

        clock.advance(Duration.ofSeconds(90));
        Volume volume = service.createVolume(REGION, "us-east-1a", "gp3", 8, false, 0, null, null, List.of());
        assertEquals(START.plusSeconds(90), volume.getCreateTime());

        clock.advance(Duration.ofDays(400));
        KeyPair keyPair = service.createKeyPair(REGION, "timeline-key");
        assertEquals(START.plusSeconds(90).plus(Duration.ofDays(400)), keyPair.getCreateTime());
    }

    @Test
    void spotPriceHistoryStartingAfterNowIsEmptyAndStartingNowIsNot() {
        List<SpotPrice> future = spotPrices(START.plusMillis(1));
        assertThat(future, is(empty()));

        List<SpotPrice> current = spotPrices(START);
        assertThat(current, is(not(empty())));
        assertThat(current.stream().map(SpotPrice::timestamp).toList(), everyItem(is(START)));

        clock.advance(Duration.ofMillis(1));
        assertThat("the start time that was a millisecond ahead has now been reached",
                spotPrices(START.plusMillis(1)), is(not(empty())));
    }

    @Test
    void spotPriceHistoryWithoutAWindowIsStampedWithTheCurrentHour() {
        List<SpotPrice> prices = spotPrices(null);

        assertThat(prices, is(not(empty())));
        assertThat(prices.stream().map(SpotPrice::timestamp).toList(),
                everyItem(is(START.truncatedTo(ChronoUnit.HOURS))));
    }

    @Test
    void capacityReservationSchedulesAreMeasuredFromTheClock() {
        assertEquals(START, service.capacityReservationNow());

        clock.advance(Duration.ofDays(5));

        assertEquals(START.plus(Duration.ofDays(5)), service.capacityReservationNow());
    }

    private Instance launch() {
        return service.runInstances(REGION, "ami-test", "t3.micro", 1, 1, null, List.of(), null, null,
                List.of(), null, null).getInstances().getFirst();
    }

    private List<String> describedIds(Instance instance) {
        return service.describeInstances(REGION, List.of(instance.getInstanceId()), Map.of()).stream()
                .flatMap(reservation -> reservation.getInstances().stream())
                .map(Instance::getInstanceId)
                .toList();
    }

    private List<SpotPrice> spotPrices(Instant startTime) {
        return service.describeSpotPriceHistory(REGION, List.of("t3.micro"), List.of(), "us-east-1a", null,
                startTime, null, Map.<String, List<String>>of(), 0, null).spotPrices();
    }
}
