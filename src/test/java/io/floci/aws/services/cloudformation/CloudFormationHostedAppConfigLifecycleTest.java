package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.appconfig.AppConfigService;
import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudFormationHostedAppConfigLifecycleTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private InMemoryStorageFactory storage;
    private AppConfigService appconfig;
    private CloudFormationService cfn;
    private int changes;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorageFactory(ACCOUNT);
        appconfig = new AppConfigService(storage, new RegionResolver(REGION, ACCOUNT));
        cfn = CloudFormationServiceFixture.create(CfnProvisionerFixture.builder().appConfig(appconfig).build());
    }

    @AfterEach
    void close() {
        cfn.stop();
        storage.shutdownAll();
    }

    @Test
    void twoProfilesCanBothReturnVersionOneWithoutSharingAPhysicalIdentity() throws Exception {
        ObjectNode template = profileTemplate();
        deploy("CREATE", template);
        Stack stack = stack();

        assertEquals("CREATE_COMPLETE", stack.getStatus(), stack.getStatusReason());
        assertEquals("1", stack.getOutputs().get("FirstRef"));
        assertEquals("1", stack.getOutputs().get("FirstVersion"));
        assertEquals("1-1", stack.getOutputs().get("Both"));
        assertNotEquals(stack.getResources().get("First").getPhysicalId(),
                stack.getResources().get("Second").getPhysicalId());
        assertContent("FirstProfile", 1, "first");
        assertContent("SecondProfile", 1, "second");

        ((ObjectNode) template.path("Resources").path("First").path("Properties")).put("Content", "updated");
        deploy("UPDATE", template);

        assertEquals("UPDATE_COMPLETE", stack().getStatus(), stack().getStatusReason());
        assertEquals("2-1", stack().getOutputs().get("Both"));
        assertContent("FirstProfile", 2, "updated");
        assertContent("SecondProfile", 1, "second");
        String app = stack().getResources().get("App").getPhysicalId();
        String firstProfile = stack().getResources().get("FirstProfile").getPhysicalId();
        assertThrows(AwsException.class, () -> appconfig.getHostedConfigurationVersion(app, firstProfile, 1));
    }

    @Test
    void outputsOnlyUpdatePreservesTheVersionAndResolvesItsReferenceAgain() throws Exception {
        ObjectNode template = profileTemplate();
        deploy("CREATE", template);
        String original = stack().getResources().get("First").getPhysicalId();
        ((ObjectNode) template.path("Outputs")).set("Again", JSON.valueToTree(Map.of("Value", ref("First"))));

        deploy("UPDATE", template);

        assertEquals("UPDATE_COMPLETE", stack().getStatus(), stack().getStatusReason());
        assertEquals(original, stack().getResources().get("First").getPhysicalId());
        assertEquals("1", stack().getOutputs().get("Again"));
        assertContent("FirstProfile", 1, "first");
    }

    @Test
    void aLaterFailureRestoresTheOldVersionAndDeletesTheReplacement() throws Exception {
        ObjectNode template = profileTemplate();
        deploy("CREATE", template);
        String original = stack().getResources().get("First").getPhysicalId();
        ((ObjectNode) template.path("Resources").path("First").path("Properties")).put("Content", "replacement");
        ((ObjectNode) template.path("Resources")).set("Invalid", JSON.valueToTree(Map.of(
                "Type", "AWS::AppConfig::HostedConfigurationVersion", "DependsOn", "First",
                "Properties", Map.of("ApplicationId", ref("App"), "ConfigurationProfileId", ref("FirstProfile"),
                        "Content", "missing content type"))));

        deploy("UPDATE", template);

        assertEquals("UPDATE_ROLLBACK_COMPLETE", stack().getStatus(), stack().getStatusReason());
        assertEquals(original, stack().getResources().get("First").getPhysicalId());
        assertContent("FirstProfile", 1, "first");
        String app = stack().getResources().get("App").getPhysicalId();
        String profile = stack().getResources().get("FirstProfile").getPhysicalId();
        assertThrows(AwsException.class, () -> appconfig.getHostedConfigurationVersion(app, profile, 2));
        assertEquals(3, appconfig.createHostedConfigurationVersion(
                app, profile, new byte[]{3}, "text/plain", null).getVersionNumber());
    }

    @Test
    void movingToAnotherProfileReplacesTheResourceEvenWhenItsVersionNumberMatches() throws Exception {
        String app = appconfig.createApplication(Map.of("Name", "parents")).getId();
        String first = appconfig.createConfigurationProfile(app, Map.of("Name", "first", "LocationUri", "hosted")).getId();
        String second = appconfig.createConfigurationProfile(app, Map.of("Name", "second", "LocationUri", "hosted")).getId();
        ObjectNode template = singleVersionTemplate(app, first);
        deploy("CREATE", template);
        String original = stack().getResources().get("Config").getPhysicalId();
        ((ObjectNode) template.path("Resources").path("Config").path("Properties")).put("ConfigurationProfileId", second);

        deploy("UPDATE", template);

        assertEquals("UPDATE_COMPLETE", stack().getStatus(), stack().getStatusReason());
        assertNotEquals(original, stack().getResources().get("Config").getPhysicalId());
        assertEquals("1", stack().getOutputs().get("Version"));
        assertThrows(AwsException.class, () -> appconfig.getHostedConfigurationVersion(app, first, 1));
        assertArrayEquals("initial".getBytes(StandardCharsets.UTF_8),
                appconfig.getHostedConfigurationVersion(app, second, 1).getContent());
    }

    @Test
    void retainedVersionsSurviveReplacementAndStackDeletionWhileTheirParentRemains() throws Exception {
        String app = appconfig.createApplication(Map.of("Name", "parents")).getId();
        String profile = appconfig.createConfigurationProfile(app, Map.of("Name", "settings", "LocationUri", "hosted"))
                .getId();
        ObjectNode template = singleVersionTemplate(app, profile);
        ObjectNode resource = (ObjectNode) template.path("Resources").path("Config");
        resource.put("DeletionPolicy", "Retain").put("UpdateReplacePolicy", "Retain");
        deploy("CREATE", template);
        ((ObjectNode) resource.path("Properties")).put("Content", "replacement");
        deploy("UPDATE", template);
        assertEquals("UPDATE_COMPLETE", stack().getStatus(), stack().getStatusReason());

        cfn.deleteStack("hosted", REGION, ACCOUNT).get(30, TimeUnit.SECONDS);

        assertArrayEquals("initial".getBytes(StandardCharsets.UTF_8),
                appconfig.getHostedConfigurationVersion(app, profile, 1).getContent());
        assertArrayEquals("replacement".getBytes(StandardCharsets.UTF_8),
                appconfig.getHostedConfigurationVersion(app, profile, 2).getContent());
    }

    private void assertContent(String logicalProfile, int version, String content) {
        String app = stack().getResources().get("App").getPhysicalId();
        String profile = stack().getResources().get(logicalProfile).getPhysicalId();
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8),
                appconfig.getHostedConfigurationVersion(app, profile, version).getContent());
    }

    private static ObjectNode profileTemplate() {
        ObjectNode template = JSON.createObjectNode();
        ObjectNode resources = template.putObject("Resources");
        resources.set("App", JSON.valueToTree(Map.of(
                "Type", "AWS::AppConfig::Application", "Properties", Map.of("Name", "settings"))));
        for (String name : List.of("First", "Second")) {
            resources.set(name + "Profile", JSON.valueToTree(Map.of(
                    "Type", "AWS::AppConfig::ConfigurationProfile",
                    "Properties", Map.of("ApplicationId", ref("App"), "Name", name, "LocationUri", "hosted"))));
            resources.set(name, JSON.valueToTree(Map.of(
                    "Type", "AWS::AppConfig::HostedConfigurationVersion",
                    "Properties", Map.of("ApplicationId", ref("App"), "ConfigurationProfileId", ref(name + "Profile"),
                            "Content", name.equals("First") ? "first" : "second", "ContentType", "text/plain"))));
        }
        ObjectNode outputs = template.putObject("Outputs");
        outputs.set("FirstRef", JSON.valueToTree(Map.of("Value", ref("First"))));
        outputs.set("FirstVersion", JSON.valueToTree(Map.of("Value", Map.of("Fn::GetAtt", List.of("First", "VersionNumber")))));
        outputs.set("Both", JSON.valueToTree(Map.of("Value", Map.of("Fn::Sub", "${First}-${Second}"))));
        return template;
    }

    private static ObjectNode singleVersionTemplate(String app, String profile) {
        return JSON.valueToTree(Map.of("Resources", Map.of("Config", Map.of(
                "Type", "AWS::AppConfig::HostedConfigurationVersion",
                "Properties", Map.of("ApplicationId", app, "ConfigurationProfileId", profile,
                        "Content", "initial", "ContentType", "text/plain"))),
                "Outputs", Map.of("Version", Map.of("Value", ref("Config")))));
    }

    private static Map<String, String> ref(String logicalId) {
        return Map.of("Ref", logicalId);
    }

    private void deploy(String type, ObjectNode template) throws Exception {
        String changeSet = "change-" + (++changes);
        cfn.createChangeSet("hosted", changeSet, type, template.toString(), null, Map.of(), List.of(), Map.of(),
                REGION, ACCOUNT);
        cfn.executeChangeSet("hosted", changeSet, REGION, ACCOUNT).get(30, TimeUnit.SECONDS);
    }

    private Stack stack() {
        return cfn.describeStacks("hosted", REGION, ACCOUNT).getFirst();
    }
}
