package io.floci.aws.services.athena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.athena.model.QueryExecution;
import io.floci.aws.services.athena.model.WorkGroupTag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Reads existing execution metadata without submitting, stopping or deleting queries. */
@ApplicationScoped
public class AthenaBatchQueryService {

    private static final Pattern IDENTIFIER = Pattern.compile("\\S+");
    private final AthenaService athenaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public AthenaBatchQueryService(AthenaService athenaService, RegionResolver regionResolver, ObjectMapper mapper) {
        this.athenaService = athenaService;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    public Map<String, Object> batchGet(String region, JsonNode request) {
        List<String> ids = identifiers(request);
        List<ObjectNode> found = new ArrayList<>();
        List<Map<String, String>> missing = new ArrayList<>();
        for (String id : ids) {
            QueryExecution execution = athenaService.findBatchQueryExecution(region, id);
            if (execution == null) {
                missing.add(Map.of("QueryExecutionId", id, "ErrorCode", "QUERY_EXECUTION_NOT_FOUND",
                        "ErrorMessage", "Query execution not found: " + id));
            } else {
                found.add(wireExecution(execution));
            }
        }
        return Map.of("QueryExecutions", found, "UnprocessedQueryExecutionIds", missing);
    }

    /** The existing Get response and batch entries share a complete snapshot with no storage-only fields. */
    public ObjectNode wireExecution(QueryExecution execution) {
        synchronized (execution) {
            ObjectNode snapshot = mapper.valueToTree(execution);
            snapshot.remove("_flociRegion");
            return snapshot;
        }
    }

    /** Keeps each workgroup and its own tag conditions together for IAM evaluation. */
    public List<AuthorizationTarget> authorizationTargets(String region, String account, JsonNode request) {
        List<String> ids = identifiers(request);
        Map<String, AuthorizationTarget> targets = new LinkedHashMap<>();
        for (String id : ids) {
            QueryExecution execution = athenaService.findBatchQueryExecutionForAuthorization(region, id);
            if (execution != null) {
                String group = Objects.requireNonNullElse(execution.getWorkGroup(), "primary");
                String arn = regionResolver.buildArn("athena", region, account, "workgroup/" + group);
                Map<String, List<String>> context = new LinkedHashMap<>();
                for (WorkGroupTag tag : athenaService.batchWorkGroupTags(region, group)) {
                    if (tag.getKey() != null && tag.getValue() != null) {
                        context.put("aws:ResourceTag/" + tag.getKey(), List.of(tag.getValue()));
                    }
                }
                targets.putIfAbsent(arn, new AuthorizationTarget(arn, Map.copyOf(context)));
            }
        }
        return List.copyOf(targets.values());
    }

    static List<String> identifiers(JsonNode request) {
        JsonNode values = request == null ? null : request.get("QueryExecutionIds");
        if (values == null || !values.isArray() || values.isEmpty() || values.size() > 50) {
            throw invalid("QueryExecutionIds must be an array containing 1 to 50 identifiers.");
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw invalid("Each QueryExecutionIds entry must be a string.");
            }
            String id = value.textValue();
            if (id.codePointCount(0, id.length()) > 128 || !IDENTIFIER.matcher(id).matches()) {
                throw invalid("Each query execution identifier must contain 1 to 128 non-whitespace characters.");
            }
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    public record AuthorizationTarget(String resource, Map<String, List<String>> context) {
    }
}
