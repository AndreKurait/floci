package io.github.hectorvent.floci.services.s3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A single S3 notification filter rule (prefix or suffix match on the object key).
 *
 * @param name  "prefix" or "suffix"
 * @param value the form-URL-encoded value to match against
 */
@RegisterForReflection
public record FilterRule(String name, String value) {

    public boolean matches(String key) {
        if (key == null || name == null || value == null) {
            return false;
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // A malformed stored filter must not interrupt notifications to other destinations.
            return false;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "prefix" -> key.startsWith(decoded);
            case "suffix" -> key.endsWith(decoded);
            default -> false;
        };
    }
}
