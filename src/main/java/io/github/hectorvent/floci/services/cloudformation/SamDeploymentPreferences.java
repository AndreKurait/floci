package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServicePrincipals;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Per-template collection of SAM Lambda deployment preferences and their shared resources. */
final class SamDeploymentPreferences {
    private static final String APPLICATION = "ServerlessDeploymentApplication";
    private static final String ROLE = "CodeDeployServiceRole";
    private static final String CONDITION = "ServerlessCodeDeployCondition";
    private static final String LIMITED_POLICY = "ServerlessCodeDeployRoleUsesLimitedPolicy";
    private static final Set<String> PREDEFINED_TYPES = Set.of(
            "AllAtOnce", "Canary10Percent5Minutes", "Canary10Percent10Minutes",
            "Canary10Percent15Minutes", "Canary10Percent30Minutes", "Linear10PercentEvery1Minute",
            "Linear10PercentEvery2Minutes", "Linear10PercentEvery3Minutes", "Linear10PercentEvery10Minutes");

    private final ObjectMapper mapper;
    private final ObjectNode template;
    private final ObjectNode resources;
    private final List<String> conditions = new ArrayList<>();
    private final ObjectNode sharedTags;
    private boolean unconditional;
    private boolean needsRole;
    private boolean enabled;

    SamDeploymentPreferences(ObjectMapper mapper, ObjectNode template) {
        this.mapper = mapper;
        this.template = template;
        this.resources = (ObjectNode) template.path("Resources");
        this.sharedTags = mapper.createObjectNode();
    }

    void add(String logicalId, String aliasId, JsonNode source, JsonNode properties,
             UnaryOperator<JsonNode> resolveParameters) {
        JsonNode preference = properties.path("DeploymentPreference");
        if (preference.isMissingNode() || preference.isNull()) {
            return;
        }
        if (!preference.isObject()) {
            throw invalid(logicalId, "'DeploymentPreference' must be an object.");
        }
        if (preference.isEmpty()) {
            return;
        }
        JsonNode enabledValue = resolveMappings(resolveParameters.apply(preference.path("Enabled")));
        if (!enabledValue.isMissingNode() && !truthy(enabledValue)) {
            return;
        }
        if (aliasId == null) {
            throw invalid(logicalId, "'DeploymentPreference' requires AutoPublishAlias property to be specified.");
        }
        if (!preference.has("Type")) {
            throw invalid(logicalId, "'DeploymentPreference' is missing required Property 'Type'.");
        }
        JsonNode hooks = preference.path("Hooks");
        if (!hooks.isMissingNode() && !hooks.isObject()) {
            throw invalid(logicalId, "'Hooks' property of 'DeploymentPreference' must be an object.");
        }
        JsonNode passthrough = resolveMappings(resolveParameters.apply(preference.path("PassthroughCondition")));
        if (!passthrough.isMissingNode() && !booleanValue(passthrough)) {
            throw invalid(logicalId, "'PassthroughCondition' must resolve to a boolean.");
        }
        String condition = truthy(passthrough) ? source.path("Condition").asText(null) : null;
        if (condition == null) {
            unconditional = true;
        } else {
            conditions.add(condition);
        }
        enabled = true;

        String groupId = logicalId + "DeploymentGroup";
        ObjectNode group = resource("AWS::CodeDeploy::DeploymentGroup");
        ObjectNode groupProperties = (ObjectNode) group.path("Properties");
        groupProperties.set("ApplicationName", ref(APPLICATION));
        groupProperties.set("DeploymentConfigName",
                replaceTypes(resolveMappings(resolveParameters.apply(preference.get("Type"))), null));
        groupProperties.putObject("DeploymentStyle")
                .put("DeploymentType", "BLUE_GREEN").put("DeploymentOption", "WITH_TRAFFIC_CONTROL");
        groupProperties.putObject("AutoRollbackConfiguration").put("Enabled", true)
                .putArray("Events").add("DEPLOYMENT_FAILURE").add("DEPLOYMENT_STOP_ON_ALARM")
                .add("DEPLOYMENT_STOP_ON_REQUEST");
        if (truthy(preference.path("Role"))) {
            groupProperties.set("ServiceRoleArn", preference.get("Role").deepCopy());
        } else {
            needsRole = true;
            groupProperties.set("ServiceRoleArn", getAtt(ROLE, "Arn"));
        }
        JsonNode alarms = preference.path("Alarms");
        if (truthy(alarms) && !noValue(alarms)) {
            groupProperties.set("AlarmConfiguration", alarmConfiguration(logicalId, alarms));
        }
        if (truthy(preference.path("TriggerConfigurations"))) {
            groupProperties.set("TriggerConfigurations", preference.get("TriggerConfigurations").deepCopy());
        }
        JsonNode tags = properties.path("Tags");
        if (truthy(properties.path("PropagateTags")) && tags.isObject()) {
            sharedTags.setAll((ObjectNode) tags.deepCopy());
            groupProperties.set("Tags", tagList(tags));
        }
        if (condition != null) {
            group.put("Condition", condition);
        }
        putResource(groupId, group);

        ObjectNode policy = ((ObjectNode) resources.path(aliasId)).putObject("UpdatePolicy")
                .putObject("CodeDeployLambdaAliasUpdate");
        policy.set("ApplicationName", ref(APPLICATION));
        policy.set("DeploymentGroupName", ref(groupId));
        if (hooks.hasNonNull("PreTraffic")) {
            policy.set("BeforeAllowTrafficHook", hooks.get("PreTraffic").deepCopy());
        }
        if (hooks.hasNonNull("PostTraffic")) {
            policy.set("AfterAllowTrafficHook", hooks.get("PostTraffic").deepCopy());
        }
    }

