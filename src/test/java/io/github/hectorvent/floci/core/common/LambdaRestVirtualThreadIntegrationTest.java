package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(AwsJsonVirtualThreadIntegrationTest.FiniteWorkerPoolProfile.class)
class LambdaRestVirtualThreadIntegrationTest {

    private static final List<Scope> SCOPES = List.of(
            new Scope("111122223333", "us-east-1"),
            new Scope("111122223333", "us-west-2"),
            new Scope("444455556666", "us-east-1"),
            new Scope("444455556666", "us-west-2"));

    @TestHTTPResource("/")
    URI endpoint;

    @InjectMock
    LambdaService lambdaService;

    @Inject
    RequestContext context;

    @Inject
    ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = {"Invoke", "GET", "POST", "PUT", "DELETE", "PATCH"})
    void synchronousInvocationsLeaveWorkersAvailableAndPreserveScope(String route) throws Exception {
        String table = "lambda-dispatch-" + UUID.randomUUID();
        CountDownLatch waiting = new CountDownLatch(SCOPES.size());
        CompletableFuture<InvokeResult> released = new CompletableFuture<>();
        Map<String, Scope> targets = new ConcurrentHashMap<>();
        Map<Scope, Scope> observed = new ConcurrentHashMap<>();
        List<CompletableFuture<HttpResponse<String>>> requests = new ArrayList<>();
        InvokeResult result = new InvokeResult(200, null,
                "{\"statusCode\":201,\"body\":\"released\"}".getBytes(StandardCharsets.UTF_8), null, "request");
        result.setExecutedVersion("1");

        for (Scope scope : SCOPES) {
            targets.put(scope.functionName(), scope);
            targets.put(scope.functionArn(), scope);
        }
        when(lambdaService.getTargetByUrlId(anyString())).thenAnswer(call -> {
            Scope scope = SCOPES.stream()
                    .filter(candidate -> candidate.identity().equals(call.getArgument(0)))
                    .findFirst().orElseThrow();
            LambdaFunction function = new LambdaFunction();
            function.setFunctionName(scope.functionName());
            function.setFunctionArn(scope.functionArn());
            function.setAccountId(scope.account());
            return function;
        });
        // Hold only the service boundary: real REST dispatch and request scope are exercised without Docker.
        doAnswer(call -> {
            Scope target = targets.get(call.getArgument(1));
            assertEquals(InvocationType.RequestResponse, call.getArgument(4));
            observed.put(target, new Scope(context.getAccountId(), context.getRegion()));
            waiting.countDown();
            return released.get(10, TimeUnit.SECONDS);
        }).when(lambdaService).invoke(anyString(), anyString(), any(), any(), any(), any(), any());
        doAnswer(call -> {
            Scope target = targets.get(call.getArgument(0));
            assertEquals(InvocationType.RequestResponse, call.getArgument(2));
            observed.put(target, new Scope(context.getAccountId(), context.getRegion()));
            waiting.countDown();
            return released.get(10, TimeUnit.SECONDS);
        }).when(lambdaService).invokeArn(anyString(), any(), any());

        try (HttpClient client = HttpClient.newHttpClient()) {
            assertEquals(200, response(json(client, SCOPES.getFirst(), "DynamoDB_20120810.CreateTable", """
                    {"TableName":"%s","KeySchema":[{"AttributeName":"id","KeyType":"HASH"}],
                     "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                     "BillingMode":"PAY_PER_REQUEST"}
                    """.formatted(table))).statusCode());
            try {
                for (Scope scope : SCOPES) {
                    String path = route.equals("Invoke")
                            ? "2015-03-31/functions/" + scope.functionName() + "/invocations"
                            : "lambda-url/" + scope.identity() + "/check";
                    String method = route.equals("Invoke") ? "POST" : route;
                    HttpRequest request = authorized(endpoint.resolve(path), scope, "lambda")
                            .header("Content-Type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofString("{}")).build();
                    requests.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
                }
                assertTrue(waiting.await(5, TimeUnit.SECONDS), "All four REST requests must enter the held invoke");
                for (Scope scope : SCOPES) {
                    assertEquals(scope, observed.get(scope));
                }
                HttpResponse<String> health = response(client.sendAsync(
                        HttpRequest.newBuilder(endpoint.resolve("health")).GET().build(),
                        HttpResponse.BodyHandlers.ofString()));
                assertEquals(200, health.statusCode());
                assertEquals("community", mapper.readTree(health.body()).path("edition").asText());
                for (Scope scope : SCOPES) {
                    HttpResponse<String> listed = response(json(client, scope, "DynamoDB_20120810.ListTables", "{}"));
                    assertEquals(200, listed.statusCode());
                    assertEquals(scope.equals(SCOPES.getFirst()),
                            mapper.readTree(listed.body()).path("TableNames").toString().contains(table));
                }
                HttpResponse<String> regions = response(client.sendAsync(
                        authorized(endpoint, SCOPES.getFirst(), "ec2")
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString("Action=DescribeRegions&Version=2016-11-15"))
                                .build(), HttpResponse.BodyHandlers.ofString()));
                assertEquals(200, regions.statusCode());
                assertTrue(regions.body().contains("<DescribeRegionsResponse"));
                for (CompletableFuture<HttpResponse<String>> request : requests) {
                    assertFalse(request.isDone(), "Normal responses must finish before invocations are released");
                }
            } finally {
                released.complete(result);
                try {
                    for (CompletableFuture<HttpResponse<String>> request : requests) {
                        HttpResponse<String> response = request.get(5, TimeUnit.SECONDS);
                        assertEquals(route.equals("Invoke") ? 200 : 201, response.statusCode());
                        if (route.equals("Invoke")) {
                            assertEquals("1", response.headers().firstValue("X-Amz-Executed-Version").orElseThrow());
                            assertEquals("released", mapper.readTree(response.body()).path("body").asText());
                        } else {
                            assertEquals("released", response.body());
                        }
                    }
                } finally {
                    assertEquals(200, response(json(client, SCOPES.getFirst(), "DynamoDB_20120810.DeleteTable",
                            "{\"TableName\":\"" + table + "\"}")).statusCode());
                }
            }
        }
    }

    private CompletableFuture<HttpResponse<String>> json(HttpClient client, Scope scope, String target, String body) {
        return client.sendAsync(authorized(endpoint, scope, "dynamodb")
                        .header("Content-Type", "application/x-amz-json-1.0")
                        .header("X-Amz-Target", target)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder authorized(URI uri, Scope scope, String service) {
        return HttpRequest.newBuilder(uri)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + scope.account()
                        + "/20261020/" + scope.region() + "/" + service
                        + "/aws4_request, SignedHeaders=host, Signature=abc");
    }

    private static HttpResponse<String> response(CompletableFuture<HttpResponse<String>> request) {
        return assertDoesNotThrow(() -> request.get(3, TimeUnit.SECONDS),
                "A normal HTTP request must complete while Lambda invocations remain pending");
    }

    private record Scope(String account, String region) {
        String identity() {
            return account + "-" + region;
        }

        String functionName() {
            return "held-" + identity();
        }

        String functionArn() {
            return "arn:aws:lambda:" + region + ":" + account + ":function:" + functionName();
        }
    }
}
