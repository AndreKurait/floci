package io.floci.aws.services.apigateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

/** Process-local cursor authentication; caller and list arguments remain fixed between pages. */
final class ApiGatewayDocumentationCursor {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final byte[] secret = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);

    String encode(String account, String region, String api, Integer limit, String after) {
        ObjectNode node = JSON.createObjectNode().put("account", account).put("region", region).put("api", api)
                .put("limit", limit).put("after", after);
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(node.toString().getBytes(StandardCharsets.UTF_8));
        return payload + "." + signature(payload);
    }

    String decode(String token, String account, String region, String api, Integer limit) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(signature(parts[0]).getBytes(StandardCharsets.US_ASCII),
                    parts[1].getBytes(StandardCharsets.US_ASCII))) {
                throw invalid();
            }
            JsonNode node = JSON.readTree(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            if (!account.equals(node.path("account").asText()) || !region.equals(node.path("region").asText())
                    || !api.equals(node.path("api").asText()) || !JSON.valueToTree(limit).equals(node.path("limit"))
                    || !node.path("after").isTextual()) {
                throw invalid();
            }
            return node.path("after").textValue();
        } catch (IllegalArgumentException | JsonProcessingException failure) {
            throw invalid();
        }
    }

    private String signature(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot authenticate documentation cursor", failure);
        }
    }

    private static AwsException invalid() {
        return new AwsException("BadRequestException", "Invalid documentation version position", 400);
    }
}
