package io.floci.aws.services.codedeploy;

import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.CodeDeployException;
import software.amazon.awssdk.services.codedeploy.model.ComputePlatform;
import software.amazon.awssdk.services.codedeploy.model.RevisionLocationType;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CodeDeployRuntimeScopeIntegrationTest {
    private static final String OWNER = "123456789012";
    private static final String FOREIGN = "234567890123";

    @TestHTTPResource
    URI endpoint;

    @Inject
    RegionResolver regions;

    @ParameterizedTest
    @ValueSource(strings = {"us-west-2", "cn-north-1"})
    void deploymentReadsAndStopsRemainInTheirOwnerScope(String region) {
        String app = "runtime-scope-" + UUID.randomUUID();
        try (CodeDeployClient owner = client(OWNER, region);
             CodeDeployClient foreign = client(FOREIGN, region);
             CodeDeployClient otherRegion = client(OWNER, "eu-west-1")) {
            owner.createApplication(request -> request.applicationName(app).computePlatform(ComputePlatform.SERVER));
            try {
                String role = RequestScopes.callAs(OWNER, region,
                        () -> regions.buildGlobalArn("iam", "role/deploy"));
                owner.createDeploymentGroup(request -> request.applicationName(app).deploymentGroupName("group")
                        .serviceRoleArn(role).deploymentConfigName("CodeDeployDefault.AllAtOnce"));
                String deployment = owner.createDeployment(request -> request.applicationName(app)
                        .deploymentGroupName("group").revision(revision -> revision
                                .revisionType(RevisionLocationType.APP_SPEC_CONTENT)
                                .appSpecContent(content -> content.content("{\"version\":\"0.0\"}"))))
                        .deploymentId();
                assertEquals(deployment, owner.getDeployment(request -> request.deploymentId(deployment))
                        .deploymentInfo().deploymentId());
                assertTrue(owner.listDeployments(request -> request.applicationName(app))
                        .deployments().contains(deployment));
                assertEquals(1, owner.batchGetDeployments(request -> request.deploymentIds(deployment))
                        .deploymentsInfo().size());
                assertTrue(owner.listDeploymentTargets(request -> request.deploymentId(deployment)).targetIds().isEmpty());
                assertTrue(owner.batchGetDeploymentTargets(request -> request.deploymentId(deployment)
                        .targetIds("absent")).deploymentTargets().isEmpty());
                assertEquals("Succeeded", owner.stopDeployment(request -> request.deploymentId(deployment)).statusAsString());
                assertHidden(foreign, deployment, app);
                assertHidden(otherRegion, deployment, app);
                assertEquals("Failed", owner.getDeployment(request -> request.deploymentId(deployment))
                        .deploymentInfo().statusAsString());
            } finally {
                owner.deleteApplication(request -> request.applicationName(app));
            }
        }
    }

    private void assertHidden(CodeDeployClient client, String deployment, String app) {
        assertFalse(client.listDeployments(request -> request.applicationName(app)).deployments().contains(deployment));
        assertAbsent(() -> client.getDeployment(request -> request.deploymentId(deployment)));
        assertAbsent(() -> client.batchGetDeployments(request -> request.deploymentIds(deployment)));
        assertAbsent(() -> client.stopDeployment(request -> request.deploymentId(deployment)));
        assertAbsent(() -> client.listDeploymentTargets(request -> request.deploymentId(deployment)));
        assertAbsent(() -> client.batchGetDeploymentTargets(request -> request.deploymentId(deployment)
                .targetIds("absent")));
    }

    private void assertAbsent(Runnable operation) {
        CodeDeployException failure = assertThrows(CodeDeployException.class, operation::run);
        assertEquals("DeploymentDoesNotExistException", failure.awsErrorDetails().errorCode());
    }

    private CodeDeployClient client(String account, String region) {
        return CodeDeployClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))).build();
    }
}
