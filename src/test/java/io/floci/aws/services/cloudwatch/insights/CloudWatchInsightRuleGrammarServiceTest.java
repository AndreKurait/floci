package io.floci.aws.services.cloudwatch.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudwatch.insights.model.InsightRule;
import io.floci.aws.services.cloudwatch.logs.LogEventsIngested;
import io.floci.aws.services.cloudwatch.logs.model.LogEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudWatchInsightRuleGrammarServiceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "111122223333";
    private static final String OTHER_ACCOUNT = "444455556666";
    private static final String REGION = "us-east-1";
    private static final String OTHER_REGION = "eu-west-1";
    private static final String GROUP = "/example/service";
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final String SIMPLE = """
            {"Schema":{"Name":"CloudWatchLogRule","Version":1},
             "LogGroupNames":["/example/service"],"LogFormat":"JSON",
             "Contribution":{"Keys":["$.client"]},"AggregateOn":"Count"}
            """;
    private static final String MULTI = """
            {"Schema":{"Name":"CloudWatchLogRule","Version":1},
             "LogGroupNames":["/example/service*"],"LogFormat":"JSON",
             "Contribution":{
               "Keys":["$.request.headers[0].name","$.request.status","$.tenant","$.zone"],
               "Filters":[
                 {"Match":"$.request.status","StartsWith":["4","5","x0","x1","x2","x3","x4","x5","x6","x7"]},
                 {"Match":"$.request.method","StartsWith":["GET","POST"]},
                 {"Match":"$.stage","StartsWith":["prod"]},
                 {"Match":"$.request.path","StartsWith":["/api"]}]},
             "AggregateOn":"Count"}
            """;
    private static final String MATCH = """
            {"request":{"headers":[{"name":"agent"},{"name":"other"}],
                        "status":500,"method":"GET","path":"/api/items"},
             "tenant":"example","zone":"west","stage":"production"}
            """;

    @Test
    void fourKeysFourAndFiltersAndTenOrPrefixesCountNestedArrayCombinations() {
        CloudWatchInsightRulesService service = service(new InMemoryStorage<>(), ACCOUNT);
        service.put("rule", MULTI, "ENABLED", false, Map.of(), REGION);
        ingest(service, GROUP, List.of(MATCH, MATCH.replace("\"status\":500", "\"status\":400")));
        InsightRuleReport before = report(service);
        assertEquals(2, before.aggregate());
        assertEquals(2, before.unique());
        assertEquals(Set.of(List.of("agent", "400", "example", "west"),
                        List.of("agent", "500", "example", "west")),
                Set.copyOf(before.contributors().stream().map(InsightRuleReport.Contributor::keys).toList()));
        ingest(service, GROUP, List.of(
                MATCH.replace("\"status\":500", "\"status\":200"),
                MATCH.replace("\"GET\"", "\"get\""),
                MATCH.replace("\"production\"", "\"staging\""),
                MATCH.replace("\"/api/items\"", "\"/health\""),
                MATCH.replace("\"headers\"", "\"Headers\""),
                MATCH.replace("[{\"name\":\"agent\"},{\"name\":\"other\"}]", "[]")));
        ingest(service, "/example/unselected", List.of(MATCH));
        assertEquals(before, report(service));
        ingest(service, GROUP + "/child", List.of(MATCH));
        assertEquals(3, report(service).aggregate());
        assertEquals(2, report(service).unique());
        assertEquals(List.of("agent", "500", "example", "west"),
                report(service).contributors().getFirst().keys());
    }

    @Test
    void referencedNumericBoundsAndMissingFieldsExcludeOnlyIneligibleEvents() {
        CloudWatchInsightRulesService service = service(new InMemoryStorage<>(), ACCOUNT);
        service.put("rule", SIMPLE, "ENABLED", false, Map.of(), REGION);
        ingest(service, GROUP, List.of(
                "{\"client\":-1000000000}", "{\"client\":1000000000}", "{\"client\":0}",
                "{\"client\":\"1000000001\"}"));
        InsightRuleReport before = report(service);
        assertEquals(4, before.aggregate());
        assertEquals(4, before.unique());
        assertEquals(Set.of("-1000000000", "0", "1000000000", "1000000001"),
                Set.copyOf(before.contributors().stream().map(contributor -> contributor.keys().getFirst()).toList()));
        ingest(service, GROUP, List.of(
                "{\"client\":-1000000001}", "{\"client\":1000000001}",
                "{}", "{\"CLIENT\":\"wrong-case\"}", "{\"client\":null}", "{\"client\":true}",
                "{\"client\":[]}", "{\"client\":{}}", "null", "[]", "{",
                "{\"client\":\"trailing-document\"} {}"));
        assertEquals(before, report(service));
        ingest(service, GROUP, List.of("{\"client\":\"next-valid\"}"));
        assertEquals(5, report(service).aggregate());
    }

    @Test
    void asciiKeysAtOneKilobyteCoalesceOnlyAfterTheDocumentedTruncationBoundary() {
        CloudWatchInsightRulesService service = service(new InMemoryStorage<>(), ACCOUNT);
        service.put("rule", SIMPLE, "ENABLED", false, Map.of(), REGION);
        String boundary = "a".repeat(1024);
        String shorter = "a".repeat(1023);
        ingest(service, GROUP, List.of(message(boundary), message(boundary + "suffix"), message(shorter)));
        InsightRuleReport report = report(service);
        assertEquals(3, report.aggregate());
        assertEquals(2, report.unique());
        assertEquals(List.of(boundary), report.contributors().getFirst().keys());
        assertEquals(2, report.contributors().getFirst().aggregate());
        assertEquals(List.of(shorter), report.contributors().get(1).keys());
        assertEquals(1, report.contributors().get(1).aggregate());
    }

    @Test
    void unsupportedAndOverLimitRulesPreserveEveryStoredRuleTagAndContribution() throws Exception {
        InMemoryStorage<String, InsightRule> backend = new InMemoryStorage<>();
        CloudWatchInsightRulesService service = service(backend, ACCOUNT);
        CloudWatchInsightRulesService other = service(backend, OTHER_ACCOUNT);
        service.put("rule", SIMPLE, "ENABLED", false, Map.of("owner", "first"), REGION);
        service.put("rule", SIMPLE, "DISABLED", false, Map.of("owner", "other-region"), OTHER_REGION);
        other.put("rule", SIMPLE, "DISABLED", false, Map.of("owner", "other-account"), REGION);
        ingest(service, GROUP, List.of(message("original")));
        Map<String, InsightRule> before = snapshot(backend);
        InsightRuleReport original = report(service);
        List<String> unsupported = new ArrayList<>();
        unsupported.add(SIMPLE.replace("\"Count\"", "\"Sum\""));
        unsupported.add(SIMPLE.replace("\"JSON\"", "\"CLF\""));
        unsupported.add(MULTI.replace("\"StartsWith\"", "\"In\""));
        unsupported.add(SIMPLE.replace("\"LogGroupNames\"", "\"LogGroupARNs\""));
        unsupported.add(SIMPLE.replace("$.client", "$.client_name"));
        for (String definition : unsupported) {
            assertRefused(service, backend, before, original, definition, "UnsupportedOperation");
        }
        List<String> invalid = new ArrayList<>(List.of(
                "{", SIMPLE + "{}", SIMPLE.replace("\"Version\":1", "\"Version\":2"),
                SIMPLE.replace("/example/service", "/example/*/service")));
        ObjectNode tooManyKeys = (ObjectNode) MAPPER.readTree(SIMPLE);
        ((ObjectNode) tooManyKeys.path("Contribution")).putArray("Keys")
                .add("$.one").add("$.two").add("$.three").add("$.four").add("$.five");
        invalid.add(tooManyKeys.toString());
        ObjectNode tooManyFilters = (ObjectNode) MAPPER.readTree(MULTI);
        ((ObjectNode) tooManyFilters.path("Contribution")).withArray("Filters")
                .add(tooManyFilters.path("Contribution").path("Filters").get(0).deepCopy());
        invalid.add(tooManyFilters.toString());
        ObjectNode tooManyPrefixes = (ObjectNode) MAPPER.readTree(MULTI);
        ((ObjectNode) tooManyPrefixes.path("Contribution").path("Filters").get(0))
                .withArray("StartsWith").add("eleventh");
        invalid.add(tooManyPrefixes.toString());
        for (String definition : invalid) {
            assertRefused(service, backend, before, original, definition, "InvalidParameterValue");
        }
        ingest(service, GROUP, List.of(message("after-refusals")));
        assertEquals(2, report(service).aggregate());
        assertEquals(Map.of("owner", "first"), service.listTags(service.arn("rule", REGION), REGION));
        assertEquals(before.get(OTHER_ACCOUNT + "/" + REGION + "::rule"),
                snapshot(backend).get(OTHER_ACCOUNT + "/" + REGION + "::rule"));
        assertEquals(before.get(ACCOUNT + "/" + OTHER_REGION + "::rule"),
                snapshot(backend).get(ACCOUNT + "/" + OTHER_REGION + "::rule"));
    }

    private static void assertRefused(CloudWatchInsightRulesService service,
                                      InMemoryStorage<String, InsightRule> backend,
                                      Map<String, InsightRule> before, InsightRuleReport original,
                                      String definition, String code) {
        AwsException failure = assertThrows(AwsException.class,
                () -> service.put("rule", definition, "ENABLED", false, Map.of("owner", "changed"), REGION));
        assertEquals(code, failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
        assertEquals(before, snapshot(backend));
        assertEquals(original, report(service));
    }

    private static String message(String value) {
        return MAPPER.createObjectNode().put("client", value).toString();
    }

    private static void ingest(CloudWatchInsightRulesService service, String group, List<String> messages) {
        List<LogEvent> events = new ArrayList<>();
        for (String message : messages) {
            LogEvent event = new LogEvent();
            event.setMessage(message);
            event.setTimestamp(BASE.plusSeconds(30).toEpochMilli());
            events.add(event);
        }
        service.ingest(new LogEventsIngested(ACCOUNT, REGION, group, "events", events));
    }

    private static InsightRuleReport report(CloudWatchInsightRulesService service) {
        return service.report("rule", BASE, BASE.plusSeconds(120), 60, 100, "Sum",
                List.of("SampleCount"), REGION);
    }

    private static CloudWatchInsightRulesService service(InMemoryStorage<String, InsightRule> backend, String account) {
        return new CloudWatchInsightRulesService(new AccountAwareStorageBackend<>(backend, null, account),
                new RegionResolver(REGION, account), () -> BASE.plusSeconds(180).toEpochMilli());
    }

    private static Map<String, InsightRule> snapshot(InMemoryStorage<String, InsightRule> backend) {
        Map<String, InsightRule> result = new HashMap<>();
        backend.keys().forEach(key -> result.put(key, backend.get(key).orElseThrow()));
        assertTrue(result.size() >= 3);
        return result;
    }
}
