package io.floci.aws.services.dynamodb;

import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;

import java.time.Duration;
import java.util.function.LongSupplier;

final class DynamoDbCleanupDeadline {
    private final LongSupplier nanoTime;
    private final long started;

    DynamoDbCleanupDeadline(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.started = nanoTime.getAsLong();
    }

    AwsRequestOverrideConfiguration requestTimeout() {
        long remaining = Duration.ofSeconds(3).toNanos() - (nanoTime.getAsLong() - started);
        if (remaining <= 0) {
            throw new AssertionError("Owned table cleanup deadline expired before SDK request");
        }
        Duration timeout = Duration.ofNanos(remaining);
        return AwsRequestOverrideConfiguration.builder()
                .apiCallTimeout(timeout).apiCallAttemptTimeout(timeout).build();
    }
}
