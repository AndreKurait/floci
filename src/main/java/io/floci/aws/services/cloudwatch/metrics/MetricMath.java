package io.floci.aws.services.cloudwatch.metrics;

import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService.MetricDataQuery;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService.MetricDataResult;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService.MetricStat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.regex.Pattern;

/** A bounded arithmetic plan shared by metric queries and alarm evaluation. */
final class MetricMath {
    private static final Pattern ID = Pattern.compile("[a-z][a-zA-Z0-9_]*");
    private static final int MAX_DEPTH = 128;
    private final Map<String, MetricDataQuery> queries = new LinkedHashMap<>();
    private final Map<String, Node> expressions = new HashMap<>();
    private final Map<String, Shape> shapes = new HashMap<>();
    private final Set<String> quotaMetrics = new HashSet<>();
    private int shapeDepth;

    MetricMath(List<MetricDataQuery> input) {
        if (input.size() > 500) {
            throw invalid("A metric data request supports at most 500 queries.");
        }
        for (MetricDataQuery query : input) {
            if (query == null || query.id() == null || query.id().length() > 255
                    || !ID.matcher(query.id()).matches()
                    || queries.putIfAbsent(query.id(), query) != null) {
                throw invalid("Query IDs must be unique and start with a lowercase letter.");
            }
            if ((query.metricStat() == null) == (query.expression() == null)) {
                throw invalid("Each query must contain exactly one MetricStat or Expression.");
            }
            if (query.metricStat() == null) {
                if (query.expression().isEmpty() || query.expression().length() > 2048) {
                    throw invalid("A metric expression must contain between 1 and 2048 characters.");
                }
                expressions.put(query.id(), new Parser(query.expression()).parse());
            } else {
                MetricStat stat = query.metricStat();
                if (stat.namespace() == null || stat.namespace().isBlank()
                        || stat.metricName() == null || stat.metricName().isBlank()
                        || stat.period() <= 0 || stat.stat() == null || stat.stat().isBlank()) {
                    throw invalid("MetricStat requires Namespace, MetricName, a positive Period and Stat.");
                }
                if (!List.of("Average", "Sum", "Minimum", "Maximum", "SampleCount").contains(stat.stat())) {
                    MetricPercentiles.percentile(stat.stat());
                }
            }
        }
        for (String id : queries.keySet()) {
            Shape shape = shape(id, new HashSet<>());
            if (shape.period() == null) {
                throw invalid("The result of query " + id + " must be a time series.");
            }
            Integer requested = queries.get(id).period();
            if (requested != null && !requested.equals(shape.period())) {
                throw invalid("Query Period must match its metric periods.");
            }
        }
    }

    int alarmPeriod() {
        long metricCount = queries.values().stream().filter(query -> query.metricStat() != null).count();
        if (queries.size() > 20 || metricCount > 10 || queries.size() - metricCount > 10) {
            throw invalid("An alarm supports at most 10 metric queries and 10 expression queries.");
        }
        List<MetricDataQuery> output = queries.values().stream().filter(MetricDataQuery::returnData).toList();
        if (output.size() != 1) {
            throw invalid("A metric-math alarm must have exactly one ReturnData query.");
        }
        return shapes.get(output.getFirst().id()).period();
    }

    List<MetricStat> quotaMetrics() {
        return quotaMetrics.stream().map(this::metric).toList();
    }

    List<MetricDataResult> evaluate(Instant start, Instant end,
                                    Function<MetricStat, Map<Instant, Double>> readMetric,
                                    ToDoubleFunction<MetricStat> readQuota) {
        Evaluation evaluation = new Evaluation(start, end, readMetric, readQuota);
        List<MetricDataResult> results = new ArrayList<>();
        for (MetricDataQuery query : queries.values()) {
            if (query.returnData()) {
                Map<Instant, Double> samples = new TreeMap<>(evaluation.query(query.id()).samples());
                String label = query.label() != null ? query.label()
                        : query.metricStat() != null ? query.metricStat().metricName() : query.id();
                results.add(new MetricDataResult(query.id(), label, List.copyOf(samples.keySet()),
                        List.copyOf(samples.values()), "Complete"));
            }
        }
        return results;
    }

    private Shape shape(String id, Set<String> visiting) {
        MetricDataQuery query = queries.get(id);
        if (query == null) {
            throw invalid("Unknown metric query: " + id);
        }
        if (shapes.containsKey(id)) {
            return shapes.get(id);
        }
        if (!visiting.add(id)) {
            throw invalid("Metric queries contain a cycle at " + id);
        }
        Shape result = query.metricStat() == null
                ? nodeShape(expressions.get(id), visiting) : new Shape(query.metricStat().period(), 1);
        visiting.remove(id);
        shapes.put(id, result);
        return result;
    }

