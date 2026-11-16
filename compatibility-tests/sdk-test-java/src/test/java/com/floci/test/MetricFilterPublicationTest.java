package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MetricFilterPublicationTest {

    @Test
    void waitsForEveryBatchEvenWhenTheFirstDatapointIsAlreadyVisible() throws InterruptedException {
        Datapoint partial = Datapoint.builder().sum(3.0).sampleCount(1.0).build();
        Datapoint complete = Datapoint.builder().sum(13.0).sampleCount(3.0).build();
        Queue<List<Datapoint>> reads = new ArrayDeque<>(List.of(List.of(partial), List.of(complete)));
        assertThat(CloudWatchLogsMetricFilterTest.awaitPublishedSamples(reads::remove, 3, Duration.ofSeconds(1)))
                .containsExactly(complete);
        assertThat(reads).isEmpty();
    }

    @Test
    void waitsThroughAnInitiallyAbsentSeries() throws InterruptedException {
        Datapoint complete = Datapoint.builder().sum(13.0).sampleCount(3.0).build();
        Queue<List<Datapoint>> reads = new ArrayDeque<>(List.of(List.of(), List.of(complete)));
        assertThat(CloudWatchLogsMetricFilterTest.awaitPublishedSamples(reads::remove, 3, Duration.ofSeconds(1)))
                .containsExactly(complete);
    }

    @Test
    void returnsIncompleteDataAtDeadlineForTheExistingExactAssertions() throws InterruptedException {
        Datapoint partial = Datapoint.builder().sum(3.0).sampleCount(1.0).build();
        AtomicInteger reads = new AtomicInteger();
        assertThat(CloudWatchLogsMetricFilterTest.awaitPublishedSamples(() -> {
            reads.incrementAndGet();
            return List.of(partial);
        }, 3, Duration.ZERO)).containsExactly(partial);
        assertThat(reads).hasValue(1);
    }

    @Test
    void leavesWrongSumsExcessSamplesAndMultipleSeriesToTheExactAssertions() throws InterruptedException {
        Datapoint wrongSum = Datapoint.builder().sum(99.0).sampleCount(3.0).build();
        Datapoint excess = Datapoint.builder().sum(20.0).sampleCount(4.0).build();
        for (List<Datapoint> points : List.of(List.of(wrongSum), List.of(excess), List.of(wrongSum, excess))) {
            Queue<List<Datapoint>> reads = new ArrayDeque<>(List.of(points));
            assertThat(CloudWatchLogsMetricFilterTest.awaitPublishedSamples(reads::remove, 3, Duration.ofSeconds(1)))
                    .containsExactlyElementsOf(points);
            assertThat(reads).isEmpty();
        }
    }
}
