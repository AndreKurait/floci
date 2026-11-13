package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SamDeploymentPreferencesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SamTransformProcessor processor = new SamTransformProcessor(mapper);

    private ObjectNode template() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"Transform":"AWS::Serverless-2016-10-31","Resources":{"Function":{
                  "Type":"AWS::Serverless::Function",
                  "Properties":{"Runtime":"python3.12","Handler":"index.handler",
                    "InlineCode":"def handler(event, context): return 1",
                    "Role":"arn:aws:iam::123456789012:role/function",
                    "AutoPublishAlias":"live","DeploymentPreference":{"Type":"AllAtOnce"}}}}}
                """);
    }

    private ObjectNode properties(ObjectNode template) {
        return (ObjectNode) template.at("/Resources/Function/Properties");
    }

    private ObjectNode preference(ObjectNode template) {
        return (ObjectNode) properties(template).path("DeploymentPreference");
    }

    @Test
    void generatesCodeDeployResourcesAliasPolicyAndPartitionAwareRoleWithoutChangingTheInput() throws Exception {
        ObjectNode input = template();
        preference(input).putArray("Alarms").addObject().put("Ref", "Alarm");
        preference(input).putObject("Hooks").put("PreTraffic", "before").put("PostTraffic", "after");
        JsonNode original = input.deepCopy();
        JsonNode resources = processor.expandSamTemplate(input).path("Resources");

        assertEquals(original, input);
        assertEquals("AWS::CodeDeploy::Application", resources.at("/ServerlessDeploymentApplication/Type").asText());
        JsonNode group = resources.at("/FunctionDeploymentGroup/Properties");
        assertEquals("ServerlessDeploymentApplication", group.at("/ApplicationName/Ref").asText());
        assertEquals("CodeDeployDefault.Lambda${ConfigName}", group.at("/DeploymentConfigName/Fn::Sub/0").asText());
        assertEquals("AllAtOnce", group.at("/DeploymentConfigName/Fn::Sub/1/ConfigName").asText());
        assertEquals("BLUE_GREEN", group.at("/DeploymentStyle/DeploymentType").asText());
        assertEquals("WITH_TRAFFIC_CONTROL", group.at("/DeploymentStyle/DeploymentOption").asText());
        assertTrue(group.at("/AutoRollbackConfiguration/Enabled").asBoolean());
        assertEquals(3, group.at("/AutoRollbackConfiguration/Events").size());
        assertEquals("Alarm", group.at("/AlarmConfiguration/Alarms/0/Name/Ref").asText());
        JsonNode policy = resources.at("/FunctionAliasLive/UpdatePolicy/CodeDeployLambdaAliasUpdate");
        assertEquals("FunctionDeploymentGroup", policy.at("/DeploymentGroupName/Ref").asText());
        assertEquals("before", policy.path("BeforeAllowTrafficHook").asText());
        assertEquals("after", policy.path("AfterAllowTrafficHook").asText());
        JsonNode role = resources.at("/CodeDeployServiceRole/Properties");
        assertEquals("codedeploy.amazonaws.com",
                role.at("/AssumeRolePolicyDocument/Statement/0/Principal/Service/0").asText());
        JsonNode managed = role.at("/ManagedPolicyArns/0/Fn::If");
        assertTrue(managed.get(1).path("Fn::Sub").asText().contains("${AWS::Partition}"));
        assertTrue(managed.get(1).path("Fn::Sub").asText().endsWith("AWSCodeDeployRoleForLambdaLimited"));
        assertTrue(managed.get(2).path("Fn::Sub").asText().endsWith("AWSCodeDeployRoleForLambda"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "\"false\"", "\"False\""})
    void disabledPreferenceNeedsNeitherAnAliasNorAType(String enabled) throws Exception {
        ObjectNode input = template();
        properties(input).remove("AutoPublishAlias");
        properties(input).putObject("DeploymentPreference").set("Enabled", mapper.readTree(enabled));
        JsonNode resources = processor.expandSamTemplate(input).path("Resources");
        assertFalse(resources.has("ServerlessDeploymentApplication"));
        assertFalse(resources.has("CodeDeployServiceRole"));
        assertFalse(resources.has("FunctionDeploymentGroup"));
        assertEquals(1, resources.size());
    }

    @Test
    void resolvesEnabledAndTypeFromParametersAndMappings() throws Exception {
        ObjectNode input = template();
        input.putObject("Parameters").putObject("Deploy").put("Type", "String").put("Default", "false");
        preference(input).putObject("Enabled").put("Ref", "Deploy");
        input.putObject("Mappings").putObject("Rollouts").putObject("Gradual")
                .put("Type", "Canary10Percent5Minutes");
        preference(input).putObject("Type").putArray("Fn::FindInMap").add("Rollouts").add("Gradual").add("Type");
        assertFalse(processor.expandSamTemplate(input).path("Resources").has("FunctionDeploymentGroup"));
        JsonNode enabled = processor.expandSamTemplate(input, Map.of("Deploy", "true"));
        assertEquals("Canary10Percent5Minutes",
                enabled.at("/Resources/FunctionDeploymentGroup/Properties/DeploymentConfigName/Fn::Sub/1/ConfigName")
                        .asText());
    }

    @Test
    void customRoleAvoidsGeneratingTheDefaultRoleAndPreservesTriggers() throws Exception {
        ObjectNode input = template();
        preference(input).putObject("Role").put("Ref", "ExternalRole");
        preference(input).put("Type", "custom-config");
        preference(input).putArray("TriggerConfigurations").addObject()
                .put("TriggerName", "notify").put("TriggerTargetArn", "topic");
        JsonNode output = processor.expandSamTemplate(input);
        assertFalse(output.path("Resources").has("CodeDeployServiceRole"));
        assertFalse(output.path("Conditions").has("ServerlessCodeDeployRoleUsesLimitedPolicy"));
        JsonNode group = output.at("/Resources/FunctionDeploymentGroup/Properties");
        assertEquals("ExternalRole", group.at("/ServiceRoleArn/Ref").asText());
        assertEquals("custom-config", group.path("DeploymentConfigName").asText());
        assertEquals("notify", group.at("/TriggerConfigurations/0/TriggerName").asText());
    }

    @Test
    void passesConditionsToGroupsAndOrsThemForSharedResources() throws Exception {
        ObjectNode input = template();
        ObjectNode first = (ObjectNode) input.at("/Resources/Function");
        first.put("Condition", "FirstEnabled");
        preference(input).put("PassthroughCondition", true);
        ObjectNode second = first.deepCopy().put("Condition", "SecondEnabled");
        ((ObjectNode) input.path("Resources")).set("Second", second);
        JsonNode output = processor.expandSamTemplate(input);
        assertEquals("FirstEnabled", output.at("/Resources/FunctionDeploymentGroup/Condition").asText());
        assertEquals("SecondEnabled", output.at("/Resources/SecondDeploymentGroup/Condition").asText());
        assertEquals("ServerlessCodeDeployCondition",
                output.at("/Resources/ServerlessDeploymentApplication/Condition").asText());
        assertEquals("ServerlessCodeDeployCondition", output.at("/Resources/CodeDeployServiceRole/Condition").asText());
        JsonNode conditions = output.at("/Conditions/ServerlessCodeDeployCondition/Fn::Or");
        assertEquals(Set.of("FirstEnabled", "SecondEnabled"),
                Set.of(conditions.get(0).path("Condition").asText(), conditions.get(1).path("Condition").asText()));
    }

    @Test
    void anUnconditionalPreferenceKeepsSharedResourcesUnconditional() throws Exception {
        ObjectNode input = template();
        ObjectNode first = (ObjectNode) input.at("/Resources/Function");
        first.put("Condition", "FirstEnabled");
        preference(input).put("PassthroughCondition", true);
        ObjectNode second = first.deepCopy();
        second.remove("Condition");
        ((ObjectNode) input.path("Resources")).set("Second", second);
        JsonNode resources = processor.expandSamTemplate(input).path("Resources");
        assertFalse(resources.path("ServerlessDeploymentApplication").has("Condition"));
        assertFalse(resources.path("CodeDeployServiceRole").has("Condition"));
    }

    @Test
    void conditionPassthroughIsExplicitAndSharedResourcesReuseASingleCondition() throws Exception {
        ObjectNode input = template();
        ((ObjectNode) input.at("/Resources/Function")).put("Condition", "Enabled");
        JsonNode without = processor.expandSamTemplate(input).path("Resources");
        assertFalse(without.path("FunctionDeploymentGroup").has("Condition"));
        preference(input).put("PassthroughCondition", true);
        JsonNode with = processor.expandSamTemplate(input);
        assertEquals("Enabled", with.at("/Resources/ServerlessDeploymentApplication/Condition").asText());
        assertFalse(with.path("Conditions").has("ServerlessCodeDeployCondition"));
    }

    @Test
    void aggregateConditionsRespectCloudFormationOperandLimits() throws Exception {
        ObjectNode input = template();
        ObjectNode function = (ObjectNode) input.at("/Resources/Function");
        preference(input).put("PassthroughCondition", true);
        ObjectNode resources = (ObjectNode) input.path("Resources");
        resources.removeAll();
        for (int index = 0; index < 21; index++) {
            resources.set("Function" + index, function.deepCopy().put("Condition", "Enabled" + index));
        }
        JsonNode combined = processor.expandSamTemplate(input).at("/Conditions/ServerlessCodeDeployCondition");
        assertOrLimits(combined);
    }

    private void assertOrLimits(JsonNode condition) {
        if (condition.has("Fn::Or")) {
            JsonNode operands = condition.get("Fn::Or");
            assertTrue(operands.size() >= 2 && operands.size() <= 10);
            operands.forEach(this::assertOrLimits);
        } else {
            assertTrue(condition.has("Condition"));
        }
    }

    @Test
    void preservesConditionalAlarmListsAndDeploymentTypes() throws Exception {
        ObjectNode input = template();
        preference(input).set("Alarms", mapper.readTree("""
                {"Fn::If":["AlarmsEnabled",["first","second"],[]]}
                """));
        preference(input).set("Type", mapper.readTree("""
                {"Fn::If":["Gradual","Linear10PercentEvery1Minute","AllAtOnce"]}
                """));
        JsonNode group = processor.expandSamTemplate(input).at("/Resources/FunctionDeploymentGroup/Properties");
        assertEquals("first", group.at("/AlarmConfiguration/Fn::If/1/Alarms/0/Name").asText());
        assertEquals(mapper.createObjectNode(), group.at("/AlarmConfiguration/Fn::If/2"));
        assertEquals("Linear10PercentEvery1Minute",
                group.at("/DeploymentConfigName/Fn::If/1/Fn::Sub/1/ConfigName").asText());
        assertEquals("AllAtOnce", group.at("/DeploymentConfigName/Fn::If/2/Fn::Sub/1/ConfigName").asText());
    }

    @Test
    void propagatesTagsOnlyWhenRequestedAndMergesSharedTags() throws Exception {
        ObjectNode input = template();
        properties(input).putObject("Tags").put("owner", "first");
        assertFalse(processor.expandSamTemplate(input).at("/Resources/FunctionDeploymentGroup/Properties").has("Tags"));
        properties(input).put("PropagateTags", true);
        ObjectNode second = ((ObjectNode) input.at("/Resources/Function")).deepCopy();
        ((ObjectNode) second.path("Properties")).putObject("Tags").put("environment", "test");
        ((ObjectNode) input.path("Resources")).set("Second", second);
        JsonNode output = processor.expandSamTemplate(input);
        assertEquals(2, output.at("/Resources/ServerlessDeploymentApplication/Properties/Tags").size());
        assertEquals(2, output.at("/Resources/CodeDeployServiceRole/Properties/Tags").size());
        assertEquals(1, output.at("/Resources/FunctionDeploymentGroup/Properties/Tags").size());
        assertEquals(1, output.at("/Resources/SecondDeploymentGroup/Properties/Tags").size());
    }

    @Test
    void enabledPreferenceRequiresAnAlias() throws Exception {
        ObjectNode input = template();
        properties(input).remove("AutoPublishAlias");
        assertThrows(AwsException.class, () -> processor.expandSamTemplate(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"invalid\"", "{\"Enabled\":true}", "{\"Type\":\"AllAtOnce\",\"Hooks\":[]}",
            "{\"Type\":\"AllAtOnce\",\"Alarms\":\"alarm\"}",
            "{\"Type\":\"AllAtOnce\",\"PassthroughCondition\":\"unknown\"}"
    })
    void malformedPreferencesFailTranslation(String raw) throws Exception {
        ObjectNode input = template();
        properties(input).set("DeploymentPreference", mapper.readTree(raw));
        assertThrows(AwsException.class, () -> processor.expandSamTemplate(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ServerlessDeploymentApplication", "CodeDeployServiceRole", "FunctionDeploymentGroup"})
    void generatedResourcesCannotOverwriteUserResources(String collision) throws Exception {
        ObjectNode input = template();
        ((ObjectNode) input.path("Resources")).putObject(collision).put("Type", "AWS::SNS::Topic");
        assertThrows(AwsException.class, () -> processor.expandSamTemplate(input));
    }
}
