package io.floci.aws.services.ecs;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.CapacityProviderStrategyItem;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.RunTaskResponse;
import software.amazon.awssdk.services.ecs.model.Task;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EcsDefaultLaunchTypeIntegrationTest {

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "true,true,false", "false,false,true"})
    void preservesImplicitFargateAndExplicitPlacement(boolean registerInstance, boolean explicitEc2,
                                                     boolean defaultStrategy) throws Exception {
        String name = "launch-default-" + UUID.randomUUID();
        try (EcsClient client = AwsTestClients.create(EcsClient.builder()
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))),
                "test", Region.US_EAST_1)) {
            String cluster = client.createCluster(request -> request.clusterName(name)
                    .capacityProviders(defaultStrategy ? List.of("FARGATE") : List.of())
                    .defaultCapacityProviderStrategy(defaultStrategy
                            ? List.of(CapacityProviderStrategyItem.builder()
                                    .capacityProvider("FARGATE").weight(1).build()) : List.of()))
                    .cluster().clusterArn();
            try (AutoCloseable _ = () -> client.deleteCluster(request -> request.cluster(cluster))) {
                String definition = client.registerTaskDefinition(request -> request.family(name)
                        .containerDefinitions(container -> container.name("app").image("alpine:3.22")
                                .memory(128).essential(true))).taskDefinition().taskDefinitionArn();
                try (AutoCloseable _ = () -> client.deregisterTaskDefinition(request ->
                        request.taskDefinition(definition))) {
                    String instance = registerInstance
                            ? client.registerContainerInstance(request -> request.cluster(cluster)
                                    .instanceIdentityDocument("""
                                            {"instanceId":"i-0123456789abcdef0",
                                             "availabilityZone":"us-east-1b","instanceType":"m5.large"}
                                            """)).containerInstance().containerInstanceArn()
                            : null;
                    try (AutoCloseable _ = () -> {
                        if (instance != null) {
                            client.deregisterContainerInstance(request -> request.cluster(cluster)
                                    .containerInstance(instance).force(true));
                        }
                    }) {
                        RunTaskResponse response = assertDoesNotThrow(() -> client.runTask(request ->
                                request.cluster(cluster).taskDefinition(definition)
                                        .launchType(explicitEc2 ? LaunchType.EC2 : null)));
                        try (AutoCloseable _ = () -> {
                            for (Task task : response.tasks()) {
                                client.stopTask(request -> request.cluster(cluster).task(task.taskArn()));
                            }
                        }) {
                            assertTrue(response.failures().isEmpty());
                            assertEquals(1, response.tasks().size());
                            Task task = response.tasks().getFirst();
                            assertEquals(explicitEc2 ? LaunchType.EC2 : LaunchType.FARGATE, task.launchType());
                            if (explicitEc2) {
                                assertEquals(instance, task.containerInstanceArn());
                                assertEquals("us-east-1b", task.availabilityZone());
                            } else {
                                assertNull(task.containerInstanceArn());
                            }
                            if (defaultStrategy) {
                                assertEquals("FARGATE", task.capacityProviderName());
                            }
                        }
                    }
                }
            }
        }
    }
}
