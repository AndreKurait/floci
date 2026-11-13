package io.github.hectorvent.floci.services.codedeploy;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Wire compatibility and IAM decisions; actual agent execution is covered separately. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CodeDeployAgentProtocolIntegrationTest {
    private static final String ACCOUNT = "444444444444";
    private static final String REGION = "us-east-1";
    private static final String SECURE = "codedeploy-commands-secure";
    private static final String SECURE_TARGET = "CodeDeployCommandService.";

    @TestHTTPResource("/")
    static URI baseUri;

    @Inject
    CodeDeployAgentCommands commands;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void theSecureAgentCanFetchASignedSpecificationAcknowledgeAndCompleteItsCommand() {
        String key = user(SECURE + ":*");
        String id = "d-" + UUID.randomUUID();
        String host = "arn:aws:ec2:" + REGION + ":" + ACCOUNT + ":instance/i-agent";
        CodeDeployAgentCommands.Session session = commands.open(ACCOUNT, REGION, id, id,
                List.of(host), Map.of("DeploymentId", id));
        cleanup.register(() -> commands.close(session));
        CodeDeployAgentCommands.Ticket ticket = commands.enqueue(session, host, "DownloadBundle");

        String command = call(key, SECURE, REGION, SECURE_TARGET + "PollHostCommand",
                Map.of("HostIdentifier", host)).statusCode(200)
                .body("HostCommand.CommandName", equalTo("DownloadBundle"))
                .body("HostCommand.DeploymentExecutionId", equalTo(id))
                .extract().jsonPath().getString("HostCommand.HostCommandIdentifier");
        call(key, SECURE, REGION, SECURE_TARGET + "GetDeploymentSpecification",
                Map.of("HostIdentifier", host, "DeploymentExecutionId", id)).statusCode(200)
                .body("DeploymentSpecification.GenericEnvelope.Format", equalTo("PKCS7/JSON"))
                .body("DeploymentSpecification.GenericEnvelope.Payload", containsString("BEGIN PKCS7"));
        call(key, SECURE, REGION, SECURE_TARGET + "PutHostCommandAcknowledgement",
                Map.of("HostCommandIdentifier", command)).statusCode(200)
                .body("CommandStatus", equalTo("InProgress"));
        call(key, SECURE, REGION, SECURE_TARGET + "PostHostCommandUpdate",
                Map.of("HostCommandIdentifier", command)).statusCode(200)
                .body("CommandStatus", equalTo("InProgress"));
        assertFalse(ticket.result().toCompletableFuture().isDone());

        Map<String, Object> completion = Map.of("HostCommandIdentifier", command, "CommandStatus", "Succeeded",
                "Diagnostics", Map.of("Format", "JSON", "Payload", "{\"error_code\":0}"));
        call(key, SECURE, REGION, SECURE_TARGET + "PutHostCommandComplete", completion).statusCode(200);
        call(key, SECURE, REGION, SECURE_TARGET + "PutHostCommandComplete", completion).statusCode(200);
        assertEquals(new CodeDeployAgentCommands.Result("Succeeded", "{\"error_code\":0}"),
                ticket.result().toCompletableFuture().join());

        call("555555555555", SECURE, REGION, SECURE_TARGET + "GetDeploymentSpecification",
                Map.of("HostIdentifier", host, "DeploymentExecutionId", id)).statusCode(400)
                .body("__type", equalTo("InvalidDeploymentExecutionIdException"));
        call(key, SECURE, "us-west-2", SECURE_TARGET + "PostHostCommandUpdate",
                Map.of("HostCommandIdentifier", command)).statusCode(400)
                .body("__type", equalTo("InvalidHostCommandIdentifierException"));
    }

    @Test
    void publicCodeDeployPermissionsCannotBeUsedForTheAgentApiEvenWithAMisleadingSigningScope() {
        String key = user("codedeploy:*");
        call(key, "codedeploy", REGION, SECURE_TARGET + "PollHostCommand", Map.of("HostIdentifier", "host"))
                .statusCode(400).body("__type", equalTo("AccessDeniedException"))
                .body(containsString(SECURE + ":PollHostCommand"));
    }

    @Test
    void agentPermissionsDoNotGrantPublicDeploymentManagement() {
        String key = user(SECURE + ":*");
        call(key, SECURE, REGION, "CodeDeploy_20141006.ListApplications", Map.of())
                .statusCode(400).body("__type", equalTo("AccessDeniedException"))
                .body(containsString("codedeploy:ListApplications"));
        call(key, SECURE, REGION, SECURE_TARGET + "CreateApplication", Map.of("applicationName", "unexpected"))
                .statusCode(400).body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void invalidDiagnosticsCannotAcknowledgeOrCompleteAHostCommand() {
        String key = user(SECURE + ":*");
        String id = "d-" + UUID.randomUUID();
        CodeDeployAgentCommands.Session session = commands.open(ACCOUNT, REGION, id, id,
                List.of("host"), Map.of("DeploymentId", id));
        cleanup.register(() -> commands.close(session));
        CodeDeployAgentCommands.Ticket ticket = commands.enqueue(session, "host", "Install");
        String command = call(key, SECURE, REGION, SECURE_TARGET + "PollHostCommand",
                Map.of("HostIdentifier", "host")).statusCode(200)
                .extract().jsonPath().getString("HostCommand.HostCommandIdentifier");
        call(key, SECURE, REGION, SECURE_TARGET + "PutHostCommandAcknowledgement",
                Map.of("HostCommandIdentifier", command, "Diagnostics",
                        Map.of("Format", "JSON", "Payload", "x".repeat(8193))))
                .statusCode(400).body("__type", equalTo("ValidationException"));
        assertFalse(ticket.accepted().toCompletableFuture().isDone());
        call(key, SECURE, REGION, SECURE_TARGET + "PollHostCommand", Map.of("hostIdentifier", "host"))
                .statusCode(400).body("__type", equalTo("ValidationException"));
        call(key, SECURE, REGION, SECURE_TARGET + "PutHostCommandComplete",
                Map.of("HostCommandIdentifier", command, "CommandStatus", "Succeeded"))
                .statusCode(400).body("__type", equalTo("InvalidHostCommandIdentifierException"));
        assertFalse(ticket.result().toCompletableFuture().isDone());
    }

    private String user(String action) {
        String name = "agent-protocol-" + UUID.randomUUID().toString().substring(0, 8);
        String key = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ACCOUNT, "iam"), name).accessKeyId();
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ACCOUNT, "iam"), name, "agent", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"%s","Resource":"*"}]}
                """.formatted(action));
        return key;
    }

    private ValidatableResponse call(String key, String scope, String region, String target,
                                      Map<String, Object> request) {
        return given().header("Authorization", auth(key, region, scope))
                .header("X-Amz-Target", target).contentType("application/x-amz-json-1.1")
                .body(request).post("/").then();
    }
}
