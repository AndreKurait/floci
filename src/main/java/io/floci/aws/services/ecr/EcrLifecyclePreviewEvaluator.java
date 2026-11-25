package io.floci.aws.services.ecr;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ecr.model.LifecyclePolicyPreview;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Pure selection and precedence over one verified image snapshot; never applies expiry. */
final class EcrLifecyclePreviewEvaluator {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private EcrLifecyclePreviewEvaluator() {
    }

    record Image(String digest, Instant pushedAt, List<String> tags) {
        Image {
            tags = List.copyOf(tags);
        }
    }

    record Rule(int priority, String tagStatus, List<Pattern> filters, String countType, BigInteger count) {}
    record Policy(List<Rule> rules) {}

    static Policy parse(String text) {
        if (text == null || text.length() < 100 || text.length() > 30_720) {
            throw invalid("lifecyclePolicyText must contain between 100 and 30720 characters");
        }
        JsonNode policy;
        try {
            policy = JSON.readTree(text);
        } catch (Exception failure) {
            throw invalid("lifecyclePolicyText must contain one valid JSON object");
        }
        fields(policy, Set.of("rules"), "policy");
        JsonNode rules = policy.get("rules");
        if (rules == null || !rules.isArray() || rules.isEmpty()) {
            throw invalid("rules must be a nonempty array");
        }
        List<Rule> parsed = new ArrayList<>();
        Set<Integer> priorities = new HashSet<>();
        Set<Set<String>> prefixSelections = new HashSet<>();
        boolean untagged = false;
        for (JsonNode rule : rules) {
            fields(rule, Set.of("rulePriority", "description", "selection", "action"), "rule");
            JsonNode priority = rule.get("rulePriority");
            if (priority == null || !priority.isIntegralNumber() || !priority.canConvertToInt()
                    || priority.intValue() <= 0 || !priorities.add(priority.intValue())) {
                throw invalid("rulePriority must be a unique positive integer");
            }
            if (rule.has("description") && !rule.get("description").isTextual()) {
                throw invalid("description must be a string");
            }
            JsonNode action = rule.get("action");
            fields(action, Set.of("type"), "action");
            if (!"expire".equals(string(action, "type"))) {
                throw invalid("Only expire lifecycle previews are supported");
            }
            JsonNode selection = rule.get("selection");
            fields(selection, Set.of("tagStatus", "tagPrefixList", "tagPatternList", "storageClass",
                    "countType", "countUnit", "countNumber"), "selection");
            if (selection.has("storageClass") && !"standard".equals(string(selection, "storageClass"))) {
                throw invalid("Only standard image storage is supported");
            }
            String tagStatus = string(selection, "tagStatus");
            if (!Set.of("tagged", "untagged", "any").contains(tagStatus)) {
                throw invalid("tagStatus must be tagged, untagged or any");
            }
            if ("untagged".equals(tagStatus)) {
                if (untagged) {
                    throw invalid("Only one rule may select untagged images");
                }
                untagged = true;
            }
            List<Pattern> filters = filters(selection, tagStatus, prefixSelections);
            String countType = string(selection, "countType");
            if (!Set.of("imageCountMoreThan", "sinceImagePushed").contains(countType)) {
                throw invalid("Only imageCountMoreThan and sinceImagePushed previews are supported");
            }
            if ("sinceImagePushed".equals(countType)) {
                if (!"days".equals(string(selection, "countUnit"))) {
                    throw invalid("sinceImagePushed requires countUnit days");
                }
            } else if (selection.has("countUnit")) {
                throw invalid("countUnit cannot accompany imageCountMoreThan");
            }
            JsonNode count = selection.get("countNumber");
            if (count == null || !count.isIntegralNumber() || count.bigIntegerValue().signum() <= 0) {
                throw invalid("countNumber must be a positive integer");
            }
            parsed.add(new Rule(priority.intValue(), tagStatus, List.copyOf(filters), countType,
                    count.bigIntegerValue()));
        }
        parsed.sort(Comparator.comparingInt(Rule::priority));
        for (int index = 0; index < parsed.size() - 1; index++) {
            if ("any".equals(parsed.get(index).tagStatus())) {
                throw invalid("An any rule must have the highest rulePriority value");
            }
        }
        return new Policy(List.copyOf(parsed));
    }

