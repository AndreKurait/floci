package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkField;
import software.amazon.awssdk.core.SdkPojo;
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
import software.amazon.awssdk.services.appconfig.model.Monitor;
import software.amazon.awssdk.services.appconfig.model.StartDeploymentResponse;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Tag;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.sts.StsClient;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class AppConfigMonitorRoleIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ORIGINAL = "{\"value\":\"original\"}";
    private static final String CANDIDATE = "{\"value\":\"candidate\"}";
    private static final String TRUST = """
            {"Version":"2012-10-17","Statement":{"Effect":"Allow",
            "Principal":{"Service":"appconfig.amazonaws.com"},"Action":"sts:AssumeRole"}}""";
    private static final String READ = """
            {"Version":"2012-10-17","Statement":{"Effect":"Allow",
            "Action":"cloudwatch:DescribeAlarms","Resource":"*"}}""";
    private static final String START = """
            {"Version":"2012-10-17","Statement":{"Effect":"Allow",
            "Action":"appconfig:StartDeployment","Resource":"*"}}""";

    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "cn-north-1,aws-cn", "us-gov-west-1,aws-us-gov"})
    void signedCallerUsesTheActualTaggedMetricRoleAndBackgroundMonitoring(String region, String partition)
            throws Exception {
        try (Cloud cloud = new Cloud("signed-background", region, partition, false, false)) {
            cloud.prepare();
            cloud.rolePolicy("""
                    {"Statement":{"Effect":"Allow","Action":"cloudwatch:DescribeAlarms","Resource":"%s",
                    "Condition":{"StringEquals":{"aws:ResourceTag/team":"platform",
                    "aws:RequestedRegion":"%s","aws:PrincipalArn":"%s"},
                    "Null":{"aws:SourceArn":"true","aws:SourceAccount":"true"}}}}"""
                    .formatted(cloud.alarmArn, region, cloud.roleArn));
            String strategy = cloud.strategy(5);
            Map<String, JsonNode> before = cloud.snapshot();
            try (AppConfigClient forged = cloud.caller("incorrect-secret")) {
                Outcome refused = outcome(() -> cloud.deploy(forged, cloud.candidateVersion, strategy));
                Map<String, JsonNode> after = cloud.snapshot();
                cloud.observe("forgedCallerRefusal", refused);
                assertAll(() -> assertEquals(403, refused.status()), () -> assertEquals(before, after));
            }
            try (AppConfigClient caller = cloud.caller(cloud.accessKey.secretAccessKey())) {
                cloud.assertCaller();
                StartDeploymentResponse started = cloud.deploy(caller, cloud.candidateVersion, strategy);
                assertEquals("BAKING", started.stateAsString());
                assertEquals(CANDIDATE, cloud.active().content().asUtf8String());
                cloud.observe("candidateSeenBeforeAlarm", true);
                cloud.metrics.setAlarmState(request -> request.alarmName(cloud.alarmName)
                        .stateValue("ALARM").stateReason("owned signed monitor fixture"));
                await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(250))
                        .untilAsserted(() -> assertEquals(ORIGINAL, cloud.active().content().asUtf8String()));
                cloud.observe("restorationSeenBeforeFirstDeploymentRead", true);
                assertEquals("ROLLED_BACK", cloud.config.getDeployment(request ->
                        request.applicationId(cloud.application).environmentId(cloud.environment)
                                .deploymentNumber(started.deploymentNumber())).stateAsString());
                cloud.observe("rolledBack", cloud.raw());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-policy", "explicit-deny", "wrong-resource", "wrong-tag",
            "wrong-trust", "source-required", "boundary-deny", "boundary-missing", "missing-role", "wrong-role-path"})
    void monitorRefusalPreservesTheWholeConfigurationAndDeploymentState(String restriction) throws Exception {
        try (Cloud cloud = new Cloud(restriction, "us-east-1", "aws", false, false)) {
            cloud.prepare();
            cloud.restrict(restriction);
            cloud.assertRefused(new Outcome(400, "BadRequestException"));
        }
    }

    @Test
    void callerPermissionIsRequiredBeforeAValidMonitorCanStartDeployment() throws Exception {
        try (Cloud cloud = new Cloud("caller-deny", "us-east-1", "aws", false, false)) {
            cloud.prepare();
            cloud.iam.putUserPolicy(request -> request.userName(cloud.userName).policyName("start")
                    .policyDocument(START.replace("StartDeployment", "GetApplication")));
            cloud.assertRefused(new Outcome(403, "AccessDeniedException"));
        }
    }

    @Test
    void allMonitorsAreAuthorizedEvenAfterAnEarlierAlarmSuppliesARollbackCause() throws Exception {
        try (Cloud cloud = new Cloud("all-monitors", "us-east-1", "aws", false, true)) {
            cloud.prepare();
            cloud.rolePolicy(READ.replace("\"Resource\":\"*\"", "\"Resource\":\"" + cloud.alarmArn + "\""));
            cloud.metrics.setAlarmState(request -> request.alarmName(cloud.alarmName)
                    .stateValue("ALARM").stateReason("first monitor is unhealthy but readable"));
            cloud.assertRefused(new Outcome(400, "BadRequestException"));
        }
    }

    @Test
    void aCompositeMonitorRequiresWildcardPermissionInsteadOfItsAlarmArn() throws Exception {
        try (Cloud cloud = new Cloud("composite", "us-east-1", "aws", true, false)) {
            cloud.prepare();
            cloud.rolePolicy(READ.replace("\"Resource\":\"*\"", "\"Resource\":\"" + cloud.alarmArn + "\""));
            cloud.assertRefused(new Outcome(400, "BadRequestException"));
            cloud.rolePolicy(READ);
            try (AppConfigClient caller = cloud.caller(cloud.accessKey.secretAccessKey())) {
                cloud.assertCaller();
                assertEquals("COMPLETE", cloud.deploy(caller, cloud.candidateVersion, cloud.immediate).stateAsString());
                assertEquals(CANDIDATE, cloud.active().content().asUtf8String());
            }
        }
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
        private final String region;
        private final String partition;
        private final boolean composite;
        private final boolean secondMonitor;
        private final String name = "monitor-iam-" + UUID.randomUUID().toString().substring(0, 12);
        private final String alarmName = name + "-alarm";
        private final String secondAlarm = name + "-second";
        private final String roleName = name + "-role";
        private final String userName = name + "-user";
        private final TapeHttpClient transport = new TapeHttpClient();
        private final AppConfigClient config;
        private final CloudWatchClient metrics;
        private final IamClient iam;
        private final List<String> strategies = new ArrayList<>();
        private final Map<String, Object> observations = new LinkedHashMap<>();
        private String account;
        private String alarmArn;
        private String roleArn;
        private String boundaryArn;
        private String application;
        private String environment;
        private String profile;
        private String immediate;
        private AccessKey accessKey;
        private int originalVersion;
        private int candidateVersion;

        private Cloud(String label, String region, String partition, boolean composite, boolean secondMonitor) {
            assertTrue(List.of("localhost", "127.0.0.1", "::1").contains(endpoint.getHost()), endpoint.toString());
            this.label = label;
            this.region = region;
            this.partition = partition;
            this.composite = composite;
            this.secondMonitor = secondMonitor;
            config = client("test", "test");
            metrics = CloudWatchClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(credentials("test", "test")).httpClient(transport)
                    .overrideConfiguration(options -> options.apiCallTimeout(Duration.ofSeconds(15))).build();
            iam = IamClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(credentials("test", "test")).httpClient(transport)
                    .overrideConfiguration(options -> options.apiCallTimeout(Duration.ofSeconds(15))).build();
            observe("reservedNames", List.of(name, alarmName, secondAlarm, roleName, userName));
        }

        private AppConfigClient client(String key, String secret) {
            return AppConfigClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(credentials(key, secret)).httpClient(transport)
                    .overrideConfiguration(options -> options.apiCallTimeout(Duration.ofSeconds(15))
                            .apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
        }

        private AppConfigClient caller(String secret) {
            return client(accessKey.accessKeyId(), secret);
        }

        private void assertCaller() {
            try (StsClient identity = StsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(credentials(accessKey.accessKeyId(), accessKey.secretAccessKey()))
                    .httpClient(transport).build()) {
                assertEquals("arn:" + partition + ":iam::" + account + ":user/" + userName,
                        identity.getCallerIdentity(request -> {}).arn());
            }
        }

        private void prepare() {
            try (StsClient identity = StsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(credentials("test", "test")).httpClient(transport).build()) {
                account = identity.getCallerIdentity(request -> {}).account();
            }
            alarmArn = "arn:" + partition + ":cloudwatch:" + region + ":" + account + ":alarm:" + alarmName;
            roleArn = iam.createRole(request -> request.roleName(roleName).path("/service-role/")
                    .assumeRolePolicyDocument(TRUST)).role().arn();
            rolePolicy(READ);
            if (composite) {
                metrics.putCompositeAlarm(request -> request.alarmName(alarmName).alarmRule("FALSE")
                        .actionsEnabled(true));
            } else {
                metric(alarmName);
            }
            if (secondMonitor) {
                metric(secondAlarm);
            }
            List<Monitor> monitors = new ArrayList<>();
            monitors.add(Monitor.builder().alarmArn(alarmArn).alarmRoleArn(roleArn).build());
            if (secondMonitor) {
                monitors.add(Monitor.builder().alarmArn("arn:" + partition + ":cloudwatch:" + region + ":"
                        + account + ":alarm:" + secondAlarm).alarmRoleArn(roleArn).build());
            }
            application = config.createApplication(request -> request.name(name)).id();
            environment = config.createEnvironment(request -> request.applicationId(application).name(name)
                    .monitors(monitors)).id();
            profile = config.createConfigurationProfile(request -> request.applicationId(application)
                    .name(name).locationUri("hosted").type("AWS.Freeform")).id();
            originalVersion = version(ORIGINAL);
            candidateVersion = version(CANDIDATE);
            immediate = strategy(0);
            assertEquals("COMPLETE", deploy(config, originalVersion, immediate).stateAsString());
            assertEquals(ORIGINAL, active().content().asUtf8String());
            iam.createUser(request -> request.userName(userName));
            accessKey = iam.createAccessKey(request -> request.userName(userName)).accessKey();
            iam.putUserPolicy(request -> request.userName(userName).policyName("start").policyDocument(START));
        }

        private void metric(String selected) {
            metrics.putMetricAlarm(request -> request.alarmName(selected).namespace("Floci/MonitorRoleFixture")
                    .metricName("Owned").period(60).evaluationPeriods(1).threshold(1.0).statistic("Average")
                    .comparisonOperator("GreaterThanThreshold").treatMissingData("ignore").actionsEnabled(true)
                    .tags(Tag.builder().key("team").value("platform").build()));
            metrics.setAlarmState(request -> request.alarmName(selected).stateValue("OK").stateReason("owned fixture"));
        }

        private void rolePolicy(String policy) {
            iam.putRolePolicy(request -> request.roleName(roleName).policyName("read").policyDocument(policy));
        }

        private void restrict(String restriction) {
            switch (restriction) {
                case "missing-policy" -> iam.deleteRolePolicy(request -> request.roleName(roleName).policyName("read"));
                case "explicit-deny" -> rolePolicy(READ.replace("Allow", "Deny"));
                case "wrong-resource" -> rolePolicy(READ.replace("\"Resource\":\"*\"",
                        "\"Resource\":\"" + alarmArn + "-other\""));
                case "wrong-tag" -> rolePolicy("""
                        {"Statement":{"Effect":"Allow","Action":"cloudwatch:DescribeAlarms","Resource":"*",
                        "Condition":{"StringEquals":{"aws:ResourceTag/team":"other"}}}}""");
                case "wrong-trust" -> iam.updateAssumeRolePolicy(request -> request.roleName(roleName)
                        .policyDocument(TRUST.replace("appconfig.", "lambda.")));
                case "source-required" -> iam.updateAssumeRolePolicy(request -> request.roleName(roleName)
                        .policyDocument("""
                                {"Statement":{"Effect":"Allow","Principal":{"Service":"appconfig.amazonaws.com"},
                                "Action":"sts:AssumeRole","Condition":{"Null":{"aws:SourceArn":"false"}}}}"""));
                case "boundary-deny", "boundary-missing" -> {
                    boundaryArn = iam.createPolicy(request -> request.policyName(name + "-boundary")
                            .policyDocument("boundary-deny".equals(restriction)
                                    ? READ.replace("Allow", "Deny")
                                    : READ.replace("cloudwatch:DescribeAlarms", "s3:GetObject"))).policy().arn();
                    iam.putRolePermissionsBoundary(request -> request.roleName(roleName).permissionsBoundary(boundaryArn));
                }
                case "missing-role", "wrong-role-path" -> {
                    iam.deleteRolePolicy(request -> request.roleName(roleName).policyName("read"));
                    iam.deleteRole(request -> request.roleName(roleName));
                    if ("wrong-role-path".equals(restriction)) {
                        iam.createRole(request -> request.roleName(roleName).path("/other/").assumeRolePolicyDocument(TRUST));
                        rolePolicy(READ);
                    }
                }
                default -> throw new IllegalArgumentException(restriction);
            }
        }

        private void assertRefused(Outcome expected) throws Exception {
            String selected = strategy(5);
            Map<String, JsonNode> before = snapshot();
            try (AppConfigClient caller = caller(accessKey.secretAccessKey())) {
                assertCaller();
                Outcome result = outcome(() -> deploy(caller, candidateVersion, selected));
                Map<String, JsonNode> after = snapshot();
                observe("refusedStart", result);
                observe("snapshotBefore", before);
                observe("snapshotAfter", after);
                assertAll(() -> assertEquals(expected, result), () -> assertEquals(before, after),
                        () -> assertEquals(ORIGINAL, active().content().asUtf8String()));
            }
        }

        private int version(String content) {
            return config.createHostedConfigurationVersion(request -> request.applicationId(application)
                    .configurationProfileId(profile).contentType("application/json")
                    .content(SdkBytes.fromUtf8String(content))).versionNumber();
        }

        private String strategy(int bake) {
            String selected = config.createDeploymentStrategy(request -> request.name(name + "-" + strategies.size())
                    .deploymentDurationInMinutes(0).finalBakeTimeInMinutes(bake).growthType("LINEAR")
                    .growthFactor(100f).replicateTo("NONE")).id();
            strategies.add(selected);
            return selected;
        }

        private StartDeploymentResponse deploy(AppConfigClient caller, int version, String strategy) {
            return caller.startDeployment(request -> request.applicationId(application).environmentId(environment)
                    .configurationProfileId(profile).configurationVersion(Integer.toString(version))
                    .deploymentStrategyId(strategy));
        }

        private GetConfigurationResponse active() {
            return config.getConfiguration(request -> request.application(application).environment(environment)
                    .configuration(profile).clientId(name));
        }

        private Map<String, JsonNode> snapshot() throws IOException {
            Map<String, JsonNode> result = new LinkedHashMap<>();
            config.getApplication(request -> request.applicationId(application));
            result.put("application", raw());
            config.getEnvironment(request -> request.applicationId(application).environmentId(environment));
            result.put("environment", raw());
            config.getConfigurationProfile(request -> request.applicationId(application).configurationProfileId(profile));
            result.put("profile", raw());
            config.listHostedConfigurationVersions(request ->
                    request.applicationId(application).configurationProfileId(profile).maxResults(50));
            result.put("versions", raw());
            for (int version : List.of(originalVersion, candidateVersion)) {
                result.put("hostedVersion" + version, MAPPER.valueToTree(fields(
                        config.getHostedConfigurationVersion(request -> request.applicationId(application)
                                .configurationProfileId(profile).versionNumber(version)))));
            }
            config.listDeployments(request -> request.applicationId(application).environmentId(environment).maxResults(50));
            result.put("deployments", raw());
            assertFalse(result.get("versions").hasNonNull("NextToken"));
            assertFalse(result.get("deployments").hasNonNull("NextToken"));
            result.put("active", MAPPER.valueToTree(Map.of("content", active().content().asUtf8String(),
                    "version", active().configurationVersion())));
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
                        .environmentId(environment).deletionProtectionCheck("BYPASS")));
                cleanup(failures, () -> assertTrue(config.listEnvironments(request -> request.applicationId(application))
                        .items().stream().noneMatch(item -> environment.equals(item.id()))));
            }
            if (profile != null) {
                cleanup(failures, () -> config.deleteConfigurationProfile(request -> request.applicationId(application)
                        .configurationProfileId(profile).deletionProtectionCheck("BYPASS")));
                cleanup(failures, () -> assertTrue(config.listConfigurationProfiles(request -> request.applicationId(application))
                        .items().stream().noneMatch(item -> profile.equals(item.id()))));
            }
            for (String strategy : strategies) {
                cleanup(failures, () -> config.deleteDeploymentStrategy(request -> request.deploymentStrategyId(strategy)));
                cleanup(failures, () -> assertEquals(new Outcome(404, "ResourceNotFoundException"),
                        outcome(() -> config.getDeploymentStrategy(request -> request.deploymentStrategyId(strategy)))));
            }
            if (application != null) {
                cleanup(failures, () -> config.deleteApplication(request -> request.applicationId(application)));
                cleanup(failures, () -> assertEquals(new Outcome(404, "ResourceNotFoundException"),
                        outcome(() -> config.getApplication(request -> request.applicationId(application)))));
            }
            cleanup(failures, () -> metrics.deleteAlarms(request -> request.alarmNames(alarmName, secondAlarm)));
            cleanup(failures, () -> {
                assertTrue(metrics.describeAlarms(request -> request.alarmNames(alarmName, secondAlarm)
                        .alarmTypesWithStrings("MetricAlarm", "CompositeAlarm")).metricAlarms().isEmpty());
                assertTrue(metrics.describeAlarms(request -> request.alarmNames(alarmName, secondAlarm)
                        .alarmTypesWithStrings("MetricAlarm", "CompositeAlarm")).compositeAlarms().isEmpty());
            });
            if (boundaryArn != null) {
                cleanup(failures, () -> missingIsRemoved(() -> iam.deleteRolePermissionsBoundary(request ->
                        request.roleName(roleName))));
                cleanup(failures, () -> iam.deletePolicy(request -> request.policyArn(boundaryArn)));
            }
            cleanup(failures, () -> missingIsRemoved(() -> iam.deleteRolePolicy(request ->
                    request.roleName(roleName).policyName("read"))));
            cleanup(failures, () -> missingIsRemoved(() -> iam.deleteRole(request -> request.roleName(roleName))));
            cleanup(failures, () -> {
                try {
                    iam.getRole(request -> request.roleName(roleName));
                    fail("Owned monitor role still exists");
                } catch (NoSuchEntityException absent) {
                    assertEquals(404, absent.statusCode());
                }
            });
            if (accessKey != null) {
                cleanup(failures, () -> iam.deleteAccessKey(request ->
                        request.userName(userName).accessKeyId(accessKey.accessKeyId())));
            }
            cleanup(failures, () -> missingIsRemoved(() -> iam.deleteUserPolicy(request ->
                    request.userName(userName).policyName("start"))));
            cleanup(failures, () -> missingIsRemoved(() -> iam.deleteUser(request -> request.userName(userName))));
            cleanup(failures, () -> {
                try {
                    iam.getUser(request -> request.userName(userName));
                    fail("Owned caller user still exists");
                } catch (NoSuchEntityException absent) {
                    assertEquals(404, absent.statusCode());
                }
            });
            try {
                Path directory = Path.of("target", "appconfig-monitor-iam-contract");
                Files.createDirectories(directory);
                Map<String, Object> tape = new LinkedHashMap<>();
                tape.put("label", label);
                tape.put("account", account);
                tape.put("region", region);
                tape.put("application", application);
                tape.put("environment", environment);
                tape.put("profile", profile);
                tape.put("alarmArn", alarmArn);
                tape.put("roleArn", roleArn);
                tape.put("userName", userName);
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
                AssertionError failure = new AssertionError("Owned monitor role fixture cleanup failed");
                failures.forEach(failure::addSuppressed);
                throw failure;
            }
        }
    }

    private static StaticCredentialsProvider credentials(String key, String secret) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret));
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
            return values.stream().map(AppConfigMonitorRoleIntegrationTest::fields).toList();
        }
        return value;
    }

    private static void missingIsRemoved(Runnable action) {
        try {
            action.run();
        } catch (NoSuchEntityException absent) {
            assertEquals(404, absent.statusCode());
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
