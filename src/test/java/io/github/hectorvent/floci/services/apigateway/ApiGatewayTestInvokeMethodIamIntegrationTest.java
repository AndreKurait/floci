package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.ApiGatewayException;
import software.amazon.awssdk.services.apigateway.model.IntegrationType;
import software.amazon.awssdk.services.apigateway.model.TestInvokeMethodRequest;
import software.amazon.awssdk.services.apigateway.model.TestInvokeMethodResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class ApiGatewayTestInvokeMethodIamIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "us-gov-west-1,aws-us-gov", "cn-north-1,aws-cn"})
    void nativeInvocationUsesCallerOwnershipAndCanonicalPostPermissions(String regionName, String partition)
            throws Exception {
        String account = "235711131727";
        String otherAccount = "235711131729";
        Region region = Region.of(regionName);
        String rootAuth = auth(account, regionName, "iam");
        String user = "test-invoke-reader-" + UUID.randomUUID();
        UserCredentials key = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), rootAuth, user);
        try (ApiGatewayClient root = AwsTestClients.create(ApiGatewayClient.builder(), account, region);
             ApiGatewayClient reader = AwsTestClients.create(ApiGatewayClient.builder(),
                     key.accessKeyId(), key.secretAccessKey(), region);
             ApiGatewayClient other = AwsTestClients.create(ApiGatewayClient.builder(), otherAccount, region);
             StsClient sts = AwsTestClients.create(StsClient.builder(), key.accessKeyId(), key.secretAccessKey(), region)) {
            GetCallerIdentityResponse identity = sts.getCallerIdentity();
            assertEquals(account, identity.account());
            assertEquals("arn:" + partition + ":iam::" + account + ":user/" + user, identity.arn());
            String api = root.createRestApi(r -> r.name("test-invoke-owned-" + UUID.randomUUID())).id();
            try {
                String resource = root.getResources(r -> r.restApiId(api)).items().stream()
                        .filter(item -> "/".equals(item.path())).findFirst().orElseThrow().id();
                root.putMethod(r -> r.restApiId(api).resourceId(resource).httpMethod("GET").authorizationType("NONE"));
                root.putIntegration(r -> r.restApiId(api).resourceId(resource).httpMethod("GET")
                        .type(IntegrationType.MOCK)
                        .requestTemplates(Map.of("application/json", "{\"statusCode\":200}")));
                root.putMethodResponse(r -> r.restApiId(api).resourceId(resource).httpMethod("GET").statusCode("200"));
                root.putIntegrationResponse(r -> r.restApiId(api).resourceId(resource).httpMethod("GET")
                        .statusCode("200").responseTemplates(Map.of("application/json",
                                "{\"owner\":\"$context.accountId\",\"executed\":true}")));
                TestInvokeMethodRequest request = TestInvokeMethodRequest.builder().restApiId(api)
                        .resourceId(resource).httpMethod("GET").body("{}").pathWithQueryString("/")
                        .headers(Map.of("Content-Type", "application/json",
                                "Authorization", auth(otherAccount, regionName, "apigateway"),
                                "X-Amz-Account-Id", otherAccount)).build();
                String resourceArn = "arn:" + partition + ":apigateway:" + regionName
                        + "::/restapis/" + api + "/resources/" + resource + "/methods/GET";

                assertEquals(403, assertThrows(ApiGatewayException.class,
                        () -> reader.testInvokeMethod(request)).statusCode());
                policy(rootAuth, user, statement("Allow", "apigateway:POST", resourceArn));
                TestInvokeMethodResponse success = reader.testInvokeMethod(request);
                JsonNode body = new ObjectMapper().readTree(success.body());
                assertEquals(200, success.status());
                assertEquals(account, body.path("owner").asText());
                assertEquals(true, body.path("executed").asBoolean());
                assertEquals(404, assertThrows(ApiGatewayException.class,
                        () -> other.testInvokeMethod(request)).statusCode());

                policy(rootAuth, user, statement("Allow", "apigateway:POST", resourceArn + "-wrong"));
                assertEquals(403, assertThrows(ApiGatewayException.class,
                        () -> reader.testInvokeMethod(request)).statusCode());
                policy(rootAuth, user, statement("Allow", "apigateway:*", "*") + ","
                        + statement("Deny", "apigateway:POST", resourceArn));
                assertEquals(403, assertThrows(ApiGatewayException.class,
                        () -> reader.testInvokeMethod(request)).statusCode());
                TestInvokeMethodRequest misleading = request.toBuilder().overrideConfiguration(c -> c
                        .putHeader("X-Amz-Target", "ApiGateway.GetRestApis")
                        .putRawQueryParameter("Action", "GetRestApis")).build();
                assertEquals(403, assertThrows(ApiGatewayException.class,
                        () -> reader.testInvokeMethod(misleading)).statusCode());
                assertEquals(200, root.testInvokeMethod(request).status());
            } finally {
                root.deleteRestApi(r -> r.restApiId(api));
            }
        }
    }

    private void policy(String authorization, String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, user, "test-invoke",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }
}
