package io.floci.aws.services.cloudwatch.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsNamespaces;
import io.floci.aws.core.common.AwsQueryResponse;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.XmlBuilder;
import io.floci.aws.services.cloudwatch.insights.model.InsightRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static io.floci.aws.services.cloudwatch.insights.InsightRuleDefinition.invalid;
import static io.floci.aws.services.cloudwatch.insights.InsightRuleDefinition.unsupported;

/** Both public CloudWatch protocols use the same validation and response members. */
@ApplicationScoped
public class CloudWatchInsightRulesHandler {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> OPERATIONS = Set.of("PutInsightRule", "DescribeInsightRules",
            "DeleteInsightRules", "EnableInsightRules", "DisableInsightRules", "GetInsightRuleReport");
    private static final Set<String> TAG_OPERATIONS = Set.of("TagResource", "UntagResource", "ListTagsForResource");
    private final CloudWatchInsightRulesService service;

    public CloudWatchInsightRulesHandler(CloudWatchInsightRulesService service) {
        this.service = service;
    }

    public boolean handles(String operation, String resourceArn) {
        return OPERATIONS.contains(operation) || TAG_OPERATIONS.contains(operation)
                && CloudWatchInsightRulesService.isInsightRuleArn(resourceArn);
    }

    public Response json(String operation, JsonNode request, String region) {
        return Response.ok(result(operation, request, region)).build();
    }

    public Response query(String operation, MultivaluedMap<String, String> parameters, String region) {
        ObjectNode request = MAPPER.createObjectNode();
        for (String field : List.of("RuleName", "RuleDefinition", "RuleState", "NextToken", "ResourceARN",
                "StartTime", "EndTime", "OrderBy")) {
            if (parameters.containsKey(field)) {
                request.put(field, single(parameters, field));
            }
        }
        for (String field : List.of("MaxResults", "Period", "MaxContributorCount")) {
            if (parameters.containsKey(field)) {
                try {
                    request.put(field, Integer.parseInt(single(parameters, field)));
                } catch (NumberFormatException error) {
                    throw invalid(field + " must be an integer.");
                }
            }
        }
        if (parameters.containsKey("ApplyOnTransformedLogs")) {
            String value = single(parameters, "ApplyOnTransformedLogs");
            if (!"true".equals(value) && !"false".equals(value)) {
                throw invalid("ApplyOnTransformedLogs must be a boolean.");
            }
            request.put("ApplyOnTransformedLogs", Boolean.parseBoolean(value));
        }
        for (String field : List.of("RuleNames", "TagKeys", "Metrics")) {
            List<String> values = queryList(parameters, field);
            if (!values.isEmpty() || parameters.containsKey(field)) {
                ArrayNode array = request.putArray(field);
                values.forEach(array::add);
            }
        }
        TreeMap<Integer, ObjectNode> tags = new TreeMap<>();
        parameters.keySet().stream().filter(key -> key.startsWith("Tags.member.")).forEach(key -> {
            String suffix = key.substring("Tags.member.".length());
            int separator = suffix.indexOf('.');
            if (separator < 1) {
                throw invalid("Invalid Tags member.");
            }
            int index = index(suffix.substring(0, separator));
            String field = suffix.substring(separator + 1);
            if (!Set.of("Key", "Value").contains(field)) {
                throw invalid("Invalid Tags member.");
            }
            tags.computeIfAbsent(index, ignored -> MAPPER.createObjectNode())
                    .put(field, single(parameters, key));
        });
        if (!tags.isEmpty() || parameters.containsKey("Tags")) {
            contiguous(tags.keySet());
            ArrayNode array = request.putArray("Tags");
            tags.values().forEach(array::add);
        }
        ObjectNode result = result(operation, request, region);
        XmlBuilder xml = new XmlBuilder();
        result.properties().forEach(entry -> xml(xml, entry.getKey(), entry.getValue()));
        return Response.ok(AwsQueryResponse.envelope(operation, AwsNamespaces.CW, xml.build())).build();
    }

