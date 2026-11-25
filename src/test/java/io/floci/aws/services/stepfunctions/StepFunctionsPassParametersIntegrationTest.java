package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.floci.aws.testing.StepFunctionsExecutions.waitForTerminalState;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies a Pass state applies its {@code Parameters} (with intrinsics) — previously they were
 * accepted but ignored, so the input flowed through unchanged.
 */
@QuarkusTest
class StepFunctionsPassParametersIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String DEFINITION = """
            {"StartAt":"Validate","States":{
              "Validate":{"Type":"Pass",
                "Parameters":{"result.$":"States.ArrayContains($.list, $.target)"},
                "ResultPath":"$.validation","End":true}}}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void passStateAppliesParametersWithArrayContains() throws Exception {
        JsonNode hit = mapper.readTree(run("{\"list\":[\"a\",\"b\",\"c\"],\"target\":\"b\"}"));
        assertTrue(hit.path("validation").path("result").asBoolean());

        JsonNode miss = mapper.readTree(run("{\"list\":[\"a\",\"b\",\"c\"],\"target\":\"z\"}"));
        assertFalse(miss.path("validation").path("result").asBoolean());
    }

    private String run(String input) {
        String smArn = create("pass-params-" + System.currentTimeMillis());
        String execArn = start(smArn, input);
        Response resp = waitForTerminalState(execArn, Duration.ofSeconds(5));
        String status = resp.jsonPath().getString("status");
        assertEquals("SUCCEEDED", status, "Execution " + status + ": " + resp.body().asString());
        return resp.jsonPath().getString("output");
    }

    private String create(String name) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"name\":\"" + name + "\",\"definition\":" + quote(DEFINITION) + ",\"roleArn\":\"" + ROLE_ARN + "\"}")
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("stateMachineArn");
    }

    private String start(String smArn, String input) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body("{\"stateMachineArn\":\"" + smArn + "\",\"input\":" + quote(input) + "}")
                .when().post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("executionArn");
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
