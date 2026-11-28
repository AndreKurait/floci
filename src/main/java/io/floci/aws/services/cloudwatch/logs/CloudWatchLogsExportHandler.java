package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.cloudwatch.logs.model.LogExportTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static io.floci.aws.services.cloudwatch.logs.CloudWatchLogsExportService.invalid;

@ApplicationScoped
public class CloudWatchLogsExportHandler {
    private static final Pattern GROUP = Pattern.compile("[._/#A-Za-z0-9-]+");
    private final CloudWatchLogsExportService exports;
    private final ObjectMapper mapper;

    @Inject
    public CloudWatchLogsExportHandler(CloudWatchLogsExportService exports, ObjectMapper mapper) {
        this.exports = exports;
        this.mapper = mapper;
    }

    Response handle(String action, JsonNode request, String region, String authorization) {
        if (request == null || !request.isObject()) {
            throw invalid("The request must be a JSON object.");
        }
        ObjectNode response = mapper.createObjectNode();
        switch (action) {
            case "CreateExportTask" -> {
                String group = text(request, "logGroupName", true, 1, 512);
                if (!GROUP.matcher(group).matches()) {
                    throw invalid("Invalid log group name.");
                }
                String stream = text(request, "logStreamNamePrefix", false, 1, 512);
                if (stream != null && (stream.indexOf(':') >= 0 || stream.indexOf('*') >= 0)) {
                    throw invalid("Invalid log stream prefix.");
                }
                long from = number(request, "from");
                long to = number(request, "to");
                if (from < 0 || to < from) {
                    throw invalid("Export timestamps must be nonnegative and from must not exceed to.");
                }
                String destination = text(request, "destination", true, 1, 512);
                String prefix = text(request, "destinationPrefix", false, 0, 1024);
                if (prefix == null) {
                    prefix = "exportedlogs";
                }
                if (prefix.getBytes(StandardCharsets.UTF_8).length > 1024) {
                    throw invalid("The destination prefix exceeds 1024 UTF-8 bytes.");
                }
                String name = text(request, "taskName", false, 1, 512);
                response.put("taskId", exports.create(group, stream, from, to, destination,
                        prefix, name, region, authorization));
            }
            case "DescribeExportTasks" -> {
                String id = text(request, "taskId", false, 1, 512);
                String status = text(request, "statusCode", false, 1, 64);
                String token = text(request, "nextToken", false, 1, 1024);
                Long limit = request.has("limit") ? number(request, "limit") : null;
                if (limit != null && (limit < 1 || limit > 50)) {
                    throw invalid("The limit must be between 1 and 50.");
                }
                CloudWatchLogsExportService.Page page = exports.describe(id, status,
                        limit == null ? null : limit.intValue(), token, region);
                ArrayNode tasks = response.putArray("exportTasks");
                for (LogExportTask task : page.tasks()) {
                    tasks.add(project(task));
                }
                if (page.nextToken() != null) {
                    response.put("nextToken", page.nextToken());
                }
            }
            case "CancelExportTask" -> exports.cancel(text(request, "taskId", true, 1, 512), region);
            default -> throw invalid("Unsupported export operation.");
        }
        return Response.ok(response).build();
    }

    private ObjectNode project(LogExportTask task) {
        ObjectNode value = mapper.createObjectNode();
        value.put("taskId", task.taskId());
        if (task.taskName() != null) {
            value.put("taskName", task.taskName());
        }
        value.put("logGroupName", task.logGroupName());
        value.put("from", task.from());
        value.put("to", task.to());
        value.put("destination", task.destination());
        value.put("destinationPrefix", task.destinationPrefix());
        value.putObject("status").put("code", task.status()).put("message", task.message());
        ObjectNode execution = value.putObject("executionInfo").put("creationTime", task.creationTime());
        if (task.completionTime() != null) {
            execution.put("completionTime", task.completionTime());
        }
        return value;
    }

    private static String text(JsonNode request, String key, boolean required, int minimum, int maximum) {
        JsonNode value = request.get(key);
        if (value == null && !required) {
            return null;
        }
        if (value == null || !value.isTextual() || value.textValue().length() < minimum
                || value.textValue().length() > maximum) {
            throw invalid("Invalid " + key + ".");
        }
        return value.textValue();
    }

    private static long number(JsonNode request, String key) {
        JsonNode value = request.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid("Invalid " + key + ": an integer timestamp or limit is required.");
        }
        return value.longValue();
    }
}
