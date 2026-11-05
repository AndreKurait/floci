package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.RedshiftEventCatalog.EventInfo;
import io.github.hectorvent.floci.services.redshift.model.RedshiftEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Records the events Floci raises for Redshift resources and serves DescribeEvents and
 * DescribeEventCategories from that history.
 */
@ApplicationScoped
public class RedshiftEventService {

    private static final Logger LOG = Logger.getLogger(RedshiftEventService.class);

    /** DescribeEvents reports the past 14 days at most. */
    static final Duration RETENTION = Duration.ofDays(14);
    private static final long DEFAULT_WINDOW_MINUTES = 60;
    private static final int PAGE_MIN = 20;
    private static final int PAGE_MAX = 100;
    private static final int PRUNE_EVERY = 200;

    private final AccountAwareStorageBackend<RedshiftEvent> events;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();

    @Inject
    public RedshiftEventService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory, regionResolver, Clock.systemUTC());
    }

    RedshiftEventService(StorageFactory storageFactory, RegionResolver regionResolver, Clock clock) {
        this.events = storageFactory.create("redshift", "redshift-events.json",
                new TypeReference<Map<String, RedshiftEvent>>() { });
        this.regionResolver = regionResolver;
        this.clock = clock;
    }

    // ── Recording ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Never fails the operation that raised the event: a lost event is logged, not surfaced. */
    public void record(EventInfo info, String sourceIdentifier, String message) {
        recordForAccount(events.accountId(), regionResolver.getRegion(), info, sourceIdentifier, message);
    }

    public void recordForAccount(String accountId, String region, EventInfo info, String sourceIdentifier, String message) {
        try {
            Instant now = clock.instant();
            long eventSequence = sequence.incrementAndGet();
            RedshiftEvent event = new RedshiftEvent();
            event.setKey(String.format(Locale.ROOT, "%019d-%010d-%s", now.toEpochMilli(),
                    eventSequence, UUID.randomUUID().toString().substring(0, 8)));
            event.setDate(now);
            event.setSeverity(info.severity());
            event.setEventId(info.id());
            event.setSourceIdentifier(sourceIdentifier);
            event.setSourceType(info.sourceType());
            event.setEventCategories(new ArrayList<>(List.of(info.category())));
            event.setMessage(message);
            events.putForAccount(accountId, key(region, event.getKey()), event);
            if (eventSequence % PRUNE_EVERY == 0) {
                prune(now);
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not record Redshift event {0} for {1}", info.id(), sourceIdentifier);
        }
    }

    private void prune(Instant now) {
        Instant floor = now.minus(RETENTION);
        for (Map.Entry<String, RedshiftEvent> entry : events.scanAllAccountsWithRawKeys().entrySet()) {
            if (entry.getValue().getDate().isBefore(floor)) {
                events.deleteRaw(entry.getKey());
            }
        }
    }

    // ── DescribeEvents / DescribeEventCategories ─────────────────────────────

    public PaginatedResult<RedshiftEvent> describeEvents(String sourceIdentifier, String sourceType, Instant startTime,
                                                         Instant endTime, Integer duration, Integer maxRecords,
                                                         String marker) {
        validatePageSize(maxRecords);
        boolean hasType = sourceType != null && !sourceType.isBlank();
        boolean hasId = sourceIdentifier != null && !sourceIdentifier.isBlank();
        if (hasType) {
            requireSourceType(sourceType);
        }
        if (hasType != hasId) {
            throw new AwsException("InvalidParameterCombination",
                    "SourceType and SourceIdentifier must be provided together.", 400);
        }
        if (duration != null && duration < 1) {
            throw new AwsException("InvalidParameterValue", "Duration must be at least 1 minute.", 400);
        }
        Instant now = clock.instant();
        Instant from;
        Instant to;
        if (startTime != null || endTime != null) {
            from = startTime != null ? startTime : now.minus(RETENTION);
            to = endTime != null ? endTime : now;
        } else {
            from = now.minus(Duration.ofMinutes(duration != null ? duration : DEFAULT_WINDOW_MINUTES));
            to = now;
        }
        if (from.isAfter(to)) {
            throw new AwsException("InvalidParameterValue", "StartTime must not be after EndTime.", 400);
        }
        Instant floor = now.minus(RETENTION);
        Instant start = from.isBefore(floor) ? floor : from;
        List<RedshiftEvent> matching = events.scan(this::inRegion).stream()
                .filter(event -> !event.getDate().isBefore(start) && !event.getDate().isAfter(to))
                .filter(event -> !hasType || sourceType.equals(event.getSourceType())
                        && sourceIdentifier.equals(event.getSourceIdentifier()))
                .sorted(Comparator.comparing(RedshiftEvent::getKey))
                .toList();
        return Pagination.paginate(matching, RedshiftEvent::getKey, maxRecords, marker, PAGE_MAX, PAGE_MAX,
                "InvalidParameterValue", "Marker");
    }

    /** The emitted events grouped by source type; a source type that raises none still appears, empty. */
    public Map<String, List<EventInfo>> describeEventCategories(String sourceType) {
        if (sourceType != null && !sourceType.isBlank()) {
            requireSourceType(sourceType);
        }
        Map<String, List<EventInfo>> byType = new LinkedHashMap<>();
        for (String type : List.of(RedshiftEventCatalog.CLUSTER, RedshiftEventCatalog.PARAMETER_GROUP,
                RedshiftEventCatalog.SECURITY_GROUP, RedshiftEventCatalog.SNAPSHOT,
                RedshiftEventCatalog.SCHEDULED_ACTION)) {
            if (sourceType == null || sourceType.isBlank() || sourceType.equals(type)) {
                byType.put(type, RedshiftEventCatalog.ALL.stream()
                        .filter(info -> info.sourceType().equals(type)).toList());
            }
        }
        return byType;
    }

    private static void requireSourceType(String sourceType) {
        if (!RedshiftEventCatalog.SOURCE_TYPES.contains(sourceType)) {
            throw new AwsException("InvalidParameterValue", "SourceType must be one of "
                    + RedshiftEventCatalog.SOURCE_TYPES.stream().sorted().toList() + ".", 400);
        }
    }

    private static void validatePageSize(Integer maxRecords) {
        if (maxRecords != null && (maxRecords < PAGE_MIN || maxRecords > PAGE_MAX)) {
            throw new AwsException("InvalidParameterValue",
                    "MaxRecords must be between " + PAGE_MIN + " and " + PAGE_MAX + ".", 400);
        }
    }

    private static String key(String region, String name) {
        return region + "/" + name;
    }

    private boolean inRegion(String storedKey) {
        return storedKey.startsWith(regionResolver.getRegion() + "/");
    }
}
