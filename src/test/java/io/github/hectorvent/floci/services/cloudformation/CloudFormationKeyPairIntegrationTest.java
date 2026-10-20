package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.services.ec2.Ec2KeyMaterial;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudFormationKeyPairIntegrationTest {
    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260205/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260205/us-east-1/ec2/aws4_request";
    private static final String SSM_AUTH =
            "AWS4-HMAC-SHA256 Credential=111122223333/20260205/us-east-1/ssm/aws4_request";

    @Test
    void generatedKeyHasBackingEc2AndSecureParameterAndBothDisappearOnDelete() throws Exception {
        String name = "cfn-key-" + Long.toString(System.nanoTime(), 36);
        String template = """
                {"Resources":{"Key":{"Type":"AWS::EC2::KeyPair","Properties":{"KeyName":"%s","KeyType":"rsa"}}},
                "Outputs":{"Name":{"Value":{"Ref":"Key"}},"Id":{"Value":{"Fn::GetAtt":["Key","KeyPairId"]}},
                "Fingerprint":{"Value":{"Fn::GetAtt":["Key","KeyFingerprint"]}}}}
                """.formatted(name);
        String stack = createStack(name, template);
        String id;
        try {
            awaitStackStatus(stack, "CREATE_COMPLETE");
            String xml = describeStacks(stack);
            assertEquals(name, outputValue(xml, "Name"));
            id = outputValue(xml, "Id");
            assertTrue(id.startsWith("key-"));
            String fingerprint = outputValue(xml, "Fingerprint");
            assertFalse(fingerprint.isBlank());
            given().header("Authorization", EC2_AUTH).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DescribeKeyPairs").formParam("KeyName.1", name)
                    .when().post("/").then().statusCode(200).body(containsString(id), containsString(fingerprint));
            Response parameter = given().header("Authorization", SSM_AUTH)
                    .header("X-Amz-Target", "AmazonSSM.GetParameter").contentType("application/x-amz-json-1.1")
                    .body(("{\"Name\":\"/ec2/keypair/" + id + "\",\"WithDecryption\":true}").getBytes(StandardCharsets.UTF_8))
                    .when().post("/");
            assertEquals(200, parameter.statusCode());
            assertEquals("SecureString", parameter.jsonPath().getString("Parameter.Type"));
            assertTrue(parameter.jsonPath().getString("Parameter.Value").contains("PRIVATE KEY"));
            cfnQuery("UpdateStack", name, template).then().statusCode(400)
                    .body(containsString("No updates are to be performed"));
            assertEquals(id, outputValue(describeStacks(stack), "Id"));
        } finally {
            deleteStack(name);
            awaitStackStatus(stack, "DELETE_COMPLETE");
        }
        given().header("Authorization", EC2_AUTH).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeKeyPairs").formParam("KeyName.1", name)
                .when().post("/").then().statusCode(400).body(containsString("InvalidKeyPair.NotFound"));
        given().header("Authorization", SSM_AUTH).header("X-Amz-Target", "AmazonSSM.GetParameter")
                .contentType("application/x-amz-json-1.1").body(("{\"Name\":\"/ec2/keypair/" + id + "\"}").getBytes(StandardCharsets.UTF_8))
                .when().post("/").then().statusCode(400).body(containsString("ParameterNotFound"));
    }

    @Test
    void importedKeyCreatesNoPrivateParameter() throws Exception {
        String name = "cfn-import-" + Long.toString(System.nanoTime(), 36);
        String publicKey = Ec2KeyMaterial.generateRsa().openSshPublicKey();
        String template = """
                {"Resources":{"Key":{"Type":"AWS::EC2::KeyPair",
                "Properties":{"KeyName":"%s","PublicKeyMaterial":"%s"}}},
                "Outputs":{"Id":{"Value":{"Fn::GetAtt":["Key","KeyPairId"]}}}}
                """.formatted(name, publicKey);
        String stack = createStack(name, template);
        try {
            awaitStackStatus(stack, "CREATE_COMPLETE");
            String id = outputValue(describeStacks(stack), "Id");
            given().header("Authorization", EC2_AUTH).contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DescribeKeyPairs").formParam("KeyName.1", name)
                    .when().post("/").then().statusCode(200).body(containsString(id));
            given().header("Authorization", SSM_AUTH).header("X-Amz-Target", "AmazonSSM.GetParameter")
                    .contentType("application/x-amz-json-1.1")
                    .body(("{\"Name\":\"/ec2/keypair/" + id + "\"}").getBytes(StandardCharsets.UTF_8))
                    .when().post("/").then().statusCode(400).body(containsString("ParameterNotFound"));
        } finally {
            deleteStack(name);
            awaitStackStatus(stack, "DELETE_COMPLETE");
        }
        given().header("Authorization", EC2_AUTH).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeKeyPairs").formParam("KeyName.1", name)
                .when().post("/").then().statusCode(400).body(containsString("InvalidKeyPair.NotFound"));
    }

    private static String createStack(String stackName, String template) {
        return cfnQuery("CreateStack", stackName, template)
                .then()
                .statusCode(200)
                .extract()
                .xmlPath()
                .getString("CreateStackResponse.CreateStackResult.StackId");
    }

    private static void updateStack(String stackName, String template) {
        cfnQuery("UpdateStack", stackName, template).then().statusCode(200);
    }

    private static void deleteStack(String stackName) {
        cfnQuery("DeleteStack", stackName, null).then().statusCode(200);
    }

    private static void awaitStackStatus(String stackId, String status) throws InterruptedException {
        String xml = "";
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            xml = describeStacks(stackId);
            if (xml.contains("<StackStatus>" + status + "</StackStatus>")) {
                return;
            }
            Thread.sleep(50);
        }
        fail("stack " + stackId + " never reached " + status + ": " + xml);
    }

    private static String describeStacks(String stackId) {
        return cfnQuery("DescribeStacks", stackId, null).then().statusCode(200).extract().asString();
    }

    private static Response cfnQuery(String action, String stackName, String template) {
        RequestSpecification req = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stackName);
        if (template != null) {
            req.formParam("TemplateBody", template);
        }
        return req.when().post("/");
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }
}
