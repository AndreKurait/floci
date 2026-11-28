package io.floci.aws.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jboss.resteasy.reactive.common.util.UnmodifiableMultivaluedMap;
import org.jboss.resteasy.reactive.server.jaxrs.HttpHeadersImpl;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Simulated method inputs, independent of the authenticated management request. */
record TestInvokeMethodInput(String body, HttpHeaders headers, String path,
                             MultivaluedMap<String, String> query,
                             Map<String, String> stageVariables) {

    static TestInvokeMethodInput read(JsonNode input, String resourcePath) {
        if (input != null && !input.isNull() && !input.isObject()) {
            throw badRequest("The test invocation request must be a JSON object");
        }
        if (text(input, "clientCertificateId") != null) {
            throw badRequest("Client certificates are not supported for MOCK test invocation");
        }
        String pathAndQuery = text(input, "pathWithQueryString");
        String path = resourcePath;
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        if (pathAndQuery != null && !pathAndQuery.isEmpty()) {
            try {
                URI uri = URI.create(pathAndQuery);
                if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null
                        || uri.getRawPath() == null || !uri.getRawPath().startsWith("/")) {
                    throw badRequest("pathWithQueryString must be an absolute path with an optional query string");
                }
                path = uri.getPath();
                if (uri.getRawQuery() != null) {
                    for (String pair : uri.getRawQuery().split("&", -1)) {
                        if (!pair.isEmpty()) {
                            int separator = pair.indexOf('=');
                            String key = separator < 0 ? pair : pair.substring(0, separator);
                            String value = separator < 0 ? "" : pair.substring(separator + 1);
                            query.add(URLDecoder.decode(key, StandardCharsets.UTF_8),
                                    URLDecoder.decode(value, StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (IllegalArgumentException invalidUri) {
                throw badRequest("Invalid pathWithQueryString");
            }
        }

        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        stringMap(input, "headers").forEach((name, value) -> headers.put(name, List.of(value)));
        JsonNode multiValueHeaders = field(input, "multiValueHeaders");
        if (multiValueHeaders != null) {
            requireObject(multiValueHeaders, "multiValueHeaders");
            multiValueHeaders.properties().forEach(entry -> {
                if (!entry.getValue().isArray()) {
                    throw badRequest("multiValueHeaders values must be arrays of strings");
                }
                if (headers.containsKey(entry.getKey())) {
                    throw badRequest("A test header must appear in only one of headers and multiValueHeaders");
                }
                List<String> values = new ArrayList<>();
                for (JsonNode value : entry.getValue()) {
                    if (!value.isTextual()) {
                        throw badRequest("multiValueHeaders values must be arrays of strings");
                    }
                    values.add(value.textValue());
                }
                headers.put(entry.getKey(), List.copyOf(values));
            });
        }
        List<Map.Entry<String, String>> headerEntries = new ArrayList<>();
        headers.forEach((name, values) -> values.forEach(value -> headerEntries.add(Map.entry(name, value))));
        return new TestInvokeMethodInput(text(input, "body"), new HttpHeadersImpl(headerEntries), path,
                new UnmodifiableMultivaluedMap<>(query), Map.copyOf(stringMap(input, "stageVariables")));
    }

    private static Map<String, String> stringMap(JsonNode input, String name) {
        JsonNode value = field(input, name);
        Map<String, String> result = new LinkedHashMap<>();
        if (value != null) {
            requireObject(value, name);
            value.properties().forEach(entry -> {
                if (!entry.getValue().isTextual()) {
                    throw badRequest(name + " values must be strings");
                }
                result.put(entry.getKey(), entry.getValue().textValue());
            });
        }
        return result;
    }

    private static void requireObject(JsonNode value, String name) {
        if (!value.isObject()) {
            throw badRequest(name + " must be an object");
        }
    }

    private static String text(JsonNode input, String name) {
        JsonNode value = field(input, name);
        if (value == null) {
            return null;
        }
        if (!value.isTextual()) {
            throw badRequest(name + " must be a string");
        }
        return value.textValue();
    }

    private static JsonNode field(JsonNode input, String name) {
        JsonNode value = input != null ? input.get(name) : null;
        return value == null || value.isNull() ? null : value;
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }
}
