package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationResponse;
import software.amazon.awssdk.services.appconfig.model.GetDeploymentResponse;
import software.amazon.awssdk.services.appconfig.model.GetHostedConfigurationVersionResponse;
import software.amazon.awssdk.services.appconfig.model.ResourceNotFoundException;
import software.amazon.awssdk.services.appconfig.model.StopDeploymentResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@Timeout(60)
class AppConfigCompletedRevertMetadataIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExecutionAttribute<Integer> WIRE_ID =
            new ExecutionAttribute<>("floci-appconfig-completed-revert-wire-id");
    private final String marker = UUID.randomUUID().toString();
    private final WireReceipt wire = new WireReceipt();
    private final List<String> profiles = new ArrayList<>();
    private AppConfigClient client;
    private String application;
    private String environment;
    private String strategy;
    private String primary;
    private String independent;
    private String testName;
    private List<JsonNode> baselineStrategies;

    @BeforeEach
    void createOwnedHierarchy(TestInfo info) {
        testName = info.getDisplayName();
        String account = Long.toString(ThreadLocalRandom.current().nextLong(770000000000L, 779999999999L));
        client = AwsTestClients.create(AppConfigClient.builder().overrideConfiguration(config -> config
                        .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                        .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)),
                account, Region.US_EAST_1, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(6)));
        assertEquals(List.of(), applications());
        baselineStrategies = strategies();
        application = client.createApplication(request -> request.name("completed-revert-" + marker)
                .description(marker)).id();
        environment = client.createEnvironment(request -> request.applicationId(application)
                .name("environment").description(marker)).id();
        strategy = client.createDeploymentStrategy(request -> request.name("revert-" + marker)
                .description(marker).deploymentDurationInMinutes(0).finalBakeTimeInMinutes(0)
                .growthFactor(100.0f).growthType("LINEAR").replicateTo("NONE")).id();
        primary = profile("primary");
        independent = profile("independent");
        version(primary, "baseline");
        version(primary, "candidate");
        version(independent, "independent");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void completedRevertRetainsTimestampsAndPrependsTheUserEvent(boolean hasPrevious) {
        int selected = deploySelection(hasPrevious);
        GetDeploymentResponse completed = get(selected);
        Map<String, JsonNode> before = snapshot();
        Instant lowerBound = Instant.now();
        StopDeploymentResponse direct = client.stopDeployment(request -> request.applicationId(application)
                .environmentId(environment).deploymentNumber(selected).allowRevert(true));
        Instant upperBound = Instant.now();
        JsonNode directBody = wire.json();
        GetDeploymentResponse fetched = get(selected);
        JsonNode fetchedBody = wire.json();
        Map<String, JsonNode> after = snapshot();
        JsonNode summary = selectedSummary(after.get("deployments"), selected);
        JsonNode expectedEvent = JSON.createObjectNode()
                .put("EventType", "REVERT_COMPLETED")
                .put("Description", "Deployment reverted by user request").put("TriggeredBy", "USER");
        List<JsonNode> eventMetadata = new ArrayList<>();
        List<Instant> eventTimes = new ArrayList<>();
        for (JsonNode event : directBody.path("EventLog")) {
            ObjectNode metadata = event.deepCopy();
            metadata.remove("OccurredAt");
            eventMetadata.add(metadata);
            eventTimes.add(Instant.parse(event.path("OccurredAt").asText()));
        }
        JsonNode previous = before.get("deployment:" + selected);
        List<JsonNode> expectedHistory = new ArrayList<>();
        expectedHistory.add(expectedEvent);
        for (JsonNode event : previous.path("EventLog")) {
            ObjectNode metadata = event.deepCopy();
            metadata.remove("OccurredAt");
            expectedHistory.add(metadata);
        }
        assertAll(
                () -> assertEquals(202, direct.sdkHttpResponse().statusCode()),
                () -> assertEquals("REVERTED", direct.stateAsString()),
                () -> assertEquals(0.0f, direct.percentageComplete()),
                () -> assertEquals(completed.startedAt(), direct.startedAt()),
                () -> assertEquals(completed.completedAt(), direct.completedAt()),
                () -> assertEquals(completed.startedAt(), fetched.startedAt()),
                () -> assertEquals(completed.completedAt(), fetched.completedAt()),
                () -> assertEquals(previous.get("StartedAt"), summary.get("StartedAt")),
                () -> assertEquals(previous.get("CompletedAt"), summary.get("CompletedAt")),
                () -> assertEquals(expectedHistory, eventMetadata),
                () -> assertEquals(expectedHistory.size(), eventTimes.size()),
                () -> assertTrue(!eventTimes.isEmpty() && !eventTimes.getFirst().isBefore(lowerBound)
                        && !eventTimes.getFirst().isAfter(upperBound)),
                () -> assertEquals(withoutActionInvocations(directBody), withoutActionInvocations(fetchedBody)),
                () -> assertEquals(unaffected(before, selected), unaffected(after, selected)),
                () -> assertEquals(otherSummaries(before.get("deployments"), selected),
                        otherSummaries(after.get("deployments"), selected)),
                () -> assertEquals(activeState(hasPrevious ? "1" : null, "baseline"), after.get("active:" + primary)),
                () -> assertEquals(before.get("active:" + independent), after.get("active:" + independent)));
    }

    @Test
    void completedWithoutAllowRevertAndRepeatedRevertRefuseWithoutChangingWholeState() {
        int selected = deploySelection(true);
        Map<String, JsonNode> before = snapshot();
        Outcome without = attempt(selected, false);
        Map<String, JsonNode> afterWithout = snapshot();
        client.stopDeployment(request -> request.applicationId(application).environmentId(environment)
                .deploymentNumber(selected).allowRevert(true));
        Map<String, JsonNode> afterRevert = snapshot();
        Outcome repeated = attempt(selected, true);
        Map<String, JsonNode> afterRepeat = snapshot();
        assertAll(
                () -> assertEquals(new Outcome(400, "BadRequestException"), without),
                () -> assertEquals(before, afterWithout),
                () -> assertEquals(new Outcome(400, "BadRequestException"), repeated),
                () -> assertEquals(afterRevert, afterRepeat));
    }

    private int deploySelection(boolean hasPrevious) {
        deploy(independent, "1");
        if (hasPrevious) {
            deploy(primary, "1");
        }
        return deploy(primary, "2");
    }

    private String profile(String suffix) {
        String id = client.createConfigurationProfile(request -> request.applicationId(application)
                .name(suffix).description(marker).locationUri("hosted").type("AWS.Freeform")).id();
        profiles.add(id);
        return id;
    }

    private void version(String profile, String value) {
        client.createHostedConfigurationVersion(request -> request.applicationId(application)
                .configurationProfileId(profile).description(marker + ":" + value)
                .contentType("application/octet-stream").content(SdkBytes.fromByteArray(content(value))));
    }

    private static byte[] content(String value) {
        return ("\u0000" + value + " café \u03bb\u00ff").getBytes(StandardCharsets.UTF_8);
    }

    private int deploy(String profile, String version) {
        return client.startDeployment(request -> request.applicationId(application).environmentId(environment)
                .configurationProfileId(profile).configurationVersion(version).deploymentStrategyId(strategy)
                .description(marker)).deploymentNumber();
    }

    private GetDeploymentResponse get(int number) {
        return client.getDeployment(request -> request.applicationId(application).environmentId(environment)
                .deploymentNumber(number));
    }

    private Outcome attempt(int number, boolean allow) {
        try {
            StopDeploymentResponse response = client.stopDeployment(request -> request.applicationId(application)
                    .environmentId(environment).deploymentNumber(number).allowRevert(allow));
            return new Outcome(response.sdkHttpResponse().statusCode(), null);
        } catch (AppConfigException error) {
            return new Outcome(error.statusCode(), error.awsErrorDetails().errorCode());
        }
    }

    private List<JsonNode> collection(Consumer<String> readPage) {
        List<JsonNode> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String token = null;
        for (int page = 0; page < 30; page++) {
            readPage.accept(token);
            JsonNode body = wire.json();
            for (JsonNode value : body.path("Items")) {
                rows.add(value);
            }
            token = body.path("NextToken").isTextual() ? body.path("NextToken").asText() : null;
            if (token == null) {
                return rows.stream().sorted(Comparator.comparing(JsonNode::toString)).toList();
            }
            assertTrue(seen.add(token), "Collection page token must advance");
        }
        throw new AssertionError("Owned collection page budget exceeded");
    }

    private List<JsonNode> applications() {
        return collection(token -> client.listApplications(request -> request.maxResults(1).nextToken(token)));
    }

    private List<JsonNode> strategies() {
        return collection(token -> client.listDeploymentStrategies(request -> request.maxResults(1).nextToken(token)));
    }

    private List<JsonNode> profileCollection() {
        return collection(token -> client.listConfigurationProfiles(request -> request.applicationId(application)
                .maxResults(1).nextToken(token)));
    }

    private List<JsonNode> versions(String profile) {
        return collection(token -> client.listHostedConfigurationVersions(request -> request.applicationId(application)
                .configurationProfileId(profile).maxResults(1).nextToken(token)));
    }

    private List<JsonNode> environments() {
        return collection(token -> client.listEnvironments(request -> request.applicationId(application)
                .maxResults(1).nextToken(token)));
    }

    private Map<String, JsonNode> snapshot() {
        int firstCall = wire.calls.size();
        Map<String, JsonNode> state = new LinkedHashMap<>();
        state.put("applications", JSON.valueToTree(applications()));
        client.getApplication(request -> request.applicationId(application));
        state.put("application", wire.json());
        state.put("environments", JSON.valueToTree(environments()));
        client.getEnvironment(request -> request.applicationId(application).environmentId(environment));
        state.put("environment", wire.json());
        state.put("strategies", JSON.valueToTree(strategies()));
        client.getDeploymentStrategy(request -> request.deploymentStrategyId(strategy));
        state.put("strategy", wire.json());
        List<JsonNode> allProfiles = profileCollection();
        state.put("profiles", JSON.valueToTree(allProfiles));
        assertEquals(new HashSet<>(profiles), new HashSet<>(allProfiles.stream()
                .map(value -> value.path("Id").asText()).toList()));
        for (String profile : profiles) {
            client.getConfigurationProfile(request -> request.applicationId(application).configurationProfileId(profile));
            state.put("profile:" + profile, wire.json());
            List<JsonNode> summaries = versions(profile);
            state.put("versions:" + profile, JSON.valueToTree(summaries));
            for (JsonNode summary : summaries) {
                int number = summary.path("VersionNumber").asInt();
                GetHostedConfigurationVersionResponse version = client.getHostedConfigurationVersion(request ->
                        request.applicationId(application).configurationProfileId(profile).versionNumber(number));
                Map<String, Object> modeled = new LinkedHashMap<>();
                modeled.put("ApplicationId", version.applicationId());
                modeled.put("ConfigurationProfileId", version.configurationProfileId());
                modeled.put("VersionNumber", version.versionNumber());
                modeled.put("Description", version.description());
                modeled.put("ContentType", version.contentType());
                modeled.put("VersionLabel", version.versionLabel());
                modeled.put("KmsKeyArn", version.kmsKeyArn());
                modeled.put("ContentBase64", Base64.getEncoder().encodeToString(version.content().asByteArray()));
                state.put("version:" + profile + ":" + number, JSON.valueToTree(modeled));
            }
            state.put("active:" + profile, active(profile));
        }
        List<JsonNode> deployments = collection(token -> client.listDeployments(request ->
                request.applicationId(application).environmentId(environment).maxResults(1).nextToken(token)));
        state.put("deployments", JSON.valueToTree(deployments));
        for (JsonNode summary : deployments) {
            int number = summary.path("DeploymentNumber").asInt();
            get(number);
            state.put("deployment:" + number, wire.json());
        }
        wire.snapshots.add(Map.of("firstCall", firstCall, "lastCall", wire.calls.size() - 1, "state", state));
        return state;
    }

    private JsonNode active(String profile) {
        try {
            GetConfigurationResponse response = client.getConfiguration(request -> request.application(application)
                    .environment(environment).configuration(profile).clientId("completed-revert-client"));
            return JSON.createObjectNode().put("Status", response.sdkHttpResponse().statusCode())
                    .put("Version", response.configurationVersion()).put("ContentType", response.contentType())
                    .put("ContentBase64", Base64.getEncoder().encodeToString(response.content().asByteArray()));
        } catch (AppConfigException error) {
            return JSON.createObjectNode().put("Status", error.statusCode())
                    .put("ErrorCode", error.awsErrorDetails().errorCode());
        }
    }

    private static JsonNode activeState(String version, String value) {
        if (version == null) {
            return JSON.createObjectNode().put("Status", 400).put("ErrorCode", "BadRequestException");
        }
        return JSON.createObjectNode().put("Status", 200).put("Version", version)
                .put("ContentType", "application/octet-stream")
                .put("ContentBase64", Base64.getEncoder().encodeToString(content(value)));
    }

    private Map<String, JsonNode> unaffected(Map<String, JsonNode> state, int selected) {
        Map<String, JsonNode> result = new LinkedHashMap<>(state);
        result.remove("deployments");
        result.remove("deployment:" + selected);
        result.remove("active:" + primary);
        result.remove("environment");
        result.remove("environments");
        return result;
    }

    private static JsonNode withoutActionInvocations(JsonNode deployment) {
        ObjectNode result = deployment.deepCopy();
        for (JsonNode event : result.path("EventLog")) {
            ((ObjectNode) event).remove("ActionInvocations");
        }
        return result;
    }

    private static JsonNode selectedSummary(JsonNode summaries, int selected) {
        for (JsonNode value : summaries) {
            if (value.path("DeploymentNumber").asInt() == selected) {
                return value;
            }
        }
        throw new AssertionError("Selected deployment missing from full collection");
    }

    private static List<JsonNode> otherSummaries(JsonNode summaries, int selected) {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode value : summaries) {
            if (value.path("DeploymentNumber").asInt() != selected) {
                result.add(value);
            }
        }
        return result;
    }

    @AfterEach
    void removeOwnedHierarchyAndSaveCapture() {
        List<Executable> failures = new ArrayList<>();
        try {
            if (client != null && application != null) {
                if (environment != null) {
                    cleanupAttempt(failures, () -> {
                        assertEquals(marker, client.getEnvironment(request -> request.applicationId(application)
                                .environmentId(environment)).description());
                        assertEquals(204, client.deleteEnvironment(request -> request.applicationId(application)
                                .environmentId(environment)).sdkHttpResponse().statusCode());
                        assertThrows(ResourceNotFoundException.class, () -> client.getEnvironment(request ->
                                request.applicationId(application).environmentId(environment)));
                    });
                }
                for (String profile : profiles) {
                    cleanupAttempt(failures, () -> {
                        assertEquals(marker, client.getConfigurationProfile(request -> request.applicationId(application)
                                .configurationProfileId(profile)).description());
                        for (JsonNode version : versions(profile)) {
                            cleanupAttempt(failures, () -> {
                                assertTrue(version.path("Description").asText().startsWith(marker + ":"));
                                int number = version.path("VersionNumber").asInt();
                                assertEquals(204, client.deleteHostedConfigurationVersion(request ->
                                        request.applicationId(application).configurationProfileId(profile)
                                                .versionNumber(number)).sdkHttpResponse().statusCode());
                                assertThrows(ResourceNotFoundException.class, () -> client.getHostedConfigurationVersion(
                                        request -> request.applicationId(application).configurationProfileId(profile)
                                                .versionNumber(number)));
                            });
                        }
                        assertEquals(List.of(), versions(profile));
                        assertEquals(204, client.deleteConfigurationProfile(request -> request.applicationId(application)
                                .configurationProfileId(profile)).sdkHttpResponse().statusCode());
                        assertThrows(ResourceNotFoundException.class, () -> client.getConfigurationProfile(request ->
                                request.applicationId(application).configurationProfileId(profile)));
                    });
                }
                cleanupAttempt(failures, () -> {
                    assertEquals(List.of(), profileCollection());
                    assertEquals(List.of(), environments());
                    assertEquals(marker, client.getApplication(request -> request.applicationId(application)).description());
                    assertEquals(204, client.deleteApplication(request ->
                            request.applicationId(application)).sdkHttpResponse().statusCode());
                    assertThrows(ResourceNotFoundException.class,
                            () -> client.getApplication(request -> request.applicationId(application)));
                    assertEquals(List.of(), applications());
                });
            }
            if (client != null && strategy != null) {
                cleanupAttempt(failures, () -> {
                    assertEquals(marker, client.getDeploymentStrategy(request -> request.deploymentStrategyId(strategy))
                            .description());
                    assertEquals(204, client.deleteDeploymentStrategy(request ->
                            request.deploymentStrategyId(strategy)).sdkHttpResponse().statusCode());
                    assertThrows(ResourceNotFoundException.class,
                            () -> client.getDeploymentStrategy(request -> request.deploymentStrategyId(strategy)));
                    assertEquals(baselineStrategies, strategies());
                });
            }
        } finally {
            wire.cleanupSucceeded = failures.isEmpty();
            cleanupAttempt(failures, wire::save);
            if (client != null) {
                cleanupAttempt(failures, client::close);
            }
        }
        assertAll("Owned cleanup and complete capture", failures);
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
        private final List<Map<String, Object>> snapshots = new ArrayList<>();
        private boolean cleanupSucceeded;

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertEquals("localhost", context.httpRequest().getUri().getHost());
            assertEquals("http", context.httpRequest().getUri().getScheme());
            int id = calls.size();
            attributes.putAttribute(WIRE_ID, id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", id);
            row.put("operation", context.request().getClass().getSimpleName().replaceFirst("Request$", ""));
            row.put("method", context.httpRequest().method().name());
            row.put("uri", context.httpRequest().getUri().toString());
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (String header : List.of("accept", "allow-revert", "content-type", "description", "latest-version-number", "versionlabel")) {
                context.httpRequest().firstMatchingHeader(header)
                        .ifPresent(value -> headers.put(header, List.of(value)));
            }
            row.put("requestHeaders", headers);
            row.put("requestBodyPresent", context.requestBody().isPresent());
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

        private JsonNode json() {
            try {
                JsonNode result = JSON.readTree(Base64.getDecoder().decode(
                        (String) calls.getLast().get("responseBase64")));
                assertNotNull(result, "Expected captured JSON response");
                return result;
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        private void save() throws IOException {
            Path directory = Path.of(System.getProperty(
                    "audit.appconfig.completed-revert.directory", "target/completed-revert-wire"));
            Files.createDirectories(directory);
            JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(marker + ".json").toFile(),
                    Map.of("marker", marker, "test", testName, "calls", calls,
                            "snapshots", snapshots, "cleanupSucceeded", cleanupSucceeded));
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")));
        }
    }
}
