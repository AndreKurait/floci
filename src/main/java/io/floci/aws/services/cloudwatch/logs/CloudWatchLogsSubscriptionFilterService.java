package io.floci.aws.services.cloudwatch.logs;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.cloudwatch.logs.filter.FilterPattern;
import io.floci.aws.services.cloudwatch.logs.model.MetricFilter;
import io.floci.aws.services.cloudwatch.logs.model.SubscriptionFilter;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Internal CloudFormation lifecycle operations over the native subscription store.
 * The public Logs API retains its upsert behavior in CloudWatchLogsService.
 */
@ApplicationScoped
public class CloudWatchLogsSubscriptionFilterService {

    public enum MutationOutcome { APPLIED, NOT_APPLIED, UNKNOWN }

    /** Presence is null when inspection could not establish the storage outcome. */
    public record MutationResult(MutationOutcome outcome, Boolean present) {}

    private final CloudWatchLogsService logs;
    private final CloudWatchLogsSubscriptionAdmissionService admission;
    private final StorageBackend<String, SubscriptionFilter> store;
    private final StorageBackend<String, MetricFilter> monitor;

    public CloudWatchLogsSubscriptionFilterService(CloudWatchLogsService logs,
                                                    CloudWatchLogsSubscriptionAdmissionService admission) {
        this.logs = logs;
        this.admission = admission;
        this.store = logs.subscriptionFilterStore();
        this.monitor = logs.metricFilterStore();
    }

    public Optional<SubscriptionFilter> findSubscriptionFilter(String group, String name, String region) {
        synchronized (monitor) {
            return store.get(key(group, name, region)).map(CloudWatchLogsSubscriptionFilterService::copy);
        }
    }

    /** A create-only write cannot acquire ownership of an existing native filter. */
    public void createSubscriptionFilter(SubscriptionFilter definition, String region,
                                         Consumer<MutationResult> outcome) {
        put(definition, region, true, false, outcome);
    }

    public void updateSubscriptionFilter(SubscriptionFilter definition, String region,
                                         Consumer<MutationResult> outcome) {
        put(definition, region, false, false, outcome);
    }

    /** Restores every stored member, including the original creation time. */
    public void restoreSubscriptionFilter(SubscriptionFilter definition, String region, boolean createOnly,
                                          Consumer<MutationResult> outcome) {
        put(definition, region, createOnly, true, outcome);
    }

    private void put(SubscriptionFilter definition, String region, boolean createOnly,
                     boolean restore, Consumer<MutationResult> outcome) {
        synchronized (monitor) {
            SubscriptionFilter before = null;
            SubscriptionFilter after;
            Boolean present = null;
            String key;
            try {
                String group = definition.getLogGroupName();
                String name = definition.getFilterName();
                if (group == null || name == null || !group.matches("[.\\-_/#A-Za-z0-9]{1,512}")
                        || !name.matches("[^:*]{1,512}")) {
                    throw invalid("The log group and filter names must be valid.");
                }
                key = key(group, name, region);
                before = store.get(key).orElse(null);
                present = before != null;
                if (createOnly && before != null) {
                    throw new AwsException("AlreadyExistsException",
                            "Subscription filter " + name + " already exists in " + group, 400);
                }
                if (!logs.logGroupExists(group, region)) {
                    throw new AwsException("ResourceNotFoundException",
                            "The specified log group does not exist.", 400);
                }
                FilterPattern pattern = CloudWatchLogsService.requireFilterPattern(definition.getFilterPattern());
                validateDestination(definition.getDestinationArn(), region);
                admission.validate(definition.getDestinationArn(), group, region,
                        logs.regionResolver().getAccountId());
                String distribution = definition.getDistribution();
                if (distribution != null && !Set.of("Random", "ByLogStream").contains(distribution)) {
                    throw invalid("distribution must be Random or ByLogStream.");
                }
                logs.validateFilterRegexQuota(group, name, pattern, false, region);
                after = copy(definition);
                after.setDistribution(distribution == null ? "ByLogStream" : distribution);
                if (!restore) {
                    after.setCreationTime(before == null ? System.currentTimeMillis() : before.getCreationTime());
                }
            } catch (RuntimeException failure) {
                record(new MutationResult(MutationOutcome.NOT_APPLIED, present), outcome, failure);
                throw failure;
            }
            mutate(key, before, after, outcome);
        }
    }

