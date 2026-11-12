package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.dynamodb.model.ReplicaAutoScalingState.Policy;
import io.floci.aws.services.dynamodb.model.ReplicaAutoScalingState.Settings;
import io.floci.aws.services.dynamodb.model.ReplicaAutoScalingState.TargetTracking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** The native JSON input boundary; null settings members mean no update. */
record ReplicaAutoScalingRequest(String tableName, Settings write, List<IndexUpdate> indexWrites,
                                 List<ReplicaUpdate> replicas) {
    private static final Pattern INDEX_NAME = Pattern.compile("[a-zA-Z0-9_.-]+");
    private static final Pattern POLICY_NAME = Pattern.compile("\\p{Print}+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final double MINIMUM_TARGET_VALUE = 8.515920e-109;
    private static final double MAXIMUM_TARGET_VALUE = 1.174271e108;

    record IndexUpdate(String name, Settings settings) {}

    record ReplicaUpdate(String region, Settings read, List<IndexUpdate> indexReads) {}

    static ReplicaAutoScalingRequest parse(JsonNode request) {
        object(request, "request");
        String table = tableName(request);
        Settings write = settings(request.get("ProvisionedWriteCapacityAutoScalingUpdate"),
                "provisionedWriteCapacityAutoScalingUpdate");
        List<IndexUpdate> indexWrites = indexes(request.get("GlobalSecondaryIndexUpdates"),
                "globalSecondaryIndexUpdates", "ProvisionedWriteCapacityAutoScalingUpdate", true);
        List<ReplicaUpdate> replicas = new ArrayList<>();
        JsonNode replicaNodes = request.get("ReplicaUpdates");
        if (present(replicaNodes)) {
            array(replicaNodes, "replicaUpdates", true);
            for (int index = 0; index < replicaNodes.size(); index++) {
                JsonNode replica = replicaNodes.get(index);
                String path = "replicaUpdates[" + index + "]";
                object(replica, path);
                String region = string(replica.get("RegionName"), path + ".regionName", true, 0, Integer.MAX_VALUE);
                Settings read = settings(replica.get("ReplicaProvisionedReadCapacityAutoScalingUpdate"),
                        path + ".replicaProvisionedReadCapacityAutoScalingUpdate");
                List<IndexUpdate> reads = indexes(replica.get("ReplicaGlobalSecondaryIndexUpdates"),
                        path + ".replicaGlobalSecondaryIndexUpdates", "ProvisionedReadCapacityAutoScalingUpdate", false);
                replicas.add(new ReplicaUpdate(region, read, reads));
            }
        }
        return new ReplicaAutoScalingRequest(table, write, List.copyOf(indexWrites), List.copyOf(replicas));
    }

    static String tableName(JsonNode request) {
        object(request, "request");
        return string(request.get("TableName"), "tableName", true, 1, 1024);
    }

    private static List<IndexUpdate> indexes(JsonNode nodes, String path, String settingMember, boolean nonempty) {
        if (!present(nodes)) {
            return List.of();
        }
        array(nodes, path, nonempty);
        List<IndexUpdate> updates = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            JsonNode node = nodes.get(index);
            String memberPath = path + "[" + index + "]";
            object(node, memberPath);
            String name = string(node.get("IndexName"), memberPath + ".indexName", true, 3, 255);
            if (!INDEX_NAME.matcher(name).matches()) {
                throw validation(memberPath + ".indexName must match [a-zA-Z0-9_.-]+");
            }
            updates.add(new IndexUpdate(name, settings(node.get(settingMember), memberPath + "." + settingMember)));
        }
        return updates;
    }

    private static Settings settings(JsonNode node, String path) {
        if (!present(node)) {
            return null;
        }
        object(node, path);
        Long minimum = positiveLong(node.get("MinimumUnits"), path + ".minimumUnits");
        Long maximum = positiveLong(node.get("MaximumUnits"), path + ".maximumUnits");
        Boolean disabled = bool(node.get("AutoScalingDisabled"), path + ".autoScalingDisabled");
        String role = string(node.get("AutoScalingRoleArn"), path + ".autoScalingRoleArn", false, 1, 1600);
        if (role != null && role.codePoints().anyMatch(value ->
                value != '\r' && value != '\n' && value != '\t'
                        && (value < 0x20 || value >= 0xd800 && value <= 0xdfff || value == 0xfffe || value == 0xffff))) {
            throw validation(path + ".autoScalingRoleArn contains invalid characters");
        }
        Policy policy = policy(node.get("ScalingPolicyUpdate"), path + ".scalingPolicyUpdate");
        return new Settings(minimum, maximum, disabled, role, policy);
    }

    private static Policy policy(JsonNode node, String path) {
        if (!present(node)) {
            return null;
        }
        object(node, path);
        String name = string(node.get("PolicyName"), path + ".policyName", false, 1, 256);
        if (name != null && !POLICY_NAME.matcher(name).matches()) {
            throw validation(path + ".policyName must contain printable characters");
        }
        JsonNode target = node.get("TargetTrackingScalingPolicyConfiguration");
        if (!present(target)) {
            throw validation(path + ".targetTrackingScalingPolicyConfiguration is required");
        }
        object(target, path + ".targetTrackingScalingPolicyConfiguration");
        JsonNode value = target.get("TargetValue");
        if (!present(value)) {
            throw validation(path + ".targetTrackingScalingPolicyConfiguration.targetValue is required");
        }
        if (!value.isNumber()) {
            throw serialization(path + ".targetTrackingScalingPolicyConfiguration.targetValue must be a number");
        }
        double targetValue = value.doubleValue();
        if (!Double.isFinite(targetValue) || targetValue < MINIMUM_TARGET_VALUE || targetValue > MAXIMUM_TARGET_VALUE) {
            throw validation(path + ".targetTrackingScalingPolicyConfiguration.targetValue is outside its supported range");
        }
        String targetPath = path + ".targetTrackingScalingPolicyConfiguration";
        return new Policy(name, new TargetTracking(
                bool(target.get("DisableScaleIn"), targetPath + ".disableScaleIn"),
                integer(target.get("ScaleInCooldown"), targetPath + ".scaleInCooldown"),
                integer(target.get("ScaleOutCooldown"), targetPath + ".scaleOutCooldown"), targetValue));
    }

    private static Long positiveLong(JsonNode node, String path) {
        if (!present(node)) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw serialization(path + " must be a 64-bit integer");
        }
        long value = node.longValue();
        if (value < 1) {
            throw validation(path + " must be greater than or equal to 1");
        }
        return value;
    }

    private static Integer integer(JsonNode node, String path) {
        if (!present(node)) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw serialization(path + " must be a 32-bit integer");
        }
        return node.intValue();
    }

    private static Boolean bool(JsonNode node, String path) {
        if (!present(node)) {
            return null;
        }
        if (!node.isBoolean()) {
            throw serialization(path + " must be a boolean");
        }
        return node.booleanValue();
    }

    private static String string(JsonNode node, String path, boolean required, int minimum, int maximum) {
        if (!present(node)) {
            if (required) {
                throw validation(path + " is required");
            }
            return null;
        }
        if (!node.isTextual()) {
            throw serialization(path + " must be a string");
        }
        String value = node.textValue();
        int length = value.codePointCount(0, value.length());
        if (length < minimum || length > maximum) {
            throw validation(path + " length must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static void array(JsonNode node, String path, boolean nonempty) {
        if (!node.isArray()) {
            throw serialization(path + " must be an array");
        }
        if (nonempty && node.isEmpty()) {
            throw validation(path + " must contain at least one member");
        }
    }

    private static void object(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw serialization(path + " must be an object");
        }
    }

    private static boolean present(JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode();
    }

    private static AwsException serialization(String message) {
        return new AwsException("SerializationException", message, 400);
    }

    static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
