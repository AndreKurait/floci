package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.ValidateSignaturesProfile;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
class StsSessionTokenCallerIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final Credentials ROOT = new Credentials("test", "test", null);
    private final List<String> sessions = new ArrayList<>();
    private final List<Runnable> cleanup = new ArrayList<>();

    @Inject
    IamService iam;

    @AfterEach
    void cleanup() {
        sessions.forEach(key -> iam.unregisterSession(ACCOUNT, key));
        AssertionError failures = null;
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            try {
                cleanup.get(i).run();
            } catch (AssertionError failure) {
                if (failures == null) {
                    failures = new AssertionError("Fixture cleanup failed");
                }
                failures.addSuppressed(failure);
            }
        }
        if (failures != null) {
            throw failures;
        }
    }

    @ParameterizedTest
    @CsvSource({
            "GetSessionToken, valid", "GetFederationToken, valid", "AssumeRole, valid",
            "GetSessionToken, missing", "GetFederationToken, missing", "AssumeRole, missing",
            "GetSessionToken, wrong", "GetFederationToken, wrong", "AssumeRole, wrong"
    })
    void temporaryCallerCannotMintAnotherSessionToken(String issuer, String tokenMode) throws Exception {
        Credentials user = createUser();
        Map<String, String> params = new LinkedHashMap<>();
        if ("GetFederationToken".equals(issuer)) {
            params.put("Name", "caller");
        } else if ("AssumeRole".equals(issuer)) {
            String role = "token-caller-" + UUID.randomUUID();
            Response created = request(ROOT, "iam", "CreateRole", Map.of("RoleName", role,
                    "AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}"));
            created.then().statusCode(200);
            String arn = created.xmlPath().getString("CreateRoleResponse.CreateRoleResult.Role.Arn");
            assertNotNull(arn);
            cleanup.add(() -> remove("DeleteRole", Map.of("RoleName", role)));
            params.put("RoleArn", arn);
            params.put("RoleSessionName", "caller");
        }
        Credentials issued = credentials(request(user, "sts", issuer, params), issuer);
        request(issued, "sts", "GetCallerIdentity", Map.of()).then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Account", equalTo(ACCOUNT));
        Credentials caller = switch (tokenMode) {
            case "missing" -> new Credentials(issued.key(), issued.secret(), null);
            case "wrong" -> new Credentials(issued.key(), issued.secret(), "wrong-token");
            default -> issued;
        };
        Response result = request(caller, "sts", "GetSessionToken", Map.of());
        remember(result, "GetSessionToken");
        result.then().statusCode(403).body("ErrorResponse.Error.Code",
                equalTo("valid".equals(tokenMode) ? "AccessDenied" : "InvalidClientTokenId"));
        request(issued, "sts", "GetCallerIdentity", Map.of()).then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void longTermCallerCanMintSessionToken(boolean user) throws Exception {
        Credentials caller = user ? createUser() : ROOT;
        Credentials issued = credentials(request(caller, "sts", "GetSessionToken", Map.of()), "GetSessionToken");
        assertTrue(issued.key().startsWith("ASIA"));
        request(issued, "sts", "GetCallerIdentity", Map.of()).then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Account", equalTo(ACCOUNT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "absent"})
    void blankAuthorizationDoesNotHideTemporaryQueryCredentials(String authorization) throws Exception {
        Credentials user = createUser();
        Credentials issued = credentials(request(user, "sts", "GetSessionToken", Map.of()), "GetSessionToken");
        Response result = querySessionRequest(authorization, issued).post("/");
        remember(result, "GetSessionToken");
        result.then().statusCode(403).body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void blankAuthorizationRetainsLongTermQueryIssuerIdentity(String authorization) throws Exception {
        Credentials user = createUser();
        String expected = request(user, "sts", "GetCallerIdentity", Map.of()).then().statusCode(200)
                .extract().path("GetCallerIdentityResponse.GetCallerIdentityResult.Arn");
        Credentials issued = credentials(querySessionRequest(authorization, user).post("/"), "GetSessionToken");
        request(issued, "sts", "GetCallerIdentity", Map.of()).then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Arn", equalTo(expected));
    }

    private RequestSpecification querySessionRequest(String authorization, Credentials caller) {
        RequestSpecification request = given().queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                .queryParam("X-Amz-Credential", caller.key() + "/20261013/us-east-1/sts/aws4_request")
                .contentType("application/x-www-form-urlencoded").body("Action=GetSessionToken");
        if (caller.token() != null) {
            request.queryParam("X-Amz-Security-Token", caller.token());
        }
        if (!"absent".equals(authorization)) {
            request.header("Authorization", authorization);
        }
        return request;
    }

    private Credentials createUser() throws Exception {
        String name = "token-user-" + UUID.randomUUID();
        request(ROOT, "iam", "CreateUser", Map.of("UserName", name)).then().statusCode(200);
        cleanup.add(() -> remove("DeleteUser", Map.of("UserName", name)));
        Response created = request(ROOT, "iam", "CreateAccessKey", Map.of("UserName", name));
        created.then().statusCode(200);
        String prefix = "CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.";
        String key = created.xmlPath().getString(prefix + "AccessKeyId");
        cleanup.add(() -> remove("DeleteAccessKey", Map.of("UserName", name, "AccessKeyId", key)));
        return new Credentials(key, created.xmlPath().getString(prefix + "SecretAccessKey"), null);
    }

    private Credentials credentials(Response response, String action) {
        remember(response, action);
        response.then().statusCode(200);
        String prefix = action + "Response." + action + "Result.Credentials.";
        Credentials result = new Credentials(response.xmlPath().getString(prefix + "AccessKeyId"),
                response.xmlPath().getString(prefix + "SecretAccessKey"),
                response.xmlPath().getString(prefix + "SessionToken"));
        assertNotNull(result.key());
        assertNotNull(result.secret());
        assertNotNull(result.token());
        return result;
    }

    private void remember(Response response, String action) {
        if (response.statusCode() == 200) {
            String key = response.xmlPath().getString(action + "Response." + action + "Result.Credentials.AccessKeyId");
            if (key != null && !key.isEmpty()) {
                sessions.add(key);
            }
        }
    }

    private void remove(String action, Map<String, String> params) {
        try {
            request(ROOT, "iam", action, params).then().statusCode(200);
        } catch (Exception e) {
            throw new AssertionError("Failed cleanup " + action, e);
        }
    }

    private Response request(Credentials caller, String service, String action, Map<String, String> params)
            throws Exception {
        StringBuilder body = new StringBuilder("Action=").append(action);
        params.forEach((name, value) -> body.append('&').append(name).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        URI uri = URI.create("http://localhost:" + RestAssured.port + "/");
        Map<String, String> extra = caller.token() == null ? Map.of()
                : Map.of("x-amz-security-token", caller.token());
        Map<String, String> headers = new LinkedHashMap<>(extra);
        headers.putAll(AwsRequestSigner.signedAs(caller.key(), caller.secret(), service)
                .headersFor("POST", uri, extra, body.toString().getBytes(StandardCharsets.UTF_8)));
        return given().headers(headers).contentType("application/x-www-form-urlencoded")
                .body(body.toString()).post("/");
    }

    private record Credentials(String key, String secret, String token) {}
}
