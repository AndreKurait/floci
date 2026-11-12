package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.cloudwatch.logs.model.LogTransformer;
import jakarta.ws.rs.core.Response;

final class CloudWatchLogsTransformerHandler {
    private final CloudWatchLogsService service;
    private final ObjectMapper mapper;

    CloudWatchLogsTransformerHandler(CloudWatchLogsService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "PutTransformer" -> {
                service.putTransformer(identifier(request), request.get("transformerConfig"), region);
                yield Response.ok(mapper.createObjectNode()).build();
            }
            case "GetTransformer" -> {
                String identifier = identifier(request);
                LogTransformer transformer = service.getTransformer(identifier, region);
                ObjectNode response = mapper.createObjectNode();
                ArrayNode configuration = response.putArray("transformerConfig");
                if (transformer != null) {
                    response.put("logGroupIdentifier", service.transformerGroupArn(identifier, region));
                    response.put("creationTime", transformer.creationTime());
                    response.put("lastModifiedTime", transformer.lastModifiedTime());
                    ObjectNode parse = configuration.addObject().putObject("parseJSON");
                    if (transformer.source() != null) {
                        parse.put("source", transformer.source());
                    }
                    if (transformer.destination() != null) {
                        parse.put("destination", transformer.destination());
                    }
                }
                yield Response.ok(response).build();
            }
            case "DeleteTransformer" -> {
                service.deleteTransformer(identifier(request), region);
                yield Response.ok(mapper.createObjectNode()).build();
            }
            case "TestTransformer" -> preview(request);
            default -> throw new IllegalArgumentException("Unknown transformer action: " + action);
        };
    }

    private Response preview(JsonNode request) {
        LogTransformer transformer = ParseJsonTransformer.definition(request.get("transformerConfig"));
        JsonNode messages = request.get("logEventMessages");
        if (messages == null || !messages.isArray() || messages.isEmpty() || messages.size() > 50) {
            throw ParseJsonTransformer.invalid("logEventMessages must contain between 1 and 50 messages.");
        }
        ObjectNode response = mapper.createObjectNode();
        ArrayNode transformed = response.putArray("transformedLogs");
        for (JsonNode message : messages) {
            if (!message.isTextual() || message.textValue().isEmpty()) {
                throw ParseJsonTransformer.invalid("Each log event message must be a nonempty string.");
            }
            ParseJsonTransformer.Outcome result = ParseJsonTransformer.transform(message.textValue(), transformer);
            if (result.error() != null) {
                throw ParseJsonTransformer.invalid(result.error());
            }
            ObjectNode event = transformed.addObject();
            event.put("eventMessage", message.textValue());
            event.put("transformedEventMessage", result.message());
        }
        return Response.ok(response).build();
    }

    private static String identifier(JsonNode request) {
        JsonNode value = request.get("logGroupIdentifier");
        if (value == null || !value.isTextual() || value.textValue().isEmpty()
                || value.textValue().length() > 2048) {
            throw ParseJsonTransformer.invalid("logGroupIdentifier must be a nonempty name or ARN up to 2048 characters.");
        }
        return value.textValue();
    }
}
