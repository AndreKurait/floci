package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.lambda.LambdaExecutorService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.lambda.model.LambdaFunction;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkField;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;
import software.amazon.awssdk.services.appconfig.model.AppConfigResponse;
import software.amazon.awssdk.services.appconfig.model.ListApplicationsResponse;
import software.amazon.awssdk.services.appconfig.model.ListConfigurationProfilesResponse;
import software.amazon.awssdk.services.appconfig.model.ListDeploymentsResponse;
import software.amazon.awssdk.services.appconfig.model.ListEnvironmentsResponse;
import software.amazon.awssdk.services.appconfig.model.ListHostedConfigurationVersionsResponse;
import software.amazon.awssdk.services.appconfig.model.Validator;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Real SDK AppConfig and Lambda management routes with a scripted Lambda executor. These cases
 * qualify event construction, function outcome handling and stored AppConfig preservation.
 * They do not execute a Lambda runtime or establish permission-failure or deadline behavior.
 */
@QuarkusTest
class AppConfigLambdaValidatorIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<byte[]> CONTENTS = List.of(
            "{\"mode\":\"first\",\"message\":\"café\"}\n".getBytes(StandardCharsets.UTF_8),
            "{ \"mode\": \"second\", \"values\": [1, 2, 3] }".getBytes(StandardCharsets.UTF_8),
            "{\"mode\":\"third\",\"enabled\":false}".getBytes(StandardCharsets.UTF_8),
            "{\"mode\":\"fourth\",\"nested\":{\"value\":\"λ\"}}".getBytes(StandardCharsets.UTF_8));
    private static final Set<String> EVENT_KEYS = Set.of(
            "applicationId", "configurationProfileId", "configurationVersion", "content", "uri");

    @TestHTTPResource("/")
    URI endpoint;

    private AppConfigClient appConfig;
    private LambdaClient lambda;
    private IamClient iam;
    private String application;
    private String profile;
    private String environment;
    private String function;
    private String functionArn;
    private String role;
    private List<Object> applicationsBefore;
    private final List<Integer> versions = new ArrayList<>();
    private final List<Captured> invocations = new CopyOnWriteArrayList<>();
    private volatile String outcome;

    @BeforeEach
    void createOwnedHostedResourcesAndScriptOnlyTheExecutor() throws Exception {
        LambdaExecutorService executor = mock(LambdaExecutorService.class, call -> {
            if (!"invoke".equals(call.getMethod().getName())) {
                return Answers.RETURNS_DEFAULTS.answer(call);
            }
            LambdaFunction target = call.getArgument(0);
            byte[] payload = call.getArgument(1);
            InvocationType type = call.getArgument(2);
            invocations.add(new Captured(target.getFunctionArn(), type, JSON.readTree(payload)));
            String error = "throw".equals(outcome) ? "Unhandled" : null;
            String response = switch (outcome) {
                case "false" -> "false";
                case "object" -> "{\"statusCode\":500,\"error\":\"ordinary return value\"}";
                case "throw" -> "{\"errorType\":\"ValueError\",\"errorMessage\":\"configuration rejected\"}";
                default -> throw new IllegalStateException("No scripted outcome selected");
            };
            return new InvokeResult(200, error, response.getBytes(StandardCharsets.UTF_8), null, "scripted-invocation");
        });
        QuarkusMock.installMockForType(executor, LambdaExecutorService.class);
        appConfig = AwsTestClients.create(AppConfigClient.builder()
                .overrideConfiguration(AppConfigLambdaValidatorIntegrationTest::bound),
                "test", "test", Region.US_EAST_1, endpoint);
        lambda = AwsTestClients.create(LambdaClient.builder()
                .overrideConfiguration(AppConfigLambdaValidatorIntegrationTest::bound),
                "test", "test", Region.US_EAST_1, endpoint);
        iam = AwsTestClients.create(IamClient.builder()
                .overrideConfiguration(AppConfigLambdaValidatorIntegrationTest::bound),
                "test", "test", Region.US_EAST_1, endpoint);
        applicationsBefore = applications();
        String name = "validator-" + UUID.randomUUID();
        String roleName = name + "-role";
        String roleArn = iam.createRole(request -> request.roleName(roleName).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                """)).role().arn();
        role = roleName;
        byte[] zip = code();
        functionArn = lambda.createFunction(request -> request.functionName(name).runtime("python3.13")
                .handler("index.handler").role(roleArn).timeout(3)
                .code(value -> value.zipFile(SdkBytes.fromByteArray(zip)))).functionArn();
        function = name;
        lambda.addPermission(request -> request.functionName(function).statementId("appconfig-validation")
                .principal("appconfig.amazonaws.com").action("lambda:InvokeFunction"));
        application = appConfig.createApplication(request -> request.name(name)).id();
        environment = appConfig.createEnvironment(request -> request.applicationId(application).name(name)).id();
        profile = appConfig.createConfigurationProfile(request -> request.applicationId(application)
                .name(name).locationUri("hosted").type("AWS.Freeform")
                .validators(Validator.builder().type("LAMBDA").content(functionArn).build())).id();
        for (byte[] content : CONTENTS) {
            versions.add(appConfig.createHostedConfigurationVersion(request -> request.applicationId(application)
                    .configurationProfileId(profile).contentType("application/json")
                    .content(SdkBytes.fromByteArray(content))).versionNumber());
        }
    }

    @AfterEach
    void removeEveryCapturedResourceAndRestoreTheApplicationCollection() {
        List<Executable> removals = new ArrayList<>();
        if (appConfig != null) {
            if (environment != null) {
                removals.add(() -> {
                    appConfig.deleteEnvironment(request -> request.applicationId(application)
                            .environmentId(environment).deletionProtectionCheck("BYPASS"));
                    absent(() -> appConfig.getEnvironment(request -> request.applicationId(application)
                            .environmentId(environment)));
                });
            }
            for (Integer version : versions) {
                removals.add(() -> {
                    appConfig.deleteHostedConfigurationVersion(request -> request.applicationId(application)
                            .configurationProfileId(profile).versionNumber(version));
                    absent(() -> appConfig.getHostedConfigurationVersion(request -> request.applicationId(application)
                            .configurationProfileId(profile).versionNumber(version)));
                });
            }
            if (profile != null) {
                removals.add(() -> {
                    assertTrue(appConfig.listHostedConfigurationVersions(request ->
                            request.applicationId(application).configurationProfileId(profile)).items().isEmpty());
                    appConfig.deleteConfigurationProfile(request -> request.applicationId(application)
                            .configurationProfileId(profile).deletionProtectionCheck("BYPASS"));
                    absent(() -> appConfig.getConfigurationProfile(request -> request.applicationId(application)
                            .configurationProfileId(profile)));
                });
            }
            if (application != null) {
                removals.add(() -> {
                    assertTrue(appConfig.listConfigurationProfiles(request ->
                            request.applicationId(application)).items().isEmpty());
                    assertTrue(appConfig.listEnvironments(request -> request.applicationId(application)).items().isEmpty());
                    appConfig.deleteApplication(request -> request.applicationId(application));
                    absent(() -> appConfig.getApplication(request -> request.applicationId(application)));
                });
            }
            if (applicationsBefore != null) {
                removals.add(() -> assertEquals(applicationsBefore, applications()));
            }
        }
        if (function != null) {
            removals.add(() -> {
                lambda.deleteFunction(request -> request.functionName(function));
                ResourceNotFoundException error = assertThrows(ResourceNotFoundException.class,
                        () -> lambda.getFunction(request -> request.functionName(function)));
                assertEquals(404, error.statusCode());
            });
        }
        if (role != null) {
            removals.add(() -> {
                iam.deleteRole(request -> request.roleName(role));
                NoSuchEntityException error = assertThrows(NoSuchEntityException.class,
                        () -> iam.getRole(request -> request.roleName(role)));
                assertEquals(404, error.statusCode());
            });
        }
        try {
            assertAll("Remove independent captured resources", removals);
        } finally {
            if (appConfig != null) {
                appConfig.close();
            }
            if (lambda != null) {
                lambda.close();
            }
            if (iam != null) {
                iam.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "object", "throw"})
    void hostedValidationUsesTheExactEventAndFunctionErrorWithoutChangingStoredConfiguration(String behavior) {
        outcome = behavior;
        List<Executable> checks = new ArrayList<>();
        for (int index = 0; index < versions.size(); index++) {
            int version = versions.get(index);
            String expectedContent = Base64.getEncoder().encodeToString(CONTENTS.get(index));
            Map<String, Object> before = snapshot();
            int priorCalls = invocations.size();
            Verdict result = verdict(() -> appConfig.validateConfiguration(request -> request.applicationId(application)
                    .configurationProfileId(profile).configurationVersion(Integer.toString(version))));
            Map<String, Object> after = snapshot();
            List<Captured> observed = List.copyOf(invocations.subList(priorCalls, invocations.size()));
            checks.add(() -> assertEquals(before, after, "Every selected AppConfig field remains unchanged"));
            checks.add(() -> assertEquals("throw".equals(behavior)
                    ? new Verdict(400, "BadRequestException") : new Verdict(204, null), result));
            checks.add(() -> {
                assertEquals(1, observed.size(), "One synchronous Lambda invocation for this hosted version");
                Captured invocation = observed.getFirst();
                assertEquals(functionArn, invocation.functionArn());
                assertEquals(InvocationType.RequestResponse, invocation.type());
                JsonNode event = invocation.event();
                assertNotNull(event);
                assertTrue(event.isObject());
                assertEquals(EVENT_KEYS, event.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet()));
                event.properties().forEach(entry -> assertTrue(entry.getValue().isTextual(), entry.getKey()));
                assertEquals(JSON.valueToTree(Map.of(
                        "applicationId", application, "configurationProfileId", profile,
                        "configurationVersion", Integer.toString(version), "content", expectedContent,
                        "uri", "hosted")), event);
            });
        }
        assertAll("Every version, verdict and complete stored state", checks);
    }

    private Map<String, Object> snapshot() {
        ListConfigurationProfilesResponse profiles = appConfig.listConfigurationProfiles(request ->
                request.applicationId(application));
        ListEnvironmentsResponse environments = appConfig.listEnvironments(request -> request.applicationId(application));
        ListHostedConfigurationVersionsResponse listedVersions = appConfig.listHostedConfigurationVersions(request ->
                request.applicationId(application).configurationProfileId(profile));
        ListDeploymentsResponse deployments = appConfig.listDeployments(request -> request.applicationId(application)
                .environmentId(environment));
        assertAll(() -> assertNull(profiles.nextToken()), () -> assertNull(environments.nextToken()),
                () -> assertNull(listedVersions.nextToken()), () -> assertNull(deployments.nextToken()),
                () -> assertEquals(1, profiles.items().size()), () -> assertEquals(1, environments.items().size()),
                () -> assertEquals(versions.size(), listedVersions.items().size()),
                () -> assertTrue(deployments.items().isEmpty()));
        List<Object> hosted = new ArrayList<>();
        for (Integer version : versions) {
            hosted.add(fields(appConfig.getHostedConfigurationVersion(request -> request.applicationId(application)
                    .configurationProfileId(profile).versionNumber(version))));
        }
        return Map.of(
                "applications", applications(),
                "application", fields(appConfig.getApplication(request -> request.applicationId(application))),
                "profile", fields(appConfig.getConfigurationProfile(request -> request.applicationId(application)
                        .configurationProfileId(profile))),
                "profiles", fields(profiles),
                "environment", fields(appConfig.getEnvironment(request -> request.applicationId(application)
                        .environmentId(environment))),
                "environments", fields(environments),
                "versions", fields(listedVersions),
                "contents", hosted,
                "deployments", fields(deployments));
    }

    private List<Object> applications() {
        List<Object> result = new ArrayList<>();
        String next = null;
        for (int page = 0; page < 100; page++) {
            String token = next;
            ListApplicationsResponse response = appConfig.listApplications(request ->
                    request.maxResults(50).nextToken(token));
            response.items().forEach(applicationItem -> result.add(fields(applicationItem)));
            next = response.nextToken();
            if (next == null) {
                break;
            }
        }
        assertNull(next, "Bounded collection exhausted");
        result.sort(Comparator.comparing(Object::toString));
        return result;
    }

    private static Object fields(Object value) {
        if (value instanceof SdkBytes bytes) {
            return Base64.getEncoder().encodeToString(bytes.asByteArray());
        }
        if (value instanceof SdkPojo pojo) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (SdkField<?> field : pojo.sdkFields()) {
                result.put(field.memberName(), fields(field.getValueOrDefault(pojo)));
            }
            return result;
        }
        if (value instanceof List<?> values) {
            return values.stream().map(AppConfigLambdaValidatorIntegrationTest::fields).toList();
        }
        if (value instanceof Map<?, ?> values) {
            Map<Object, Object> result = new LinkedHashMap<>();
            values.forEach((key, item) -> result.put(key, fields(item)));
            return result;
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

    private static byte[] code() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.py"));
            zip.write("def handler(event, context):\n    return None\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void bound(ClientOverrideConfiguration.Builder config) {
        config.apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(5))
                .retryPolicy(RetryPolicy.none());
    }

    private record Captured(String functionArn, InvocationType type, JsonNode event) {}

    private record Verdict(int status, String code) {}
}
