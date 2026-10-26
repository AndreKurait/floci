package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.ApiGatewayException;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class RestManagementAuthenticationIntegrationTest {
    @Inject
    ResolvedServiceCatalog catalog;

    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"restapis", "2015-03-31/functions", "2020-05-31/distribution"})
    void unsignedManagementListingsRequireCredentials(String path) {
        given().get(endpoint.resolve(path)).then().statusCode(403);
    }

    @Test
    void unsignedCodeArtifactManagementRequiresCredentials() {
        given().contentType("application/json").body("{}")
                .post(endpoint.resolve("v1/domains")).then().statusCode(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic invalid", "Bearer invalid"})
    void codeArtifactPackageControllerStillOwnsTokenAuthentication(String authorization) {
        Response response = given().header("Authorization", authorization)
                .get(endpoint.resolve("codeartifact/maven/auth-probe/none/example.pom"));
        assertEquals(401, response.statusCode());
        assertEquals("Basic realm=\"floci-codeartifact\"", response.header("WWW-Authenticate"));
    }

    @Test
    void unsignedXmlManagementUsesTheXmlErrorEnvelope() {
        Response response = given().get(endpoint.resolve("2020-05-31/distribution"));
        assertEquals(403, response.statusCode());
        assertEquals("MissingAuthenticationToken", response.xmlPath().getString("ErrorResponse.Error.Code"));
    }

    @Test
    void malformedCredentialsDoNotBecomeAnonymous() {
        given().header("Authorization", "AWS4-HMAC-SHA256 malformed")
                .get(endpoint.resolve("restapis")).then().statusCode(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Basic invalid", "Bearer invalid"})
    void alternativeAuthorizationCannotBypassManagementAuthentication(String authorization) {
        given().header("Authorization", authorization).get(endpoint.resolve("restapis"))
                .then().statusCode(400);
    }

    @Test
    void publicControllersDoNotInheritManagementAuthentication() {
        for (ServiceDescriptor descriptor : catalog.all()) {
            for (Class<?> resource : descriptor.resourceClasses()) {
                String name = resource.getSimpleName();
                if (Set.of("S3Controller", "CloudFrontServingController", "CodeArtifactMavenController",
                        "CodeArtifactPypiController", "CognitoOAuthController", "CognitoWellKnownController")
                        .contains(name)) {
                    assertFalse(
                            descriptor.credentialRequiredRestClasses().contains(resource), name);
                }
            }
        }
    }

    @Test
    void unsignedContestedGroupPathRemainsS3() throws Exception {
        String name = "unsigned-audit-" + UUID.randomUUID();
        String body = "{\"Name\":\"" + name + "\",\"Configuration\":["
                + "{\"Type\":\"AWS::EC2::CapacityReservationPool\"},"
                + "{\"Type\":\"AWS::ResourceGroups::Generic\",\"Parameters\":["
                + "{\"Name\":\"allowed-resource-types\",\"Values\":[\"AWS::EC2::CapacityReservation\"]}]}]}";
        Response response = given().contentType("application/json").body(body).post(endpoint.resolve("groups"));
        try {
            assertEquals(400, response.statusCode(), response.asString());
            assertEquals("InvalidArgument", response.xmlPath().getString("Error.Code"));
        } finally {
            if (response.statusCode() == 200) {
                String deletion = "{\"Group\":\"" + name + "\"}";
                URI uri = endpoint.resolve("delete-group");
                given().headers(AwsRequestSigner.signedAs("test", "test", "resource-groups")
                                .headersFor("POST", uri, Map.of(), deletion.getBytes(StandardCharsets.UTF_8)))
                        .contentType("application/json").body(deletion).post(uri).then().statusCode(200);
            }
        }
    }

    @Test
    void unsignedApiCreationMustBeRejected() {
        Response response = given().contentType("application/json")
                .body(Map.of("name", "unsigned-audit-" + UUID.randomUUID())).post(endpoint.resolve("restapis"));
        try {
            assertEquals(403, response.statusCode(), "Unsigned management must be refused before creation: " + response.asString());
        } finally {
            if (response.statusCode() == 201) {
                try (ApiGatewayClient client = api("test", "test")) {
                    client.deleteRestApi(request -> request.restApiId(response.jsonPath().getString("id")));
                }
            }
        }
    }

    @Test
    void signedSdkCreationAndDeletionRemainAllowed() throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "management-allow-", "allow", """
                {"Statement":{"Effect":"Allow","Action":"apigateway:*","Resource":"*"}}
                """); ApiGatewayClient client = api(user.accessKeyId(), user.secretAccessKey())) {
            String id = client.createRestApi(request -> request.name("signed-audit-" + UUID.randomUUID())).id();
            try {
                assertEquals(id, client.getRestApi(request -> request.restApiId(id)).id());
            } finally {
                client.deleteRestApi(request -> request.restApiId(id));
            }
        }
    }

    @Test
    void signedSdkExplicitDenyIsEnforced() throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "audit-deny-", "deny", """
                {"Statement":{"Effect":"Deny","Action":"*","Resource":"*"}}
                """); ApiGatewayClient client = api(user.accessKeyId(), user.secretAccessKey())) {
            ApiGatewayException error = assertThrows(ApiGatewayException.class,
                    () -> client.createRestApi(request -> request.name("denied-audit")));
            assertEquals(403, error.statusCode());
        }
    }

    @Test
    void healthRemainsPublic() {
        given().get(endpoint.resolve("_localstack/health")).then().statusCode(200);
    }

    @Test
    void s3ResourcePolicyAllowsAnonymousRead() {
        String bucket = "public-audit-" + UUID.randomUUID();
        try (S3Client client = S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .forcePathStyle(true).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test"))).build()) {
            client.createBucket(request -> request.bucket(bucket));
            try {
                client.putObject(request -> request.bucket(bucket).key("item"),
                        RequestBody.fromString("public body"));
                client.putBucketPolicy(request -> request.bucket(bucket).policy("""
                        {"Version":"2012-10-17","Statement":{"Effect":"Allow","Principal":"*",
                        "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/item"}}
                        """.formatted(bucket)));
                given().get(endpoint.resolve(bucket + "/item")).then().statusCode(200);
            } finally {
                client.deleteObject(request -> request.bucket(bucket).key("item"));
                client.deleteBucketPolicy(request -> request.bucket(bucket));
                client.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }

    private ApiGatewayClient api(String key, String secret) {
        return ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret))).build();
    }
}
