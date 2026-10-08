package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iam.IssuedSessionVerifier.Identity;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IssuedSessionVerifierTest {
    private static final String ACCOUNT = "123456789012";
    private static final String KEY = "ASIAABCDEFGHIJKLMNOP";
    private static final String SECOND_KEY = "ASIAQRSTUVWXYZABCDEF";
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private InMemoryStorage<String, SessionCredential> sessions;
    private InMemoryStorage<String, IamRole> roles;
    private IamService iam;
    private IamRole role;
    private IssuedSessionVerifier verifier;

    @BeforeEach
    void prepare() {
        sessions = new InMemoryStorage<>();
        roles = new InMemoryStorage<>();
        iam = new IamService(new InMemoryStorage<>(), new InMemoryStorage<>(), roles,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), sessions,
                new RegionResolver("us-gov-west-1", ACCOUNT), false);
        role = new IamRole();
        role.setRoleName("worker");
        role.setRoleId("AROAABCDEFGHIJKLMNOP");
        role.setArn(AwsArnUtils.Arn.global("aws-us-gov", "iam", ACCOUNT, "role/path/worker").toString());
        roles.put("worker", role);
        issue(KEY, null, false, ACCOUNT, NOW.plusSeconds(3600));
        verifier = new IssuedSessionVerifier(iam, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void issue(String key, String policy, boolean managed, String origin, Instant expiry) {
        iam.registerAssumeRoleSession(key, "dummy-secret", "dummy-token", role.getArn(), expiry,
                policy, origin, "session", role.getRoleId() + ":session", managed);
    }

    private Map<String, String> proof() throws Exception {
        return proof("20261008T120000Z", "20261008/us-gov-west-1/ExampleService/aws4_request");
    }

    private Map<String, String> proof(String time, String scope) throws Exception {
        String text = "AWS4-HMAC-SHA256\n" + time + "\n" + scope + "\n" + "a".repeat(64);
        String[] parts = scope.split("/");
        String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(
                SigV4RequestValidator.deriveSigningKey("dummy-secret", parts[0], parts[1], parts[2]), text));
        return new HashMap<>(Map.of("correlationId", "test-1", "accessKeyId", KEY,
                "sessionToken", "dummy-token", "stringToSign", text, "signature", signature));
    }

    @Test
    void verifiesOriginalProofAndReturnsOnlySafeCurrentIdentity() throws Exception {
        Identity identity = verifier.verify(proof());
        assertEquals("Verify", identity.operation());
        assertEquals("ASSUMED_ROLE", identity.kind());
        assertEquals(role.getArn(), identity.roleArn());
        assertEquals(role.getRoleId(), identity.roleId());
        assertEquals(ACCOUNT, identity.originAccountId());
        assertTrue(identity.principalArn().endsWith(":assumed-role/worker/session"));
        assertFalse(identity.sessionPolicyPresent());
        String response = json.writeValueAsString(identity);
        for (String privateValue : List.of(KEY, "dummy-token", "dummy-secret", proof().get("signature"))) {
            assertFalse(response.contains(privateValue));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"accessKeyId", "sessionToken", "stringToSign", "signature"})
    void rejectsEachTamperedProofComponent(String field) throws Exception {
        Map<String, String> request = proof();
        request.put(field, field.equals("signature") ? "0".repeat(64) : request.get(field) + "x");
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"20261008T114459Z", "20261008T120501Z", "20260230T120000Z"})
    void refusesStaleFutureAndInvalidTimestamps(String timestamp) throws Exception {
        Map<String, String> request = proof(timestamp, "20261008/us-gov-west-1/sts/aws4_request");
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"20261007/us-gov-west-1/sts/aws4_request", "20261008/us-gov-west-1/sts/wrong",
            "20261008/us-gov-west-1/sts/aws4_request/extra"})
    void refusesNonstandardOrMismatchedScope(String scope) throws Exception {
        Map<String, String> request = proof("20261008T120000Z", scope);
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unmarked", "lambda", "ec2", "ecs", "presigned", "expired", "nonexpiring", "role-replaced",
            "role-deleted", "origin-missing", "secret-missing", "token-missing"})
    void refusesUnsupportedOrStaleIssuances(String kind) throws Exception {
        SessionCredential session = sessions.get(KEY).orElseThrow();
        switch (kind) {
            case "unmarked" -> session.setAssumeRoleIssued(false);
            case "lambda" -> session.setLambdaExecutionRole(true);
            case "ec2" -> session.setEc2InstanceId("i-example");
            case "ecs" -> session.setEcsTaskArn("task");
            case "presigned" -> session.setPresignedAction("s3:GetObject");
            case "expired" -> session.setExpiration(NOW);
            case "nonexpiring" -> session.setExpiration(null);
            case "role-replaced" -> role.setRoleId("AROAREPLACEMENT");
            case "role-deleted" -> roles.delete("worker");
            case "origin-missing" -> session.setOriginAccountId(null);
            case "secret-missing" -> session.setSecretAccessKey(null);
            case "token-missing" -> session.setSessionToken(null);
            default -> fail();
        }
        Map<String, String> request = proof();
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(request));
        assertThrows(IllegalArgumentException.class, () -> verifier.lookup(Map.of("correlationId", "test", "accessKeyId", KEY)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "replace"})
    void rechecksActualRoleAfterSignatureVerification(String mutation) throws Exception {
        IamService changing = spy(iam);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(invocation -> {
            if (reads.incrementAndGet() == 2) {
                if (mutation.equals("delete")) {
                    roles.delete("worker");
                } else {
                    role.setRoleId("AROAREPLACEMENT");
                }
            }
            return iam.issuedSessionCandidates(KEY);
        }).when(changing).issuedSessionCandidates(KEY);
        Map<String, String> request = proof();
        assertThrows(IllegalArgumentException.class,
                () -> new IssuedSessionVerifier(changing, Clock.fixed(NOW, ZoneOffset.UTC)).verify(request));
        assertEquals(2, reads.get(), "The initial identity read must succeed before the role changes");
    }

    @Test
    void rejectsRegistryRevocationBetweenVerificationReads() throws Exception {
        IamService changing = spy(iam);
        doReturn(iam.issuedSessionCandidates(KEY), List.of()).when(changing).issuedSessionCandidates(KEY);
        Map<String, String> request = proof();
        assertThrows(IllegalArgumentException.class,
                () -> new IssuedSessionVerifier(changing, Clock.fixed(NOW, ZoneOffset.UTC)).verify(request));
    }

    @Test
    void lookupSupportsEachExactSelectorWithoutTokenProof() {
        Identity first = verifier.lookup(Map.of("correlationId", "cold", "accessKeyId", KEY));
        assertEquals("Lookup", first.operation());
        for (String selector : List.of("principalArn", "principalId")) {
            String value = selector.equals("principalArn") ? first.principalArn() : first.principalId();
            assertEquals(first, verifier.lookup(Map.of("correlationId", "cold", selector, value)));
        }
    }

    @Test
    void lookupConsistentReissuancesReturnsEarliestCurrentExpiry() {
        issue(SECOND_KEY, null, false, ACCOUNT, NOW.plusSeconds(1800));
        Identity identity = verifier.lookup(Map.of("correlationId", "cold", "principalId", role.getRoleId() + ":session"));
        assertEquals(NOW.plusSeconds(1800).toString(), identity.expiresAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"origin", "policy"})
    void lookupRefusesAmbiguousOriginOrRestrictions(String field) {
        issue(SECOND_KEY, field.equals("policy") ? "{}" : null, false,
                field.equals("origin") ? "222222222222" : ACCOUNT, NOW.plusSeconds(1800));
        assertThrows(IllegalArgumentException.class, () -> verifier.lookup(Map.of(
                "correlationId", "cold", "principalId", role.getRoleId() + ":session")));
    }

    @Test
    void policyPresenceIncludesBothInlineAndManagedSessionPolicies() throws Exception {
        issue(KEY, "", false, ACCOUNT, NOW.plusSeconds(3600));
        assertTrue(verifier.verify(proof()).sessionPolicyPresent());
        issue(KEY, null, true, ACCOUNT, NOW.plusSeconds(3600));
        assertTrue(verifier.lookup(Map.of("correlationId", "cold", "accessKeyId", KEY)).sessionPolicyPresent());
    }

    @Test
    void normalIssuanceProvenanceIsNotPersistedAndCannotBeRestored() throws Exception {
        String serialized = json.writeValueAsString(sessions.get(KEY).orElseThrow());
        assertFalse(serialized.contains("assumeRoleIssued"));
        assertFalse(serialized.contains("managedSessionPolicyPresent"));
        SessionCredential restored = json.readValue(serialized, SessionCredential.class);
        assertFalse(restored.isAssumeRoleIssued());
        sessions.put(KEY, restored);
        assertThrows(IllegalArgumentException.class, () -> verifier.lookup(Map.of("correlationId", "cold", "accessKeyId", KEY)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"correlationId\":1}",
            "{\"correlationId\":\"x\",\"accountId\":\"123456789012\"}",
            "{\"correlationId\":\"x\",\"accessKeyId\":\"ASIAABCDEFGHIJKLMNOP\",\"principalId\":\"AROAX:xx\"}",
            "{\"correlationId\":\"x\",\"correlationId\":\"y\",\"accessKeyId\":\"ASIAABCDEFGHIJKLMNOP\"}",
            "{\"correlationId\":\"x\",\"accessKeyId\":\"ASIAABCDEFGHIJKLMNOP\"} {}"})
    void controllerRefusesMalformedLookupWithoutEcho(String body) {
        Response response = new IssuedSessionController(verifier, true).lookup(stream(body));
        assertEquals(400, response.getStatus());
        assertEquals(Map.of("code", "InvalidIssuedSessionRequest"), response.getEntity());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
    }

    @Test
    void controllerDefaultsOffAndBoundsBodies() {
        assertEquals(404, new IssuedSessionController(verifier, false).lookup(stream("{}")).getStatus());
        assertEquals(404, new IssuedSessionController(verifier, false).verify(stream("{}")).getStatus());
        assertEquals(400, new IssuedSessionController(verifier, true).verify(stream(" ".repeat(16385))).getStatus());
    }

    @Test
    void controllerReturnsOnlyGenericFailureForWrongToken() throws Exception {
        Map<String, String> request = proof();
        request.put("sessionToken", "private-wrong-token");
        Response response = new IssuedSessionController(verifier, true).verify(stream(json.writeValueAsString(request)));
        assertEquals(403, response.getStatus());
        assertEquals(Map.of("code", "IssuedSessionVerificationFailed"), response.getEntity());
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
