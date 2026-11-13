package io.floci.aws.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.XmlParser;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkField;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.CompositeAlarm;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.testing.CfnRequests.cfn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AWS::AppConfig::Application}, {@code Environment}, {@code ConfigurationProfile} and
 * {@code DeploymentStrategy} through real stack operations, read back through the AppConfig API.
 * {@code Ref} and the ID attribute of each are the AppConfig ID; an update changes mutable
 * properties in place and replaces a profile whose {@code LocationUri} changes; a failed update puts
 * the in-place changes back and deletes the replacement; and the stack delete removes everything,
 * hosted configuration versions and deployments included. A failing operation ends in a Secrets
 * Manager secret that cannot be created.
 */
@QuarkusTest
class CloudFormationAppConfigIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALARM = "arn:aws:cloudwatch:us-east-1:000000000000:alarm:configuration-errors";
    private static final List<String> RESOURCES = List.of("App", "Env", "Profile", "Strategy");

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sdkStackDeletePreservesApplicationNamedAfterItsPhysicalId(boolean removeApplicationFirst) throws Exception {
        String stack = unique("cfn-appconfig-exact-delete");
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1);
                AutoCloseable stackCleanup = () -> {
                    client.deleteStack(request -> request.stackName(stack));
                    CfnStackWaits.awaitStackDeleted(stack);
                }) {
            client.createStack(request -> request.stackName(stack).templateBody("""
                    {"Resources":{"Application":{"Type":"AWS::AppConfig::Application",
                    "Properties":{"Name":"stack-original-application"}}}}
                    """));
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
            String original = client.describeStackResource(request -> request.stackName(stack)
                    .logicalResourceId("Application")).stackResourceDetail().physicalResourceId();
            String other = appconfig().body(Map.of("Name", original)).post("/applications")
                    .then().statusCode(201).extract().path("Id");
            try (AutoCloseable applicationCleanup = () -> appconfig().delete("/applications/" + other)
                    .then().statusCode(204)) {
                assertNotEquals(original, other);
                if (removeApplicationFirst) {
                    appconfig().delete("/applications/" + original).then().statusCode(204);
                }
                client.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStackDeleted(stack);
                appconfig().get("/applications/" + other).then().statusCode(200)
                        .body("Id", equalTo(other), "Name", equalTo(original));
            }
        }
    }

    @Test
    void aStackCreatesTheFourTypesAndItsDeleteRemovesThemWithTheirVersionsAndDeployments() throws Exception {
        try (OwnedMonitorAlarm monitor = new OwnedMonitorAlarm()) {
            String stack = unique("cfn-appconfig-create");
            Map<String, Object> resources = first();
            resources.put("Untyped", Map.of("Type", "AWS::AppConfig::ConfigurationProfile", "Properties",
                    Map.of("ApplicationId", ref("App"), "Name", "untyped", "LocationUri", "hosted")));
            deploy("CreateStack", stack, template(resources, false));
            assertStatus(stack, "CREATE_COMPLETE");

            Map<String, String> outputs = outputs(stack);
            Map<String, String> physicalIds = physicalIds(stack);
            for (String resource : RESOURCES) {
                assertEquals(outputs.get(resource + "Ref"), outputs.get(resource + "Id"), resource);
                assertEquals(physicalIds.get(resource), outputs.get(resource + "Ref"), resource);
            }
            String app = outputs.get("AppRef");
            String env = outputs.get("EnvRef");
            String profile = outputs.get("ProfileRef");
            String strategy = outputs.get("StrategyRef");
            String envPath = "/applications/" + app + "/environments/" + env;
            String profilePath = "/applications/" + app + "/configurationprofiles/" + profile;

            appconfig().get("/applications/" + app).then().statusCode(200)
                    .body("Name", equalTo("orders"), "Description", equalTo("Order service"));
            assertEquals(Map.of("team", "payments", "tier", "gold"), applicationTags(app));
            appconfig().get(envPath).then().statusCode(200)
                    .body("Name", equalTo("beta"), "Monitors[0].AlarmArn", equalTo(ALARM))
                    .body("Monitors[0]", not(hasKey("AlarmRoleArn")));
            appconfig().get(profilePath).then().statusCode(200)
                    .body("Name", equalTo("settings"), "LocationUri", equalTo("hosted"), "Type", equalTo("AWS.Freeform"));
            appconfig().get("/applications/" + app + "/configurationprofiles/" + physicalIds.get("Untyped"))
                    .then().statusCode(200).body("Type", equalTo("AWS.Freeform"));
            appconfig().get("/deploymentstrategies/" + strategy).then().statusCode(200)
                    .body("Name", equalTo("gradual"), "DeploymentDurationInMinutes", equalTo(0),
                            "FinalBakeTimeInMinutes", equalTo(0), "GrowthFactor", equalTo(100f),
                            "GrowthType", equalTo("LINEAR"), "ReplicateTo", equalTo("NONE"));

            // The stack's resources work together: a hosted version deploys with the stack's strategy.
            appconfig().body("{\"enabled\":true}").post(profilePath + "/hostedconfigurationversions")
                    .then().statusCode(201).header("Version-Number", equalTo("1"));
            monitor.assertReady();
            int deployment = appconfig().body(Map.of("ConfigurationProfileId", profile, "ConfigurationVersion", "1",
                            "DeploymentStrategyId", strategy))
                    .post(envPath + "/deployments").then().statusCode(201)
                    .body("ConfigurationName", equalTo("settings"), "ConfigurationLocationUri", equalTo("hosted"))
                    .extract().path("DeploymentNumber");

            deleteStack(stack);
            appconfig().get(envPath + "/deployments/" + deployment).then().statusCode(404);
            appconfig().get(envPath).then().statusCode(404);
            appconfig().get(profilePath + "/hostedconfigurationversions/1").then().statusCode(404);
            appconfig().get(profilePath).then().statusCode(404);
            appconfig().get("/deploymentstrategies/" + strategy).then().statusCode(404);
            appconfig().get("/applications/" + app).then().statusCode(404);
        }
    }

    @Test
    void anUpdateChangesMutablePropertiesInPlaceAndReplacesAProfileWhoseLocationChanges() throws Exception {
        String stack = unique("cfn-appconfig-update");
        deploy("CreateStack", stack, template(first(), false));
        assertStatus(stack, "CREATE_COMPLETE");
        Map<String, String> before = outputs(stack);

        deploy("UpdateStack", stack, template(second(), false));
        assertStatus(stack, "UPDATE_COMPLETE");

        Map<String, String> after = outputs(stack);
        String app = after.get("AppRef");
        for (String kept : List.of("App", "Env", "Strategy")) {
            assertEquals(before.get(kept + "Ref"), after.get(kept + "Ref"), kept + " is changed in place");
        }
        String oldProfile = before.get("ProfileRef");
        String newProfile = after.get("ProfileRef");
        assertNotEquals(oldProfile, newProfile, "a LocationUri change replaces the profile");
        assertEquals(newProfile, after.get("ProfileId"));

        appconfig().get("/applications/" + app).then().statusCode(200)
                .body("Name", equalTo("orders-v2"), "Description", nullValue());
        assertEquals(Map.of("team", "billing", "owner", "ops"), applicationTags(app));
        appconfig().get("/applications/" + app + "/environments/" + after.get("EnvRef")).then().statusCode(200)
                .body("Name", equalTo("gamma"), "Description", equalTo("Second stage"))
                .body("$", not(hasKey("Monitors")));
        appconfig().get("/applications/" + app + "/configurationprofiles/" + newProfile).then().statusCode(200)
                .body("Name", equalTo("settings"), "LocationUri", equalTo("ssm-parameter://orders-settings"));
        appconfig().get("/applications/" + app + "/configurationprofiles/" + oldProfile).then().statusCode(404);
        appconfig().get("/deploymentstrategies/" + after.get("StrategyRef")).then().statusCode(200)
                .body("Description", equalTo("Ten minutes"), "DeploymentDurationInMinutes", equalTo(10),
                        "GrowthFactor", equalTo(20f), "GrowthType", equalTo("EXPONENTIAL"));
        assertTrue(hasEvent(stack, "Profile", oldProfile, "DELETE_COMPLETE"),
                "the update's cleanup reports the displaced profile deleted");

        deleteStack(stack);
        appconfig().get("/applications/" + app).then().statusCode(404);
    }

    @Test
    void aFailedUpdatePutsTheInPlaceChangesBackAndDeletesTheReplacement() throws Exception {
        String stack = unique("cfn-appconfig-rollback");
        deploy("CreateStack", stack, template(first(), false));
        assertStatus(stack, "CREATE_COMPLETE");
        Map<String, String> before = outputs(stack);
        String app = before.get("AppRef");

        deploy("UpdateStack", stack, template(second(), true));
        assertStatus(stack, "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(before, outputs(stack));
        appconfig().get("/applications/" + app).then().statusCode(200)
                .body("Name", equalTo("orders"), "Description", equalTo("Order service"));
        assertEquals(Map.of("team", "payments", "tier", "gold"), applicationTags(app));
        appconfig().get("/applications/" + app + "/environments/" + before.get("EnvRef")).then().statusCode(200)
                .body("Name", equalTo("beta"), "Monitors[0].AlarmArn", equalTo(ALARM));
        List<String> profiles = appconfig().get("/applications/" + app + "/configurationprofiles")
                .then().statusCode(200).extract().jsonPath().getList("Items.Id", String.class);
        assertEquals(List.of(before.get("ProfileRef")), profiles, "the replacement profile is deleted");
        appconfig().get("/deploymentstrategies/" + before.get("StrategyRef")).then().statusCode(200)
                .body("Description", equalTo("Immediate"), "DeploymentDurationInMinutes", equalTo(0),
                        "GrowthFactor", equalTo(100f), "GrowthType", equalTo("LINEAR"));

        deleteStack(stack);
        appconfig().get("/applications/" + app).then().statusCode(404);
    }

    @Test
    void aChangeSetReportsReplacementForCreateOnlyChangesOnly() throws Exception {
        String stack = unique("cfn-appconfig-change-set");
        deploy("CreateStack", stack, template(first(), false));
        assertStatus(stack, "CREATE_COMPLETE");

        Map<String, Object> renamed = first();
        properties(renamed, "App").put("Name", "orders-v2");
        properties(renamed, "Env").put("Name", "gamma");
        properties(renamed, "Profile").put("Name", "renamed");
        properties(renamed, "Strategy").put("GrowthFactor", 50);
        assertEquals(Map.of("App", "False", "Env", "False", "Profile", "False", "Strategy", "False"),
                replacements(stack, "renamed", template(renamed, false)));

        Map<String, Object> moved = first();
        properties(moved, "Profile").put("LocationUri", "ssm-parameter://orders-settings");
        properties(moved, "Strategy").put("Name", "immediate");
        assertEquals(Map.of("Profile", "True", "Strategy", "True"),
                replacements(stack, "moved", template(moved, false)));

        deleteStack(stack);
    }

    @Test
    void sdkTagOnlyUpdatesKeepAllFourIdsAndRollbackRestoresTheirTags() throws Exception {
        String stack = unique("cfn-appconfig-tags");
        Map<String, Object> resources = first();
        properties(resources, "Env").remove("Monitors");
        for (String name : RESOURCES) {
            properties(resources, name).put("Tags", tags("team", "first", "remove", "yes"));
        }
        try (CloudFormationClient client = AwsTestClients.create(
                CloudFormationClient.builder(), "test", Region.US_EAST_1)) {
            try {
                String originalTemplate = template(resources, false);
                client.createStack(request -> request.stackName(stack).templateBody(originalTemplate));
                assertStatus(stack, "CREATE_COMPLETE");
                Map<String, String> before = outputs(stack);
                assertResourceTags(before, Map.of("team", "first", "remove", "yes"));
                for (String name : RESOURCES) {
                    properties(resources, name).put("Tags", tags("team", "second", "empty", ""));
                }
                String updatedTemplate = template(resources, false);
                client.updateStack(request -> request.stackName(stack).templateBody(updatedTemplate));
                assertStatus(stack, "UPDATE_COMPLETE");
                assertEquals(before, outputs(stack));
                assertResourceTags(before, Map.of("team", "second", "empty", ""));

                for (String name : RESOURCES) {
                    properties(resources, name).put("Tags", tags("team", "rolled-back"));
                }
                String failingTemplate = template(resources, true);
                client.updateStack(request -> request.stackName(stack).templateBody(failingTemplate));
                assertStatus(stack, "UPDATE_ROLLBACK_COMPLETE");
                assertEquals(before, outputs(stack));
                assertResourceTags(before, Map.of("team", "second", "empty", ""));

                for (String name : RESOURCES) {
                    properties(resources, name).remove("Tags");
                }
                String withoutTags = template(resources, false);
                client.updateStack(request -> request.stackName(stack).templateBody(withoutTags));
                assertStatus(stack, "UPDATE_COMPLETE");
                assertEquals(before, outputs(stack));
                assertResourceTags(before, Map.of());
                client.deleteStack(request -> request.stackName(stack));
                CfnStackWaits.awaitStackDeleted(stack);
                for (String arn : resourceTagArns(before)) {
                    appconfig().get("/tags/" + arn).then().statusCode(404);
                }
            } finally {
                deleteStack(stack);
            }
        }
    }

    private static void assertResourceTags(Map<String, String> outputs, Map<String, String> expected) {
        for (String arn : resourceTagArns(outputs)) {
            assertEquals(expected, appconfig().get("/tags/" + arn).then().statusCode(200)
                    .extract().jsonPath().getMap("Tags", String.class, String.class), arn);
        }
    }

    private static List<String> resourceTagArns(Map<String, String> outputs) {
        String prefix = "arn:aws:appconfig:us-east-1:000000000000:";
        String app = "application/" + outputs.get("AppRef");
        return List.of(prefix + app, prefix + app + "/environment/" + outputs.get("EnvRef"),
                prefix + app + "/configurationprofile/" + outputs.get("ProfileRef"),
                prefix + "deploymentstrategy/" + outputs.get("StrategyRef"));
    }

    /** The first version of the four resources. */
    private static Map<String, Object> first() {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("App", resource("Application", props("Name", "orders", "Description", "Order service",
                "Tags", tags("team", "payments", "tier", "gold"))));
        resources.put("Env", resource("Environment", props("ApplicationId", ref("App"), "Name", "beta",
                "Monitors", List.of(Map.of("AlarmArn", ALARM)))));
        resources.put("Profile", resource("ConfigurationProfile", props("ApplicationId", ref("App"),
                "Name", "settings", "LocationUri", "hosted", "Type", "AWS.Freeform")));
        resources.put("Strategy", resource("DeploymentStrategy", props("Name", "gradual", "ReplicateTo", "NONE",
                "DeploymentDurationInMinutes", 0, "FinalBakeTimeInMinutes", 0, "GrowthFactor", 100,
                "Description", "Immediate")));
        return resources;
    }

    /**
     * The second version: the application renamed, its description dropped and its tags changed,
     * the environment renamed with its monitors dropped, the profile moved to another location, and
     * the strategy slowed down.
     */
    private static Map<String, Object> second() {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("App", resource("Application", props("Name", "orders-v2",
                "Tags", tags("team", "billing", "owner", "ops"))));
        resources.put("Env", resource("Environment", props("ApplicationId", ref("App"), "Name", "gamma",
                "Description", "Second stage")));
        resources.put("Profile", resource("ConfigurationProfile", props("ApplicationId", ref("App"),
                "Name", "settings", "LocationUri", "ssm-parameter://orders-settings", "Type", "AWS.Freeform")));
        resources.put("Strategy", resource("DeploymentStrategy", props("Name", "gradual", "ReplicateTo", "NONE",
                "DeploymentDurationInMinutes", 10, "GrowthFactor", 20, "GrowthType", "EXPONENTIAL",
                "Description", "Ten minutes")));
        return resources;
    }

    private static String template(Map<String, Object> resources, boolean failAfter) throws JsonProcessingException {
        Map<String, Object> all = new LinkedHashMap<>(resources);
        if (failAfter) {
            all.put("Failure", Map.of("Type", "AWS::SecretsManager::Secret", "DependsOn", RESOURCES,
                    "Properties", Map.of("SecretString", "explicit",
                            "GenerateSecretString", Map.of("PasswordLength", 32))));
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        Map<String, String> idAttributes = Map.of("App", "ApplicationId", "Env", "EnvironmentId",
                "Profile", "ConfigurationProfileId", "Strategy", "Id");
        for (String resource : RESOURCES) {
            outputs.put(resource + "Ref", Map.of("Value", ref(resource)));
            outputs.put(resource + "Id", Map.of("Value",
                    Map.of("Fn::GetAtt", List.of(resource, idAttributes.get(resource)))));
        }
        return MAPPER.writeValueAsString(Map.of("Resources", all, "Outputs", outputs));
    }

    private static Map<String, Object> resource(String type, Map<String, Object> properties) {
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("Type", "AWS::AppConfig::" + type);
        resource.put("Properties", properties);
        return resource;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> resources, String logicalId) {
        return (Map<String, Object>) ((Map<String, Object>) resources.get(logicalId)).get("Properties");
    }

    /** Alternating property names and values, in order, in a map a test can change. */
    private static Map<String, Object> props(Object... namesAndValues) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            properties.put((String) namesAndValues[i], namesAndValues[i + 1]);
        }
        return properties;
    }

    private static List<Map<String, String>> tags(String... keysAndValues) {
        List<Map<String, String>> tags = new ArrayList<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            tags.add(Map.of("Key", keysAndValues[i], "Value", keysAndValues[i + 1]));
        }
        return tags;
    }

    private static Map<String, String> ref(String logicalId) {
        return Map.of("Ref", logicalId);
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }

    private static void deploy(String action, String stack, String template) {
        cfn(action, stack).formParam("TemplateBody", template).post("/").then().statusCode(200);
    }

    private static void assertStatus(String stack, String expected) {
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stack);
        assertEquals(expected, state.status(), state.reason());
    }

    private static void deleteStack(String stack) {
        cfn("DeleteStack", stack).post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    private static Map<String, String> outputs(String stack) {
        String body = cfn("DescribeStacks", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(body, "Outputs", "OutputKey", "OutputValue");
    }

    private static Map<String, String> physicalIds(String stack) {
        String body = cfn("DescribeStackResources", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(body, "StackResources", "LogicalResourceId", "PhysicalResourceId");
    }

    private static boolean hasEvent(String stack, String logicalId, String physicalId, String status) {
        String body = cfn("DescribeStackEvents", stack).post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractGroups(body, "member").stream()
                .anyMatch(event -> logicalId.equals(event.get("LogicalResourceId"))
                        && physicalId.equals(event.get("PhysicalResourceId"))
                        && status.equals(event.get("ResourceStatus")));
    }

    /** The {@code Replacement} a change set to {@code template} reports, by logical id. */
    private static Map<String, String> replacements(String stack, String changeSet, String template) {
        cfn("CreateChangeSet", stack).formParam("ChangeSetName", changeSet).formParam("ChangeSetType", "UPDATE")
                .formParam("TemplateBody", template).post("/").then().statusCode(200);
        XmlPath body = cfn("DescribeChangeSet", stack).formParam("ChangeSetName", changeSet).post("/")
                .then().statusCode(200).extract().xmlPath();
        List<String> logicalIds = body.getList(
                "**.findAll { it.name() == 'ResourceChange' }.LogicalResourceId", String.class);
        List<String> values = body.getList("**.findAll { it.name() == 'ResourceChange' }.Replacement", String.class);
        Map<String, String> replacements = new LinkedHashMap<>();
        for (int i = 0; i < logicalIds.size(); i++) {
            replacements.put(logicalIds.get(i), values.get(i));
        }
        return replacements;
    }

    private static RequestSpecification appconfig() {
        return given().contentType(ContentType.JSON);
    }

    private static Map<String, String> applicationTags(String applicationId) {
        JsonPath body = appconfig().get("/tags/arn:aws:appconfig:us-east-1:000000000000:application/" + applicationId)
                .then().statusCode(200).extract().jsonPath();
        return body.getMap("Tags", String.class, String.class);
    }

    private static final class OwnedMonitorAlarm implements AutoCloseable {
        private static final String ALARM_NAME = "configuration-errors";
        private final CloudWatchClient metrics = AwsTestClients.create(
                CloudWatchClient.builder(), "test", Region.US_EAST_1);
        private final String marker = "cfn-appconfig-monitor-" + UUID.randomUUID();
        private AlarmInventory before;
        private AlarmInventory after;
        private MetricAlarm ready;
        private boolean createAttempted;

        private OwnedMonitorAlarm() {
            try {
                before = inventory();
                assertTrue(before.metrics().stream().noneMatch(alarm -> ALARM_NAME.equals(alarm.alarmName())));
                assertTrue(before.composites().stream().noneMatch(alarm -> ALARM_NAME.equals(alarm.alarmName())));
                createAttempted = true;
                metrics.putMetricAlarm(request -> request.alarmName(ALARM_NAME).alarmDescription(marker)
                        .namespace("Floci/AppConfigCfnMonitor").metricName(marker).statistic("Sum")
                        .period(60).evaluationPeriods(1).datapointsToAlarm(1).threshold(1.0)
                        .comparisonOperator("GreaterThanThreshold").treatMissingData("notBreaching")
                        .actionsEnabled(true));
                metrics.setAlarmState(request -> request.alarmName(ALARM_NAME)
                        .stateValue("OK").stateReason(marker));
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (IOException | RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private void assertReady() {
            DescribeAlarmsResponse response = metrics.describeAlarms(request -> request.alarmNames(ALARM_NAME)
                    .alarmTypesWithStrings("MetricAlarm", "CompositeAlarm"));
            assertEquals(List.of(), response.compositeAlarms());
            assertEquals(1, response.metricAlarms().size());
            ready = response.metricAlarms().getFirst();
            assertAll(() -> assertEquals(ALARM, ready.alarmArn()),
                    () -> assertEquals(marker, ready.alarmDescription()),
                    () -> assertEquals("OK", ready.stateValueAsString()),
                    () -> assertEquals(Boolean.TRUE, ready.actionsEnabled()));
        }

        private AlarmInventory inventory() {
            List<MetricAlarm> metricAlarms = new ArrayList<>();
            List<CompositeAlarm> compositeAlarms = new ArrayList<>();
            String next = null;
            for (int page = 0; page < 100; page++) {
                String token = next;
                DescribeAlarmsResponse response = metrics.describeAlarms(request -> request.maxRecords(100)
                        .alarmTypesWithStrings("MetricAlarm", "CompositeAlarm").nextToken(token));
                metricAlarms.addAll(response.metricAlarms());
                compositeAlarms.addAll(response.compositeAlarms());
                next = response.nextToken();
                if (next == null) {
                    break;
                }
            }
            assertEquals(null, next, "Complete bounded CloudWatch alarm inventory");
            return new AlarmInventory(metricAlarms.stream().sorted(Comparator.comparing(MetricAlarm::alarmName)).toList(),
                    compositeAlarms.stream().sorted(Comparator.comparing(CompositeAlarm::alarmName)).toList());
        }

        private void removeOwnedAlarm() {
            if (!createAttempted) {
                return;
            }
            DescribeAlarmsResponse response = metrics.describeAlarms(request -> request.alarmNames(ALARM_NAME)
                    .alarmTypesWithStrings("MetricAlarm", "CompositeAlarm"));
            assertEquals(List.of(), response.compositeAlarms());
            if (!response.metricAlarms().isEmpty()) {
                assertEquals(1, response.metricAlarms().size());
                MetricAlarm alarm = response.metricAlarms().getFirst();
                assertEquals(ALARM, alarm.alarmArn());
                assertEquals(marker, alarm.alarmDescription());
                metrics.deleteAlarms(request -> request.alarmNames(ALARM_NAME));
                assertTrue(metrics.describeAlarms(request -> request.alarmNames(ALARM_NAME)
                        .alarmTypesWithStrings("MetricAlarm", "CompositeAlarm")).metricAlarms().isEmpty());
            }
        }

        @Override
        public void close() throws IOException {
            Throwable cleanupFailure = null;
            try {
                assertAll("Owned monitor teardown and complete inventory restoration",
                        this::removeOwnedAlarm,
                        () -> {
                            after = inventory();
                            if (before != null) {
                                assertEquals(before, after);
                            }
                        });
            } catch (RuntimeException | Error failure) {
                cleanupFailure = failure;
                throw failure;
            } finally {
                try {
                    Path directory = Path.of("target/appconfig-cfn-monitor-contract");
                    Files.createDirectories(directory);
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("marker", marker);
                    record.put("alarmArn", ALARM);
                    record.put("createAttempted", createAttempted);
                    record.put("before", alarmFields(before));
                    record.put("ready", alarmFields(ready));
                    record.put("after", alarmFields(after));
                    record.put("cleanupFailure", cleanupFailure == null ? null : cleanupFailure.toString());
                    MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(marker + ".json").toFile(), record);
                } catch (IOException captureFailure) {
                    if (cleanupFailure != null) {
                        cleanupFailure.addSuppressed(captureFailure);
                    } else {
                        throw captureFailure;
                    }
                } finally {
                    metrics.close();
                }
            }
        }
    }

    private static Object alarmFields(Object value) {
        if (value instanceof AlarmInventory inventory) {
            return Map.of("metrics", alarmFields(inventory.metrics()), "composites", alarmFields(inventory.composites()));
        }
        if (value instanceof SdkPojo pojo) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (SdkField<?> field : pojo.sdkFields()) {
                fields.put(field.memberName(), alarmFields(field.getValueOrDefault(pojo)));
            }
            return fields;
        }
        if (value instanceof List<?> values) {
            return values.stream().map(CloudFormationAppConfigIntegrationTest::alarmFields).toList();
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        return value;
    }

    private record AlarmInventory(List<MetricAlarm> metrics, List<CompositeAlarm> composites) {}
}