    private Shape nodeShape(Node node, Set<String> visiting) {
        if (++shapeDepth > MAX_DEPTH) {
            throw invalid("Metric expression nesting exceeds the supported depth.");
        }
        try {
            return node.shape(this, visiting);
        } finally {
            shapeDepth--;
        }
    }

    private MetricStat metric(String id) {
        MetricDataQuery query = queries.get(id);
        if (query == null || query.metricStat() == null) {
            throw invalid("PERIOD and SERVICE_QUOTA require a metric query: " + id);
        }
        return query.metricStat();
    }

    private record Shape(Integer period, int depth) {
        Shape {
            if (depth > MAX_DEPTH) {
                throw invalid("Metric expression nesting exceeds the supported depth.");
            }
        }

        Shape combine(Shape other) {
            if (period != null && other.period != null && !period.equals(other.period)) {
                throw invalid("Arithmetic currently requires matching metric periods.");
            }
            return new Shape(period == null ? other.period : period, Math.max(depth, other.depth) + 1);
        }
    }

    private record Value(Double scalar, Map<Instant, Double> samples) {
        static Value scalar(double value) {
            return new Value(value, Map.of());
        }

        static Value series(Map<Instant, Double> samples) {
            return new Value(null, Map.copyOf(samples));
        }

        double at(Instant time) {
            return scalar == null ? samples.getOrDefault(time, 0.0) : scalar;
        }

        Value combine(char operator, Value other) {
            if (scalar != null && other.scalar != null) {
                return scalar(calculate(operator, scalar, other.scalar));
            }
            Set<Instant> times = new TreeSet<>(samples.keySet());
            times.addAll(other.samples.keySet());
            Map<Instant, Double> result = new TreeMap<>();
            for (Instant time : times) {
                double value = calculate(operator, at(time), other.at(time));
                if (Double.isFinite(value)) {
                    result.put(time, value);
                }
            }
            return series(result);
        }
    }

    private static double calculate(char operator, double left, double right) {
        return switch (operator) {
            case '+' -> left + right;
            case '-' -> left - right;
            case '*' -> left * right;
            case '/' -> right == 0 ? Double.NaN : left / right;
            case '^' -> Math.pow(left, right);
            default -> throw invalid("Unsupported arithmetic operator.");
        };
    }

    private interface Node {
        Shape shape(MetricMath plan, Set<String> visiting);
        Value evaluate(Evaluation evaluation);
    }

    private record Constant(double value) implements Node {
        @Override
        public Shape shape(MetricMath plan, Set<String> visiting) {
            return new Shape(null, 1);
        }

        @Override
        public Value evaluate(Evaluation evaluation) {
            return Value.scalar(value);
        }
    }

    private record Reference(String id) implements Node {
        @Override
        public Shape shape(MetricMath plan, Set<String> visiting) {
            Shape shape = plan.shape(id, visiting);
            return new Shape(shape.period(), shape.depth() + 1);
        }

        @Override
        public Value evaluate(Evaluation evaluation) {
            return evaluation.query(id);
        }
    }

    private record Binary(char operator, Node left, Node right) implements Node {
        @Override
        public Shape shape(MetricMath plan, Set<String> visiting) {
            return plan.nodeShape(left, visiting).combine(plan.nodeShape(right, visiting));
        }

        @Override
        public Value evaluate(Evaluation evaluation) {
            return left.evaluate(evaluation).combine(operator, right.evaluate(evaluation));
        }
    }

    private record MetricFunction(String name, String id) implements Node {
        @Override
        public Shape shape(MetricMath plan, Set<String> visiting) {
            MetricStat metric = plan.metric(id);
            if ("SERVICE_QUOTA".equals(name)) {
                plan.quotaMetrics.add(id);
                return new Shape(metric.period(), 1);
            }
            return new Shape(null, 1);
        }

        @Override
        public Value evaluate(Evaluation evaluation) {
            MetricStat metric = evaluation.metric(id);
            return "PERIOD".equals(name) ? Value.scalar(metric.period()) : evaluation.quota(metric);
        }
    }

    private final class Evaluation {
        private final Instant start;
        private final Instant end;
        private final Function<MetricStat, Map<Instant, Double>> readMetric;
        private final ToDoubleFunction<MetricStat> readQuota;
        private final Map<String, Value> values = new HashMap<>();

        Evaluation(Instant start, Instant end, Function<MetricStat, Map<Instant, Double>> readMetric,
                   ToDoubleFunction<MetricStat> readQuota) {
            this.start = start;
            this.end = end;
            this.readMetric = readMetric;
            this.readQuota = readQuota;
        }

        MetricStat metric(String id) {
            return MetricMath.this.metric(id);
        }

