package io.floci.aws.services.dynamodb;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.floci.aws.services.cloudwatch.metrics.model.Dimension;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Native request consumption and periodic capacity snapshots, published after DynamoDB locks are released. */
@ApplicationScoped
public class DynamoDbCapacityMetrics implements Resettable {
    private static final Logger LOG = Logger.getLogger(DynamoDbCapacityMetrics.class);
    private static final String NAMESPACE = "AWS/DynamoDB";
    private static final String READ = "ConsumedReadCapacityUnits";
    private static final String WRITE = "ConsumedWriteCapacityUnits";
    private static final int MAX_PENDING = 4096;
    private static final int MAX_RESOURCES = 10_000;
    private static final int MAX_MINUTES = 100_000;
    private static final long RETRY_SECONDS = 3600;

    private final DynamoDbService dynamo;
    private final CloudWatchMetricsService metrics;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final boolean enabled;
    private final String producerId = UUID.randomUUID().toString();
    private final Map<Resource, Observed> resources = new LinkedHashMap<>();
    private final Map<Minute, Boolean> minutes = new LinkedHashMap<>();
    private final Map<String, Publication> pending = new LinkedHashMap<>();
    private long generation;
    private long suppressIdleThrough = Long.MIN_VALUE;
    private long lastRetentionHour = Long.MIN_VALUE;
    private long failures;
    private boolean started;
    private boolean paused;
    private boolean closed;

