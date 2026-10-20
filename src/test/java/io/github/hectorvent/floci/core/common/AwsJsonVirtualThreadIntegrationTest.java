package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.swf.SwfService;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@QuarkusTest
@TestProfile(AwsJsonVirtualThreadIntegrationTest.FiniteWorkerPoolProfile.class)
class AwsJsonVirtualThreadIntegrationTest {

    private static final String JSON_1_0 = "application/x-amz-json-1.0";
    private static final String SWF = "SimpleWorkflowService.";
    private static final List<Scope> SCOPES = List.of(
            new Scope("111122223333", "us-east-1"),
            new Scope("111122223333", "us-west-2"),
            new Scope("444455556666", "us-east-1"),
            new Scope("444455556666", "us-west-2"));

    @TestHTTPResource("/")
    URI endpoint;

    @InjectSpy
    SwfService swf;

    @Inject
    RequestContext context;

    @Inject
    ObjectMapper mapper;

    public static final class FiniteWorkerPoolProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.thread-pool.core-threads", "4",
                    "quarkus.thread-pool.max-threads", "4");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {JSON_1_0, "application/json"})
    void idlePollsLeaveWorkersAvailableAndPreserveRequestScope(String contentType) throws Exception {
        String domain = "dispatch-" + UUID.randomUUID();
        String table = "dispatch-" + UUID.randomUUID();
        CountDownLatch waiting = new CountDownLatch(SCOPES.size());
        Map<String, Scope> observed = new ConcurrentHashMap<>();
        List<CompletableFuture<HttpResponse<String>>> polls = new ArrayList<>();
        try (HttpClient client = HttpClient.newHttpClient()) {
            for (Scope scope : SCOPES) {
                assertEquals(200, response(post(client, scope, "swf", contentType,
                        SWF + "RegisterDomain", """
                        {"name":"%s","workflowExecutionRetentionPeriodInDays":"1"}
                        """.formatted(domain))).statusCode());
            }
            assertEquals(200, response(post(client, SCOPES.getFirst(), "dynamodb", JSON_1_0,
                    "DynamoDB_20120810.CreateTable", """
                    {"TableName":"%s","KeySchema":[{"AttributeName":"id","KeyType":"HASH"}],
                     "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                     "BillingMode":"PAY_PER_REQUEST"}
                    """.formatted(table))).statusCode());

            // This claim is reached after the real timed poll registers its wakeup signal.
            doAnswer(call -> {
                observed.put(call.getArgument(3), new Scope(context.getAccountId(), context.getRegion()));
                waiting.countDown();
                return call.callRealMethod();
            }).when(swf).pollForDecisionTask(anyString(), anyString(), anyString(), anyString());
            try {
                for (Scope scope : SCOPES) {
                    polls.add(post(client, scope, "swf", contentType, SWF + "PollForDecisionTask", """
                            {"domain":"%s","taskList":{"name":"idle"},"identity":"%s"}
                            """.formatted(domain, scope.identity())));
                }
                assertTrue(waiting.await(5, TimeUnit.SECONDS), "All four HTTP polls must enter the real wait path");
                for (Scope scope : SCOPES) {
                    assertEquals(scope, observed.get(scope.identity()));
                    HttpResponse<String> listed = response(post(client, scope, "dynamodb", JSON_1_0,
                            "DynamoDB_20120810.ListTables", "{}"));
                    assertEquals(200, listed.statusCode());
                    assertEquals(scope.equals(SCOPES.getFirst()),
                            mapper.readTree(listed.body()).path("TableNames").toString().contains(table));
                }
                HttpResponse<String> duplicate = response(post(client, SCOPES.getFirst(), "swf", contentType,
                        SWF + "RegisterDomain", """
                        {"name":"%s","workflowExecutionRetentionPeriodInDays":"1"}
                        """.formatted(domain)));
                assertEquals(400, duplicate.statusCode());
                assertEquals("com.amazonaws.swf.base.model#DomainAlreadyExistsFault",
                        mapper.readTree(duplicate.body()).path("__type").asText());
                HttpResponse<String> regions = response(post(client, SCOPES.getFirst(), "ec2",
                        "application/x-www-form-urlencoded", null,
                        "Action=DescribeRegions&Version=2016-11-15"));
                assertEquals(200, regions.statusCode());
                assertTrue(regions.body().contains("<DescribeRegionsResponse"));
                assertTrue(regions.body().contains("<regionName>us-east-1</regionName>"));
                HttpResponse<String> health = response(client.sendAsync(
                        HttpRequest.newBuilder(endpoint.resolve("health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString()));
                assertEquals(200, health.statusCode());
                assertEquals("community", mapper.readTree(health.body()).path("edition").asText());
                for (CompletableFuture<HttpResponse<String>> poll : polls) {
                    assertFalse(poll.isDone(), "Normal responses must finish before idle polls are released");
                }
            } finally {
                swf.clear();
                for (CompletableFuture<HttpResponse<String>> poll : polls) {
                    HttpResponse<String> empty = poll.get(5, TimeUnit.SECONDS);
                    assertEquals(200, empty.statusCode());
                    JsonNode body = mapper.readTree(empty.body());
                    assertEquals("", body.path("taskToken").asText());
                }
            }
        }
    }

    private CompletableFuture<HttpResponse<String>> post(HttpClient client, Scope scope, String service,
                                                          String contentType, String target, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", contentType)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + scope.account()
                        + "/20261020/" + scope.region() + "/" + service
                        + "/aws4_request, SignedHeaders=host, Signature=abc")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (target != null) {
            request.header("X-Amz-Target", target);
        }
        return client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> response(CompletableFuture<HttpResponse<String>> request) {
        return assertDoesNotThrow(() -> request.get(3, TimeUnit.SECONDS),
                "A normal HTTP request must complete while SWF polls remain pending");
    }

    private record Scope(String account, String region) {
        String identity() {
            return account + "-" + region;
        }
    }
}