    public void deleteSubscriptionFilter(String group, String name, String region,
                                         Consumer<MutationResult> outcome) {
        synchronized (monitor) {
            String key = key(group, name, region);
            SubscriptionFilter before;
            try {
                before = store.get(key).orElse(null);
            } catch (RuntimeException failure) {
                record(new MutationResult(MutationOutcome.NOT_APPLIED, null), outcome, failure);
                throw failure;
            }
            if (before == null) {
                record(new MutationResult(MutationOutcome.NOT_APPLIED, false), outcome, null);
            } else {
                mutate(key, before, null, outcome);
            }
        }
    }

    /** An uncertain write may be retired only after confirming absence under the shared monitor. */
    public boolean confirmSubscriptionFilterAbsent(String group, String name, String region, Runnable onAbsent) {
        synchronized (monitor) {
            if (store.get(key(group, name, region)).isPresent()) {
                return false;
            }
            onAbsent.run();
            return true;
        }
    }

    private void mutate(String key, SubscriptionFilter before, SubscriptionFilter after,
                        Consumer<MutationResult> outcome) {
        RuntimeException failure = null;
        MutationResult result = new MutationResult(MutationOutcome.APPLIED, after != null);
        try {
            if (after == null) {
                store.deleteAllAtomically(Set.of(key));
            } else {
                store.putAllAtomically(Map.of(key, after));
            }
        } catch (RuntimeException storageFailure) {
            failure = storageFailure;
            result = new MutationResult(MutationOutcome.UNKNOWN, null);
            try {
                SubscriptionFilter current = store.get(key).orElse(null);
                if (current == after) {
                    result = new MutationResult(MutationOutcome.APPLIED, current != null);
                } else if (current == before) {
                    result = new MutationResult(MutationOutcome.NOT_APPLIED, current != null);
                }
            } catch (RuntimeException inspectionFailure) {
                if (storageFailure != inspectionFailure) {
                    storageFailure.addSuppressed(inspectionFailure);
                }
            }
        }
        record(result, outcome, failure);
    }

    private static void record(MutationResult result, Consumer<MutationResult> outcome, RuntimeException failure) {
        try {
            outcome.accept(result);
        } catch (RuntimeException recordingFailure) {
            if (failure == null) {
                failure = recordingFailure;
            } else if (failure != recordingFailure) {
                failure.addSuppressed(recordingFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String key(String group, String name, String region) {
        return CloudWatchLogsService.subscriptionFilterKeyPrefix(region, group) + name;
    }

    private static SubscriptionFilter copy(SubscriptionFilter original) {
        SubscriptionFilter copy = new SubscriptionFilter();
        copy.setFilterName(original.getFilterName());
        copy.setLogGroupName(original.getLogGroupName());
        copy.setFilterPattern(original.getFilterPattern());
        copy.setDestinationArn(original.getDestinationArn());
        copy.setDistribution(original.getDistribution());
        copy.setCreationTime(original.getCreationTime());
        copy.setApplyOnTransformedLogs(original.getApplyOnTransformedLogs());
        return copy;
    }

    private void validateDestination(String value, String region) {
        if (!AwsArnUtils.isArn(value)) {
            throw invalid("destinationArn must be a valid ARN.");
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(value);
        if (!region.equals(arn.region()) || !logs.regionResolver().partitionForRegion(region).equals(arn.partition())
                || !arn.accountId().matches("[0-9]{12}")) {
            throw invalid("The subscription destination must use the log group's region and partition.");
        }
        if ("lambda".equals(arn.service()) && logs.regionResolver().getAccountId().equals(arn.accountId())
                && arn.resource().matches("function:[A-Za-z0-9_-]{1,64}(?::(?:\\$LATEST|[A-Za-z0-9_-]+))?")) {
            return;
        }
        if ("logs".equals(arn.service()) && arn.resource().matches("destination:[^:*]{1,512}")) {
            return;
        }
        throw invalid("Native CloudFormation subscription filters support same-account Lambda functions and logical "
                + "Logs destinations. Direct role-backed destinations are not supported.");
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
