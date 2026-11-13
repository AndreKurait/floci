package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Transient signal collectors owned by active CloudFormation resource operations. */
final class CfnResourceSignals {
    private final ConcurrentHashMap<Key, Pending> pending = new ConcurrentHashMap<>();
    private final TimedWait timedWait;

    CfnResourceSignals() {
        this((completion, timeoutNanos) -> completion.get(timeoutNanos, TimeUnit.NANOSECONDS));
    }

    /** For tests that put the signal deadline on a timeline they control. */
    CfnResourceSignals(TimedWait timedWait) {
        this.timedWait = timedWait;
    }

    /** The bounded wait for a collector's outcome; the default is {@link CompletableFuture#get(long, TimeUnit)}. */
    @FunctionalInterface
    interface TimedWait {
        String get(CompletableFuture<String> completion, long timeoutNanos)
                throws InterruptedException, ExecutionException, TimeoutException;
    }

    Pending begin(String stackId, String logicalId, String type, JsonNode creationPolicy, JsonNode properties) {
        Policy policy = Policy.parse(type, creationPolicy, properties);
        if (policy == null) {
            return null;
        }
        Pending collector = new Pending(policy, timedWait);
        if (pending.putIfAbsent(new Key(stackId, logicalId), collector) != null) {
            throw new IllegalStateException("A creation signal collector already owns " + logicalId);
        }
        return collector;
    }

    void signal(String stackId, String logicalId, String uniqueId, String status, Consumer<String> event) {
        if (logicalId == null || logicalId.isBlank()) {
            throw invalid("LogicalResourceId is required.");
        }
        if (uniqueId == null || uniqueId.isEmpty() || uniqueId.length() > 64) {
            throw invalid("UniqueId must contain from 1 to 64 characters.");
        }
        if (!"SUCCESS".equals(status) && !"FAILURE".equals(status)) {
            throw invalid("Status must be SUCCESS or FAILURE.");
        }
        Pending collector = pending.get(new Key(stackId, logicalId));
        if (collector == null) {
            throw invalid("Resource " + logicalId + " is not waiting for creation signals.");
        }
        collector.signal(uniqueId, status, event);
    }

    void finish(String stackId, String logicalId, Pending collector) {
        if (collector != null) {
            pending.remove(new Key(stackId, logicalId), collector);
        }
    }

    void finishStack(String stackId) {
        pending.keySet().removeIf(key -> key.stackId().equals(stackId));
    }

    private record Key(String stackId, String logicalId) {}

    record Policy(int count, Duration timeout) {
        private static Policy parse(String type, JsonNode policy, JsonNode properties) {
            if (policy == null || policy.isMissingNode() || policy.isNull()) {
                return null;
            }
            requireFields(policy, Set.of("ResourceSignal", "AutoScalingCreationPolicy"), "CreationPolicy");
            if (policy.isEmpty()) {
                return null;
            }
            boolean autoScaling = "AWS::AutoScaling::AutoScalingGroup".equals(type);
            if (!autoScaling && !"AWS::EC2::Instance".equals(type)) {
                throw invalid("CreationPolicy resource signals are not supported for " + type + ".");
            }
            JsonNode signals = policy.path("ResourceSignal");
            if (!signals.isMissingNode()) {
                requireFields(signals, Set.of("Count", "Timeout"), "ResourceSignal");
            }
            int count = integer(signals.path("Count"), 1, Integer.MAX_VALUE, "ResourceSignal.Count");
            JsonNode scaling = policy.path("AutoScalingCreationPolicy");
            if (!scaling.isMissingNode()) {
                if (!autoScaling) {
                    throw invalid("AutoScalingCreationPolicy requires an AutoScalingGroup.");
                }
                requireFields(scaling, Set.of("MinSuccessfulInstancesPercent"), "AutoScalingCreationPolicy");
                int percent = integer(scaling.path("MinSuccessfulInstancesPercent"), 100, 100,
                        "AutoScalingCreationPolicy.MinSuccessfulInstancesPercent");
                JsonNode capacity = properties.has("DesiredCapacity")
                        ? properties.path("DesiredCapacity") : properties.path("MinSize");
                int instances = integer(capacity, 0, Integer.MAX_VALUE, "AutoScalingGroup initial capacity");
                count = Math.max(count, (int) (((long) instances * percent + 99) / 100));
            }
            String duration = signals.path("Timeout").isMissingNode() ? "PT5M" : signals.path("Timeout").asText();
            Duration timeout;
            try {
                if (!duration.matches("PT(?=[0-9])(?:[0-9]+H)?(?:[0-9]+M)?(?:[0-9]+S)?")) {
                    throw invalid("ResourceSignal.Timeout must use the PT#H#M#S duration format.");
                }
                timeout = Duration.parse(duration);
            } catch (DateTimeParseException failure) {
                throw invalid("ResourceSignal.Timeout is not a valid duration.");
            }
            if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofHours(12)) > 0) {
                throw invalid("ResourceSignal.Timeout must be positive and no greater than 12 hours.");
            }
            return new Policy(count, timeout);
        }

        private static int integer(JsonNode value, int fallback, int maximum, String name) {
            if (value.isMissingNode()) {
                return fallback;
            }
            try {
                String text = value.asText();
                if (!text.matches("[0-9]+")) {
                    throw invalid(name + " must be a nonnegative integer.");
                }
                int parsed = Integer.parseInt(text);
                if (parsed > maximum) {
                    throw invalid(name + " must not exceed " + maximum + ".");
                }
                return parsed;
            } catch (NumberFormatException failure) {
                throw invalid(name + " is outside the supported integer range.");
            }
        }

        private static void requireFields(JsonNode object, Set<String> fields, String name) {
            if (!object.isObject()) {
                throw invalid(name + " must be an object.");
            }
            object.fieldNames().forEachRemaining(field -> {
                if (!fields.contains(field)) {
                    throw invalid(name + " does not support " + field + ".");
                }
            });
        }
    }

    static final class Pending {
        private final Policy policy;
        private final TimedWait timedWait;
        private final Set<String> successes = new HashSet<>();
        private final CompletableFuture<String> completion = new CompletableFuture<>();

        private Pending(Policy policy, TimedWait timedWait) {
            this.policy = policy;
            this.timedWait = timedWait;
            if (policy.count() == 0) {
                completion.complete(null);
            }
        }

        synchronized void signal(String uniqueId, String status, Consumer<String> event) {
            if (completion.isDone()) {
                return;
            }
            if ("FAILURE".equals(status)) {
                String failure = "Received FAILURE resource signal with UniqueId " + uniqueId + ".";
                event.accept(failure);
                completion.complete(failure);
                return;
            }
            if (!successes.add(uniqueId)) {
                return;
            }
            event.accept("Received SUCCESS resource signal with UniqueId " + uniqueId + ": "
                    + successes.size() + " of " + policy.count() + " required signals.");
            if (successes.size() >= policy.count()) {
                completion.complete(null);
            }
        }

        String await() {
            try {
                return timedWait.get(completion, policy.timeout().toNanos());
            } catch (TimeoutException failure) {
                String reason;
                synchronized (this) {
                    reason = "ResourceSignal timed out after " + policy.timeout() + ": received "
                            + successes.size() + " of " + policy.count() + " required success signals.";
                    completion.complete(reason);
                }
                return completion.getNow(reason);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                String reason = "Interrupted while waiting for resource creation signals.";
                synchronized (this) {
                    completion.complete(reason);
                }
                return completion.getNow(reason);
            } catch (ExecutionException failure) {
                throw new IllegalStateException("Cannot read resource creation signals", failure);
            }
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", message, 400);
    }
}
