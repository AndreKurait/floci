package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.DeploymentConfigDoesNotExistException;
import software.amazon.awssdk.services.codedeploy.model.DeploymentConfigInfo;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class CloudFormationCodeDeployDeploymentConfigIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void createsARealLambdaCanaryConfigurationAndDeletesItWithTheStack(String region) {
        String stack = "cfn-deployment-config-" + UUID.randomUUID();
        String name = "canary-" + UUID.randomUUID();
        try (CloudFormationClient cfn = AwsTestClients.create(
                CloudFormationClient.builder(), "000000000000", Region.of(region));
             CodeDeployClient deployments = AwsTestClients.create(
                     CodeDeployClient.builder(), "000000000000", Region.of(region))) {
            try {
                cfn.createStack(request -> request.stackName(stack).templateBody("""
                        {"Parameters":{"Percentage":{"Type":"Number","Default":25}},
                         "Resources":{"Config":{"Type":"AWS::CodeDeploy::DeploymentConfig","Properties":{
                           "DeploymentConfigName":"%s","ComputePlatform":"Lambda",
                           "TrafficRoutingConfig":{"Type":"TimeBasedCanary","TimeBasedCanary":{
                             "CanaryPercentage":{"Ref":"Percentage"},"CanaryInterval":5}}}}},
                         "Outputs":{"Config":{"Value":{"Ref":"Config"}}}}
                        """.formatted(name)));
                await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                        .until(() -> !cfn.describeStacks(request -> request.stackName(stack)).stacks()
                                .getFirst().stackStatusAsString().endsWith("_IN_PROGRESS"));
                assertEquals("CREATE_COMPLETE", cfn.describeStacks(request -> request.stackName(stack))
                        .stacks().getFirst().stackStatusAsString());
                DeploymentConfigInfo config = assertDoesNotThrow(() -> deployments
                        .getDeploymentConfig(request -> request.deploymentConfigName(name)).deploymentConfigInfo());
                assertEquals(name, cfn.describeStacks(request -> request.stackName(stack)).stacks()
                        .getFirst().outputs().getFirst().outputValue());
                assertEquals(name, config.deploymentConfigName());
                assertNotNull(config.deploymentConfigId());
                assertEquals("Lambda", config.computePlatformAsString());
                assertEquals("TimeBasedCanary", config.trafficRoutingConfig().typeAsString());
                assertEquals(25, config.trafficRoutingConfig().timeBasedCanary().canaryPercentage());
                assertEquals(5, config.trafficRoutingConfig().timeBasedCanary().canaryInterval());
            } finally {
                if (cfn.describeStacks().stacks().stream().anyMatch(value -> stack.equals(value.stackName()))) {
                    cfn.deleteStack(request -> request.stackName(stack));
                    await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                            .untilAsserted(() -> assertFalse(cfn.describeStacks().stacks().stream()
                                    .anyMatch(value -> stack.equals(value.stackName()))));
                }
                assertThrows(DeploymentConfigDoesNotExistException.class,
                        () -> deployments.getDeploymentConfig(request -> request.deploymentConfigName(name)));
            }
        }
    }
}