    private static List<Pattern> filters(JsonNode selection, String status, Set<Set<String>> prefixSelections) {
        boolean prefix = selection.has("tagPrefixList");
        boolean pattern = selection.has("tagPatternList");
        if (!"tagged".equals(status)) {
            if (prefix || pattern) {
                throw invalid("Tag filters require tagStatus tagged");
            }
            return List.of();
        }
        if (prefix == pattern) {
            throw invalid("Tagged rules require exactly one tagPrefixList or tagPatternList");
        }
        JsonNode values = selection.get(prefix ? "tagPrefixList" : "tagPatternList");
        if (!values.isArray() || values.isEmpty()) {
            throw invalid("Tag filters must be a nonempty string array");
        }
        Set<String> unique = new HashSet<>();
        List<Pattern> filters = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.textValue().isEmpty() || !unique.add(value.textValue())) {
                throw invalid("Tag filters must be nonempty, unique strings");
            }
            String filter = value.textValue();
            long wildcards = filter.chars().filter(character -> character == '*').count();
            if (prefix && wildcards != 0) {
                throw invalid("Wildcard tag prefixes are unsupported; use tagPatternList");
            }
            if (wildcards > 4) {
                throw invalid("A tag pattern may contain at most four wildcards");
            }
            filters.add(Pattern.compile("^" + (prefix ? Pattern.quote(filter) + ".*"
                    : Pattern.quote(filter).replace("*", "\\E.*\\Q")) + "$"));
        }
        if (prefix && !prefixSelections.add(Set.copyOf(unique))) {
            throw invalid("Rules must have unique tag prefix selections");
        }
        return filters;
    }

    static List<LifecyclePolicyPreview.Result> evaluate(Policy policy, List<Image> images, Instant now) {
        Set<String> identifiers = new HashSet<>();
        for (Image image : images) {
            if (image.digest() == null || image.pushedAt() == null || !identifiers.add(image.digest())) {
                throw new IllegalArgumentException("Inventory images require unique digests and known push times");
            }
        }
        List<Selection> selections = new ArrayList<>();
        for (Rule rule : policy.rules()) {
            List<Image> matching = images.stream().filter(image -> matches(rule, image)).toList();
            List<Image> expired;
            if ("imageCountMoreThan".equals(rule.countType())) {
                List<Image> youngest = matching.stream().sorted(Comparator.comparing(Image::pushedAt)
                        .reversed().thenComparing(Image::digest)).toList();
                int retain = rule.count().min(BigInteger.valueOf(youngest.size())).intValueExact();
                expired = youngest.subList(retain, youngest.size());
            } else {
                BigInteger ageSeconds = rule.count().multiply(BigInteger.valueOf(86_400));
                expired = matching.stream().filter(image -> olderThan(image.pushedAt(), now, ageSeconds)).toList();
            }
            selections.add(new Selection(rule.priority(), matching, expired));
        }
        Set<String> protectedImages = new HashSet<>();
        List<LifecyclePolicyPreview.Result> results = new ArrayList<>();
        for (Selection selection : selections) {
            for (Image image : selection.expired()) {
                if (!protectedImages.contains(image.digest())) {
                    results.add(new LifecyclePolicyPreview.Result(image.digest(), image.pushedAt(),
                            image.tags(), selection.priority()));
                }
            }
            selection.matching().forEach(image -> protectedImages.add(image.digest()));
        }
        results.sort(Comparator.comparing(LifecyclePolicyPreview.Result::imagePushedAt)
                .thenComparing(LifecyclePolicyPreview.Result::imageDigest));
        return List.copyOf(results);
    }

    private static boolean olderThan(Instant pushed, Instant now, BigInteger seconds) {
        Duration age = Duration.between(pushed, now);
        int comparison = BigInteger.valueOf(age.getSeconds()).compareTo(seconds);
        return comparison > 0 || comparison == 0 && age.getNano() > 0;
    }

    private static boolean matches(Rule rule, Image image) {
        if ("tagged".equals(rule.tagStatus()) && image.tags().isEmpty()
                || "untagged".equals(rule.tagStatus()) && !image.tags().isEmpty()) {
            return false;
        }
        return rule.filters().stream().allMatch(filter ->
                image.tags().stream().anyMatch(tag -> filter.matcher(tag).matches()));
    }

    private static void fields(JsonNode node, Set<String> allowed, String label) {
        if (node == null || !node.isObject()) {
            throw invalid(label + " must be an object");
        }
        node.properties().forEach(field -> {
            if (!allowed.contains(field.getKey())) {
                throw invalid("Unsupported " + label + " member: " + field.getKey());
            }
        });
    }

    private static String string(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid(field + " must be a string");
        }
        return value.textValue();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }

    private record Selection(int priority, List<Image> matching, List<Image> expired) {}
}
