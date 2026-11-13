package io.github.hectorvent.floci.services.athena;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.athena.model.PreparedStatement;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AthenaPreparedStatementHandler {

    private final AthenaPreparedStatementService service;

    AthenaPreparedStatementHandler(AthenaPreparedStatementService service) {
        this.service = service;
    }

    Response handle(String action, JsonNode request, String region) {
        if (request == null || !request.isObject()) {
            throw invalid("Request must be a JSON object.");
        }
        String workGroup = text(request, "WorkGroup", true);
        return switch (action) {
            case "CreatePreparedStatement" -> {
                service.create(region, workGroup, text(request, "StatementName", true),
                        text(request, "QueryStatement", true), text(request, "Description", false));
                yield Response.ok().build();
            }
            case "GetPreparedStatement" -> Response.ok(Map.of("PreparedStatement",
                    wire(service.get(region, workGroup, text(request, "StatementName", true))))).build();
            case "UpdatePreparedStatement" -> {
                service.update(region, workGroup, text(request, "StatementName", true),
                        text(request, "QueryStatement", true), text(request, "Description", false));
                yield Response.ok().build();
            }
            case "DeletePreparedStatement" -> {
                service.delete(region, workGroup, text(request, "StatementName", true));
                yield Response.ok().build();
            }
            case "BatchGetPreparedStatement" -> {
                JsonNode values = request.get("PreparedStatementNames");
                if (values == null || !values.isArray()) {
                    throw invalid("PreparedStatementNames must be an array of strings.");
                }
                List<String> names = new ArrayList<>();
                for (JsonNode value : values) {
                    if (!value.isTextual()) {
                        throw invalid("PreparedStatementNames must contain only strings.");
                    }
                    names.add(value.textValue());
                }
                AthenaPreparedStatementService.BatchResult result = service.batchGet(region, workGroup, names);
                yield Response.ok(Map.of("PreparedStatements", result.statements().stream()
                                .map(AthenaPreparedStatementHandler::wire).toList(),
                        "UnprocessedPreparedStatementNames", result.missing().stream().map(name -> Map.of(
                                "StatementName", name, "ErrorCode", "STATEMENT_NOT_FOUND",
                                "ErrorMessage", "Prepared statement " + name + " was not found.")).toList())).build();
            }
            default -> throw invalid("Unsupported prepared-statement operation.");
        };
    }

    private static Map<String, Object> wire(PreparedStatement statement) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("StatementName", statement.statementName());
        result.put("WorkGroupName", statement.workGroupName());
        result.put("QueryStatement", statement.queryStatement());
        if (statement.description() != null) {
            result.put("Description", statement.description());
        }
        result.put("LastModifiedTime", BigDecimal.valueOf(statement.lastModifiedTime().getEpochSecond())
                .add(BigDecimal.valueOf(statement.lastModifiedTime().getNano(), 9)));
        return result;
    }

    private static String text(JsonNode request, String name, boolean required) {
        JsonNode value = request.get(name);
        if (value == null || value.isNull()) {
            if (required) {
                throw invalid(name + " is required.");
            }
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(name + " must be a string.");
        }
        return value.textValue();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }
}