    private ObjectNode result(String operation, JsonNode request, String region) {
        ObjectNode result = MAPPER.createObjectNode();
        switch (operation) {
            case "PutInsightRule" -> service.put(text(request, "RuleName"), text(request, "RuleDefinition"),
                    text(request, "RuleState"), bool(request, "ApplyOnTransformedLogs"), tags(request), region);
            case "DescribeInsightRules" -> {
                JsonNode maximum = request.get("MaxResults");
                if (maximum != null && (!maximum.isIntegralNumber() || !maximum.canConvertToInt())) {
                    throw invalid("MaxResults must be an integer.");
                }
                PaginatedResult<InsightRule> page = service.describe(maximum == null ? null : maximum.intValue(),
                        text(request, "NextToken"), region);
                ArrayNode rules = result.putArray("InsightRules");
                for (InsightRule rule : page.items()) {
                    rules.addObject().put("Name", rule.name()).put("Definition", rule.definition())
                            .put("State", rule.state()).put("Schema", InsightRuleDefinition.SCHEMA)
                            .put("ManagedRule", false).put("ApplyOnTransformedLogs", false);
                }
                if (page.nextToken() != null) {
                    result.put("NextToken", page.nextToken());
                }
            }
            case "DeleteInsightRules", "EnableInsightRules", "DisableInsightRules" -> {
                ArrayNode failures = result.putArray("Failures");
                for (CloudWatchInsightRulesService.Failure failure
                        : service.change(strings(request, "RuleNames"), operation, region)) {
                    failures.addObject().put("FailureResource", failure.resource())
                            .put("ExceptionType", failure.exceptionType()).put("FailureCode", failure.code())
                            .put("FailureDescription", failure.description());
                }
            }
            case "ListTagsForResource" -> {
                ArrayNode tags = result.putArray("Tags");
                service.listTags(text(request, "ResourceARN"), region).forEach((key, value) ->
                        tags.addObject().put("Key", key).put("Value", value));
            }
            case "TagResource" -> service.tag(text(request, "ResourceARN"), tags(request), region);
            case "UntagResource" -> service.untag(text(request, "ResourceARN"), strings(request, "TagKeys"), region);
            case "GetInsightRuleReport" -> {
                InsightRuleReport report = service.report(text(request, "RuleName"), timestamp(request, "StartTime"),
                        timestamp(request, "EndTime"), integer(request, "Period"),
                        integer(request, "MaxContributorCount"), text(request, "OrderBy"),
                        request.has("Metrics") ? strings(request, "Metrics") : null, region);
                report.labels().forEach(result.putArray("KeyLabels")::add);
                result.put("AggregationStatistic", "SampleCount").put("AggregateValue", report.aggregate())
                        .put("ApproximateUniqueCount", report.unique());
                ArrayNode contributors = result.putArray("Contributors");
                for (InsightRuleReport.Contributor contributor : report.contributors()) {
                    ObjectNode node = contributors.addObject().put("ApproximateAggregateValue", contributor.aggregate());
                    contributor.keys().forEach(node.putArray("Keys")::add);
                    ArrayNode points = node.putArray("Datapoints");
                    for (InsightRuleReport.Point point : contributor.points()) {
                        points.addObject().put("Timestamp", point.timestamp() / 1000.0)
                                .put("ApproximateValue", point.value());
                    }
                }
                if (!report.metrics().isEmpty()) {
                    ArrayNode points = result.putArray("MetricDatapoints");
                    for (InsightRuleReport.MetricPoint point : report.metrics()) {
                        ObjectNode node = points.addObject().put("Timestamp", point.timestamp() / 1000.0);
                        point.values().forEach(node::put);
                    }
                }
            }
            default -> throw unsupported("Unsupported insight rule operation.");
        }
        return result;
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(field + " must be a string.");
        }
        return value.textValue();
    }

    private static Integer integer(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(field + " must be an integer.");
        }
        return value.intValue();
    }

    private static Instant timestamp(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null) {
            return null;
        }
        try {
            Instant parsed = value.isNumber() ? Instant.ofEpochMilli(value.decimalValue()
                    .multiply(BigDecimal.valueOf(1000)).longValueExact())
                    : Instant.parse(text(request, field));
            parsed.toEpochMilli();
            return parsed;
        } catch (ArithmeticException | DateTimeException error) {
            throw invalid(field + " must be a valid timestamp.");
        }
    }

    private static Boolean bool(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isBoolean()) {
            throw invalid(field + " must be a boolean.");
        }
        return value.booleanValue();
    }

    private static List<String> strings(JsonNode request, String field) {
        JsonNode array = request.get(field);
        if (array == null) {
            return List.of();
        }
        if (!array.isArray()) {
            throw invalid(field + " must be an array.");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            if (!value.isTextual()) {
                throw invalid(field + " entries must be strings.");
            }
            values.add(value.textValue());
        }
        return values;
    }

    private static Map<String, String> tags(JsonNode request) {
        JsonNode array = request.get("Tags");
        Map<String, String> result = new LinkedHashMap<>();
        if (array == null) {
            return result;
        }
        if (!array.isArray()) {
            throw invalid("Tags must be an array.");
        }
        for (JsonNode tag : array) {
            if (!tag.isObject()) {
                throw invalid("Each tag must be an object.");
            }
            String key = text(tag, "Key");
            String value = text(tag, "Value");
            if (key == null || value == null || result.putIfAbsent(key, value) != null) {
                throw invalid("Tags require unique keys and string values.");
            }
        }
        return result;
    }

    private static List<String> queryList(MultivaluedMap<String, String> parameters, String field) {
        TreeMap<Integer, String> values = new TreeMap<>();
        String prefix = field + ".member.";
        parameters.keySet().stream().filter(key -> key.startsWith(prefix)).forEach(key ->
                values.put(index(key.substring(prefix.length())), single(parameters, key)));
        contiguous(values.keySet());
        return List.copyOf(values.values());
    }

    private static int index(String value) {
        try {
            int index = Integer.parseInt(value);
            if (index > 0 && Integer.toString(index).equals(value)) {
                return index;
            }
        } catch (NumberFormatException ignored) {
            // Report the same modeled parameter error for malformed and out-of-range indices.
        }
        throw invalid("Invalid list member index.");
    }

    private static void contiguous(Set<Integer> indices) {
        int expected = 1;
        for (int index : indices) {
            if (index != expected++) {
                throw invalid("List member indices must start at one and be consecutive.");
            }
        }
    }

    private static String single(MultivaluedMap<String, String> parameters, String field) {
        if (parameters.get(field).size() != 1) {
            throw invalid("Duplicate parameter " + field + ".");
        }
        return parameters.getFirst(field);
    }

    private static void xml(XmlBuilder xml, String name, JsonNode value) {
        if (value.isContainerNode()) {
            xml.start(name);
            if (value.isArray()) {
                value.forEach(member -> xml(xml, "member", member));
            } else {
                value.properties().forEach(entry -> xml(xml, entry.getKey(), entry.getValue()));
            }
            xml.end(name);
        } else if ("Timestamp".equals(name) && value.isNumber()) {
            xml.elem(name, Instant.ofEpochMilli(value.decimalValue()
                    .multiply(BigDecimal.valueOf(1000)).longValueExact()).toString());
        } else {
            xml.elem(name, value.asText());
        }
    }
}