    @Inject
    public DynamoDbCapacityMetrics(DynamoDbService dynamo, CloudWatchMetricsService metrics, EmulatorConfig config) {
        this(dynamo, metrics, Clock.systemUTC(), Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "dynamodb-capacity-metrics");
            thread.setDaemon(true);
            return thread;
        }), config.services().dynamodb().enabled() && config.services().cloudwatchmetrics().enabled()
                && "native".equalsIgnoreCase(config.services().dynamodb().backend().trim()));
    }

    DynamoDbCapacityMetrics(DynamoDbService dynamo, CloudWatchMetricsService metrics, Clock clock,
                            ScheduledExecutorService scheduler, boolean enabled) {
        this.dynamo = dynamo;
        this.metrics = metrics;
        this.clock = clock;
        this.scheduler = scheduler;
        this.enabled = enabled;
    }

    public synchronized void start() {
        if (enabled && !started && !closed) {
            started = true;
            scheduler.scheduleAtFixedRate(this::tick, 1, 10, TimeUnit.SECONDS);
        }
    }

    void record(DynamoDbCapacityUsage usage) {
        long admitted;
        synchronized (this) {
            if (!enabled || paused || closed) {
                return;
            }
            admitted = generation;
        }
        // This table read is deliberately outside the publisher monitor. No metric call holds an item lock.
        DynamoDbService.CapacityOwner owner;
        try {
            owner = dynamo.currentCapacityOwner(usage);
        } catch (RuntimeException failure) {
            warn(failure);
            return;
        }
        synchronized (this) {
            if (paused || closed || generation != admitted || !owner.current()
                    || usage.timestamp().isBefore(clock.instant().minusSeconds(RETRY_SECONDS))) {
                return;
            }
            Resource table = new Resource(usage.accountId(), usage.region(), usage.tableName(), usage.tableId(), null);
            consume(table, READ, usage.tableRead(), usage);
            consume(table, WRITE, usage.tableWrite(), usage);
            usage.indexReads().forEach((index, units) -> {
                if (owner.indexes().contains(index)) {
                    consume(table.index(index), READ, units, usage);
                }
            });
            usage.indexWrites().forEach((index, units) -> {
                if (owner.indexes().contains(index)) {
                    consume(table.index(index), WRITE, units, usage);
                }
            });
        }
    }

    private void consume(Resource resource, String metric, double units, DynamoDbCapacityUsage usage) {
        if (!(units > 0) || !Double.isFinite(units)) {
            return;
        }
        long minute = minute(usage.timestamp());
        Minute key = new Minute(resource, metric, minute);
        Boolean prior = minutes.get(key);
        String id;
        if (prior == null && minutes.size() >= MAX_MINUTES) {
            // Under overload skip idle claims, never infer that an untracked request minute was idle.
            suppressIdleThrough = Math.max(suppressIdleThrough, minute);
            id = resource.id() + ":" + metric + ":" + usage.publicationId();
        } else {
            minutes.put(key, true);
            // A late first contribution replaces this minute's zero at the exact same storage key.
            id = !Boolean.TRUE.equals(prior) ? key.id()
                    : resource.id() + ":" + metric + ":" + usage.publicationId();
        }
        publish(new Publication(resource, metric, minute, units, id, clock.instant()));
    }

    void tick() {
        long admitted;
        synchronized (this) {
            if (!enabled || paused || closed) {
                return;
            }
            admitted = generation;
        }
        List<DynamoDbService.CapacitySnapshot> snapshots;
        try {
            snapshots = dynamo.capacitySnapshots();
        } catch (RuntimeException failure) {
            warn(failure);
            return;
        }
        synchronized (this) {
            if (paused || closed || admitted != generation) {
                return;
            }
            Instant now = clock.instant();
            long currentMinute = minute(now);
            minutes.keySet().removeIf(key -> key.minute() < currentMinute - RETRY_SECONDS);
            Set<Resource> current = new HashSet<>();
            for (DynamoDbService.CapacitySnapshot snapshot : snapshots) {
                Resource resource = new Resource(snapshot.accountId(), snapshot.region(), snapshot.tableName(),
                        snapshot.tableId(), snapshot.indexName());
                current.add(resource);
                Observed observed = resources.get(resource);
                if (observed == null) {
                    if (resources.size() >= MAX_RESOURCES) {
                        continue;
                    }
                    // Discovery supplies no evidence about the incomplete minute that preceded it.
                    long firstMinute = now.equals(Instant.ofEpochSecond(currentMinute))
                            ? currentMinute : currentMinute + 60;
                    observed = new Observed(firstMinute);
                    resources.put(resource, observed);
                }
                observed.nextIdleMinute = Math.max(observed.nextIdleMinute, currentMinute - RETRY_SECONDS);
                while (observed.nextIdleMinute < currentMinute) {
                    idle(resource, READ, observed.nextIdleMinute, now);
                    idle(resource, WRITE, observed.nextIdleMinute, now);
                    observed.nextIdleMinute += 60;
                }
                long gaugePeriod = now.getEpochSecond() / 300;
                if (snapshot.provisioned()) {
                    if (observed.gaugePeriod != gaugePeriod || observed.reads != snapshot.reads()) {
                        gauge(resource, "ProvisionedReadCapacityUnits", snapshot.reads(), now);
                    }
                    if (observed.gaugePeriod != gaugePeriod || observed.writes != snapshot.writes()) {
                        gauge(resource, "ProvisionedWriteCapacityUnits", snapshot.writes(), now);
                    }
                    observed.gaugePeriod = gaugePeriod;
                    observed.reads = snapshot.reads();
                    observed.writes = snapshot.writes();
                } else {
                    observed.gaugePeriod = Long.MIN_VALUE;
                }
            }
            resources.keySet().retainAll(current);
            Iterator<Publication> retries = pending.values().iterator();
            while (retries.hasNext()) {
                Publication publication = retries.next();
                if (!current.contains(publication.resource())
                        || publication.enqueuedAt().isBefore(now.minusSeconds(RETRY_SECONDS))) {
                    retries.remove();
                } else if (send(publication)) {
                    retries.remove();
                }
            }
            long hour = now.getEpochSecond() / 3600;
            if (hour != lastRetentionHour) {
                lastRetentionHour = hour;
                try {
                    metrics.deleteMetricDataBefore(NAMESPACE, now.minus(Duration.ofDays(15)));
                } catch (RuntimeException failure) {
                    warn(failure);
                }
            }
        }
    }

    private void idle(Resource resource, String metric, long minute, Instant now) {
        Minute key = new Minute(resource, metric, minute);
        if (minute <= suppressIdleThrough || minutes.containsKey(key) || minutes.size() >= MAX_MINUTES) {
            return;
        }
        minutes.put(key, false);
        publish(new Publication(resource, metric, minute, 0, key.id(), now));
    }

    private void gauge(Resource resource, String metric, long units, Instant now) {
        String id = resource.id() + ":" + metric + ":" + now.getEpochSecond();
        publish(new Publication(resource, metric, now.getEpochSecond(), units, id, now));
    }

    private void publish(Publication publication) {
        // Replace a failed zero with the late positive too; never retry the obsolete zero afterwards.
        pending.remove(publication.id());
        if (!send(publication) && pending.size() < MAX_PENDING) {
            pending.put(publication.id(), publication);
        }
    }

    private boolean send(Publication publication) {
        try {
            List<Dimension> dimensions = new ArrayList<>();
            dimensions.add(new Dimension("TableName", publication.resource().tableName()));
            if (publication.resource().indexName() != null) {
                dimensions.add(new Dimension("GlobalSecondaryIndexName", publication.resource().indexName()));
            }
            MetricDatum datum = MetricDatum.of(publication.metric(), "Count", publication.units(), dimensions,
                    publication.timestamp());
            metrics.publishMetricForAccount(publication.resource().accountId(), NAMESPACE, datum,
                    publication.resource().region(), producerId + ":" + publication.id());
            return true;
        } catch (RuntimeException failure) {
            warn(failure);
            return false;
        }
    }

    private synchronized void warn(RuntimeException failure) {
        if (++failures == 1 || failures % 1024 == 0) {
            LOG.warnv(failure, "DynamoDB metric publication failed; bounded retries preserve successful item requests");
        }
    }

    @Override
    public synchronized void beforeReset() {
        paused = true;
        generation++;
    }

    @Override
    public synchronized void clear() {
        resources.clear();
        minutes.clear();
        pending.clear();
        suppressIdleThrough = Long.MIN_VALUE;
        lastRetentionHour = Long.MIN_VALUE;
    }

    @Override
    public synchronized void afterReset() {
        paused = false;
    }

    @PreDestroy
    public void stop() {
        synchronized (this) {
            closed = true;
            generation++;
        }
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    private static long minute(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), 60) * 60;
    }

    private record Resource(String accountId, String region, String tableName, String tableId, String indexName) {
        Resource index(String name) {
            return new Resource(accountId, region, tableName, tableId, name);
        }

        String id() {
            return accountId + ":" + region + ":" + tableId
                    + (indexName == null ? ":table" : ":index:" + indexName);
        }
    }

    private record Minute(Resource resource, String metric, long minute) {
        String id() {
            return resource.id() + ":" + metric + ":minute:" + minute;
        }
    }

    private record Publication(Resource resource, String metric, long timestamp, double units, String id,
                               Instant enqueuedAt) {}

    private static final class Observed {
        private long nextIdleMinute;
        private long gaugePeriod = Long.MIN_VALUE;
        private long reads;
        private long writes;

        private Observed(long firstMinute) {
            nextIdleMinute = firstMinute;
        }
    }
}
