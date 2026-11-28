package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeStateMachineResponse;
import software.amazon.awssdk.services.sfn.model.SfnException;
import software.amazon.awssdk.services.sfn.model.TestStateResponse;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class StepFunctionsTestStateSdkIntegrationTest {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final String TRUST = """
            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
             "Principal":{"Service":"states.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
    static final String PASS = "{\"Type\":\"Pass\",\"End\":true}";

    @TestHTTPResource
    URI endpoint;

    private IamClient iam;
    private SfnClient states;
    private String roleName;
    private String roleArn;

    @BeforeEach
    void createAnOwnedExecutionRole() {
        iam = iam(endpoint, "test", "test", "us-east-1");
        states = states(endpoint, "test", "test", "us-east-1");
        roleName = "test-state-" + UUID.randomUUID().toString().substring(0, 12);
        roleArn = iam.createRole(request -> request.roleName(roleName).path("/test-state/")
                .assumeRolePolicyDocument(TRUST)).role().arn();
    }

    @AfterEach
    void deleteTheOwnedRole() {
        try {
            if (roleArn != null) {
                assertEquals(roleArn, iam.getRole(request -> request.roleName(roleName)).role().arn());
                iam.deleteRole(request -> request.roleName(roleName));
                assertThrows(NoSuchEntityException.class, () -> iam.getRole(request -> request.roleName(roleName)));
            }
        } finally {
            if (states != null) {
                states.close();
            }
            if (iam != null) {
                iam.close();
            }
        }
    }

    @Test
    void documentedDebugPassShowsTheActualDataFlow() throws Exception {
        String definition = """
                {"Type":"Pass","InputPath":"$.payload","Parameters":{"data":1},
                 "ResultPath":"$.result","OutputPath":"$.result.data","Next":"Another State"}""";
        String input = "{\"payload\":{\"foo\":\"bar\"}}";
        TestStateResponse result = states.testState(request -> request.definition(definition)
                .roleArn(roleArn).input(input).inspectionLevel("DEBUG"));
        assertEquals("SUCCEEDED", result.statusAsString());
        assertEquals("1", result.output());
        assertEquals("Another State", result.nextState());
        assertJson(input, result.inspectionData().input());
        assertJson("{\"foo\":\"bar\"}", result.inspectionData().afterInputPath());
        assertJson("{\"data\":1}", result.inspectionData().afterParameters());
        assertJson("{\"data\":1}", result.inspectionData().afterResultSelector());
        assertJson("{\"payload\":{\"foo\":\"bar\"},\"result\":{\"data\":1}}",
                result.inspectionData().afterResultPath());
        assertNull(result.error());
    }

    @Test
    void literalResultAndResultPathPreserveTheOriginalInput() throws Exception {
        TestStateResponse result = run("""
                {"Type":"Pass","InputPath":"$.selected","Result":{"values":[1,true,null]},
                 "ResultPath":"$.added","End":true}""", "{\"selected\":{},\"keep\":7}");
        assertJson("{\"selected\":{},\"keep\":7,\"added\":{\"values\":[1,true,null]}}", result.output());
        assertNull(result.nextState());
        assertNull(result.inspectionData());
    }

    @Test
    void nullResultPathDiscardsTheResult() throws Exception {
        assertJson("{\"keep\":7}", run("""
                {"Type":"Pass","Result":{"discard":true},"ResultPath":null,"End":true}""",
                "{\"keep\":7}").output());
    }

    @Test
    void parametersResolvePathsAndIntrinsicsWithoutInterpretingLiteralDollarStrings() throws Exception {
        TestStateResponse result = run("""
                {"Type":"Pass","Parameters":{"picked.$":"$.value","count.$":"States.ArrayLength($.items)",
                 "literal":"$$.Execution.Id","nested":{"Assign":"ordinary data"}},"End":true}""",
                "{\"value\":3,\"items\":[1,2]}");
        assertJson("""
                {"picked":3,"count":2,"literal":"$$.Execution.Id","nested":{"Assign":"ordinary data"}}""",
                result.output());
    }

    @Test
    void debugCapturesTheSameIntrinsicEvaluationThatProducesTheOutput() throws Exception {
        TestStateResponse result = states.testState(request -> request.roleArn(roleArn).inspectionLevel("DEBUG")
                .definition("{\"Type\":\"Pass\",\"Parameters\":{\"id.$\":\"States.UUID()\"},\"End\":true}"));
        JsonNode output = MAPPER.readTree(result.output());
        assertNotNull(UUID.fromString(output.path("id").asText()));
        assertEquals(output, MAPPER.readTree(result.inspectionData().afterParameters()));
        assertEquals(output, MAPPER.readTree(result.inspectionData().afterResultSelector()));
        assertEquals(output, MAPPER.readTree(result.inspectionData().afterResultPath()));
    }

    @ParameterizedTest
    @CsvSource({"7,Matched", "9,Other"})
    void choiceReturnsItsSelectedTransitionWithoutFollowingIt(int value, String next) throws Exception {
        String input = "{\"value\":" + value + "}";
        TestStateResponse result = run("""
                {"Type":"Choice","Choices":[{"Variable":"$.value","NumericEquals":7,"Next":"Matched"}],
                 "Default":"Other"}""", input);
        assertEquals(next, result.nextState());
        assertJson(input, result.output());
    }

    @Test
    void succeedAppliesInputAndOutputPaths() throws Exception {
        TestStateResponse result = run("""
                {"Type":"Succeed","InputPath":"$.payload","OutputPath":"$.answer"}""",
                "{\"payload\":{\"answer\":42},\"unused\":true}");
        assertJson("42", result.output());
        assertNull(result.nextState());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failReturnsTheLiteralOrDynamicErrorAsAStateResult(boolean dynamic) {
        String definition = dynamic
                ? "{\"Type\":\"Fail\",\"ErrorPath\":\"$.error\",\"CausePath\":\"$.cause\"}"
                : "{\"Type\":\"Fail\",\"Error\":\"OwnedFailure\",\"Cause\":\"Owned cause\"}";
        TestStateResponse result = states.testState(request -> request.definition(definition).roleArn(roleArn)
                .input("{\"error\":\"OwnedFailure\",\"cause\":\"Owned cause\"}"));
        assertEquals(200, result.sdkHttpResponse().statusCode());
        assertEquals("FAILED", result.statusAsString());
        assertEquals("OwnedFailure", result.error());
        assertEquals("Owned cause", result.cause());
        assertNull(result.output());
        assertNull(result.nextState());
    }

    @Test
    void selectingAStateDoesNotRunStartAtOrChangeStoredMachines() throws Exception {
        String definition = """
                {"StartAt":"Start","States":{
                 "Start":{"Type":"Choice","Choices":[{"Variable":"$.flag","BooleanEquals":true,"Next":"Pick"}],
                          "Default":"Other"},
                 "Pick":{"Type":"Pass","Result":{"selected":"Pick"},"End":true},
                 "Other":{"Type":"Succeed"}}}""";
        String machine = states.createStateMachine(request -> request.name(roleName).roleArn(roleArn)
                .definition(definition)).stateMachineArn();
        try {
            DescribeStateMachineResponse before = states.describeStateMachine(request -> request.stateMachineArn(machine));
            assertTrue(states.listExecutions(request -> request.stateMachineArn(machine)).executions().isEmpty());
            for (int attempt = 0; attempt < 2; attempt++) {
                TestStateResponse result = states.testState(request -> request.definition(definition)
                        .stateName("Pick").roleArn(roleArn).input("{\"flag\":false}"));
                assertEquals("SUCCEEDED", result.statusAsString());
                assertJson("{\"selected\":\"Pick\"}", result.output());
            }
            DescribeStateMachineResponse after = states.describeStateMachine(request -> request.stateMachineArn(machine));
            assertEquals(before.definition(), after.definition());
            assertEquals(before.roleArn(), after.roleArn());
            assertEquals(before.creationDate(), after.creationDate());
            assertEquals(before.revisionId(), after.revisionId());
            assertTrue(states.listExecutions(request -> request.stateMachineArn(machine)).executions().isEmpty());
        } finally {
            states.deleteStateMachine(request -> request.stateMachineArn(machine));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "42", "\"literal\"", "[1,null,true]"})
    void scalarAndNullInputsRemainJsonValues(String input) throws Exception {
        assertJson(input, run(PASS, input).output());
    }

    @Test
    void omittedInputDefaultsToAnEmptyObject() throws Exception {
        TestStateResponse result = states.testState(request -> request.definition(PASS).roleArn(roleArn));
        assertEquals("SUCCEEDED", result.statusAsString());
        assertJson("{}", result.output());
    }

    @Test
    void runtimeFailureKeepsOnlyCompletedDebugStagesAndDoesNotPoisonTheNextRequest() throws Exception {
        TestStateResponse failed = states.testState(request -> request.roleArn(roleArn).input("{\"value\":3}")
                .definition("{\"Type\":\"Pass\",\"Parameters\":{\"missing.$\":\"$.missing\"},\"End\":true}")
                .inspectionLevel("DEBUG"));
        assertEquals("FAILED", failed.statusAsString());
        assertEquals("States.Runtime", failed.error());
        assertJson("{\"value\":3}", failed.inspectionData().afterInputPath());
        assertNull(failed.inspectionData().afterParameters());
        assertNull(failed.inspectionData().afterResultPath());
        assertJson("{\"fresh\":true}", run(PASS, "{\"fresh\":true}").output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{} {}", ""})
    void malformedInputIsAnApiError(String input) {
        SfnException error = assertThrows(SfnException.class, () -> run(PASS, input));
        assertEquals("InvalidExecutionInput", error.awsErrorDetails().errorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "{} {}"})
    void malformedDefinitionIsAnApiError(String definition) {
        SfnException error = assertThrows(SfnException.class, () -> run(definition, "{}"));
        assertEquals("InvalidDefinition", error.awsErrorDetails().errorCode());
    }

    @Test
    void aNonmockedRequestRequiresAnExecutionRole() {
        SfnException error = assertThrows(SfnException.class,
                () -> states.testState(request -> request.definition(PASS).input("{}")));
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"Type\":\"Task\",\"Resource\":\"arn:aws:states:::sqs:sendMessage\",\"End\":true}",
            "{\"Type\":\"Wait\",\"Seconds\":1,\"End\":true}",
            "{\"Type\":\"Map\",\"ItemProcessor\":{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Succeed\"}}},\"End\":true}",
            "{\"Type\":\"Parallel\",\"Branches\":[{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Succeed\"}}}],\"End\":true}"
    })
    void unsupportedStateTypesNeverPretendToExecute(String definition) {
        SfnException error = assertThrows(SfnException.class, () -> run(definition, "{}"));
        assertEquals("UnsupportedOperation", error.awsErrorDetails().errorCode());
    }

    @Test
    void traceIsRefusedForPureStates() {
        SfnException error = assertThrows(SfnException.class, () -> states.testState(request -> request.definition(PASS)
                .roleArn(roleArn).inspectionLevel("TRACE")));
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"Type\":\"Pass\",\"QueryLanguage\":\"JSONata\",\"Output\":\"{% 1 %}\",\"End\":true}",
            "{\"Type\":\"Pass\",\"Assign\":{\"value\":1},\"End\":true}",
            "{\"Type\":\"Pass\",\"Parameters\":{\"context.$\":\"$$.Execution.Id\"},\"End\":true}"
    })
    void unsupportedProcessingIsExplicit(String definition) {
        SfnException error = assertThrows(SfnException.class, () -> run(definition, "{}"));
        assertEquals("UnsupportedOperation", error.awsErrorDetails().errorCode());
    }

    @Test
    void variablesCannotBeSilentlyIgnored() {
        SfnException error = assertThrows(SfnException.class, () -> states.testState(request -> request.definition(PASS)
                .roleArn(roleArn).variables("{\"value\":1}")));
        assertEquals("UnsupportedOperation", error.awsErrorDetails().errorCode());
    }

    private TestStateResponse run(String definition, String input) {
        TestStateResponse result = states.testState(request -> request.definition(definition).roleArn(roleArn).input(input));
        assertEquals("SUCCEEDED", result.statusAsString(), result.toString());
        return result;
    }

    static void assertJson(String expected, String actual) throws Exception {
        JsonNode expectedNode = MAPPER.readTree(expected);
        assertNotNull(actual);
        assertEquals(expectedNode, MAPPER.readTree(actual));
    }

    static SfnClient states(URI endpoint, String accessKey, String secret, String region) {
        return SfnClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .overrideConfiguration(config -> config.putAdvancedOption(
                        SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION, true))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret)))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    static IamClient iam(URI endpoint, String accessKey, String secret, String region) {
        return IamClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret)))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
