package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.awscore.AwsResponse;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;
import software.amazon.awssdk.services.appconfig.model.Application;
import software.amazon.awssdk.services.appconfig.model.ConfigurationProfileSummary;
import software.amazon.awssdk.services.appconfig.model.DeploymentStrategy;
import software.amazon.awssdk.services.appconfig.model.DeploymentSummary;
import software.amazon.awssdk.services.appconfig.model.Environment;
import software.amazon.awssdk.services.appconfig.model.GetDeploymentResponse;
import software.amazon.awssdk.services.appconfig.model.HostedConfigurationVersionSummary;
import software.amazon.awssdk.services.appconfig.model.ListConfigurationProfilesResponse;
import software.amazon.awssdk.services.appconfig.model.ListDeploymentsResponse;
import software.amazon.awssdk.services.appconfig.model.ListEnvironmentsResponse;
import software.amazon.awssdk.services.appconfig.model.ListHostedConfigurationVersionsResponse;
import software.amazon.awssdk.services.appconfig.model.ResourceNotFoundException;
import software.amazon.awssdk.services.appconfig.model.StartDeploymentResponse;
import software.amazon.awssdk.services.appconfig.model.Validator;
import software.amazon.awssdk.services.appconfig.model.ValidatorType;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@Timeout(90)
class AppConfigDeployedLabelsIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] LABELED = "{\"release\":1}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNLABELED = "{\"release\":2}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INDEPENDENT = "{\"release\":3}".getBytes(StandardCharsets.UTF_8);
    private static final String SCHEMA = """
            {"type":"object","required":["release"],"properties":{"release":{"type":"integer"}}}
            """;
    private static final ExecutionAttribute<Integer> WIRE_ID =
            new ExecutionAttribute<>("appconfig-deployed-label-wire-id");
    private final String marker = "deployed-label-" + UUID.randomUUID();
    private final WireReceipt wire = new WireReceipt();
    private AppConfigClient client;
    private AppConfigDataClient data;
    private List<Application> initialApplications;
    private List<DeploymentStrategy> initialStrategies;
    private String application;
    private String profile;
    private String independent;
    private String environment;
    private String strategy;

