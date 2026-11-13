package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StepFunctionsPureTestStateTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROLE = "arn:aws:iam::000000000000:role/test-state/owned";
    private static final String PASS = "{\"Type\":\"Pass\",\"End\":true}";

    @ParameterizedTest
    @CsvSource({"definition,17", "input,17", "roleArn,17", "stateName,17",
            "inspectionLevel,17", "revealSecrets,17"})
    void apiMembersKeepTheirDeclaredTypes(String member, int value) {
        ObjectNode request = request(PASS);
        request.put(member, value);
        assertCode("ValidationException", () -> TestStateDefinition.prepare(request, MAPPER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"definition", "roleArn", "stateName", "inspectionLevel"})
    void emptyRequiredOrBoundedMembersAreRejected(String member) {
        ObjectNode request = request(PASS);
        request.put(member, "");
        assertCode("ValidationException", () -> TestStateDefinition.prepare(request, MAPPER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mock", "variables", "context", "stateConfiguration"})
    void unsupportedRequestProcessingIsNeverIgnored(String member) {
        ObjectNode request = request(PASS);
        request.set(member, MAPPER.createObjectNode());
        assertCode("UnsupportedOperation", () -> TestStateDefinition.prepare(request, MAPPER));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"Type\":\"Pass\",\"Parameters\":{\"x\":1,\"x.$\":\"$.value\"},\"End\":true}",
            "{\"Type\":\"Pass\",\"Parameters\":{\"nested\":[{\"x.$\":7}]},\"End\":true}",
            "{\"Type\":\"Pass\",\"Parameters\":[],\"End\":true}",
            "{\"Type\":\"Pass\",\"ResultSelector\":{},\"End\":true}",
            "{\"Type\":\"Succeed\",\"Next\":\"Another\"}",
            "{\"Type\":\"Fail\",\"InputPath\":\"$\"}",
            "{\"Type\":\"Pass\",\"InputPath\":17,\"End\":true}",
            "{\"Type\":\"Pass\",\"End\":false}",
            "{\"Type\":\"Pass\",\"End\":true,\"Next\":\"Another\"}"
    })
    void malformedPureStateFieldsAreRefused(String definition) {
        TestStateDefinition.Prepared prepared = TestStateDefinition.prepare(request(definition), MAPPER);
        assertCode("InvalidDefinition", () -> TestStateDefinition.validateSupported(prepared));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"Type\":\"Pass\",\"Parameters\":{\"nested\":[{\"x.$\":\"$secret.value\"}]},\"End\":true}",
            "{\"Type\":\"Pass\",\"Parameters\":{\"x.$\":\"States.Format('{}', $$.Execution.Id)\"},\"End\":true}",
            "{\"Type\":\"Choice\",\"Choices\":[{\"And\":[{\"Variable\":\"$secret\",\"IsPresent\":true}],\"Next\":\"Next\"}]}",
            "{\"Type\":\"Choice\",\"Choices\":[{\"Variable\":\"$.x\",\"StringEqualsPath\":\"$secret\",\"Next\":\"Next\"}]}",
            "{\"Type\":\"Fail\",\"ErrorPath\":\"States.Format('{}', $secret)\"}"
    })
    void activeNestedVariablesAndContextAreRefused(String definition) {
        TestStateDefinition.Prepared prepared = TestStateDefinition.prepare(request(definition), MAPPER);
        assertCode("UnsupportedOperation", () -> TestStateDefinition.validateSupported(prepared));
    }

    @Test
    void quotedDollarTextAndPathMembersRemainLiteralData() throws Exception {
        String definition = """
                {"Type":"Pass","Parameters":{
                 "literal":"$$.Execution.Id",
                 "quoted.$":"States.Format('{}', '$secret')",
                 "member.$":"$['$secret']",
                 "nested":[{"Assign":"literal","value.$":"$.number"}]},"End":true}""";
        ObjectNode request = request(definition).put("input", "{\"$secret\":7,\"number\":9}");
        TestStateDefinition.Prepared prepared = TestStateDefinition.prepare(request, MAPPER);
        TestStateDefinition.validateSupported(prepared);
        LambdaTaskAuthorizer authorizer = mock(LambdaTaskAuthorizer.class);
        AslExecutor executor = AslExecutorFixture.builder().lambdaTaskAuthorizer(authorizer).build();
        ObjectNode result = executor.testPureState(prepared, "000000000000", "aws");
        assertEquals("SUCCEEDED", result.path("status").asText(), result.toString());
        assertEquals(MAPPER.readTree("""
                {"literal":"$$.Execution.Id","quoted":"$secret","member":7,
                 "nested":[{"Assign":"literal","value":9}]}"""),
                MAPPER.readTree(result.path("output").asText()));
        verify(authorizer).authorizeTestStateRole(ROLE, "000000000000", "aws");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void inheritedThreadScopeCannotLeakAndIsRestoredAfterEvaluation(boolean fail) throws Exception {
        Field field = AslExecutor.class.getDeclaredField("JSONPATH_SCOPE");
        field.setAccessible(true);
        ThreadLocal<Object> scope = (ThreadLocal<Object>) field.get(null);
        Object original = scope.get();
        Class<?> scopeType = List.of(AslExecutor.class.getDeclaredClasses()).stream()
                .filter(type -> type.getSimpleName().equals("JsonPathScope")).findFirst().orElseThrow();
        Constructor<?> constructor = scopeType.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        ObjectNode oldVariables = MAPPER.createObjectNode().put("secret", "must not leak");
        ObjectNode oldContext = MAPPER.createObjectNode().put("old", "context");
        ObjectNode oldAssignments = MAPPER.createObjectNode().put("pending", 7);
        Object previous = constructor.newInstance(oldVariables, oldContext, oldAssignments);
        scope.set(previous);
        try {
            String definition = fail
                    ? "{\"Type\":\"Pass\",\"Parameters\":{\"x.$\":\"$secret\"},\"End\":true}"
                    : PASS;
            ObjectNode request = request(definition).put("input", "{\"fresh\":true}");
            TestStateDefinition.Prepared prepared = TestStateDefinition.prepare(request, MAPPER);
            LambdaTaskAuthorizer authorizer = mock(LambdaTaskAuthorizer.class);
            LambdaExecutorService lambda = mock(LambdaExecutorService.class);
            SqsJsonHandler sqs = mock(SqsJsonHandler.class);
            AslExecutor executor = AslExecutorFixture.builder().lambdaTaskAuthorizer(authorizer)
                    .lambdaExecutor(lambda).sqsJsonHandler(sqs).build();
            ObjectNode result = executor.testPureState(prepared, "000000000000", "aws");
            assertEquals(fail ? "FAILED" : "SUCCEEDED", result.path("status").asText());
            if (fail) {
                assertEquals("States.Runtime", result.path("error").asText());
                assertTrue(result.path("cause").asText().contains("undefined variable"));
            } else {
                assertEquals("{\"fresh\":true}", result.path("output").asText());
            }
            assertSame(previous, scope.get());
            assertEquals("{\"secret\":\"must not leak\"}", oldVariables.toString());
            assertEquals("{\"old\":\"context\"}", oldContext.toString());
            assertEquals("{\"pending\":7}", oldAssignments.toString());
            assertEquals("{\"fresh\":true}", prepared.input().toString());
            verifyNoInteractions(lambda, sqs);
        } finally {
            if (original == null) {
                scope.remove();
            } else {
                scope.set(original);
            }
        }
    }

    @Test
    void aRoleRefusalPrecedesEvaluationAndPreservesTheThreadScope() throws Exception {
        LambdaTaskAuthorizer authorizer = mock(LambdaTaskAuthorizer.class);
        doThrow(new AwsException("AccessDeniedException", "owned refusal", 400)).when(authorizer)
                .authorizeTestStateRole(ROLE, "000000000000", "aws");
        Field field = AslExecutor.class.getDeclaredField("JSONPATH_SCOPE");
        field.setAccessible(true);
        ThreadLocal<?> scope = (ThreadLocal<?>) field.get(null);
        Object before = scope.get();
        AslExecutor executor = AslExecutorFixture.builder().lambdaTaskAuthorizer(authorizer).build();
        TestStateDefinition.Prepared prepared = TestStateDefinition.prepare(request(PASS), MAPPER);
        assertCode("AccessDeniedException", () -> executor.testPureState(prepared, "000000000000", "aws"));
        assertSame(before, scope.get());
    }

    private static ObjectNode request(String definition) {
        return MAPPER.createObjectNode().put("definition", definition).put("roleArn", ROLE);
    }

    private static void assertCode(String code, Runnable action) {
        AwsException error = assertThrows(AwsException.class, action::run);
        assertEquals(code, error.getErrorCode());
    }
}
