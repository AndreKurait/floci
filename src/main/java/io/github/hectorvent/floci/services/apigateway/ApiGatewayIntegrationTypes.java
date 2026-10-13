package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Ascii;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/** Integration types generated from Botocore's public API Gateway service model. */
final class ApiGatewayIntegrationTypes {

    private static final String RESOURCE_NAME = "aws/apigateway-integration-types.json";
    private static final Set<String> TYPES = load();

    private ApiGatewayIntegrationTypes() {
    }

    static String canonical(String value) {
        String canonical = Ascii.toUpperCase(value);
        return TYPES.contains(canonical) ? canonical : null;
    }

    private static Set<String> load() {
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (input == null) {
                throw new IllegalStateException("Missing API Gateway integration types: " + RESOURCE_NAME);
            }
            JsonNode values = new ObjectMapper().readTree(input).path("integrationTypes");
            if (!values.isArray() || values.isEmpty()) {
                throw new IllegalStateException(RESOURCE_NAME + " carries no integrationTypes");
            }
            Set<String> types = new HashSet<>();
            for (JsonNode value : values) {
                if (!value.isTextual() || value.asText().isBlank()) {
                    throw new IllegalStateException(RESOURCE_NAME + " carries an invalid integration type");
                }
                types.add(value.asText());
            }
            return Set.copyOf(types);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read " + RESOURCE_NAME, error);
        }
    }
}
