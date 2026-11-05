package io.github.hectorvent.floci.services.s3;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3SignatureConditionsTest {

    @Test
    void suppliesTheDocumentedSigV4PolicyValue() {
        String algorithm = "AWS4-HMAC-SHA256";
        String credential = credential(algorithm);
        Map<String, List<String>> expected = Map.of("s3:signatureversion", List.of(algorithm));
        assertEquals(expected, S3SignatureConditions.request(
                algorithm + " Credential=" + credential + ", SignedHeaders=host, Signature=signature", null));
        assertEquals(expected, S3SignatureConditions.request(null, query(algorithm)));
    }

    @Test
    void asymmetricAuthenticationDoesNotClaimAnUnqualifiedPolicyValue() {
        String algorithm = "AWS4-ECDSA-P256-SHA256";
        String header = algorithm + " Credential=" + credential(algorithm)
                + ", SignedHeaders=host, Signature=signature";
        assertEquals(algorithm, S3RequestAuthorizationParser.parse(
                header, new MultivaluedHashMap<>()).signatureVersion());
        assertTrue(S3SignatureConditions.request(header, null).isEmpty());
        assertTrue(S3SignatureConditions.request(null, query(algorithm)).isEmpty());
    }

    @Test
    void headerAuthenticationKeepsPrecedenceOverQueryMetadata() {
        assertEquals(Map.of("s3:signatureversion", List.of("AWS4-HMAC-SHA256")),
                S3SignatureConditions.request("AWS4-HMAC-SHA256 Credential="
                        + credential("AWS4-HMAC-SHA256") + ", SignedHeaders=host, Signature=signature",
                        query("AWS4-ECDSA-P256-SHA256")));
        assertTrue(S3SignatureConditions.request("Basic unsupported", query("AWS4-HMAC-SHA256")).isEmpty());
    }

    @Test
    void unsignedAndMalformedRequestsCannotSupplyASignatureVersion() {
        assertTrue(S3SignatureConditions.request(null, null).isEmpty());
        assertTrue(S3SignatureConditions.request("AWS4-HMAC-SHA256", null).isEmpty());
        assertTrue(S3SignatureConditions.request(
                "AWS4-HMAC-SHA256 Credential=" + credential("AWS4-HMAC-SHA256"), null).isEmpty());
        for (String name : List.of("X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Date",
                "X-Amz-Expires", "X-Amz-SignedHeaders", "X-Amz-Signature")) {
            MultivaluedMap<String, String> query = query("AWS4-HMAC-SHA256");
            query.remove(name);
            assertTrue(S3SignatureConditions.request(null, query).isEmpty(), name);
        }
        MultivaluedMap<String, String> query = query("AWS4-HMAC-SHA256");
        query.putSingle("X-Amz-Algorithm", "unsupported");
        assertTrue(S3SignatureConditions.request(null, query).isEmpty());
    }

    @Test
    void credentialsAloneDoNotImplyASignatureVersion() {
        assertTrue(new S3Service.RequestAuthorization(true, "key", "token").signatureConditions().isEmpty());
        assertTrue(new S3Service.RequestAuthorization(
                false, null, null, false, "AWS4-HMAC-SHA256").signatureConditions().isEmpty());
    }

    @Test
    void postRequiresTheFormsOwnCompleteSigningMetadata() {
        Map<String, String> fields = Map.of(
                "x-amz-algorithm", "AWS4-HMAC-SHA256",
                "x-amz-credential", credential("AWS4-HMAC-SHA256"),
                "x-amz-date", "20261105T000000Z",
                "x-amz-signature", "signature",
                "policy", "policy");
        assertEquals(Map.of("s3:signatureversion", List.of("AWS4-HMAC-SHA256")),
                S3SignatureConditions.post(fields));
        for (String name : fields.keySet()) {
            Map<String, String> incomplete = new HashMap<>(fields);
            incomplete.remove(name);
            assertTrue(S3SignatureConditions.post(incomplete).isEmpty(), name);
        }
        Map<String, String> mismatched = new HashMap<>(fields);
        mismatched.put("x-amz-date", "20261106T000000Z");
        assertTrue(S3SignatureConditions.post(mismatched).isEmpty());
        mismatched.put("x-amz-date", fields.get("x-amz-date"));
        mismatched.put("x-amz-algorithm", "AWS4-ECDSA-P256-SHA256");
        assertTrue(S3SignatureConditions.post(mismatched).isEmpty());
    }

    private static String credential(String algorithm) {
        return "key/20261105/" + ("AWS4-HMAC-SHA256".equals(algorithm) ? "us-east-1/" : "") + "s3/aws4_request";
    }

    private static MultivaluedMap<String, String> query(String algorithm) {
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.putSingle("X-Amz-Algorithm", algorithm);
        query.putSingle("X-Amz-Credential", credential(algorithm));
        query.putSingle("X-Amz-Date", "20261105T000000Z");
        query.putSingle("X-Amz-Expires", "300");
        query.putSingle("X-Amz-SignedHeaders", "host");
        query.putSingle("X-Amz-Signature", "signature");
        query.putSingle("X-Amz-Region-Set", "*");
        return query;
    }
}