    void finish() {
        if (!enabled) {
            return;
        }
        String condition = sharedCondition();
        ObjectNode application = resource("AWS::CodeDeploy::Application");
        ((ObjectNode) application.path("Properties")).put("ComputePlatform", "Lambda");
        decorateShared(application, condition);
        putResource(APPLICATION, application);
        if (needsRole) {
            ObjectNode role = resource("AWS::IAM::Role");
            ObjectNode properties = (ObjectNode) role.path("Properties");
            ObjectNode trust = properties.putObject("AssumeRolePolicyDocument").put("Version", "2012-10-17")
                    .putArray("Statement").addObject().put("Effect", "Allow");
            trust.putArray("Action").add("sts:AssumeRole");
            trust.putObject("Principal").putArray("Service").add(ServicePrincipals.of("codedeploy"));
            ObjectNode limited = mapper.createObjectNode();
            limited.putArray("Fn::Or").add(partitionIs("aws-iso")).add(partitionIs("aws-iso-b"));
            putCondition(LIMITED_POLICY, limited);
            ObjectNode managedPolicy = mapper.createObjectNode();
            managedPolicy.putArray("Fn::If").add(LIMITED_POLICY)
                    .add(sub("arn:${AWS::Partition}:iam::aws:policy/service-role/AWSCodeDeployRoleForLambdaLimited"))
                    .add(sub("arn:${AWS::Partition}:iam::aws:policy/service-role/AWSCodeDeployRoleForLambda"));
            properties.putArray("ManagedPolicyArns").add(managedPolicy);
            decorateShared(role, condition);
            putResource(ROLE, role);
        }
    }

