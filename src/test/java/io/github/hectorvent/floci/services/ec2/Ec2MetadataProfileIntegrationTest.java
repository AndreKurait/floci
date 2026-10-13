package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
class Ec2MetadataProfileIntegrationTest {
    @Inject
    IamService iam;

    @Test
    void iamInfoMatchesCreatedProfileAndRejectsMissingOrMismatchedAttachments() throws Exception {
        String account = "246813579012";
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String roleName = "metadata-role-" + suffix;
        String profileName = "metadata-profile-" + suffix;
        String authorization = "AWS4-HMAC-SHA256 Credential=" + account
                + "/20261008/us-east-1/iam/aws4_request, SignedHeaders=host, Signature=abc";
        iamRequest(authorization, "CreateRole")
                .formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Principal":{"Service":"ec2.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                        """)
                .post("/").then().statusCode(200);
        Response created = iamRequest(authorization, "CreateInstanceProfile")
                .formParam("InstanceProfileName", profileName).formParam("Path", "/nodes/")
                .post("/").then().statusCode(200).extract().response();
        String profileArn = created.xmlPath().getString(
                "CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.Arn");
        String profileId = created.xmlPath().getString(
                "CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.InstanceProfileId");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().imdsPort()).thenReturn(port);
        Vertx vertx = Vertx.vertx();
        Ec2MetadataServer server = new Ec2MetadataServer(vertx, config, iam);
        Instance instance = new Instance();
        instance.setInstanceId("i-0123456789abcdef0");
        instance.setIamInstanceProfileArn(profileArn);
        String endpoint = "http://127.0.0.1:" + port;
        boolean roleAdded = false;
        boolean profileDeleted = false;
        try (HttpClient client = HttpClient.newHttpClient()) {
            server.start().get(10, TimeUnit.SECONDS);
            server.registerContainer("127.0.0.1", instance.getInstanceId(), instance);
            HttpResponse<String> token = client.send(
                    HttpRequest.newBuilder(URI.create(endpoint + "/latest/api/token"))
                            .timeout(Duration.ofSeconds(5))
                            .header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                            .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, token.statusCode());
            assertEquals(404, getInfo(client, endpoint, token.body()).statusCode());
            iamRequest(authorization, "AddRoleToInstanceProfile")
                    .formParam("InstanceProfileName", profileName).formParam("RoleName", roleName)
                    .post("/").then().statusCode(200);
            roleAdded = true;
            HttpResponse<String> response = getInfo(client, endpoint, token.body());
            assertEquals(200, response.statusCode());
            JsonObject info = new JsonObject(response.body());
            assertEquals("Success", info.getString("Code"));
            assertEquals(profileArn, info.getString("InstanceProfileArn"));
            assertEquals(profileId, info.getString("InstanceProfileId"));
            instance.setIamInstanceProfileArn(profileArn.replace("/nodes/", "/other/"));
            assertEquals(404, getInfo(client, endpoint, token.body()).statusCode());
            instance.setIamInstanceProfileArn(profileArn);
            iamRequest(authorization, "RemoveRoleFromInstanceProfile")
                    .formParam("InstanceProfileName", profileName).formParam("RoleName", roleName)
                    .post("/").then().statusCode(200);
            roleAdded = false;
            assertEquals(404, getInfo(client, endpoint, token.body()).statusCode());
            iamRequest(authorization, "DeleteInstanceProfile").formParam("InstanceProfileName", profileName)
                    .post("/").then().statusCode(200);
            profileDeleted = true;
            assertEquals(404, getInfo(client, endpoint, token.body()).statusCode());
        } finally {
            server.stop();
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            if (roleAdded) {
                iamRequest(authorization, "RemoveRoleFromInstanceProfile")
                        .formParam("InstanceProfileName", profileName).formParam("RoleName", roleName)
                        .post("/").then().statusCode(200);
            }
            if (!profileDeleted) {
                iamRequest(authorization, "DeleteInstanceProfile")
                        .formParam("InstanceProfileName", profileName).post("/").then().statusCode(200);
            }
            iamRequest(authorization, "DeleteRole").formParam("RoleName", roleName)
                    .post("/").then().statusCode(200);
        }
    }

    private static RequestSpecification iamRequest(String authorization, String action) {
        return given().header("Authorization", authorization)
                .contentType("application/x-www-form-urlencoded").formParam("Action", action);
    }

    private static HttpResponse<String> getInfo(HttpClient client, String endpoint, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(endpoint + "/latest/meta-data/iam/info"))
                        .timeout(Duration.ofSeconds(5)).header("X-aws-ec2-metadata-token", token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
