package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.List;
import java.util.Map;

/** S3 policy facts from signing metadata, independent of whether signature verification is enabled. */
public final class S3SignatureConditions {

    private static final String SIGV4_ALGORITHM = "AWS4-HMAC-SHA256";

    private S3SignatureConditions() {
    }

    public static Map<String, List<String>> request(
            String authorization, MultivaluedMap<String, String> queryParameters) {
        try {
            return S3RequestAuthorizationParser.parse(authorization,
                    queryParameters == null ? new MultivaluedHashMap<>() : queryParameters).signatureConditions();
        } catch (AwsException expected) {
            // Existing authentication filters own malformed-request errors. No version can be claimed here.
            return Map.of();
        }
    }

    /** Browser POST authentication lives in its form fields, never in the outer HTTP headers. */
    public static Map<String, List<String>> post(Map<String, String> fields) {
        for (String name : List.of("x-amz-algorithm", "x-amz-credential", "x-amz-date",
                "x-amz-signature", "policy")) {
            if (fields.get(name) == null || fields.get(name).isBlank()) {
                return Map.of();
            }
        }
        String credential = fields.get("x-amz-credential");
        if (!SIGV4_ALGORITHM.equals(fields.get("x-amz-algorithm"))
                || !S3PostPolicySigner.isValidS3CredentialScope(credential)
                || !S3PostPolicySigner.isConsistentAmzDate(fields.get("x-amz-date"), credential)) {
            return Map.of();
        }
        return forAlgorithm(SIGV4_ALGORITHM);
    }

    static Map<String, List<String>> forAlgorithm(String algorithm) {
        if (!SIGV4_ALGORITHM.equals(algorithm)) {
            return Map.of();
        }
        return Map.of("s3:signatureversion", List.of(algorithm));
    }
}
