package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.endpoints.Endpoint;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.LambdaException;
import software.amazon.awssdk.services.s3control.S3ControlClient;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class RestAuthenticationOwnershipIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {
            "2016-08-19/account-settings",
            "2020-04-22/code-signing-configs",
            "2020-06-30/functions/absent/code-signing-config",
            "2019-09-30/functions/absent/concurrency",
            "2019-09-25/functions/absent/event-invoke-config",
            "2018-10-31/layers",
            "2019-09-30/functions/absent/provisioned-concurrency",
            "2017-03-31/tags/absent",
            "2021-10-31/functions/absent/url",
            "v2/email/contact-lists",
            "v1/clusters",
            "applications",
            "workspaces",
            "2013-04-01/hostedzone",
            "2015-02-01/file-systems",
            "tags/absent",
            "v1/tags/absent",
            "admin"
    })
    void ownedManagementRoutesRefuseUnsignedRequestsBeforeBusinessValidation(String path) {
        Response response = given().get(endpoint.resolve(path));
        assertEquals(403, response.statusCode(), path + ": " + response.asString());
        assertTrue(response.asString().contains("MissingAuthenticationToken"), response.asString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Basic invalid", "Bearer invalid", "AWS4-HMAC-SHA256 malformed"})
    void sharedTagDispatcherRejectsUnreadableCredentials(String authorization) {
        Response response = given().header("Authorization", authorization).get(endpoint.resolve("tags/absent"));
        assertEquals(400, response.statusCode(), response.asString());
        assertTrue(response.asString().contains("IncompleteSignature"), response.asString());
    }

    @Test
    void unsignedLambdaCreationCannotPublishCodeSigningConfiguration() {
        String description = "ownership-" + UUID.randomUUID();
        try (LambdaClient client = lambda("test", "test")) {
            Response response = given().contentType("application/json").body(Map.of(
                    "Description", description, "AllowedPublishers", Map.of("SigningProfileVersionArns",
                            new String[]{"arn:aws:signer:us-east-1:000000000000:/signing-profiles/test/1234567890"})))
                    .post(endpoint.resolve("2020-04-22/code-signing-configs"));
            try {
                assertEquals(403, response.statusCode(), response.asString());
                assertFalse(client.listCodeSigningConfigs(request -> request.maxItems(1000)).codeSigningConfigs().stream()
                        .anyMatch(config -> description.equals(config.description())));
            } finally {
                client.listCodeSigningConfigs(request -> request.maxItems(1000)).codeSigningConfigs().stream()
                        .filter(config -> description.equals(config.description()))
                        .forEach(config -> client.deleteCodeSigningConfig(request ->
                                request.codeSigningConfigArn(config.codeSigningConfigArn())));
            }
        }
    }

    @Test
    void signedLambdaSdkStillRequiresAnIdentityGrant() throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ownership-allow-", "allow", """
                {"Statement":{"Effect":"Allow","Action":"lambda:ListLayers","Resource":"*"}}
                """); LambdaClient client = lambda(user.accessKeyId(), user.secretAccessKey())) {
            assertNotNull(client.listLayers());
        }
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ownership-deny-", "deny", """
                {"Statement":{"Effect":"Deny","Action":"lambda:ListLayers","Resource":"*"}}
                """); LambdaClient client = lambda(user.accessKeyId(), user.secretAccessKey())) {
            assertEquals(403, assertThrows(LambdaException.class, client::listLayers).statusCode());
        }
    }

    @Test
    void presignedLambdaUsesTheSameIdentityPolicyAndSignatureValidation() throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ownership-query-", "allow", """
                {"Statement":{"Effect":"Allow","Action":"lambda:ListLayers","Resource":"*"}}
                """)) {
            URI valid = AwsRequestSigner.signedAs(user.accessKeyId(), user.secretAccessKey(), "lambda")
                    .presignedUri("GET", endpoint.resolve("2018-10-31/layers"), Map.of(), new byte[0]);
            given().urlEncodingEnabled(false).get(valid).then().statusCode(200);
            URI invalid = AwsRequestSigner.signedAs(user.accessKeyId(), "wrong-secret", "lambda")
                    .presignedUri("GET", endpoint.resolve("2018-10-31/layers"), Map.of(), new byte[0]);
            given().urlEncodingEnabled(false).get(invalid).then().statusCode(403);
        }
    }

    @Test
    void oidcRegistrationRemainsUnsignedAndUsesItsOwnValidation() {
        Response response = given().contentType("application/json").body("{}")
                .post(endpoint.resolve("client/register"));
        assertEquals(400, response.statusCode(), response.asString());
        assertFalse(response.asString().contains("MissingAuthenticationToken"), response.asString());
    }

    @Test
    void s3ControlDenialPreservesAccountSettingsAndUsesItsOwnXmlNamespace() {
        String account = "%012d".formatted(Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 1_000_000_000_000L));
        try (S3ControlClient client = S3ControlClient.builder().region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .endpointProvider(params -> CompletableFuture.completedFuture(Endpoint.builder().url(endpoint).build()))
                .build()) {
            client.putPublicAccessBlock(request -> request.accountId(account)
                    .publicAccessBlockConfiguration(config -> config.blockPublicAcls(true)));
            try {
                Response response = given().header("x-amz-account-id", account).contentType("application/xml")
                        .body("<PublicAccessBlockConfiguration><BlockPublicAcls>false</BlockPublicAcls>"
                                + "</PublicAccessBlockConfiguration>")
                        .put(endpoint.resolve("v20180820/configuration/publicAccessBlock"));
                assertEquals(403, response.statusCode(), response.asString());
                assertEquals("MissingAuthenticationToken", response.xmlPath().getString("ErrorResponse.Error.Code"));
                assertTrue(response.asString().contains(AwsNamespaces.S3_CONTROL), response.asString());
                assertTrue(client.getPublicAccessBlock(request -> request.accountId(account))
                        .publicAccessBlockConfiguration().blockPublicAcls());
            } finally {
                client.deletePublicAccessBlock(request -> request.accountId(account));
            }
        }
    }

    @Test
    void unsignedSharedTagWritesCannotChangeSignedSdkResourceTags() {
        try (ApiGatewayClient client = ApiGatewayClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build()) {
            String id = client.createRestApi(request -> request.name("tag-ownership-" + UUID.randomUUID())).id();
            String arn = "arn:aws:apigateway:us-east-1::/restapis/" + id;
            try {
                client.tagResource(request -> request.resourceArn(arn).tags(Map.of("owner", "original")));
                Response response = given().contentType("application/json")
                        .body(Map.of("tags", Map.of("owner", "unverified")))
                        .post(endpoint.resolve("tags/" + arn));
                assertEquals(403, response.statusCode(), response.asString());
                assertEquals("original", client.getTags(request -> request.resourceArn(arn)).tags().get("owner"));
            } finally {
                client.deleteRestApi(request -> request.restApiId(id));
            }
        }
    }

    @Test
    void iotJobDataMethodsRetainEndpointAuthenticationWhileManagementRequiresCredentials() {
        given().get(endpoint.resolve("things")).then().statusCode(403);
        Response response = given().get(endpoint.resolve("things/absent/jobs/absent"));
        assertEquals(404, response.statusCode(), response.asString());
        assertFalse(response.asString().contains("MissingAuthenticationToken"), response.asString());
    }

    private LambdaClient lambda(String key, String secret) {
        return LambdaClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret))).build();
    }
}
