package io.floci.aws.services.appconfig;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkField;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;
import software.amazon.awssdk.services.appconfig.model.AppConfigResponse;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationResponse;
import software.amazon.awssdk.services.appconfig.model.Validator;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AppConfigHostedSchemaIntegrationTest {
    private static final String SCHEMA = """
            {"$schema":"http://json-schema.org/draft-04/schema#","type":"object",
             "properties":{"enabled":{"type":"boolean"},"limit":{"type":"integer","minimum":1}},
             "required":["enabled","limit"],"additionalProperties":false}
            """;
    private static final String VALID = "{\"enabled\":true,\"limit\":3}";
    private static final String INVALID = "{\"enabled\":true,\"limit\":0}";
    private static final List<Validator> VALIDATORS = List.of(
            Validator.builder().type("JSON_SCHEMA").content(SCHEMA).build());

    private AppConfigClient client;
    private String application;
    private String environment;
    private String profile;
    private String strategy;
    private String name;
    private final List<Integer> versions = new ArrayList<>();

    @BeforeEach
    void createOwnedHostedConfiguration() {
        client = AppConfigClient.builder()
                .endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10)))
                .build();
        name = "hosted-schema-" + UUID.randomUUID();
        application = client.createApplication(request -> request.name(name)).id();
        environment = client.createEnvironment(request -> request.applicationId(application).name(name)).id();
        profile = client.createConfigurationProfile(request -> request.applicationId(application)
                .name(name).locationUri("hosted").type("AWS.Freeform").validators(VALIDATORS)).id();
        strategy = client.createDeploymentStrategy(request -> request.name(name)
                .deploymentDurationInMinutes(0).finalBakeTimeInMinutes(0).growthFactor(100.0f)
                .growthType("LINEAR").replicateTo("NONE")).id();
        for (String content : List.of(VALID, INVALID)) {
            versions.add(client.createHostedConfigurationVersion(request -> request.applicationId(application)
                    .configurationProfileId(profile).contentType("application/json").description(name)
                    .content(SdkBytes.fromUtf8String(content))).versionNumber());
        }
    }

    @AfterEach
    void deleteOnlyCapturedResources() {
        try {
            assertAll(
                    () -> {
                        if (environment != null) {
                            client.deleteEnvironment(request -> request.applicationId(application)
                                    .environmentId(environment).deletionProtectionCheck("BYPASS"));
                            assertAbsent(() -> client.getEnvironment(request -> request.applicationId(application)
                                    .environmentId(environment)));
                        }
                    },
                    () -> {
                        for (Integer version : versions) {
                            client.deleteHostedConfigurationVersion(request -> request.applicationId(application)
                                    .configurationProfileId(profile).versionNumber(version));
                            assertAbsent(() -> client.getHostedConfigurationVersion(request ->
                                    request.applicationId(application).configurationProfileId(profile)
                                            .versionNumber(version)));
                        }
                    },
                    () -> {
                        if (profile != null) {
                            assertTrue(client.listHostedConfigurationVersions(request ->
                                    request.applicationId(application).configurationProfileId(profile)).items().isEmpty());
                            client.deleteConfigurationProfile(request -> request.applicationId(application)
                                    .configurationProfileId(profile).deletionProtectionCheck("BYPASS"));
                            assertAbsent(() -> client.getConfigurationProfile(request ->
                                    request.applicationId(application).configurationProfileId(profile)));
                        }
                    },
                    () -> {
                        if (application != null) {
                            assertTrue(client.listEnvironments(request -> request.applicationId(application))
                                    .items().isEmpty());
                            assertTrue(client.listConfigurationProfiles(request -> request.applicationId(application))
                                    .items().isEmpty());
                            client.deleteApplication(request -> request.applicationId(application));
                            assertAbsent(() -> client.getApplication(request -> request.applicationId(application)));
                        }
                    },
                    () -> {
                        if (strategy != null) {
                            client.deleteDeploymentStrategy(request -> request.deploymentStrategyId(strategy));
                            assertAbsent(() -> client.getDeploymentStrategy(request -> request.deploymentStrategyId(strategy)));
                        }
                    });
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    @Test
    void preservesTheSchemaAndBothOriginalHostedContents() {
        assertAll(
                () -> assertEquals(VALIDATORS, client.getConfigurationProfile(request ->
                        request.applicationId(application).configurationProfileId(profile)).validators()),
                () -> assertEquals(VALID, content(versions.getFirst())),
                () -> assertEquals(INVALID, content(versions.getLast())));
    }

    @Test
    void validValidationLeavesEveryStoredResourceUnchanged() {
        Map<String, Object> before = snapshot();
        Verdict result = validate(versions.getFirst());
        Map<String, Object> after = snapshot();
        assertAll(
                () -> assertEquals(new Verdict(204, null), result),
                () -> assertEquals(before, after));
    }

    @Test
    void invalidValidationIsRefusedWithoutChangingAnyStoredResource() {
        Map<String, Object> before = snapshot();
        Verdict result = validate(versions.getLast());
        Map<String, Object> after = snapshot();
        assertAll(
                () -> assertEquals(new Verdict(400, "BadRequestException"), result),
                () -> assertEquals(before, after));
    }

    @Test
    void validDeploymentServesTheExactValidatedBytes() {
        assertEquals(new Verdict(201, null), deploy(versions.getFirst()));
        assertEquals(new Active(VALID, versions.getFirst().toString(), "application/json"), active());
    }

    @Test
    void invalidDeploymentPreservesAllResourcesAndTheActiveConfiguration() {
        assertEquals(new Verdict(201, null), deploy(versions.getFirst()));
        Map<String, Object> before = snapshot();
        Active activeBefore = active();
        Verdict result = deploy(versions.getLast());
        Map<String, Object> after = snapshot();
        Active activeAfter = active();
        assertAll(
                () -> assertEquals(new Verdict(400, "BadRequestException"), result),
                () -> assertEquals(before, after),
                () -> assertEquals(activeBefore, activeAfter));
    }

    private String content(int version) {
        return client.getHostedConfigurationVersion(request -> request.applicationId(application)
                .configurationProfileId(profile).versionNumber(version)).content().asUtf8String();
    }

    private Verdict validate(int version) {
        return verdict(() -> client.validateConfiguration(request -> request.applicationId(application)
                .configurationProfileId(profile).configurationVersion(Integer.toString(version))));
    }

    private Verdict deploy(int version) {
        return verdict(() -> client.startDeployment(request -> request.applicationId(application)
                .environmentId(environment).configurationProfileId(profile).deploymentStrategyId(strategy)
                .configurationVersion(Integer.toString(version))));
    }

    private Map<String, Object> snapshot() {
        return Map.of(
                "profile", fields(client.getConfigurationProfile(request ->
                        request.applicationId(application).configurationProfileId(profile))),
                "versions", fields(client.listHostedConfigurationVersions(request ->
                        request.applicationId(application).configurationProfileId(profile))),
                "deployments", fields(client.listDeployments(request ->
                        request.applicationId(application).environmentId(environment))));
    }

    private Active active() {
        GetConfigurationResponse response = client.getConfiguration(request ->
                request.application(application).environment(environment).configuration(profile).clientId(name));
        return new Active(response.content().asUtf8String(), response.configurationVersion(), response.contentType());
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
            return values.stream().map(AppConfigHostedSchemaIntegrationTest::fields).toList();
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

    private static void assertAbsent(Supplier<? extends AppConfigResponse> operation) {
        assertEquals(new Verdict(404, "ResourceNotFoundException"), verdict(operation));
    }

    private record Verdict(int status, String code) {}

    private record Active(String content, String version, String contentType) {}
}
