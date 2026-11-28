package io.floci.aws.services.apigateway;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.PatchOperation;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class ApiGatewayStageVariableRemovalSdkIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void removingAVariablePersistsWithoutChangingSiblingStages() {
        try (ApiGatewayClient client = ApiGatewayClient.builder().endpointOverride(endpoint)
                .region(Region.US_EAST_1).httpClientBuilder(Apache5HttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            String apiId = client.createRestApi(r -> r.name("stage-variable-remove-sdk")).id();
            try {
                String deploymentId = client.createDeployment(r -> r.restApiId(apiId)).id();
                Map<String, String> variables = Map.of("keep", "1", "drop", "2");
                for (String stage : new String[] {"dev", "prod"}) {
                    client.createStage(r -> r.restApiId(apiId).stageName(stage)
                            .deploymentId(deploymentId).variables(variables));
                }
                assertEquals(Map.of("keep", "1"), client.updateStage(r -> r.restApiId(apiId).stageName("dev")
                        .patchOperations(PatchOperation.builder().op("remove").path("/variables/drop").build()))
                        .variables());
                assertEquals(Map.of("keep", "1"), client.getStage(r -> r.restApiId(apiId).stageName("dev")).variables());
                assertEquals(variables, client.getStage(r -> r.restApiId(apiId).stageName("prod")).variables());
                assertEquals(Map.of("keep", "3"), client.updateStage(r -> r.restApiId(apiId).stageName("dev")
                        .patchOperations(PatchOperation.builder().op("replace").path("/variables/keep")
                                .value("3").build())).variables());
            } finally {
                client.deleteRestApi(r -> r.restApiId(apiId));
            }
        }
    }
}
