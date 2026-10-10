package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Alarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.CompositeAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class CloudWatchMetricsService {

    private static final Logger LOG = Logger.getLogger(CloudWatchMetricsService.class);

    private final StorageBackend<String, MetricDatum> metricStore;
    private final StorageBackend<String, Alarm> alarmStore;
    private final RegionResolver regionResolver;

    @Inject
    public CloudWatchMetricsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.metricStore = storageFactory.create("cloudwatchmetrics", "cwmetrics.json",
                new TypeReference<Map<String, MetricDatum>>() {});
        this.alarmStore = storageFactory.create("cloudwatchmetrics", "cwalarms.json",
                new TypeReference<Map<String, Alarm>>() {});
        this.regionResolver = regionResolver;
    }

    CloudWatchMetricsService(StorageBackend<String, MetricDatum> metricStore,
                             StorageBackend<String, Alarm> alarmStore,
                             RegionResolver regionResolver) {
        this.metricStore = metricStore;
        this.alarmStore = alarmStore;
        this.regionResolver = regionResolver;
    }

    public void putMetricData(String namespace, List<MetricDatum> datums, String region) {
        putMetricDataForAccount(null, namespace, datums, region);
    }

    /**
     * Stores the datums in {@code accountId}'s partition rather than the caller's, for writers that
     * run outside a request, such as a metric filter publishing for a log batch a container
     * streamed on another account's behalf. A null account is the caller's own.
     */
    public void putMetricDataForAccount(String accountId, String namespace, List<MetricDatum> datums, String region) {
        long nowSeconds = Instant.now().getEpochSecond();
        for (MetricDatum datum : datums) {
            datum.setNamespace(namespace);
            if (datum.getTimestamp() == 0) {
                datum.setTimestamp(nowSeconds);
            }
            // Synthesize StatisticValues if only a scalar value was provided
            if (datum.getSampleCount() == 0 && datum.getSum() == 0) {
                datum.setSampleCount(1);
                datum.setSum(datum.getValue());
                datum.setMinimum(datum.getValue());
                datum.setMaximum(datum.getValue());
            }

            storeDatum(accountId, namespace, datum, region, UUID.randomUUID().toString());
        }
        LOG.debugv("PutMetricData: {0} datums for namespace {1}", datums.size(), namespace);
    }

    /**
     * Internal scalar publication, not an AWS PutMetricData operation. The publisher supplies a
     * canonical account, an explicit event timestamp (including epoch zero), and a stable ID for
     * this one contribution. Retrying a partially/ambiguously committed write replaces the same
     * key instead of appending another sample. Distinct contributions must have distinct IDs.
     * The caller's snapshot is never mutated or retained by the store.
     */
    public void publishMetricForAccount(String accountId, String namespace, MetricDatum datum,
                                        String region, String publicationId) {
        if (accountId == null || accountId.isBlank() || publicationId == null || publicationId.isBlank()) {
            throw new IllegalArgumentException("Internal publication requires an explicit account and publication ID");
        }
        MetricDatum sample = new MetricDatum();
        sample.setNamespace(namespace);
        sample.setMetricName(datum.getMetricName());
        sample.setUnit(datum.getUnit());
        sample.setDimensions(List.copyOf(datum.getDimensions()));
        sample.setTimestamp(datum.getTimestamp());
        sample.setValue(datum.getValue());
        sample.setSampleCount(1);
        sample.setSum(datum.getValue());
        sample.setMinimum(datum.getValue());
        sample.setMaximum(datum.getValue());
        storeDatum(accountId, namespace, sample, region, "publication-" + publicationId);
    }

    private void storeDatum(String accountId, String namespace, MetricDatum datum, String region, String id) {
        String key = region + "::" + namespace + "::" + datum.getMetricName()
                + "::" + buildDimKey(datum.getDimensions()) + "::"
                + String.format("%013d", datum.getTimestamp()) + "::" + id;
        if (accountId != null && metricStore instanceof AccountAwareStorageBackend<?> rawAware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<MetricDatum> aware = (AccountAwareStorageBackend<MetricDatum>) rawAware;
            aware.putForAccount(accountId, key, datum);
        } else {
            metricStore.put(key, datum);
        }
    }

    public record MetricIdentity(String namespace, String metricName, List<Dimension> dimensions) {}

    public List<MetricIdentity> listMetrics(String namespace, String metricName,
                                             List<Dimension> dimensions, String region) {
        String prefix = region + "::";
        if (namespace != null && !namespace.isBlank()) {
            prefix += namespace + "::";
        }

        final String finalPrefix = prefix;
        List<MetricDatum> all = metricStore.scan(k -> k.startsWith(finalPrefix));

        // De-duplicate by (namespace, metricName, dimKey)
        Map<String, MetricIdentity> deduped = new LinkedHashMap<>();
        for (MetricDatum d : all) {
            if (metricName != null && !metricName.isBlank() && !metricName.equals(d.getMetricName())) {
                continue;
            }
            if (dimensions != null && !dimensions.isEmpty() && !matchesDimensions(d.getDimensions(), dimensions)) {
                continue;
            }
            String identity = d.getNamespace() + "::" + d.getMetricName() + "::" + buildDimKey(d.getDimensions());
            deduped.putIfAbsent(identity, new MetricIdentity(d.getNamespace(), d.getMetricName(), d.getDimensions()));
        }
        return new ArrayList<>(deduped.values());
    }

    public record Datapoint(Instant timestamp, double sampleCount, double sum,
                             double average, double minimum, double maximum, String unit) {}

    public record MetricStat(
            String namespace,
            String metricName,
            List<Dimension> dimensions,
            int period,
            String stat,
            String unit
    ) {}

    public record MetricDataQuery(
            String id,
            MetricStat metricStat,
            String expression,
            String label,
            boolean returnData
    ) {}

    public record MetricDataResult(
            String id,
            String label,
            List<Instant> timestamps,
            List<Double> values,
            String statusCode
    ) {}

    public List<Datapoint> getMetricStatistics(String namespace, String metricName,
                                                List<Dimension> dimensions,
                                                Instant startTime, Instant endTime,
                                                int periodSeconds,
                                                List<String> statistics,
                                                String unit, String region) {
        String dimKey = dimensions != null ? buildDimKey(dimensions) : "";
        String prefix = region + "::" + namespace + "::" + metricName + "::" + dimKey + "::";

        long startEpoch = startTime != null ? startTime.getEpochSecond() : 0;
        long endEpoch = endTime != null ? endTime.getEpochSecond() : Long.MAX_VALUE;

        List<MetricDatum> matching = metricStore.scan(k -> {
            if (!k.startsWith(prefix)) return false;
            // Extract timestamp from key segment
            String[] parts = k.split("::");
            if (parts.length < 6) return false;
            try {
                long ts = Long.parseLong(parts[parts.length - 2]);
                return ts >= startEpoch && ts <= endEpoch;
            } catch (NumberFormatException e) {
                return false;
            }
        });

        if (unit != null && !unit.isBlank() && !"None".equals(unit)) {
            matching = matching.stream()
                    .filter(d -> unit.equals(d.getUnit()))
                    .collect(Collectors.toList());
        }

        // Group by period bucket
        Map<Long, List<MetricDatum>> buckets = new LinkedHashMap<>();
        for (MetricDatum d : matching) {
            long bucket = (d.getTimestamp() / periodSeconds) * periodSeconds;
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(d);
        }

        List<Datapoint> result = new ArrayList<>();
        for (Map.Entry<Long, List<MetricDatum>> entry : buckets.entrySet()) {
            List<MetricDatum> group = entry.getValue();
            double sc = group.stream().mapToDouble(MetricDatum::getSampleCount).sum();
            double sum = group.stream().mapToDouble(MetricDatum::getSum).sum();
            double min = group.stream().mapToDouble(MetricDatum::getMinimum).min().orElse(0);
            double max = group.stream().mapToDouble(MetricDatum::getMaximum).max().orElse(0);
            double avg = sc > 0 ? sum / sc : 0;
            String resolvedUnit = group.stream()
                    .map(MetricDatum::getUnit)
                    .filter(u -> u != null && !u.isBlank())
                    .findFirst().orElse("None");
            result.add(new Datapoint(
                    Instant.ofEpochSecond(entry.getKey()),
                    sc, sum, avg, min, max, resolvedUnit
            ));
        }
        result.sort(Comparator.comparing(Datapoint::timestamp));
        return result;
    }

    public List<MetricDataResult> getMetricData(
            List<MetricDataQuery> queries,
            Instant startTime,
            Instant endTime,
            String region) {

        List<MetricDataResult> results = new ArrayList<>();

        for (MetricDataQuery query : queries) {
            if (!query.returnData()) {
                continue;
            }
            if (query.metricStat() != null) {
                MetricStat stat = query.metricStat();
                int period = stat.period() > 0 ? stat.period() : 60;

                List<Datapoint> datapoints = getMetricStatistics(
                        stat.namespace(), stat.metricName(), stat.dimensions(),
                        startTime, endTime, period,
                        List.of(stat.stat()), stat.unit(), region);

                List<Instant> timestamps = new ArrayList<>();
                List<Double> values = new ArrayList<>();
                for (Datapoint dp : datapoints) {
                    timestamps.add(dp.timestamp());
                    values.add(resolveStatValue(dp, stat.stat()));
                }

                String label = query.label() != null ? query.label() : stat.metricName();
                results.add(new MetricDataResult(query.id(), label, timestamps, values, "Complete"));
            }
            // Expression-based queries are out of scope for this implementation
        }
        return results;
    }

    /** Shared with {@link AlarmEvaluator}, which resolves the same statistic against
     * freshly-fetched datapoints when evaluating an alarm's threshold. */
    public static double resolveStatValue(Datapoint dp, String stat) {
        return switch (stat) {
            case "Average" -> dp.average();
            case "Sum" -> dp.sum();
            case "Minimum" -> dp.minimum();
            case "Maximum" -> dp.maximum();
            case "SampleCount" -> dp.sampleCount();
            default -> {
                if (stat.startsWith("p")) yield dp.maximum();
                else yield dp.average();
            }
        };
    }

    public synchronized void putMetricAlarm(MetricAlarm alarm, String region) {
        putAlarm(alarm, region);
        evaluateComposites(region, null);
    }

    public void putCompositeAlarm(CompositeAlarm alarm, String region) {
        putCompositeAlarm(alarm, region, true);
    }

    public synchronized void putCompositeAlarm(CompositeAlarm alarm, String region, boolean replaceExisting) {
        if (!replaceExisting && alarmStore.get(region + "::" + alarm.getAlarmName()).isPresent()) {
            throw new AwsException("AlreadyExists", "Alarm already exists: " + alarm.getAlarmName(), 400);
        }
        Set<String> references = alarmReferences(alarm, region);
        if (references.size() > 100) {
            throw new AwsException("LimitExceeded", "AlarmRule references more than 100 child alarms", 400);
        }
        List<CompositeAlarm> existing = describeCompositeAlarms(null, null, region);
        for (String reference : references) {
            long parents = existing.stream().filter(parent -> !parent.getAlarmName().equals(alarm.getAlarmName()))
                    .filter(parent -> alarmReferences(parent, region).contains(reference))
                    .count();
            if (parents >= 150) {
                throw new AwsException("LimitExceeded", "An alarm can be referenced by at most 150 composite alarms", 400);
            }
        }
        boolean created = alarmStore.get(region + "::" + alarm.getAlarmName()).isEmpty();
        putAlarm(alarm, region);
        if (created) {
            evaluateComposites(region, null);
        }
    }

    private Set<String> alarmReferences(CompositeAlarm alarm, String region) {
        return CompositeAlarmRule.parse(alarm.getAlarmRule()).references().stream()
                .map(reference -> AwsArnUtils.isArn(reference) ? reference
                        : regionResolver.buildArn("cloudwatch", region, "alarm:" + reference))
                .collect(Collectors.toSet());
    }

    private void putAlarm(Alarm alarm, String region) {
        String name = alarm.getAlarmName();
        if (name == null || name.isBlank() || name.length() > 255) {
            throw new AwsException("ValidationError", "AlarmName must contain between 1 and 255 characters", 400);
        }
        Alarm previous = alarmStore.get(region + "::" + name).orElse(null);
        if (previous != null) {
            if (!previous.getClass().equals(alarm.getClass())) {
                throw new AwsException("ValidationError", "An alarm of another type already has this name", 400);
            }
            alarm.setStateValue(previous.getStateValue());
            alarm.setStateReason(previous.getStateReason());
            alarm.setStateReasonData(previous.getStateReasonData());
            alarm.setStateUpdatedTimestamp(previous.getStateUpdatedTimestamp());
            alarm.setTags(new HashMap<>(previous.getTags()));
        }
        if (alarm.getAlarmArn() == null) {
            alarm.setAlarmArn(regionResolver.buildArn("cloudwatch", region, "alarm:" + name));
        }
        alarm.setRegion(region);
        alarm.setAlarmConfigurationUpdatedTimestamp(Instant.now().getEpochSecond());
        alarmStore.put(region + "::" + name, alarm);
        LOG.infov("Put {0}: {1} in {2}", alarm.getClass().getSimpleName(), name, region);
    }

    /** Metric alarms evaluated by the existing metric evaluator. */
    public List<MetricAlarm> allAlarms() {
        return alarmStore.scan(k -> true).stream().filter(MetricAlarm.class::isInstance)
                .map(MetricAlarm.class::cast).toList();
    }

    public List<MetricAlarm> describeAlarms(List<String> names, String prefix, String region) {
        return matchingAlarms(names, prefix, region).stream().filter(MetricAlarm.class::isInstance)
                .map(MetricAlarm.class::cast).toList();
    }

    public void validateAlarmTypes(List<String> types) {
        if (!Set.of("MetricAlarm", "CompositeAlarm").containsAll(types)) {
            throw new AwsException("ValidationError", "Unknown alarm type", 400);
        }
    }

    public List<CompositeAlarm> describeCompositeAlarms(List<String> names, String prefix, String region) {
        return matchingAlarms(names, prefix, region).stream().filter(CompositeAlarm.class::isInstance)
                .map(CompositeAlarm.class::cast).toList();
    }

    private List<Alarm> matchingAlarms(List<String> names, String prefix, String region) {
        return alarmStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(a -> names == null || names.isEmpty() || names.contains(a.getAlarmName()))
                .filter(a -> prefix == null || a.getAlarmName().startsWith(prefix)).toList();
    }

    private Map<String, Alarm> alarmIndex(String region) {
        Map<String, Alarm> alarms = new HashMap<>();
        for (Alarm alarm : alarmStore.scan(k -> k.startsWith(region + "::"))) {
            alarms.put(alarm.getAlarmName(), alarm);
            alarms.put(alarm.getAlarmArn(), alarm);
        }
        return alarms;
    }

    private void evaluateComposites(String region, String manualAlarm) {
        Map<String, Alarm> alarms = alarmIndex(region);
        Map<String, CompositeAlarmRule.Rule> pending = new HashMap<>();
        for (Alarm alarm : new HashSet<>(alarms.values())) {
            if (alarm instanceof CompositeAlarm composite && !alarm.getAlarmName().equals(manualAlarm)) {
                pending.put(alarm.getAlarmName(), CompositeAlarmRule.parse(composite.getAlarmRule()));
            }
        }
        Set<String> cycles = pending.keySet().stream()
                .filter(name -> isCyclic(name, alarms)).collect(Collectors.toSet());
        boolean progress;
        do {
            progress = false;
            Iterator<Map.Entry<String, CompositeAlarmRule.Rule>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, CompositeAlarmRule.Rule> next = iterator.next();
                boolean blocked = next.getValue().references().stream().map(alarms::get)
                        .anyMatch(child -> child != null && pending.containsKey(child.getAlarmName()));
                if (blocked || cycles.contains(next.getKey())) {
                    continue;
                }
                Alarm alarm = alarms.get(next.getKey());
                boolean triggered = next.getValue().evaluate(reference -> {
                    Alarm child = alarms.get(reference);
                    return child == null ? null : child.getStateValue();
                });
                String state = triggered ? "ALARM" : "OK";
                if (!state.equals(alarm.getStateValue())) {
                    alarm.setStateValue(state);
                    alarm.setStateReason("AlarmRule evaluated to " + state);
                    alarm.setStateReasonData(null);
                    alarm.setStateUpdatedTimestamp(Instant.now().getEpochSecond());
                    alarmStore.put(region + "::" + alarm.getAlarmName(), alarm);
                }
                iterator.remove();
                progress = true;
            }
        } while (progress && !pending.isEmpty());
        if (!pending.isEmpty()) {
            LOG.debugv("Composite alarm evaluation stopped at cyclic dependencies: {0}", pending.keySet());
        }
    }

    public synchronized void deleteAlarms(List<String> alarmNames, String region) {
        Map<String, Alarm> alarms = alarmIndex(region);
        long composites = alarmNames.stream().map(alarms::get).filter(CompositeAlarm.class::isInstance).count();
        if (composites > 1) {
            throw new AwsException("ValidationError", "DeleteAlarms accepts at most one composite alarm", 400);
        }
        for (String name : alarmNames) {
            if (isCyclic(name, alarms)) {
                throw new AwsException("ValidationError", "Break the composite alarm cycle before deleting " + name, 400);
            }
        }
        for (String name : alarmNames) {
            alarmStore.delete(region + "::" + name);
        }
        evaluateComposites(region, null);
        LOG.infov("Deleted alarms: {0} in {1}", alarmNames, region);
    }

    private boolean isCyclic(String name, Map<String, Alarm> alarms) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(name);
        while (!pending.isEmpty()) {
            Alarm next = alarms.get(pending.remove());
            if (next instanceof CompositeAlarm composite && visited.add(next.getAlarmName())) {
                for (String reference : CompositeAlarmRule.parse(composite.getAlarmRule()).references()) {
                    Alarm child = alarms.get(reference);
                    if (child != null && child.getAlarmName().equals(name)) {
                        return true;
                    }
                    if (child != null) {
                        pending.add(child.getAlarmName());
                    }
                }
            }
        }
        return false;
    }

    public synchronized void setAlarmState(String alarmName, String stateValue, String stateReason, String stateReasonData, String region) {
        if (stateValue == null || !Set.of("OK", "ALARM", "INSUFFICIENT_DATA").contains(stateValue)) {
            throw new AwsException("ValidationError", "Invalid alarm state", 400);
        }
        String key = region + "::" + alarmName;
        Alarm alarm = alarmStore.get(key)
                .orElseThrow(() -> new AwsException("ResourceNotFound", "Alarm not found: " + alarmName, 404));

        alarm.setStateValue(stateValue);
        alarm.setStateReason(stateReason);
        alarm.setStateReasonData(stateReasonData);
        alarm.setStateUpdatedTimestamp(Instant.now().getEpochSecond());

        alarmStore.put(key, alarm);
        evaluateComposites(region, alarmName);
        LOG.infov("SetAlarmState: {0} -> {1}", alarmName, stateValue);
    }

    /**
     * Resolves the alarm an ARN names, or reports that nothing does.
     *
     * <p>CloudWatch's three tag operations each declare {@code ResourceNotFoundException},
     * which is a different shape from the {@code ResourceNotFound} that {@code SetAlarmState}
     * and {@code GetDashboard} declare; the SDK maps the two codes to two exception classes,
     * so the tag path uses the longer one rather than the code used elsewhere in this service.
     *
     * <p>The message names the ARN rather than asserting it was an alarm, because both tag
     * handlers route every ARN that is not a dashboard and not a metric stream here. That
     * includes kinds AWS considers taggable and Floci does not serve, a Contributor Insights
     * {@code insight-rule/} ARN being the one AWS documents; reporting that no resource
     * matches the ARN is true of those, where "alarm not found" would not be.
     */
    private Alarm requireAlarm(String resourceArn, String region) {
        return alarmStore.scan(k -> k.startsWith(region + "::"))
                .stream()
                .filter(a -> a.getAlarmArn() != null && a.getAlarmArn().equals(resourceArn))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No CloudWatch resource matches the ARN " + resourceArn + ".", 404));
    }

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        return requireAlarm(resourceArn, region).getTags();
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        Alarm alarm = requireAlarm(resourceArn, region);
        alarm.getTags().putAll(tags);
        alarmStore.put(region + "::" + alarm.getAlarmName(), alarm);
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        Alarm alarm = requireAlarm(resourceArn, region);
        tagKeys.forEach(alarm.getTags()::remove);
        alarmStore.put(region + "::" + alarm.getAlarmName(), alarm);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    static String buildDimKey(List<Dimension> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return "";
        }
        return dimensions.stream()
                .sorted(Comparator.comparing(Dimension::name))
                .map(d -> d.name() + "=" + d.value())
                .collect(Collectors.joining(","));
    }

    private boolean matchesDimensions(List<Dimension> actual, List<Dimension> required) {
        String requiredKey = buildDimKey(required);
        String actualKey = buildDimKey(actual);
        return actualKey.contains(requiredKey);
    }
}
