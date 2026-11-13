package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.glue.model.CustomEntityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GlueCustomEntityServiceTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AccountAwareStorageBackend<CustomEntityType> store = AccountAwareStorageBackend.inMemory(ACCOUNT);
    private final GlueCustomEntityService service = service(ACCOUNT);

    private GlueCustomEntityService service(String account) {
        return new GlueCustomEntityService(store, new RegionResolver(REGION, account), MAPPER);
    }

    static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    static JsonNode named(String name) {
        return MAPPER.createObjectNode().put("Name", name);
    }

    static ObjectNode pattern(String name, String expression) {
        return MAPPER.createObjectNode().put("Name", name).put("RegexString", expression);
    }

    @Test
    void recordsAreImmutableAndDuplicateDeleteRecreatePreservesTheOtherPattern() {
        ObjectNode first = pattern("first", "[0-9]+");
        first.putArray("ContextWords").add("invoice").add("reference");
        first.putObject("Tags").put("owner", "one");
        service.create(first, REGION);
        first.put("RegexString", "mutated");
        first.withArray("ContextWords").add("mutated");
        service.create(pattern("second", "[A-Z]+"), REGION);
        Map<String, Object> original = service.get(named("first"), REGION);
        assertEquals("[0-9]+", original.get("RegexString"));
        assertEquals(List.of("invoice", "reference"), original.get("ContextWords"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<?>) original.get("ContextWords")).clear());
        assertEquals("AlreadyExistsException", assertThrows(AwsException.class,
                () -> service.create(pattern("first", "different"), REGION)).getErrorCode());
        assertEquals(original, service.get(named("first"), REGION));
        assertEquals(Map.of("Name", "first"), service.delete(named("first"), REGION));
        assertEquals("EntityNotFoundException", assertThrows(AwsException.class,
                () -> service.get(named("first"), REGION)).getErrorCode());
        assertEquals("[A-Z]+", service.get(named("second"), REGION).get("RegexString"));
        assertEquals(List.of("first"), service.batchGet(json("{\"Names\":[\"second\",\"first\"]}"), REGION)
                .get("CustomEntityTypesNotFound"));
        service.create(pattern("first", "replacement"), REGION);
        assertEquals(Map.of("Name", "first", "RegexString", "replacement"), service.get(named("first"), REGION));
    }

    @Test
    void accountAndRegionPartitionsNeverReadDeleteOrListEachOther() {
        service.create(pattern("same", "account-one-east"), REGION);
        service.create(pattern("same", "account-one-west"), "us-west-2");
        GlueCustomEntityService other = service("222222222222");
        other.create(pattern("same", "account-two-east"), REGION);
        assertEquals("account-one-east", service.get(named("same"), REGION).get("RegexString"));
        assertEquals("account-one-west", service.get(named("same"), "us-west-2").get("RegexString"));
        assertEquals("account-two-east", other.get(named("same"), REGION).get("RegexString"));
        assertEquals(1, ((List<?>) service.list(json("{}"), REGION).get("CustomEntityTypes")).size());
        other.delete(named("same"), REGION);
        assertEquals("account-one-east", service.get(named("same"), REGION).get("RegexString"));
        assertTrue(((List<?>) other.list(json("{}"), REGION).get("CustomEntityTypes")).isEmpty());
    }

    @Test
    void taggedPagesAreScopedToTheAccountRegionAndExactFilter() {
        for (String name : List.of("a", "b", "c")) {
            ObjectNode pattern = pattern(name, name);
            pattern.putObject("Tags").put("group", "one").put("kind", name.equals("c") ? "other" : "chosen");
            service.create(pattern, REGION);
        }
        JsonNode first = json("{\"MaxResults\":1,\"Tags\":{\"group\":\"one\",\"kind\":\"chosen\"}}");
        Map<String, Object> page = service.list(first, REGION);
        assertEquals(List.of(Map.of("Name", "a", "RegexString", "a")), page.get("CustomEntityTypes"));
        String token = (String) page.get("NextToken");
        assertNotNull(token);
        ObjectNode next = first.deepCopy();
        next.put("NextToken", token);
        service.delete(named("a"), REGION);
        assertEquals(List.of(Map.of("Name", "b", "RegexString", "b")),
                service.list(next, REGION).get("CustomEntityTypes"));
        assertFalse(service.list(next, REGION).containsKey("NextToken"));
        assertThrows(AwsException.class, () -> service.list(next, "us-west-2"));
        assertThrows(AwsException.class, () -> service("222222222222").list(next, REGION));
        ObjectNode changed = next.deepCopy();
        changed.set("Tags", json("{\"group\":\"two\"}"));
        assertThrows(AwsException.class, () -> service.list(changed, REGION));
        changed.put("NextToken", token + "x");
        assertThrows(AwsException.class, () -> service.list(changed, REGION));
        assertTrue(((List<?>) service.list(json("{\"Tags\":{\"group\":\"absent\"}}"), REGION)
                .get("CustomEntityTypes")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "[]", "{}", "{\"Name\":1,\"RegexString\":\"x\"}", "{\"Name\":\"\",\"RegexString\":\"x\"}",
            "{\"Name\":\"line\\nbreak\",\"RegexString\":\"x\"}", "{\"Name\":\"x\",\"RegexString\":true}",
            "{\"Name\":\"x\",\"RegexString\":\"x\",\"ContextWords\":[]}",
            "{\"Name\":\"x\",\"RegexString\":\"x\",\"ContextWords\":[1]}",
            "{\"Name\":\"x\",\"RegexString\":\"x\",\"Tags\":[]}",
            "{\"Name\":\"x\",\"RegexString\":\"x\",\"Tags\":{\"key\":3}}",
            "{\"Name\":\"x\",\"RegexString\":\"x\",\"Tags\":{\"\":\"value\"}}"
    })
    void malformedCreatesDoNotLeaveStoredRecords(String request) {
        AwsException error = assertThrows(AwsException.class, () -> service.create(json(request), REGION));
        assertEquals("InvalidInputException", error.getErrorCode());
        assertTrue(store.keys().isEmpty());
    }

    @Test
    void allPublishedCollectionAndStringBoundsAreEnforcedWithoutChangingExistingRecords() {
        service.create(pattern("existing", "kept"), REGION);
        List<JsonNode> invalid = new ArrayList<>();
        invalid.add(pattern("x".repeat(256), "x"));
        invalid.add(pattern("x", "x".repeat(256)));
        ObjectNode words = pattern("x", "x");
        for (int i = 0; i < 21; i++) {
            words.withArray("ContextWords").add("word");
        }
        invalid.add(words);
        ObjectNode tags = pattern("x", "x");
        for (int i = 0; i < 51; i++) {
            tags.withObject("/Tags").put("tag-" + i, "value");
        }
        invalid.add(tags);
        ObjectNode longKey = pattern("x", "x");
        longKey.putObject("Tags").put("x".repeat(129), "value");
        invalid.add(longKey);
        ObjectNode longValue = pattern("x", "x");
        longValue.putObject("Tags").put("key", "x".repeat(257));
        invalid.add(longValue);
        for (JsonNode request : invalid) {
            assertThrows(AwsException.class, () -> service.create(request, REGION));
        }
        assertEquals("kept", service.get(named("existing"), REGION).get("RegexString"));
        assertEquals(1, store.keys().size());
        String unicode = "😀".repeat(255);
        service.create(pattern(unicode, "\t[0-9]+"), REGION);
        assertEquals(unicode, service.get(named(unicode), REGION).get("Name"));
    }

    @Test
    void invalidReadInputsAndOutOfRangePageSizesAreRefused() {
        for (String request : List.of("{\"Names\":[]}", "{\"Names\":[null]}", "{\"Names\":\"x\"}")) {
            assertThrows(AwsException.class, () -> service.batchGet(json(request), REGION));
        }
        ObjectNode batch = MAPPER.createObjectNode();
        for (int i = 0; i < 51; i++) {
            batch.withArray("Names").add("x");
        }
        assertThrows(AwsException.class, () -> service.batchGet(batch, REGION));
        for (String request : List.of("{\"MaxResults\":0}", "{\"MaxResults\":1001}", "{\"MaxResults\":1.5}",
                "{\"MaxResults\":\"1\"}", "{\"NextToken\":2}", "{\"NextToken\":\"garbage\"}")) {
            assertThrows(AwsException.class, () -> service.list(json(request), REGION));
        }
        assertThrows(AwsException.class, () -> service.get(json("{}"), REGION));
        assertThrows(AwsException.class, () -> service.delete(json("{}"), REGION));
    }

    @Test
    void simultaneousCreatesHaveOneWinnerAndNeverPublishAPartialPattern() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int number = i;
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        service.create(pattern("contended", "value-" + number), REGION);
                        return true;
                    } catch (AwsException e) {
                        assertEquals("AlreadyExistsException", e.getErrorCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get(10, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertEquals(1, winners);
        }
        assertTrue(((String) service.get(named("contended"), REGION).get("RegexString")).startsWith("value-"));
        assertEquals(1, store.keys().size());
    }

    @Test
    void simultaneousDeletesRemoveTheWholePatternOnceAndPreserveItsSibling() throws Exception {
        service.create(pattern("contended", "removed"), REGION);
        service.create(pattern("sibling", "preserved"), REGION);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        assertEquals(Map.of("Name", "contended"), service.delete(named("contended"), REGION));
                        return true;
                    } catch (AwsException e) {
                        assertEquals("EntityNotFoundException", e.getErrorCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int deleted = 0;
            for (Future<Boolean> result : results) {
                deleted += result.get(10, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertEquals(1, deleted);
        }
        assertThrows(AwsException.class, () -> service.get(named("contended"), REGION));
        assertEquals("preserved", service.get(named("sibling"), REGION).get("RegexString"));
        assertEquals(1, store.keys().size());
    }
}