    private String sharedCondition() {
        if (unconditional) {
            return null;
        }
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(conditions));
        if (unique.size() == 1) {
            return unique.getFirst();
        }
        List<JsonNode> operands = unique.stream()
                .map(name -> (JsonNode) mapper.createObjectNode().put("Condition", name)).toList();
        putCondition(CONDITION, anyCondition(operands));
        return CONDITION;
    }

    private JsonNode anyCondition(List<JsonNode> operands) {
        if (operands.size() == 1) {
            return operands.getFirst();
        }
        if (operands.size() > 10) {
            List<JsonNode> groups = new ArrayList<>();
            for (int index = 0; index < operands.size(); index += 10) {
                groups.add(anyCondition(operands.subList(index, Math.min(index + 10, operands.size()))));
            }
            return anyCondition(groups);
        }
        ObjectNode expression = mapper.createObjectNode();
        ArrayNode values = expression.putArray("Fn::Or");
        operands.forEach(values::add);
        return expression;
    }

    private void decorateShared(ObjectNode resource, String condition) {
        if (condition != null) {
            resource.put("Condition", condition);
        }
        if (!sharedTags.isEmpty()) {
            ((ObjectNode) resource.path("Properties")).set("Tags", tagList(sharedTags));
        }
    }

    private ArrayNode tagList(JsonNode tags) {
        ArrayNode list = mapper.createArrayNode();
        for (Map.Entry<String, JsonNode> tag : tags.properties()) {
            list.addObject().put("Key", tag.getKey()).set("Value", tag.getValue().deepCopy());
        }
        return list;
    }

    private JsonNode alarmConfiguration(String logicalId, JsonNode alarms) {
        if (alarms.isObject() && alarms.has("Fn::If")) {
            JsonNode branches = alarms.get("Fn::If");
            if (!branches.isArray() || branches.size() != 3 || !branches.get(0).isTextual()) {
                throw invalid(logicalId, "'Alarms' Fn::If requires a condition and two lists.");
            }
            ObjectNode conditional = mapper.createObjectNode();
            conditional.putArray("Fn::If").add(branches.get(0).deepCopy())
                    .add(alarmConfiguration(logicalId, branches.get(1)))
                    .add(alarmConfiguration(logicalId, branches.get(2)));
            return conditional;
        }
        if (!alarms.isArray()) {
            throw invalid(logicalId, "'Alarms' must be a list.");
        }
        ObjectNode configuration = mapper.createObjectNode();
        if (!alarms.isEmpty() && !noValue(alarms.get(0))) {
            configuration.put("Enabled", true);
            ArrayNode names = configuration.putArray("Alarms");
            alarms.forEach(alarm -> names.addObject().set("Name", alarm.deepCopy()));
        }
        return configuration;
    }

    private JsonNode replaceTypes(JsonNode node, String key) {
        if (node.isTextual() && PREDEFINED_TYPES.contains(node.asText())) {
            ArrayNode expression = mapper.createArrayNode().add("CodeDeployDefault.Lambda${ConfigName}")
                    .add(mapper.createObjectNode().put("ConfigName", node.asText()));
            if ("Fn::Sub".equals(key)) {
                return expression;
            }
            return mapper.createObjectNode().set("Fn::Sub", expression);
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(value -> result.add(replaceTypes(value, null)));
            return result;
        }
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            node.properties().forEach(entry -> result.set(entry.getKey(),
                    replaceTypes(entry.getValue(), entry.getKey())));
            return result;
        }
        return node.deepCopy();
    }

    private JsonNode resolveMappings(JsonNode node) {
        if (node.isObject()) {
            ObjectNode resolved = mapper.createObjectNode();
            node.properties().forEach(entry -> resolved.set(entry.getKey(), resolveMappings(entry.getValue())));
            JsonNode keys = resolved.path("Fn::FindInMap");
            if (resolved.size() == 1 && keys.isArray() && keys.size() == 3
                    && keys.get(0).isTextual() && keys.get(1).isTextual() && keys.get(2).isTextual()) {
                JsonNode value = template.path("Mappings").path(keys.get(0).asText())
                        .path(keys.get(1).asText()).path(keys.get(2).asText());
                if (!value.isMissingNode()) {
                    return value.deepCopy();
                }
            }
            return resolved;
        }
        if (node.isArray()) {
            ArrayNode resolved = mapper.createArrayNode();
            node.forEach(value -> resolved.add(resolveMappings(value)));
            return resolved;
        }
        return node.deepCopy();
    }

    private ObjectNode resource(String type) {
        ObjectNode resource = mapper.createObjectNode().put("Type", type);
        resource.putObject("Properties");
        return resource;
    }

    private void putResource(String logicalId, ObjectNode resource) {
        if (resources.has(logicalId)) {
            throw invalid(logicalId, "Generated CodeDeploy resource conflicts with an existing resource.");
        }
        resources.set(logicalId, resource);
    }

    private void putCondition(String name, JsonNode expression) {
        ObjectNode definitions = template.withObject("/Conditions");
        if (definitions.has(name) && !definitions.get(name).equals(expression)) {
            throw invalid(name, "Generated CodeDeploy condition conflicts with an existing condition.");
        }
        definitions.set(name, expression);
    }

    private ObjectNode ref(String name) {
        return mapper.createObjectNode().put("Ref", name);
    }

    private ObjectNode getAtt(String name, String attribute) {
        ObjectNode reference = mapper.createObjectNode();
        reference.putArray("Fn::GetAtt").add(name).add(attribute);
        return reference;
    }

    private ObjectNode sub(String value) {
        return mapper.createObjectNode().put("Fn::Sub", value);
    }

    private ObjectNode partitionIs(String partition) {
        ObjectNode condition = mapper.createObjectNode();
        condition.putArray("Fn::Equals").add(ref("AWS::Partition")).add(partition);
        return condition;
    }

    private static boolean noValue(JsonNode value) {
        return value.isObject() && value.size() == 1 && value.path("Ref").asText().equals("AWS::NoValue");
    }

    private static boolean truthy(JsonNode value) {
        return SamTransformProcessor.isTruthy(value)
                && (!value.isTextual() || !Set.of("false", "False").contains(value.asText()));
    }

    private static boolean booleanValue(JsonNode value) {
        return value.isBoolean() || value.isTextual() && Set.of("true", "True", "false", "False").contains(value.asText());
    }

    private static AwsException invalid(String logicalId, String message) {
        return new AwsException("ValidationError", "Resource " + logicalId + ": " + message, 400);
    }
}
