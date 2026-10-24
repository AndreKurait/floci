package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.ElasticLoadBalancingV2Exception;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ELB v2 target port validation")
class ElbV2TargetPortTest {
    private static ElasticLoadBalancingV2Client client;

    @BeforeAll
    static void setup() {
        client = TestFixtures.elbV2Client();
    }

    @AfterAll
    static void close() {
        if (client != null) {
            client.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 65536})
    @DisplayName("Out-of-range target-group ports return a modeled validation error")
    void invalidGroupPort(int port) {
        ElasticLoadBalancingV2Exception error = assertThrows(ElasticLoadBalancingV2Exception.class,
                () -> {
                    String unexpectedArn = client.createTargetGroup(r -> r.name(TestFixtures.uniqueName("port-invalid"))
                            .protocol("HTTP").port(port).targetType("ip").vpcId("vpc-test"))
                            .targetGroups().get(0).targetGroupArn();
                    client.deleteTargetGroup(r -> r.targetGroupArn(unexpectedArn));
                });
        assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
        assertThat(error.statusCode()).isEqualTo(400);
    }

    @ParameterizedTest
    @CsvSource({"register,0", "register,65536", "deregister,0", "deregister,65536", "health,0", "health,65536"})
    @DisplayName("Every target operation validates port ranges before resolving the group")
    void invalidTargetPort(String operation, int port) {
        List<TargetDescription> targets = List.of(TargetDescription.builder().id("10.0.0.5").port(port).build());
        ElasticLoadBalancingV2Exception error = assertThrows(ElasticLoadBalancingV2Exception.class, () -> {
            switch (operation) {
                case "register" -> client.registerTargets(r -> r.targetGroupArn("missing-group").targets(targets));
                case "deregister" -> client.deregisterTargets(r -> r.targetGroupArn("missing-group").targets(targets));
                case "health" -> client.describeTargetHealth(r -> r.targetGroupArn("missing-group").targets(targets));
                default -> throw new IllegalArgumentException(operation);
            }
        });
        assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationError");
        assertThat(error.statusCode()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 65535})
    @DisplayName("Boundary ports register and deregister through omitted-port normalization")
    void boundaryPort(int port) {
        String arn = client.createTargetGroup(r -> r.name(TestFixtures.uniqueName("port-valid"))
                .protocol("HTTP").port(port).targetType("ip").vpcId("vpc-test"))
                .targetGroups().get(0).targetGroupArn();
        try {
            client.registerTargets(r -> r.targetGroupArn(arn).targets(
                    TargetDescription.builder().id("10.0.0.5").build(),
                    TargetDescription.builder().id("10.0.0.5").port(port).build()));
            assertThat(client.describeTargetHealth(r -> r.targetGroupArn(arn)).targetHealthDescriptions())
                    .singleElement().satisfies(t -> assertThat(t.target().port()).isEqualTo(port));
            client.deregisterTargets(r -> r.targetGroupArn(arn)
                    .targets(TargetDescription.builder().id("10.0.0.5").build()));
            assertThat(client.describeTargetHealth(r -> r.targetGroupArn(arn)).targetHealthDescriptions()).isEmpty();
        } finally {
            client.deleteTargetGroup(r -> r.targetGroupArn(arn));
        }
    }
}
