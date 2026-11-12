package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.ResourceChange;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.DeploymentConfigDoesNotExistException;
import software.amazon.awssdk.services.codedeploy.model.DeploymentConfigInfo;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationCodeDeployDeploymentConfigLifecycleIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void generatedNamesStayStableUntilReplacementAndRollbackRemovesOnlyTheReplacement(String region) {
        try (Fixture fixture = new Fixture("000000000000", region)) {
            DeploymentConfigInfo original = fixture.create(canary(25));
            fixture.assertReplacementPreview(canary(50));
            DeploymentConfigInfo unchanged = fixture.update(canary(25)
                    .replace("\"CanaryPercentage\":25", "\"CanaryPercentage\":\"25\""), false, "UPDATE_COMPLETE");
            assertEquals(original.deploymentConfigName(), unchanged.deploymentConfigName());
            assertEquals(original.deploymentConfigId(), unchanged.deploymentConfigId());

            DeploymentConfigInfo replacement = fixture.update("""
                    {"ComputePlatform":"Lambda","TrafficRoutingConfig":{"Type":"TimeBasedLinear",
                     "TimeBasedLinear":{"LinearPercentage":50,"LinearInterval":2}}}
                    """, false, "UPDATE_COMPLETE");
            assertNotEquals(original.deploymentConfigName(), replacement.deploymentConfigName());
            assertEquals(50, replacement.trafficRoutingConfig().timeBasedLinear().linearPercentage());
            fixture.assertGone(original.deploymentConfigName());

            DeploymentConfigInfo restored = fixture.update(canary(75), true, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(replacement.deploymentConfigName(), restored.deploymentConfigName());
            assertEquals(replacement.deploymentConfigId(), restored.deploymentConfigId());
            assertEquals(Set.of(restored.deploymentConfigName()), fixture.generatedConfigurations());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void serverConfigurationMapsHostCountsAndZoneDurationsAndRequiresANewExplicitName(String region) {
        try (Fixture fixture = new Fixture("000000000000", region)) {
            String first = fixture.stack + "-named";
            String properties = """
                    {"DeploymentConfigName":"%s",
                     "MinimumHealthyHosts":{"Type":"FLEET_PERCENT","Value":25},
                     "ZonalConfig":{"FirstZoneMonitorDurationInSeconds":900,
                       "MonitorDurationInSeconds":"1800",
                       "MinimumHealthyHostsPerZone":{"Type":"HOST_COUNT","Value":"1"}}}
                    """.formatted(first);
            DeploymentConfigInfo original = fixture.create(properties);
            assertEquals("Server", original.computePlatformAsString());
            assertEquals("FLEET_PERCENT", original.minimumHealthyHosts().typeAsString());
            assertEquals(25, original.minimumHealthyHosts().value());
            assertEquals(900L, original.zonalConfig().firstZoneMonitorDurationInSeconds());
            assertEquals(1800L, original.zonalConfig().monitorDurationInSeconds());
            assertEquals("HOST_COUNT", original.zonalConfig().minimumHealthyHostsPerZone().typeAsString());
            assertEquals(1, original.zonalConfig().minimumHealthyHostsPerZone().value());

            String changed = properties.replace("\"Value\":25", "\"Value\":50");
            DeploymentConfigInfo rejected = fixture.update(changed, false, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(original.deploymentConfigId(), rejected.deploymentConfigId());
            assertEquals(25, rejected.minimumHealthyHosts().value());

            DeploymentConfigInfo renamed = fixture.update(changed.replace(first, first + "-next"),
                    false, "UPDATE_COMPLETE");
            assertEquals(first + "-next", renamed.deploymentConfigName());
            assertEquals(50, renamed.minimumHealthyHosts().value());
            fixture.assertGone(first);
        }
    }

    @Test
    void identicalNamesInDifferentAccountsAndRegionsHaveIndependentLifecycles() {
        String name = "shared-config-" + UUID.randomUUID();
        String properties = "{\"DeploymentConfigName\":\"" + name + "\"," + canary(25).substring(1);
        try (Fixture first = new Fixture("111122223333", "us-east-1");
             Fixture otherAccount = new Fixture("444455556666", "us-east-1");
             Fixture otherRegion = new Fixture("111122223333", "us-gov-west-1")) {
            DeploymentConfigInfo original = first.create(properties);
            DeploymentConfigInfo second = otherAccount.create(properties);
            DeploymentConfigInfo third = otherRegion.create(properties);
            assertNotEquals(original.deploymentConfigId(), second.deploymentConfigId());
            assertNotEquals(original.deploymentConfigId(), third.deploymentConfigId());
            first.delete();
            first.assertGone(name);
            assertEquals(second.deploymentConfigId(), otherAccount.get(name).deploymentConfigId());
            assertEquals(third.deploymentConfigId(), otherRegion.get(name).deploymentConfigId());
        }
    }

    private static String canary(int percentage) {
        return """
                {"ComputePlatform":"Lambda","TrafficRoutingConfig":{"Type":"TimeBasedCanary",
                 "TimeBasedCanary":{"CanaryPercentage":%d,"CanaryInterval":5}}}
                """.formatted(percentage);
    }

    private static final class Fixture implements AutoCloseable {
        private final String stack = "cfg-" + UUID.randomUUID();
        private final CloudFormationClient cfn;
        private final CodeDeployClient deployments;
        private final Set<String> names = new LinkedHashSet<>();
        private int revision;

        private Fixture(String account, String region) {
            cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.of(region));
            deployments = AwsTestClients.create(CodeDeployClient.builder(), account, Region.of(region));
        }

        private DeploymentConfigInfo create(String properties) {
            cfn.createStack(request -> request.stackName(stack).templateBody(template(properties, false)));
            awaitStatus("CREATE_COMPLETE");
            return current();
        }

        private DeploymentConfigInfo update(String properties, boolean failure, String expected) {
            cfn.updateStack(request -> request.stackName(stack).templateBody(template(properties, failure)));
            awaitStatus(expected);
            return current();
        }

        private void assertReplacementPreview(String properties) {
            String changeSet = "preview-" + UUID.randomUUID();
            cfn.createChangeSet(request -> request.stackName(stack).changeSetName(changeSet)
                    .changeSetType("UPDATE").templateBody(template(properties, false)));
            try {
                await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                        .untilAsserted(() -> assertEquals("CREATE_COMPLETE",
                                cfn.describeChangeSet(request -> request.stackName(stack).changeSetName(changeSet))
                                        .statusAsString()));
                ResourceChange change = cfn.describeChangeSet(request -> request.stackName(stack)
                        .changeSetName(changeSet)).changes().getFirst().resourceChange();
                assertEquals("True", change.replacementAsString());
                assertEquals("Modify", change.actionAsString());
                assertEquals("Config", change.logicalResourceId());
                assertEquals("AWS::CodeDeploy::DeploymentConfig", change.resourceType());
            } finally {
                cfn.deleteChangeSet(request -> request.stackName(stack).changeSetName(changeSet));
            }
        }

        private String template(String properties, boolean failure) {
            String dependent = failure ? """
                    ,"Failure":{"Type":"AWS::CodeDeploy::Application","DependsOn":"Config",
                                "Properties":{"ComputePlatform":"invalid"}}
                    """ : "";
            return """
                    {"Description":"revision-%d","Resources":{
                      "Config":{"Type":"AWS::CodeDeploy::DeploymentConfig","Properties":%s}%s},
                     "Outputs":{"Config":{"Value":{"Ref":"Config"}}}}
                    """.formatted(++revision, properties, dependent);
        }

        private void awaitStatus(String expected) {
            await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                    .untilAsserted(() -> assertEquals(expected, cfn.describeStacks(request -> request.stackName(stack))
                            .stacks().getFirst().stackStatusAsString(),
                            () -> cfn.describeStackEvents(request -> request.stackName(stack)).stackEvents().toString()));
        }

        private DeploymentConfigInfo current() {
            String name = cfn.describeStacks(request -> request.stackName(stack)).stacks().getFirst()
                    .outputs().getFirst().outputValue();
            names.add(name);
            return get(name);
        }

        private DeploymentConfigInfo get(String name) {
            return deployments.getDeploymentConfig(request -> request.deploymentConfigName(name)).deploymentConfigInfo();
        }

        private Set<String> generatedConfigurations() {
            Set<String> result = new LinkedHashSet<>();
            deployments.listDeploymentConfigs().deploymentConfigsList().stream()
                    .filter(name -> name.startsWith(stack)).forEach(result::add);
            return result;
        }

        private void assertGone(String name) {
            assertThrows(DeploymentConfigDoesNotExistException.class, () -> get(name));
        }

        private void delete() {
            if (cfn.describeStacks().stacks().stream().anyMatch(value -> stack.equals(value.stackName()))) {
                cfn.deleteStack(request -> request.stackName(stack));
                await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                        .untilAsserted(() -> assertFalse(cfn.describeStacks().stacks().stream()
                                .anyMatch(value -> stack.equals(value.stackName()))));
            }
        }

        @Override
        public void close() {
            try {
                delete();
                names.forEach(this::assertGone);
                assertTrue(generatedConfigurations().isEmpty(), "A replaced configuration was orphaned");
            } finally {
                deployments.close();
                cfn.close();
            }
        }
    }
}
