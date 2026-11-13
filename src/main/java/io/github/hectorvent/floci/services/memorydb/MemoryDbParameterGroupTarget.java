package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;

import java.util.Locale;
import java.util.Set;

/** Shared canonical identity for the handler, primary IAM action and tagged create. */
public final class MemoryDbParameterGroupTarget {
    private static final Set<String> GROUP_ACTIONS = Set.of(
            "memorydb:CreateParameterGroup", "memorydb:DeleteParameterGroup",
            "memorydb:DescribeParameterGroups", "memorydb:DescribeParameters",
            "memorydb:UpdateParameterGroup", "memorydb:ResetParameterGroup");
    private static final Set<String> TAG_ACTIONS = Set.of(
            "memorydb:ListTags", "memorydb:TagResource", "memorydb:UntagResource");
    private static final String PREFIX = "parametergroup/";

    private MemoryDbParameterGroupTarget() {}

    public static boolean claims(String operation, JsonNode body) {
        return operation != null && (GROUP_ACTIONS.contains(operation)
                || TAG_ACTIONS.contains(operation) && isGroupArn(text(body, "ResourceArn")));
    }

    public static boolean isGroupArn(String value) {
        if (!AwsArnUtils.isArn(value)) {
            return false;
        }
        try {
            return AwsArnUtils.parse(value).resource().startsWith(PREFIX);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    public static String arn(String partition, String region, String account, String name) {
        return new AwsArnUtils.Arn(partition, "memorydb", region, account, PREFIX + name).toString();
    }

    public static String createdArn(JsonNode body, String partition, String region, String account) {
        String name = text(body, "ParameterGroupName");
        return name == null || name.isEmpty() ? "*"
                : arn(partition, region, account, name.toLowerCase(Locale.ROOT));
    }

    public static String resourceArn(String operation, JsonNode body, String partition, String region, String account) {
        if ("memorydb:CreateParameterGroup".equals(operation)) {
            return "*";
        }
        if (TAG_ACTIONS.contains(operation)) {
            String selected = text(body, "ResourceArn");
            return selected == null ? "*" : selected;
        }
        return createdArn(body, partition, region, account);
    }

    private static String text(JsonNode body, String name) {
        JsonNode value = body == null ? null : body.get(name);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}
