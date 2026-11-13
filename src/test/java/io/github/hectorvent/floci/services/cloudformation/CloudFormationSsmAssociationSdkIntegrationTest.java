package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.AssociationDescription;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.ssm.model.Target;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationSsmAssociationSdkIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "840011110010";

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void stackCreatesRealAssociationFromDocumentReferenceAndDeletesBackingState(String region) throws Exception {
        try (Scenario scenario = new Scenario(region)) {
            scenario.create("CREATE_COMPLETE");
            String id = scenario.output("AssociationRef");
            assertTrue(id.matches("[0-9a-f-]{36}"), "Ref must identify a real SSM association: " + id);
            assertEquals(id, scenario.output("AssociationAttribute"));
            assertEquals(id, scenario.cfn.describeStackResource(request -> request.stackName(scenario.stackName)
                    .logicalResourceId("Registration")).stackResourceDetail().physicalResourceId());
            String document = scenario.output("DocumentName");
            AssociationDescription association = scenario.describe(id);
            assertEquals(document, association.name());
            assertEquals(scenario.stackName + "-inventory", association.associationName());
            assertEquals("$DEFAULT", association.documentVersion());
            assertEquals("rate(12 hours)", association.scheduleExpression());
            assertEquals(Map.of("classification", List.of(region)), association.parameters());
            assertEquals(List.of(Target.builder().key("tag:InventoryTarget").values(scenario.stackName).build()),
                    association.targets());
            assertEquals("Pending", association.status().nameAsString());
            assertNull(association.lastExecutionDate());
            scenario.delete();
            assertMissingAssociation(scenario.ssm, id);
            assertMissingDocument(scenario.ssm, document);
        }
    }

    @Test
    void documentReplacementRollsBackTheAssociationReferenceAndCanBeRetried() throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            scenario.create("CREATE_COMPLETE");
            String id = scenario.output("AssociationRef");
            String original = scenario.output("DocumentName");
            String replacement = original + "-next";
            String collision = "/" + scenario.stackName;
            scenario.ssm.putParameter(request -> request.name(collision).type("String").value("foreign"));
            try {
                scenario.template.withObject("/Resources/Inventory/Properties").put("Name", replacement);
                ObjectNode blocked = scenario.template.withObject("/Resources").putObject("Blocked");
                blocked.put("Type", "AWS::SSM::Parameter").put("DependsOn", "Registration");
                blocked.putObject("Properties").put("Name", collision).put("Type", "String").put("Value", "replace");
                scenario.update("UPDATE_ROLLBACK_COMPLETE");
                assertEquals(id, scenario.output("AssociationRef"));
                assertEquals(original, scenario.describe(id).name());
                assertMissingDocument(scenario.ssm, replacement);
                assertEquals(original, scenario.ssm.getDocument(request -> request.name(original)).name());
                assertEquals("foreign",
                        scenario.ssm.getParameter(request -> request.name(collision)).parameter().value());

                scenario.template.withObject("/Resources").remove("Blocked");
                scenario.update("UPDATE_COMPLETE");
                assertEquals(id, scenario.output("AssociationRef"));
                assertEquals(replacement, scenario.describe(id).name());
                assertMissingDocument(scenario.ssm, original);
                scenario.delete();
                assertMissingAssociation(scenario.ssm, id);
                assertMissingDocument(scenario.ssm, replacement);
            } finally {
                scenario.ssm.deleteParameter(request -> request.name(collision));
            }
        }
    }

    @Test
    void mutableConfigurationCanBeUpdatedAndRemovedWithoutReplacingTheAssociation() throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            scenario.create("CREATE_COMPLETE");
            String id = scenario.output("AssociationRef");
            ObjectNode properties = scenario.properties();
            properties.put("AssociationName", scenario.stackName + "-changed")
                    .put("ScheduleExpression", "rate(6 hours)")
                    .put("MaxErrors", "2").put("MaxConcurrency", "50%").put("ComplianceSeverity", "LOW");
            properties.putObject("Parameters").putArray("classification").add("changed");
            scenario.update("UPDATE_COMPLETE");
            AssociationDescription changed = scenario.describe(id);
            assertEquals(id, scenario.output("AssociationRef"));
            assertEquals(scenario.stackName + "-changed", changed.associationName());
            assertEquals("rate(6 hours)", changed.scheduleExpression());
            assertEquals(Map.of("classification", List.of("changed")), changed.parameters());
            assertEquals("2", changed.maxErrors());
            assertEquals("50%", changed.maxConcurrency());
            assertEquals("LOW", changed.complianceSeverityAsString());

            for (String property : List.of("AssociationName", "ScheduleExpression", "Parameters",
                    "MaxErrors", "MaxConcurrency", "ComplianceSeverity")) {
                properties.putObject(property).putArray("Fn::If")
                        .add("OmitOptional").add("unused").addObject().put("Ref", "AWS::NoValue");
            }
            scenario.update("UPDATE_COMPLETE");
            AssociationDescription cleared = scenario.describe(id);
            assertEquals(id, scenario.output("AssociationRef"));
            assertNull(cleared.associationName());
            assertNull(cleared.scheduleExpression());
            assertTrue(cleared.parameters().isEmpty());
            assertNull(cleared.maxErrors());
            assertNull(cleared.maxConcurrency());
            assertNull(cleared.complianceSeverity());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changingLegacyInstanceIdReplacesOnlyTheOwnedAssociation(boolean retain) throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            ObjectNode properties = scenario.properties();
            properties.remove(List.of("Targets", "AssociationName", "ScheduleExpression", "Parameters"));
            properties.put("InstanceId", "i-0123456789abcdef0");
            ObjectNode content = scenario.template.withObject("/Resources/Inventory/Properties").putObject("Content");
            content.put("schemaVersion", "1.2").putObject("runtimeConfig").putObject("aws:runShellScript")
                    .putObject("properties").putArray("runCommand").add("printf configured");
            if (retain) {
                scenario.template.withObject("/Resources/Registration").put("UpdateReplacePolicy", "Retain");
            }
            scenario.create("CREATE_COMPLETE");
            String original = scenario.output("AssociationRef");
            properties.put("InstanceId", "i-0123456789abcdef1");
            scenario.update("UPDATE_COMPLETE");
            String replacement = scenario.output("AssociationRef");
            assertNotEquals(original, replacement);
            assertEquals("i-0123456789abcdef1", scenario.describe(replacement).instanceId());
            if (retain) {
                assertEquals("i-0123456789abcdef0", scenario.describe(original).instanceId());
                scenario.ssm.deleteAssociation(request -> request.associationId(original));
            } else {
                assertMissingAssociation(scenario.ssm, original);
            }
            scenario.delete();
            assertMissingAssociation(scenario.ssm, replacement);
        }
    }

    @Test
    void anUnmanagedAssociationIsNotAdoptedOrDeletedAfterCreateFails() throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            String document = scenario.stackName + "-external";
            scenario.ssm.createDocument(request -> request.name(document).documentType("Command")
                    .content("{\"schemaVersion\":\"2.2\",\"mainSteps\":[]}"));
            String id = scenario.ssm.createAssociation(request -> request.name(document)
                    .targets(Target.builder().key("tag:InventoryTarget").values(scenario.stackName).build()))
                    .associationDescription().associationId();
            scenario.template.withObject("/Resources").remove("Inventory");
            scenario.properties().put("Name", document);
            scenario.properties().remove("Parameters");
            scenario.template.withObject("/Outputs").remove("DocumentName");
            try {
                scenario.create("ROLLBACK_COMPLETE");
                assertEquals(document, scenario.describe(id).name());
                scenario.delete();
                assertEquals(document, scenario.describe(id).name());
            } finally {
                scenario.ssm.deleteAssociation(request -> request.associationId(id));
                scenario.ssm.deleteDocument(request -> request.name(document));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-name", "missing-targets", "invalid-version", "execution-wait", "output-location"})
    void invalidOrUnsupportedConfigurationDoesNotLeaveAnAssociation(String invalid) throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            switch (invalid) {
                case "missing-name" -> scenario.properties().remove("Name");
                case "missing-targets" -> scenario.properties().remove("Targets");
                case "invalid-version" -> scenario.properties().put("DocumentVersion", "999");
                case "execution-wait" -> scenario.properties().put("WaitForSuccessTimeoutSeconds", 15);
                case "output-location" -> scenario.properties().putObject("OutputLocation")
                        .putObject("S3Location").put("OutputS3BucketName", "inventory-output");
                default -> throw new IllegalArgumentException(invalid);
            }
            scenario.create("ROLLBACK_COMPLETE");
            assertFalse(scenario.ssm.listAssociations().associations().stream()
                    .anyMatch(association -> (scenario.stackName + "-inventory").equals(association.associationName())));
        }
    }

    @Test
    void disabledConditionCreatesNeitherTheDocumentNorAssociation() throws Exception {
        try (Scenario scenario = new Scenario("us-east-1")) {
            scenario.template.withObject("/Conditions").putObject("CollectInventory")
                    .putArray("Fn::Equals").add("disabled").add("enabled");
            scenario.template.withObject("/Resources/Inventory").put("Condition", "CollectInventory");
            scenario.template.withObject("/Resources/Registration").put("Condition", "CollectInventory");
            scenario.template.remove("Outputs");
            scenario.create("CREATE_COMPLETE");
            assertTrue(scenario.cfn.listStackResources(request -> request.stackName(scenario.stackName))
                    .stackResourceSummaries().isEmpty());
            assertMissingDocument(scenario.ssm, "inventory-" + scenario.stackName);
        }
    }

    private static void assertMissingAssociation(SsmClient client, String id) {
        SsmException failure = assertThrows(SsmException.class,
                () -> client.describeAssociation(request -> request.associationId(id)));
        assertEquals("AssociationDoesNotExist", failure.awsErrorDetails().errorCode());
    }

    private static void assertMissingDocument(SsmClient client, String name) {
        SsmException failure = assertThrows(SsmException.class,
                () -> client.getDocument(request -> request.name(name)));
        assertEquals("InvalidDocument", failure.awsErrorDetails().errorCode());
    }

    private static final class Scenario implements AutoCloseable {
        private final String stackName = "cfn-association-" + UUID.randomUUID();
        private final CloudFormationClient cfn;
        private final SsmClient ssm;
        private final ObjectNode template;
        private boolean created;

        private Scenario(String region) throws Exception {
            cfn = AwsTestClients.create(CloudFormationClient.builder(), ACCOUNT, Region.of(region));
            ssm = AwsTestClients.create(SsmClient.builder(), ACCOUNT, Region.of(region));
            template = (ObjectNode) MAPPER.readTree("""
                    {
                      "Conditions": {
                        "UseInventoryTag": {"Fn::Equals": ["enabled", "enabled"]},
                        "OmitOptional": {"Fn::Equals": ["disabled", "enabled"]}
                      },
                      "Resources": {
                        "Inventory": {
                          "Type": "AWS::SSM::Document",
                          "Properties": {
                            "Name": {"Fn::Sub": "inventory-${AWS::StackName}"},
                            "DocumentType": "Command",
                            "Content": {
                              "schemaVersion": "2.2",
                              "parameters": {"classification": {"type": "String", "default": ""}},
                              "mainSteps": [{"action": "aws:softwareInventory", "name": "inventory",
                                            "inputs": {"applications": "Enabled"}}]
                            }
                          }
                        },
                        "Registration": {
                          "Type": "AWS::SSM::Association",
                          "Properties": {
                            "Name": {"Ref": "Inventory"},
                            "AssociationName": {"Fn::Sub": "${AWS::StackName}-inventory"},
                            "ScheduleExpression": "rate(12 hours)",
                            "Parameters": {"classification": [{"Ref": "AWS::Region"}]},
                            "Targets": [{"Fn::If": ["UseInventoryTag",
                              {"Key": "tag:InventoryTarget", "Values": [{"Ref": "AWS::StackName"}]},
                              {"Key": "tag:aws:cloudformation:stack-name", "Values": [{"Ref": "AWS::StackName"}]}]}]
                          }
                        }
                      },
                      "Outputs": {
                        "AssociationRef": {"Value": {"Ref": "Registration"}},
                        "AssociationAttribute": {"Value": {"Fn::GetAtt": ["Registration", "AssociationId"]}},
                        "DocumentName": {"Value": {"Ref": "Inventory"}}
                      }
                    }
                    """);
        }

        private ObjectNode properties() {
            return template.withObject("/Resources/Registration/Properties");
        }

        private void create(String expected) throws Exception {
            cfn.createStack(request -> request.stackName(stackName).templateBody(template.toString()));
            created = true;
            await(expected);
        }

        private void update(String expected) throws Exception {
            cfn.updateStack(request -> request.stackName(stackName).templateBody(template.toString()));
            await(expected);
        }

        private void await(String expected) throws Exception {
            Stack stack = CfnStackWaits.awaitState(Duration.ofSeconds(15),
                    () -> cfn.describeStacks(request -> request.stackName(stackName)).stacks().getFirst(),
                    described -> !described.stackStatusAsString().endsWith("_IN_PROGRESS"),
                    described -> "Stack did not reach " + expected + ": " + stackName);
            assertEquals(expected, stack.stackStatusAsString(), stack.stackStatusReason());
        }

        private String output(String key) {
            return cfn.describeStacks(request -> request.stackName(stackName)).stacks().getFirst().outputs().stream()
                    .filter(output -> key.equals(output.outputKey())).findFirst().orElseThrow().outputValue();
        }

        private AssociationDescription describe(String id) {
            return ssm.describeAssociation(request -> request.associationId(id)).associationDescription();
        }

        private void delete() throws Exception {
            cfn.deleteStack(request -> request.stackName(stackName));
            CfnStackWaits.awaitState(Duration.ofSeconds(15), () -> {
                try {
                    Stack stack = cfn.describeStacks(request -> request.stackName(stackName)).stacks().getFirst();
                    assertNotEquals("DELETE_FAILED", stack.stackStatusAsString(), stack.stackStatusReason());
                    return false;
                } catch (CloudFormationException failure) {
                    assertEquals("ValidationError", failure.awsErrorDetails().errorCode());
                    assertTrue(failure.getMessage().contains("does not exist"), failure.getMessage());
                    return true;
                }
            }, deleted -> deleted, deleted -> "Stack was not deleted: " + stackName);
            created = false;
        }

        @Override
        public void close() throws Exception {
            try {
                if (created) {
                    delete();
                }
            } finally {
                cfn.close();
                ssm.close();
            }
        }
    }
}
