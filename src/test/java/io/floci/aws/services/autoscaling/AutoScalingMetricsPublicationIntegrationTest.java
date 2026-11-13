package io.floci.aws.services.autoscaling;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.autoscaling.model.AutoScalingGroup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
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
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AutoScalingMetricsPublicationIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NAMESPACE = "AWS/AutoScaling";
    @Inject AutoScalingService service;
    @Inject AutoScalingMetricsPublisher publisher;
    @Inject RegionResolver resolver;
    private final List<Owner> owners = new ArrayList<>();
    private final List<Map<String, Object>> wire = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private String name;
    private String test;
    private Path archive;
    private Instant minute;

    @BeforeEach
    void prepare(TestInfo info) throws IOException {
        name = "asg-publisher-" + UUID.randomUUID();
        test = info.getTestMethod().orElseThrow().getName();
        minute = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        archive = Path.of("target", "asg-metrics-sdk-receipts", name + ".json");
        Files.createDirectories(archive.getParent());
        persist();
    }

    @AfterEach
    void cleanOwnedGroups() {
        List<Throwable> failures = new ArrayList<>();
        for (Owner owner : owners) {
            try {
                RequestScopes.runAs(owner.account, owner.region, () -> {
                    List<AutoScalingGroup> groups = service.describeAutoScalingGroups(owner.region, List.of(name));
                    if (!groups.isEmpty()) {
                        assertEquals(1, groups.size());
                        assertEquals(owner.incarnation, groups.getFirst().getMetricsIncarnation());
                        assertTrue(groups.getFirst().getInstances().isEmpty());
                        service.deleteAutoScalingGroup(owner.region, name, false);
                    }
                    assertTrue(service.describeAutoScalingGroups(owner.region, List.of(name)).isEmpty());
                    owner.absent = true;
                    events.add("verified-owned-group-absent:" + owner.account + ":" + owner.region);
                });
            } catch (Throwable failure) {
                failures.add(failure);
                events.add("cleanup-failure:" + failure);
            } finally {
                owner.asg.close();
                owner.cloudWatch.close();
            }
        }
        persist();
        assertAll(failures.stream().map(failure -> () -> {
            throw failure;
        }));
    }

    @Test
    void storedAccountAndRegionDetermineTheHttpMetricViewAndTheCallerScopeIsRestored() {
        Owner first = group("741234567890", "us-west-2", 3);
        Owner accountSibling = group("751234567890", "us-west-2", 5);
        Owner regionSibling = group("741234567890", "us-east-1", 7);
        for (Owner owner : owners) {
            owner.asg.enableMetricsCollection(request -> request.autoScalingGroupName(name)
                    .granularity("1Minute").metrics("GroupMaxSize"));
        }
        RequestScopes.runAs("761234567890", "eu-west-1", () -> {
            publisher.tick(minute);
            assertEquals("761234567890", resolver.getAccountId());
            assertEquals("eu-west-1", resolver.getRegion());
        });
        assertPoint(first, 3);
        assertPoint(accountSibling, 5);
        assertPoint(regionSibling, 7);
        for (Owner owner : owners) {
            assertEquals(List.of(Dimension.builder().name("AutoScalingGroupName").value(name).build()),
                    owner.cloudWatch.listMetrics(request -> request.namespace(NAMESPACE).metricName("GroupMaxSize")
                            .dimensions(filter -> filter.name("AutoScalingGroupName").value(name)))
                            .metrics().getFirst().dimensions());
        }
    }

    @Test
    void actualSdkDisableAndDeleteStopLaterSamplesAndKeepHistoricalSamples() {
        Owner owner = group("771234567890", "us-west-2", 4);
        owner.asg.enableMetricsCollection(request -> request.autoScalingGroupName(name)
                .granularity("1Minute").metrics("GroupMaxSize"));
        publisher.tick(minute);
        assertPoint(owner, 4);
        owner.asg.disableMetricsCollection(request -> request.autoScalingGroupName(name));
        publisher.tick(minute.plusSeconds(60));
        assertPoint(owner, 4);
        assertTrue(owner.asg.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().getFirst().enabledMetrics().isEmpty());
        owner.asg.deleteAutoScalingGroup(request -> request.autoScalingGroupName(name));
        publisher.tick(minute.plusSeconds(120));
        assertPoint(owner, 4);
        assertTrue(owner.asg.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().isEmpty());
    }

    @Test
    void internalIncarnationIsAbsentFromTheActualDescribeResponse() {
        Owner owner = group("781234567890", "us-west-2", 0);
        assertNotNull(owner.incarnation);
        assertEquals(name, owner.asg.describeAutoScalingGroups(request -> request.autoScalingGroupNames(name))
                .autoScalingGroups().getFirst().autoScalingGroupName());
        List<Map<String, Object>> responses = wire.stream()
                .filter(row -> "response-before-parser".equals(row.get("kind")))
                .filter(row -> "DescribeAutoScalingGroupsRequest".equals(row.get("operation"))).toList();
        assertEquals(1, responses.size());
        String body = new String(Base64.getDecoder().decode((String) responses.getFirst().get("bodyBase64")),
                StandardCharsets.UTF_8);
        assertFalse(body.contains(owner.incarnation));
        assertFalse(body.toLowerCase().contains("metricsincarnation"));
    }

    private Owner group(String account, String region, int maximum) {
        URI endpoint = URI.create("http://127.0.0.1:" + RestAssured.port);
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(account, "test"));
        ClientOverrideConfiguration overrides = ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(12))
                .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(new WireReceipt(account, region)).build();
        Owner owner = new Owner(account, region,
                AutoScalingClient.builder().region(Region.of(region)).endpointOverride(endpoint)
                        .credentialsProvider(credentials).overrideConfiguration(overrides)
                        .httpClientBuilder(UrlConnectionHttpClient.builder()).build(),
                CloudWatchClient.builder().region(Region.of(region)).endpointOverride(endpoint)
                        .credentialsProvider(credentials).overrideConfiguration(overrides)
                        .httpClientBuilder(UrlConnectionHttpClient.builder()).build());
        RequestScopes.runAs(account, region, () ->
                assertTrue(service.describeAutoScalingGroups(region, List.of(name)).isEmpty()));
        owners.add(owner);
        events.add("direct-service-create-intent:" + account + ":" + region);
        persist();
        RequestScopes.runAs(account, region, () -> {
            AutoScalingGroup group = service.createAutoScalingGroup(region, name, "synthetic-zero-capacity-config",
                    null, null, null, null, 0, maximum, 0, 300, List.of(region + "a"), null, null, null,
                    "EC2", 0, null, Map.of(), Map.of(), null);
            owner.incarnation = group.getMetricsIncarnation();
        });
        persist();
        return owner;
    }

    private void assertPoint(Owner owner, double expected) {
        List<Datapoint> points = owner.cloudWatch.getMetricStatistics(request -> request.namespace(NAMESPACE)
                .metricName("GroupMaxSize")
                .dimensions(Dimension.builder().name("AutoScalingGroupName").value(name).build())
                .startTime(minute).endTime(minute.plusSeconds(180)).period(60)
                .statistics(Statistic.SAMPLE_COUNT, Statistic.SUM, Statistic.AVERAGE,
                        Statistic.MINIMUM, Statistic.MAXIMUM)).datapoints();
        assertEquals(1, points.size());
        Datapoint point = points.getFirst();
        assertEquals(minute, point.timestamp());
        assertEquals(StandardUnit.NONE, point.unit());
        assertEquals(1.0, point.sampleCount());
        assertEquals(expected, point.sum());
        assertEquals(expected, point.average());
        assertEquals(expected, point.minimum());
        assertEquals(expected, point.maximum());
    }

    private void persist() {
        List<Map<String, Object>> reservations = new ArrayList<>();
        for (Owner owner : owners) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("account", owner.account);
            row.put("region", owner.region);
            row.put("name", name);
            row.put("incarnation", owner.incarnation);
            row.put("absent", owner.absent);
            reservations.add(row);
        }
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(archive.toFile(),
                    Map.of("test", test, "name", name, "reservations", reservations, "events", events, "wire", wire));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static final class Owner {
        private final String account;
        private final String region;
        private final AutoScalingClient asg;
        private final CloudWatchClient cloudWatch;
        private String incarnation;
        private boolean absent;

        private Owner(String account, String region, AutoScalingClient asg, CloudWatchClient cloudWatch) {
            this.account = account;
            this.region = region;
            this.asg = asg;
            this.cloudWatch = cloudWatch;
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
            assertTrue(wire.size() < 256);
            Map<String, Object> row = base(context.request().getClass().getSimpleName());
            row.put("kind", "request");
            row.put("uri", context.httpRequest().getUri().toString());
            row.put("headers", context.httpRequest().headers());
            context.requestBody().ifPresent(body -> {
                try (InputStream stream = body.contentStreamProvider().newStream()) {
                    byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
                    assertTrue(bytes.length <= 1024 * 1024);
                    row.put("bodyBase64", Base64.getEncoder().encodeToString(bytes));
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
            Map<String, Object> row = base(context.request().getClass().getSimpleName());
            row.put("kind", "response-before-parser");
            row.put("status", context.httpResponse().statusCode());
            row.put("headers", context.httpResponse().headers());
            try (InputStream stream = context.responseBody().orElse(InputStream.nullInputStream())) {
                byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
                row.put("bodyBase64", Base64.getEncoder().encodeToString(bytes));
                wire.add(row);
                persist();
                assertTrue(bytes.length <= 1024 * 1024);
                return Optional.of(new ByteArrayInputStream(bytes));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        private Map<String, Object> base(String operation) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("operation", operation);
            row.put("account", account);
            row.put("region", region);
            return row;
        }
    }
}
