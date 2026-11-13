package io.github.hectorvent.floci.services.iam.model;

import io.github.hectorvent.floci.core.common.ServicePrincipals;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable identity facts captured from a resolved credential, never from request condition values. */
public record VerifiedIdentityContext(Map<String, List<String>> cognitoClaims) {
    public VerifiedIdentityContext {
        Map<String, List<String>> snapshot = new HashMap<>();
        cognitoClaims.forEach((key, values) -> snapshot.put(key, List.copyOf(values)));
        cognitoClaims = Map.copyOf(snapshot);
    }

    /** Replaces request-derived Cognito keys, including differently cased spellings, with verified facts. */
    public Map<String, List<String>> mergeInto(Map<String, List<String>> supplied) {
        String prefix = ServicePrincipals.of("cognito-identity") + ":";
        Map<String, List<String>> context = new LinkedHashMap<>();
        if (supplied != null) {
            supplied.forEach((key, values) -> {
                // Match IAM normalization's null handling and preserve its first case-insensitive key.
                if (key != null && values != null && !key.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    context.put(key, values.stream().filter(Objects::nonNull).toList());
                }
            });
        }
        cognitoClaims.forEach((key, values) -> context.put(prefix + key, values));
        return Collections.unmodifiableMap(context);
    }
}
