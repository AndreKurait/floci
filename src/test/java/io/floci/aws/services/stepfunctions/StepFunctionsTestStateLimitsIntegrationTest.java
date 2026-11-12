package io.floci.aws.services.stepfunctions;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.SfnException;
import software.amazon.awssdk.services.sfn.model.TestStateRequest;
import software.amazon.awssdk.services.sfn.model.TestStateResponse;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.services.stepfunctions.StepFunctionsTestStateSdkIntegrationTest.MAPPER;
import static io.floci.aws.services.stepfunctions.StepFunctionsTestStateSdkIntegrationTest.PASS;
import static io.floci.aws.services.stepfunctions.StepFunctionsTestStateSdkIntegrationTest.TRUST;
import static io.floci.aws.services.stepfunctions.StepFunctionsTestStateSdkIntegrationTest.assertJson;
import static io.floci.aws.services.stepfunctions.StepFunctionsTestStateSdkIntegrationTest.iam;
import static io.floci.aws.services.stepfunctions.StepFunctionsTestStateSdkIntegrationTest.states;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class StepFunctionsTestStateLimitsIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    private IamClient admin;
    private SfnClient client;
    private String name;
    private String roleArn;

    @BeforeEach
    void createAnOwnedRoleAndLocalClients() {
        admin = iam(endpoint, "test", "test", "us-east-1");
        client = states(endpoint, "test", "test", "us-east-1");
        name = "test-state-limits-" + UUID.randomUUID().toString().substring(0, 12);
        roleArn = admin.createRole(request -> request.roleName(name).path("/test-state/")
                .assumeRolePolicyDocument(TRUST)).role().arn();
    }

    @AfterEach
    void removeTheExactOwnedRole() {
        try {
            if (roleArn != null) {
                assertEquals(roleArn, admin.getRole(request -> request.roleName(name)).role().arn());
                admin.deleteRole(request -> request.roleName(name));
                assertThrows(NoSuchEntityException.class, () -> admin.getRole(request -> request.roleName(name)));
            }
        } finally {
            if (client != null) {
                client.close();
            }
            if (admin != null) {
                admin.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"definition", "input", "roleArn", "stateName"})
    void apiStringLimitsAreEnforcedWithNativeSdkRequests(String field) {
        TestStateRequest.Builder request = TestStateRequest.builder().definition(PASS).roleArn(roleArn).input("{}");
        switch (field) {
            case "definition" -> request.definition(" ".repeat(1048577));
            case "input" -> request.input(" ".repeat(262145));
            case "roleArn" -> request.roleArn("x".repeat(257));
            case "stateName" -> request.stateName("x".repeat(81));
            default -> throw new IllegalArgumentException(field);
        }
        SfnException error = assertThrows(SfnException.class, () -> client.testState(request.build()));
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
    }

    @ParameterizedTest
    @CsvSource({"262142,true", "262143,false"})
    void outputLimitIncludesTheJsonStringQuotes(int length, boolean allowed) {
        ObjectNode definition = MAPPER.createObjectNode().put("Type", "Pass").put("Result", "x".repeat(length))
                .put("End", true);
        TestStateResponse result = client.testState(request -> request.definition(definition.toString()).roleArn(roleArn));
        assertEquals(allowed ? "SUCCEEDED" : "FAILED", result.statusAsString());
        if (allowed) {
            assertEquals(length + 2, result.output().length());
        } else {
            assertEquals("States.DataLimitExceeded", result.error());
            assertNull(result.output());
        }
    }

    @Test
    void outputLimitCountsUtf8Bytes() {
        ObjectNode definition = MAPPER.createObjectNode().put("Type", "Pass").put("Result", "漢".repeat(90000))
                .put("End", true);
        TestStateResponse result = client.testState(request -> request.definition(definition.toString()).roleArn(roleArn));
        assertEquals("FAILED", result.statusAsString());
        assertEquals("States.DataLimitExceeded", result.error());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"Type\":\"Pass\",\"QueryLanguage\":17,\"End\":true}",
            "{\"Type\":\"Pass\",\"QueryLanguage\":\"unknown\",\"End\":true}",
            "{\"Type\":\"Pass\",\"Next\":17}",
            "{\"Type\":\"Choice\",\"Choices\":{}}",
            "{\"Type\":\"Choice\",\"Choices\":[{\"Variable\":\"$.x\",\"NumericEquals\":\"7\",\"Next\":\"Next\"}]}",
            "{\"Type\":\"Fail\",\"Error\":\"literal\",\"ErrorPath\":\"$.x\"}",
            "{\"Type\":\"Fail\",\"Cause\":17}",
            "{\"Type\":\"Fail\",\"Error\":17}",
            "{\"Type\":\"Fail\",\"Error\":{}}",
            "{\"Type\":\"Fail\",\"Error\":[]}",
            "{\"Type\":\"Fail\",\"Error\":null}",
            "{\"Type\":\"Fail\",\"Error\":false}",
            "{\"Type\":\"Fail\",\"Cause\":{}}",
            "{\"Type\":\"Fail\",\"Cause\":[]}",
            "{\"Type\":\"Fail\",\"Cause\":null}",
            "{\"Type\":\"Fail\",\"Cause\":false}",
            "{\"Type\":\"Pass\",\"End\":true,\"UnknownMember\":true}"
    })
    void invalidStateDefinitionsRemainApiFailures(String definition) {
        SfnException error = assertThrows(SfnException.class,
                () -> client.testState(request -> request.definition(definition).roleArn(roleArn)));
        assertEquals("InvalidDefinition", error.awsErrorDetails().errorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"States\":{\"Pick\":{\"Type\":\"Pass\",\"End\":true}}}",
            "{\"StartAt\":\"Missing\",\"States\":{\"Pick\":{\"Type\":\"Pass\",\"End\":true}}}",
            "{\"StartAt\":\"Pick\",\"States\":{\"Pick\":{\"Type\":\"Pass\",\"Next\":\"Missing\"}}}"
    })
    void selectingAStateStillValidatesTheWholeDefinition(String definition) {
        SfnException error = assertThrows(SfnException.class, () -> client.testState(request -> request
                .definition(definition).stateName("Pick").roleArn(roleArn)));
        assertEquals("InvalidDefinition", error.awsErrorDetails().errorCode());
    }

    @Test
    void stateNameMustResolveAtTheTopLevel() {
        String definition = "{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Succeed\"}}}";
        SfnException error = assertThrows(SfnException.class, () -> client.testState(request -> request
                .definition(definition).stateName("Missing").roleArn(roleArn)));
        assertEquals("InvalidDefinition", error.awsErrorDetails().errorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"InputPath", "OutputPath"})
    void nullInputAndOutputPathsProduceAnEmptyObject(String field) throws Exception {
        ObjectNode definition = MAPPER.createObjectNode().put("Type", "Pass").put("End", true);
        definition.putNull(field);
        TestStateResponse result = client.testState(request -> request.definition(definition.toString())
                .roleArn(roleArn).input("{\"discarded\":true}"));
        assertEquals("SUCCEEDED", result.statusAsString());
        assertJson("{}", result.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-path", "missing", "wrong-account", "wrong-partition"})
    void exactRoleIdentityIsRequiredEvenWithIamEnforcementDisabled(String mode) {
        String selected = switch (mode) {
            case "wrong-path" -> roleArn.replace("/test-state/", "/other-path/");
            case "missing" -> roleArn + "-missing";
            case "wrong-account" -> roleArn.replace(":000000000000:", ":111111111111:");
            case "wrong-partition" -> roleArn.replace("arn:aws:", "arn:aws-cn:");
            default -> throw new IllegalArgumentException(mode);
        };
        assertNotEquals(roleArn, selected);
        SfnException error = assertThrows(SfnException.class,
                () -> client.testState(request -> request.definition(PASS).roleArn(selected)));
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"explicit-deny", "source-arn", "source-account"})
    void testStateDoesNotInventTrustContextForItsExecutionRole(String mode) {
        String document = switch (mode) {
            case "explicit-deny" -> """
                    {"Version":"2012-10-17","Statement":[
                     {"Effect":"Allow","Principal":{"Service":"states.amazonaws.com"},"Action":"sts:AssumeRole"},
                     {"Effect":"Deny","Principal":{"Service":"states.amazonaws.com"},"Action":"sts:AssumeRole"}]}""";
            case "source-arn" -> """
                    {"Version":"2012-10-17","Statement":[
                     {"Effect":"Allow","Principal":{"Service":"states.amazonaws.com"},"Action":"sts:AssumeRole",
                      "Condition":{"ArnLike":{"aws:SourceArn":"arn:aws:states:us-east-1:000000000000:stateMachine:*"}}}]}""";
            case "source-account" -> """
                    {"Version":"2012-10-17","Statement":[
                     {"Effect":"Allow","Principal":{"Service":"states.amazonaws.com"},"Action":"sts:AssumeRole",
                      "Condition":{"StringEquals":{"aws:SourceAccount":"000000000000"}}}]}""";
            default -> throw new IllegalArgumentException(mode);
        };
        admin.updateAssumeRolePolicy(request -> request.roleName(name).policyDocument(document));
        SfnException error = assertThrows(SfnException.class,
                () -> client.testState(request -> request.definition(PASS).roleArn(roleArn)));
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
        admin.updateAssumeRolePolicy(request -> request.roleName(name).policyDocument(TRUST));
        assertEquals("SUCCEEDED", client.testState(request -> request.definition(PASS).roleArn(roleArn)).statusAsString());
    }
}
