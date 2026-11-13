package io.floci.aws.services.cloudfront;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;
import software.amazon.awssdk.services.cloudfront.model.CreateFunctionResponse;
import software.amazon.awssdk.services.cloudfront.model.DescribeFunctionResponse;
import software.amazon.awssdk.services.cloudfront.model.FunctionConfig;
import software.amazon.awssdk.services.cloudfront.model.FunctionSummary;
import software.amazon.awssdk.services.cloudfront.model.TestResult;
import software.amazon.awssdk.services.cloudfront.model.UpdateFunctionResponse;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFrontTestFunctionSdkIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REWRITE = """
            function handler(event) {
              var token = event.request.headers['x-audit-token'].value;
              console.log('rewrite:' + token + ':' + event.request.uri);
              event.request.uri = '/rewritten/' + token + event.request.uri;
              event.request.headers['x-audit-result'] = {value: token.toUpperCase()};
              return event.request;
            }
            """;
    private static final String GENERATED = """
            function handler(event) {
              return {statusCode: 200, statusDescription: 'OK',
                headers: {'x-generated': {value: event.request.headers['x-audit-token'].value}},
                body: 'generated:' + event.request.uri};
            }
            """;

    @TestHTTPResource("/")
    URI endpoint;

    private CloudFrontClient client;

    @BeforeEach
    void openClient() {
        client = CloudFrontClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(60)))
                .build();
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void developmentExecutesDifferentInputsAndReturnsActualLogs() throws Exception {
        try (OwnedFunction function = create(REWRITE)) {
            for (String token : List.of("alpha", "beta")) {
                TestResult result = evaluate(function, "DEVELOPMENT", event(token, "/input", false));
                JsonNode output = JSON.readTree(result.functionOutput());
                assertEquals("/rewritten/" + token + "/input", output.path("request").path("uri").asText());
                assertEquals(token.toUpperCase(),
                        output.path("request").path("headers").path("x-audit-result").path("value").asText());
                assertEquals(List.of("rewrite:" + token + ":/input"), result.functionExecutionLogs());
                assertEquals(function.summary, result.functionSummary());
            }
        }
    }

    @Test
    void requestHandlerCanGenerateAResponse() throws Exception {
        try (OwnedFunction function = create(GENERATED)) {
            JsonNode output = JSON.readTree(evaluate(function, "DEVELOPMENT", event("generated", "/asset", false))
                    .functionOutput());
            assertEquals(200, output.path("response").path("statusCode").asInt());
            assertEquals("generated:/asset", output.path("response").path("body").asText());
            assertEquals("generated", output.path("response").path("headers").path("x-generated").path("value").asText());
        }
    }

    @Test
    void viewerResponseHandlerUsesTheProvidedResponse() throws Exception {
        try (OwnedFunction function = create("""
                function handler(event) {
                  event.response.headers['x-result'] = {value: event.request.headers['x-audit-token'].value};
                  return event.response;
                }
                """)) {
            JsonNode output = JSON.readTree(evaluate(function, "DEVELOPMENT", event("gamma", "/asset", true))
                    .functionOutput());
            assertEquals(203, output.path("response").path("statusCode").asInt());
            assertEquals("gamma", output.path("response").path("headers").path("x-result").path("value").asText());
            assertEquals("original", output.path("response").path("body").asText());
        }
    }

    @Test
    void thrownErrorsCarryTheActualMessageAndEarlierLogs() {
        try (OwnedFunction function = create("""
                function handler(event) {
                  console.log('before:' + event.request.headers['x-audit-token'].value);
                  throw new Error('owned-error:' + event.request.headers['x-audit-token'].value);
                }
                """)) {
            TestResult result = evaluate(function, "DEVELOPMENT", event("delta", "/asset", false));
            assertNotNull(result.functionErrorMessage());
            assertTrue(result.functionErrorMessage().contains("owned-error:delta"), result.functionErrorMessage());
            assertEquals(List.of("before:delta"), result.functionExecutionLogs());
            assertTrue(result.functionOutput() == null || result.functionOutput().isEmpty());
        }
    }

    @Test
    void liveKeepsItsPublishedSourceAfterDevelopmentChanges() throws Exception {
        try (OwnedFunction function = create(REWRITE)) {
            client.publishFunction(request -> request.name(function.name).ifMatch(function.etag));
            DescribeFunctionResponse live = client.describeFunction(request -> request.name(function.name).stage("LIVE"));
            function.update(GENERATED);
            TestResult development = evaluate(function, "DEVELOPMENT", event("new", "/asset", false));
            TestResult published = client.testFunction(request -> request.name(function.name).stage("LIVE")
                    .ifMatch(live.eTag()).eventObject(SdkBytes.fromUtf8String(event("old", "/asset", false)))).testResult();
            assertEquals("generated:/asset", JSON.readTree(development.functionOutput()).path("response").path("body").asText());
            assertEquals("/rewritten/old/asset", JSON.readTree(published.functionOutput()).path("request").path("uri").asText());
            assertEquals(live.functionSummary(), published.functionSummary());
            assertEquals(REWRITE, client.getFunction(request -> request.name(function.name).stage("LIVE")).functionCode().asUtf8String());
            assertEquals(GENERATED, client.getFunction(request -> request.name(function.name).stage("DEVELOPMENT"))
                    .functionCode().asUtf8String());
        }
    }

    @Test
    void testingPreservesTheSourceConfigurationAndVersion() {
        try (OwnedFunction function = create(REWRITE)) {
            DescribeFunctionResponse before = client.describeFunction(request -> request.name(function.name)
                    .stage("DEVELOPMENT"));
            byte[] code = client.getFunction(request -> request.name(function.name).stage("DEVELOPMENT")).functionCode().asByteArray();
            evaluate(function, "DEVELOPMENT", event("unchanged", "/asset", false));
            DescribeFunctionResponse after = client.describeFunction(request -> request.name(function.name)
                    .stage("DEVELOPMENT"));
            assertEquals(before.eTag(), after.eTag());
            assertEquals(before.functionSummary(), after.functionSummary());
            assertArrayEquals(code,
                    client.getFunction(request -> request.name(function.name).stage("DEVELOPMENT")).functionCode().asByteArray());
        }
    }

    @Test
    void separateInvocationsDoNotShareJavaScriptGlobals() throws Exception {
        try (OwnedFunction function = create("""
                var count = 0;
                function handler(event) {
                  count += 1;
                  event.request.headers['x-count'] = {value: String(count)};
                  return event.request;
                }
                """)) {
            for (int i = 0; i < 2; i++) {
                JsonNode output = JSON.readTree(evaluate(function, "DEVELOPMENT", event("fresh", "/asset", false))
                        .functionOutput());
                assertEquals("1", output.path("request").path("headers").path("x-count").path("value").asText());
            }
        }
    }

    @Test
    void executionDoesNotValidateRequiredViewerHeaders() throws Exception {
        try (OwnedFunction function = create("""
                function handler(event) { delete event.request.headers.host; return event.request; }
                """)) {
            TestResult result = evaluate(function, "DEVELOPMENT", event("header", "/asset", false));
            assertTrue(result.functionErrorMessage() == null || result.functionErrorMessage().isEmpty());
            assertFalse(JSON.readTree(result.functionOutput()).path("request").path("headers").has("host"));
        }
    }

    @Test
    void aGenuinePreviousVersionIsRefusedWithoutExecuting() {
        try (OwnedFunction function = create(REWRITE)) {
            String prior = function.etag;
            function.update(GENERATED);
            assertNotEquals(prior, function.etag);
            CloudFrontException failure = assertThrows(CloudFrontException.class, () -> client.testFunction(request ->
                    request.name(function.name).stage("DEVELOPMENT").ifMatch(prior)
                            .eventObject(SdkBytes.fromUtf8String(event("old", "/asset", false)))));
            assertEquals(412, failure.statusCode());
            assertEquals("PreconditionFailed", failure.awsErrorDetails().errorCode());
            assertEquals(function.etag, client.describeFunction(request -> request.name(function.name)
                    .stage("DEVELOPMENT")).eTag());
        }
    }

    @Test
    void malformedEventJsonUsesAnAdmissionError() {
        try (OwnedFunction function = create(REWRITE)) {
            CloudFrontException failure = assertThrows(CloudFrontException.class, () -> client.testFunction(request ->
                    request.name(function.name).stage("DEVELOPMENT").ifMatch(function.etag)
                            .eventObject(SdkBytes.fromUtf8String("{"))));
            assertEquals(400, failure.statusCode());
            assertEquals("InvalidArgument", failure.awsErrorDetails().errorCode());
        }
    }

    private TestResult evaluate(OwnedFunction function, String stage, String event) {
        TestResult result = client.testFunction(request -> request.name(function.name).ifMatch(function.etag)
                .stage(stage).eventObject(SdkBytes.fromUtf8String(event))).testResult();
        assertNotNull(result);
        return result;
    }

    private static String event(String token, String uri, boolean response) {
        return """
                {"version":"1.0","context":{"eventType":"%s"},"viewer":{"ip":"192.0.2.1"},
                 "request":{"method":"GET","uri":"%s","querystring":{},"cookies":{},
                 "headers":{"host":{"value":"example.com"},"x-audit-token":{"value":"%s"}}}%s}
                """.formatted(response ? "viewer-response" : "viewer-request", uri, token,
                response ? ",\"response\":{\"statusCode\":203,\"statusDescription\":\"Non-Authoritative Information\","
                        + "\"headers\":{},\"cookies\":{},\"body\":\"original\"}" : "");
    }

    private OwnedFunction create(String code) {
        String name = "test-function-" + UUID.randomUUID().toString().replace("-", "");
        FunctionConfig config = FunctionConfig.builder().comment(name).runtime("cloudfront-js-2.0").build();
        CreateFunctionResponse created = client.createFunction(
                request -> request.name(name).functionConfig(config).functionCode(SdkBytes.fromUtf8String(code)));
        return new OwnedFunction(name, created.eTag(), created.functionSummary());
    }

    private final class OwnedFunction implements AutoCloseable {
        private final String name;
        private final Instant createdTime;
        private String etag;
        private FunctionSummary summary;

        private OwnedFunction(String name, String etag, FunctionSummary summary) {
            this.name = name;
            this.etag = etag;
            this.summary = summary;
            this.createdTime = summary.functionMetadata().createdTime();
        }

        private void update(String code) {
            UpdateFunctionResponse updated = client.updateFunction(
                    request -> request.name(name).ifMatch(etag).functionConfig(summary.functionConfig())
                            .functionCode(SdkBytes.fromUtf8String(code)));
            etag = updated.eTag();
            summary = updated.functionSummary();
        }

        @Override
        public void close() {
            DescribeFunctionResponse current = client.describeFunction(request -> request.name(name).stage("DEVELOPMENT"));
            assertEquals(createdTime, current.functionSummary().functionMetadata().createdTime());
            assertEquals(name, current.functionSummary().functionConfig().comment());
            client.deleteFunction(request -> request.name(name).ifMatch(current.eTag()));
            for (String stage : List.of("DEVELOPMENT", "LIVE")) {
                CloudFrontException missing = assertThrows(CloudFrontException.class,
                        () -> client.describeFunction(request -> request.name(name).stage(stage)));
                assertEquals(404, missing.statusCode());
                assertEquals("NoSuchFunctionExists", missing.awsErrorDetails().errorCode());
            }
        }
    }
}
