package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Percentile selection over recoverable, nonnegative samples, including repeated equal values. */
final class MetricPercentiles {
    private static final Pattern PERCENTILE = Pattern.compile("p[0-9]+(?:\\.[0-9]+)?");
    private static final Set<String> STATISTICS = Set.of("SampleCount", "Average", "Sum", "Minimum", "Maximum");

    private MetricPercentiles() {
    }

    static double percentile(String statistic) {
        if (statistic == null || !PERCENTILE.matcher(statistic).matches()) {
            throw CloudWatchMetricsException.invalidParameterValue("Unsupported percentile statistic: " + statistic);
        }
        double value = Double.parseDouble(statistic.substring(1));
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            throw CloudWatchMetricsException.invalidParameterValue("Percentile must be between p0 and p100.");
        }
        return value;
    }

    static List<String> selection(List<String> statistics, List<String> extended) {
        if (!statistics.isEmpty() && !extended.isEmpty()) {
            throw CloudWatchMetricsException.invalidParameterCombination(
                    "Specify either Statistics or ExtendedStatistics, not both.");
        }
        if (statistics.isEmpty() && extended.isEmpty()) {
            throw CloudWatchMetricsException.missingParameter("Statistics or ExtendedStatistics");
        }
        if (statistics.size() > 5 || !STATISTICS.containsAll(statistics) || extended.size() > 10) {
            throw CloudWatchMetricsException.invalidParameterValue("Invalid Statistics or ExtendedStatistics.");
        }
        extended.forEach(MetricPercentiles::percentile);
        return statistics.isEmpty() ? extended : statistics;
    }

    static Map<String, Double> calculate(List<MetricDatum> samples, List<String> percentiles) {
        if (percentiles.isEmpty() || samples.isEmpty() || samples.stream().anyMatch(sample -> !recoverable(sample))) {
            return Map.of();
        }
        List<MetricDatum> ordered = samples.stream().sorted(Comparator.comparingDouble(MetricDatum::getMinimum)).toList();
        double count = ordered.stream().mapToDouble(MetricDatum::getSampleCount).sum();
        if (!Double.isFinite(count)) {
            return Map.of();
        }
        Map<String, Double> values = new LinkedHashMap<>();
        for (String statistic : percentiles) {
            double percentile = percentile(statistic);
            if (percentile == 0) {
                values.put(statistic, ordered.getFirst().getMinimum());
                continue;
            }
            if (percentile == 100) {
                values.put(statistic, ordered.getLast().getMinimum());
                continue;
            }
            if (count == 0) {
                continue;
            }
            double rank = percentile / 100 * count;
            double seen = 0;
            double value = ordered.getLast().getMinimum();
            for (MetricDatum sample : ordered) {
                seen += sample.getSampleCount();
                if (seen >= rank) {
                    value = sample.getMinimum();
                    break;
                }
            }
            values.put(statistic, value);
        }
        return values;
    }

    private static boolean recoverable(MetricDatum sample) {
        double value = sample.getMinimum();
        double expectedSum = value * sample.getSampleCount();
        return Double.isFinite(value) && value >= 0 && value == sample.getMaximum()
                && Double.isFinite(sample.getSampleCount()) && sample.getSampleCount() >= 0
                && Double.isFinite(expectedSum) && Double.isFinite(sample.getSum())
                && Math.abs(sample.getSum() - expectedSum) <= Math.ulp(expectedSum) * 4;
    }
}
