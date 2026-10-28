package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsQueryLists;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Direct AssumeRole tags shared by request authorization and session issuance. */
public final class StsSessionTags {
    private static final Pattern MEMBER = Pattern.compile("(Tags(?:\\.member)?\\.([^.]+))\\.(?:Key|Value)");
    private static final Pattern VALUE = Pattern.compile("[\\p{L}\\p{Z}\\p{N}_.:/=+\\-@]*");

    private StsSessionTags() {
    }

    public static Map<String, String> read(MultivaluedMap<String, String> parameters) {
        Map<String, String> form = new LinkedHashMap<>();
        parameters.keySet().forEach(key -> form.put(key, parameters.getFirst(key)));
        return readForm(form);
    }

    public static Map<String, String> readForm(Map<String, String> form) {
        List<String> members = form.keySet().stream().map(MEMBER::matcher).filter(Matcher::matches)
                .map(matcher -> matcher.group(1)).distinct()
                .sorted(Comparator.comparingInt(AwsQueryLists::memberIndex)).toList();
        if (members.size() > 50) {
            throw invalid("Member 'tags' must have length less than or equal to 50");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (String member : members) {
            String key = form.get(member + ".Key");
            String value = form.get(member + ".Value");
            validate(member + ".Key", key, 1, 128);
            validate(member + ".Value", value, 0, 256);
            if (tags.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(key))) {
                throw invalid("Duplicate session tag keys are not allowed");
            }
            tags.put(key, value);
        }
        return Map.copyOf(tags);
    }

    private static void validate(String member, String value, int minimum, int maximum) {
        if (value == null) {
            throw invalid(SmithyValidationErrors.notNull(member));
        }
        String length = SmithyValidationErrors.lengthOutOfRange(member, value, minimum, maximum);
        if (length != null) {
            throw invalid(length);
        }
        if (value.regionMatches(true, 0, "aws:", 0, 4)) {
            throw invalid("Tag keys and values must not begin with the reserved aws: prefix");
        }
        if (!VALUE.matcher(value).matches()) {
            throw invalid("Member '" + member + "' must satisfy regular expression pattern: " + VALUE.pattern());
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", message, 400);
    }

    public static Map<String, List<String>> context(Map<String, String> tags) {
        Map<String, List<String>> context = new LinkedHashMap<>();
        if (!tags.isEmpty()) {
            tags.forEach((key, value) -> context.put("aws:RequestTag/" + key, List.of(value)));
            context.put("aws:TagKeys", new ArrayList<>(tags.keySet()));
        }
        return context;
    }

    /** Session tags replace attached role tags case-insensitively, without changing either source. */
    public static Map<String, String> effective(Map<String, String> roleTags, Map<String, String> sessionTags) {
        Map<String, String> effective = new LinkedHashMap<>(roleTags == null ? Map.of() : roleTags);
        if (sessionTags != null) {
            sessionTags.forEach((key, value) -> {
                effective.keySet().removeIf(existing -> existing.equalsIgnoreCase(key));
                effective.put(key, value);
            });
        }
        return Map.copyOf(effective);
    }
}
