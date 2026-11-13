package io.github.hectorvent.floci.services.kms;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code floci.services.kms.listed-grantee-service-principals} changes only how ListGrants and
 * ListRetirableGrants list a grant's grantee and retiring principal ("if the grantee principal is an
 * AWS service, the GranteePrincipal field contains an AWS service principal", KMS GrantListEntry). The
 * grant keeps the principal it was created with, and the list filters use it.
 */
@QuarkusTest
@TestProfile(KmsListedGranteeServicePrincipalsIntegrationTest.Profile.class)
class KmsListedGranteeServicePrincipalsIntegrationTest {

    private static final String KMS_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String OWNER = "000000000331";
    private static final String SERVICE = "000000000332";
    private static final String OTHER = "000000000333";
    private static final String LISTED_AS = "example.us-east-1.amazonaws.com";

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.kms.listed-grantee-service-principals", SERVICE + "=" + LISTED_AS);
        }
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response kmsAs(String account, String operation, String body) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20261109/us-east-1/kms/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", "TrentService." + operation)
                .contentType(KMS_CONTENT_TYPE)
                .body(body)
                .when().post("/");
    }

    @Test
    void listingShowsTheServicePrincipalWhileTheGrantKeepsItsArn() {
        String keyArn = kmsAs(OWNER, "CreateKey", "{\"Description\":\"customer key\"}")
                .then().statusCode(200).extract().path("KeyMetadata.Arn");
        String service = "arn:aws:iam::" + SERVICE + ":root";
        String other = "arn:aws:iam::" + OTHER + ":role/worker";
        String serviceGrant = kmsAs(OWNER, "CreateGrant", "{\"KeyId\":\"" + keyArn + "\",\"GranteePrincipal\":\""
                + service + "\",\"RetiringPrincipal\":\"" + service + "\",\"Operations\":[\"Encrypt\"]}")
                .then().statusCode(200).extract().path("GrantId");
        String otherGrant = kmsAs(OWNER, "CreateGrant", "{\"KeyId\":\"" + keyArn + "\",\"GranteePrincipal\":\""
                + other + "\",\"Operations\":[\"Encrypt\"]}").then().statusCode(200).extract().path("GrantId");

        // ListGrants promises no order, so each grant is found by its id.
        Response listed = kmsAs(OWNER, "ListGrants", "{\"KeyId\":\"" + keyArn + "\"}")
                .then().statusCode(200).body("Grants.size()", equalTo(2)).extract().response();
        assertEquals(LISTED_AS, listed.path("Grants.find { it.GrantId == '" + serviceGrant + "' }.GranteePrincipal"));
        assertEquals(LISTED_AS, listed.path("Grants.find { it.GrantId == '" + serviceGrant + "' }.RetiringPrincipal"));
        assertEquals(other, listed.path("Grants.find { it.GrantId == '" + otherGrant + "' }.GranteePrincipal"));
        // The stored grantee still filters.
        kmsAs(OWNER, "ListGrants", "{\"KeyId\":\"" + keyArn + "\",\"GranteePrincipal\":\"" + service + "\"}")
                .then().statusCode(200).body("Grants.size()", equalTo(1));
        kmsAs(OWNER, "ListRetirableGrants", "{\"RetiringPrincipal\":\"" + service + "\"}")
                .then().statusCode(200).body("Grants.size()", equalTo(1))
                .body("Grants[0].RetiringPrincipal", equalTo(LISTED_AS))
                .body("Grants[0].GranteePrincipal", equalTo(LISTED_AS));
    }
}
