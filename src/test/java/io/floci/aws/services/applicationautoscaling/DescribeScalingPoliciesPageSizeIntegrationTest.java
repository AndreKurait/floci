package io.floci.aws.services.applicationautoscaling;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.ApplicationAutoScalingException;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingPoliciesRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingPoliciesResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalableTarget;
import software.amazon.awssdk.services.applicationautoscaling.model.ScalingPolicy;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@Timeout(60)
class DescribeScalingPoliciesPageSizeIntegrationTest {
    private static final Logger LOG = Logger.getLogger(DescribeScalingPoliciesPageSizeIntegrationTest.class);
    private ApplicationAutoScalingClient scaling;
    private DynamoDbClient dynamo;
    private String marker;
    private String table;
    private String resource;
    private boolean tableReserved;
    private boolean targetReserved;
    private boolean policyReserved;

    @BeforeEach
    void openOwnedClients() {
        String account = Long.toString(ThreadLocalRandom.current().nextLong(750000000000L, 759999999999L));
        marker = UUID.randomUUID().toString();
        table = "policy-pages-" + marker;
        resource = "table/" + table;
        WireReceipt wire = new WireReceipt();
        scaling = AwsTestClients.create(ApplicationAutoScalingClient.builder().overrideConfiguration(config -> config
                .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)),
                account, Region.US_EAST_1, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(6)));
        dynamo = AwsTestClients.create(DynamoDbClient.builder().overrideConfiguration(config -> config
                .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)), account, Region.US_EAST_1,
                UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3))
                        .socketTimeout(Duration.ofSeconds(6)));
        assertEquals(List.of(), dynamo.listTables().tableNames());
        assertEquals(List.of(), policies(10, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {11, 50, 1000, Integer.MAX_VALUE})
    void acceptsPositivePageSizesForEmptyPopulatedAndFilteredPolicies(int size) {
        List<String> failures = new ArrayList<>();
        comparePage(size, null, List.of(), failures);
        tableReserved = true;
        dynamo.createTable(request -> request.tableName(table).billingMode("PROVISIONED")
                .attributeDefinitions(attribute -> attribute.attributeName("pk").attributeType("S"))
                .keySchema(key -> key.attributeName("pk").keyType("HASH"))
                .provisionedThroughput(capacity -> capacity.readCapacityUnits(1L).writeCapacityUnits(1L)));
        await().atMost(Duration.ofSeconds(10)).until(() -> "ACTIVE".equals(
                dynamo.describeTable(request -> request.tableName(table)).table().tableStatusAsString()));
        targetReserved = true;
        scaling.registerScalableTarget(request -> request.serviceNamespace("dynamodb").resourceId(resource)
                .scalableDimension("dynamodb:table:WriteCapacityUnits").minCapacity(1).maxCapacity(1));
        policyReserved = true;
        scaling.putScalingPolicy(request -> request.serviceNamespace("dynamodb").resourceId(resource)
                .scalableDimension("dynamodb:table:WriteCapacityUnits").policyName("owned-policy")
                .policyType("TargetTrackingScaling").targetTrackingScalingPolicyConfiguration(configuration ->
                        configuration.targetValue(50.0).predefinedMetricSpecification(metric ->
                                metric.predefinedMetricType("DynamoDBWriteCapacityUtilization"))));
        List<ScalingPolicy> baseline = policies(10, null);
        assertEquals(1, baseline.size());
        TableDescription tableBefore = dynamo.describeTable(request -> request.tableName(table)).table();
        List<ScalableTarget> targetsBefore = scaling.describeScalableTargets(request -> request.serviceNamespace("dynamodb")
                .resourceIds(resource)).scalableTargets();
        comparePage(size, null, baseline, failures);
        comparePage(size, List.of("owned-policy"), baseline, failures);
        comparePage(size, List.of("missing-policy"), List.of(), failures);
        assertEquals(baseline, policies(10, null));
        assertEquals(tableBefore, dynamo.describeTable(request -> request.tableName(table)).table());
        assertEquals(targetsBefore, scaling.describeScalableTargets(request -> request.serviceNamespace("dynamodb")
                .resourceIds(resource)).scalableTargets());
        assertTrue(failures.isEmpty(), () -> String.join("; ", failures));
    }

    private List<ScalingPolicy> policies(int size, List<String> names) {
        DescribeScalingPoliciesRequest.Builder request = DescribeScalingPoliciesRequest.builder().serviceNamespace("dynamodb").resourceId(resource)
                .scalableDimension("dynamodb:table:WriteCapacityUnits").maxResults(size);
        if (names != null) {
            request.policyNames(names);
        }
        DescribeScalingPoliciesResponse response = scaling.describeScalingPolicies(request.build());
        assertEquals(200, response.sdkHttpResponse().statusCode());
        assertNull(response.nextToken());
        return response.scalingPolicies();
    }

    private void comparePage(int size, List<String> names, List<ScalingPolicy> expected, List<String> failures) {
        try {
            assertEquals(expected, policies(size, names));
        } catch (ApplicationAutoScalingException error) {
            failures.add("MaxResults=" + size + ", PolicyNames=" + names + ": HTTP " + error.statusCode()
                    + " " + error.awsErrorDetails().errorCode());
        }
    }

    @AfterEach
    void removeOwnedResources() {
        try {
            try {
                if (policyReserved) {
                    scaling.deleteScalingPolicy(request -> request.serviceNamespace("dynamodb").resourceId(resource)
                            .scalableDimension("dynamodb:table:WriteCapacityUnits").policyName("owned-policy"));
                }
            } finally {
                try {
                    if (targetReserved) {
                        scaling.deregisterScalableTarget(request -> request.serviceNamespace("dynamodb")
                                .resourceId(resource).scalableDimension("dynamodb:table:WriteCapacityUnits"));
                    }
                } finally {
                    if (tableReserved) {
                        dynamo.deleteTable(request -> request.tableName(table));
                        await().atMost(Duration.ofSeconds(10)).until(() ->
                                dynamo.listTables().tableNames().isEmpty());
                        assertThrows(ResourceNotFoundException.class,
                                () -> dynamo.describeTable(request -> request.tableName(table)));
                    }
                }
            }
            assertEquals(List.of(), policies(10, null));
            assertEquals(List.of(), scaling.describeScalableTargets(request -> request.serviceNamespace("dynamodb")
                    .resourceIds(resource)).scalableTargets());
        } finally {
            if (scaling != null) {
                scaling.close();
            }
            if (dynamo != null) {
                dynamo.close();
            }
        }
    }

    private final class WireReceipt implements ExecutionInterceptor {
        private int requestId;

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            requestId++;
            assertEquals("localhost", context.httpRequest().getUri().getHost());
            assertEquals("http", context.httpRequest().getUri().getScheme());
            try (InputStream stream = context.requestBody().orElseThrow().contentStreamProvider().newStream()) {
                byte[] body = stream.readNBytes(262145);
                assertTrue(body.length <= 262144);
                LOG.infov("POLICY_PAGES_REQUEST marker={0} id={1} method={2} uri={3} headers={4} base64={5}",
                        marker, requestId, context.httpRequest().method(), context.httpRequest().getUri(),
                        Map.of("content-type", context.httpRequest().firstMatchingHeader("content-type").orElse(""),
                                "x-amz-target", context.httpRequest().firstMatchingHeader("x-amz-target").orElse("")),
                        Base64.getEncoder().encodeToString(body));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(
                Context.ModifyHttpResponse context, ExecutionAttributes attributes) {
            try (InputStream stream = context.responseBody().orElseThrow()) {
                byte[] body = stream.readNBytes(262145);
                assertTrue(body.length <= 262144);
                LOG.infov("POLICY_PAGES_RESPONSE marker={0} id={1} status={2} headers={3} base64={4}",
                        marker, requestId, context.httpResponse().statusCode(), context.httpResponse().headers(),
                        Base64.getEncoder().encodeToString(body));
                return Optional.of(new ByteArrayInputStream(body));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }
}
