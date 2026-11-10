package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Change;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Address;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationConditionOrderIntegrationTest {
    private final String name = "condition-order-" + UUID.randomUUID();
    private CloudFormationClient cfn;
    private Ec2Client ec2;

    @BeforeEach
    void clients() {
        String account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
        ec2 = AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1);
    }

    @AfterEach
    void cleanup() {
        try {
            cfn.deleteStack(request -> request.stackName(name));
            await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                    .untilAsserted(() -> {
                assertStackAbsent();
                assertTrue(ec2.describeAddresses().addresses().isEmpty(),
                        "Stack deletion must release every conditionally created address");
            });
        } finally {
            ec2.close();
            cfn.close();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,1", "true,false,2", "true,true,3"})
    void forwardConditionReferencesSelectRealResourcesAndOutputs(String two, String three, int count) {
        cfn.createStack(request -> request.stackName(name).templateBody(template())
                .parameters(parameters(two, three)));
        awaitStatus("CREATE_COMPLETE");
        assertAddresses(count);
    }

    @Test
    void updateAndChangeSetReevaluateTheWholeConditionChain() {
        cfn.createStack(request -> request.stackName(name).templateBody(template())
                .parameters(parameters("true", "true")));
        awaitStatus("CREATE_COMPLETE");
        assertAddresses(3);

        cfn.createChangeSet(request -> request.stackName(name).changeSetName("one-zone")
                .changeSetType("UPDATE").templateBody(template()).parameters(parameters("false", "false")));
        await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() ->
                assertEquals("CREATE_COMPLETE", cfn.describeChangeSet(request -> request
                        .stackName(name).changeSetName("one-zone")).statusAsString()));
        List<Change> changes = cfn.describeChangeSet(request -> request
                .stackName(name).changeSetName("one-zone")).changes();
        assertEquals(Set.of("Address2", "Address3"), changes.stream()
                .filter(change -> "Remove".equals(change.resourceChange().actionAsString()))
                .map(change -> change.resourceChange().logicalResourceId()).collect(Collectors.toSet()));
        cfn.executeChangeSet(request -> request.stackName(name).changeSetName("one-zone"));
        awaitStatus("UPDATE_COMPLETE");
        assertAddresses(1);

        cfn.updateStack(request -> request.stackName(name).templateBody(template())
                .parameters(parameters("true", "true")));
        awaitStatus("UPDATE_COMPLETE");
        assertAddresses(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"A\":{\"Condition\":\"Missing\"}",
            "\"A\":{\"Condition\":\"A\"}",
            "\"A\":{\"Condition\":\"B\"},\"B\":{\"Condition\":\"A\"}",
            "\"A\":{\"Fn::And\":[{\"Fn::Equals\":[\"a\",\"b\"]},{\"Condition\":\"B\"}]},"
                    + "\"B\":{\"Condition\":\"A\"}"
    })
    void invalidConditionGraphsFailBeforeCreatingTheStackOrResources(String conditions) {
        CloudFormationException error = assertThrows(CloudFormationException.class, () ->
                cfn.createStack(request -> request.stackName(name).templateBody("""
                        {"Conditions":{%s},"Resources":{"Address":{"Type":"AWS::EC2::EIP"}}}
                        """.formatted(conditions))));
        assertEquals("ValidationError", error.awsErrorDetails().errorCode());
        assertTrue(error.getMessage().contains(conditions.contains("Missing") ? "Missing" : "A"));
        assertStackAbsent();
        assertTrue(ec2.describeAddresses().addresses().isEmpty());
    }

    @Test
    void aLongReverseDeclaredConditionChainResolvesWithoutRepeatedPasses() throws Exception {
        ObjectNode template = (ObjectNode) new ObjectMapper().readTree("""
                {"Conditions":{},"Resources":{"Address":{"Type":"AWS::EC2::EIP","Condition":"C0"}}}
                """);
        ObjectNode conditions = template.withObject("/Conditions");
        for (int index = 0; index < 199; index++) {
            conditions.putObject("C" + index).put("Condition", "C" + (index + 1));
        }
        conditions.putObject("C199").putArray("Fn::Equals").add("enabled").add("enabled");
        cfn.createStack(request -> request.stackName(name).templateBody(template.toString()));
        awaitStatus("CREATE_COMPLETE");
        assertEquals(1, ec2.describeAddresses().addresses().size());
    }

    private void assertAddresses(int count) {
        Stack stack = cfn.describeStacks(request -> request.stackName(name)).stacks().getFirst();
        Map<String, String> outputs = stack.outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));
        assertEquals(Integer.toString(count), outputs.get("Count"));
        assertEquals(Boolean.toString(count != 3), outputs.get("NotThree"));
        Set<String> allocated = ec2.describeAddresses().addresses().stream()
                .map(Address::allocationId).collect(Collectors.toSet());
        assertEquals(count, allocated.size());
        assertEquals(allocated, outputs.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("Allocation"))
                .map(Map.Entry::getValue).collect(Collectors.toSet()),
                "Every output must name an allocation actually created by the EC2 service");
        assertEquals(count, cfn.describeStackResources(request -> request.stackName(name)).stackResources().size());
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() ->
                assertEquals(expected, cfn.describeStacks(request -> request.stackName(name))
                        .stacks().getFirst().stackStatusAsString()));
    }

    private void assertStackAbsent() {
        CloudFormationException absent = assertThrows(CloudFormationException.class,
                () -> cfn.describeStacks(request -> request.stackName(name)));
        assertEquals("ValidationError", absent.awsErrorDetails().errorCode());
        assertTrue(absent.getMessage().contains("does not exist"));
    }

    private static List<Parameter> parameters(String two, String three) {
        return List.of(Parameter.builder().parameterKey("TwoZones").parameterValue(two).build(),
                Parameter.builder().parameterKey("ThreeZones").parameterValue(three).build());
    }

    private static String template() {
        return """
                {
                  "Parameters": {
                    "TwoZones": {"Type":"String"},
                    "ThreeZones": {"Type":"String"}
                  },
                  "Conditions": {
                    "NotThree": {"Fn::Not":[{"Condition":"Third"}]},
                    "Third": {"Fn::And":[{"Condition":"Second"},{"Condition":"HasThreeZones"}]},
                    "Second": {"Fn::And":[{"Condition":"Managed"},{"Condition":"HasTwoZones"}]},
                    "Managed": {"Fn::Equals":["enabled","enabled"]},
                    "HasThreeZones": {"Fn::Equals":[{"Ref":"ThreeZones"},"true"]},
                    "HasTwoZones": {"Fn::Equals":[{"Ref":"TwoZones"},"true"]}
                  },
                  "Resources": {
                    "Address1": {"Type":"AWS::EC2::EIP","Condition":"Managed","Properties":{"Domain":"vpc"}},
                    "Address2": {"Type":"AWS::EC2::EIP","Condition":"Second","Properties":{"Domain":"vpc"}},
                    "Address3": {"Type":"AWS::EC2::EIP","Condition":"Third","Properties":{"Domain":"vpc"}}
                  },
                  "Outputs": {
                    "Count":{"Value":{"Fn::If":["Third","3",{"Fn::If":["Second","2","1"]}]}},
                    "NotThree":{"Value":{"Fn::If":["NotThree","true","false"]}},
                    "Allocation1":{"Condition":"Managed","Value":{"Fn::GetAtt":["Address1","AllocationId"]}},
                    "Allocation2":{"Condition":"Second","Value":{"Fn::GetAtt":["Address2","AllocationId"]}},
                    "Allocation3":{"Condition":"Third","Value":{"Fn::GetAtt":["Address3","AllocationId"]}}
                  }
                }
                """;
    }
}
