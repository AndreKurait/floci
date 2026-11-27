package io.floci.aws.services.kms;

import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Under IAM enforcement, a grant on another account's key authorizes its grantee on its own:
 * "users and roles in the external account cannot use the KMS key until you attach IAM policies
 * that delegate those permissions, or use grants to manage access to the KMS key"
 * (KMS Developer Guide, key-policy-modifying-external-accounts). The grantee's identity policy
 * here allows KMS only on its own account's keys.
 * Explicit denies still win, and without a grant the request stays refused.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class KmsCrossAccountGrantIamEnforcementIntegrationTest {

    private static final String KMS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String OWNER = "000000000311";
    private static final String GRANTEE = "000000000312";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response kms(String accessKeyId, String operation, String body) {
        return given()
                .header("Authorization", auth(accessKeyId, "kms"))
                .header("X-Amz-Target", "TrentService." + operation)
                .contentType(KMS_CONTENT_TYPE)
                .body(body)
                .when().post("/");
    }

    private static String plaintext() {
        return Base64.getEncoder().encodeToString("grant-plaintext".getBytes(StandardCharsets.UTF_8));
    }

    private EnforcementFixtures.UserCredentials granteeUser(String userName, String extraStatement) {
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, RestAssured.port, auth(GRANTEE, "iam"), userName);
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(GRANTEE, "iam"), userName, "own-keys", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Action":["kms:Encrypt","kms:Decrypt","kms:DescribeKey","kms:CreateGrant","kms:RetireGrant"],
                  "Resource":"arn:aws:kms:us-east-1:%s:key/*"}%s]}""".formatted(GRANTEE, extraStatement));
        return credentials;
    }

    private static String customerKey() {
        return kms(OWNER, "CreateKey", "{\"Description\":\"customer key\"}")
                .then().statusCode(200).extract().path("KeyMetadata.Arn");
    }

    private static Response grant(String keyArn, String grantee, String operations) {
        return kms(OWNER, "CreateGrant", "{\"KeyId\":\"" + keyArn + "\",\"GranteePrincipal\":\"" + grantee
                + "\",\"RetiringPrincipal\":\"" + grantee + "\",\"Operations\":" + operations + "}")
                .then().statusCode(200).extract().response();
    }

    @Test
    void aGrantOnAnotherAccountsKeyAuthorizesItsGranteeWithoutAnIdentityPolicyForThatKey() {
        String userName = "grantee-" + UUID.randomUUID().toString().substring(0, 8);
        EnforcementFixtures.UserCredentials user = granteeUser(userName, "");
        String userArn = "arn:aws:iam::" + GRANTEE + ":user/" + userName;
        String keyArn = customerKey();
        String ungranted = customerKey();
        Response grant = grant(keyArn, userArn, "[\"Encrypt\"]");
        String grantToken = grant.path("GrantToken");

        kms(user.accessKeyId(), "Encrypt", "{\"KeyId\":\"" + keyArn + "\",\"Plaintext\":\"" + plaintext()
                + "\",\"GrantTokens\":[\"" + grantToken + "\"]}")
                .then().statusCode(200).body("CiphertextBlob", notNullValue()).body("KeyId", equalTo(keyArn));
        // The retiring principal retires its grant by key ID and grant ID (RetireGrant API reference).
        kms(user.accessKeyId(), "RetireGrant", "{\"KeyId\":\"" + keyArn + "\",\"GrantId\":\""
                + grant.path("GrantId") + "\"}")
                .then().statusCode(200);
        // Retired: the grant no longer authorizes the grantee.
        kms(user.accessKeyId(), "Encrypt", "{\"KeyId\":\"" + keyArn + "\",\"Plaintext\":\"" + plaintext() + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
        // No grant on this key: still refused.
        kms(user.accessKeyId(), "Encrypt", "{\"KeyId\":\"" + ungranted + "\",\"Plaintext\":\"" + plaintext() + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void aDecryptNamingNoKeyIsAuthorizedAgainstTheKeyThatEncryptedTheCiphertextInItsOwnersAccount() {
        // Decrypt's KeyId is optional: "AWS KMS gets the KMS key that was used to encrypt the data from
        // metadata in the ciphertext blob" (Decrypt API reference), and kms:Decrypt is authorized on that
        // key. A caller whose policy names only the other account's key must therefore be allowed, not
        // refused as if it had asked for a key of its own account.
        String userName = "decrypter-" + UUID.randomUUID().toString().substring(0, 8);
        String keyArn = customerKey();
        EnforcementFixtures.UserCredentials user = EnforcementFixtures.createUser(
                cleanup, RestAssured.port, auth(GRANTEE, "iam"), userName);
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(GRANTEE, "iam"), userName, "that-key", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Action":["kms:Encrypt","kms:Decrypt"],"Resource":"%s"}]}""".formatted(keyArn));
        grant(keyArn, "arn:aws:iam::" + GRANTEE + ":user/" + userName, "[\"Encrypt\",\"Decrypt\"]");
        String ciphertext = kms(user.accessKeyId(), "Encrypt", "{\"KeyId\":\"" + keyArn + "\",\"Plaintext\":\""
                + plaintext() + "\"}").then().statusCode(200).extract().path("CiphertextBlob");

        kms(user.accessKeyId(), "Decrypt", "{\"CiphertextBlob\":\"" + ciphertext + "\"}")
                .then().statusCode(200).body("KeyId", equalTo(keyArn)).body("Plaintext", equalTo(plaintext()));
    }

    @Test
    void aDecryptNamingNoKeyStaysRefusedWhenThePolicyDoesNotNameTheCiphertextsKey() {
        String userName = "other-key-" + UUID.randomUUID().toString().substring(0, 8);
        String keyArn = customerKey();
        String otherKey = customerKey();
        EnforcementFixtures.UserCredentials user = EnforcementFixtures.createUser(
                cleanup, RestAssured.port, auth(GRANTEE, "iam"), userName);
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(GRANTEE, "iam"), userName, "other-key", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"kms:Encrypt","Resource":"%s"},
                  {"Effect":"Allow","Action":"kms:Decrypt","Resource":"%s"}]}""".formatted(keyArn, otherKey));
        grant(keyArn, "arn:aws:iam::" + GRANTEE + ":user/" + userName, "[\"Encrypt\"]");
        String ciphertext = kms(user.accessKeyId(), "Encrypt", "{\"KeyId\":\"" + keyArn + "\",\"Plaintext\":\""
                + plaintext() + "\"}").then().statusCode(200).extract().path("CiphertextBlob");

        kms(user.accessKeyId(), "Decrypt", "{\"CiphertextBlob\":\"" + ciphertext + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void anExplicitDenyOverridesAGrantOnAnotherAccountsKey() {
        String userName = "denied-" + UUID.randomUUID().toString().substring(0, 8);
        EnforcementFixtures.UserCredentials user = granteeUser(userName,
                ",{\"Effect\":\"Deny\",\"Action\":\"kms:Encrypt\",\"Resource\":\"*\"}");
        String keyArn = customerKey();
        grant(keyArn, "arn:aws:iam::" + GRANTEE + ":user/" + userName, "[\"Encrypt\"]");

        kms(user.accessKeyId(), "Encrypt", "{\"KeyId\":\"" + keyArn + "\",\"Plaintext\":\"" + plaintext() + "\"}")
                .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
    }
}
