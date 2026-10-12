package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusTest
class StepFunctionsTaskCredentialsIntegrationTest {
    private static final String SOURCE = "000000000331";
    private static final String TARGET = "000000000332";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PASS = """
            {"StartAt":"Done","States":{"Done":{"Type":"Pass","End":true}}}
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"states:startExecution", "states:startExecution.sync",
            "states:startExecution.sync:2", "aws-sdk:sfn:startExecution", "aws-sdk:sfn:startSyncExecution"})
    void nestedTaskUsesRoleAccountAndRestoresParentAccount(String resource) throws Exception {
        String child = machine(TARGET, PASS, resource.endsWith("startSyncExecution") ? "EXPRESS" : "STANDARD");
        String ownChild = machine(SOURCE, PASS, "STANDARD");
        ObjectNode definition = definition(child, resource, role());
        ObjectNode task = (ObjectNode) definition.path("States").path("Child");
        task.remove("End");
        task.put("Next", "OwnChild");
        task.put("ResultPath", "$.child");
        ((ObjectNode) definition.path("States")).set("OwnChild", MAPPER.readTree("""
                {"Type":"Task","Resource":"arn:aws:states:::states:startExecution.sync:2",
                 "Parameters":{"StateMachineArn":"%s","Input":{"restored":true}},"End":true}
                """.formatted(ownChild)));
        String parent = machine(SOURCE, definition.toString(), "STANDARD");
        String execution = start(parent, "{}");
        Response done = terminal(SOURCE, execution);
        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        JsonNode output = MAPPER.readTree(done.jsonPath().getString("output"));
        assertEquals(true, output.path("Output").path("restored").asBoolean());
        request(TARGET, "DescribeExecution", Map.of("executionArn", execution)).then().statusCode(400);
        Response history = request(SOURCE, "GetExecutionHistory", Map.of("executionArn", execution));
        history.then().statusCode(200);
        assertTrue(history.asString().contains(child), history.asString());
        assertTrue(history.asString().contains("ExecutionSucceeded"), history.asString());
    }

    @Test
    void dynamicRoleUsesInputPathBeforeParameters() throws Exception {
        String child = machine(TARGET, PASS, "STANDARD");
        String taskRole = role();
        ObjectNode definition = definition(child, "states:startExecution.sync:2", taskRole);
        ObjectNode task = (ObjectNode) definition.path("States").path("Child");
        task.put("InputPath", "$.selected");
        task.set("Credentials", MAPPER.valueToTree(Map.of("RoleArn.$", "$.role")));
        String input = MAPPER.writeValueAsString(Map.of("selected", Map.of("role", taskRole)));
        String execution = start(machine(SOURCE, definition.toString(), "STANDARD"), input);
        Response done = terminal(SOURCE, execution);
        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
    }

    @Test
    void jsonataRoleUsesOriginalStateInput() throws Exception {
        String child = machine(TARGET, PASS, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", role());
        definition.put("QueryLanguage", "JSONata");
        ObjectNode task = (ObjectNode) definition.path("States").path("Child");
        task.set("Arguments", task.remove("Parameters"));
        task.set("Credentials", MAPPER.valueToTree(Map.of("RoleArn", "{% $states.input.role %}")));
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"),
                MAPPER.writeValueAsString(Map.of("role", role()))));
        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
    }

    @Test
    void foreignArnAloneDoesNotGrantAccountAccess() throws Exception {
        String child = machine(TARGET, PASS, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", role());
        ((ObjectNode) definition.path("States").path("Child")).remove("Credentials");
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("FAILED", done.jsonPath().getString("status"), done.asString());
        assertEquals("StepFunctions.StateMachineDoesNotExistException", done.jsonPath().getString("error"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "wrong-path", "foreign-partition", "wrong-shape"})
    void invalidCredentialsCannotStartChild(String invalid) throws Exception {
        String existing = role();
        String value = switch (invalid) {
            case "missing" -> existing + "-missing";
            case "wrong-path" -> existing.replace(":role/", ":role/different/");
            case "foreign-partition" -> existing.replace("arn:aws:", "arn:aws-cn:");
            default -> "not-an-arn";
        };
        String child = machine(TARGET, PASS, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", value);
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("FAILED", done.jsonPath().getString("status"), done.asString());
        request(TARGET, "ListExecutions", Map.of("stateMachineArn", child)).then()
                .statusCode(200).body("executions.size()", org.hamcrest.Matchers.equalTo(0));
    }

    @Test
    void childFailureIsCaughtInParentAccount() throws Exception {
        String child = machine(TARGET, """
                {"StartAt":"Fail","States":{"Fail":{"Type":"Fail","Error":"ChildFailure"}}}
                """, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", role());
        ((ObjectNode) definition.path("States").path("Child")).set("Catch",
                MAPPER.readTree("[{\"ErrorEquals\":[\"States.TaskFailed\"],\"Next\":\"Recovered\"}]"));
        ((ObjectNode) definition.path("States")).set("Recovered",
                MAPPER.readTree("{\"Type\":\"Pass\",\"End\":true}"));
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("SUCCEEDED", done.jsonPath().getString("status"), done.asString());
        assertTrue(done.jsonPath().getString("output").contains("ChildFailure"), done.asString());
    }

    @Test
    void stoppingParentStopsForeignChild() throws Exception {
        String child = machine(TARGET, """
                {"StartAt":"Wait","States":{"Wait":{"Type":"Wait","Seconds":60,"End":true}}}
                """, "STANDARD");
        String parent = machine(SOURCE, definition(child, "states:startExecution.sync:2", role()).toString(),
                "STANDARD");
        String execution = start(parent, "{}");
        String childExecution = null;
        for (int i = 0; i < 100; i++) {
            Response history = request(SOURCE, "GetExecutionHistory", Map.of("executionArn", execution));
            history.then().statusCode(200);
            for (JsonNode event : MAPPER.readTree(history.asString()).path("events")) {
                if ("TaskSubmitted".equals(event.path("type").asText())) {
                    childExecution = MAPPER.readTree(event.path("taskSubmittedEventDetails").path("output").asText())
                            .path("ExecutionArn").asText();
                }
            }
            if (childExecution != null) {
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(childExecution != null && childExecution.contains(TARGET));
        request(SOURCE, "StopExecution", Map.of("executionArn", execution)).then().statusCode(200);
        assertEquals("ABORTED", terminal(TARGET, childExecution).jsonPath().getString("status"));
        assertEquals("ABORTED", terminal(SOURCE, execution).jsonPath().getString("status"));
    }

    @Test
    void taskTimeoutStopsForeignChild() throws Exception {
        String child = machine(TARGET, """
                {"StartAt":"Wait","States":{"Wait":{"Type":"Wait","Seconds":60,"End":true}}}
                """, "STANDARD");
        ObjectNode definition = definition(child, "states:startExecution.sync:2", role());
        ((ObjectNode) definition.path("States").path("Child")).put("TimeoutSeconds", 1);
        Response done = terminal(SOURCE, start(machine(SOURCE, definition.toString(), "STANDARD"), "{}"));
        assertEquals("FAILED", done.jsonPath().getString("status"), done.asString());
        assertEquals("States.Timeout", done.jsonPath().getString("error"));
        Response children = request(TARGET, "ListExecutions", Map.of("stateMachineArn", child));
        children.then().statusCode(200).body("executions.size()", org.hamcrest.Matchers.equalTo(1));
        String execution = children.jsonPath().getString("executions[0].executionArn");
        assertEquals("ABORTED", terminal(TARGET, execution).jsonPath().getString("status"));
    }

    private static ObjectNode definition(String child, String resource, String role) throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {"StartAt":"Child","States":{"Child":{"Type":"Task","Resource":"arn:aws:states:::%s",
                "Credentials":{"RoleArn":"%s"},"Parameters":{"StateMachineArn":"%s","Input":{}},"End":true}}}
                """.formatted(resource, role, child));
    }

    private static String role() {
        String name = "nested-" + UUID.randomUUID();
        given().header("Authorization", auth(TARGET, "iam")).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", name)
                .formParam("AssumeRolePolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"sts:AssumeRole",
                        "Principal":{"AWS":"arn:aws:iam::%s:root"}}]}
                        """.formatted(SOURCE))
                .post("/").then().statusCode(200);
        return "arn:aws:iam::" + TARGET + ":role/" + name;
    }

    private static String machine(String account, String definition, String type) throws Exception {
        Response response = request(account, "CreateStateMachine", Map.of(
                "name", "credentials-" + UUID.randomUUID(), "definition", definition,
                "type", type, "roleArn", "arn:aws:iam::" + account + ":role/execution"));
        response.then().statusCode(200);
        return response.jsonPath().getString("stateMachineArn");
    }

    private static String start(String machine, String input) throws Exception {
        Response response = request(SOURCE, "StartExecution", Map.of("stateMachineArn", machine, "input", input));
        response.then().statusCode(200);
        return response.jsonPath().getString("executionArn");
    }

    private static Response terminal(String account, String execution) throws Exception {
        for (int i = 0; i < 100; i++) {
            Response response = request(account, "DescribeExecution", Map.of("executionArn", execution));
            response.then().statusCode(200);
            if (!"RUNNING".equals(response.jsonPath().getString("status"))) {
                return response;
            }
            Thread.sleep(50);
        }
        fail("Execution did not terminate: " + execution);
        return null;
    }

    private static Response request(String account, String action, Map<String, ?> input) throws Exception {
        return given().header("Authorization", auth(account, "states"))
                .header("X-Amz-Target", "AWSStepFunctions." + action)
                .contentType("application/x-amz-json-1.0").body(MAPPER.writeValueAsString(input)).post("/");
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20261012/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
