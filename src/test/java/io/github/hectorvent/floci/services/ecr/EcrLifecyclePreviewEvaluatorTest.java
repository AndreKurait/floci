package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecr.model.LifecyclePolicyPreview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EcrLifecyclePreviewEvaluatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-01-31T00:00:00Z");

    @Test
    void countKeepsTheYoungestAndOrdersExpiredRowsWithoutMutatingInput() throws Exception {
        List<EcrLifecyclePreviewEvaluator.Image> images = new ArrayList<>(List.of(
                image("c", NOW.minusSeconds(10), "prod-3"), image("a", NOW.minusSeconds(30), "prod-1"),
                image("b", NOW.minusSeconds(20), "prod-2")));
        List<EcrLifecyclePreviewEvaluator.Image> before = List.copyOf(images);
        List<LifecyclePolicyPreview.Result> result = evaluate(List.of(rule(1, "tagged", "imageCountMoreThan", 1,
                Map.of("tagPrefixList", List.of("prod")))), images);
        assertEquals(List.of("a", "b"), result.stream().map(LifecyclePolicyPreview.Result::imageDigest).toList());
        assertEquals(before, images);
        assertEquals(List.of(1, 1), result.stream().map(LifecyclePolicyPreview.Result::appliedRulePriority).toList());
    }

    @Test
    void ageIsStrictAtTheBoundaryAndDoesNotOverflowLargeCounts() throws Exception {
        List<EcrLifecyclePreviewEvaluator.Image> images = List.of(
                image("older", NOW.minusSeconds(86_400).minusNanos(1), "prod"),
                image("equal", NOW.minusSeconds(86_400), "prod"),
                image("newer", NOW.minusSeconds(86_400).plusNanos(1), "prod"));
        assertEquals(List.of("older"), evaluate(List.of(rule(1, "any", "sinceImagePushed", 1,
                Map.of("countUnit", "days"))), images).stream()
                .map(LifecyclePolicyPreview.Result::imageDigest).toList());
        String huge = policy(List.of(rule(1, "any", "sinceImagePushed", 1, Map.of("countUnit", "days"))))
                .replace("\"countNumber\":1", "\"countNumber\":999999999999999999999999999999999999999999");
        assertTrue(EcrLifecyclePreviewEvaluator.evaluate(EcrLifecyclePreviewEvaluator.parse(huge), images, NOW).isEmpty());
    }

    @Test
    void earlierTaggingMatchProtectsRetainedImagesFromLowerPriorityRules() throws Exception {
        List<EcrLifecyclePreviewEvaluator.Image> images = List.of(
                image("shared-old", NOW.minusSeconds(200_000), "prod-v1", "release-v1"),
                image("release-only", NOW.minusSeconds(190_000), "release-v2"));
        List<Map<String, Object>> rules = List.of(
                rule(2, "tagged", "sinceImagePushed", 1,
                        Map.of("tagPrefixList", List.of("release"), "countUnit", "days")),
                rule(1, "tagged", "imageCountMoreThan", 10, Map.of("tagPrefixList", List.of("prod"))));
        List<LifecyclePolicyPreview.Result> results = evaluate(rules, images);
        assertEquals(List.of("release-only"), results.stream().map(LifecyclePolicyPreview.Result::imageDigest).toList());
        assertEquals(2, results.getFirst().appliedRulePriority());
    }

    @Test
    void patternAndPrefixFiltersRequireAllFiltersAcrossAnImagesTags() throws Exception {
        List<EcrLifecyclePreviewEvaluator.Image> images = List.of(
                image("both-old", NOW.minusSeconds(30), "prod-a", "release.a"),
                image("both-new", NOW.minusSeconds(10), "prod-b", "release.b"),
                image("only-one", NOW.minusSeconds(40), "prod-c"),
                image("regex-decoy", NOW.minusSeconds(50), "prod-d", "releaseXb"));
        Map<String, Object> pattern = rule(1, "tagged", "imageCountMoreThan", 1,
                Map.of("tagPatternList", List.of("prod-*", "release.*")));
        assertEquals(List.of("both-old"), evaluate(List.of(pattern), images).stream()
                .map(LifecyclePolicyPreview.Result::imageDigest).toList());
        Map<String, Object> prefix = rule(1, "tagged", "imageCountMoreThan", 1,
                Map.of("tagPrefixList", List.of("prod-", "release.")));
        assertEquals(List.of("both-old"), evaluate(List.of(prefix), images).stream()
                .map(LifecyclePolicyPreview.Result::imageDigest).toList());
    }

    @Test
    void anyRuleIsLastAndEachImageCanHaveOnlyOneAction() throws Exception {
        List<EcrLifecyclePreviewEvaluator.Image> images = List.of(
                image("tagged-old", NOW.minusSeconds(300_000), "prod"),
                image("tagged-new", NOW.minusSeconds(10), "prod"),
                image("untagged-old", NOW.minusSeconds(200_000)));
        List<LifecyclePolicyPreview.Result> result = evaluate(List.of(
                rule(1, "tagged", "imageCountMoreThan", 1, Map.of("tagPrefixList", List.of("prod"))),
                rule(9, "any", "sinceImagePushed", 1, Map.of("countUnit", "days"))), images);
        assertEquals(List.of("tagged-old", "untagged-old"), result.stream()
                .map(LifecyclePolicyPreview.Result::imageDigest).toList());
        assertEquals(List.of(1, 9), result.stream().map(LifecyclePolicyPreview.Result::appliedRulePriority).toList());
    }

    @Test
    void completedSnapshotCopiesBothListLevels() {
        List<String> tags = new ArrayList<>(List.of("prod"));
        LifecyclePolicyPreview.Result row = new LifecyclePolicyPreview.Result("digest", NOW, tags, 1);
        List<LifecyclePolicyPreview.Result> rows = new ArrayList<>(List.of(row));
        LifecyclePolicyPreview snapshot = new LifecyclePolicyPreview("id", "generation", "policy", NOW, rows);
        tags.clear();
        rows.clear();
        assertEquals(List.of("prod"), snapshot.results().getFirst().imageTags());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.results().clear());
        assertThrows(UnsupportedOperationException.class, () -> row.imageTags().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"archive", "transition", "pull-age", "count-unit", "wild-prefix", "five-stars",
            "empty-filter", "both-filters", "zero-count", "duplicate-priority", "any-first", "duplicate-untagged",
            "duplicate-json", "trailing-json", "unknown-member"})
    void unsupportedAndMalformedPoliciesFailBeforeEvaluation(String kind) throws Exception {
        Map<String, Object> base = rule(1, "tagged", "imageCountMoreThan", 1, Map.of("tagPrefixList", List.of("prod")));
        String text = policy(List.of(base));
        text = switch (kind) {
            case "archive" -> text.replace("expire", "archive");
            case "transition" -> text.replace("expire", "transition");
            case "pull-age" -> text.replace("imageCountMoreThan", "sinceImagePulled");
            case "count-unit" -> text.replace("\"countNumber\":1", "\"countNumber\":1,\"countUnit\":\"days\"");
            case "wild-prefix" -> text.replace("\"prod\"", "\"prod*\"");
            case "five-stars" -> text.replace("tagPrefixList", "tagPatternList").replace("\"prod\"", "\"******\"");
            case "empty-filter" -> text.replace("[\"prod\"]", "[]");
            case "both-filters" -> text.replace("\"tagPrefixList\"", "\"tagPatternList\":[\"prod*\"],\"tagPrefixList\"");
            case "zero-count" -> text.replace("\"countNumber\":1", "\"countNumber\":0");
            case "duplicate-priority" -> policy(List.of(base, base));
            case "any-first" -> policy(List.of(rule(1, "any", "imageCountMoreThan", 1, Map.of()),
                    rule(2, "untagged", "imageCountMoreThan", 1, Map.of())));
            case "duplicate-untagged" -> policy(List.of(rule(1, "untagged", "imageCountMoreThan", 1, Map.of()),
                    rule(2, "untagged", "imageCountMoreThan", 1, Map.of())));
            case "duplicate-json" -> text.replace("\"rulePriority\":1", "\"rulePriority\":1,\"rulePriority\":2");
            case "trailing-json" -> text + "{}";
            case "unknown-member" -> text.replace("\"action\":", "\"unknown\":true,\"action\":");
            default -> throw new IllegalStateException(kind);
        };
        String invalid = text;
        AwsException error = assertThrows(AwsException.class, () -> EcrLifecyclePreviewEvaluator.parse(invalid));
        assertEquals("InvalidParameterException", error.getErrorCode());
    }

    private static EcrLifecyclePreviewEvaluator.Image image(String digest, Instant pushedAt, String... tags) {
        return new EcrLifecyclePreviewEvaluator.Image(digest, pushedAt, List.of(tags));
    }

    private static List<LifecyclePolicyPreview.Result> evaluate(List<Map<String, Object>> rules,
                                                                List<EcrLifecyclePreviewEvaluator.Image> images)
            throws Exception {
        return EcrLifecyclePreviewEvaluator.evaluate(EcrLifecyclePreviewEvaluator.parse(policy(rules)), images, NOW);
    }

    private static String policy(List<Map<String, Object>> rules) throws Exception {
        return JSON.writeValueAsString(Map.of("rules", rules));
    }

    private static Map<String, Object> rule(int priority, String status, String countType, int count,
                                            Map<String, Object> extra) {
        Map<String, Object> selection = new HashMap<>(extra);
        selection.put("tagStatus", status);
        selection.put("countType", countType);
        selection.put("countNumber", count);
        return Map.of("rulePriority", priority, "selection", selection, "action", Map.of("type", "expire"),
                "description", "Pure evaluator contract and independent expected selection");
    }
}
