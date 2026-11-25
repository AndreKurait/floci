package io.floci.aws.services.memorydb;

import io.floci.aws.services.memorydb.model.MemoryDbParameterGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.floci.aws.services.memorydb.MemoryDbParameterGroupTestFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class MemoryDbParameterGroupServiceTest {
    private final MemoryDbParameterGroupTestFixture fixture = new MemoryDbParameterGroupTestFixture();

    @ParameterizedTest
    @ValueSource(ints = {1, 40, 41, 255})
    void documentedNameLengthsHaveCanonicalIdentity(int length) {
        String name = "A" + "b".repeat(length - 1);
        MemoryDbParameterGroup group = fixture.create(name);
        assertEquals(name.toLowerCase(Locale.ROOT), group.name());
        assertEquals(group, fixture.current(name.toUpperCase(Locale.ROOT)));
        error("ParameterGroupAlreadyExistsFault", () -> fixture.create(group.name()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1group", "a_b", "a--b", "a-", " a", "é", "default.memorydb-redis6"})
    void invalidNamesCannotPublish(String name) {
        error("InvalidParameterValueException", () -> fixture.create(name));
        assertTrue(fixture.raw.isEmpty());
    }

    @Test
    void name256IsRejectedWithoutTruncating() {
        error("InvalidParameterValueException", () -> fixture.create("a".repeat(256)));
        assertTrue(fixture.raw.isEmpty());
    }

    @Test
    void defaultReadsDoNotMaterializeAndOnlyTagsCanHaveAnOverlay() {
        for (String name : fixture.catalog.defaults().keySet()) {
            MemoryDbParameterGroup group = fixture.current(name);
            fixture.service.parameters(name, null, null, REGION);
            assertTrue(fixture.service.listTags(group.arn(), REGION).isEmpty());
            assertTrue(fixture.service.iamTags(group.arn(), group.partition(), REGION, ACCOUNT).isEmpty());
            error("InvalidParameterGroupStateFault", () -> fixture.service.delete(name, REGION));
            error("InvalidParameterGroupStateFault", () -> fixture.service.update(name, update("timeout", "20"), REGION));
            error("InvalidParameterGroupStateFault", () -> fixture.service.reset(name, true, null, REGION));
        }
        assertTrue(fixture.raw.isEmpty());
        String name = "default.memorydb-redis7.search";
        MemoryDbParameterGroup before = fixture.current(name);
        List<Map<String, String>> values = fixture.service.parameters(name, null, null, REGION).values();
        fixture.service.tag(before.arn(), Map.of("owner", "one"), REGION);
        assertEquals(values, fixture.service.parameters(name, null, null, REGION).values());
        assertEquals(before.generation(), fixture.current(name).generation());
        assertEquals(Map.of("owner", "one"), fixture.service.untag(before.arn(), List.of("owner"), REGION));
        assertTrue(fixture.service.listTags(before.arn(), REGION).isEmpty());
    }

    @Test
    void accountsRegionsAndPartitionsKeepIndependentSameNamedLifetimes() {
        MemoryDbParameterGroup first = fixture.create("same");
        when(fixture.regions.getAccountId()).thenReturn("222222222222");
        MemoryDbParameterGroup second = fixture.create("same");
        MemoryDbParameterGroup china = fixture.service.create("same", FAMILY, null, null, "cn-north-1");
        assertEquals("aws-cn", china.partition());
        assertNotEquals(first.arn(), second.arn());
        error("ParameterGroupNotFoundFault", () -> fixture.service.listTags(first.arn(), REGION));
        assertTrue(fixture.service.iamTags(first.arn(), "aws", REGION, "222222222222").isEmpty());
        fixture.service.delete("same", REGION);
        assertEquals(china, fixture.service.describe("same", null, null, "cn-north-1").values().getFirst());
        when(fixture.regions.getAccountId()).thenReturn(ACCOUNT);
        assertEquals(first, fixture.current("same"));
    }

    @Test
    void readOnlyLookupDoesNotAdoptLegacyOrContradictoryRecords() throws Exception {
        MemoryDbParameterGroup group = fixture.create("same");
        fixture.raw.clear();
        fixture.raw.put(REGION + ":same", group);
        byte[] before = fixture.mapper.writeValueAsBytes(fixture.raw.scan(key -> true));
        assertTrue(fixture.service.iamTags(group.arn(), "aws", REGION, ACCOUNT).isEmpty());
        error("ParameterGroupNotFoundFault", () -> fixture.current("same"));
        assertArrayEquals(before, fixture.mapper.writeValueAsBytes(fixture.raw.scan(key -> true)));
        assertEquals(Set.of(REGION + ":same"), fixture.raw.keys());
        MemoryDbParameterGroup foreign = new MemoryDbParameterGroup("222222222222", group.region(), group.partition(),
                group.name(), group.arn(), group.family(), group.description(), group.generation(), group.revision(),
                group.catalogVersion(), group.overrides(), group.tags());
        fixture.raw.put(ACCOUNT + "/" + REGION + ":same", foreign);
        assertTrue(fixture.service.iamTags(group.arn(), "aws", REGION, ACCOUNT).isEmpty());
        error("ParameterGroupNotFoundFault", () -> fixture.current("same"));
        assertSame(foreign, fixture.raw.get(ACCOUNT + "/" + REGION + ":same").orElseThrow());
        assertFalse(fixture.service.describe(null, null, null, REGION).values().stream()
                .anyMatch(value -> value.name().equals("same")));
    }

    @ParameterizedTest
    @CsvSource({"timeout,19", "timeout,-1", "timeout,1.5", "timeout,9223372036854775808x",
            "maxclients,65000", "maxmemory-policy,unknown", "client-query-buffer-limit,1048575",
            "unknown,20", "search-enabled,yes"})
    void invalidUpdateBatchPreservesEntirePublishedRecord(String name, String value) {
        MemoryDbParameterGroup before = fixture.create("atomic");
        error("InvalidParameterValueException", () -> fixture.service.update("atomic",
                List.of(new MemoryDbParameterGroupService.ParameterValue("slowlog-max-len", "42"),
                        new MemoryDbParameterGroupService.ParameterValue(name, value)), REGION));
        assertSame(before, fixture.current("atomic"));
    }

    @Test
    void duplicateUpdateAndResetNamesAndIllegalCombinationsAreAtomic() {
        MemoryDbParameterGroup before = fixture.create("atomic");
        error("InvalidParameterValueException", () -> fixture.service.update("atomic",
                List.of(update("timeout", "20").getFirst(), update("timeout", "30").getFirst()), REGION));
        error("InvalidParameterValueException", () -> fixture.service.reset("atomic", false,
                List.of("timeout", "timeout"), REGION));
        error("InvalidParameterValueException", () -> fixture.service.reset("atomic", false,
                List.of("timeout", "maxclients"), REGION));
        error("InvalidParameterCombinationException", () -> fixture.service.reset("atomic", true, List.of(), REGION));
        error("InvalidParameterCombinationException", () -> fixture.service.reset("atomic", false, null, REGION));
        assertSame(before, fixture.current("atomic"));
    }

    @Test
    void updateTwentyPublishesOnceAndTwentyOnePublishesNothing() {
        fixture.create("batch");
        List<MemoryDbParameterGroupService.ParameterValue> values = fixture.catalog.parameters(FAMILY, Map.of())
                .stream().filter(row -> fixture.catalog.parameter(FAMILY, row.get("Name")).modifiable()).limit(20)
                .map(row -> new MemoryDbParameterGroupService.ParameterValue(row.get("Name"), row.get("Value"))).toList();
        assertEquals(20, values.size());
        MemoryDbParameterGroup after = fixture.service.update("batch", values, REGION);
        assertEquals(1, after.revision());
        List<MemoryDbParameterGroupService.ParameterValue> tooMany = new ArrayList<>(values);
        tooMany.add(update("timeout", "20").getFirst());
        error("InvalidParameterValueException", () -> fixture.service.update("batch", tooMany, REGION));
        assertSame(after, fixture.current("batch"));
    }

    @Test
    void integerDomainsUseArbitraryPrecisionAndSearchHasItsPublishedBound() {
        fixture.create("numbers");
        String hugeNegative = "-999999999999999999999999999999999999999";
        MemoryDbParameterGroup after = fixture.service.update("numbers", update("slowlog-log-slower-than", hugeNegative), REGION);
        assertEquals(hugeNegative, after.overrides().get("slowlog-log-slower-than"));
        fixture.service.update("numbers", update("timeout", "0"), REGION);
        fixture.service.update("numbers", update("timeout", "20"), REGION);
        fixture.service.create("search", "memorydb_redis7", null, null, REGION);
        fixture.service.update("search", update("search-query-timeout-ms", "1"), REGION);
        MemoryDbParameterGroup limit = fixture.service.update("search", update("search-query-timeout-ms", "60000"), REGION);
        error("InvalidParameterValueException",
                () -> fixture.service.update("search", update("search-query-timeout-ms", "60001"), REGION));
        assertSame(limit, fixture.current("search"));
    }

    @Test
    void tagInputsAndReturnedMapsCannotMutateAStoredGeneration() {
        Map<String, String> input = new HashMap<>(Map.of("team", "one"));
        MemoryDbParameterGroup first = fixture.service.create("tags", FAMILY, null, input, REGION);
        input.put("team", "external");
        assertEquals("one", first.tags().get("team"));
        assertThrows(UnsupportedOperationException.class, () -> first.tags().put("team", "external"));
        error("InvalidParameterValueException",
                () -> fixture.service.tag(first.arn(), Map.of("aws:reserved", "bad"), REGION));
        error("InvalidParameterValueException",
                () -> fixture.service.tag(first.arn(), Map.of("valid", "v".repeat(257)), REGION));
        Map<String, String> tooMany = new HashMap<>();
        for (int i = 0; i < 51; i++) {
            tooMany.put("k" + i, "v");
        }
        error("TagQuotaPerResourceExceeded", () -> fixture.service.tag(first.arn(), tooMany, REGION));
        assertSame(first, fixture.current("tags"));
        fixture.service.delete("tags", REGION);
        MemoryDbParameterGroup second = fixture.create("tags");
        assertNotEquals(first.generation(), second.generation());
        assertEquals(0, second.revision());
    }

    @Test
    void tokensRejectChangedScopeOperationSizeGenerationAndReset() {
        fixture.create("paged");
        String token = fixture.service.parameters("paged", 20, null, REGION).nextToken();
        assertNotNull(token);
        assertFalse(fixture.service.parameters("paged", 20, token, REGION).values().isEmpty());
        error("InvalidParameterValueException", () -> fixture.service.parameters("paged", 21, token, REGION));
        error("InvalidParameterValueException", () -> fixture.service.describe("paged", 20, token, REGION));
        error("InvalidParameterValueException", () -> fixture.service.parameters("paged", 20, token + "x", REGION));
        when(fixture.regions.getAccountId()).thenReturn("222222222222");
        fixture.create("paged");
        error("InvalidParameterValueException", () -> fixture.service.parameters("paged", 20, token, REGION));
        when(fixture.regions.getAccountId()).thenReturn(ACCOUNT);
        fixture.service.tag(fixture.current("paged").arn(), Map.of("team", "changed"), REGION);
        error("InvalidParameterValueException", () -> fixture.service.parameters("paged", 20, token, REGION));
        String beforeDelete = fixture.service.parameters("paged", 20, null, REGION).nextToken();
        fixture.service.delete("paged", REGION);
        fixture.create("paged");
        error("InvalidParameterValueException", () -> fixture.service.parameters("paged", 20, beforeDelete, REGION));
        String beforeReset = fixture.service.parameters("paged", 20, null, REGION).nextToken();
        fixture.service.clear();
        error("InvalidParameterValueException", () -> fixture.service.parameters("paged", 20, beforeReset, REGION));
    }
}
