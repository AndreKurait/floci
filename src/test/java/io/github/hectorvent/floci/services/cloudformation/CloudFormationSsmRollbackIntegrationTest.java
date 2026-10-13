package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationSsmRollbackIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedUpdateRestoresBackingValueTagsAndIdentity(boolean rename) throws Exception {
        String stack = unique("ssm-rollback");
        String original = "/" + stack;
        String target = rename ? original + "-new" : original;
        deploy("CreateStack", stack, template(original, "before", Map.of("team", "old", "dropped", "restore"), false, false));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        ssm("AddTagsToResource", Map.of("ResourceType", "Parameter", "ResourceId", original,
                "Tags", List.of(Map.of("Key", "external", "Value", "keep")))).then().statusCode(200);
        deploy("UpdateStack", stack, template(target, "after", Map.of("team", "new", "added", "remove"), true, false));
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("before", value(original));
        List<Map<String, String>> tags = ssm("ListTagsForResource", Map.of("ResourceType", "Parameter", "ResourceId", original))
                .then().statusCode(200).extract().jsonPath().getList("TagList");
        assertEquals(Map.of("team", "old", "dropped", "restore", "external", "keep"),
                tags.stream().collect(Collectors.toMap(t -> t.get("Key"), t -> t.get("Value"))));
        if (rename) {
            assertMissing(target);
        }
        deleteStack(stack);
        assertMissing(original);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successfulReplacementAppliesItsRetentionPolicy(boolean retain) throws Exception {
        String stack = unique("ssm-replace");
        String original = "/" + stack;
        String target = original + "-new";
        deploy("CreateStack", stack, template(original, "before", Map.of(), false, retain));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        deploy("UpdateStack", stack, template(target, "after", Map.of(), false, retain));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("after", value(target));
        if (retain) {
            assertEquals("before", value(original));
        } else {
            assertMissing(original);
        }
        deleteStack(stack);
        assertMissing(target);
        if (retain) {
            assertEquals("before", value(original));
            ssm("DeleteParameter", Map.of("Name", original)).then().statusCode(200);
        }
    }

    @Test
    void replacementCollisionCannotAdoptOrDeleteAnUnmanagedParameter() throws Exception {
        String stack = unique("ssm-collision");
        String original = "/" + stack;
        String target = original + "-foreign";
        deploy("CreateStack", stack, template(original, "before", Map.of(), false, false));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        ssm("PutParameter", Map.of("Name", target, "Value", "foreign", "Type", "String")).then().statusCode(200);
        deploy("UpdateStack", stack, template(target, "after", Map.of(), false, false));
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("before", value(original));
        assertEquals("foreign", value(target));
        deleteStack(stack);
        assertMissing(original);
        assertEquals("foreign", value(target));
        ssm("DeleteParameter", Map.of("Name", target)).then().statusCode(200);
    }

    @Test
    void createCollisionCannotOverwriteOrDeleteAnUnmanagedParameter() throws Exception {
        String stack = unique("ssm-create-collision");
        String name = "/" + stack;
        ssm("PutParameter", Map.of("Name", name, "Value", "foreign", "Type", "String")).then().statusCode(200);
        deploy("CreateStack", stack, template(name, "managed", Map.of(), false, false));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("foreign", value(name));
        deleteStack(stack);
        assertEquals("foreign", value(name));
        ssm("DeleteParameter", Map.of("Name", name)).then().statusCode(200);
    }

    @Test
    void aSecondUpdateRollsBackToTheLastCommittedValue() throws Exception {
        String stack = unique("ssm-two-updates");
        String name = "/" + stack;
        deploy("CreateStack", stack, template(name, "one", Map.of(), false, false));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        deploy("UpdateStack", stack, template(name, "two", Map.of(), false, false));
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        deploy("UpdateStack", stack, template(name, "three", Map.of(), true, false));
        assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stack).status());
        assertEquals("two", value(name));
        deleteStack(stack);
        assertMissing(name);
    }

    private static String template(String name, String value, Map<String, String> tags, boolean fail, boolean retain)
            throws JsonProcessingException {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("Parameter", Map.of("Type", "AWS::SSM::Parameter", "UpdateReplacePolicy", retain ? "Retain" : "Delete",
                "Properties", Map.of("Name", name, "Value", value, "Type", "String", "Tags", tags)));
        if (fail) {
            resources.put("Failure", Map.of("Type", "AWS::SecretsManager::Secret", "DependsOn", "Parameter",
                    "Properties", Map.of("SecretString", "explicit", "GenerateSecretString", Map.of("PasswordLength", 32))));
        }
        return MAPPER.writeValueAsString(Map.of("Resources", resources,
                "Outputs", Map.of("Name", Map.of("Value", Map.of("Ref", "Parameter")))));
    }

    private static void deploy(String action, String stack, String template) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", action).formParam("StackName", stack).formParam("TemplateBody", template)
                .post("/").then().statusCode(200);
    }

    private static Response ssm(String operation, Map<String, ?> body) throws JsonProcessingException {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AmazonSSM." + operation).body(MAPPER.writeValueAsString(body)).post("/");
    }

    private static String value(String name) throws JsonProcessingException {
        return ssm("GetParameter", Map.of("Name", name)).then().statusCode(200)
                .extract().jsonPath().getString("Parameter.Value");
    }

    private static void assertMissing(String name) throws JsonProcessingException {
        Response response = ssm("GetParameter", Map.of("Name", name));
        assertEquals(400, response.statusCode());
        assertTrue(response.asString().contains("ParameterNotFound"), response.asString());
    }

    private static void deleteStack(String stack) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack").formParam("StackName", stack).post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(stack);
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }
}
