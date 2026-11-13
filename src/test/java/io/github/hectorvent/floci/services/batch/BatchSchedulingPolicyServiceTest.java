package io.github.hectorvent.floci.services.batch;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.batch.model.StoredSchedulingPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.hectorvent.floci.services.batch.BatchSchedulingPolicyTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchSchedulingPolicyServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "[]", "null", "{\"name\":\"a\",\"name\":\"b\"}", "{} {}", "{\"tags\":{\"a\":\"b\",\"a\":\"c\"}}"})
    void rawReaderRefusesNonobjectsDuplicateMembersAndTrailingData(String body) {
        assertEquals("ClientException", assertThrows(AwsException.class, () -> READER.object(body)).getErrorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"computeReservation\":100}", "{\"shareDecaySeconds\":604801}", "{\"shareDecaySeconds\":0.5}",
            "{\"computeReservation\":\"1\"}", "{\"shareDistribution\":{}}",
            "{\"shareDistribution\":[{\"shareIdentifier\":\"a\",\"weightFactor\":0}]}",
            "{\"shareDistribution\":[{\"shareIdentifier\":\"a\",\"weightFactor\":1000}]}",
            "{\"shareDistribution\":[{\"shareIdentifier\":\"a\",\"weightFactor\":\"1\"}]}",
            "{\"shareDistribution\":[{\"shareIdentifier\":\"a\"},{\"shareIdentifier\":\"a\"}]}",
            "{\"shareDistribution\":[{\"shareIdentifier\":\"ab*\"},{\"shareIdentifier\":\"abc\"}]}",
            "{\"shareDistribution\":[{\"shareIdentifier\":\"abc\"},{\"shareIdentifier\":\"ab*\"}]}",
            "{\"shareDistribution\":[{\"shareIdentifier\":\"a*b\"}]}",
            "{\"shareDistribution\":[{\"shareIdentifier\":\"\"}]}",
            "{\"shareDistribution\":[{\"shareIdentifier\":\"a-b\"}]}"
    })
    void invalidUpdatesPreserveEveryStoredField(String policy) {
        BatchSchedulingPolicyTestFixture fixture = new BatchSchedulingPolicyTestFixture();
        StoredSchedulingPolicy before = fixture.create("held");
        assertThrows(AwsException.class, () -> fixture.service.update(READER.object(
                "{\"arn\":\"" + fixture.arn("held") + "\",\"fairsharePolicy\":" + policy + "}")));
        assertSame(before, fixture.stored("held"));
        assertEquals(Set.of(fixture.key("held")), fixture.storage.keys());
    }

    @Test
    void replacementResetsOmittedMembersButKeepsIdentityTagsAndDetachedPreviousSnapshot() {
        BatchSchedulingPolicyTestFixture fixture = new BatchSchedulingPolicyTestFixture();
        StoredSchedulingPolicy before = fixture.create("held");
        fixture.service.update(fixture.update("held", 90));
        StoredSchedulingPolicy after = fixture.stored("held");
        assertEquals(before.generation(), after.generation());
        assertEquals(before.revision() + 1, after.revision());
        assertEquals(before.arn(), after.arn());
        assertEquals(before.tags(), after.tags());
        assertEquals(0, after.fairshare().computeReservation());
        assertTrue(after.fairshare().shareDistribution().isEmpty());
        assertEquals(90, after.fairshare().shareDecaySeconds());
        assertEquals(20, before.fairshare().computeReservation());
        assertEquals(2.0F, before.fairshare().shareDistribution().getFirst().weightFactor());
        fixture.service.update(fixture.select("held"));
        assertSame(after, fixture.stored("held"));
        assertThrows(UnsupportedOperationException.class, () -> after.tags().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> before.fairshare().shareDistribution().clear());
    }

    @Test
    void unsetAndQuotaKindsHaveExplicitProjectionsAndCannotSwitchKind() {
        BatchSchedulingPolicyTestFixture fixture = new BatchSchedulingPolicyTestFixture();
        fixture.service.create(json(Map.of("name", "unset")));
        assertEquals("unset", fixture.stored("unset").kind());
        assertEquals(0, json(fixture.service.describe(json(Map.of("arns", List.of(fixture.arn("unset"))))))
                .path("schedulingPolicies").get(0).path("fairsharePolicy").path("shareDecaySeconds").asInt());
        fixture.service.update(json(Map.of("arn", fixture.arn("unset"),
                "quotaSharePolicy", Map.of("idleResourceAssignmentStrategy", "FIFO"))));
        StoredSchedulingPolicy quota = fixture.stored("unset");
        assertEquals("quota", quota.kind());
        assertThrows(AwsException.class, () -> fixture.service.update(fixture.update("unset", 3)));
        assertSame(quota, fixture.stored("unset"));
        fixture.service.update(fixture.select("unset"));
        assertSame(quota, fixture.stored("unset"));
        assertThrows(AwsException.class, () -> fixture.service.create(json(Map.of("name", "both",
                "fairsharePolicy", Map.of(), "quotaSharePolicy", Map.of("idleResourceAssignmentStrategy", "FIFO")))));
        assertFalse(fixture.storage.keys().contains(fixture.key("both")));
    }

    @Test
    void duplicateCreateAndBadTagsKeepCompleteGenerationAndScope() {
        BatchSchedulingPolicyTestFixture fixture = new BatchSchedulingPolicyTestFixture();
        StoredSchedulingPolicy before = fixture.create("held");
        assertEquals(409, assertThrows(AwsException.class, () -> fixture.create("held")).getHttpStatus());
        List<Map<String, String>> bad = new ArrayList<>(List.of(Map.of("aws:reserved", "x"),
                Map.of("key", "AWS:reserved"), Map.of("", "x"), Map.of("k".repeat(129), "x"),
                Map.of("key", "v".repeat(257))));
        Map<String, String> tooMany = new LinkedHashMap<>();
        for (int index = 0; index < 50; index++) {
            tooMany.put("key" + index, "v");
        }
        bad.add(tooMany);
        for (Map<String, String> added : bad) {
            assertThrows(AwsException.class, () -> fixture.service.tag(before.arn(), added));
            assertSame(before, fixture.stored("held"));
        }
        assertThrows(AwsException.class, () -> fixture.service.untag(before.arn(), List.of()));
        assertSame(before, fixture.stored("held"));
        fixture.service.tag(before.arn(), Map.of("empty", ""));
        assertEquals(Map.of("team", "blue", "empty", ""), fixture.service.listTags(before.arn()));
        fixture.service.untag(before.arn(), List.of("empty", "absent"));
        assertEquals(before.tags(), fixture.service.listTags(before.arn()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"111111111111/us-west-2", "123456789012/us-east-2", "123456789012/cn-north-1", "123456789012/us-gov-west-1"})
    void sameNameScopesAndExactSelectorsNeverMutateAnotherRow(String otherScope) {
        InMemoryStorage<String, StoredSchedulingPolicy> storage = new InMemoryStorage<>();
        BatchSchedulingPolicyTestFixture owner = new BatchSchedulingPolicyTestFixture(storage);
        String[] selected = otherScope.split("/");
        BatchSchedulingPolicyTestFixture other = new BatchSchedulingPolicyTestFixture(storage, selected[0], selected[1]);
        StoredSchedulingPolicy original = owner.create("same");
        StoredSchedulingPolicy separate = other.create("same");
        assertNotEquals(original.arn(), separate.arn());
        assertThrows(AwsException.class, () -> other.service.delete(json(Map.of("arn", original.arn()))));
        assertThrows(AwsException.class, () -> other.service.tag(original.arn(), Map.of("x", "y")));
        assertEquals(Map.of(), other.service.iamTags(original.arn(), other.partition, other.region, other.account));
        assertSame(original, owner.stored("same"));
        assertSame(separate, other.stored("same"));
        other.service.delete(other.select("same"));
        assertSame(original, owner.stored("same"));
        assertEquals(Set.of(owner.key("same")), storage.keys());
    }

    @Test
    void describeValidatesAllSelectorsBeforeProjectionAndOmitsUnknownDuplicates() {
        BatchSchedulingPolicyTestFixture fixture = new BatchSchedulingPolicyTestFixture();
        StoredSchedulingPolicy before = fixture.create("held");
        assertEquals(1, json(fixture.service.describe(json(Map.of("arns",
                List.of(before.arn(), fixture.arn("absent"), before.arn()))))).path("schedulingPolicies").size());
        assertThrows(AwsException.class, () -> fixture.service.describe(json(Map.of("arns", List.of(before.arn(), "held")))));
        assertSame(before, fixture.stored("held"));
        assertThrows(AwsException.class, () -> fixture.service.describe(json(Map.of("arns", List.of()))));
    }

    @Test
    void pagesAreCompleteSignedScopedAndGenerationBoundButSurviveConfigurationChanges() {
        BatchSchedulingPolicyTestFixture fixture = new BatchSchedulingPolicyTestFixture();
        fixture.create("b");
        fixture.create("a");
        Map<String, Object> page = fixture.service.list(json(Map.of("maxResults", 1)));
        String token = (String) page.get("nextToken");
        assertEquals(List.of(Map.of("arn", fixture.arn("a"))), page.get("schedulingPolicies"));
        fixture.service.update(fixture.update("b", 90));
        fixture.service.tag(fixture.arn("b"), Map.of("new", "tag"));
        Map<String, Object> last = fixture.service.list(json(Map.of("maxResults", 1, "nextToken", token)));
        assertEquals(List.of(Map.of("arn", fixture.arn("b"))), last.get("schedulingPolicies"));
        assertFalse(last.containsKey("nextToken"));
        assertThrows(AwsException.class, () -> fixture.service.list(json(Map.of("maxResults", 2, "nextToken", token))));
        assertThrows(AwsException.class, () -> fixture.service.list(json(Map.of("maxResults", 1, "nextToken", token + "x"))));
        fixture.service.delete(fixture.select("b"));
        fixture.create("b");
        assertThrows(AwsException.class, () -> fixture.service.list(json(Map.of("maxResults", 1, "nextToken", token))));
        fixture.service.delete(fixture.select("a"));
        fixture.service.delete(fixture.select("b"));
        assertEquals(Map.of("schedulingPolicies", List.of()), fixture.service.list(json(Map.of())));
    }

    @Test
    void unknownVersionAndConflictingIdentityFailWithoutMigrationOrRewrite() {
        BatchSchedulingPolicyTestFixture fixture = new BatchSchedulingPolicyTestFixture();
        StoredSchedulingPolicy known = fixture.create("held");
        for (StoredSchedulingPolicy corrupt : List.of(
                new StoredSchedulingPolicy(9, known.account(), known.partition(), known.region(), known.name(), known.arn(),
                        known.generation(), 0, known.kind(), known.fairshare(), null, known.tags(), 1, 1),
                new StoredSchedulingPolicy(1, "999999999999", known.partition(), known.region(), known.name(), known.arn(),
                        known.generation(), 0, known.kind(), known.fairshare(), null, known.tags(), 1, 1))) {
            fixture.storage.put(fixture.key("held"), corrupt);
            assertEquals(500, assertThrows(AwsException.class, () -> fixture.service.list(json(Map.of()))).getHttpStatus());
            assertThrows(AwsException.class, () -> fixture.service.iamTags(known.arn(), "aws", REGION, ACCOUNT));
            assertThrows(AwsException.class, () -> fixture.service.delete(fixture.select("held")));
            assertSame(corrupt, fixture.stored("held"));
            assertEquals(Set.of(fixture.key("held")), fixture.storage.keys());
        }
    }

    @Test
    void explorerProjectsOnlyCurrentAccountRegionAndUsesDetachedTags() {
        BatchSchedulingPolicyTestFixture fixture = new BatchSchedulingPolicyTestFixture();
        StoredSchedulingPolicy before = fixture.create("held");
        assertEquals(List.of(before.arn()), fixture.service.getResources().stream().map(resource -> resource.arn()).toList());
        fixture.service.delete(fixture.select("held"));
        assertTrue(fixture.service.getResources().isEmpty());
        assertEquals(Map.of("team", "blue"), before.tags());
        assertEquals(1, fixture.service.getSupportedResourceTypes().size());
    }
}
