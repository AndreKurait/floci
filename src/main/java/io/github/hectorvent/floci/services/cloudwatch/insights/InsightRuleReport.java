package io.github.hectorvent.floci.services.cloudwatch.insights;

import io.github.hectorvent.floci.services.cloudwatch.insights.model.InsightContribution;
import io.github.hectorvent.floci.services.cloudwatch.insights.model.InsightRule;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static io.github.hectorvent.floci.services.cloudwatch.insights.InsightRuleDefinition.invalid;
import static io.github.hectorvent.floci.services.cloudwatch.insights.InsightRuleDefinition.unsupported;

/** Report values are derived from committed minute counts; report reads never re-evaluate stored Logs. */
public record InsightRuleReport(List<String> labels, double aggregate, long unique,
                                List<Contributor> contributors, List<MetricPoint> metrics) {
    private static final Set<String> METRICS = Set.of("UniqueContributors", "MaxContributorValue", "SampleCount",
            "Sum", "Minimum", "Maximum", "Average");

    public record Point(long timestamp, double value) {}
    public record Contributor(List<String> keys, double aggregate, List<Point> points) {}
    public record MetricPoint(long timestamp, Map<String, Double> values) {}

    public static InsightRuleReport create(InsightRule rule, Instant start, Instant end, Integer period,
                                           Integer maximum, String order, List<String> metrics, Instant now) {
        if (start == null || end == null) {
            throw invalid("StartTime and EndTime are required.");
        }
        if (period == null || period < 60 || period > 86400 || period % 60 != 0) {
            throw invalid("INVALID_PERIOD: A Period must be a multiple of 60 seconds, from 60 to 86400.");
        }
        long duration;
        try {
            duration = Math.subtractExact(end.toEpochMilli(), start.toEpochMilli());
        } catch (ArithmeticException error) {
            throw invalid("INVALID_TIME_RANGE: Timestamp range is out of bounds.");
        }
        long step = period * 1000L;
        if (duration < step) {
            throw invalid("INVALID_TIME_RANGE: The request range must be greater than or equal to the Period.");
        }
        if (duration > 86400000L || start.isBefore(now.minusSeconds(15 * 86400L))) {
            throw unsupported("Reports support windows up to 24 hours within the previous 15 days.");
        }
        int limit = maximum == null ? 10 : maximum;
        if (limit < 1 || limit > 100) {
            throw invalid("MaxContributorCount must be between 1 and 100.");
        }
        String ranking = order == null ? "Sum" : order;
        if (!Set.of("Sum", "Maximum").contains(ranking)) {
            throw invalid("OrderBy must be Sum or Maximum.");
        }
        Set<String> selected = metrics == null ? Set.of() : Set.copyOf(metrics);
        if (!METRICS.containsAll(selected)) {
            throw invalid("Metrics contains an unsupported statistic.");
        }
        long firstMinute;
        try {
            firstMinute = Math.multiplyExact(Math.floorDiv(start.toEpochMilli(), 60000), 60000);
        } catch (ArithmeticException error) {
            throw invalid("INVALID_TIME_RANGE: Timestamp range is out of bounds.");
        }
        Map<List<String>, Map<Long, Long>> values = new LinkedHashMap<>();
        for (InsightContribution contribution : rule.history()) {
            if (contribution.timestamp() < firstMinute || contribution.timestamp() >= end.toEpochMilli()) {
                continue;
            }
            values.computeIfAbsent(contribution.keys(), ignored -> new TreeMap<>())
                    .merge(contribution.timestamp(), contribution.count(), Math::addExact);
        }
        List<Contributor> contributors = new ArrayList<>();
        long total = 0;
        for (Map.Entry<List<String>, Map<Long, Long>> entry : values.entrySet()) {
            List<Point> points = entry.getValue().entrySet().stream()
                    .map(point -> new Point(point.getKey(), point.getValue())).toList();
            long sum = entry.getValue().values().stream().reduce(0L, Math::addExact);
            total = Math.addExact(total, sum);
            double value = "Maximum".equals(ranking)
                    ? entry.getValue().values().stream().mapToLong(Long::longValue).max().orElse(0) : sum;
            contributors.add(new Contributor(entry.getKey(), value, points));
        }
        contributors.sort(Comparator.comparingDouble(Contributor::aggregate).reversed()
                .thenComparing(Contributor::keys, InsightRuleReport::compareKeys));
        List<MetricPoint> points = new ArrayList<>();
        for (long bucket = firstMinute; bucket < end.toEpochMilli();) {
            long bucketEnd = end.toEpochMilli() - bucket <= step
                    ? end.toEpochMilli() : Math.addExact(bucket, step);
            long count = 0;
            long unique = 0;
            long top = 0;
            for (Map<Long, Long> contributor : values.values()) {
                long value = 0;
                for (Map.Entry<Long, Long> sample : contributor.entrySet()) {
                    if (sample.getKey() >= bucket && sample.getKey() < bucketEnd) {
                        value = Math.addExact(value, sample.getValue());
                    }
                }
                count = Math.addExact(count, value);
                if (value > 0) {
                    unique++;
                    top = Math.max(top, value);
                }
            }
            Map<String, Double> fields = new LinkedHashMap<>();
            fields.put("UniqueContributors", (double) unique);
            fields.put("SampleCount", (double) count);
            if (count > 0) {
                fields.put("MaxContributorValue", (double) top);
                fields.put("Sum", (double) count);
                fields.put("Minimum", 1.0);
                fields.put("Maximum", 1.0);
                fields.put("Average", 1.0);
            }
            fields.keySet().retainAll(selected);
            if (!fields.isEmpty()) {
                points.add(new MetricPoint(bucket, Map.copyOf(fields)));
            }
            if (end.toEpochMilli() - bucket <= step) {
                break;
            }
            bucket = Math.addExact(bucket, step);
        }
        return new InsightRuleReport(InsightRuleDefinition.parse(rule.definition()).keys(), total, values.size(),
                List.copyOf(contributors.subList(0, Math.min(limit, contributors.size()))), List.copyOf(points));
    }

    private static int compareKeys(List<String> first, List<String> second) {
        for (int index = 0; index < Math.min(first.size(), second.size()); index++) {
            int comparison = first.get(index).compareTo(second.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(first.size(), second.size());
    }
}
