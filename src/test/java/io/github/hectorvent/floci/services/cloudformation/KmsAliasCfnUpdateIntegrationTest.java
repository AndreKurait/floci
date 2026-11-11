package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * An {@code AWS::KMS::Alias} whose {@code TargetKeyId} changes is updated in place: the alias keeps its
 * name and points at the new key. CreateAlias refuses a name that exists, so the update has to go
 * through UpdateAlias.
 */
@QuarkusTest
class KmsAliasCfnUpdateIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260808/us-west-2/cloudformation/aws4_request";
    private static final String KMS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260808/us-west-2/kms/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String template(String aliasName, String targetLogicalId) {
        return """
                {
                  "Resources": {
                    "KeyA": {"Type": "AWS::KMS::Key", "Properties": {}},
                    "KeyB": {"Type": "AWS::KMS::Key", "Properties": {}},
                    "Alias": {
                      "Type": "AWS::KMS::Alias",
                      "Properties": {"AliasName": "%s", "TargetKeyId": {"Ref": "%s"}}
                    }
                  }
                }
                """.formatted(aliasName, targetLogicalId);
    }

    private static String physicalIdOf(String stackName, String logicalId) {
        String body = given()
                .contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
                .formParam("Action", "DescribeStackResources").formParam("StackName", stackName)
            .when().post("/").then().statusCode(200)
            .extract().body().asString();
        return XmlParser.extractGroups(body, "member").stream()
                .filter(member -> logicalId.equals(member.get("LogicalResourceId")))
                .map(member -> member.get("PhysicalResourceId"))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> aliasesOf(String keyId) {
        return given()
                .header("X-Amz-Target", "TrentService.ListAliases").header("Authorization", KMS_AUTH)
                .contentType("application/x-amz-json-1.1")
                .body("{\"KeyId\":\"" + keyId + "\"}")
            .when().post("/").then().statusCode(200)
            .extract().jsonPath().getList("Aliases.AliasName");
    }

    @Test
    void anAliasWhoseTargetKeyChangesIsRepointedInPlace() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "kms-alias-update-" + suffix;
        String aliasName = "alias/kms-alias-update-" + suffix;

        given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack").formParam("StackName", stackName)
            .formParam("TemplateBody", template(aliasName, "KeyA"))
        .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);

        String keyA = physicalIdOf(stackName, "KeyA");
        String keyB = physicalIdOf(stackName, "KeyB");
        assertNotEquals(keyA, keyB);
        assertEquals(List.of(aliasName), aliasesOf(keyA));
        assertEquals(List.of(), aliasesOf(keyB));

        given().contentType("application/x-www-form-urlencoded").header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack").formParam("StackName", stackName)
            .formParam("TemplateBody", template(aliasName, "KeyB"))
        .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE", CFN_AUTH);

        assertEquals(keyA, physicalIdOf(stackName, "KeyA"));
        assertEquals(keyB, physicalIdOf(stackName, "KeyB"));
        assertEquals(aliasName, physicalIdOf(stackName, "Alias"));
        assertEquals(List.of(), aliasesOf(keyA));
        assertEquals(List.of(aliasName), aliasesOf(keyB));

        CfnStackWaits.deleteStack(stackName, CFN_AUTH);
        CfnStackWaits.awaitStackDeleted(stackName, CFN_AUTH);
    }
}
