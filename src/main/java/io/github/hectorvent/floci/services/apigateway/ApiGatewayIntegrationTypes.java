package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Ascii;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Integration types generated from Botocore's public API Gateway service model. */
final class ApiGatewayIntegrationTypes {

    private static final String RESOURCE_NAME = "aws/apigateway-integration-types.json";
    private static final Map<String, String> TYPES = load();

    private ApiGatewayIntegrationTypes() {
    }

    static String canonical(String value) {
        return TYPES.get(Ascii.toLowerCase(value));
    }

    private static Map<String, String> load() {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (input == null) {
                throw new IllegalStateException("Missing API Gateway integration types: " + RESOURCE_NAME);
            }
            JsonNode values = new ObjectMapper().readTree(input).path("integrationTypes");
            if (!values.isArray() || values.isEmpty()) {
                throw new IllegalStateException(RESOURCE_NAME + " carries no integrationTypes");
            }
            Map<String, String> types = new HashMap<>();
            for (JsonNode value : values) {
                if (!value.isTextual() || value.asText().isBlank()) {
                    throw new IllegalStateException(RESOURCE_NAME + " carries an invalid integration type");
                }
                types.put(Ascii.toLowerCase(value.asText()), value.asText());
            }
            return Map.copyOf(types);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read " + RESOURCE_NAME, error);
        }
    }
}
