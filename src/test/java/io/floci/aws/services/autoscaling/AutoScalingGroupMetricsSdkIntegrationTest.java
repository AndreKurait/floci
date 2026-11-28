package io.floci.aws.services.autoscaling;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingException;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.EnabledMetric;
import software.amazon.awssdk.services.autoscaling.model.LaunchConfiguration;
import software.amazon.awssdk.services.autoscaling.model.Tag;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.ListMetricsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Metric;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@Timeout(180)
class AutoScalingGroupMetricsSdkIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> BASIC = List.of("GroupMinSize", "GroupMaxSize", "GroupDesiredCapacity",
            "GroupInServiceInstances", "GroupPendingInstances", "GroupStandbyInstances",
            "GroupTerminatingInstances", "GroupTotalInstances");
    private static final String NAMESPACE = "AWS/AutoScaling";
    private final List<Scope> scopes = new ArrayList<>();
    private final List<Owner> owners = new ArrayList<>();
    private final List<Map<String, Object>> wire = new ArrayList<>();
    private final List<Map<String, Object>> events = new ArrayList<>();
    private Scope primary;
    private String marker;
    private String testName;
    private Path archive;
    private Instant started;

    @BeforeEach
    void openClients(TestInfo info) throws IOException {
        marker = "asg-metrics-" + UUID.randomUUID();
        testName = info.getTestMethod().orElseThrow().getName();
        started = Instant.now();
        archive = Path.of("target", "asg-metrics-sdk-receipts", marker + ".json");
        Files.createDirectories(archive.getParent());
        primary = scope(randomAccount(), "us-east-1");
        persist();
    }

    @AfterEach
    void removeOnlyOwnedResources() {
        List<Throwable> failures = new ArrayList<>();
        for (Owner owner : owners) {
            try {
                List<AutoScalingGroup> groups = groups(owner);
                if (!groups.isEmpty()) {
                    assertEquals(1, groups.size());
                    assertOwned(owner, groups.getFirst());
                    owner.scope.asg.deleteAutoScalingGroup(request -> request
                            .autoScalingGroupName(owner.name).forceDelete(true));
                    assertTrue(groups(owner).isEmpty());
                }
                owner.groupAbsent = true;
                event("group-absence", owner.name);
            } catch (Throwable failure) {
                failures.add(failure);
                event("group-cleanup-failure", owner.name + ": " + failure);
            }
            try {
                List<LaunchConfiguration> configurations = configurations(owner);
                if (!configurations.isEmpty()) {
                    assertEquals(1, configurations.size());
                    LaunchConfiguration configuration = configurations.getFirst();
                    assertEquals(owner.name, configuration.launchConfigurationName());
                    assertEquals("ami-12345678", configuration.imageId());
                    assertEquals("t3.micro", configuration.instanceType());
                    assertTrue(owner.name.startsWith(marker));
                    owner.scope.asg.deleteLaunchConfiguration(request -> request
                            .launchConfigurationName(owner.name));
                    assertTrue(configurations(owner).isEmpty());
                }
                owner.configurationAbsent = true;
                event("launch-configuration-absence", owner.name);
            } catch (Throwable failure) {
                failures.add(failure);
                event("launch-configuration-cleanup-failure", owner.name + ": " + failure);
            }
        }
        scopes.forEach(scope -> {
            scope.asg.close();
            scope.cloudWatch.close();
        });
        persist();
        assertAll("Every pending owned create retains its independent cleanup outcome",
                failures.stream().map(failure -> () -> {
                    throw failure;
                }));
    }

    @Test
    void selectedMetricsProduceUnitlessZeroCapacityDatapoints() throws InterruptedException {
        Owner owner = create(primary, "selected");
        enable(owner, List.of("GroupMinSize", "GroupDesiredCapacity"));
        assertEquals(Set.of("GroupMinSize", "GroupDesiredCapacity"), enabled(owner));
        awaitMetrics(owner, Set.of("GroupMinSize", "GroupDesiredCapacity"));
        assertTrue(points(owner, "GroupMaxSize").isEmpty());
        assertEquals(Set.of("GroupMinSize", "GroupDesiredCapacity"), metricNames(owner));
    }

    @Test
    void omittedMetricListPublishesAllEightBasicMetrics() throws InterruptedException {
        Owner owner = create(primary, "all");
        enable(owner, List.of());
        assertTrue(enabled(owner).containsAll(BASIC));
        awaitMetrics(owner, Set.copyOf(BASIC));
        assertTrue(metricNames(owner).containsAll(BASIC));
    }

    @Test
    void collectionStartsDisabledWithoutAnInitialDatapoint() {
        Owner owner = create(primary, "disabled");
        assertTrue(enabled(owner).isEmpty());
        assertTrue(metricNames(owner).isEmpty());
        for (String metric : BASIC) {
            assertTrue(points(owner, metric).isEmpty());
        }
    }

    @Test
    void enablingAnotherSelectionKeepsPreviouslyEnabledMetrics() {
        Owner owner = create(primary, "additive");
        enable(owner, List.of("GroupMinSize"));
        enable(owner, List.of("GroupMaxSize", "GroupDesiredCapacity"));
        assertEquals(Set.of("GroupMinSize", "GroupMaxSize", "GroupDesiredCapacity"), enabled(owner));
    }

    @Test
    void disablingASelectionKeepsOtherEnabledMetrics() {
        Owner owner = create(primary, "selective-disable");
        enable(owner, List.of("GroupMinSize", "GroupMaxSize", "GroupDesiredCapacity"));
        primary.asg.disableMetricsCollection(request -> request.autoScalingGroupName(owner.name)
                .metrics("GroupMaxSize"));
        assertEquals(Set.of("GroupMinSize", "GroupDesiredCapacity"), enabled(owner));
    }

    @Test
    void omittedDisableSelectionRemovesAllEnabledMetrics() {
        Owner owner = create(primary, "all-disable");
        enable(owner, List.of());
        primary.asg.disableMetricsCollection(request -> request.autoScalingGroupName(owner.name));
        assertTrue(enabled(owner).isEmpty());
    }

    @Test
    void rejectedMetricOrGranularityPreservesTheCompleteGroup() {
        Owner owner = create(primary, "invalid");
        enable(owner, List.of("GroupMinSize"));
        AutoScalingGroup before = read(owner);
        AutoScalingException metricFailure = assertThrows(AutoScalingException.class,
                () -> enable(owner, List.of("GroupMaxSize", "UnrecognizedGroupMetric")));
        assertEquals(400, metricFailure.statusCode());
        assertEquals("ValidationError", metricFailure.awsErrorDetails().errorCode());
        assertTrue(before.equalsBySdkFields(read(owner)));
        AutoScalingException granularityFailure = assertThrows(AutoScalingException.class,
                () -> primary.asg.enableMetricsCollection(request -> request.autoScalingGroupName(owner.name)
                        .granularity("5Minute").metrics("GroupMaxSize")));
        assertEquals(400, granularityFailure.statusCode());
        assertEquals("ValidationError", granularityFailure.awsErrorDetails().errorCode());
        assertTrue(before.equalsBySdkFields(read(owner)));
    }

    @Test
    void collectionSelectionIsIsolatedByAccountAndRegion() {
        Owner owner = create(primary, "isolation");
        Owner anotherAccount = create(scope(randomAccount(), primary.region), "isolation");
        Owner anotherRegion = create(scope(primary.account, "us-west-2"), "isolation");
        AutoScalingGroup accountBefore = read(anotherAccount);
        AutoScalingGroup regionBefore = read(anotherRegion);
        enable(owner, List.of("GroupDesiredCapacity"));
        assertEquals(Set.of("GroupDesiredCapacity"), enabled(owner));
        assertTrue(accountBefore.equalsBySdkFields(read(anotherAccount)));
        assertTrue(regionBefore.equalsBySdkFields(read(anotherRegion)));
        assertTrue(metricNames(anotherAccount).isEmpty());
        assertTrue(metricNames(anotherRegion).isEmpty());
    }

    private Owner create(Scope scope, String suffix) {
        Owner owner = new Owner(scope, marker + "-" + suffix);
        assertTrue(groups(owner).isEmpty());
        assertTrue(configurations(owner).isEmpty());
        owners.add(owner);
        owner.configurationPending = true;
        event("launch-configuration-create-intent", owner.name);
        scope.asg.createLaunchConfiguration(request -> request.launchConfigurationName(owner.name)
                .imageId("ami-12345678").instanceType("t3.micro"));
        assertEquals(1, configurations(owner).size());
        owner.groupPending = true;
        event("group-create-intent", owner.name);
        scope.asg.createAutoScalingGroup(request -> request.autoScalingGroupName(owner.name)
                .launchConfigurationName(owner.name).minSize(0).maxSize(0).desiredCapacity(0)
                .availabilityZones(scope.region + "a")
                .tags(Tag.builder().key("AuditOwner").value(marker).propagateAtLaunch(false).build()));
        owner.arn = read(owner).autoScalingGroupARN();
        assertNotNull(owner.arn);
        event("group-created", owner.arn);
        return owner;
    }

    private List<AutoScalingGroup> groups(Owner owner) {
        return owner.scope.asg.describeAutoScalingGroups(request -> request.autoScalingGroupNames(owner.name))
                .autoScalingGroups();
    }

    private List<LaunchConfiguration> configurations(Owner owner) {
        return owner.scope.asg.describeLaunchConfigurations(request -> request
                .launchConfigurationNames(owner.name)).launchConfigurations();
    }

    private AutoScalingGroup read(Owner owner) {
        List<AutoScalingGroup> groups = groups(owner);
        assertEquals(1, groups.size());
        assertOwned(owner, groups.getFirst());
        return groups.getFirst();
    }

    private void assertOwned(Owner owner, AutoScalingGroup group) {
        assertEquals(owner.name, group.autoScalingGroupName());
        assertEquals(owner.name, group.launchConfigurationName());
        assertEquals(0, group.minSize());
        assertEquals(0, group.maxSize());
        assertEquals(0, group.desiredCapacity());
        assertTrue(group.instances().isEmpty());
        assertEquals(List.of(owner.scope.region + "a"), group.availabilityZones());
        assertTrue(group.tags().stream().anyMatch(tag -> "AuditOwner".equals(tag.key())
                && marker.equals(tag.value()) && Boolean.FALSE.equals(tag.propagateAtLaunch())));
        if (owner.arn != null) {
            assertEquals(owner.arn, group.autoScalingGroupARN());
        }
    }

    private Set<String> enabled(Owner owner) {
        return read(owner).enabledMetrics().stream().map(EnabledMetric::metric).collect(Collectors.toSet());
    }

    private void enable(Owner owner, List<String> metrics) {
        owner.scope.asg.enableMetricsCollection(request -> request.autoScalingGroupName(owner.name)
                .granularity("1Minute").metrics(metrics));
    }

    private List<Datapoint> points(Owner owner, String metric) {
        return owner.scope.cloudWatch.getMetricStatistics(request -> request.namespace(NAMESPACE)
                .metricName(metric).dimensions(dimension(owner)).startTime(started.minusSeconds(60))
                .endTime(Instant.now().plusSeconds(60)).period(60)
                .statistics(Statistic.SAMPLE_COUNT, Statistic.MINIMUM, Statistic.MAXIMUM,
                        Statistic.AVERAGE, Statistic.SUM)).datapoints();
    }

    private Set<String> metricNames(Owner owner) {
        Set<String> names = new HashSet<>();
        Set<String> tokens = new HashSet<>();
        String token = null;
        for (int page = 0; page < 16; page++) {
            String supplied = token;
            ListMetricsResponse response = owner.scope.cloudWatch.listMetrics(request -> request.namespace(NAMESPACE)
                    .dimensions(filter -> filter.name("AutoScalingGroupName").value(owner.name))
                    .nextToken(supplied));
            for (Metric metric : response.metrics()) {
                assertEquals(NAMESPACE, metric.namespace());
                assertEquals(List.of(dimension(owner)), metric.dimensions());
                assertTrue(names.add(metric.metricName()));
            }
            token = response.nextToken();
            if (token == null) {
                return names;
            }
            assertFalse(token.isBlank());
            assertTrue(tokens.add(token));
        }
        throw new AssertionError("ListMetrics exceeded its complete 16-page bound");
    }

    private Dimension dimension(Owner owner) {
        return Dimension.builder().name("AutoScalingGroupName").value(owner.name).build();
    }

    private void awaitMetrics(Owner owner, Set<String> expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(75).toNanos();
        Set<String> observed = new HashSet<>();
        while (System.nanoTime() < deadline) {
            for (String metric : expected) {
                List<Datapoint> datapoints = points(owner, metric);
                for (Datapoint datapoint : datapoints) {
                    assertEquals(StandardUnit.NONE, datapoint.unit());
                    assertEquals(1.0, datapoint.sampleCount());
                    assertEquals(0.0, datapoint.minimum());
                    assertEquals(0.0, datapoint.maximum());
                    assertEquals(0.0, datapoint.average());
                    assertEquals(0.0, datapoint.sum());
                    assertNotNull(datapoint.timestamp());
                    observed.add(metric);
                }
            }
            if (observed.containsAll(expected)) {
                event("observed-metrics", observed.toString());
                return;
            }
            Thread.sleep(1000);
        }
        event("observation-deadline", observed.toString());
        assertEquals(expected, observed, "Enabled group metrics must reach CloudWatch within the local 75s bound");
    }

    private Scope scope(String account, String region) {
        URI endpoint = URI.create("http://127.0.0.1:" + RestAssured.port);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(account, "test"));
        ClientOverrideConfiguration overrides = ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(12))
                .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(new WireReceipt(account, region)).build();
        AutoScalingClient asg = AutoScalingClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(credentials).overrideConfiguration(overrides)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(10))).build();
        CloudWatchClient cloudWatch = CloudWatchClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(credentials).overrideConfiguration(overrides)
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(10))).build();
        Scope scope = new Scope(asg, cloudWatch, account, region);
        scopes.add(scope);
        return scope;
    }

    private String randomAccount() {
        return Long.toString(100000000000L + Long.parseUnsignedLong(
                UUID.randomUUID().toString().replace("-", "").substring(0, 10), 16) % 800000000000L);
    }

    private void event(String kind, String value) {
        events.add(Map.of("kind", kind, "value", value, "time", Instant.now().toString()));
        persist();
    }

    private void persist() {
        List<Map<String, Object>> reservations = new ArrayList<>();
        for (Owner owner : owners) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", owner.name);
            row.put("account", owner.scope.account);
            row.put("region", owner.scope.region);
            row.put("configurationPending", owner.configurationPending);
            row.put("groupPending", owner.groupPending);
            row.put("returnedArn", owner.arn);
            row.put("groupAbsent", owner.groupAbsent);
            row.put("configurationAbsent", owner.configurationAbsent);
            row.put("outcomeUncertain", owner.groupPending && owner.arn == null && !owner.groupAbsent);
            reservations.add(row);
        }
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(archive.toFile(), Map.of(
                    "test", testName, "marker", marker, "reservations", reservations, "events", events, "wire", wire));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private record Scope(AutoScalingClient asg, CloudWatchClient cloudWatch, String account, String region) {}

    private static final class Owner {
        private final Scope scope;
        private final String name;
        private boolean configurationPending;
        private boolean groupPending;
        private String arn;
        private boolean groupAbsent;
        private boolean configurationAbsent;

        private Owner(Scope scope, String name) {
            this.scope = scope;
            this.name = name;
        }
    }

    private final class WireReceipt implements ExecutionInterceptor {
        private final String account;
        private final String region;

        private WireReceipt(String account, String region) {
            this.account = account;
            this.region = region;
        }

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertTrue(wire.size() < 4096, "Fixture exceeded its wire-record bound");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "request");
            row.put("account", account);
            row.put("region", region);
            row.put("operation", context.request().getClass().getSimpleName());
            row.put("method", context.httpRequest().method().name());
            row.put("uri", context.httpRequest().getUri().toString());
            row.put("headers", context.httpRequest().headers());
            context.requestBody().ifPresent(body -> {
                try (InputStream stream = body.contentStreamProvider().newStream()) {
                    byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
                    row.put("bodyBase64", Base64.getEncoder().encodeToString(bytes));
                    assertTrue(bytes.length <= 1024 * 1024);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });
            wire.add(row);
            persist();
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
                                                               ExecutionAttributes attributes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "response-before-parser");
            row.put("account", account);
            row.put("region", region);
            row.put("operation", context.request().getClass().getSimpleName());
            row.put("status", context.httpResponse().statusCode());
            row.put("headers", context.httpResponse().headers());
            try (InputStream stream = context.responseBody().orElse(InputStream.nullInputStream())) {
                byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
                row.put("bodyBase64", Base64.getEncoder().encodeToString(bytes));
                row.put("withinBound", bytes.length <= 1024 * 1024);
                wire.add(row);
                persist();
                assertTrue(bytes.length <= 1024 * 1024);
                return Optional.of(new ByteArrayInputStream(bytes));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }
}
