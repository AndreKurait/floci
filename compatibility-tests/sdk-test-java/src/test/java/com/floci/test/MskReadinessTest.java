package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.kafka.KafkaClient;
import software.amazon.awssdk.services.kafka.model.ClusterInfo;
import software.amazon.awssdk.services.kafka.model.ClusterState;
import software.amazon.awssdk.services.kafka.model.CreateClusterRequest;
import software.amazon.awssdk.services.kafka.model.CreateClusterResponse;
import software.amazon.awssdk.services.kafka.model.DescribeClusterRequest;
import software.amazon.awssdk.services.kafka.model.DescribeClusterResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MskReadinessTest {

    @Test
    void createWaitsForActiveBeforeLaterOrderedOperations() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        KafkaClient previous = MskTest.kafka;
        try (KafkaClient client = client(calls, ClusterState.CREATING, ClusterState.ACTIVE)) {
            MskTest.kafka = client;
            new MskTest().createCluster();
            assertThat(calls).hasValue(2);
        } finally {
            MskTest.kafka = previous;
        }
    }

    @Test
    void waitsThroughCreatingUntilActive() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        try (KafkaClient client = client(calls, ClusterState.CREATING, ClusterState.CREATING, ClusterState.ACTIVE)) {
            MskTest.awaitActive(client, "cluster", Duration.ofSeconds(1), 0);
            assertThat(calls).hasValue(3);
        }
    }

    @Test
    void activeReturnsImmediately() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        try (KafkaClient client = client(calls, ClusterState.ACTIVE)) {
            MskTest.awaitActive(client, "cluster", Duration.ofSeconds(1), 0);
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void terminalFailureDoesNotPollAgain() {
        assertTerminal(ClusterState.FAILED);
    }

    @Test
    void deletingDoesNotPollAgain() {
        assertTerminal(ClusterState.DELETING);
    }

    @Test
    void expiredDeadlineDoesNotCallService() {
        AtomicInteger calls = new AtomicInteger();
        try (KafkaClient client = client(calls, ClusterState.CREATING)) {
            assertThatThrownBy(() -> MskTest.awaitActive(client, "cluster", Duration.ZERO, 0))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("timed out waiting for ACTIVE");
            assertThat(calls).hasValue(0);
        }
    }

    private static void assertTerminal(ClusterState state) {
        AtomicInteger calls = new AtomicInteger();
        try (KafkaClient client = client(calls, state)) {
            assertThatThrownBy(() -> MskTest.awaitActive(client, "cluster", Duration.ofSeconds(1), 0))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("must become ACTIVE");
            assertThat(calls).hasValue(1);
        }
    }

    private static KafkaClient client(AtomicInteger calls, ClusterState... states) {
        return new KafkaClient() {
            @Override
            public CreateClusterResponse createCluster(CreateClusterRequest request) {
                return CreateClusterResponse.builder().clusterArn("cluster").clusterName(request.clusterName())
                        .state(ClusterState.CREATING).build();
            }

            @Override
            public DescribeClusterResponse describeCluster(DescribeClusterRequest request) {
                assertThat(request.clusterArn()).isEqualTo("cluster");
                int index = calls.getAndIncrement();
                assertThat(index).isLessThan(states.length);
                return DescribeClusterResponse.builder().clusterInfo(ClusterInfo.builder()
                        .state(states[index]).build()).build();
            }

            @Override
            public String serviceName() {
                return "kafka";
            }

            @Override
            public void close() {
            }
        };
    }
}
