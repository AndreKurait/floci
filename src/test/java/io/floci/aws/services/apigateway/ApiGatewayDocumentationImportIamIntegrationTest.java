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
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.ApiGatewayException;
import software.amazon.awssdk.services.apigateway.model.ImportDocumentationPartsRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.services.apigateway.ApiGatewayDocumentationImportSdkIntegrationTest.document;
import static io.floci.aws.services.apigateway.ApiGatewayDocumentationImportSdkIntegrationTest.part;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ApiGatewayDocumentationImportIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "us-gov-west-1,aws-us-gov", "cn-north-1,aws-cn"})
    void importUsesCredentialOwnerAndCanonicalPutResource(String regionName, String partition) {
        String account = "314159265358";
        String otherAccount = "271828182845";
        Region region = Region.of(regionName);
        String rootAuth = auth(account, regionName, "iam");
        String user = "documentation-import-" + UUID.randomUUID();
        UserCredentials key = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), rootAuth, user);
        try (ApiGatewayClient root = AwsTestClients.create(ApiGatewayClient.builder(), account, region);
             ApiGatewayClient reader = AwsTestClients.create(ApiGatewayClient.builder(), key.accessKeyId(), key.secretAccessKey(), region);
             ApiGatewayClient other = AwsTestClients.create(ApiGatewayClient.builder(), otherAccount, region);
             StsClient sts = AwsTestClients.create(StsClient.builder(), key.accessKeyId(), key.secretAccessKey(), region)) {
            GetCallerIdentityResponse identity = sts.getCallerIdentity();
            assertEquals(account, identity.account());
            assertEquals("arn:" + partition + ":iam::" + account + ":user/" + user, identity.arn());
            String api = root.createRestApi(r -> r.name("owned-documentation-import")).id();
            try {
                ImportDocumentationPartsRequest request = ImportDocumentationPartsRequest.builder().restApiId(api)
                        .body(SdkBytes.fromUtf8String(document(List.of(part(Map.of("type", "API"), Map.of("summary", "Owned"))))))
                        .overrideConfiguration(c -> c.putHeader("X-Amz-Account-Id", otherAccount)).build();
                String arn = "arn:" + partition + ":apigateway:" + regionName + "::/restapis/" + api + "/documentation/parts";
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> reader.importDocumentationParts(request)).statusCode());
                policy(rootAuth, user, statement("Allow", "apigateway:PUT", arn));
                String id = reader.importDocumentationParts(request).ids().getFirst();
                assertEquals("{\"summary\":\"Owned\"}", root.getDocumentationPart(r -> r.restApiId(api).documentationPartId(id)).properties());
                assertEquals(404, assertThrows(ApiGatewayException.class, () -> other.importDocumentationParts(request)).statusCode());
                policy(rootAuth, user, statement("Allow", "apigateway:PUT", arn + "/wrong"));
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> reader.importDocumentationParts(request)).statusCode());
                policy(rootAuth, user, statement("Allow", "apigateway:*", "*") + "," + statement("Deny", "apigateway:PUT", arn));
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> reader.importDocumentationParts(request)).statusCode());
                ImportDocumentationPartsRequest misleading = request.toBuilder().overrideConfiguration(c -> c
                        .putHeader("X-Amz-Target", "ApiGateway.GetRestApis").putRawQueryParameter("Action", "GetRestApis")).build();
                assertEquals(403, assertThrows(ApiGatewayException.class, () -> reader.importDocumentationParts(misleading)).statusCode());
                assertEquals(List.of(id), root.getDocumentationParts(r -> r.restApiId(api)).items().stream().map(p -> p.id()).toList());
                assertEquals("{\"summary\":\"Owned\"}", root.getDocumentationPart(r -> r.restApiId(api).documentationPartId(id)).properties());
            } finally {
                root.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    private void policy(String authorization, String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, user, "documentation-import",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }
}
