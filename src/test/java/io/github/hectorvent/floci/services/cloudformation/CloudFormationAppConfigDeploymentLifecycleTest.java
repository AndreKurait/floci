package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.appconfig.AppConfigAlarmMonitor;
import io.github.hectorvent.floci.services.appconfig.AppConfigS3Source;
import io.github.hectorvent.floci.services.appconfig.AppConfigService;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class CloudFormationAppConfigDeploymentLifecycleTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private InMemoryStorageFactory storage;
    private AppConfigService appconfig;
    private CloudFormationService cfn;
    private String app;
    private String environment;
    private String profile;
    private String strategy;
    private int changes;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorageFactory(ACCOUNT);
        appconfig = new AppConfigService(storage, new RegionResolver(REGION, ACCOUNT), mock(AppConfigAlarmMonitor.class),
                mock(AppConfigS3Source.class));
        cfn = CloudFormationServiceFixture.create(CfnProvisionerFixture.builder().appConfig(appconfig).build());
        app = appconfig.createApplication(Map.of("Name", "settings")).getId();
        environment = appconfig.createEnvironment(app, Map.of("Name", "test")).getId();
        profile = appconfig.createConfigurationProfile(app, Map.of("Name", "settings", "LocationUri", "hosted")).getId();
        strategy = appconfig.createDeploymentStrategy(Map.of("Name", "instant",
                "DeploymentDurationInMinutes", 0, "FinalBakeTimeInMinutes", 0, "GrowthFactor", 100)).getId();
        for (String version : List.of("one", "two", "three")) {
            appconfig.createHostedConfigurationVersion(app, profile, version.getBytes(StandardCharsets.UTF_8),
                    "text/plain", null);
        }
        appconfig.startDeployment(app, environment, deploymentProperties("1"));
    }

    @AfterEach
    void close() {
        cfn.stop();
        storage.shutdownAll();
    }

    @Test
    void createsARealDeploymentAndRevertsItWhenTheStackIsDeleted() throws Exception {
        deploy("CREATE", template("2"));
        assertEquals("CREATE_COMPLETE", stack().getStatus(), stack().getStatusReason());
        String physical = stack().getResources().get("Deployment").getPhysicalId();
        String number = stack().getOutputs().get("Number");
        assertEquals(app + "|" + environment + "|" + number, physical);
        assertEquals(number, stack().getOutputs().get("Reference"));
        assertEquals("COMPLETE", stack().getOutputs().get("State"));
        assertEquals("2", appconfig.getActiveVersion(environment, profile));

        cfn.deleteStack("deployment", REGION, ACCOUNT).get(30, TimeUnit.SECONDS);

        assertEquals("1", appconfig.getActiveVersion(environment, profile));
        assertEquals("REVERTED", appconfig.getDeployment(app, environment, Integer.parseInt(number)).getState());
    }

    @Test
    void successfulReplacementDoesNotRevertTheNewDeploymentDuringOldResourceCleanup() throws Exception {
        deploy("CREATE", template("2"));
        String original = stack().getResources().get("Deployment").getPhysicalId();

        deploy("UPDATE", template("3"));

        assertEquals("UPDATE_COMPLETE", stack().getStatus(), stack().getStatusReason());
        assertNotEquals(original, stack().getResources().get("Deployment").getPhysicalId());
        assertEquals("3", appconfig.getActiveVersion(environment, profile));
        assertEquals("COMPLETE", currentDeployment().getState());
    }

    @Test
    void aLaterFailedResourceRevertsTheReplacementAndRestoresThePriorStackIdentity() throws Exception {
        deploy("CREATE", template("2"));
        String original = stack().getResources().get("Deployment").getPhysicalId();
        ObjectNode update = template("3");
        addInvalidResource(update);

        deploy("UPDATE", update);

        assertEquals("UPDATE_ROLLBACK_COMPLETE", stack().getStatus(), stack().getStatusReason());
        assertEquals(original, stack().getResources().get("Deployment").getPhysicalId());
        assertEquals("2", appconfig.getActiveVersion(environment, profile));
        assertEquals("COMPLETE", currentDeployment().getState());
        assertEquals("REVERTED", appconfig.listDeployments(app, environment, 50, null).items().getFirst().getState());
    }

    @Test
    void aFailedStackCreateRevertsItsDeployment() throws Exception {
        ObjectNode create = template("2");
        addInvalidResource(create);

        deploy("CREATE", create);

        assertEquals("ROLLBACK_COMPLETE", stack().getStatus(), stack().getStatusReason());
        assertEquals("1", appconfig.getActiveVersion(environment, profile));
        assertEquals("REVERTED", appconfig.listDeployments(app, environment, 50, null).items().getFirst().getState());
    }

    @Test
    void deletingAnOlderStackLeavesANewerExternalDeploymentActive() throws Exception {
        deploy("CREATE", template("2"));
        Deployment external = appconfig.startDeployment(app, environment, deploymentProperties("3"));

        cfn.deleteStack("deployment", REGION, ACCOUNT).get(30, TimeUnit.SECONDS);

        assertEquals("3", appconfig.getActiveVersion(environment, profile));
        assertEquals("COMPLETE", appconfig.getDeployment(app, environment, external.getDeploymentNumber()).getState());
    }

    @Test
    void aRetainedDeploymentStaysActiveAfterStackDeletion() throws Exception {
        ObjectNode template = template("2");
        ((ObjectNode) template.path("Resources").path("Deployment")).put("DeletionPolicy", "Retain");
        deploy("CREATE", template);
        int number = currentDeployment().getDeploymentNumber();

        cfn.deleteStack("deployment", REGION, ACCOUNT).get(30, TimeUnit.SECONDS);

        assertEquals("2", appconfig.getActiveVersion(environment, profile));
        assertEquals("COMPLETE", appconfig.getDeployment(app, environment, number).getState());
    }

    @Test
    void missingPriorContentMakesDeletionFailWithoutChangingTheActiveConfiguration() throws Exception {
        deploy("CREATE", template("2"));
        appconfig.deleteHostedConfigurationVersion(app, profile, 1);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> cfn.deleteStack("deployment", REGION, ACCOUNT).get(30, TimeUnit.SECONDS));

        assertTrue(failure.getCause().getMessage().contains("failed to delete: [Deployment]"));
        assertEquals("DELETE_FAILED", stack().getStatus());
        assertTrue(stack().getResources().get("Deployment").getStatusReason()
                .contains("previous hosted configuration version"));
        assertEquals("2", appconfig.getActiveVersion(environment, profile));
        assertEquals("COMPLETE", currentDeployment().getState());
    }

    private Deployment currentDeployment() {
        int number = Integer.parseInt(stack().getResources().get("Deployment").getAttributes().get("DeploymentNumber"));
        return appconfig.getDeployment(app, environment, number);
    }

    private Map<String, Object> deploymentProperties(String version) {
        return Map.of("ApplicationId", app, "EnvironmentId", environment, "ConfigurationProfileId", profile,
                "ConfigurationVersion", version, "DeploymentStrategyId", strategy);
    }

    private ObjectNode template(String version) {
        return JSON.valueToTree(Map.of("Resources", Map.of("Deployment", Map.of(
                        "Type", "AWS::AppConfig::Deployment", "Properties", deploymentProperties(version))),
                "Outputs", Map.of(
                        "Reference", Map.of("Value", Map.of("Ref", "Deployment")),
                        "Number", Map.of("Value", Map.of("Fn::GetAtt", List.of("Deployment", "DeploymentNumber"))),
                        "State", Map.of("Value", Map.of("Fn::GetAtt", List.of("Deployment", "State"))))));
    }

    private void addInvalidResource(ObjectNode template) {
        ((ObjectNode) template.path("Resources")).set("Invalid", JSON.valueToTree(Map.of(
                "Type", "AWS::AppConfig::HostedConfigurationVersion", "DependsOn", "Deployment",
                "Properties", Map.of("ApplicationId", app, "ConfigurationProfileId", profile,
                        "Content", "missing required content type"))));
    }

    private void deploy(String type, ObjectNode template) throws Exception {
        String changeSet = "change-" + (++changes);
        cfn.createChangeSet("deployment", changeSet, type, template.toString(), null, Map.of(), List.of(), Map.of(),
                REGION, ACCOUNT);
        cfn.executeChangeSet("deployment", changeSet, REGION, ACCOUNT).get(30, TimeUnit.SECONDS);
    }

    private Stack stack() {
        return cfn.describeStacks("deployment", REGION, ACCOUNT).getFirst();
    }
}