    @BeforeEach
    void openOwnedClients() {
        String account = Long.toString(ThreadLocalRandom.current().nextLong(770000000000L, 779999999999L));
        client = AwsTestClients.create(AppConfigClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)),
                account, Region.US_EAST_1, ApacheHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(6)));
        data = AwsTestClients.create(AppConfigDataClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)),
                account, Region.US_EAST_1, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(6)));
        initialApplications = applications();
        initialStrategies = strategies();
    }

    @Test
    void numericValidationRemainsAWorkingControl() {
        configureSchema(false);
        assertEquals(204, client.validateConfiguration(request -> request.applicationId(application)
                .configurationProfileId(profile).configurationVersion("1")).sdkHttpResponse().statusCode());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactLabelValidationUsesTheSelectedHostedVersion(boolean schema) {
        configureSchema(schema);
        Map<String, AwsResponse> before = snapshot();
        Outcome outcome = attempt(() -> client.validateConfiguration(request -> request.applicationId(application)
                .configurationProfileId(profile).configurationVersion("release-a")));
        Map<String, AwsResponse> after = snapshot();
        assertAll(() -> assertEquals(new Outcome(204, null), outcome), () -> assertEquals(before, after));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Release-A", "missing-label"})
    void unknownValidationLabelRefusesWithoutChangingCompleteModeledState(String selector) {
        configureSchema(false);
        Map<String, AwsResponse> before = snapshot();
        Outcome outcome = attempt(() -> client.validateConfiguration(request -> request.applicationId(application)
                .configurationProfileId(profile).configurationVersion(selector)));
        Map<String, AwsResponse> after = snapshot();
        assertAll(() -> assertEquals(new Outcome(404, "ResourceNotFoundException"), outcome),
                () -> assertEquals(before, after));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void labelDeploymentReturnsCanonicalNumberAndLabelInStartGetAndList(boolean schema) throws IOException {
        configureSchema(schema);
        StartDeploymentResponse started = start("release-a");
        JsonNode raw = wire.lastJson("StartDeployment");
        GetDeploymentResponse fetched = client.getDeployment(request -> request.applicationId(application)
                .environmentId(environment).deploymentNumber(started.deploymentNumber()));
        List<DeploymentSummary> listed = deployments(environment);
        assertEquals(1, listed.size());
        DeploymentSummary summary = listed.getFirst();
        assertAll(() -> assertEquals(201, started.sdkHttpResponse().statusCode()),
                () -> assertEquals(1, started.deploymentNumber()),
                () -> assertEquals("1", started.configurationVersion()),
                () -> assertEquals("release-a", started.versionLabel()),
                () -> assertEquals("1", fetched.configurationVersion()),
                () -> assertEquals("release-a", fetched.versionLabel()),
                () -> assertEquals("1", summary.configurationVersion()),
                () -> assertEquals("release-a", summary.versionLabel()),
                () -> assertEquals("1", raw.path("ConfigurationVersion").asText()),
                () -> assertEquals("release-a", raw.path("VersionLabel").asText()));
        GetLatestConfigurationResponse content = poll(newSession());
        assertArrayEquals(LABELED, content.configuration().asByteArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Release-A", "missing-label"})
    void unknownDeploymentLabelRefusesWithoutChangingStateOrConsumingANumber(String selector) {
        configureSchema(false);
        StartDeploymentResponse first = start("1");
        Map<String, AwsResponse> before = snapshot();
        Outcome outcome = attempt(() -> start(selector));
        Map<String, AwsResponse> after = snapshot();
        StartDeploymentResponse next = start("2");
        assertAll(() -> assertEquals(1, first.deploymentNumber()),
                () -> assertEquals(new Outcome(404, "ResourceNotFoundException"), outcome),
                () -> assertEquals(before, after),
                () -> assertEquals(2, next.deploymentNumber()));
    }

    @Test
    void numericDeploymentOfLabeledContentExposesTheHostedLabelOnNewContent() {
        configureSchema(false);
        StartDeploymentResponse started = start("1");
        String token = newSession();
        GetLatestConfigurationResponse response = poll(token);
        assertAll(() -> assertEquals("1", started.configurationVersion()),
                () -> assertEquals("release-a", started.versionLabel()),
                () -> assertArrayEquals(LABELED, response.configuration().asByteArray()),
                () -> assertEquals("application/json", response.contentType()),
                () -> assertEquals("release-a", response.versionLabel()),
                () -> assertEquals(Optional.of("release-a"),
                        response.sdkHttpResponse().firstMatchingHeader("Version-Label")),
                () -> assertEquals(15, response.nextPollIntervalInSeconds()),
                () -> assertNotEquals(token, response.nextPollConfigurationToken()));
    }

    @Test
    void unchangedContentOmitsLabelHeaderAndRetainsContentType() throws InterruptedException {
        configureSchema(false);
        start("1");
        GetLatestConfigurationResponse first = poll(newSession());
        assertEquals(15, first.nextPollIntervalInSeconds());
        Thread.sleep(Duration.ofSeconds(first.nextPollIntervalInSeconds()).plusMillis(200));
        GetLatestConfigurationResponse unchanged = poll(first.nextPollConfigurationToken());
        assertAll(() -> assertArrayEquals(LABELED, first.configuration().asByteArray()),
                () -> assertArrayEquals(new byte[0], unchanged.configuration().asByteArray()),
                () -> assertEquals("application/json", unchanged.contentType()),
                () -> assertNull(unchanged.versionLabel()),
                () -> assertEquals(Optional.empty(), unchanged.sdkHttpResponse().firstMatchingHeader("Version-Label")),
                () -> assertEquals(15, unchanged.nextPollIntervalInSeconds()),
                () -> assertNotEquals(first.nextPollConfigurationToken(), unchanged.nextPollConfigurationToken()));
    }

    @Test
    void numericUnlabeledDeploymentOmitsLabelInDeploymentAndDataResponses() throws IOException {
        configureSchema(false);
        StartDeploymentResponse started = start("2");
        JsonNode raw = wire.lastJson("StartDeployment");
        GetLatestConfigurationResponse response = poll(newSession());
        assertAll(() -> assertEquals("2", started.configurationVersion()),
                () -> assertNull(started.versionLabel()),
                () -> assertFalse(raw.has("VersionLabel")),
                () -> assertArrayEquals(UNLABELED, response.configuration().asByteArray()),
                () -> assertEquals("application/json", response.contentType()),
                () -> assertNull(response.versionLabel()),
                () -> assertEquals(Optional.empty(), response.sdkHttpResponse().firstMatchingHeader("Version-Label")));
    }

    private String createProfile(String suffix) {
        return createProfile(suffix, false);
    }

    private String createProfile(String suffix, boolean schema) {
        return client.createConfigurationProfile(request -> {
            request.applicationId(application).name(marker + "-" + suffix).description(marker)
                    .locationUri("hosted").type("AWS.Freeform");
            if (schema) {
                request.validators(Validator.builder().type(ValidatorType.JSON_SCHEMA).content(SCHEMA).build());
            }
        }).id();
    }

    private int createVersion(String selected, String label, byte[] content) {
        return client.createHostedConfigurationVersion(request -> request.applicationId(application)
                .configurationProfileId(selected).description(marker).versionLabel(label)
                .contentType("application/json").content(SdkBytes.fromByteArray(content))).versionNumber();
    }

    private void configureSchema(boolean enabled) {
        // The unique names and description are reserved before each request, including uncertain outcomes.
        application = client.createApplication(request -> request.name(marker).description(marker)).id();
        profile = createProfile("primary", enabled);
        independent = createProfile("independent");
        environment = client.createEnvironment(request -> request.applicationId(application)
                .name(marker).description(marker)).id();
        strategy = client.createDeploymentStrategy(request -> request.name(marker).description(marker)
                .deploymentDurationInMinutes(0).finalBakeTimeInMinutes(0).growthFactor(100.0F)
                .growthType("LINEAR").replicateTo("NONE")).id();
        assertEquals(1, createVersion(profile, "release-a", LABELED));
        assertEquals(2, createVersion(profile, null, UNLABELED));
        assertEquals(1, createVersion(independent, "release-a", INDEPENDENT));
    }

    private StartDeploymentResponse start(String selector) {
        return client.startDeployment(request -> request.applicationId(application).environmentId(environment)
                .configurationProfileId(profile).configurationVersion(selector).deploymentStrategyId(strategy)
                .description(marker));
    }

    private String newSession() {
        String token = data.startConfigurationSession(request -> request.applicationIdentifier(application)
                .environmentIdentifier(environment).configurationProfileIdentifier(profile)
                .requiredMinimumPollIntervalInSeconds(15)).initialConfigurationToken();
        assertNotNull(token);
        return token;
    }

    private GetLatestConfigurationResponse poll(String token) {
        return data.getLatestConfiguration(request -> request.configurationToken(token));
    }

    private static Outcome attempt(Supplier<? extends AwsResponse> request) {
        try {
            return new Outcome(request.get().sdkHttpResponse().statusCode(), null);
        } catch (AppConfigException error) {
            return new Outcome(error.statusCode(), error.awsErrorDetails().errorCode());
        }
    }

    private static AwsResponse modeled(AwsResponse response) {
        AwsResponse.Builder builder = response.toBuilder();
        builder.sdkHttpResponse(null);
        builder.responseMetadata(null);
        AwsResponse state = builder.build();
        assertTrue(response.equalsBySdkFields(state), "Every modeled field must survive transport-metadata removal");
        return state;
    }

    private Map<String, AwsResponse> snapshot() {
        Map<String, AwsResponse> state = new LinkedHashMap<>();
        state.put("application", modeled(client.getApplication(request -> request.applicationId(application))));
        state.put("strategy", modeled(client.getDeploymentStrategy(request -> request.deploymentStrategyId(strategy))));
        int profilePage = 0;
        for (ListConfigurationProfilesResponse page : client.listConfigurationProfilesPaginator(request ->
                request.applicationId(application).maxResults(50))) {
            state.put("profiles/" + profilePage++, modeled(page));
            for (ConfigurationProfileSummary selected : page.items()) {
                state.put("profile/" + selected.id(), modeled(client.getConfigurationProfile(request ->
                        request.applicationId(application).configurationProfileId(selected.id()))));
                int versionPage = 0;
                for (ListHostedConfigurationVersionsResponse versions : client.listHostedConfigurationVersionsPaginator(request ->
                        request.applicationId(application).configurationProfileId(selected.id()).maxResults(50))) {
                    state.put("versions/" + selected.id() + "/" + versionPage++, modeled(versions));
                    for (HostedConfigurationVersionSummary version : versions.items()) {
                        state.put("version/" + selected.id() + "/" + version.versionNumber(),
                                modeled(client.getHostedConfigurationVersion(request -> request.applicationId(application)
                                        .configurationProfileId(selected.id()).versionNumber(version.versionNumber()))));
                    }
                }
            }
        }
        int environmentPage = 0;
        for (ListEnvironmentsResponse page : client.listEnvironmentsPaginator(request ->
                request.applicationId(application).maxResults(50))) {
            state.put("environments/" + environmentPage++, modeled(page));
            for (Environment selected : page.items()) {
                state.put("environment/" + selected.id(), modeled(client.getEnvironment(request ->
                        request.applicationId(application).environmentId(selected.id()))));
                int deploymentPage = 0;
                for (ListDeploymentsResponse deployments : client.listDeploymentsPaginator(request ->
                        request.applicationId(application).environmentId(selected.id()).maxResults(50))) {
                    state.put("deployments/" + selected.id() + "/" + deploymentPage++, modeled(deployments));
                    for (DeploymentSummary deployment : deployments.items()) {
                        state.put("deployment/" + selected.id() + "/" + deployment.deploymentNumber(),
                                modeled(client.getDeployment(request -> request.applicationId(application)
                                        .environmentId(selected.id()).deploymentNumber(deployment.deploymentNumber()))));
                    }
                }
            }
        }
        return state;
    }

    private List<Application> applications() {
        List<Application> result = new ArrayList<>();
        client.listApplicationsPaginator(request -> request.maxResults(50)).forEach(page -> result.addAll(page.items()));
        return result.stream().sorted(Comparator.comparing(Application::id)).toList();
    }

    private List<DeploymentStrategy> strategies() {
        List<DeploymentStrategy> result = new ArrayList<>();
        client.listDeploymentStrategiesPaginator(request -> request.maxResults(50))
                .forEach(page -> result.addAll(page.items()));
        return result.stream().sorted(Comparator.comparing(DeploymentStrategy::id)).toList();
    }

    private List<ConfigurationProfileSummary> profiles(String owner) {
        List<ConfigurationProfileSummary> result = new ArrayList<>();
        client.listConfigurationProfilesPaginator(request -> request.applicationId(owner).maxResults(50))
                .forEach(page -> result.addAll(page.items()));
        return result;
    }

    private List<Environment> environments(String owner) {
        List<Environment> result = new ArrayList<>();
        client.listEnvironmentsPaginator(request -> request.applicationId(owner).maxResults(50))
                .forEach(page -> result.addAll(page.items()));
        return result;
    }

    private List<HostedConfigurationVersionSummary> versions(String selected) {
        return versions(application, selected);
    }

    private List<HostedConfigurationVersionSummary> versions(String owner, String selected) {
        List<HostedConfigurationVersionSummary> result = new ArrayList<>();
        client.listHostedConfigurationVersionsPaginator(request -> request.applicationId(owner)
                        .configurationProfileId(selected).maxResults(50))
                .forEach(page -> result.addAll(page.items()));
        return result;
    }

    private List<DeploymentSummary> deployments(String selectedEnvironment) {
        return deployments(application, selectedEnvironment);
    }

    private List<DeploymentSummary> deployments(String owner, String selectedEnvironment) {
        List<DeploymentSummary> result = new ArrayList<>();
        client.listDeploymentsPaginator(request -> request.applicationId(owner)
                        .environmentId(selectedEnvironment).maxResults(50))
                .forEach(page -> result.addAll(page.items()));
        return result;
    }

    @AfterEach
    void removeEveryOwnedResourceIncludingUnknownCreateOutcomes() {
        List<Executable> failures = new ArrayList<>();
        try {
            if (client != null) {
                cleanupAttempt(failures, () -> {
                    for (Application app : applications()) {
                        if (marker.equals(app.name()) && marker.equals(app.description())) {
                            cleanupAttempt(failures, () -> removeApplication(app.id(), failures));
                        }
                    }
                });
                cleanupAttempt(failures, () -> {
                    for (DeploymentStrategy item : strategies()) {
                        if (marker.equals(item.name()) && marker.equals(item.description())) {
                            cleanupAttempt(failures, () -> {
                                client.deleteDeploymentStrategy(request -> request.deploymentStrategyId(item.id()));
                                assertThrows(ResourceNotFoundException.class, () -> client.getDeploymentStrategy(request ->
                                        request.deploymentStrategyId(item.id())));
                            });
                        }
                    }
                });
                if (initialApplications != null) {
                    cleanupAttempt(failures, () -> assertEquals(initialApplications, applications()));
                }
                if (initialStrategies != null) {
                    cleanupAttempt(failures, () -> assertEquals(initialStrategies, strategies()));
                }
            }
        } finally {
            cleanupAttempt(failures, wire::save);
            if (data != null) {
                cleanupAttempt(failures, data::close);
            }
            if (client != null) {
                cleanupAttempt(failures, client::close);
            }
        }
        assertAll("Independent owned cleanup, typed absence and full root inventories", failures);
    }

    private void removeApplication(String owner, List<Executable> failures) {
        cleanupAttempt(failures, () -> {
            for (Environment item : environments(owner)) {
                cleanupAttempt(failures, () -> {
                    assertEquals(marker, item.name());
                    assertEquals(marker, item.description());
                    for (DeploymentSummary deployment : deployments(owner, item.id())) {
                        assertEquals(marker, client.getDeployment(request -> request.applicationId(owner)
                                .environmentId(item.id()).deploymentNumber(deployment.deploymentNumber())).description());
                    }
                    client.deleteEnvironment(request -> request.applicationId(owner).environmentId(item.id()));
                    assertThrows(ResourceNotFoundException.class, () -> client.getEnvironment(request ->
                            request.applicationId(owner).environmentId(item.id())));
                });
            }
        });
        cleanupAttempt(failures, () -> {
            for (ConfigurationProfileSummary item : profiles(owner)) {
                cleanupAttempt(failures, () -> {
                    assertTrue(item.name().startsWith(marker + "-"));
                    assertEquals(marker, client.getConfigurationProfile(request -> request.applicationId(owner)
                            .configurationProfileId(item.id())).description());
                    for (HostedConfigurationVersionSummary version : versions(owner, item.id())) {
                        cleanupAttempt(failures, () -> {
                            assertEquals(marker, version.description());
                            client.deleteHostedConfigurationVersion(request -> request.applicationId(owner)
                                    .configurationProfileId(item.id()).versionNumber(version.versionNumber()));
                            assertThrows(ResourceNotFoundException.class, () -> client.getHostedConfigurationVersion(request ->
                                    request.applicationId(owner).configurationProfileId(item.id())
                                            .versionNumber(version.versionNumber())));
                        });
                    }
                    assertTrue(versions(owner, item.id()).isEmpty());
                    client.deleteConfigurationProfile(request -> request.applicationId(owner).configurationProfileId(item.id()));
                    assertThrows(ResourceNotFoundException.class, () -> client.getConfigurationProfile(request ->
                            request.applicationId(owner).configurationProfileId(item.id())));
                });
            }
        });
        cleanupAttempt(failures, () -> {
            assertTrue(environments(owner).isEmpty());
            assertTrue(profiles(owner).isEmpty());
            client.deleteApplication(request -> request.applicationId(owner));
            assertThrows(ResourceNotFoundException.class, () -> client.getApplication(request -> request.applicationId(owner)));
        });
    }

    private static void cleanupAttempt(List<Executable> failures, Executable action) {
        try {
            action.execute();
        } catch (Throwable failure) {
            failures.add(() -> {
                throw failure;
            });
        }
    }

    private record Outcome(int status, String code) {}

    private final class WireReceipt implements ExecutionInterceptor {
        private final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertEquals("localhost", context.httpRequest().getUri().getHost());
            assertEquals("http", context.httpRequest().getUri().getScheme());
            assertTrue(calls.size() < 2000, "Per-case request budget");
            int id = calls.size();
            attributes.putAttribute(WIRE_ID, id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", id);
            row.put("operation", context.request().getClass().getSimpleName().replaceFirst("Request$", ""));
            row.put("method", context.httpRequest().method().name());
            row.put("uri", context.httpRequest().getUri().toString());
            Map<String, String> headers = new LinkedHashMap<>();
            for (String header : List.of("content-type", "description", "versionlabel")) {
                context.httpRequest().firstMatchingHeader(header).ifPresent(value -> headers.put(header, value));
            }
            row.put("requestHeaders", headers);
            try (InputStream stream = context.requestBody().isPresent()
                    ? context.requestBody().orElseThrow().contentStreamProvider().newStream() : InputStream.nullInputStream()) {
                row.put("requestBase64", encode(stream));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            calls.add(row);
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(
                Context.ModifyHttpResponse context, ExecutionAttributes attributes) {
            Map<String, Object> row = calls.get(attributes.getAttribute(WIRE_ID));
            row.put("status", context.httpResponse().statusCode());
            row.put("responseHeaders", context.httpResponse().headers());
            try (InputStream stream = context.responseBody().orElseGet(InputStream::nullInputStream)) {
                String encoded = encode(stream);
                row.put("responseBase64", encoded);
                return Optional.of(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        private String encode(InputStream stream) throws IOException {
            byte[] bytes = stream.readNBytes(262145);
            assertTrue(bytes.length <= 262144, "Wire capture byte limit");
            return Base64.getEncoder().encodeToString(bytes);
        }

        private JsonNode lastJson(String operation) throws IOException {
            for (int index = calls.size() - 1; index >= 0; index--) {
                Map<String, Object> call = calls.get(index);
                if (operation.equals(call.get("operation"))) {
                    return JSON.readTree(Base64.getDecoder().decode((String) call.get("responseBase64")));
                }
            }
            throw new AssertionError("No captured operation " + operation);
        }

        private void save() throws IOException {
            Path directory = Path.of(System.getProperty("audit.appconfig.deployed-labels.directory",
                    "target/deployed-labels-wire"));
            Files.createDirectories(directory);
            JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(marker + ".json").toFile(),
                    Map.of("marker", marker, "calls", calls));
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")));
        }
    }
}
