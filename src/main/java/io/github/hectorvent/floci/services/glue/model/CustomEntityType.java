package io.github.hectorvent.floci.services.glue.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A stored custom pattern definition; saving a definition does not execute its expression. */
@RegisterForReflection
public record CustomEntityType(String name, String regexString, List<String> contextWords,
                               Map<String, String> tags) {
    public CustomEntityType {
        contextWords = contextWords == null ? null : List.copyOf(contextWords);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    public Map<String, Object> response() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("Name", name);
        response.put("RegexString", regexString);
        if (contextWords != null) {
            response.put("ContextWords", contextWords);
        }
        return response;
    }
}
