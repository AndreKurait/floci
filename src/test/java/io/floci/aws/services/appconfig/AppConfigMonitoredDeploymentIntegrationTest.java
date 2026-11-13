package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfig.model.AppConfigException;
import software.amazon.awssdk.services.appconfig.model.AppConfigResponse;
import software.amazon.awssdk.services.appconfig.model.GetConfigurationResponse;
import software.amazon.awssdk.services.appconfig.model.GetDeploymentResponse;
import software.amazon.awssdk.services.appconfig.model.Monitor;
import software.amazon.awssdk.services.appconfig.model.StartDeploymentResponse;
import software.amazon.awssdk.services.appconfig.model.StopDeploymentResponse;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** SDK controls use only public APIs and retain every request/response, including failed assertions. */
@QuarkusTest
class AppConfigMonitoredDeploymentIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ORIGINAL = "{\"value\":\"original\"}";
    private static final String CANDIDATE = "{\"value\":\"candidate\"}";
    private static final String ACCOUNT = "771661204010";

    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"LINEAR", "EXPONENTIAL"})
    void nonzeroDurationStartsDeploymentBeforeTheBakeWindow(String growth) throws Exception {
        try (Cloud cloud = cloud("duration-" + growth, ACCOUNT, "us-east-1")) {
            cloud.prepare();
            String strategy = cloud.strategy(1, 1, growth, 20f);
            StartDeploymentResponse started = cloud.deploy(cloud.candidateVersion, strategy);
            cloud.observe("started", cloud.raw());
            assertAll(
                    () -> assertEquals("DEPLOYING", started.stateAsString()),
                    () -> assertEquals(0f, started.percentageComplete()),
                    () -> assertEquals(1, started.deploymentDurationInMinutes()),
                    () -> assertEquals(1, started.finalBakeTimeInMinutes()),
                    () -> assertEquals(growth, started.growthTypeAsString()));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "771661204010,us-east-1,ALARM",
            "771661204010,us-west-2,INSUFFICIENT_DATA",
            "771661204011,us-east-1,ALARM",
            "771661204011,cn-north-1,INSUFFICIENT_DATA"
    })
    void enabledAlarmRestoresConfigurationWithoutDeploymentReads(
            String account, String region, String alarmState) throws Exception {
        try (Cloud cloud = cloud("background-" + alarmState, account, region)) {
            cloud.prepare();
            StartDeploymentResponse started = cloud.deploy(cloud.candidateVersion, cloud.strategy(0, 5, "LINEAR", 100f));
            assertEquals("BAKING", started.stateAsString());
            assertEquals(CANDIDATE, cloud.active().content().asUtf8String());
            cloud.setAlarm(alarmState);

            // GetConfiguration reads active content. It does not refresh a deployment or advance its clock.
            await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(250))
                    .untilAsserted(() -> assertEquals(ORIGINAL, cloud.active().content().asUtf8String()));
            cloud.observe("restorationSeenBeforeFirstDeploymentRead", true);

            GetDeploymentResponse rolledBack = cloud.config.getDeployment(request -> request.applicationId(cloud.application)
                    .environmentId(cloud.environment).deploymentNumber(started.deploymentNumber()));
            JsonNode raw = cloud.raw();
            List<JsonNode> alarmEvents = new ArrayList<>();
            raw.path("EventLog").forEach(event -> {
                if (event.path("EventType").asText().startsWith("ROLLBACK_")) {
                    alarmEvents.add(event);
                }
            });
            cloud.observe("rolledBack", raw);
            assertAll(
                    () -> assertEquals("ROLLED_BACK", rolledBack.stateAsString()),
                    () -> assertEquals(0f, rolledBack.percentageComplete()),
                    () -> assertNotNull(rolledBack.completedAt()),
                    () -> assertEquals(Integer.toString(cloud.originalVersion), cloud.active().configurationVersion()),
                    () -> assertEquals(List.of("ROLLBACK_COMPLETED", "ROLLBACK_STARTED"), alarmEvents.stream()
                            .map(event -> event.path("EventType").asText()).toList()),
                    () -> assertTrue(alarmEvents.stream().allMatch(event ->
                            "CLOUDWATCH_ALARM".equals(event.path("TriggeredBy").asText()))),
                    () -> assertTrue(alarmEvents.stream().allMatch(event ->
                            event.path("Description").asText().contains(cloud.alarmArn)
                                    && event.path("Description").asText().contains(alarmState))));
        }
    }

    @Test
    void disabledAlarmActionsPreserveTheCandidate() throws Exception {
        try (Cloud cloud = cloud("disabled", ACCOUNT, "us-east-1")) {
            cloud.prepare();
            StartDeploymentResponse started = cloud.deploy(cloud.candidateVersion, cloud.strategy(0, 5, "LINEAR", 100f));
            cloud.metrics.putMetricAlarm(request -> request.alarmName(cloud.alarmName)
                    .namespace("Floci/MonitorFixture").metricName("Owned").period(60).evaluationPeriods(1)
                    .threshold(1.0).statistic("Average").comparisonOperator("GreaterThanThreshold")
                    .treatMissingData("ignore").actionsEnabled(false));
            cloud.setAlarm("ALARM");
            await().during(Duration.ofMillis(1250)).atMost(Duration.ofSeconds(4))
                    .pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> assertEquals(CANDIDATE, cloud.active().content().asUtf8String()));
            GetDeploymentResponse current = cloud.config.getDeployment(request -> request.applicationId(cloud.application)
                    .environmentId(cloud.environment).deploymentNumber(started.deploymentNumber()));
            assertEquals("BAKING", current.stateAsString());
        }
    }

    @Test
    void completedDeploymentDoesNotResumeAlarmMonitoring() throws Exception {
        try (Cloud cloud = cloud("completed", ACCOUNT, "us-east-1")) {
            cloud.prepare();
            StartDeploymentResponse completed = cloud.deploy(cloud.candidateVersion, cloud.immediate);
            assertEquals("COMPLETE", completed.stateAsString());
            cloud.setAlarm("ALARM");
            await().during(Duration.ofMillis(1250)).atMost(Duration.ofSeconds(4))
                    .pollInterval(Duration.ofMillis(100))
                    .untilAsserted(() -> assertEquals(CANDIDATE, cloud.active().content().asUtf8String()));
            assertEquals("COMPLETE", cloud.config.getDeployment(request -> request.applicationId(cloud.application)
                    .environmentId(cloud.environment).deploymentNumber(completed.deploymentNumber())).stateAsString());
        }
    }

    @Test
    void missingMonitorRefusesStartBeforeChangingDeploymentsOrActiveContent() throws Exception {
        try (Cloud cloud = cloud("missing", ACCOUNT, "us-east-1")) {
            cloud.prepare();
            String strategy = cloud.strategy(0, 5, "LINEAR", 100f);
            cloud.metrics.deleteAlarms(request -> request.alarmNames(cloud.alarmName));
            JsonNode before = cloud.deployments();
            Outcome actual = outcome(() -> cloud.deploy(cloud.candidateVersion, strategy));
            JsonNode after = cloud.deployments();
            cloud.observe("refusedStart", actual);
            cloud.observe("deploymentsBefore", before);
            cloud.observe("deploymentsAfter", after);
            assertAll(
                    () -> assertEquals(new Outcome(400, "BadRequestException"), actual),
                    () -> assertEquals(before, after),
                    () -> assertEquals(ORIGINAL, cloud.active().content().asUtf8String()));
        }
    }

    @Test
    void explicitStopStillRestoresContentWhenTheMonitorDisappears() throws Exception {
        try (Cloud cloud = cloud("stop-unreadable", ACCOUNT, "us-east-1")) {
            cloud.prepare();
            StartDeploymentResponse started = cloud.deploy(cloud.candidateVersion, cloud.strategy(0, 5, "LINEAR", 100f));
            cloud.metrics.deleteAlarms(request -> request.alarmNames(cloud.alarmName));
            StopDeploymentResponse stopped = cloud.config.stopDeployment(request -> request.applicationId(cloud.application)
                    .environmentId(cloud.environment).deploymentNumber(started.deploymentNumber()).allowRevert(true));
            assertAll(
                    () -> assertEquals("ROLLED_BACK", stopped.stateAsString()),
                    () -> assertEquals(ORIGINAL, cloud.active().content().asUtf8String()));
        }
    }

    private Cloud cloud(String label, String account, String region) {
        return new Cloud(label, account, region);
    }

    private record Outcome(int status, String code) {}

    private static Outcome outcome(Supplier<? extends AppConfigResponse> action) {
        try {
            return new Outcome(action.get().sdkHttpResponse().statusCode(), null);
        } catch (AppConfigException error) {
            return new Outcome(error.statusCode(), error.awsErrorDetails().errorCode());
        }
    }

    private final class Cloud implements AutoCloseable {
        private final String label;
        private final String account;
        private final String region;
        private final String name = "monitor-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);
        private final String alarmName = name + "-alarm";
        private final String roleName = name + "-role";
        private final String alarmArn;
        private final TapeHttpClient transport = new TapeHttpClient();
        private final AppConfigClient config;
        private final CloudWatchClient metrics;
        private final IamClient iam;
        private final List<String> strategies = new ArrayList<>();
        private final Map<String, Object> observations = new LinkedHashMap<>();
        private String application;
        private String environment;
        private String profile;
        private String immediate;
        private boolean roleCreated;
        private boolean policyCreated;
        private int originalVersion;
        private int candidateVersion;

        private Cloud(String label, String account, String region) {
            assertTrue(List.of("localhost", "127.0.0.1", "::1").contains(endpoint.getHost()), endpoint.toString());
            this.label = label;
            this.account = account;
            this.region = region;
            String partition = region.startsWith("cn-") ? "aws-cn" : "aws";
            this.alarmArn = "arn:" + partition + ":cloudwatch:" + region + ":" + account + ":alarm:" + alarmName;
            StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(account, "local-monitor-secret"));
            this.config = AppConfigClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(credentials).httpClient(transport)
                    .overrideConfiguration(options -> options.apiCallTimeout(Duration.ofSeconds(15))
                            .apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
            this.metrics = CloudWatchClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(credentials).httpClient(transport)
                    .overrideConfiguration(options -> options.apiCallTimeout(Duration.ofSeconds(15))
                            .apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
            this.iam = IamClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(credentials).httpClient(transport)
                    .overrideConfiguration(options -> options.apiCallTimeout(Duration.ofSeconds(15))
                            .apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
            observe("reservedNames", List.of(name, alarmName, roleName));
        }

        private void prepare() {
            String roleArn = iam.createRole(request -> request.roleName(roleName).assumeRolePolicyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                     "Principal":{"Service":"appconfig.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                    """)).role().arn();
            roleCreated = true;
            iam.putRolePolicy(request -> request.roleName(roleName).policyName("read-alarm").policyDocument("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                     "Action":"cloudwatch:DescribeAlarms","Resource":"*"}]}
                    """));
            policyCreated = true;
            metrics.putMetricAlarm(request -> request.alarmName(alarmName).namespace("Floci/MonitorFixture")
                    .metricName("Owned").period(60).evaluationPeriods(1).threshold(1.0)
                    .statistic("Average").comparisonOperator("GreaterThanThreshold")
                    .treatMissingData("ignore").actionsEnabled(true));
            setAlarm("OK");
            application = config.createApplication(request -> request.name(name)).id();
            environment = config.createEnvironment(request -> request.applicationId(application).name(name)
                    .monitors(Monitor.builder().alarmArn(alarmArn).alarmRoleArn(roleArn).build())).id();
            profile = config.createConfigurationProfile(request -> request.applicationId(application)
                    .name(name).locationUri("hosted").type("AWS.Freeform")).id();
            originalVersion = version(ORIGINAL);
            candidateVersion = version(CANDIDATE);
            immediate = strategy(0, 0, "LINEAR", 100f);
            assertEquals("COMPLETE", deploy(originalVersion, immediate).stateAsString());
            assertEquals(ORIGINAL, active().content().asUtf8String());
        }

        private int version(String content) {
            return config.createHostedConfigurationVersion(request -> request.applicationId(application)
                    .configurationProfileId(profile).contentType("application/json")
                    .content(SdkBytes.fromUtf8String(content))).versionNumber();
        }

        private String strategy(int duration, int bake, String growth, float factor) {
            String id = config.createDeploymentStrategy(request -> request.name(name + "-" + strategies.size())
                    .deploymentDurationInMinutes(duration).finalBakeTimeInMinutes(bake)
                    .growthType(growth).growthFactor(factor).replicateTo("NONE")).id();
            strategies.add(id);
            return id;
        }

        private StartDeploymentResponse deploy(int version, String strategy) {
            return config.startDeployment(request -> request.applicationId(application).environmentId(environment)
                    .configurationProfileId(profile).configurationVersion(Integer.toString(version))
                    .deploymentStrategyId(strategy));
        }

        private GetConfigurationResponse active() {
            return config.getConfiguration(request -> request.application(application).environment(environment)
                    .configuration(profile).clientId(name));
        }

        private void setAlarm(String state) {
            metrics.setAlarmState(request -> request.alarmName(alarmName).stateValue(state)
                    .stateReason("owned fixture " + label));
        }

        private JsonNode deployments() throws IOException {
            config.listDeployments(request -> request.applicationId(application).environmentId(environment)
                    .maxResults(50));
            JsonNode result = raw();
            assertFalse(result.hasNonNull("NextToken"), result.toPrettyString());
            return result;
        }

        private JsonNode raw() throws IOException {
            return MAPPER.readTree(transport.calls.getLast().responseBody());
        }

        private void observe(String key, Object value) {
            observations.put(key, value);
        }

        @Override
        public void close() throws Exception {
            List<Throwable> failures = new ArrayList<>();
            if (environment != null) {
                cleanup(failures, () -> config.deleteEnvironment(request -> request.applicationId(application)
                        .environmentId(environment)));
                cleanup(failures, () -> assertTrue(config.listEnvironments(request -> request.applicationId(application))
                        .items().stream().noneMatch(item -> environment.equals(item.id()))));
            }
            if (profile != null) {
                cleanup(failures, () -> config.deleteConfigurationProfile(request -> request.applicationId(application)
                        .configurationProfileId(profile)));
                cleanup(failures, () -> assertTrue(config.listConfigurationProfiles(request ->
                        request.applicationId(application)).items().stream().noneMatch(item -> profile.equals(item.id()))));
            }
            for (String strategy : strategies) {
                cleanup(failures, () -> config.deleteDeploymentStrategy(request -> request.deploymentStrategyId(strategy)));
                cleanup(failures, () -> assertEquals(new Outcome(404, "ResourceNotFoundException"),
                        absentStrategy(strategy)));
            }
            if (application != null) {
                cleanup(failures, () -> config.deleteApplication(request -> request.applicationId(application)));
                cleanup(failures, () -> assertEquals(new Outcome(404, "ResourceNotFoundException"), absentApplication()));
            }
            cleanup(failures, () -> metrics.deleteAlarms(request -> request.alarmNames(alarmName)));
            cleanup(failures, () -> assertTrue(metrics.describeAlarms(request -> request.alarmNames(alarmName))
                    .metricAlarms().isEmpty()));
            if (policyCreated) {
                cleanup(failures, () -> iam.deleteRolePolicy(request -> request.roleName(roleName).policyName("read-alarm")));
            }
            if (roleCreated) {
                cleanup(failures, () -> iam.deleteRole(request -> request.roleName(roleName)));
                cleanup(failures, () -> {
                    try {
                        iam.getRole(request -> request.roleName(roleName));
                        fail("Owned monitor role still exists");
                    } catch (NoSuchEntityException absent) {
                        assertEquals(404, absent.statusCode());
                    }
                });
            }
            try {
                Path directory = Path.of("target", "appconfig-monitor-contract");
                Files.createDirectories(directory);
                Map<String, Object> tape = new LinkedHashMap<>();
                tape.put("label", label);
                tape.put("account", account);
                tape.put("region", region);
                tape.put("application", application);
                tape.put("environment", environment);
                tape.put("profile", profile);
                tape.put("strategies", strategies);
                tape.put("alarmArn", alarmArn);
                tape.put("roleName", roleName);
                tape.put("observations", observations);
                tape.put("calls", transport.calls);
                tape.put("cleanupFailures", failures.stream().map(Throwable::toString).toList());
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(name + ".json").toFile(), tape);
            } finally {
                config.close();
                metrics.close();
                iam.close();
                transport.close();
            }
            if (!failures.isEmpty()) {
                AssertionError failure = new AssertionError("Owned monitor fixture cleanup failed");
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        }

        private Outcome absentStrategy(String id) {
            try {
                return new Outcome(config.getDeploymentStrategy(request -> request.deploymentStrategyId(id))
                        .sdkHttpResponse().statusCode(), null);
            } catch (AppConfigException error) {
                return new Outcome(error.statusCode(), error.awsErrorDetails().errorCode());
            }
        }

        private Outcome absentApplication() {
            try {
                return new Outcome(config.getApplication(request -> request.applicationId(application))
                        .sdkHttpResponse().statusCode(), null);
            } catch (AppConfigException error) {
                return new Outcome(error.statusCode(), error.awsErrorDetails().errorCode());
            }
        }
    }

    private static void cleanup(List<Throwable> failures, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            failures.add(failure);
        }
    }

    private record WireCall(String method, String path, String query, Map<String, List<String>> requestHeaders,
                            String requestBody, String requestBodyBase64, int status,
                            Map<String, List<String>> responseHeaders, String responseBody,
                            String responseBodyBase64) {}

    private static final class TapeHttpClient implements SdkHttpClient {
        private final SdkHttpClient delegate = UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(10)).build();
        private final List<WireCall> calls = new ArrayList<>();

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            ExecutableHttpRequest prepared = delegate.prepareRequest(request);
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() throws IOException {
                    byte[] requestBytes = new byte[0];
                    if (request.contentStreamProvider().isPresent()) {
                        try (InputStream body = request.contentStreamProvider().orElseThrow().newStream()) {
                            requestBytes = body.readAllBytes();
                        }
                    }
                    HttpExecuteResponse received = prepared.call();
                    byte[] bytes = new byte[0];
                    if (received.responseBody().isPresent()) {
                        try (AbortableInputStream body = received.responseBody().orElseThrow()) {
                            bytes = body.readAllBytes();
                        }
                    }
                    calls.add(new WireCall(request.httpRequest().method().name(), request.httpRequest().encodedPath(),
                            request.httpRequest().encodedQueryParameters().orElse(""),
                            request.httpRequest().headers(), new String(requestBytes, StandardCharsets.UTF_8),
                            Base64.getEncoder().encodeToString(requestBytes), received.httpResponse().statusCode(),
                            received.httpResponse().headers(), new String(bytes, StandardCharsets.UTF_8),
                            Base64.getEncoder().encodeToString(bytes)));
                    return HttpExecuteResponse.builder().response(received.httpResponse())
                            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(bytes))).build();
                }

                @Override
                public void abort() {
                    prepared.abort();
                }
            };
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