        Value query(String id) {
            if (values.containsKey(id)) {
                return values.get(id);
            }
            MetricDataQuery query = queries.get(id);
            Value result = query.metricStat() == null
                    ? expressions.get(id).evaluate(this) : Value.series(readMetric.apply(query.metricStat()));
            values.put(id, result);
            return result;
        }

        Value quota(MetricStat metric) {
            double quota = readQuota.applyAsDouble(metric);
            if (!Double.isFinite(quota) || quota <= 0) {
                throw invalid("The service quota must be a positive finite number.");
            }
            long first = Math.floorDiv(start.getEpochSecond(), metric.period()) * metric.period();
            long last = end.minusNanos(1).getEpochSecond();
            long count = last < first ? 0 : Math.floorDiv(last - first, metric.period()) + 1;
            if (count > 100800) {
                throw invalid("Quota time series currently support at most 100800 points per request.");
            }
            Map<Instant, Double> samples = new TreeMap<>();
            for (long index = 0; index < count; index++) {
                samples.put(Instant.ofEpochSecond(first + index * metric.period()), quota);
            }
            return Value.series(samples);
        }
    }

    private static final class Parser {
        private final String text;
        private int position;
        private int depth;
        private int nodes;

        Parser(String text) {
            this.text = text;
        }

        Node parse() {
            Node result = sum();
            whitespace();
            if (position != text.length()) {
                throw invalid("Unsupported metric expression near offset " + position);
            }
            return result;
        }

        private Node sum() {
            Node result = product();
            while (true) {
                if (take('+')) {
                    result = node(new Binary('+', result, product()));
                } else if (take('-')) {
                    result = node(new Binary('-', result, product()));
                } else {
                    return result;
                }
            }
        }

        private Node product() {
            Node result = unary();
            while (true) {
                if (take('*')) {
                    result = node(new Binary('*', result, unary()));
                } else if (take('/')) {
                    result = node(new Binary('/', result, unary()));
                } else {
                    return result;
                }
            }
        }

        private Node unary() {
            if (++depth > MAX_DEPTH) {
                throw invalid("Metric expression nesting exceeds the supported depth.");
            }
            try {
                if (take('+')) {
                    return unary();
                }
                if (take('-')) {
                    return node(new Binary('-', node(new Constant(0)), unary()));
                }
                Node result = primary();
                return take('^') ? node(new Binary('^', result, unary())) : result;
            } finally {
                depth--;
            }
        }

        private Node primary() {
            if (take('(')) {
                Node result = sum();
                require(')');
                return result;
            }
            whitespace();
            int start = position;
            if (position < text.length() && (Character.isDigit(text.charAt(position))
                    || text.charAt(position) == '.')) {
                while (position < text.length()) {
                    char next = text.charAt(position);
                    if (Character.isDigit(next) || next == '.' || next == 'e' || next == 'E'
                            || ((next == '+' || next == '-') && position > start
                            && (text.charAt(position - 1) == 'e' || text.charAt(position - 1) == 'E'))) {
                        position++;
                    } else {
                        break;
                    }
                }
                try {
                    double value = Double.parseDouble(text.substring(start, position));
                    if (Double.isFinite(value)) {
                        return node(new Constant(value));
                    }
                } catch (NumberFormatException ignored) {
                    // Report the same protocol error for every malformed numeric literal.
                }
                throw invalid("Invalid numeric literal in metric expression.");
            }
            String identifier = identifier();
            if (take('(')) {
                if (!"PERIOD".equals(identifier) && !"SERVICE_QUOTA".equals(identifier)) {
                    throw invalid("Unsupported metric math function: " + identifier);
                }
                String argument = identifier();
                require(')');
                return node(new MetricFunction(identifier, argument));
            }
            return node(new Reference(identifier));
        }

        private String identifier() {
            whitespace();
            int start = position;
            while (position < text.length()
                    && (Character.isLetterOrDigit(text.charAt(position)) || text.charAt(position) == '_')) {
                position++;
            }
            if (position == start) {
                throw invalid("Expected a metric identifier near offset " + position);
            }
            return text.substring(start, position);
        }

        private Node node(Node node) {
            if (++nodes > 512) {
                throw invalid("Metric expression exceeds the supported node count.");
            }
            return node;
        }

        private void whitespace() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private boolean take(char value) {
            whitespace();
            if (position < text.length() && text.charAt(position) == value) {
                position++;
                return true;
            }
            return false;
        }

        private void require(char value) {
            if (!take(value)) {
                throw invalid("Expected '" + value + "' near offset " + position);
            }
        }
    }

    private static CloudWatchMetricsException invalid(String message) {
        return CloudWatchMetricsException.invalidParameterValue(message);
    }
}
