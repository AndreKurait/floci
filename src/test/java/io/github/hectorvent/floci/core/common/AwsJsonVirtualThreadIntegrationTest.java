package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.swf.SwfService;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
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

    @InjectSpy
    SqsService sqs;

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

    @ParameterizedTest
    @ValueSource(strings = {"query-post", "query-get", "cbor-target", "cbor-rpc"})
    void otherProtocolsLeaveWorkersAvailableDuringSqsPolls(String protocol) throws Exception {
        String prefix = "dispatch-sqs-" + UUID.randomUUID();
        CountDownLatch waiting = new CountDownLatch(SCOPES.size());
        Map<String, Scope> observed = new ConcurrentHashMap<>();
        Map<Scope, String> queues = new LinkedHashMap<>();
        List<CompletableFuture<HttpResponse<byte[]>>> polls = new ArrayList<>();
        ObjectMapper cbor = new ObjectMapper(new CBORFactory());
        try (HttpClient client = HttpClient.newHttpClient()) {
            try {
                for (Scope scope : SCOPES) {
                    HttpResponse<byte[]> created = response(sqsRequest(client, scope, "query-post",
                            "CreateQueue", Map.of("QueueName", prefix + "-" + scope.identity()), cbor));
                    assertEquals(200, created.statusCode());
                    String queueUrl = xml(created).getString("CreateQueueResponse.CreateQueueResult.QueueUrl");
                    queues.put(scope, queueUrl);
                }
                doAnswer(call -> {
                    String queueUrl = call.getArgument(0);
                    Scope current = new Scope(context.getAccountId(), context.getRegion());
                    if (observed.putIfAbsent(queueUrl, current) == null) {
                        waiting.countDown();
                    }
                    return call.callRealMethod();
                }).when(sqs).receiveMessage(anyString(), anyInt(), anyInt(), nullable(Integer.class), anyString());
                try {
                    for (Scope scope : SCOPES) {
                        polls.add(sqsRequest(client, scope, protocol, "ReceiveMessage",
                                Map.of("QueueUrl", queues.get(scope), "WaitTimeSeconds", 20), cbor));
                    }
                    assertTrue(waiting.await(5, TimeUnit.SECONDS), "All four HTTP receives must enter the service");
                    for (Scope scope : SCOPES) {
                        assertEquals(scope, observed.get(queues.get(scope)));
                        HttpResponse<byte[]> listed = response(sqsRequest(client, scope, protocol,
                                "ListQueues", Map.of("QueueNamePrefix", prefix), cbor));
                        assertEquals(200, listed.statusCode());
                        if (protocol.startsWith("query")) {
                            assertEquals(List.of(queues.get(scope)),
                                    xml(listed).getList("ListQueuesResponse.ListQueuesResult.QueueUrl", String.class));
                            assertTrue(listed.headers().firstValue("Content-Type").orElseThrow()
                                    .startsWith("application/xml"));
                        } else {
                            assertEquals(mapper.valueToTree(List.of(queues.get(scope))),
                                    cbor.readTree(listed.body()).path("QueueUrls"));
                            assertEquals("rpc-v2-cbor",
                                    listed.headers().firstValue("smithy-protocol").orElseThrow());
                        }
                    }
                    HttpResponse<byte[]> invalid = response(sqsRequest(client, SCOPES.getFirst(), protocol,
                            "ReceiveMessage", Map.of("QueueUrl", queues.get(SCOPES.getFirst()),
                                    "WaitTimeSeconds", 21), cbor));
                    assertEquals(400, invalid.statusCode());
                    if (protocol.startsWith("query")) {
                        assertEquals("InvalidParameterValue", xml(invalid).getString("ErrorResponse.Error.Code"));
                    } else {
                        assertEquals("InvalidParameterValue", cbor.readTree(invalid.body()).path("__type").asText());
                    }
                    HttpResponse<String> regions = response(post(client, SCOPES.getFirst(), "ec2",
                            "application/x-www-form-urlencoded", null,
                            "Action=DescribeRegions&Version=2016-11-15"));
                    assertEquals(200, regions.statusCode());
                    assertTrue(regions.body().contains("<DescribeRegionsResponse"));
                    HttpResponse<String> health = response(client.sendAsync(
                            HttpRequest.newBuilder(endpoint.resolve("health")).GET().build(),
                            HttpResponse.BodyHandlers.ofString()));
                    assertEquals(200, health.statusCode());
                    assertEquals("community", mapper.readTree(health.body()).path("edition").asText());
                    for (CompletableFuture<HttpResponse<byte[]>> poll : polls) {
                        assertFalse(poll.isDone(), "Normal responses must finish before SQS polls are released");
                    }
                } finally {
                    for (Scope scope : queues.keySet()) {
                        RequestScopes.runAs(scope.account(), scope.region(),
                                () -> sqs.sendMessage(queues.get(scope), scope.identity(), 0, scope.region()));
                    }
                    for (int index = 0; index < polls.size(); index++) {
                        HttpResponse<byte[]> delivered = polls.get(index).get(5, TimeUnit.SECONDS);
                        assertEquals(200, delivered.statusCode());
                        String expected = SCOPES.get(index).identity();
                        if (protocol.startsWith("query")) {
                            assertEquals(List.of(expected), xml(delivered)
                                    .getList("ReceiveMessageResponse.ReceiveMessageResult.Message.Body", String.class));
                        } else {
                            JsonNode messages = cbor.readTree(delivered.body()).path("Messages");
                            assertEquals(1, messages.size());
                            assertEquals(expected, messages.path(0).path("Body").asText());
                        }
                    }
                }
            } finally {
                for (Scope scope : queues.keySet()) {
                    RequestScopes.runAs(scope.account(), scope.region(),
                            () -> sqs.deleteQueue(queues.get(scope), scope.region()));
                }
            }
        }
    }

    private CompletableFuture<HttpResponse<byte[]>> sqsRequest(HttpClient client, Scope scope, String protocol,
                                                               String action, Map<String, Object> parameters,
                                                               ObjectMapper cbor) throws Exception {
        URI uri = endpoint;
        byte[] body;
        String contentType;
        if (protocol.startsWith("query")) {
            Map<String, Object> query = new LinkedHashMap<>(parameters);
            query.put("Action", action);
            query.put("Version", "2012-11-05");
            List<String> pairs = new ArrayList<>();
            query.forEach((key, value) -> pairs.add(URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(value.toString(), StandardCharsets.UTF_8)));
            String encoded = String.join("&", pairs);
            if ("query-get".equals(protocol)) {
                uri = URI.create(endpoint + "?" + encoded);
            }
            body = encoded.getBytes(StandardCharsets.UTF_8);
            contentType = "application/x-www-form-urlencoded";
        } else {
            body = cbor.writeValueAsBytes(parameters);
            contentType = "application/x-amz-cbor-1.1";
            if ("cbor-rpc".equals(protocol)) {
                uri = endpoint.resolve("service/SQS/operation/" + action);
            }
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + scope.account()
                        + "/20261020/" + scope.region() + "/sqs/aws4_request, SignedHeaders=host, Signature=abc");
        if ("query-get".equals(protocol)) {
            request.GET();
        } else {
            request.header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        }
        if ("cbor-target".equals(protocol)) {
            request.header("X-Amz-Target", "AmazonSQS." + action);
        } else if ("cbor-rpc".equals(protocol)) {
            request.header("smithy-protocol", "rpc-v2-cbor");
        }
        return client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static XmlPath xml(HttpResponse<byte[]> response) {
        return new XmlPath(new String(response.body(), StandardCharsets.UTF_8));
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

    private static <T> HttpResponse<T> response(CompletableFuture<HttpResponse<T>> request) {
        return assertDoesNotThrow(() -> request.get(3, TimeUnit.SECONDS),
                "A normal HTTP request must complete while long polls remain pending");
    }

    private record Scope(String account, String region) {
        String identity() {
            return account + "-" + region;
        }
    }
}
