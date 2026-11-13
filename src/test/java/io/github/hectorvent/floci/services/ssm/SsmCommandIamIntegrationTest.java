package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.SendCommandRequest;
import software.amazon.awssdk.services.ssm.model.SsmException;
import software.amazon.awssdk.services.ssm.model.Tag;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class SsmCommandIamIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOCUMENT = "arn:aws:ssm:us-east-1:000000000000:document/";
    private static final String INSTANCE = "arn:aws:ec2:us-east-1:000000000000:instance/";
    private static final String CONTENT = """
            {"schemaVersion":"2.2","mainSteps":[{"action":"aws:runShellScript","name":"check",
             "inputs":{"runCommand":["true"]}}]}""";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @Test
    void scopedDocumentAndInstanceGrantCanSubmitCommand() throws Exception {
        Fixture fixture = fixture();
        try (SignedIamUsers.User user = user(List.of(
                grant("Allow", DOCUMENT + fixture.name()), grant("Allow", INSTANCE + fixture.instance())));
             SsmClient actor = client(user.accessKeyId(), user.secretAccessKey())) {
            assertNotNull(assertDoesNotThrow(() -> send(fixture, actor, fixture.name(), fixture.instance())));
        }
    }

    @Test
    void explicitDocumentDenyPreventsSubmission() throws Exception {
        Fixture fixture = fixture();
        try (SignedIamUsers.User user = user(List.of(
                grant("Allow", "*"), grant("Deny", DOCUMENT + fixture.name())));
             SsmClient actor = client(user.accessKeyId(), user.secretAccessKey())) {
            denied(() -> send(fixture, actor, fixture.name(), fixture.instance()));
        }
    }

    @Test
    void explicitInstanceDenyPreventsSubmission() throws Exception {
        Fixture fixture = fixture();
        try (SignedIamUsers.User user = user(List.of(
                grant("Allow", "*"), grant("Deny", INSTANCE + fixture.instance())));
             SsmClient actor = client(user.accessKeyId(), user.secretAccessKey())) {
            denied(() -> send(fixture, actor, fixture.name(), fixture.instance()));
        }
    }

    @Test
    void documentTagsRoundTripWithoutAliasingAParameter() throws Exception {
        Fixture fixture = fixture();
        fixture.admin().putParameter(request -> request.name(fixture.name()).type("String").value("parameter")
                .tags(tag -> tag.key("workflow").value("parameter-owner")));
        cleanup.register(() -> fixture.admin().deleteParameter(request -> request.name(fixture.name())));
        List<Tag> tags = assertDoesNotThrow(
                () -> fixture.admin().listTagsForResource(request ->
                        request.resourceType("Document").resourceId(fixture.name())).tagList());
        assertEquals(1, tags.size());
        assertEquals("workflow", tags.getFirst().key());
        assertEquals(fixture.name(), tags.getFirst().value());
        assertEquals(tags, fixture.admin().describeDocument(request -> request.name(fixture.name())).document().tags());
        fixture.admin().addTagsToResource(request -> request.resourceType("Document").resourceId(fixture.name())
                .tags(tag -> tag.key("workflow").value("updated")));
        fixture.admin().updateDocument(request -> request.name(fixture.name()).documentVersion("$LATEST")
                .content(CONTENT.replace("check", "recheck")));
        assertEquals("updated", fixture.admin().listTagsForResource(request ->
                request.resourceType("Document").resourceId(fixture.name())).tagList().getFirst().value());
        fixture.admin().removeTagsFromResource(request -> request.resourceType("Document").resourceId(fixture.name())
                .tagKeys("workflow"));
        assertEquals(List.of(), fixture.admin().listTagsForResource(request ->
                request.resourceType("Document").resourceId(fixture.name())).tagList());
        assertEquals("parameter-owner", fixture.admin().listTagsForResource(request ->
                request.resourceType("Parameter").resourceId(fixture.name())).tagList().getFirst().value());
    }

    @Test
    void scopedDocumentTagGrantCannotChangeAnotherDocument() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        try (SignedIamUsers.User user = user(List.of(tagGrant("Allow", DOCUMENT + fixture.name())));
             SsmClient actor = client(user.accessKeyId(), user.secretAccessKey())) {
            assertDoesNotThrow(() -> actor.addTagsToResource(request ->
                    request.resourceType("Document").resourceId(fixture.name())
                            .tags(tag -> tag.key("workflow").value("updated"))));
            assertEquals("updated", actor.listTagsForResource(request ->
                    request.resourceType("Document").resourceId(fixture.name())).tagList().getFirst().value());
            actor.removeTagsFromResource(request -> request.resourceType("Document").resourceId(fixture.name())
                    .tagKeys("workflow"));
            assertEquals(List.of(), actor.listTagsForResource(request ->
                    request.resourceType("Document").resourceId(fixture.name())).tagList());
            denied(() -> actor.addTagsToResource(request -> request.resourceType("Document")
                    .resourceId(other.name()).tags(tag -> tag.key("workflow").value("stolen"))));
            denied(() -> actor.listTagsForResource(request ->
                    request.resourceType("Document").resourceId(other.name())));
            denied(() -> actor.removeTagsFromResource(request ->
                    request.resourceType("Document").resourceId(other.name()).tagKeys("workflow")));
            assertEquals(other.name(), other.admin().listTagsForResource(request ->
                    request.resourceType("Document").resourceId(other.name())).tagList().getFirst().value());
        }
    }

    @Test
    void explicitDocumentTagDenyPreventsReadsAndChanges() throws Exception {
        Fixture fixture = fixture();
        try (SignedIamUsers.User user = user(List.of(
                tagGrant("Allow", "*"), tagGrant("Deny", DOCUMENT + fixture.name())));
             SsmClient actor = client(user.accessKeyId(), user.secretAccessKey())) {
            denied(() -> actor.addTagsToResource(request -> request.resourceType("Document")
                    .resourceId(fixture.name()).tags(tag -> tag.key("workflow").value("stolen"))));
            denied(() -> actor.listTagsForResource(request ->
                    request.resourceType("Document").resourceId(fixture.name())));
            denied(() -> actor.removeTagsFromResource(request ->
                    request.resourceType("Document").resourceId(fixture.name()).tagKeys("workflow")));
            assertEquals(fixture.name(), fixture.admin().listTagsForResource(request ->
                    request.resourceType("Document").resourceId(fixture.name())).tagList().getFirst().value());
        }
    }

    @Test
    void builtInShellDocumentUsesItsAwsOwnedResourceArn() throws Exception {
        Fixture fixture = fixture();
        try (SignedIamUsers.User user = user(List.of(
                grant("Allow", "arn:aws:ssm:us-east-1::document/AWS-RunShellScript"),
                grant("Allow", INSTANCE + fixture.instance())));
             SsmClient actor = client(user.accessKeyId(), user.secretAccessKey())) {
            assertNotNull(assertDoesNotThrow(() -> send(fixture, actor, "AWS-RunShellScript", fixture.instance())));
        }
    }

    @Test
    void documentAndInstanceTagsEachAuthorizeTheirOwnResource() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> document = Map.of("Effect", "Allow", "Action", "ssm:SendCommand",
                "Resource", DOCUMENT + "*", "Condition",
                Map.of("StringEquals", Map.of("aws:ResourceTag/workflow", fixture.name())));
        Map<String, Object> instance = Map.of("Effect", "Allow", "Action", "ssm:SendCommand",
                "Resource", INSTANCE + "*", "Condition",
                Map.of("StringEquals", Map.of("ssm:resourceTag/workflow", fixture.name())));
        try (SignedIamUsers.User user = user(List.of(document, instance));
             SsmClient actor = client(user.accessKeyId(), user.secretAccessKey())) {
            assertNotNull(assertDoesNotThrow(() -> send(fixture, actor, fixture.name(), fixture.instance())));
            Fixture other = fixture();
            denied(() -> send(fixture, actor, fixture.name(), other.instance()));
            denied(() -> send(fixture, actor, other.name(), fixture.instance()));
            denied(() -> send(fixture, actor, fixture.name(), fixture.instance(), other.instance()));
        }
    }

    private Fixture fixture() throws Exception {
        String name = "command-iam-" + UUID.randomUUID();
        SsmClient admin = client("test", "test");
        cleanup.register(admin::close);
        Ec2Client ec2 = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        cleanup.register(ec2::close);
        String instance = ec2.runInstances(request -> request.imageId("ami-0abcdef1234567890")
                .instanceType("t3.micro").minCount(1).maxCount(1)
                .tagSpecifications(spec -> spec.resourceType("instance")
                        .tags(tag -> tag.key("workflow").value(name)))).instances().getFirst().instanceId();
        cleanup.register(() -> ec2.terminateInstances(request -> request.instanceIds(instance)));
        byte[] body = MAPPER.writeValueAsString(Map.of(
                "InstanceId", instance, "AgentName", "amazon-ssm-agent",
                "AgentVersion", "3.3.5700.0", "PlatformType", "Linux")).getBytes(StandardCharsets.UTF_8);
        given().filter(AwsRequestSigner.signedAs("test", "test", "ssm").signingContentType())
                .header("X-Amz-Target", "AmazonSSM.UpdateInstanceInformation")
                .contentType("application/x-amz-json-1.1").body(body).post(endpoint).then().statusCode(200);
        admin.createDocument(request -> request.name(name).documentType("Command").content(CONTENT)
                .tags(tag -> tag.key("workflow").value(name)));
        cleanup.register(() -> admin.deleteDocument(request -> request.name(name)));
        return new Fixture(admin, name, instance);
    }

    private String send(Fixture fixture, SsmClient actor, String document, String... instances) {
        SendCommandRequest.Builder request = SendCommandRequest.builder()
                .documentName(document).instanceIds(instances).comment(fixture.name());
        if ("AWS-RunShellScript".equals(document)) {
            request.parameters(Map.of("commands", List.of("true")));
        }
        String command = actor.sendCommand(request.build()).command().commandId();
        cleanup.register(() -> fixture.admin().cancelCommand(value -> value.commandId(command)));
        return command;
    }

    private SignedIamUsers.User user(List<Map<String, Object>> statements) throws Exception {
        return SignedIamUsers.createUser(endpoint, "command-iam-", "command",
                MAPPER.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", statements)));
    }

    private static Map<String, Object> grant(String effect, String resource) {
        return Map.of("Effect", effect, "Action", "ssm:SendCommand", "Resource", resource);
    }

    private static Map<String, Object> tagGrant(String effect, String resource) {
        return Map.of("Effect", effect, "Action", List.of(
                "ssm:AddTagsToResource", "ssm:ListTagsForResource", "ssm:RemoveTagsFromResource"),
                "Resource", resource);
    }

    private static void denied(Executable operation) {
        SsmException failure = assertThrows(SsmException.class, operation);
        assertEquals(400, failure.statusCode());
        assertEquals("AccessDeniedException", failure.awsErrorDetails().errorCode());
    }

    private SsmClient client(String access, String secret) {
        return SsmClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)))
                .build();
    }

    private record Fixture(SsmClient admin, String name, String instance) {
    }
}
