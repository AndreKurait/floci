package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.ecs.container.EcsTaskRoleCredentials;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.response.Response;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The enabled endpoint over HTTP, through Floci's real filter chain. */
@QuarkusTest
@TestProfile(DerivedSigningKeyIntegrationTest.Enabled.class)
class DerivedSigningKeyIntegrationTest {

    static final String CALLER = DerivedSigningKeyCallerAuthenticatorTest.CALLER;
    static final String SECRET = DerivedSigningKeyCallerAuthenticatorTest.SECRET;
    static final String SERVICE = "example-svc";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd").withZone(ZoneOffset.UTC);

    /** Enables the endpoint with an owner-only secret file, plus the identity lookup and a wildcard CORS origin. */
    public static class Enabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return enabledOverrides();
        }
    }

    static Map<String, String> enabledOverrides() {
        try {
            Path file = Files.createTempFile("derived-key-caller", ".secret",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(file, SECRET + "\n");
            file.toFile().deleteOnExit();
            Map<String, String> overrides = new HashMap<>();
            overrides.put("floci.services.iam.derived-signing-keys.enabled", "true");
            overrides.put("floci.services.iam.derived-signing-keys.caller-access-key-id", CALLER);
            overrides.put("floci.services.iam.derived-signing-keys.caller-secret-access-key-file", file.toString());
            overrides.put("floci.services.iam.derived-signing-keys.allowed-regions", "us-east-1");
            overrides.put("floci.services.iam.derived-signing-keys.allowed-services", SERVICE);
            overrides.put("floci.services.iam.derived-signing-keys.allowed-levels", "signing,service");
            overrides.put("floci.services.iam.issued-session-verification-enabled", "true");
            overrides.put("floci.security.extra-cors-allowed-origins", "*");
            return overrides;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @Inject
    IamService iam;
    @Inject
    EcsTaskRoleCredentials ecsTaskRoleCredentials;

    private final List<String> roles = new ArrayList<>();
    private final List<String> sessions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String key : sessions) {
            iam.unregisterSession("000000000000", key);
        }
        for (String role : roles) {
            query("iam", Map.of("Action", "DeleteRole", "RoleName", role));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    static String today() {
        return DATE.format(Instant.now());
    }

    static Map<String, String> request(String key, String token, String level) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("correlationId", "it-" + UUID.randomUUID());
        body.put("accessKeyId", key);
        body.put("sessionToken", token);
        body.put("date", today());
        body.put("region", "us-east-1");
        body.put("service", SERVICE);
        body.put("level", level);
        return body;
    }

    static Response post(byte[] body, Map<String, String> signedHeaders) {
        RequestSpecification spec = given().config(RestAssuredConfig.config().encoderConfig(
                        EncoderConfig.encoderConfig().appendDefaultContentCharsetToContentTypeIfUndefined(false)))
                .contentType("application/json").body(body);
        signedHeaders.forEach((name, value) -> {
            if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Content-Type")) {
                spec.header(name, value);
            }
        });
        return spec.post(DerivedSigningKeyController.DERIVE_PATH);
    }

    static Map<String, String> signAs(String key, String secret, String service, byte[] body) {
        return DerivedSigningKeyTestSigner.sign("localhost:" + RestAssured.port, DerivedSigningKeyController.DERIVE_PATH,
                body, key, secret, null, "us-east-1", service, Instant.now());
    }

    static Response derive(Map<String, String> request) throws Exception {
        byte[] body = JSON.writeValueAsBytes(request);
        return post(body, signAs(CALLER, SECRET, "floci", body));
    }

    record Session(String key, String secret, String token, String roleName) {
    }

    private Session assume() {
        String name = "derive-" + UUID.randomUUID();
        roles.add(name);
        String arn = query("iam", Map.of("Action", "CreateRole", "RoleName", name,
                "AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}"))
                .statusCode(200).extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");
        ValidatableResponse response = query("sts", Map.of("Action", "AssumeRole", "RoleArn", arn,
                "RoleSessionName", "derive-session")).statusCode(200);
        String prefix = "AssumeRoleResponse.AssumeRoleResult.Credentials.";
        Session session = new Session(response.extract().path(prefix + "AccessKeyId"),
                response.extract().path(prefix + "SecretAccessKey"), response.extract().path(prefix + "SessionToken"),
                name);
        sessions.add(session.key());
        return session;
    }

    static ValidatableResponse query(String service, Map<String, String> params) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20261008/us-east-1/" + service
                + "/aws4_request").formParams(params).post("/").then();
    }

    // ---- behaviour ------------------------------------------------------------------------------

    @Test
    void anAssumeRoleSessionGetsItsSigningKeyAndNothingSecret() throws Exception {
        Session session = assume();
        Map<String, String> request = request(session.key(), session.token(), "signing");
        Response response = derive(request);
        response.then().statusCode(200).header("Cache-Control", "no-store")
                .body("credentialKind", equalTo("ASSUMED_ROLE"))
                .body("correlationId", equalTo(request.get("correlationId")))
                .body("service", equalTo(SERVICE)).body("level", equalTo("signing"));
        byte[] key = Base64.getDecoder().decode(response.path("derivedKey").toString());
        assertArrayEquals(SigV4RequestValidator.deriveSigningKey(session.secret(), today(), "us-east-1", SERVICE), key);
        assertNotEquals(SigV4RequestValidator.hexEncode(SigV4RequestValidator.deriveSigningKey(session.secret(),
                today(), "us-east-1", "other-svc")), SigV4RequestValidator.hexEncode(key));
        String text = response.asString();
        assertFalse(text.contains(session.secret()));
        assertFalse(text.contains(session.token()));
        assertFalse(text.contains(SECRET));
    }

    @Test
    void theServiceLevelReturnsTheKeyOneStepBeforeSigning() throws Exception {
        Session session = assume();
        Response response = derive(request(session.key(), session.token(), "service"));
        response.then().statusCode(200).body("level", equalTo("service"));
        byte[] kService = Base64.getDecoder().decode(response.path("derivedKey").toString());
        assertArrayEquals(SigV4RequestValidator.deriveSigningKey(session.secret(), today(), "us-east-1", SERVICE),
                SigV4RequestValidator.hmacSha256(kService, "aws4_request"));
    }

    @Test
    void theDerivedKeyVerifiesTheSessionsOwnSdkSignature() throws Exception {
        Session session = assume();
        byte[] key = Base64.getDecoder().decode(derive(request(session.key(), session.token(), "signing"))
                .then().statusCode(200).extract().path("derivedKey").toString());
        Instant at = Instant.now();
        byte[] payload = "{\"Action\":\"Ping\"}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = DerivedSigningKeyTestSigner.sign("example.internal", "/", payload,
                session.key(), session.secret(), session.token(), "us-east-1", SERVICE, at);
        String amzDate = headers.get("X-Amz-Date");
        String authorization = headers.get("Authorization");
        String signedHeaders = authorization.replaceAll(".*SignedHeaders=([^,]+).*", "$1");
        String signature = authorization.replaceAll(".*Signature=([0-9a-f]+).*", "$1");
        Map<String, String> lower = new HashMap<>();
        headers.forEach((name, value) -> lower.put(name.toLowerCase(), value));
        StringBuilder canonicalHeaders = new StringBuilder();
        for (String name : signedHeaders.split(";")) {
            canonicalHeaders.append(name).append(':').append(lower.get(name).trim()).append('\n');
        }
        String canonical = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n"
                + Digests.sha256Hex(payload);
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + amzDate.substring(0, 8) + "/us-east-1/" + SERVICE
                + "/aws4_request\n" + Digests.sha256Hex(
                        canonical.getBytes(StandardCharsets.UTF_8));
        assertEquals(signature, DerivedSigningKeyTestSigner.hmacHex(key, stringToSign));
    }

    @Test
    void theTwoCallFlowGetsTheKeyHereAndTheIdentityFromLookup() throws Exception {
        Session session = assume();
        derive(request(session.key(), session.token(), "signing")).then().statusCode(200);
        given().contentType("application/json").body(Map.of("correlationId", "two-call", "accessKeyId", session.key()))
                .post("/_floci/iam/issued-sessions/lookup").then().statusCode(200)
                .body("roleArn", equalTo("arn:aws:iam::000000000000:role/" + session.roleName()));
    }

    @Test
    void ineligibleCredentialsAllGetTheSameRefusal() throws Exception {
        List<String> bodies = new ArrayList<>();
        Session good = assume();
        // A wrong token.
        bodies.add(refusal(request(good.key(), good.token() + "x", "signing")));
        // An unknown key.
        bodies.add(refusal(request("ASIA" + "Q".repeat(16), "token", "signing")));
        // A restored session: the same stored fields without in-process AssumeRole provenance.
        Session restored = assume();
        String roleArn = "arn:aws:iam::000000000000:role/" + restored.roleName();
        Instant expiry = Instant.now().plusSeconds(3600);
        iam.unregisterSession("000000000000", restored.key());
        iam.registerSession(restored.key(), restored.secret(), restored.token(), roleArn, expiry, null, "000000000000");
        bodies.add(refusal(request(restored.key(), restored.token(), "signing")));
        // An expired AssumeRole session.
        Session expired = assume();
        iam.unregisterSession("000000000000", expired.key());
        String roleId = iam.findRole("000000000000", expired.roleName()).orElseThrow().getRoleId();
        iam.registerAssumeRoleSession(expired.key(), expired.secret(), expired.token(),
                "arn:aws:iam::000000000000:role/" + expired.roleName(), Instant.now().minusSeconds(1), null,
                "000000000000", "derive-session", roleId + ":derive-session", false);
        bodies.add(refusal(request(expired.key(), expired.token(), "signing")));
        // A session whose role was deleted.
        Session orphan = assume();
        query("iam", Map.of("Action", "DeleteRole", "RoleName", orphan.roleName())).statusCode(200);
        roles.remove(orphan.roleName());
        bodies.add(refusal(request(orphan.key(), orphan.token(), "signing")));
        // An ECS task-role session, minted without AssumeRole.
        String ecsRole = "derive-ecs-" + UUID.randomUUID();
        roles.add(ecsRole);
        String ecsRoleArn = query("iam", Map.of("Action", "CreateRole", "RoleName", ecsRole,
                "AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}"))
                .statusCode(200).extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");
        String taskArn = "arn:aws:ecs:us-east-1:000000000000:task/derive/" + UUID.randomUUID().toString().replace("-", "");
        String path = ecsTaskRoleCredentials.issue(taskArn, ecsRoleArn, "000000000000", Instant.now()).orElseThrow();
        SessionCredential task = ecsTaskRoleCredentials.resolveByPath(path, Instant.now()).orElseThrow();
        try {
            bodies.add(refusal(request(task.getAccessKeyId(), task.getSessionToken(), "signing")));
        } finally {
            ecsTaskRoleCredentials.revoke(taskArn);
        }
        for (String body : bodies) {
            assertEquals("{\"code\":\"DerivedSigningKeyRefused\"}", body);
        }
    }

    private static String refusal(Map<String, String> request) throws Exception {
        Response response = derive(request);
        response.then().statusCode(403).header("Cache-Control", "no-store").body("derivedKey", nullValue());
        return response.asString();
    }

    @Test
    void anIamUserKeyIsRefusedBeforeAnyLookup() throws Exception {
        String user = "derive-user-" + UUID.randomUUID().toString().substring(0, 8);
        query("iam", Map.of("Action", "CreateUser", "UserName", user)).statusCode(200);
        String key = query("iam", Map.of("Action", "CreateAccessKey", "UserName", user)).statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        try {
            derive(request(key, "unused", "signing")).then().statusCode(400)
                    .body("code", equalTo("InvalidDerivedSigningKeyRequest"));
        } finally {
            query("iam", Map.of("Action", "DeleteAccessKey", "UserName", user, "AccessKeyId", key));
            query("iam", Map.of("Action", "DeleteUser", "UserName", user));
        }
    }

    @Test
    void aScopeOutsideTheListsIsRefused() throws Exception {
        Session session = assume();
        for (Map.Entry<String, String> change : Map.of("region", "us-west-2", "service", "sts",
                "date", "20200101").entrySet()) {
            Map<String, String> request = request(session.key(), session.token(), "signing");
            request.put(change.getKey(), change.getValue());
            derive(request).then().statusCode(403).body("code", equalTo("DerivedSigningKeyScopeNotAllowed"));
        }
    }

    @Test
    void onlyTheConfiguredCallerGetsIn() throws Exception {
        Session session = assume();
        byte[] body = JSON.writeValueAsBytes(request(session.key(), session.token(), "signing"));
        post(body, signAs("test", "test", "floci", body)).then().statusCode(403)
                .body("code", equalTo("DerivedSigningKeyCallerRefused"));
        post(body, signAs(session.key(), session.secret(), "floci", body)).then().statusCode(403)
                .body("code", equalTo("DerivedSigningKeyCallerRefused"));
        post(body, Map.of()).then().statusCode(403).body("code", equalTo("DerivedSigningKeyCallerRefused"));
        post(body, signAs(CALLER, SECRET, "iam", body)).then().statusCode(403)
                .body("code", equalTo("DerivedSigningKeyCallerRefused"));
    }

    @Test
    void anExactReplayIsRefusedWithItsOwnCode() throws Exception {
        Session session = assume();
        byte[] body = JSON.writeValueAsBytes(request(session.key(), session.token(), "signing"));
        Map<String, String> headers = signAs(CALLER, SECRET, "floci", body);
        post(body, headers).then().statusCode(200);
        post(body, headers).then().statusCode(403).body("code", equalTo("DerivedSigningKeyReplayed"));
    }

    @Test
    void theUnknownScopeExemptionIsOnlyForThisPath() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = DerivedSigningKeyTestSigner.sign("localhost:" + RestAssured.port, "/_floci/other",
                body, CALLER, SECRET, null, "us-east-1", "floci", Instant.now());
        RequestSpecification spec = given().contentType("application/json").body(body);
        headers.forEach((name, value) -> {
            if (!name.equalsIgnoreCase("Host") && !name.equalsIgnoreCase("Content-Type")) {
                spec.header(name, value);
            }
        });
        Response response = spec.post("/_floci/other");
        assertEquals(404, response.statusCode());
        // Refused by the unknown-scope filter, not merely unmatched by JAX-RS.
        assertEquals("UnknownOperationException;Sender", response.header("x-amzn-query-error"));
    }

    @Test
    void noBrowserOriginIsGrantedAKeyResponse() throws Exception {
        given().header("Origin", "https://evil.example").header("Access-Control-Request-Method", "POST")
                .options(DerivedSigningKeyController.DERIVE_PATH).then()
                .header("Access-Control-Allow-Origin", nullValue());
        Session session = assume();
        byte[] body = JSON.writeValueAsBytes(request(session.key(), session.token(), "signing"));
        Map<String, String> headers = new LinkedHashMap<>(signAs(CALLER, SECRET, "floci", body));
        headers.put("Origin", "https://evil.example");
        post(body, headers).then().statusCode(200).header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void anOversizeBodyIsRefusedAsMalformed() {
        byte[] body = new byte[DerivedSigningKeyService.MAX_BODY_BYTES + 1];
        Arrays.fill(body, (byte) ' ');
        post(body, signAs(CALLER, SECRET, "floci", body)).then().statusCode(400)
                .body("code", equalTo("InvalidDerivedSigningKeyRequest"));
    }

    @Test
    void aBodyNearTheCapIsHashedAsSent() throws Exception {
        Session session = assume();
        Map<String, String> request = request(session.key(), session.token(), "signing");
        String json = JSON.writeValueAsString(request);
        byte[] body = (json + " ".repeat(DerivedSigningKeyService.MAX_BODY_BYTES - json.length()))
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(DerivedSigningKeyService.MAX_BODY_BYTES, body.length);
        post(body, signAs(CALLER, SECRET, "floci", body)).then().statusCode(200);
    }

    @Test
    void nothingSecretIsLoggedOnSuccessOrRefusal() throws Exception {
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        Logger root = Logger.getLogger("");
        Logger floci = Logger.getLogger("io.github.hectorvent.floci");
        Level previous = floci.getLevel();
        floci.setLevel(Level.ALL);
        root.addHandler(handler);
        try {
            org.jboss.logging.Logger.getLogger(DerivedSigningKeyController.class).debug("derived-key-log-canary");
            Session session = assume();
            Map<String, String> request = request(session.key(), session.token(), "signing");
            Response ok = derive(request);
            ok.then().statusCode(200);
            String key = ok.path("derivedKey").toString();
            byte[] raw = Base64.getDecoder().decode(key);
            byte[] body = JSON.writeValueAsBytes(request(session.key(), session.token(), "signing"));
            Map<String, String> headers = signAs(CALLER, SECRET, "floci", body);
            post(body, headers).then().statusCode(200);
            post(body, headers).then().statusCode(403);
            post(body, signAs("test", "test", "floci", body)).then().statusCode(403);
            Map<String, String> wrong = request(session.key(), session.token() + "x", "signing");
            derive(wrong).then().statusCode(403);
            List<String> secrets = List.of(session.secret(), session.token(), SECRET, key,
                    Base64.getUrlEncoder().encodeToString(raw), SigV4RequestValidator.hexEncode(raw),
                    headers.get("Authorization").replaceAll(".*Signature=", ""), request.get("correlationId"));
            assertTrue(records.stream().anyMatch(r -> String.valueOf(r.getMessage()).contains("derived-key-log-canary")),
                    "the capture must see DEBUG records from the endpoint's logger");
            for (LogRecord record : records) {
                String text = record.getMessage() + " " + Arrays.toString(record.getParameters())
                        + " " + record.getThrown();
                for (String secret : secrets) {
                    assertFalse(text.contains(secret), "a log record carries secret material: " + record.getLoggerName());
                }
            }
        } finally {
            root.removeHandler(handler);
            floci.setLevel(previous);
        }
    }
}
