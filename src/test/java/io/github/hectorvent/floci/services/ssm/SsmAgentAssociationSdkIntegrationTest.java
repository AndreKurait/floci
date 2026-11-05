package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.services.ssm.model.SsmAssociation;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.AssociationDescription;
import software.amazon.awssdk.services.ssm.model.Target;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class SsmAgentAssociationSdkIntegrationTest {
    private static final String ACCOUNT = "830011112310";
    private static final String REGION = "us-east-1";
    private static final String DOCUMENT = """
            {"schemaVersion":"2.2","description":"Agent association contract",
             "parameters":{"message":{"type":"String","default":"hello"}},
             "mainSteps":[{"action":"aws:runShellScript","name":"run",
                          "inputs":{"runCommand":["echo {{ message }}"]}}]}
            """;

    @Inject
    ObjectMapper mapper;

    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1", "cn-north-1"})
    void discoversLiveEc2TagTargetsWithoutCompletingThem(String region) {
        String value = UUID.randomUUID().toString();
        try (SsmClient ssm = ssm(ACCOUNT, region); Ec2Client ec2 = ec2(ACCOUNT, region)) {
            String instance = node(ec2, region, value);
            AssociationDescription association = association(ssm, List.of(
                    Target.builder().key("tag:Inventory").values(value).build()));
            try {
                Response listed = list(ACCOUNT, region, instance);
                listed.then().statusCode(200).body("Associations", hasSize(1))
                        .body("Associations[0].AssociationId", equalTo(association.associationId()))
                        .body("Associations[0].InstanceId", equalTo(instance))
                        .body("Associations[0].DocumentVersion", equalTo("1"))
                        .body("Associations[0].Checksum", equalTo(Digests.sha256Hex(DOCUMENT)))
                        .body("Associations[0].DetailedStatus", equalTo("Pending"))
                        .body("Associations[0].ScheduleExpression", equalTo("rate(12 hours)"))
                        .body("Associations[0].Parameters.message[0]", equalTo("from association"))
                        .body("Associations[0].LastExecutionDate", nullValue());
                assertEquals(DOCUMENT, ssm.getDocument(request -> request.name(association.name())
                        .documentVersion("1")).content());
                assertEquals("Pending", describe(ssm, association).status().nameAsString());
                ec2.deleteTags(request -> request.resources(instance)
                        .tags(Tag.builder().key("Inventory").build()));
                list(ACCOUNT, region, instance).then().statusCode(200).body("Associations", hasSize(0));
                report(ACCOUNT, region, association, instance, "Success", 10).then().statusCode(400)
                        .body("__type", equalTo("AssociationDoesNotExist"));
            } finally {
                remove(ssm, ec2, association, List.of(instance));
            }
        }
    }

    @Test
    void reportsEachTargetAndDoesNotRewindCompletedExecutions() throws Exception {
        String value = UUID.randomUUID().toString();
        try (SsmClient ssm = ssm(ACCOUNT, REGION); Ec2Client ec2 = ec2(ACCOUNT, REGION)) {
            String first = node(ec2, REGION, value);
            String second = node(ec2, REGION, value);
            AssociationDescription association = association(ssm, List.of(
                    Target.builder().key("tag:Inventory").values(value).build()));
            try {
                report(ACCOUNT, REGION, association, first, "Success", 12.25).then().statusCode(200);
                assertEquals("Pending", describe(ssm, association).status().nameAsString());
                list(ACCOUNT, REGION, first).then().statusCode(200)
                        .body("Associations[0].DetailedStatus", equalTo("Success"));
                list(ACCOUNT, REGION, second).then().statusCode(200)
                        .body("Associations[0].DetailedStatus", equalTo("Pending"))
                        .body("Associations[0].LastExecutionDate", nullValue());
                report(ACCOUNT, REGION, association, second, "Success", 13.5).then().statusCode(200);
                AssociationDescription completed = describe(ssm, association);
                assertEquals("Success", completed.status().nameAsString());
                assertEquals(Instant.ofEpochMilli(13500), completed.lastExecutionDate());
                report(ACCOUNT, REGION, association, first, "InProgress", 12.25).then().statusCode(200);
                report(ACCOUNT, REGION, association, first, "Failed", 11).then().statusCode(200);
                assertEquals("Success", describe(ssm, association).status().nameAsString());

                SsmAssociation saved = new SsmAssociation();
                saved.setInstanceExecutions(Map.of(
                        first, new SsmAssociation.InstanceExecution(
                                "1", "Success", Instant.ofEpochMilli(12250), "done", null, null),
                        second, new SsmAssociation.InstanceExecution(
                                "1", "Success", Instant.ofEpochMilli(13500), "done", null,
                                mapper.createObjectNode().put("S3OutputUrl", "s3://execution-output/result"))));
                SsmAssociation restored = mapper.readValue(mapper.writeValueAsBytes(saved), SsmAssociation.class);
                assertNull(restored.getInstanceExecutions().get(first).outputUrl());
                assertEquals(saved.getInstanceExecutions(), restored.getInstanceExecutions());
            } finally {
                remove(ssm, ec2, association, List.of(first, second));
            }
        }
    }

    @Test
    void newAssociationVersionsRequireNewReportsAndReturnSelectedDocumentContent() {
        try (SsmClient ssm = ssm(ACCOUNT, REGION); Ec2Client ec2 = ec2(ACCOUNT, REGION)) {
            String instance = node(ec2, REGION, UUID.randomUUID().toString());
            AssociationDescription association = association(ssm, List.of(
                    Target.builder().key("InstanceIds").values(instance).build()));
            try {
                report(ACCOUNT, REGION, association, instance, "Success", 20).then().statusCode(200);
                String changed = DOCUMENT.replace("hello", "updated");
                ssm.updateDocument(request -> request.name(association.name())
                        .content(changed).documentVersion("$LATEST"));
                AssociationDescription updated = ssm.updateAssociation(request ->
                        request.associationId(association.associationId()).documentVersion("$LATEST")
                                .associationVersion("1").scheduleExpression("rate(12 hours)")).associationDescription();
                assertEquals("2", updated.associationVersion());
                list(ACCOUNT, REGION, instance).then().statusCode(200)
                        .body("Associations[0].DocumentVersion", equalTo("2"))
                        .body("Associations[0].AssociationVersion", equalTo("2"))
                        .body("Associations[0].Checksum", equalTo(Digests.sha256Hex(changed)))
                        .body("Associations[0].DetailedStatus", equalTo("Pending"))
                        .body("Associations[0].LastExecutionDate", nullValue());
                report(ACCOUNT, REGION, association, instance, "Success", 30).then().statusCode(400)
                        .body("__type", equalTo("InvalidAssociationVersion"));
                assertEquals("Pending", describe(ssm, association).status().nameAsString());
                report(ACCOUNT, REGION, updated, instance, "Success", 31).then().statusCode(200);
                assertEquals("Success", describe(ssm, association).status().nameAsString());
            } finally {
                remove(ssm, ec2, association, List.of(instance));
            }
        }
    }

    @Test
    void isolatesManagedNodesAssociationsAndPaginationAcrossOwnersAndRegions() {
        try (SsmClient ssm = ssm(ACCOUNT, REGION); Ec2Client ec2 = ec2(ACCOUNT, REGION)) {
            String instance = node(ec2, REGION, UUID.randomUUID().toString());
            AssociationDescription first = association(ssm, List.of(
                    Target.builder().key("InstanceIds").values(instance).build()));
            AssociationDescription second = association(ssm, List.of(
                    Target.builder().key("InstanceIds").values(instance).build()));
            String foreign = "830011112311";
            try {
                list(foreign, REGION, instance).then().statusCode(400).body("__type", equalTo("InvalidInstanceId"));
                register(foreign, REGION, instance);
                list(foreign, REGION, instance).then().statusCode(200).body("Associations", hasSize(0));
                register(ACCOUNT, "us-west-2", instance);
                list(ACCOUNT, "us-west-2", instance).then().statusCode(200).body("Associations", hasSize(0));
                report(foreign, REGION, first, instance, "Success", 50).then().statusCode(400)
                        .body("__type", equalTo("AssociationDoesNotExist"));
                Response page = call(ACCOUNT, REGION, "ListInstanceAssociations",
                        Map.of("InstanceId", instance, "MaxResults", 1));
                page.then().statusCode(200).body("Associations", hasSize(1));
                String token = page.jsonPath().getString("NextToken");
                Response next = call(ACCOUNT, REGION, "ListInstanceAssociations",
                        Map.of("InstanceId", instance, "MaxResults", 1, "NextToken", token));
                next.then().statusCode(200).body("Associations", hasSize(1)).body("NextToken", nullValue());
                assertNotEquals(page.jsonPath().getString("Associations[0].AssociationId"),
                        next.jsonPath().getString("Associations[0].AssociationId"));
                call(foreign, REGION, "ListInstanceAssociations",
                        Map.of("InstanceId", instance, "MaxResults", 1, "NextToken", token))
                        .then().statusCode(400).body("__type", equalTo("InvalidNextToken"));
                call(ACCOUNT, REGION, "ListInstanceAssociations", Map.of("InstanceId", instance, "MaxResults", 0))
                        .then().statusCode(400).body("__type", equalTo("ValidationException"));
                Map<String, Object> malformed = new HashMap<>(reportBody(first, instance, "Success", 50));
                malformed.put("ExecutionResult", Map.of("Status", "Success", "ExecutionSummary", "",
                        "ExecutionDate", "50"));
                call(ACCOUNT, REGION, "UpdateInstanceAssociationStatus", malformed)
                        .then().statusCode(400).body("__type", equalTo("SerializationException"));
                assertNull(describe(ssm, first).lastExecutionDate());
            } finally {
                ssm.deleteAssociation(request -> request.associationId(second.associationId()));
                ssm.deleteDocument(request -> request.name(second.name()));
                remove(ssm, ec2, first, List.of(instance));
            }
        }
    }

    private static AssociationDescription association(SsmClient ssm, List<Target> targets) {
        String name = "agent-association-" + UUID.randomUUID();
        ssm.createDocument(request -> request.name(name).content(DOCUMENT).documentType("Command"));
        return ssm.createAssociation(request -> request.name(name).targets(targets)
                .parameters(Map.of("message", List.of("from association")))
                .scheduleExpression("rate(12 hours)")).associationDescription();
    }

    private static String node(Ec2Client ec2, String region, String tag) {
        String instance = ec2.runInstances(request -> request.imageId("ami-1234567890abcdef0")
                .instanceType("t3.micro").minCount(1).maxCount(1)
                .tagSpecifications(TagSpecification.builder().resourceType(ResourceType.INSTANCE)
                        .tags(Tag.builder().key("Inventory").value(tag).build()).build()))
                .instances().getFirst().instanceId();
        register(ACCOUNT, region, instance);
        return instance;
    }

    private static void register(String account, String region, String instance) {
        call(account, region, "UpdateInstanceInformation", Map.of(
                "InstanceId", instance, "AgentVersion", "3.3.5700.0", "PlatformType", "Linux"))
                .then().statusCode(200);
    }

    private static Response list(String account, String region, String instance) {
        return call(account, region, "ListInstanceAssociations", Map.of("InstanceId", instance));
    }

    private static Map<String, Object> reportBody(AssociationDescription association, String instance,
                                                   String status, double date) {
        return Map.of("AssociationId", association.associationId(),
                "AssociationVersion", association.associationVersion(), "InstanceId", instance,
                "ExecutionResult", Map.of("Status", status, "ExecutionDate", date, "ExecutionSummary", "reported"));
    }

    private static Response report(String account, String region, AssociationDescription association,
                                   String instance, String status, double date) {
        return call(account, region, "UpdateInstanceAssociationStatus", reportBody(association, instance, status, date));
    }

    // These agent APIs are absent from the public Java SDK. Wire shapes follow the AWS agent SDK;
    // ordinary EC2/SSM setup and the observable result use public SDK clients.
    private static Response call(String account, String region, String operation, Object body) {
        return given().header("X-Amz-Target", "AmazonSSM." + operation)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20260102/" + region
                        + "/ssm/aws4_request, SignedHeaders=host, Signature=example")
                .contentType("application/x-amz-json-1.1").body(body).post("/");
    }

    private static AssociationDescription describe(SsmClient ssm, AssociationDescription association) {
        return ssm.describeAssociation(request -> request.associationId(association.associationId()))
                .associationDescription();
    }

    private static void remove(SsmClient ssm, Ec2Client ec2, AssociationDescription association, List<String> instances) {
        ec2.terminateInstances(request -> request.instanceIds(instances));
        ssm.deleteAssociation(request -> request.associationId(association.associationId()));
        ssm.deleteDocument(request -> request.name(association.name()));
    }

    private static SsmClient ssm(String account, String region) {
        return SsmClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private static Ec2Client ec2(String account, String region) {
        return Ec2Client.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
