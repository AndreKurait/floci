package io.floci.aws.services.appconfig;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;
import software.amazon.awssdk.services.appconfig.model.Environment;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class AppConfigEnvironmentTargetIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    @InjectSpy
    AppConfigService service;

    @ParameterizedTest
    @CsvSource({"environment,false,us-east-1", "environment,true,us-east-1",
            "application,false,us-east-1", "application,true,us-east-1",
            "environment,false,cn-north-1", "application,true,cn-north-1",
            "application-before-environment,false,us-east-1", "application-before-environment,true,us-east-1"})
    void nameReassignmentCannotChangeTheAuthorizedTarget(String rename, boolean presigned, String region) throws Exception {
        try (Fixture fixture = new Fixture(Region.of(region))) {
            AtomicBoolean armed = new AtomicBoolean(true);
            doAnswer(invocation -> {
                Object selected = invocation.callRealMethod();
                if (fixture.app.equals(invocation.getArgument(0)) && armed.compareAndSet(true, false)) {
                    if ("environment".equals(rename)) {
                        service.updateEnvironment(fixture.app, fixture.environment, Map.of("Name", "retired"));
                        service.updateEnvironment(fixture.app, fixture.sibling, Map.of("Name", "production"));
                    } else {
                        service.updateApplication(fixture.app, Map.of("Name", fixture.name + "-retired"));
                        service.updateApplication(fixture.otherApp, Map.of("Name", fixture.name));
                    }
                }
                return selected;
            }).when(service).resolveEnvironment(anyString(), anyString());
            if ("application-before-environment".equals(rename)) {
                reset(service);
                doAnswer(invocation -> {
                    Object selected = invocation.callRealMethod();
                    if (fixture.name.equals(invocation.getArgument(0)) && armed.compareAndSet(true, false)) {
                        service.updateApplication(fixture.app, Map.of("Name", fixture.name + "-retired"));
                        service.updateApplication(fixture.otherApp, Map.of("Name", fixture.name));
                    }
                    return selected;
                }).when(service).resolveApplication(anyString());
            }
            try (SignedIamUsers.User user = fixture.reader()) {
                URI uri = endpoint.resolve("applications/" + fixture.name + "/environments/production");
                AwsRequestSigner signer = AwsRequestSigner.signedAs(user.accessKeyId(), user.secretAccessKey(), "appconfig")
                        .inRegion(region);
                if (presigned) {
                    given().urlEncodingEnabled(false).get(signer.presignedUri("GET", uri, Map.of(), new byte[0]))
                            .then().statusCode(200).body("Id", equalTo(fixture.environment))
                            .body("Description", equalTo("permitted"));
                } else {
                    given().filter(signer).get(uri).then().statusCode(200)
                            .body("Id", equalTo(fixture.environment)).body("Description", equalTo("permitted"));
                }
                assertFalse(armed.get(), "The rename must run after selecting the IAM target");
            } finally {
                reset(service);
            }
        }
    }

    @Test
    void deletedAuthorizedIdCannotBeReinterpretedAsAnotherEnvironmentsName() throws Exception {
        try (Fixture fixture = new Fixture(Region.US_EAST_1)) {
            AtomicBoolean armed = new AtomicBoolean(true);
            doAnswer(invocation -> {
                Object selected = invocation.callRealMethod();
                if (fixture.app.equals(invocation.getArgument(0)) && armed.compareAndSet(true, false)) {
                    service.deleteEnvironment(fixture.app, fixture.environment);
                    service.updateEnvironment(fixture.app, fixture.sibling, Map.of("Name", fixture.environment));
                }
                return selected;
            }).when(service).resolveEnvironment(anyString(), anyString());
            try (SignedIamUsers.User user = fixture.reader();
                 AppConfigClient reader = AwsTestClients.create(AppConfigClient.builder(), user.accessKeyId(),
                         user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
                assertEquals(404, assertThrows(AppConfigException.class, () -> reader.getEnvironment(r ->
                        r.applicationId(fixture.app).environmentId(fixture.environment))).statusCode());
                assertFalse(armed.get());
            } finally {
                reset(service);
            }
        }
    }

    @Test
    void unchangedNamesAndIdsPreserveConditionalGrantsAndSiblingDenials() throws Exception {
        try (Fixture fixture = new Fixture(Region.US_EAST_1);
             SignedIamUsers.User user = fixture.reader();
             AppConfigClient reader = AwsTestClients.create(AppConfigClient.builder(), user.accessKeyId(),
                     user.secretAccessKey(), Region.US_EAST_1, endpoint)) {
            assertEquals(fixture.environment, reader.getEnvironment(r ->
                    r.applicationId(fixture.name).environmentId("production")).id());
            assertEquals(fixture.environment, reader.getEnvironment(r ->
                    r.applicationId(fixture.app).environmentId(fixture.environment)).id());
            assertEquals(403, assertThrows(AppConfigException.class, () -> reader.getEnvironment(r ->
                    r.applicationId(fixture.app).environmentId(fixture.sibling))).statusCode());
        }
    }

    private final class Fixture implements AutoCloseable {
        private final Region region;
        private final AppConfigClient admin;
        private final List<String> applications = new ArrayList<>();
        private final String name = "target-" + UUID.randomUUID();
        private final String app;
        private final String otherApp;
        private final String environment;
        private final String sibling;

        private Fixture(Region region) {
            this.region = region;
            admin = AwsTestClients.create(AppConfigClient.builder(), "test", "test", region, endpoint);
            try {
                app = admin.createApplication(r -> r.name(name)).id();
                applications.add(app);
                otherApp = admin.createApplication(r -> r.name(name + "-other")).id();
                applications.add(otherApp);
                environment = admin.createEnvironment(r -> r.applicationId(app).name("production").description("permitted")).id();
                sibling = admin.createEnvironment(r -> r.applicationId(app).name("staging").description("private")).id();
                admin.createEnvironment(r -> r.applicationId(otherApp).name("production").description("private"));
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private SignedIamUsers.User reader() throws Exception {
            String arn = AwsArnUtils.Arn.of("appconfig", region.id(), "000000000000",
                    "application/" + app + "/environment/" + environment).toString();
            String applicationArn = AwsArnUtils.Arn.of("appconfig", region.id(), "000000000000",
                    "application/" + app).toString();
            String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Action\":\"appconfig:GetEnvironment\",\"Resource\":[\"" + applicationArn + "\",\"" + arn + "\"],"
                    + "\"Condition\":{\"StringEquals\":{\"aws:RequestedRegion\":\"" + region.id() + "\"}}}]}";
            return SignedIamUsers.createUser(endpoint, "environment-target-", "read", policy);
        }

        @Override
        public void close() {
            try {
                for (String application : applications) {
                    for (Environment env : admin.listEnvironments(r -> r.applicationId(application)).items()) {
                        admin.deleteEnvironment(r -> r.applicationId(application).environmentId(env.id()));
                    }
                    assertEquals(List.of(), admin.listEnvironments(r -> r.applicationId(application)).items());
                    admin.deleteApplication(r -> r.applicationId(application));
                    assertEquals(404, assertThrows(AppConfigException.class,
                            () -> admin.getApplication(r -> r.applicationId(application))).statusCode());
                }
            } finally {
                admin.close();
            }
        }
    }
}
