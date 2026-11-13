package io.floci.aws.testing;

import io.floci.aws.services.cloudwatch.metrics.CloudWatchPolicyClockEvaluator;
import io.floci.aws.services.cloudwatch.metrics.CloudWatchPolicyClockService;
import io.floci.aws.services.dynamodb.DynamoDbPolicyClockMetrics;
import io.floci.aws.services.dynamodb.DynamoDbPolicyClockService;
import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;
import java.util.Set;

/** No global alternative priority: ordinary application and regression profiles stay unchanged. */
public class PolicyWorkloadClockProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        // Exclude the original beans as well as selecting their subclasses: the original
        // AlarmEvaluator's StartupEvent observer must not start a second real-time scheduler.
        return Map.of("quarkus.arc.exclude-types",
                "io.floci.aws.services.dynamodb.DynamoDbService,"
                        + "io.floci.aws.services.dynamodb.DynamoDbCapacityMetrics,"
                        + "io.floci.aws.services.cloudwatch.metrics.CloudWatchMetricsService,"
                        + "io.floci.aws.services.cloudwatch.metrics.AlarmEvaluator");
    }

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(DynamoDbPolicyClockService.class, DynamoDbPolicyClockMetrics.class,
                CloudWatchPolicyClockService.class, CloudWatchPolicyClockEvaluator.class);
    }
}
