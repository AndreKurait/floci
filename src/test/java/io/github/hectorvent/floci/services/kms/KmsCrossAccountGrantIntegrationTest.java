package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Another account's key is usable only through a grant on it, which the other account uses by the
 * key's ARN. Each access key here is a 12-digit account id, which this emulator attributes to that
 * account's root.
 */
@QuarkusTest
class KmsCrossAccountGrantIntegrationTest {

    private static final String KMS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String OWNER = "000000000201";
    private static final String GRANTEE = "000000000202";
    private static final String OTHER = "000000000203";
    private static final String PROJECT_X = "x";
    private static final String PROJECT_Y = "y";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response kmsAs(String account, String operation, String body) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20261106/us-east-1/kms/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", "TrentService." + operation)
                .contentType(KMS_CONTENT_TYPE)
                .body(body)
                .when().post("/");
    }

    private static String root(String account) {
        return "arn:aws:iam::" + account + ":root";
    }

    private static String grant(String account, String keyArn, String grantee, String operations,
                                String constraints) {
        return kmsAs(account, "CreateGrant", "{\"KeyId\":\"" + keyArn + "\",\"GranteePrincipal\":\"" + grantee
                + "\",\"RetiringPrincipal\":\"" + grantee + "\",\"Operations\":" + operations
                + (constraints == null ? "" : ",\"Constraints\":" + constraints) + "}")
                .then().statusCode(200).extract().path("GrantToken");
    }

    private static String subset(String project) {
        return "{\"EncryptionContextSubset\":{\"project\":\"" + project + "\"}}";
    }

    @Test
    void anotherAccountUsesACustomerKeyOnlyThroughAGrant() {
        String keyArn = kmsAs(OWNER, "CreateKey", "{\"Description\":\"customer key\"}")
                .then().statusCode(200).extract().path("KeyMetadata.Arn");
        String keyId = keyArn.substring(keyArn.lastIndexOf('/') + 1);

        // No grant yet: the grantee account is refused, as no key policy is evaluated here.
        kmsAs(GRANTEE, "DescribeKey", "{\"KeyId\":\"" + keyArn + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"))
                .body("message", containsString("kms:DescribeKey"));
        // A bare key id names the caller's own key, never another account's.
        kmsAs(GRANTEE, "DescribeKey", "{\"KeyId\":\"" + keyId + "\"}")
                .then().statusCode(400).body("__type", equalTo("NotFoundException"));

        String token = grant(OWNER, keyArn, root(GRANTEE),
                "[\"DescribeKey\",\"GenerateDataKey\",\"Decrypt\",\"Encrypt\",\"CreateGrant\"]", subset(PROJECT_X));

        kmsAs(GRANTEE, "DescribeKey", "{\"KeyId\":\"" + keyArn + "\"}")
                .then().statusCode(200).body("KeyMetadata.Arn", equalTo(keyArn));

        Response dataKey = kmsAs(GRANTEE, "GenerateDataKey", "{\"KeyId\":\"" + keyArn
                + "\",\"KeySpec\":\"AES_256\",\"EncryptionContext\":{\"project\":\"" + PROJECT_X + "\"}}");
        dataKey.then().statusCode(200).body("KeyId", equalTo(keyArn));
        String ciphertext = dataKey.path("CiphertextBlob");
        String plaintext = dataKey.path("Plaintext");

        // The grant's constraint binds the encryption context.
        kmsAs(GRANTEE, "GenerateDataKey", "{\"KeyId\":\"" + keyArn
                + "\",\"KeySpec\":\"AES_256\",\"EncryptionContext\":{\"project\":\"" + PROJECT_Y + "\"}}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));

        // Decrypt finds the key through the ciphertext, in the owner's account.
        kmsAs(GRANTEE, "Decrypt", "{\"CiphertextBlob\":\"" + ciphertext
                + "\",\"EncryptionContext\":{\"project\":\"" + PROJECT_X + "\"}}")
                .then().statusCode(200).body("Plaintext", equalTo(plaintext)).body("KeyId", equalTo(keyArn));

        // An operation the grant does not list stays refused.
        kmsAs(GRANTEE, "GenerateDataKeyWithoutPlaintext", "{\"KeyId\":\"" + keyArn
                + "\",\"KeySpec\":\"AES_256\",\"EncryptionContext\":{\"project\":\"" + PROJECT_X + "\"}}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));

        // A grantee holding CreateGrant derives grants with no more operations and no weaker constraint.
        kmsAs(GRANTEE, "CreateGrant", "{\"KeyId\":\"" + keyArn + "\",\"GranteePrincipal\":\"" + root(OTHER)
                + "\",\"Operations\":[\"Decrypt\",\"Sign\"],\"Constraints\":" + subset(PROJECT_X) + "}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        kmsAs(GRANTEE, "CreateGrant", "{\"KeyId\":\"" + keyArn + "\",\"GranteePrincipal\":\"" + root(OTHER)
                + "\",\"Operations\":[\"Decrypt\"]}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        grant(GRANTEE, keyArn, root(OTHER), "[\"Decrypt\"]", subset(PROJECT_X));

        // The derived grant lives with the key, in the owner's account.
        kmsAs(OWNER, "ListGrants", "{\"KeyId\":\"" + keyArn + "\"}")
                .then().statusCode(200).body("Grants.size()", equalTo(2));
        kmsAs(OTHER, "Decrypt", "{\"CiphertextBlob\":\"" + ciphertext
                + "\",\"EncryptionContext\":{\"project\":\"" + PROJECT_X + "\"}}")
                .then().statusCode(200).body("Plaintext", equalTo(plaintext));
        kmsAs(OTHER, "DescribeKey", "{\"KeyId\":\"" + keyArn + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));

        // The retiring principal retires its grant by token from its own account.
        kmsAs(OTHER, "RetireGrant", "{\"GrantToken\":\"" + token + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        kmsAs(GRANTEE, "RetireGrant", "{\"GrantToken\":\"" + token + "\"}").then().statusCode(200);
        kmsAs(GRANTEE, "DescribeKey", "{\"KeyId\":\"" + keyArn + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        kmsAs(OWNER, "ListGrants", "{\"KeyId\":\"" + keyArn + "\"}")
                .then().statusCode(200).body("Grants.size()", equalTo(1));
    }
}
