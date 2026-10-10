package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real HTTP/crypto/issuer/store checks; Docker inspect is a fixture, not an actual Lambda launch gate. */
@QuarkusTest
@TestProfile(LambdaIssuedIdentityIntegrationTest.Enabled.class)
class LambdaIssuedIdentityIntegrationTest {
    private static final String BASE = "/_floci/lambda/issued-identities/";
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String CONTAINER = "a".repeat(64);
    private static final String START = "2026-10-10T00:00:00Z";
    @Inject IamService iam;
    @Inject LambdaExecutionRoleCredentials issuer;
    @Inject LambdaFunctionStore functions;
    @InjectMock DockerClient docker;
    private LambdaFunction function;
    private SessionCreds credentials;
    private String roleName;

    public static class Enabled implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.lambda-identity-verification-enabled", "true",
                    "floci.services.iam.issued-session-verification-enabled", "true",
                    "floci.auth.validate-signatures", "false");
        }
    }

    @BeforeEach
    void currentNativeIssuerAndContainerFixture() throws Exception {
        roleName = "lambda-identity-" + UUID.randomUUID();
        String roleArn = query(Map.of("Action", "CreateRole", "RoleName", roleName,
                "AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}"), null)
                .statusCode(200).extract().path("CreateRoleResponse.CreateRoleResult.Role.Arn");
        function = new LambdaFunction();
        function.setAccountId(ACCOUNT);
        function.setFunctionName("proof-" + UUID.randomUUID());
        function.setFunctionArn("arn:aws:lambda:" + REGION + ":" + ACCOUNT + ":function:" + function.getFunctionName());
        function.setVersion("$LATEST");
        function.setRole(roleArn);
        function.setCodeSha256("A".repeat(43) + "=");
        function.setRevisionId(UUID.randomUUID().toString());
        functions.saveForAccount(ACCOUNT, REGION, function);
        credentials = issuer.forFunction(function).orElseThrow();
        issuer.bindContainer(function, credentials.accessKeyId(), CONTAINER);
        issuer.bindStartedContainer(function, credentials.accessKeyId(), CONTAINER, START);
        container(true, START);
    }

    @Test
    void numericLocalAccountKeepsExistingRoutingWithoutAcquiringLambdaIdentity() {
        given().filter(AwsRequestSigner.signedAs("111122223333", "test", "sts"))
                .contentType("application/x-www-form-urlencoded").body("Action=GetCallerIdentity")
                .post("/").then().statusCode(200)
                .body("GetCallerIdentityResponse.GetCallerIdentityResult.Account", equalTo("111122223333"));
    }

    @Test
    void rootProofAndOneHopSignedAssumeRoleReturnOriginalExecution() throws Exception {
        String root = verify(credentials, Instant.now()).statusCode(200)
                .body("kind", equalTo("LAMBDA_EXECUTION"))
                .body("lambda.containerId", equalTo(CONTAINER))
                .body("lambda.functionArn", equalTo(function.getFunctionArn())).extract().asString();
        assertFalse(root.contains(credentials.secretAccessKey()));
        assertFalse(root.contains(credentials.sessionToken()));
        SessionCreds derived = assume(credentials, true);
        String expiry = verify(derived, Instant.now()).statusCode(200)
                .body("kind", equalTo("LAMBDA_ASSUMED_ROLE"))
                .body("lambda.containerId", equalTo(CONTAINER))
                .body("lambda.codeSha256", equalTo(function.getCodeSha256())).extract().path("expiresAt");
        assertTrue(Instant.parse(expiry).isBefore(Instant.now().plusSeconds(61)));
        issuer.unregister(ACCOUNT, credentials.accessKeyId());
        verify(derived, Instant.now()).statusCode(403);
    }

    @Test
    void wrongSignatureTokenExpiredAndFutureProofCannotRefreshObservation() throws Exception {
        Map<String, String> proof = proof(credentials, Instant.now());
        proof.put("signature", "0".repeat(64));
        post(proof).statusCode(403);
        proof = proof(credentials, Instant.now());
        proof.put("sessionToken", "wrong");
        post(proof).statusCode(403);
        verify(credentials, Instant.now().minusSeconds(61)).statusCode(403);
        verify(credentials, Instant.now().plusSeconds(10)).statusCode(403);
    }

    @Test
    void roleReplacementAndCodeChangeRefuseExistingRootAndDerived() throws Exception {
        SessionCreds derived = assume(credentials, true);
        function.setCodeSha256("B".repeat(43) + "=");
        functions.saveForAccount(ACCOUNT, REGION, function);
        verify(credentials, Instant.now()).statusCode(403);
        verify(derived, Instant.now()).statusCode(403);
        function.setCodeSha256("A".repeat(43) + "=");
        functions.saveForAccount(ACCOUNT, REGION, function);
        query(Map.of("Action", "DeleteRole", "RoleName", roleName), null).statusCode(200);
        query(Map.of("Action", "CreateRole", "RoleName", roleName,
                "AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}"), null).statusCode(200);
        verify(credentials, Instant.now()).statusCode(403);
        verify(derived, Instant.now()).statusCode(403);
    }

    @Test
    void retiredOrRestartedContainerCannotReuseExecutionProof() throws Exception {
        container(false, START);
        verify(credentials, Instant.now()).statusCode(403);
        container(true, "2026-10-10T00:01:00Z");
        verify(credentials, Instant.now()).statusCode(403);
    }

    @Test
    void headerOnlyAssumptionAndTwoHopSessionCannotAcquireLineage() throws Exception {
        SessionCreds unsigned = assume(credentials, false);
        verify(unsigned, Instant.now()).statusCode(403);
        SessionCreds first = assume(credentials, true);
        SessionCreds second = assume(first, true);
        verify(second, Instant.now()).statusCode(403);
        query(Map.of("Action", "AssumeRole", "RoleArn", function.getRole(), "RoleSessionName", "forged"),
                new SessionCreds(credentials.accessKeyId(), "wrong", credentials.sessionToken())).statusCode(403);
    }

    @Test
    void ambiguousPrincipalRefusesLookupWhileExactIssuedKeysRemainBounded() throws Exception {
        SessionCreds another = issuer.forFunction(function).orElseThrow();
        issuer.bindContainer(function, another.accessKeyId(), CONTAINER);
        issuer.bindStartedContainer(function, another.accessKeyId(), CONTAINER, START);
        String principal = verify(credentials, Instant.now()).statusCode(200).extract().path("principalId");
        verify(another, Instant.now()).statusCode(200);
        given().contentType("application/json").body(Map.of("correlationId", "lookup", "principalId", principal))
                .post(BASE + "lookup").then().statusCode(403);
    }

    @Test
    void restoredOrManuallyRegisteredSessionDoesNotBecomeNativeExecution() throws Exception {
        String principal = verify(credentials, Instant.now()).statusCode(200).extract().path("principalId");
        RequestScopes.runAs(ACCOUNT, () -> iam.registerLambdaExecutionRoleSession(ACCOUNT,
                credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken(),
                function.getRole(), function.getFunctionName(), principal));
        verify(credentials, Instant.now()).statusCode(403);
    }

    private SessionCreds assume(SessionCreds parent, boolean signed) {
        Map<String, String> parameters = Map.of("Action", "AssumeRole", "RoleArn", function.getRole(),
                "RoleSessionName", "child-" + UUID.randomUUID());
        ValidatableResponse response;
        if (signed) {
            response = query(parameters, parent);
        } else {
            response = given().contentType("application/x-www-form-urlencoded").body(form(parameters)).post("/").then();
        }
        response.statusCode(200);
        String prefix = "AssumeRoleResponse.AssumeRoleResult.Credentials.";
        return new SessionCreds(response.extract().path(prefix + "AccessKeyId"),
                response.extract().path(prefix + "SecretAccessKey"), response.extract().path(prefix + "SessionToken"));
    }

    private ValidatableResponse query(Map<String, String> parameters, SessionCreds session) {
        AwsRequestSigner signer = session == null ? AwsRequestSigner.signedAs("test", "test", "iam")
                : AwsRequestSigner.signedAs(session.accessKeyId(), session.secretAccessKey(), "sts")
                .withSessionToken(session.sessionToken());
        return given().filter(signer).contentType("application/x-www-form-urlencoded").body(form(parameters)).post("/").then();
    }

    private static String form(Map<String, String> parameters) {
        return parameters.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

    private void container(boolean running, String start) throws Exception {
        InspectContainerCmd command = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(CONTAINER)).thenReturn(command);
        when(command.exec()).thenReturn(new ObjectMapper().readValue("{\"Id\":\"" + CONTAINER
                + "\",\"State\":{\"Running\":" + running + ",\"StartedAt\":\"" + start + "\"}}", InspectContainerResponse.class));
    }

    private ValidatableResponse verify(SessionCreds session, Instant timestamp) throws Exception {
        return post(proof(session, timestamp));
    }
    private ValidatableResponse post(Map<String, String> request) {
        return given().contentType("application/json").body(request).post(BASE + "verify").then();
    }
    private static Map<String, String> proof(SessionCreds session, Instant timestamp) throws Exception {
        String time = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(timestamp);
        String date = time.substring(0, 8);
        String text = "AWS4-HMAC-SHA256\n" + time + "\n" + date + "/us-east-1/sts/aws4_request\n" + "a".repeat(64);
        String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(
                SigV4RequestValidator.deriveSigningKey(session.secretAccessKey(), date, "us-east-1", "sts"), text));
        return new HashMap<>(Map.of("correlationId", "identity-test", "accessKeyId", session.accessKeyId(),
                "sessionToken", session.sessionToken(), "stringToSign", text, "signature", signature));
    }
}
