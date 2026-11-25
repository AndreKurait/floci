package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkField;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;
import software.amazon.awssdk.services.appconfig.model.AppConfigResponse;
import software.amazon.awssdk.services.appconfig.model.CreateApplicationResponse;
import software.amazon.awssdk.services.appconfig.model.CreateDeploymentStrategyResponse;
import software.amazon.awssdk.services.appconfig.model.ListApplicationsResponse;
import software.amazon.awssdk.services.appconfig.model.ListDeploymentStrategiesResponse;
import software.amazon.awssdk.services.appconfig.model.Validator;
import software.amazon.awssdk.services.sts.StsClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class AppConfigSchemaIamIntegrationTest {
    private static final String ACTION = "appconfig:ValidateConfiguration";
    private static final String SCHEMA = "{\"type\":\"integer\",\"minimum\":1}";
    private static final Verdict ALLOWED = new Verdict(204, null);
    private static final Verdict DENIED = new Verdict(403, "AccessDeniedException");

    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @CsvSource({"CreateApplication,application", "CreateDeploymentStrategy,deploymentstrategy"})
    void taggedCreatesRequirePermissionOnTheirMintedResourceType(String operation, String type) throws Exception {
        List<String> created = new ArrayList<>();
        try (AppConfigClient admin = client("test", "test", Region.US_EAST_1);
             StsClient identity = AwsTestClients.create(
                     StsClient.builder(), "test", "test", Region.US_EAST_1, endpoint)) {
            String prefix = "arn:aws:appconfig:us-east-1:" + identity.getCallerIdentity(request -> {}).account() + ":";
            String resource = prefix + type + "/*";
            try {
                for (int trial = 0; trial < 4; trial++) {
                    List<Map<String, Object>> statements = new ArrayList<>();
                    statements.add(Map.of("Effect", "Allow", "Action", "appconfig:" + operation, "Resource", "*"));
                    if (trial > 0) {
                        statements.add(Map.of("Effect", "Allow", "Action", "appconfig:TagResource",
                                "Resource", trial == 1 ? prefix + "environment/*" : resource));
                    }
                    if (trial == 2) {
                        statements.add(Map.of("Effect", "Deny", "Action", "appconfig:TagResource", "Resource", resource));
                    }
                    try (SignedIamUsers.User user = user(statements);
                         AppConfigClient caller = client(user.accessKeyId(), user.secretAccessKey(), Region.US_EAST_1)) {
                        assertCaller(user, Region.US_EAST_1);
                        Map<String, List<Object>> before = createdResourceSnapshot(admin);
                        Verdict result = createTagged(caller, operation, "schema-tag-" + UUID.randomUUID(), created);
                        Map<String, List<Object>> after = createdResourceSnapshot(admin);
                        if (trial < 3) {
                            assertAll(() -> assertEquals(DENIED, result), () -> assertEquals(before, after));
                        } else {
                            assertEquals(new Verdict(201, null), result);
                            String collection = "application".equals(type) ? "applications" : "strategies";
                            String other = "application".equals(type) ? "strategies" : "applications";
                            String id = created.getLast();
                            List<Object> retained = after.get(collection).stream()
                                    .filter(value -> !id.equals(((Map<?, ?>) value).get("Id"))).toList();
                            assertAll(() -> assertEquals(before.get(other), after.get(other)),
                                    () -> assertEquals(before.get(collection), retained),
                                    () -> assertEquals(before.get(collection).size() + 1, after.get(collection).size()));
                        }
                    }
                }
            } finally {
                List<Executable> removals = new ArrayList<>();
                for (String id : created) {
                    removals.add(() -> {
                        if ("application".equals(type)) {
                            admin.deleteApplication(request -> request.applicationId(id));
                            absent(() -> admin.getApplication(request -> request.applicationId(id)));
                        } else {
                            admin.deleteDeploymentStrategy(request -> request.deploymentStrategyId(id));
                            absent(() -> admin.getDeploymentStrategy(request -> request.deploymentStrategyId(id)));
                        }
                    });
                }
                assertAll("Every captured tagged-create resource is removed independently", removals);
            }
        }
    }

    private static Verdict createTagged(AppConfigClient caller, String operation, String name, List<String> created) {
        return verdict(() -> {
            if ("CreateApplication".equals(operation)) {
                CreateApplicationResponse response = caller.createApplication(request -> request.name(name).tags(Map.of("audit", "owned")));
                created.add(response.id());
                return response;
            }
            CreateDeploymentStrategyResponse response = caller.createDeploymentStrategy(request -> request.name(name).deploymentDurationInMinutes(0)
                    .finalBakeTimeInMinutes(0).growthFactor(100.0f).growthType("LINEAR").replicateTo("NONE")
                    .tags(Map.of("audit", "owned")));
            created.add(response.id());
            return response;
        });
    }

    private static Map<String, List<Object>> createdResourceSnapshot(AppConfigClient admin) {
        List<Object> applications = new ArrayList<>();
        List<Object> strategies = new ArrayList<>();
        String next = null;
        for (int page = 0; page < 100; page++) {
            String token = next;
            ListApplicationsResponse response = admin.listApplications(request -> request.maxResults(50).nextToken(token));
            response.items().forEach(value -> applications.add(fields(value)));
            next = response.nextToken();
            if (next == null) {
                break;
            }
        }
        assertEquals(null, next, "Complete bounded application collection");
        for (int page = 0; page < 100; page++) {
            String token = next;
            ListDeploymentStrategiesResponse response = admin.listDeploymentStrategies(request -> request.maxResults(50).nextToken(token));
            response.items().forEach(value -> strategies.add(fields(value)));
            next = response.nextToken();
            if (next == null) {
                break;
            }
        }
        assertEquals(null, next, "Complete bounded strategy collection");
        applications.sort(Comparator.comparing(Object::toString));
        strategies.sort(Comparator.comparing(Object::toString));
        return Map.of("applications", applications, "strategies", strategies);
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "cn-north-1,aws-cn", "us-gov-west-1,aws-us-gov"})
    void bothCanonicalResourceGrantsAreRequiredForIdsAndNames(String regionName, String partition) throws Exception {
        Region region = Region.of(regionName);
        try (Fixture fixture = new Fixture(region)) {
            String applicationArn = fixture.applicationArn(partition);
            String profileArn = applicationArn + "/configurationprofile/" + fixture.profile;
            Map<String, Object> before = fixture.snapshot();
            for (List<String> resources : List.of(
                    List.of(applicationArn), List.of(profileArn),
                    List.of(applicationArn, applicationArn + "/configurationprofile/" + fixture.sibling))) {
                try (SignedIamUsers.User user = user(List.of(statement("Allow", resources)));
                     AppConfigClient caller = client(user.accessKeyId(), user.secretAccessKey(), region)) {
                    assertCaller(user, region);
                    Verdict result = fixture.validate(caller, false, fixture.versions.getFirst());
                    Map<String, Object> after = fixture.snapshot();
                    assertAll(() -> assertEquals(DENIED, result), () -> assertEquals(before, after));
                }
            }
            try (SignedIamUsers.User user = user(List.of(statement("Allow", List.of(applicationArn, profileArn))));
                 AppConfigClient caller = client(user.accessKeyId(), user.secretAccessKey(), region)) {
                assertCaller(user, region);
                for (boolean names : List.of(false, true)) {
                    Verdict result = fixture.validate(caller, names, fixture.versions.getFirst());
                    Map<String, Object> after = fixture.snapshot();
                    assertAll(() -> assertEquals(ALLOWED, result), () -> assertEquals(before, after));
                }
                Verdict invalid = fixture.validate(caller, false, fixture.versions.getLast());
                Map<String, Object> after = fixture.snapshot();
                assertAll(() -> assertEquals(new Verdict(400, "BadRequestException"), invalid),
                        () -> assertEquals(before, after));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "cn-north-1,aws-cn", "us-gov-west-1,aws-us-gov"})
    void explicitDenyOnEitherResourcePrecedesValidationAndKeepsEveryStoredField(String regionName, String partition)
            throws Exception {
        Region region = Region.of(regionName);
        try (Fixture fixture = new Fixture(region)) {
            String applicationArn = fixture.applicationArn(partition);
            String profileArn = applicationArn + "/configurationprofile/" + fixture.profile;
            Map<String, Object> before = fixture.snapshot();
            for (String denied : List.of(applicationArn, profileArn)) {
                try (SignedIamUsers.User user = user(List.of(
                        statement("Allow", List.of("*")), statement("Deny", List.of(denied))));
                     AppConfigClient caller = client(user.accessKeyId(), user.secretAccessKey(), region)) {
                    assertCaller(user, region);
                    for (boolean names : List.of(false, true)) {
                        Verdict result = fixture.validate(caller, names, fixture.versions.getLast());
                        Map<String, Object> after = fixture.snapshot();
                        assertAll(() -> assertEquals(DENIED, result), () -> assertEquals(before, after));
                    }
                }
            }
        }
    }

    @Test
    void aValidationPathVariantCannotSkipTheRequiredGrant() throws Exception {
        try (Fixture fixture = new Fixture(Region.US_EAST_1);
             SignedIamUsers.User user = user(List.of(Map.of(
                     "Effect", "Allow", "Action", "appconfig:GetApplication", "Resource", "*")))) {
            assertCaller(user, Region.US_EAST_1);
            Map<String, Object> before = fixture.snapshot();
            String path = "/applications/" + fixture.application + "/configurationprofiles/" + fixture.profile
                    + "/validators";
            for (String suffix : List.of("", "/", ";variant=1")) {
                URI target = endpoint.resolve(path + suffix + "?configuration_version=" + fixture.versions.getFirst());
                Response response = given().urlEncodingEnabled(false)
                        .headers(user.signer("appconfig").headersFor("POST", target, Map.of(), new byte[0]))
                        .contentType("application/json").body(new byte[0]).post(target);
                Map<String, Object> after = fixture.snapshot();
                assertAll(() -> assertEquals(403, response.statusCode(), suffix),
                        () -> assertTrue(response.asString().contains("appconfig:ValidateConfiguration"), suffix),
                        () -> assertEquals(before, after));
            }
        }
    }

    private SignedIamUsers.User user(List<Map<String, Object>> statements) throws Exception {
        return SignedIamUsers.createUser(endpoint, "schema-iam-", "validate",
                new ObjectMapper().writeValueAsString(Map.of("Version", "2012-10-17", "Statement", statements)));
    }

    private static Map<String, Object> statement(String effect, List<String> resources) {
        return Map.of("Effect", effect, "Action", ACTION, "Resource", resources);
    }

    private void assertCaller(SignedIamUsers.User user, Region region) {
        try (StsClient identity = AwsTestClients.create(
                StsClient.builder(), user.accessKeyId(), user.secretAccessKey(), region, endpoint)) {
            assertTrue(identity.getCallerIdentity(request -> {}).arn().endsWith(":user/" + user.name()));
        }
    }

    private AppConfigClient client(String key, String secret, Region region) {
        return AwsTestClients.create(AppConfigClient.builder()
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10))),
                key, secret, region, endpoint);
    }

    private final class Fixture implements AutoCloseable {
        private final AppConfigClient admin;
        private final Region region;
        private final String name = "schema-" + UUID.randomUUID();
        private String account;
        private String application;
        private String environment;
        private String profile;
        private String sibling;
        private final List<Integer> versions = new ArrayList<>();

        private Fixture(Region region) {
            this.region = region;
            admin = client("test", "test", region);
            try {
                try (StsClient identity = AwsTestClients.create(StsClient.builder(), "test", "test", region, endpoint)) {
                    account = identity.getCallerIdentity(request -> {}).account();
                }
                application = admin.createApplication(request -> request.name(name)).id();
                environment = admin.createEnvironment(request -> request.applicationId(application).name(name)).id();
                profile = admin.createConfigurationProfile(request -> request.applicationId(application)
                        .name(name + "-profile").locationUri("hosted").type("AWS.Freeform")
                        .validators(Validator.builder().type("JSON_SCHEMA").content(SCHEMA).build())).id();
                sibling = admin.createConfigurationProfile(request -> request.applicationId(application)
                        .name(name + "-sibling").locationUri("hosted").type("AWS.Freeform")).id();
                for (String content : List.of("1", "0")) {
                    versions.add(admin.createHostedConfigurationVersion(request -> request.applicationId(application)
                            .configurationProfileId(profile).contentType("application/json").description(name)
                            .content(SdkBytes.fromUtf8String(content))).versionNumber());
                }
            } catch (RuntimeException | Error failure) {
                try {
                    close();
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private String applicationArn(String partition) {
            return "arn:" + partition + ":appconfig:" + region.id() + ":" + account + ":application/" + application;
        }

        private Verdict validate(AppConfigClient caller, boolean names, int version) {
            return verdict(() -> caller.validateConfiguration(request -> request
                    .applicationId(names ? name : application)
                    .configurationProfileId(names ? name + "-profile" : profile)
                    .configurationVersion(Integer.toString(version))));
        }

        private Map<String, Object> snapshot() {
            return Map.of(
                    "application", fields(admin.getApplication(request -> request.applicationId(application))),
                    "environment", fields(admin.getEnvironment(request ->
                            request.applicationId(application).environmentId(environment))),
                    "profile", fields(admin.getConfigurationProfile(request ->
                            request.applicationId(application).configurationProfileId(profile))),
                    "sibling", fields(admin.getConfigurationProfile(request ->
                            request.applicationId(application).configurationProfileId(sibling))),
                    "profiles", fields(admin.listConfigurationProfiles(request -> request.applicationId(application))),
                    "versions", fields(admin.listHostedConfigurationVersions(request ->
                            request.applicationId(application).configurationProfileId(profile))),
                    "contents", versions.stream().map(version -> fields(admin.getHostedConfigurationVersion(request ->
                            request.applicationId(application).configurationProfileId(profile).versionNumber(version)))).toList(),
                    "deployments", fields(admin.listDeployments(request ->
                            request.applicationId(application).environmentId(environment))));
        }

        @Override
        public void close() {
            try {
                assertAll(
                        () -> {
                            if (environment != null) {
                                admin.deleteEnvironment(request -> request.applicationId(application)
                                        .environmentId(environment).deletionProtectionCheck("BYPASS"));
                                absent(() -> admin.getEnvironment(request ->
                                        request.applicationId(application).environmentId(environment)));
                            }
                        },
                        () -> {
                            for (int version : versions) {
                                admin.deleteHostedConfigurationVersion(request -> request.applicationId(application)
                                        .configurationProfileId(profile).versionNumber(version));
                                absent(() -> admin.getHostedConfigurationVersion(request -> request
                                        .applicationId(application).configurationProfileId(profile).versionNumber(version)));
                            }
                        },
                        () -> removeProfile(profile),
                        () -> removeProfile(sibling),
                        () -> {
                            if (application != null) {
                                assertTrue(admin.listEnvironments(request -> request.applicationId(application)).items().isEmpty());
                                assertTrue(admin.listConfigurationProfiles(request -> request.applicationId(application)).items().isEmpty());
                                admin.deleteApplication(request -> request.applicationId(application));
                                absent(() -> admin.getApplication(request -> request.applicationId(application)));
                            }
                        });
            } finally {
                admin.close();
            }
        }

        private void removeProfile(String selected) {
            if (selected != null) {
                assertTrue(admin.listHostedConfigurationVersions(request -> request
                        .applicationId(application).configurationProfileId(selected)).items().isEmpty());
                admin.deleteConfigurationProfile(request -> request.applicationId(application)
                        .configurationProfileId(selected).deletionProtectionCheck("BYPASS"));
                absent(() -> admin.getConfigurationProfile(request ->
                        request.applicationId(application).configurationProfileId(selected)));
            }
        }
    }

    private static Object fields(Object value) {
        if (value instanceof SdkPojo pojo) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (SdkField<?> field : pojo.sdkFields()) {
                result.put(field.memberName(), fields(field.getValueOrDefault(pojo)));
            }
            return result;
        }
        if (value instanceof List<?> values) {
            return values.stream().map(AppConfigSchemaIamIntegrationTest::fields).toList();
        }
        return value;
    }

    private static Verdict verdict(Supplier<? extends AppConfigResponse> operation) {
        try {
            return new Verdict(operation.get().sdkHttpResponse().statusCode(), null);
        } catch (AppConfigException error) {
            return new Verdict(error.statusCode(), error.awsErrorDetails().errorCode());
        }
    }

    private static void absent(Supplier<? extends AppConfigResponse> operation) {
        assertEquals(new Verdict(404, "ResourceNotFoundException"), verdict(operation));
    }

    private record Verdict(int status, String code) {}
}
