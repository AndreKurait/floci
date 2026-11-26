package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.services.cloudwatch.metrics.model.MetricDatum;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Preserve weighted samples without expanding their counts; services choose packed or per-value storage. */
final class MetricValues {
    private static final int MAX_VALUES = 150;
    private static final double MAX_VALUE = Math.scalb(1.0, 360);

    private MetricValues() {
    }

    static List<MetricDatum> fromJson(JsonNode input, MetricDatum datum) {
        return fromJson(input, "MetricData.member.1", datum);
    }

    static List<MetricDatum> fromJson(JsonNode input, String memberPath, MetricDatum datum) {
        List<Double> values = jsonNumbers(input.get("Values"), "Values");
        List<Double> counts = jsonNumbers(input.get("Counts"), "Counts");
        return normalize(datum, memberPath + ".", values, counts,
                input.hasNonNull("Value"));
    }

    /** Parse fields indexed for one datum, so a batch never rescans the entire request per sample. */
    static List<MetricDatum> fromQuery(Map<String, List<String>> input, String prefix, MetricDatum datum) {
        List<Double> values = queryNumbers(input, prefix + "Values");
        List<Double> counts = queryNumbers(input, prefix + "Counts");
        boolean otherFormat = input.containsKey(prefix + "Value");
        return normalize(datum, prefix, values, counts, otherFormat);
    }

    private static List<Double> jsonNumbers(JsonNode input, String field) {
        if (input == null || input.isNull()) {
            return null;
        }
        if (!input.isArray() || input.isEmpty() || input.size() > MAX_VALUES) {
            throw CloudWatchMetricsException.invalidParameterValue(field + " must contain 1 to 150 numbers.");
        }
        List<Double> numbers = new ArrayList<>(input.size());
        for (JsonNode value : input) {
            if (!value.isNumber()) {
                throw CloudWatchMetricsException.invalidParameterValue(field + " must contain numbers.");
            }
            numbers.add(value.doubleValue());
        }
        return numbers;
    }

    private static List<Double> queryNumbers(Map<String, List<String>> input, String field) {
        TreeMap<Integer, Double> numbers = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : input.entrySet()) {
            String key = entry.getKey();
            if (!key.equals(field) && !key.startsWith(field + ".")) {
                continue;
            }
            String prefix = field + ".member.";
            try {
                if (!key.startsWith(prefix) || entry.getValue().size() != 1) {
                    throw new IllegalArgumentException();
                }
                int index = Integer.parseInt(key.substring(prefix.length()));
                if (index < 1 || index > MAX_VALUES) {
                    throw new IllegalArgumentException();
                }
                double value = Double.parseDouble(entry.getValue().getFirst());
                Double previous = numbers.putIfAbsent(index, value);
                if (previous != null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException error) {
                throw CloudWatchMetricsException.invalidParameterValue(
                        field + " must contain 1 to 150 consecutively indexed numbers.");
            }
        }
        if (numbers.isEmpty()) {
            return null;
        }
        if (numbers.lastKey() != numbers.size()) {
            throw CloudWatchMetricsException.invalidParameterValue(field + " has a missing member.");
        }
        return new ArrayList<>(numbers.values());
    }

    private static List<MetricDatum> normalize(MetricDatum template, String memberPrefix, List<Double> values,
                                               List<Double> counts, boolean otherFormat) {
        if (values == null) {
            if (counts != null) {
                throw CloudWatchMetricsException.invalidParameterCombination("Counts requires Values.");
            }
            return List.of(template);
        }
        if (otherFormat) {
            throw CloudWatchMetricsException.invalidParameterCombination("Values cannot be combined with Value.");
        }
        if (counts != null && counts.size() != values.size()) {
            throw CloudWatchMetricsException.invalidParameterValue("The parameters " + memberPrefix
                    + "Values and " + memberPrefix + "Counts must be of the same size.");
        }
        double[] packedValues = new double[values.size()];
        double[] packedWeights = new double[values.size()];
        double[] sums = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            double value = MetricNumberValidation.finite(values.get(i), "Values.member." + (i + 1));
            double count = counts == null ? 1 : counts.get(i);
            if (!Double.isFinite(value) || Math.abs(value) > MAX_VALUE) {
                throw CloudWatchMetricsException.invalidParameterValue(
                        "Values must be finite numbers between -2^360 and 2^360.");
            }
            if (!Double.isFinite(count) || count < 0 || !Double.isFinite(value * count)) {
                throw CloudWatchMetricsException.invalidParameterValue(
                        "Counts must be nonnegative finite numbers with a finite weighted sum.");
            }
            packedValues[i] = value;
            packedWeights[i] = count;
            sums[i] = value * count;
        }
        if (values.size() == 1) {
            return List.of(sample(template, packedValues[0], packedWeights[0]));
        }
        MetricDatum packed = MetricDatum.of(template.getMetricName(), template.getUnit(), 0,
                List.copyOf(template.getDimensions()), template.getTimestamp());
        packed.setSampleValues(packedValues);
        packed.setSampleWeights(packedWeights);
        packed.setSampleCount(Arrays.stream(packedWeights).sum());
        packed.setSum(Arrays.stream(sums).sum());
        packed.setMinimum(Arrays.stream(packedValues).min().orElseThrow());
        packed.setMaximum(Arrays.stream(packedValues).max().orElseThrow());
        return List.of(packed);
    }

    static Stream<MetricDatum> expand(MetricDatum stored) {
        double[] values = stored.getSampleValues();
        double[] weights = stored.getSampleWeights();
        if (values == null && weights == null) {
            return Stream.of(stored);
        }
        if (values == null || weights == null || values.length != weights.length || values.length == 0) {
            throw new IllegalStateException("Stored weighted metric has inconsistent values and weights");
        }
        return IntStream.range(0, values.length).mapToObj(index -> sample(stored, values[index], weights[index]));
    }

    private static MetricDatum sample(MetricDatum template, double value, double weight) {
        MetricDatum sample = MetricDatum.of(template.getMetricName(), template.getUnit(), value,
                List.copyOf(template.getDimensions()), template.getTimestamp());
        sample.setNamespace(template.getNamespace());
        sample.setSampleCount(weight);
        sample.setSum(value * weight);
        sample.setMinimum(value);
        sample.setMaximum(value);
        return sample;
    }
}
