package io.floci.aws.services.apigateway;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.EnforcementFixtures.UserCredentials;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.ApiGatewayException;

import java.net.URI;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ApiGatewayDocumentationVersionIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "us-gov-west-1,aws-us-gov", "cn-north-1,aws-cn"})
    void collectionCreateRequiresItsExactNativeVerbAndResource(String regionName, String partition) {
        String account = "314159265358";
        String authorization = auth(account, regionName, "iam");
        String user = "docv-create-" + UUID.randomUUID();
        UserCredentials key = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), authorization, user);
        Region region = Region.of(regionName);
        try (ApiGatewayClient root = AwsTestClients.create(ApiGatewayClient.builder(), account, region);
             ApiGatewayClient reader = AwsTestClients.create(ApiGatewayClient.builder(), key.accessKeyId(), key.secretAccessKey(), region)) {
            String api = ApiGatewayDocumentationVersionSdkIntegrationTest.createApi(root);
            try {
                String collection = "arn:" + partition + ":apigateway:" + regionName
                        + "::/restapis/" + api + "/documentation/versions";
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> reader.createDocumentationVersion(r ->
                        r.restApiId(api).documentationVersion("v1"))).statusCode());
                policy(authorization, user, statement("Allow", "apigateway:POST", collection));
                assertEquals("v1", reader.createDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1")).version());
                assertEquals("v1", root.getDocumentationVersion(r -> r.restApiId(api).documentationVersion("v1")).version());
                policy(authorization, user, statement("Allow", "apigateway:POST", collection + "/wrong"));
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> reader.createDocumentationVersion(r ->
                        r.restApiId(api).documentationVersion("v2"))).statusCode());
                policy(authorization, user, statement("Allow", "apigateway:*", "*")
                        + "," + statement("Deny", "apigateway:POST", collection));
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> reader.createDocumentationVersion(r ->
                        r.restApiId(api).documentationVersion("v2"))).statusCode());
                assertEquals(1, root.getDocumentationVersions(r -> r.restApiId(api)).items().size());
            } finally {
                root.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "us-gov-west-1,aws-us-gov", "cn-north-1,aws-cn"})
    void emptyCollectionListRequiresItsExactNativeVerbAndResource(String regionName, String partition) {
        String account = "314159265358";
        String authorization = auth(account, regionName, "iam");
        String user = "documentation-version-list-" + UUID.randomUUID();
        UserCredentials key = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), authorization, user);
        Region region = Region.of(regionName);
        try (ApiGatewayClient root = AwsTestClients.create(ApiGatewayClient.builder(), account, region);
             ApiGatewayClient reader = AwsTestClients.create(ApiGatewayClient.builder(), key.accessKeyId(), key.secretAccessKey(), region)) {
            String api = ApiGatewayDocumentationVersionSdkIntegrationTest.createApi(root);
            try {
                String collection = "arn:" + partition + ":apigateway:" + regionName
                        + "::/restapis/" + api + "/documentation/versions";
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> reader.getDocumentationVersions(r ->
                        r.restApiId(api))).statusCode());
                policy(authorization, user, statement("Allow", "apigateway:GET", collection));
                assertTrue(reader.getDocumentationVersions(r -> r.restApiId(api)).items().isEmpty());
                policy(authorization, user, statement("Allow", "apigateway:POST", collection));
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> reader.getDocumentationVersions(r ->
                        r.restApiId(api))).statusCode());
                policy(authorization, user, statement("Allow", "apigateway:*", "*")
                        + "," + statement("Deny", "apigateway:GET", collection));
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> reader.getDocumentationVersions(r ->
                        r.restApiId(api))).statusCode());
                assertTrue(root.getDocumentationVersions(r -> r.restApiId(api)).items().isEmpty());
            } finally {
                root.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    private void policy(String authorization, String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, user, "documentation-version",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }
}
