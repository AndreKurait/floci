package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoScalingDescribeTagsServiceTest {

    private static final String REGION = "eu-west-1";

    @Test
    void filtersMatchTheSameTagWithAndAcrossFiltersAndOrAcrossValues() {
        AutoScalingService service = service();
        group(service, REGION, "a", Map.of("environment", "Production", "owner", "different"));
        group(service, REGION, "b", Map.of("environment", "development"));
        group(service, "us-west-2", "foreign", Map.of("environment", "Production"));

        List<Map.Entry<String, List<String>>> filters = List.of(
                new AbstractMap.SimpleImmutableEntry<>("Key", List.of("environment")),
                new AbstractMap.SimpleImmutableEntry<>("value", List.of("Production", "development")));
        PaginatedResult<AutoScalingService.TagDescription> page = service.describeTags(REGION, filters, null, null);
        assertEquals(List.of("a", "b"), page.items().stream()
                .map(AutoScalingService.TagDescription::resourceId).toList());
        assertTrue(service.describeTags(REGION, List.of(
                new AbstractMap.SimpleImmutableEntry<>("key", List.of("environment")),
                new AbstractMap.SimpleImmutableEntry<>("value", List.of("different"))), null, null).items().isEmpty());
        assertTrue(service.describeTags(REGION, List.of(
                new AbstractMap.SimpleImmutableEntry<>("value", List.of("production"))), null, null).items().isEmpty());

        service.createOrUpdateTags(REGION, "a", "auto-scaling-group",
                Map.of("environment", "updated"), Map.of("environment", true));
        AutoScalingService.TagDescription tag = service.describeTags(REGION, List.of(
                new AbstractMap.SimpleImmutableEntry<>("auto-scaling-group", List.of("a")),
                new AbstractMap.SimpleImmutableEntry<>("propagate-at-launch", List.of("true"))), null, null)
                .items().getFirst();
        assertEquals("environment", tag.key());
        assertEquals("updated", tag.value());
        assertTrue(tag.propagateAtLaunch());
        service.deleteTags(REGION, "a", "auto-scaling-group", List.of("environment"));
        assertTrue(service.describeTags(REGION, List.of(
                new AbstractMap.SimpleImmutableEntry<>("propagate-at-launch", List.of("true"))), null, null)
                .items().isEmpty());
    }

    @Test
    void pagesTagsDeterministicallyWithTheDocumentedDefaultAndBounds() {
        AutoScalingService service = service();
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 0; i < 30; i++) {
            tags.put(String.format("key-%02d", i), "value");
        }
        group(service, REGION, "b", tags);
        group(service, REGION, "a", tags);
        PaginatedResult<AutoScalingService.TagDescription> first = service.describeTags(REGION, List.of(), null, null);
        assertEquals(50, first.items().size());
        assertEquals("a", first.items().getFirst().resourceId());
        PaginatedResult<AutoScalingService.TagDescription> second = service.describeTags(REGION, List.of(), null, first.nextToken());
        assertEquals(10, second.items().size());
        assertEquals("key-20", second.items().getFirst().key());
        assertNull(second.nextToken());
        assertEquals(60, service.describeTags(REGION, List.of(), 100, null).items().size());
        for (int limit : List.of(-1, 0, 101)) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.describeTags(REGION, List.of(), limit, null));
            assertEquals("ValidationError", error.getErrorCode());
        }
        for (String token : List.of("", " ", "0", "1", "bad-token", "-1", "61", "2147483648")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.describeTags(REGION, List.of(), 1, token));
            assertEquals("ValidationError", error.getErrorCode());
        }
    }

    @Test
    void invalidFiltersDoNotSilentlyReturnUnfilteredTags() {
        AutoScalingService service = service();
        for (Map.Entry<String, List<String>> filter : List.<Map.Entry<String, List<String>>>of(
                new AbstractMap.SimpleImmutableEntry<>("unknown", List.of("value")),
                new AbstractMap.SimpleImmutableEntry<>("unknown", List.of()),
                new AbstractMap.SimpleImmutableEntry<>(null, List.of("value")),
                new AbstractMap.SimpleImmutableEntry<>("", List.of("value")),
                new AbstractMap.SimpleImmutableEntry<>(" key ", List.of("value")),
                new AbstractMap.SimpleImmutableEntry<>("key", null),
                new AbstractMap.SimpleImmutableEntry<>("propagate-at-launch", List.of("invalid")),
                new AbstractMap.SimpleImmutableEntry<>("propagate-at-launch", List.of("true", "1")))) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.describeTags(REGION, List.of(filter), null, null));
            assertEquals("ValidationError", error.getErrorCode());
        }
    }

    @Test
    void emptyValuesIgnoreValidFiltersAndBooleanValuesMatchAwsNormalization() {
        AutoScalingService service = service();
        group(service, REGION, "a", Map.of("key", "value", "other", "different"));
        service.createOrUpdateTags(REGION, "a", "auto-scaling-group",
                Map.of("key", "value"), Map.of("key", true));
        for (String name : List.of("auto-scaling-group", "key", "value", "propagate-at-launch")) {
            assertEquals(2, service.describeTags(REGION,
                    List.of(new AbstractMap.SimpleImmutableEntry<>(name, List.of())), null, null).items().size());
        }
        assertEquals("key", service.describeTags(REGION,
                List.of(new AbstractMap.SimpleImmutableEntry<>("\u212Aey", List.of("key"))), null, null)
                .items().getFirst().key());
        for (String value : List.of("true", "TRUE", "TrUe", " true ", "\tTRUE\n")) {
            assertEquals("key", service.describeTags(REGION,
                    List.of(new AbstractMap.SimpleImmutableEntry<>("propagate-at-launch", List.of(value))), null, null)
                    .items().getFirst().key());
        }
        assertEquals("other", service.describeTags(REGION,
                List.of(new AbstractMap.SimpleImmutableEntry<>("propagate-at-launch", List.of(" FALSE "))), null, null)
                .items().getFirst().key());
        assertEquals(2, service.describeTags(REGION,
                List.of(new AbstractMap.SimpleImmutableEntry<>("propagate-at-launch", List.of("TrUe", " FALSE "))),
                null, null).items().size());
    }

    private static AutoScalingService service() {
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        return service;
    }

    private static void group(AutoScalingService service, String region, String name, Map<String, String> tags) {
        service.createAutoScalingGroup(region, name, null, "lt-test", null, "1", null,
                0, 0, 0, 300, List.of(region + "a"), List.of(), List.of(), List.of(),
                "EC2", 0, List.of("Default"), tags, Map.of(), AsgOptionalFields.none());
    }
}
