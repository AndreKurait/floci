package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.model.RedshiftEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftEventServiceTest {

    private MutableClock clock;
    private RedshiftEventService service;
    private StorageFactory factory;

    @Test
    void explicitRecordingUsesTheOwnersHistory() {
        String owner = "565656565656";
        String region = "us-west-2";

        service.recordForAccount(owner, region, RedshiftEventCatalog.SNAPSHOT_COMPLETED, "owner-snapshot", "completed");

        AccountAwareStorageBackend<RedshiftEvent> history = factory.create("redshift", "redshift-events.json",
                new TypeReference<Map<String, RedshiftEvent>>() { });
        assertEquals(1, history.scanForAccount(owner, key -> key.startsWith(region + "/")).size());
        assertTrue(service.describeEvents(null, null, null, null, null, null, null).items().isEmpty());
    }

    @Test
    void pruningRemovesExpiredHistoryAcrossAccountsAndRegionsDuringAlternatingTraffic() {
        AccountAwareStorageBackend<RedshiftEvent> backend = factory.create("redshift", "redshift-events.json",
                new TypeReference<Map<String, RedshiftEvent>>() { });
        Instant expired = clock.instant().minus(RedshiftEventService.RETENTION).minusSeconds(1);
        for (String scope : List.of("000000000000/us-east-1", "000000000000/us-west-2", "111111111111/us-east-1")) {
            RedshiftEvent event = new RedshiftEvent();
            event.setKey("expired");
            event.setDate(expired);
            int separator = scope.indexOf('/');
            backend.putForAccount(scope.substring(0, separator), scope.substring(separator + 1) + "/expired", event);
        }
        RedshiftEvent recent = new RedshiftEvent();
        recent.setKey("recent");
        recent.setDate(clock.instant());
        backend.putForAccount("111111111111", "us-west-2/recent", recent);
        AtomicReference<String> region = new AtomicReference<>("us-east-1");
        RegionResolver resolver = mock(RegionResolver.class);
        when(resolver.getRegion()).thenAnswer(invocation -> region.get());
        RedshiftEventService alternating = new RedshiftEventService(factory, resolver, clock);
        for (int index = 0; index < 200; index++) {
            region.set(index % 2 == 0 ? "us-east-1" : "us-west-2");
            alternating.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");
        }

        Map<String, RedshiftEvent> stored = backend.scanAllAccountsWithRawKeys();
        assertEquals(201, stored.size());
        assertTrue(stored.values().stream().noneMatch(event -> event.getDate().equals(expired)));
        assertTrue(stored.containsKey("111111111111/us-west-2/recent"));
    }

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("redshift")).thenReturn("memory");
        when(config.storage().persistentPath()).thenReturn("target/test-data");
        when(config.defaultAccountId()).thenReturn("000000000000");
        factory = new StorageFactory(config, access);
        clock = new MutableClock(Instant.parse("2026-10-26T12:00:00Z"));
        service = new RedshiftEventService(factory, new RegionResolver("us-east-1", "000000000000"), clock);
    }

    @Test
    void describeEventsDefaultsToTheLastHour() {
        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");
        clock.advance(Duration.ofMinutes(30));
        service.record(RedshiftEventCatalog.CLUSTER_REBOOTED, "warehouse", "rebooted");
        clock.advance(Duration.ofMinutes(45));

        assertEquals(List.of("REDSHIFT-EVENT-3000"), ids(service.describeEvents(null, null, null, null, null, null, null)));
        assertEquals(List.of("REDSHIFT-EVENT-2000", "REDSHIFT-EVENT-3000"),
                ids(service.describeEvents(null, null, null, null, 120, null, null)));
    }

    @Test
    void describeEventsFiltersBySourceAndTimeWindow() {
        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");
        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "analytics", "created");
        service.record(RedshiftEventCatalog.PARAMETER_GROUP_CREATED, "warehouse", "group created");
        Instant start = clock.instant().minusSeconds(60);
        Instant end = clock.instant().plusSeconds(60);

        List<RedshiftEvent> events = service.describeEvents("warehouse", "cluster", start, end, null, null, null).items();

        assertEquals(1, events.size());
        assertEquals("warehouse", events.getFirst().getSourceIdentifier());
        assertTrue(service.describeEvents(null, null, end.plusSeconds(1), end.plusSeconds(2), null, null, null)
                .items().isEmpty());
    }

    @Test
    void describeEventsRejectsAnUnpairedSourceAndOutOfRangePages() {
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> service.describeEvents("warehouse", null, null, null, null, null, null)).getErrorCode());
        assertEquals("InvalidParameterCombination", assertThrows(AwsException.class,
                () -> service.describeEvents(null, "cluster", null, null, null, null, null)).getErrorCode());
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> service.describeEvents(null, "database", null, null, null, null, null)).getErrorCode());
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> service.describeEvents(null, null, null, null, null, 19, null)).getErrorCode());
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> service.describeEvents(null, null, null, null, null, 101, null)).getErrorCode());
    }

    @Test
    void describeEventsPagesWithoutRepeatingOrSkipping() {
        for (int i = 0; i < 25; i++) {
            service.record(RedshiftEventCatalog.CLUSTER_CREATED, "cluster-" + i, "created " + i);
        }

        PaginatedResult<RedshiftEvent> first = service.describeEvents(null, null, null, null, null, 20, null);
        PaginatedResult<RedshiftEvent> second = service.describeEvents(null, null, null, null, null, 20,
                first.nextToken());

        assertEquals(20, first.items().size());
        assertEquals(5, second.items().size());
        assertNull(second.nextToken());
        assertEquals("cluster-0", first.items().getFirst().getSourceIdentifier());
        assertEquals("cluster-20", second.items().getFirst().getSourceIdentifier());
    }

    @Test
    void describeEventsReportsAtMostFourteenDays() {
        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");
        clock.advance(Duration.ofDays(13));
        assertEquals(1, service.describeEvents(null, null, null, null, 60 * 24 * 30, null, null).items().size());

        clock.advance(Duration.ofDays(2));

        assertTrue(service.describeEvents(null, null, null, null, 60 * 24 * 30, null, null).items().isEmpty());
    }

    @Test
    void describeEventCategoriesGroupsEventsBySourceType() {
        Map<String, List<RedshiftEventCatalog.EventInfo>> all = service.describeEventCategories(null);

        assertEquals(List.of("cluster", "cluster-parameter-group", "cluster-security-group", "cluster-snapshot",
                "scheduled-action"), List.copyOf(all.keySet()));
        assertTrue(all.get("cluster").stream().anyMatch(info -> "REDSHIFT-EVENT-2000".equals(info.id())));
        assertEquals(List.of("cluster-snapshot"), List.copyOf(service.describeEventCategories("cluster-snapshot").keySet()));
        assertTrue(service.describeEventCategories("scheduled-action").get("scheduled-action").isEmpty());
        assertEquals("InvalidParameterValue", assertThrows(AwsException.class,
                () -> service.describeEventCategories("database")).getErrorCode());
    }

    @Test
    void eventHistoryIsRegional() {
        service.record(RedshiftEventCatalog.CLUSTER_CREATED, "warehouse", "created");
        RedshiftEventService west = new RedshiftEventService(factory,
                new RegionResolver("us-west-2", "000000000000"), clock);

        assertTrue(west.describeEvents(null, null, null, null, null, null, null).items().isEmpty());
        assertEquals(1, service.describeEvents(null, null, null, null, null, null, null).items().size());
    }

    private static List<String> ids(PaginatedResult<RedshiftEvent> page) {
        return page.items().stream().map(RedshiftEvent::getEventId).toList();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
