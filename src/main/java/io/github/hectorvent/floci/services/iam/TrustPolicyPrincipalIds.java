package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Transforms only the role and user identities in a trust policy's Principal.AWS entries. */
final class TrustPolicyPrincipalIds {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern ID = Pattern.compile("(?:AROA|AIDA)[A-Z0-9]+");

    private TrustPolicyPrincipalIds() {
    }

    static boolean isIdentityId(String value) {
        return value != null && ID.matcher(value).matches();
    }

    static String rewrite(String document, UnaryOperator<String> mapping) {
        if (document == null || document.isBlank()) {
            return document;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(document);
        } catch (JsonProcessingException ignored) {
            // Reading or restoring a historical row must preserve an older accepted document.
            return document;
        }
        if (root == null) {
            return document;
        }
        JsonNode statements = root.path("Statement");
        Iterable<JsonNode> entries = statements.isArray() ? statements : List.of(statements);
        boolean changed = false;
        for (JsonNode statement : entries) {
            if (!(statement.path("Principal") instanceof ObjectNode principal)) {
                continue;
            }
            JsonNode aws = principal.path("AWS");
            if (aws.isTextual()) {
                String replacement = mapping.apply(aws.textValue());
                if (!replacement.equals(aws.textValue())) {
                    principal.put("AWS", replacement);
                    changed = true;
                }
            } else if (aws instanceof ArrayNode array) {
                for (int index = 0; index < array.size(); index++) {
                    JsonNode entry = array.get(index);
                    if (entry.isTextual()) {
                        String replacement = mapping.apply(entry.textValue());
                        if (!replacement.equals(entry.textValue())) {
                            array.set(index, MAPPER.getNodeFactory().textNode(replacement));
                            changed = true;
                        }
                    }
                }
            }
        }
        return changed ? root.toString() : document;
    }
}
