package io.floci.aws.services.kms;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Internal condition context for the limits inherited from a parent KMS grant. */
public final class KmsGrantConditions {
    private static final String RETIREMENT_TARGET = "floci:kms:RetirementTarget";
    private static final String TYPE = "floci:kms:ChildConstraintType";
    private static final String KEYS = "floci:kms:ChildConstraintKeys";
    private static final String PAIR = "floci:kms:ChildConstraint:";
    private static final String SOURCE = "floci:kms:ChildSourceArn";

    private KmsGrantConditions() {
    }

    public static Map<String, List<String>> retirementContext(JsonNode body) {
        if (body == null) {
            return Map.of();
        }
        String token = body.path("GrantToken").asText("");
        if (!token.isBlank()) {
            return Map.of(RETIREMENT_TARGET, List.of("token:" + token));
        }
        String id = body.path("GrantId").asText("");
        return id.isBlank() ? Map.of() : Map.of(RETIREMENT_TARGET, List.of("id:" + id));
    }

    public static Map<String, Object> retirementConditions(Map<?, ?> grant) {
        List<String> targets = new ArrayList<>();
        if (grant.get("GrantId") instanceof String id && !id.isBlank()) {
            targets.add("id:" + id);
        }
        if (grant.get("GrantToken") instanceof String token && !token.isBlank()) {
            targets.add("token:" + token);
        }
        return Map.of("StringEquals", Map.of(RETIREMENT_TARGET, targets));
    }

    public static Map<String, List<String>> requestContext(JsonNode body) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (body == null) {
            return result;
        }
        if (body.path("Operations").isArray()) {
            List<String> operations = new ArrayList<>();
            body.path("Operations").forEach(value -> {
                if (value.isTextual()) {
                    operations.add(value.textValue());
                }
            });
            if (!operations.isEmpty()) {
                result.put("kms:GrantOperations", operations);
            }
        }
        JsonNode constraints = body.path("Constraints");
        for (String type : List.of("EncryptionContextEquals", "EncryptionContextSubset")) {
            JsonNode pairs = constraints.path(type);
            if (pairs.isObject()) {
                result.put(TYPE, List.of(type));
                List<String> keys = new ArrayList<>();
                pairs.properties().forEach(entry -> {
                    if (entry.getValue().isTextual()) {
                        String key = encodedKey(entry.getKey());
                        keys.add(key);
                        result.put(PAIR + key, List.of(entry.getValue().textValue()));
                    }
                });
                result.put(KEYS, keys);
            }
        }
        if (constraints.path("SourceArn").isTextual()) {
            result.put(SOURCE, List.of(constraints.path("SourceArn").textValue()));
        }
        return result;
    }

    public static Map<String, Object> parentConditions(Map<?, ?> grant) {
        Map<String, Object> conditions = new LinkedHashMap<>();
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("kms:GrantOperations", grant.get("Operations"));
        conditions.put("ForAllValues:StringEquals", all);
        conditions.put("Null", Map.of("kms:GrantOperations", "false"));
        Map<String, Object> equalities = new LinkedHashMap<>();
        if (grant.get("Constraints") instanceof Map<?, ?> constraints) {
            for (String type : List.of("EncryptionContextEquals", "EncryptionContextSubset")) {
                if (constraints.get(type) instanceof Map<?, ?> pairs) {
                    equalities.put(TYPE, "EncryptionContextEquals".equals(type) ? List.of(type)
                            : List.of("EncryptionContextEquals", "EncryptionContextSubset"));
                    pairs.forEach((key, value) -> equalities.put(PAIR + encodedKey(key.toString()), value));
                    if ("EncryptionContextEquals".equals(type)) {
                        if (pairs.isEmpty()) {
                            Map<String, Object> nulls = new LinkedHashMap<>();
                            nulls.put("kms:GrantOperations", "false");
                            nulls.put(KEYS, "true");
                            conditions.put("Null", nulls);
                        } else {
                            all.put(KEYS, pairs.keySet().stream().map(key -> encodedKey(key.toString())).toList());
                        }
                    }
                }
            }
            if (constraints.get("SourceArn") instanceof String source) {
                equalities.put(SOURCE, source);
                equalities.put("aws:SourceArn", source);
            }
        }
        if (!equalities.isEmpty()) {
            conditions.put("StringEquals", equalities);
        }
        return conditions;
    }

    public static String encryptionContextKey(String key) {
        return "floci:kms:EncryptionContext:" + encodedKey(key);
    }

    private static String encodedKey(String key) {
        // IAM condition names ignore case; encoding preserves case-sensitive grant keys.
        return HexFormat.of().formatHex(key.getBytes(StandardCharsets.UTF_8));
    }
}
