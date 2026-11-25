package io.floci.aws.services.appconfig;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;
import software.amazon.awssdk.services.appconfig.model.ConfigurationProfileSummary;
import software.amazon.awssdk.services.appconfig.model.Environment;
import software.amazon.awssdk.services.sts.StsClient;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class AppConfigDeploymentNumberIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @CsvSource({"GET,plain", "GET,zero", "GET,plus", "DELETE,plain", "DELETE,zero", "DELETE,plus"})
    void numericPathAliasesCannotBypassDeploymentDenials(String method, String spelling) throws Exception {
        try (AppConfigClient admin = AwsTestClients.create(AppConfigClient.builder(), "test", "test", Region.US_EAST_1, endpoint);
             StsClient identity = AwsTestClients.create(StsClient.builder(), "test", "test", Region.US_EAST_1, endpoint)) {
            String app = admin.createApplication(r -> r.name("numeric-" + UUID.randomUUID())).id();
            try {
                String env = admin.createEnvironment(r -> r.applicationId(app).name("production")).id();
                String profile = admin.createConfigurationProfile(r -> r.applicationId(app).name("settings")
                        .locationUri("hosted")).id();
                int number = admin.startDeployment(r -> r.applicationId(app).environmentId(env)
                        .configurationProfileId(profile).configurationVersion("1")
                        .deploymentStrategyId("AppConfig.AllAtOnce")).deploymentNumber();
                String arn = "arn:aws:appconfig:us-east-1:" + identity.getCallerIdentity(r -> {}).account()
                        + ":application/" + app + "/environment/" + env + "/deployment/" + number;
                String policy = "{\"Version\":\"2012-10-17\",\"Statement\":["
                        + "{\"Effect\":\"Allow\",\"Action\":\"appconfig:*\",\"Resource\":\"*\"},"
                        + "{\"Effect\":\"Deny\",\"Action\":[\"appconfig:GetDeployment\",\"appconfig:StopDeployment\"],"
                        + "\"Resource\":\"" + arn + "\"}]}";
                String path = "applications/" + app + "/environments/" + env + "/deployments/";
                String state = admin.getDeployment(r -> r.applicationId(app).environmentId(env)
                        .deploymentNumber(number)).stateAsString();
                try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "appconfig-number-", "deny", policy)) {
                    String selector = switch (spelling) {
                        case "zero" -> "0" + number;
                        case "plus" -> "%2B" + number;
                        default -> Integer.toString(number);
                    };
                    URI uri = endpoint.resolve(path + selector);
                    given().urlEncodingEnabled(false)
                            .filter(AwsRequestSigner.signedAs(user.accessKeyId(), user.secretAccessKey(), "appconfig"))
                            .request(method, uri).then().statusCode(403)
                            .body("__type", equalTo("AccessDeniedException"));
                    given().urlEncodingEnabled(false).filter(AwsRequestSigner.signedAs("test", "test", "appconfig"))
                            .get(uri).then().statusCode(200).body("DeploymentNumber", equalTo(number));
                }
                assertEquals(state, admin.getDeployment(r -> r.applicationId(app).environmentId(env)
                        .deploymentNumber(number)).stateAsString());
            } finally {
                for (ConfigurationProfileSummary profile : admin.listConfigurationProfiles(r -> r.applicationId(app)).items()) {
                    admin.deleteConfigurationProfile(r -> r.applicationId(app).configurationProfileId(profile.id()));
                }
                for (Environment env : admin.listEnvironments(r -> r.applicationId(app)).items()) {
                    admin.deleteEnvironment(r -> r.applicationId(app).environmentId(env.id()));
                }
                assertEquals(List.of(), admin.listConfigurationProfiles(r -> r.applicationId(app)).items());
                assertEquals(List.of(), admin.listEnvironments(r -> r.applicationId(app)).items());
                admin.deleteApplication(r -> r.applicationId(app));
                assertEquals(404, assertThrows(AppConfigException.class,
                        () -> admin.getApplication(r -> r.applicationId(app))).statusCode());
            }
        }
    }
}
