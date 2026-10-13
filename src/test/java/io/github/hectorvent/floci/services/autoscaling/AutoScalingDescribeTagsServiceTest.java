package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import org.junit.jupiter.api.Test;

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

        List<AutoScalingService.TagFilter> filters = List.of(
                new AutoScalingService.TagFilter("Key", List.of("environment")),
                new AutoScalingService.TagFilter("value", List.of("Production", "development")));
        AutoScalingService.TagPage page = service.describeTags(REGION, filters, null, null);
        assertEquals(List.of("a", "b"), page.tags().stream()
                .map(AutoScalingService.TagDescription::resourceId).toList());
        assertTrue(service.describeTags(REGION, List.of(
                new AutoScalingService.TagFilter("key", List.of("environment")),
                new AutoScalingService.TagFilter("value", List.of("different"))), null, null).tags().isEmpty());
        assertTrue(service.describeTags(REGION, List.of(
                new AutoScalingService.TagFilter("value", List.of("production"))), null, null).tags().isEmpty());

        service.createOrUpdateTags(REGION, "a", "auto-scaling-group",
                Map.of("environment", "updated"), Map.of("environment", true));
        AutoScalingService.TagDescription tag = service.describeTags(REGION, List.of(
                new AutoScalingService.TagFilter("auto-scaling-group", List.of("a")),
                new AutoScalingService.TagFilter("propagate-at-launch", List.of("true"))), null, null)
                .tags().getFirst();
        assertEquals("environment", tag.key());
        assertEquals("updated", tag.value());
        assertTrue(tag.propagateAtLaunch());
        service.deleteTags(REGION, "a", "auto-scaling-group", List.of("environment"));
        assertTrue(service.describeTags(REGION, List.of(
                new AutoScalingService.TagFilter("propagate-at-launch", List.of("true"))), null, null)
                .tags().isEmpty());
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
        AutoScalingService.TagPage first = service.describeTags(REGION, List.of(), null, null);
        assertEquals(50, first.tags().size());
        assertEquals("a", first.tags().getFirst().resourceId());
        AutoScalingService.TagPage second = service.describeTags(REGION, List.of(), null, first.nextToken());
        assertEquals(10, second.tags().size());
        assertEquals("key-20", second.tags().getFirst().key());
        assertNull(second.nextToken());
        assertEquals(60, service.describeTags(REGION, List.of(), 100, null).tags().size());
        for (int limit : List.of(0, 101)) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.describeTags(REGION, List.of(), limit, null));
            assertEquals("ValidationError", error.getErrorCode());
        }
        for (String token : List.of("bad-token", "-1", "61", "2147483648")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.describeTags(REGION, List.of(), 1, token));
            assertEquals("InvalidNextToken", error.getErrorCode());
        }
    }

    @Test
    void invalidFiltersDoNotSilentlyReturnUnfilteredTags() {
        AutoScalingService service = service();
        for (AutoScalingService.TagFilter filter : List.of(
                new AutoScalingService.TagFilter("unknown", List.of("value")),
                new AutoScalingService.TagFilter(null, List.of("value")),
                new AutoScalingService.TagFilter("key", List.of()))) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.describeTags(REGION, List.of(filter), null, null));
            assertEquals("ValidationError", error.getErrorCode());
        }
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
