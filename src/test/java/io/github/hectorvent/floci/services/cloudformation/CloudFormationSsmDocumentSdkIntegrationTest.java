package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetResponse;
import software.amazon.awssdk.services.cloudformation.model.ResourceChange;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetDocumentResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationSsmDocumentSdkIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "830011110005";

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void stackCreatesTheDocumentReturnedByRefAndDeletesItsBackingContent(String region) throws Exception {
        String stackName = "ssm-inventory-" + UUID.randomUUID();
        String documentName = "inventory-" + stackName;
        ObjectNode template = template();
        try (CloudFormationClient cfn = cfn(region); SsmClient ssm = ssm(region)) {
            cfn.createStack(request -> request.stackName(stackName).templateBody(template.toString()));
            try {
                Stack stack = awaitStack(cfn, stackName, "CREATE_COMPLETE");
                GetDocumentResponse document = ssm.getDocument(request -> request.name(documentName));
                assertEquals(documentName, stack.outputs().getFirst().outputValue());
                assertEquals(documentName, cfn.describeStackResource(request -> request.stackName(stackName)
                        .logicalResourceId("Inventory")).stackResourceDetail().physicalResourceId());
                assertEquals(documentName, document.name());
                assertEquals("Command", document.documentTypeAsString());
                assertEquals("1", document.documentVersion());
                JsonNode content = MAPPER.readTree(document.content());
                assertEquals("2.2", content.path("schemaVersion").asText());
                assertEquals("aws:softwareInventory", content.path("mainSteps").get(0).path("action").asText());
                assertEquals("Inventory in " + region, content.path("description").asText());
            } finally {
                deleteStack(cfn, stackName);
            }
            SsmException deleted = assertThrows(SsmException.class,
                    () -> ssm.getDocument(request -> request.name(documentName)));
            assertEquals("InvalidDocument", deleted.awsErrorDetails().errorCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void newVersionsRollbackAndRetryThroughTheSelectedDefault(String region) throws Exception {
        String stackName = "ssm-version-" + UUID.randomUUID();
        String documentName = "inventory-" + stackName;
        String collision = "/" + stackName;
        ObjectNode template = template();
        properties(template).put("UpdateMethod", "NewVersion");
        try (CloudFormationClient cfn = cfn(region); SsmClient ssm = ssm(region)) {
            ssm.putParameter(request -> request.name(collision).type("String").value("foreign"));
            cfn.createStack(request -> request.stackName(stackName).templateBody(template.toString()));
            try {
                awaitStack(cfn, stackName, "CREATE_COMPLETE");
                description(template, "second");
                update(cfn, stackName, template, "UPDATE_COMPLETE");
                assertDocument(ssm, documentName, "$DEFAULT", "2", "second");
                assertEquals("1", ssm.getDocument(request -> request.name(documentName)
                        .documentVersion("1")).documentVersion());

                description(template, "third");
                ObjectNode failed = template.deepCopy();
                ObjectNode blocked = failed.withObject("/Resources").putObject("Blocked");
                blocked.put("Type", "AWS::SSM::Parameter");
                blocked.put("DependsOn", "Inventory");
                blocked.putObject("Properties").put("Name", collision).put("Type", "String").put("Value", "replace");
                update(cfn, stackName, failed, "UPDATE_ROLLBACK_COMPLETE");
                assertDocument(ssm, documentName, "$DEFAULT", "2", "second");
                assertDocument(ssm, documentName, "$LATEST", "3", "third");
                assertEquals("foreign", ssm.getParameter(request -> request.name(collision)).parameter().value());

                update(cfn, stackName, template, "UPDATE_COMPLETE");
                assertDocument(ssm, documentName, "$DEFAULT", "3", "third");
                assertDocument(ssm, documentName, "$LATEST", "3", "third");
                assertEquals(documentName, documentName(cfn, stackName));
            } finally {
                deleteStack(cfn, stackName);
                ssm.deleteParameter(request -> request.name(collision));
            }
            assertMissing(ssm, documentName);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void namedReplacementRequiresANewNameAndDeletesOnlyTheDisplacedDocument(String region) throws Exception {
        String stackName = "ssm-replace-" + UUID.randomUUID();
        String original = "inventory-" + stackName;
        String replacement = original + "-next";
        ObjectNode template = template();
        try (CloudFormationClient cfn = cfn(region); SsmClient ssm = ssm(region)) {
            cfn.createStack(request -> request.stackName(stackName).templateBody(template.toString()));
            try {
                awaitStack(cfn, stackName, "CREATE_COMPLETE");
                description(template, "second");
                update(cfn, stackName, template, "UPDATE_ROLLBACK_COMPLETE");
                assertEquals(original, documentName(cfn, stackName));
                assertDocument(ssm, original, "$DEFAULT", "1", "Inventory in " + region);
                assertTrue(cfn.describeStackEvents(request -> request.stackName(stackName)).stackEvents().stream()
                        .anyMatch(event -> "Inventory".equals(event.logicalResourceId())
                                && event.resourceStatusReason() != null
                                && event.resourceStatusReason().contains("without changing Name")));

                properties(template).put("Name", replacement);
                update(cfn, stackName, template, "UPDATE_COMPLETE");
                assertEquals(replacement, documentName(cfn, stackName));
                assertDocument(ssm, replacement, "$DEFAULT", "1", "second");
                assertMissing(ssm, original);
            } finally {
                deleteStack(cfn, stackName);
            }
            assertMissing(ssm, replacement);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void generatedReplacementHonorsRetentionAndDeletesTheCurrentDocument(boolean retain) throws Exception {
        String stackName = "ssm-generated-" + UUID.randomUUID();
        ObjectNode template = template();
        properties(template).remove("Name");
        if (retain) {
            template.withObject("/Resources/Inventory").put("UpdateReplacePolicy", "Retain");
        }
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            cfn.createStack(request -> request.stackName(stackName).templateBody(template.toString()));
            String original = null;
            String replacement = null;
            try {
                awaitStack(cfn, stackName, "CREATE_COMPLETE");
                original = documentName(cfn, stackName);
                description(template, "second");
                update(cfn, stackName, template, "UPDATE_COMPLETE");
                replacement = documentName(cfn, stackName);
                assertNotEquals(original, replacement);
                assertDocument(ssm, replacement, "$DEFAULT", "1", "second");
                if (retain) {
                    assertDocument(ssm, original, "$DEFAULT", "1", "Inventory in us-east-1");
                } else {
                    assertMissing(ssm, original);
                }
            } finally {
                deleteStack(cfn, stackName);
                if (retain && original != null) {
                    String retained = original;
                    ssm.deleteDocument(request -> request.name(retained));
                }
            }
            assertMissing(ssm, replacement);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void createWithNewVersionDoesNotAdoptOrDeleteAnUnmanagedDocument(String region) throws Exception {
        String stackName = "ssm-collision-" + UUID.randomUUID();
        String documentName = "inventory-" + stackName;
        ObjectNode template = template();
        properties(template).put("UpdateMethod", "NewVersion");
        try (CloudFormationClient cfn = cfn(region); SsmClient ssm = ssm(region)) {
            ssm.createDocument(request -> request.name(documentName).documentType("Command")
                    .content("{\"schemaVersion\":\"2.2\",\"description\":\"foreign\",\"mainSteps\":[]}"));
            try {
                cfn.createStack(request -> request.stackName(stackName).templateBody(template.toString()));
                try {
                    awaitStack(cfn, stackName, "ROLLBACK_COMPLETE");
                    assertDocument(ssm, documentName, "$DEFAULT", "1", "foreign");
                } finally {
                    deleteStack(cfn, stackName);
                }
                assertDocument(ssm, documentName, "$DEFAULT", "1", "foreign");
            } finally {
                ssm.deleteDocument(request -> request.name(documentName));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-content", "invalid-json", "unsupported-format", "unsupported-attachments"})
    void rejectedPropertiesNeverCreateABackingDocument(String invalid) throws Exception {
        String stackName = "ssm-invalid-" + UUID.randomUUID();
        ObjectNode template = template();
        ObjectNode properties = properties(template);
        switch (invalid) {
            case "missing-content" -> properties.remove("Content");
            case "invalid-json" -> properties.put("Content", "not json");
            case "unsupported-format" -> properties.put("DocumentFormat", "YAML");
            case "unsupported-attachments" -> properties.putArray("Attachments").addObject()
                    .put("Key", "S3FileUrl").putArray("Values").add("s3://attachment/input.txt");
            default -> throw new IllegalArgumentException(invalid);
        }
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            cfn.createStack(request -> request.stackName(stackName).templateBody(template.toString()));
            try {
                awaitStack(cfn, stackName, "ROLLBACK_COMPLETE");
                assertMissing(ssm, "inventory-" + stackName);
            } finally {
                deleteStack(cfn, stackName);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "Replace, literal, true",
            "NewVersion, literal, false",
            "omitted, literal, true",
            "Replace, parameter, true",
            "NewVersion, parameter, false",
            "NewVersion, condition, false"
    })
    void changeSetContentModesPreserveThePreviewedIdentity(String method, String expression,
                                                           boolean replacement) throws Exception {
        String stackName = "ssm-preview-" + UUID.randomUUID();
        ObjectNode template = template();
        properties(template).remove("Name");
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            cfn.createStack(request -> request.stackName(stackName).templateBody(template.toString()));
            try {
                awaitStack(cfn, stackName, "CREATE_COMPLETE");
                String original = documentName(cfn, stackName);
                description(template, "second");
                if ("parameter".equals(expression) || "condition".equals(expression)) {
                    template.putObject("Parameters").putObject("Method").put("Type", "String")
                            .put("Default", method);
                    if ("parameter".equals(expression)) {
                        properties(template).putObject("UpdateMethod").put("Ref", "Method");
                    } else {
                        template.putObject("Conditions").putObject("UseNewVersion").putArray("Fn::Equals")
                                .addObject().put("Ref", "Method");
                        template.withArray("/Conditions/UseNewVersion/Fn::Equals").add("NewVersion");
                        properties(template).putObject("UpdateMethod").putArray("Fn::If")
                                .add("UseNewVersion").add("NewVersion").add("Replace");
                    }
                } else if (!"omitted".equals(method)) {
                    properties(template).put("UpdateMethod", method);
                }
                DescribeChangeSetResponse preview = preview(cfn, stackName, template);
                assertEquals(replacement ? "True" : "False", inventoryChange(preview).replacementAsString());
                assertEquals(original, inventoryChange(preview).physicalResourceId());
                assertDocument(ssm, original, "$DEFAULT", "1", "Inventory in us-east-1");
                execute(cfn, stackName, preview);
                assertUpdatedIdentity(cfn, ssm, stackName, original, replacement);
            } finally {
                deleteStack(cfn, stackName);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Replace", "NewVersion"})
    void outputSelectedUpdateMethodHasConditionalReplacement(String method) throws Exception {
        String stackName = "ssm-dynamic-" + UUID.randomUUID();
        String producer = stackName + "-mode";
        ObjectNode producerTemplate = template();
        properties(producerTemplate).put("Name", producer);
        producerTemplate.putObject("Outputs").putObject("Method").put("Value", method);
        ObjectNode template = template();
        properties(template).remove("Name");
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            cfn.createStack(request -> request.stackName(producer).templateBody(producerTemplate.toString()));
            try {
                awaitStack(cfn, producer, "CREATE_COMPLETE");
                cfn.createStack(request -> request.stackName(stackName).templateBody(template.toString()));
                try {
                    awaitStack(cfn, stackName, "CREATE_COMPLETE");
                    String original = documentName(cfn, stackName);
                    description(template, "second");
                    properties(template).putObject("UpdateMethod").putObject("Fn::GetStackOutput")
                            .put("StackName", producer).put("OutputName", "Method");
                    DescribeChangeSetResponse preview = preview(cfn, stackName, template);
                    assertEquals("Conditional", inventoryChange(preview).replacementAsString());
                    assertDocument(ssm, original, "$DEFAULT", "1", "Inventory in us-east-1");
                    execute(cfn, stackName, preview);
                    assertUpdatedIdentity(cfn, ssm, stackName, original, "Replace".equals(method));
                } finally {
                    deleteStack(cfn, stackName);
                }
            } finally {
                deleteStack(cfn, producer);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "Replace, Ref, True",
            "NewVersion, Ref, False",
            "Replace, GetAtt, Conditional",
            "NewVersion, GetAtt, False"
    })
    void referencedContentPreservesTheUpdateMethod(String method, String reference,
                                                  String replacement) throws Exception {
        String stackName = "ssm-reference-" + UUID.randomUUID();
        String parameterName = "/" + stackName;
        boolean replacesParameter = "Ref".equals(reference);
        String before = replacesParameter ? parameterName : "first";
        String after = replacesParameter ? parameterName + "-next" : "second";
        ObjectNode template = template();
        properties(template).remove("Name");
        properties(template).put("UpdateMethod", method);
        ObjectNode source = template.withObject("/Resources").putObject("Source");
        source.put("Type", "AWS::SSM::Parameter");
        ObjectNode parameter = source.putObject("Properties")
                .put("Name", parameterName).put("Type", "String").put("Value", "first");
        ObjectNode description = properties(template).withObject("/Content").putObject("description");
        if (replacesParameter) {
            description.put("Ref", "Source");
        } else {
            description.putArray("Fn::GetAtt").add("Source").add("Value");
        }
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            cfn.createStack(request -> request.stackName(stackName).templateBody(template.toString()));
            String current = null;
            try {
                awaitStack(cfn, stackName, "CREATE_COMPLETE");
                String original = documentName(cfn, stackName);
                assertDocument(ssm, original, "$DEFAULT", "1", before);
                parameter.put(replacesParameter ? "Name" : "Value", after);
                DescribeChangeSetResponse preview = preview(cfn, stackName, template);
                assertEquals(replacement, inventoryChange(preview).replacementAsString());
                assertDocument(ssm, original, "$DEFAULT", "1", before);
                execute(cfn, stackName, preview);
                current = documentName(cfn, stackName);
                if ("Replace".equals(method)) {
                    assertNotEquals(original, current);
                    assertMissing(ssm, original);
                } else {
                    assertEquals(original, current);
                    assertDocument(ssm, original, "1", "1", before);
                }
                assertDocument(ssm, current, "$DEFAULT", "Replace".equals(method) ? "1" : "2", after);
                assertEquals(replacesParameter ? "first" : after,
                        ssm.getParameter(request -> request.name(replacesParameter ? after : parameterName))
                                .parameter().value());
            } finally {
                deleteStack(cfn, stackName);
            }
            assertMissing(ssm, current);
        }
    }

    @Test
    void newVersionModeCannotDowngradeAChangedName() throws Exception {
        String stackName = "ssm-preview-name-" + UUID.randomUUID();
        ObjectNode template = template();
        properties(template).put("UpdateMethod", "NewVersion");
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            cfn.createStack(request -> request.stackName(stackName).templateBody(template.toString()));
            try {
                awaitStack(cfn, stackName, "CREATE_COMPLETE");
                String original = documentName(cfn, stackName);
                properties(template).put("Name", original + "-renamed");
                DescribeChangeSetResponse preview = preview(cfn, stackName, template);
                assertEquals("True", inventoryChange(preview).replacementAsString());
                execute(cfn, stackName, preview);
                assertEquals(original + "-renamed", documentName(cfn, stackName));
                assertDocument(ssm, original + "-renamed", "$DEFAULT", "1", "Inventory in us-east-1");
                assertMissing(ssm, original);
            } finally {
                deleteStack(cfn, stackName);
            }
        }
    }

    @Test
    void changingOnlyTheUpdateMethodLeavesTheCurrentVersion() throws Exception {
        String stackName = "ssm-preview-mode-" + UUID.randomUUID();
        ObjectNode template = template();
        properties(template).put("UpdateMethod", "NewVersion");
        try (CloudFormationClient cfn = cfn("us-east-1"); SsmClient ssm = ssm("us-east-1")) {
            cfn.createStack(request -> request.stackName(stackName).templateBody(template.toString()));
            try {
                awaitStack(cfn, stackName, "CREATE_COMPLETE");
                String original = documentName(cfn, stackName);
                properties(template).remove("UpdateMethod");
                DescribeChangeSetResponse preview = preview(cfn, stackName, template);
                assertEquals("False", inventoryChange(preview).replacementAsString());
                execute(cfn, stackName, preview);
                assertEquals(original, documentName(cfn, stackName));
                assertDocument(ssm, original, "$DEFAULT", "1", "Inventory in us-east-1");
            } finally {
                deleteStack(cfn, stackName);
            }
        }
    }

    private static void assertUpdatedIdentity(CloudFormationClient cfn, SsmClient ssm, String stack,
                                              String original, boolean replacement) throws Exception {
        String current = documentName(cfn, stack);
        if (replacement) {
            assertNotEquals(original, current);
            assertMissing(ssm, original);
        } else {
            assertEquals(original, current);
            assertDocument(ssm, original, "1", "1", "Inventory in us-east-1");
        }
        assertDocument(ssm, current, "$DEFAULT", replacement ? "1" : "2", "second");
    }

    private static DescribeChangeSetResponse preview(CloudFormationClient client, String stack,
                                                      ObjectNode template) throws Exception {
        String id = client.createChangeSet(request -> request.stackName(stack)
                .changeSetName("preview-" + UUID.randomUUID()).changeSetType("UPDATE")
                .templateBody(template.toString())).id();
        DescribeChangeSetResponse preview = CfnStackWaits.awaitState(Duration.ofSeconds(10),
                () -> client.describeChangeSet(request -> request.changeSetName(id)),
                described -> !"CREATE_IN_PROGRESS".equals(described.statusAsString())
                        && !"CREATE_PENDING".equals(described.statusAsString()),
                described -> "Change set did not finish: " + id);
        assertEquals("CREATE_COMPLETE", preview.statusAsString(), preview.statusReason());
        return preview;
    }

    private static ResourceChange inventoryChange(DescribeChangeSetResponse preview) {
        return preview.changes().stream().map(change -> change.resourceChange())
                .filter(change -> "Inventory".equals(change.logicalResourceId())).findFirst().orElseThrow();
    }

    private static void execute(CloudFormationClient client, String stack,
                                DescribeChangeSetResponse preview) throws Exception {
        client.executeChangeSet(request -> request.changeSetName(preview.changeSetId()));
        awaitStack(client, stack, "UPDATE_COMPLETE");
    }

    private static ObjectNode template() throws Exception {
        return (ObjectNode) MAPPER.readTree("""
                {
                  "Resources": {
                    "Inventory": {
                      "Type": "AWS::SSM::Document",
                      "Properties": {
                        "Name": {"Fn::Sub": "inventory-${AWS::StackName}"},
                        "DocumentType": "Command",
                        "Content": {
                          "schemaVersion": "2.2",
                          "description": {"Fn::Sub": "Inventory in ${AWS::Region}"},
                          "mainSteps": [{
                            "action": "aws:softwareInventory",
                            "name": "collectInventory",
                            "inputs": {"applications": "Enabled"}
                          }]
                        }
                      }
                    }
                  },
                  "Outputs": {"DocumentName": {"Value": {"Ref": "Inventory"}}}
                }
                """);
    }

    private static ObjectNode properties(ObjectNode template) {
        return template.withObject("/Resources/Inventory/Properties");
    }

    private static void description(ObjectNode template, String description) {
        properties(template).withObject("/Content").put("description", description);
    }

    private static String documentName(CloudFormationClient client, String stack) {
        return client.describeStacks(request -> request.stackName(stack)).stacks().getFirst()
                .outputs().getFirst().outputValue();
    }

    private static void update(CloudFormationClient client, String stack, ObjectNode template,
                               String expected) throws Exception {
        client.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
        awaitStack(client, stack, expected);
    }

    private static void assertDocument(SsmClient client, String name, String selector,
                                       String version, String description) throws Exception {
        GetDocumentResponse document = client.getDocument(request -> request.name(name).documentVersion(selector));
        assertEquals(version, document.documentVersion());
        assertEquals(description, MAPPER.readTree(document.content()).path("description").asText());
    }

    private static void assertMissing(SsmClient client, String name) {
        SsmException failure = assertThrows(SsmException.class,
                () -> client.getDocument(request -> request.name(name)));
        assertEquals("InvalidDocument", failure.awsErrorDetails().errorCode());
    }

    private static void deleteStack(CloudFormationClient client, String name) throws Exception {
        client.deleteStack(request -> request.stackName(name));
        CfnStackWaits.awaitState(Duration.ofSeconds(10), () -> {
            try {
                Stack stack = client.describeStacks(request -> request.stackName(name)).stacks().getFirst();
                assertNotEquals("DELETE_FAILED", stack.stackStatusAsString(), stack.stackStatusReason());
                return false;
            } catch (CloudFormationException error) {
                assertEquals("ValidationError", error.awsErrorDetails().errorCode());
                assertTrue(error.getMessage().contains("does not exist"), error.getMessage());
                return true;
            }
        }, deleted -> deleted, deleted -> "Stack was not deleted: " + name);
    }

    private static Stack awaitStack(CloudFormationClient client, String name, String expected) throws Exception {
        Stack stack = CfnStackWaits.awaitState(Duration.ofSeconds(10),
                () -> client.describeStacks(request -> request.stackName(name)).stacks().getFirst(),
                described -> !described.stackStatusAsString().endsWith("_IN_PROGRESS"),
                described -> "Stack did not finish: " + described.stackStatusAsString());
        assertEquals(expected, stack.stackStatusAsString(), stack.stackStatusReason());
        return stack;
    }

    private static CloudFormationClient cfn(String region) {
        return CloudFormationClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    private static SsmClient ssm(String region) {
        return SsmClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
