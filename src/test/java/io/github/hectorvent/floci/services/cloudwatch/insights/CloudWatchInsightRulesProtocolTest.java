package io.github.hectorvent.floci.services.cloudwatch.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.common.XmlParser.XmlElement;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsJsonHandler;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsQueryHandler;
import io.restassured.path.xml.XmlPath;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CloudWatchInsightRulesProtocolTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REGION = "us-east-1";
    private static final String BODY = "{\"Schema\":{\"Name\":\"CloudWatchLogRule\",\"Version\":1},"
            + "\"LogGroupNames\":[\"/example/service\"],\"LogFormat\":\"JSON\","
            + "\"Contribution\":{\"Keys\":[\"$.client\"]},\"AggregateOn\":\"Count\"}";
    private final CloudWatchInsightRulesService service = new CloudWatchInsightRulesService(
            AccountAwareStorageBackend.inMemory("111122223333"), new RegionResolver(REGION, "111122223333"));
    private final CloudWatchInsightRulesHandler rules = new CloudWatchInsightRulesHandler(service);
    private final CloudWatchMetricsQueryHandler query = new CloudWatchMetricsQueryHandler(null, null, null, rules);
    private final CloudWatchMetricsJsonHandler json = new CloudWatchMetricsJsonHandler(null, null, null, MAPPER, rules);

    @Test
    void queryCreateJsonUpdateQueryTagsAndJsonDeleteShareOneRule() throws Exception {
        MultivaluedMap<String, String> parameters = parameters(Map.of("RuleName", "rule", "RuleDefinition", BODY,
                "RuleState", "DISABLED", "Tags.member.1.Key", "team", "Tags.member.1.Value", "first"));
        Response created = query.handle("PutInsightRule", parameters, REGION);
        assertEquals(200, created.getStatus());
        XmlElement createdXml = XmlParser.parseDocumentElementTree(created.getEntity().toString());
        assertNotNull(createdXml);
        assertEquals("PutInsightRuleResponse", createdXml.name());
        assertNotNull(createdXml.child("PutInsightRuleResult"));
        ObjectNode updated = MAPPER.createObjectNode().put("RuleName", "rule")
                .put("RuleDefinition", BODY.replace("/example/service", "/example/updated")).put("RuleState", "DISABLED");
        json.handle("PutInsightRule", updated, REGION);
        String described = query.handle("DescribeInsightRules", parameters(Map.of()), REGION).getEntity().toString();
        XmlPath xml = XmlPath.from(described);
        assertEquals("rule", xml.getString("DescribeInsightRulesResponse.DescribeInsightRulesResult.InsightRules.member.Name"));
        assertEquals(updated.path("RuleDefinition").textValue(),
                xml.getString("DescribeInsightRulesResponse.DescribeInsightRulesResult.InsightRules.member.Definition"));
        String arn = service.arn("rule", REGION);
        query.handle("TagResource", parameters(Map.of("ResourceARN", arn,
                "Tags.member.1.Key", "team", "Tags.member.1.Value", "second")), REGION);
        assertEquals(Map.of("team", "second"), service.listTags(arn, REGION));
        ObjectNode delete = MAPPER.createObjectNode();
        delete.putArray("RuleNames").add("rule");
        ObjectNode response = (ObjectNode) json.handle("DeleteInsightRules", delete, REGION).getEntity();
        assertTrue(response.path("Failures").isEmpty());
        assertTrue(service.describe(null, null, REGION).items().isEmpty());
    }

    @Test
    void malformedTypesAndSparseQueryMembersCannotChangeExistingRules() {
        service.put("rule", BODY, "DISABLED", false, Map.of("team", "keep"), REGION);
        String arn = service.arn("rule", REGION);
        assertThrows(AwsException.class, () -> query.handle("DeleteInsightRules",
                parameters(Map.of("RuleNames.member.2", "rule")), REGION));
        assertThrows(AwsException.class, () -> query.handle("TagResource",
                parameters(Map.of("ResourceARN", arn, "Tags.member.1.Key", "team")), REGION));
        assertThrows(AwsException.class, () -> query.handle("PutInsightRule",
                parameters(Map.of("RuleName", "rule", "RuleDefinition", BODY, "RuleState", "DISABLED",
                        "ApplyOnTransformedLogs", "maybe")), REGION));
        ObjectNode malformed = MAPPER.createObjectNode().put("RuleName", "rule")
                .put("RuleDefinition", BODY).put("RuleState", "DISABLED").put("ApplyOnTransformedLogs", "false");
        assertThrows(AwsException.class, () -> json.handle("PutInsightRule", malformed, REGION));
        assertEquals(BODY, service.describe(null, null, REGION).items().getFirst().definition());
        assertEquals(Map.of("team", "keep"), service.listTags(arn, REGION));
    }

    private static MultivaluedMap<String, String> parameters(Map<String, String> values) {
        MultivaluedMap<String, String> result = new MultivaluedHashMap<>();
        values.forEach(result::putSingle);
        return result;
    }
}
