package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Explicitly enabled local management protocol, separate from AWS Query operations. */
@Path("/_floci/iam/issued-sessions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class IssuedSessionController {
    private static final int MAX_BODY_BYTES = 16384;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final Set<String> VERIFY_FIELDS = Set.of(
            "correlationId", "accessKeyId", "sessionToken", "stringToSign", "signature");
    private static final Set<String> SELECTORS = Set.of("accessKeyId", "principalId", "principalArn");
    private final IssuedSessionVerifier verifier;
    private final boolean enabled;

    @Inject
    public IssuedSessionController(IssuedSessionVerifier verifier, EmulatorConfig config) {
        this(verifier, config.services().iam().enabled()
                && config.services().iam().issuedSessionVerificationEnabled());
    }

    IssuedSessionController(IssuedSessionVerifier verifier, boolean enabled) {
        this.verifier = verifier;
        this.enabled = enabled;
    }

    @POST
    @Path("/verify")
    public Response verify(InputStream body) {
        return handle(body, true);
    }

    @POST
    @Path("/lookup")
    public Response lookup(InputStream body) {
        return handle(body, false);
    }

    private Response handle(InputStream body, boolean verify) {
        if (!enabled) {
            return error(404, "IssuedSessionVerificationDisabled");
        }
        Map<String, String> request;
        try {
            request = parse(body, verify);
        } catch (IOException | IllegalArgumentException error) {
            return error(400, "InvalidIssuedSessionRequest");
        }
        try {
            return Response.ok(verify ? verifier.verify(request) : verifier.lookup(request))
                    .header("Cache-Control", "no-store").build();
        } catch (RuntimeException error) {
            return error(403, "IssuedSessionVerificationFailed");
        }
    }

    static Map<String, String> parse(InputStream body, boolean verify) throws IOException {
        if (body == null) {
            throw new IllegalArgumentException();
        }
        byte[] bytes = body.readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException();
        }
        JsonNode tree;
        try (JsonParser parser = JSON.createParser(bytes)) {
            tree = JSON.readTree(parser);
            if (tree == null || !tree.isObject() || parser.nextToken() != null) {
                throw new IllegalArgumentException();
            }
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, JsonNode> field : tree.properties()) {
            if (!field.getValue().isTextual()) {
                throw new IllegalArgumentException();
            }
            result.put(field.getKey(), field.getValue().textValue());
        }
        String correlation = result.get("correlationId");
        if (correlation == null || !correlation.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException();
        }
        if (verify) {
            if (!result.keySet().equals(VERIFY_FIELDS)
                    || !IssuedSessionVerifier.validSelector("accessKeyId", result.get("accessKeyId"))
                    || result.get("sessionToken").isEmpty() || result.get("sessionToken").length() > 4096
                    || result.get("stringToSign").isEmpty() || result.get("stringToSign").length() > 2048
                    || !result.get("signature").matches("[a-fA-F0-9]{64}")) {
                throw new IllegalArgumentException();
            }
        } else {
            if (result.size() != 2) {
                throw new IllegalArgumentException();
            }
            String selector = result.keySet().stream().filter(SELECTORS::contains).findFirst().orElseThrow(
                    IllegalArgumentException::new);
            if (!IssuedSessionVerifier.validSelector(selector, result.get(selector))) {
                throw new IllegalArgumentException();
            }
        }
        return Map.copyOf(result);
    }

    private static Response error(int status, String code) {
        return Response.status(status).entity(Map.of("code", code)).header("Cache-Control", "no-store").build();
    }
}
