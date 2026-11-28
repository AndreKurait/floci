package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.GetFunctionConfigurationResponse;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationLambdaRecursionConfigIntegrationTest {
    private static final String ACCOUNT = "000000000000";

    @Test
    void nativeCloudFormationReconcilesExplicitValuesOmissionConditionsAndReplacement() {
        String stackName = "recursion-cfn-" + UUID.randomUUID();
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, Region.US_EAST_1);
             LambdaClient lambda = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            cfn.createStack(r -> r.stackName(stackName).templateBody(template("", "", "initial")));
            Stack created = CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");
            String function = output(created, "Name");
            try {
                assertSetting(lambda, function, "Terminate");
                lambda.putFunctionRecursionConfig(r -> r.functionName(function).recursiveLoop("Allow"));
                update(cfn, stackName, template("", "", "never-declared"), "UPDATE_COMPLETE");
                assertSetting(lambda, function, "Allow");
                update(cfn, stackName, template(",\"RecursiveLoop\":\"Terminate\"", "", "explicit"), "UPDATE_COMPLETE");
                assertSetting(lambda, function, "Terminate");
                update(cfn, stackName, template(",\"RecursiveLoop\":\"Allow\"", "", "allow"), "UPDATE_COMPLETE");
                assertSetting(lambda, function, "Allow");
                String noValue = ",\"RecursiveLoop\":{\"Fn::If\":[\"IncludeSetting\",\"Allow\",{\"Ref\":\"AWS::NoValue\"}]}";
                update(cfn, stackName, template(noValue, "", "omitted-by-condition"), "UPDATE_COMPLETE");
                assertSetting(lambda, function, "Terminate");
                lambda.putFunctionRecursionConfig(r -> r.functionName(function).recursiveLoop("Allow"));
                update(cfn, stackName, template("", "", "prior-no-value"), "UPDATE_COMPLETE");
                assertSetting(lambda, function, "Allow");
                assertEquals(function, output(cfn.describeStacks(r -> r.stackName(stackName)).stacks().getFirst(), "Name"));
                String replacement = "recursion-replaced-" + UUID.randomUUID();
                update(cfn, stackName, template(",\"FunctionName\":\"" + replacement + "\",\"RecursiveLoop\":\"Allow\"",
                        "", "replacement"), "UPDATE_COMPLETE");
                assertNotEquals(function, replacement);
                assertEquals(replacement, output(cfn.describeStacks(r -> r.stackName(stackName)).stacks().getFirst(), "Name"));
                assertSetting(lambda, replacement, "Allow");
                assertThrows(ResourceNotFoundException.class, () -> lambda.getFunctionRecursionConfig(r -> r.functionName(function)));
            } finally {
                cfn.deleteStack(r -> r.stackName(stackName));
                CfnStackWaits.awaitStackDeleted(stackName);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"allow\"", "\"\"", "1", "true", "{}", "[]"})
    void invalidSettingCannotChangeActualCodeConfigurationTagsOrRecursionValue(String invalid) {
        String stackName = "rec-invalid-" + UUID.randomUUID();
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, Region.US_EAST_1);
             LambdaClient lambda = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            String original = template(",\"RecursiveLoop\":\"Allow\",\"Tags\":[{\"Key\":\"Owner\",\"Value\":\"original\"}]",
                    "", "original");
            cfn.createStack(r -> r.stackName(stackName).templateBody(original));
            String function = output(CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE"), "Name");
            try {
                GetFunctionConfigurationResponse before = lambda.getFunctionConfiguration(r -> r.functionName(function));
                String attempted = template(",\"RecursiveLoop\":" + invalid
                                + ",\"Tags\":[{\"Key\":\"Owner\",\"Value\":\"changed\"}]", "", "changed")
                        .replace("exports.handler = async () => ({});", "exports.handler = async () => 'changed';");
                try {
                    cfn.updateStack(r -> r.stackName(stackName).templateBody(attempted));
                    String status = CfnStackWaits.awaitTerminal(stackName).status();
                    assertTrue(List.of("UPDATE_FAILED", "UPDATE_ROLLBACK_FAILED", "UPDATE_ROLLBACK_COMPLETE").contains(status), status);
                } catch (CloudFormationException invalidTemplate) {
                    assertEquals(400, invalidTemplate.statusCode());
                }
                GetFunctionConfigurationResponse after = lambda.getFunctionConfiguration(r -> r.functionName(function));
                assertEquals(before.functionArn(), after.functionArn());
                assertEquals(before.codeSha256(), after.codeSha256());
                assertEquals("original", after.description());
                assertEquals(Map.of("Owner", "original"), lambda.listTags(r -> r.resource(after.functionArn())).tags());
                assertSetting(lambda, function, "Allow");
            } finally {
                cfn.deleteStack(r -> r.stackName(stackName));
                CfnStackWaits.awaitStackDeleted(stackName);
            }
        }
    }

    @Test
    void laterResourceFailureReportsExistingUnsupportedFunctionRollbackAndRetainsAppliedSetting() {
        String stackName = "rec-rollback-" + UUID.randomUUID();
        try (CloudFormationClient cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, Region.US_EAST_1);
             LambdaClient lambda = AwsTestClients.create(LambdaClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            cfn.createStack(r -> r.stackName(stackName).templateBody(template(",\"RecursiveLoop\":\"Allow\"", "", "original")));
            String function = output(CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE"), "Name");
            try {
                String later = """
                        ,"Later":{"Type":"AWS::EC2::Instance","DependsOn":"Fn","Properties":{
                          "LaunchTemplate":{"LaunchTemplateId":"lt-0123456789abcdef0","Version":"1"}}}
                        """;
                update(cfn, stackName, template(",\"RecursiveLoop\":\"Terminate\"", later, "applied"), "UPDATE_ROLLBACK_FAILED");
                assertTrue(cfn.describeStackEvents(r -> r.stackName(stackName)).stackEvents().stream().anyMatch(event ->
                        "Fn".equals(event.logicalResourceId()) && event.resourceStatusReason() != null
                                && event.resourceStatusReason().contains("Rollback is not implemented")));
                assertSetting(lambda, function, "Terminate");
                assertEquals("applied", lambda.getFunctionConfiguration(r -> r.functionName(function)).description());
            } finally {
                cfn.deleteStack(r -> r.stackName(stackName));
                CfnStackWaits.awaitStackDeleted(stackName);
            }
        }
    }

    private static void update(CloudFormationClient client, String name, String template, String status) {
        client.updateStack(r -> r.stackName(name).templateBody(template));
        CfnStackWaits.awaitStatus(client, name, status);
    }

    private static String output(Stack stack, String key) {
        return stack.outputs().stream().filter(value -> key.equals(value.outputKey())).findFirst().orElseThrow().outputValue();
    }

    private static void assertSetting(LambdaClient client, String function, String expected) {
        assertEquals(expected, client.getFunctionRecursionConfig(r -> r.functionName(function)).recursiveLoopAsString());
    }

    private static String template(String property, String additionalResource, String description) {
        return """
                {"Conditions":{"IncludeSetting":{"Fn::Equals":["yes","no"]}},"Resources":{
                  "Fn":{"Type":"AWS::Lambda::Function","Properties":{
                    "Runtime":"nodejs22.x","Handler":"index.handler",
                    "Role":"arn:aws:iam::000000000000:role/recursion-cfn",
                    "Code":{"ZipFile":"exports.handler = async () => ({});"},
                    "Description":"%s"%s}}%s},"Outputs":{"Name":{"Value":{"Ref":"Fn"}}}}
                """.formatted(description, property, additionalResource);
    }
}
