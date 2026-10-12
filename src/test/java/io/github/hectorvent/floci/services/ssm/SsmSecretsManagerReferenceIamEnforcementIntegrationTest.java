package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Parameter Store reads a reference with the caller's own {@code secretsmanager:GetSecretValue}
 * permission, checked before the secret is looked up. Measured against AWS in eu-west-1 on
 * 2026-09-28 with a role allowed only {@code ssm:GetParameter} and {@code ssm:GetParameters}.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SsmSecretsManagerReferenceIamEnforcementIntegrationTest {

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static final String ACCOUNT_ID = "000000000000";
    private static final String PREFIX = "/aws/reference/secretsmanager/";
    private static final String DEPENDENCY_FAILED = "An error occurred while calling one AWS dependency service.";
    private static final String SSM_ONLY = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":["ssm:GetParameter","ssm:GetParameters"],"Resource":"*"}
            ]}""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void getParameterIsRefusedWithoutGetSecretValueWhetherOrNotTheSecretExists() {
        String secret = createSecret("ref-denied");
        String caller = createUserWithPolicy("ssm-only", SSM_ONLY);

        ssm(caller, "GetParameter", "{\"Name\": \"" + PREFIX + secret + "\", \"WithDecryption\": true}")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo(DEPENDENCY_FAILED));
        ssm(caller, "GetParameter", "{\"Name\": \"" + PREFIX + secret + "-missing\", \"WithDecryption\": true}")
            .statusCode(400)
            .body("message", equalTo(DEPENDENCY_FAILED));
        ssm(caller, "GetParameter", "{\"Name\": \"" + PREFIX + secret + "\"}")
            .statusCode(400)
            .body("message", equalTo("WithDecryption flag must be True for retrieving a Secret Manager secret."));
    }

    @Test
    void aMalformedReferenceGetsTheSyntaxErrorBeforeTheCallerIsChecked() {
        String caller = createUserWithPolicy("ssm-only-syntax", SSM_ONLY);

        ssm(caller, "GetParameter", "{\"Name\": \"" + PREFIX + "app:a:b\", \"WithDecryption\": true}")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Invalid parameter name. Please use correct syntax "
                    + "for referencing a version/label  <name>:<version/label>"));
    }

    @Test
    void getParametersFailsAsAWholeWhenOneReferenceIsRefused() {
        String secret = createSecret("refs-denied");
        String caller = createUserWithPolicy("ssm-only-batch", SSM_ONLY);

        ssm(caller, "GetParameters", "{\"Names\": [\"" + PREFIX + secret + "\", \"" + PREFIX + secret + "-missing\"], "
                + "\"WithDecryption\": true}")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo(DEPENDENCY_FAILED));
    }

    @Test
    void aCallerAllowedGetSecretValueOnTheSecretArnReadsTheReference() {
        String secret = createSecret("ref-allowed");
        String arn = json(ACCOUNT_ID, "secretsmanager", "secretsmanager.DescribeSecret",
                "{\"SecretId\": \"" + secret + "\"}")
            .statusCode(200)
            .extract().path("ARN");
        String caller = createUserWithPolicy("ssm-and-secret", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["ssm:GetParameter","ssm:GetParameters"],"Resource":"*"},
                  {"Effect":"Allow","Action":"secretsmanager:GetSecretValue","Resource":"%s"}
                ]}""".formatted(arn));

        ssm(caller, "GetParameter", "{\"Name\": \"" + PREFIX + secret + "\", \"WithDecryption\": true}")
            .statusCode(200)
            .body("Parameter.Value", equalTo("secret-value"));
        ssm(caller, "GetParameters", "{\"Names\": [\"" + PREFIX + secret + "\"], \"WithDecryption\": true}")
            .statusCode(200)
            .body("Parameters[0].Value", equalTo("secret-value"));
    }

    private static String createSecret(String prefix) {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        json(ACCOUNT_ID, "secretsmanager", "secretsmanager.CreateSecret",
                "{\"Name\": \"" + name + "\", \"SecretString\": \"secret-value\"}")
            .statusCode(200);
        return name;
    }

    private String createUserWithPolicy(String prefix, String policyDocument) {
        String userName = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ACCOUNT_ID, "iam"), userName).accessKeyId();
        adminIam(Map.of("Action", "PutUserPolicy", "UserName", userName, "PolicyName", "inline",
                "PolicyDocument", policyDocument)).statusCode(200);
        return accessKeyId;
    }

    private static ValidatableResponse adminIam(Map<String, String> params) {
        RequestSpecification spec = given()
            .header("Authorization", auth(ACCOUNT_ID, "iam"))
            .contentType("application/x-www-form-urlencoded")
            .formParam("Version", "2010-05-08");
        params.forEach(spec::formParam);
        return spec.when().post("/").then();
    }

    private static ValidatableResponse ssm(String accessKeyId, String action, String body) {
        return json(accessKeyId, "ssm", "AmazonSSM." + action, body);
    }

    private static ValidatableResponse json(String accessKeyId, String scope, String target, String body) {
        return given()
            .header("Authorization", auth(accessKeyId, scope))
            .header("X-Amz-Target", target)
            .contentType("application/x-amz-json-1.1")
            .body(body)
        .when().post("/").then();
    }

}
