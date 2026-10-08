package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IssuedSessionIntegrationTest.Enabled.class)
class IssuedSessionIntegrationTest {
    private static final String BASE = "/_floci/iam/issued-sessions/";
    private final List<String> roleNames = new ArrayList<>();

    public static class Enabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.issued-session-verification-enabled", "true");
        }
    }

    @AfterEach
    void deleteRoles() {
        for (String name : roleNames) {
            query("iam", Map.of("Action", "DeleteRole", "RoleName", name));
        }
    }

    @Test
    void malformedProbeUniquelyIdentifiesEnabledFacility() {
        for (String operation : List.of("verify", "lookup")) {
            given().contentType("application/json").body("{}").post(BASE + operation)
                    .then().statusCode(400).header("Cache-Control", "no-store")
                    .body("code", equalTo("InvalidIssuedSessionRequest"));
        }
    }

    @Test
    void publicAssumeRoleProofAndColdExactLookupsAgreeWithPublicCallerIdentity() throws Exception {
        ValidatableResponse assumed = assume(null, false);
        String key = field(assumed, "Credentials.AccessKeyId");
        String token = field(assumed, "Credentials.SessionToken");
        String secret = field(assumed, "Credentials.SecretAccessKey");
        String arn = field(assumed, "AssumedRoleUser.Arn");
        String principal = field(assumed, "AssumedRoleUser.AssumedRoleId");
        Map<String, String> request = proof(key, token, secret);
        String response = given().contentType("application/json").body(request).post(BASE + "verify")
                .then().statusCode(200).body("operation", equalTo("Verify"))
                .body("principalArn", equalTo(arn)).body("principalId", equalTo(principal))
                .body("sessionPolicyPresent", equalTo(false)).extract().asString();
        assertFalse(response.contains(key));
        assertFalse(response.contains(token));
        assertFalse(response.contains(secret));
        for (Map.Entry<String, String> selector : Map.of("accessKeyId", key, "principalArn", arn, "principalId", principal).entrySet()) {
            given().contentType("application/json").body(Map.of("correlationId", "cold",
                    selector.getKey(), selector.getValue())).post(BASE + "lookup")
                    .then().statusCode(200).body("operation", equalTo("Lookup"))
                    .body("principalArn", equalTo(arn)).body("principalId", equalTo(principal));
        }
        given().formParam("Action", "GetCallerIdentity")
                .header("Authorization", authorization("sts", key))
                .header("X-Amz-Security-Token", token).post("/").then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn", equalTo(arn));
        request.put("signature", "0".repeat(64));
        given().contentType("application/json").body(request).post(BASE + "verify")
                .then().statusCode(403).body("code", equalTo("IssuedSessionVerificationFailed"));
    }

    @Test
    void deletedAndSameNamedReplacementRoleCannotAuthenticateOldSession() throws Exception {
        ValidatableResponse assumed = assume(null, false);
        Map<String, String> request = proof(field(assumed, "Credentials.AccessKeyId"), field(assumed, "Credentials.SessionToken"),
                field(assumed, "Credentials.SecretAccessKey"));
        String name = roleNames.getFirst();
        query("iam", Map.of("Action", "DeleteRole", "RoleName", name)).statusCode(200);
        given().contentType("application/json").body(request).post(BASE + "verify").then().statusCode(403);
        createRole(name);
        given().contentType("application/json").body(request).post(BASE + "verify").then().statusCode(403);
    }

    @Test
    void restrictionPresenceIsExplicitForBothInlineAndManagedRequests() {
        for (boolean managed : List.of(false, true)) {
            ValidatableResponse assumed = assume(managed ? null : "{\"Version\":\"2012-10-17\",\"Statement\":[]}", managed);
            given().contentType("application/json").body(Map.of("correlationId", "restricted", "accessKeyId",
                    field(assumed, "Credentials.AccessKeyId"))).post(BASE + "lookup")
                    .then().statusCode(200).body("sessionPolicyPresent", equalTo(true));
        }
    }

    @Test
    void otherStsIssuanceIsRefusedEvenWithItsRealTokenAndSecret() throws Exception {
        ValidatableResponse issued = query("sts", Map.of("Action", "GetSessionToken")).statusCode(200);
        String prefix = "GetSessionTokenResponse.GetSessionTokenResult.Credentials.";
        String key = issued.extract().path(prefix + "AccessKeyId");
        String token = issued.extract().path(prefix + "SessionToken");
        String secret = issued.extract().path(prefix + "SecretAccessKey");
        given().contentType("application/json").body(proof(key, token, secret)).post(BASE + "verify")
                .then().statusCode(403).body("code", equalTo("IssuedSessionVerificationFailed"));
    }

    private ValidatableResponse assume(String policy, boolean managed) {
        String name = "verify-" + UUID.randomUUID();
        roleNames.add(name);
        String arn = createRole(name);
        Map<String, String> params = new HashMap<>(Map.of("Action", "AssumeRole", "RoleArn", arn,
                "RoleSessionName", "integration-session"));
        if (policy != null) {
            params.put("Policy", policy);
        }
        if (managed) {
            params.put("PolicyArns.member.1.arn", arn.replace("role/", "policy/"));
        }
        return query("sts", params).statusCode(200);
    }

    private String createRole(String name) {
        return query("iam", Map.of("Action", "CreateRole", "RoleName", name,
                "AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}"))
                .statusCode(200).extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");
    }

    private static ValidatableResponse query(String service, Map<String, String> params) {
        return given().header("Authorization", authorization(service, "test")).formParams(params)
                .post("/").then();
    }

    private static String authorization(String service, String key) {
        return "AWS4-HMAC-SHA256 Credential=" + key + "/20261008/us-east-1/" + service + "/aws4_request";
    }

    private static String field(ValidatableResponse response, String suffix) {
        return response.extract().path("AssumeRoleResponse.AssumeRoleResult." + suffix);
    }

    private static Map<String, String> proof(String key, String token, String secret) throws Exception {
        String timestamp = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        String date = timestamp.substring(0, 8);
        String text = "AWS4-HMAC-SHA256\n" + timestamp + "\n" + date
                + "/us-east-1/sts/aws4_request\n" + "a".repeat(64);
        String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(
                SigV4RequestValidator.deriveSigningKey(secret, date, "us-east-1", "sts"), text));
        return new HashMap<>(Map.of("correlationId", "integration", "accessKeyId", key, "sessionToken", token,
                "stringToSign", text, "signature", signature));
    }
}
