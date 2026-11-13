package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class CloudWatchLogsGzipIntegrationTest {

    private static final AwsRequestSigner ROOT = AwsRequestSigner.signedAs("test", "test", "logs");
    private static final String STREAM = "application";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestHTTPResource("/")
    URI endpoint;

    private CloudWatchLogsClient logs;
    private String group;

    @BeforeAll
    static void configureAwsContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createLogStream() {
        logs = CloudWatchLogsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        group = "/gzip/" + UUID.randomUUID();
        logs.createLogGroup(request -> request.logGroupName(group));
        logs.createLogStream(request -> request.logGroupName(group).logStreamName(STREAM));
    }

    @AfterEach
    void deleteLogGroup() {
        try {
            logs.deleteLogGroup(request -> request.logGroupName(group));
        } finally {
            logs.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void plainAndCompressedPublicationRetainNativeEvents(boolean compressed) throws Exception {
        String message = "first line\nUnicode: \u03bb\n".repeat(150);
        long timestamp = Instant.now().toEpochMilli();
        byte[] body = publication(STREAM, message, timestamp);
        request(ROOT, "PutLogEvents", compressed ? gzip(body) : body, compressed ? "gzip" : null)
                .then().statusCode(200);
        assertEquals(message, logs.getLogEvents(request -> request.logGroupName(group).logStreamName(STREAM))
                .events().getFirst().message());
        assertEquals(timestamp, logs.getLogEvents(request -> request.logGroupName(group).logStreamName(STREAM))
                .events().getFirst().timestamp());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-header", "truncated", "invalid-crc"})
    void malformedGzipCannotWriteLogEvents(String corruption) throws Exception {
        byte[] body = gzip(publication(STREAM, "never stored", Instant.now().toEpochMilli()));
        switch (corruption) {
            case "invalid-header" -> body[0] = 0;
            case "truncated" -> body = Arrays.copyOf(body, body.length - 5);
            case "invalid-crc" -> body[body.length - 8] ^= 1;
            default -> throw new IllegalArgumentException(corruption);
        }
        request(ROOT, "PutLogEvents", body, "gzip").then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        assertEmpty(STREAM);
    }

    @Test
    void wireSignatureIsCheckedBeforeGzipDecoding() throws Exception {
        request(ROOT.withSecret("wrong-secret"), "PutLogEvents", new byte[]{1, 2, 3}, "gzip")
                .then().statusCode(403).body("__type", equalTo("InvalidSignatureException"));
        assertEmpty(STREAM);
    }

    @Test
    void decodedInvalidJsonRetainsItsSerializationError() throws Exception {
        request(ROOT, "PutLogEvents", gzip("{".getBytes(StandardCharsets.UTF_8)), "gzip")
                .then().statusCode(400).body("__type", equalTo("SerializationException"));
        assertEmpty(STREAM);
    }

    @Test
    void boundedInflationRejectsAnOversizeBodyBeforeWritingEvents() throws Exception {
        byte[] original = publication(STREAM, "never stored", Instant.now().toEpochMilli());
        byte[] padded = Arrays.copyOf(original, 10 * 1024 * 1024 + 1);
        Arrays.fill(padded, original.length, padded.length, (byte) ' ');
        request(ROOT, "PutLogEvents", gzip(padded), "gzip").then().statusCode(413)
                .body("__type", equalTo("RequestEntityTooLargeException"));
        assertEmpty(STREAM);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void iamAuthorizesTheDecodedLogStreamBeforeTheHandlerWritesEvents(boolean compressed) throws Exception {
        String deniedStream = "denied";
        logs.createLogStream(request -> request.logGroupName(group).logStreamName(deniedStream));
        String user = "gzip-" + UUID.randomUUID();
        try (IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            iam.createUser(request -> request.userName(user));
            AccessKey key = null;
            try {
                key = iam.createAccessKey(request -> request.userName(user)).accessKey();
                iam.putUserPolicy(request -> request.userName(user).policyName("one-stream").policyDocument("""
                        {"Version":"2012-10-17","Statement":[{
                          "Effect":"Allow","Action":"logs:PutLogEvents",
                          "Resource":"arn:aws:logs:us-east-1:000000000000:log-group:%s:log-stream:%s"
                        }]}
                        """.formatted(group, STREAM)));
                AwsRequestSigner signer = AwsRequestSigner.signedAs(key.accessKeyId(), key.secretAccessKey(), "logs");
                byte[] allowed = publication(STREAM, "allowed", Instant.now().toEpochMilli());
                byte[] denied = publication(deniedStream, "denied", Instant.now().toEpochMilli());
                String encoding = compressed ? "gzip" : null;
                request(signer, "PutLogEvents", compressed ? gzip(allowed) : allowed, encoding)
                        .then().statusCode(200);
                request(signer, "PutLogEvents", compressed ? gzip(denied) : denied, encoding)
                        .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
                assertEmpty(deniedStream);
                iam.putUserPolicy(request -> request.userName(user).policyName("one-stream").policyDocument("""
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"logs:PutLogEvents","Resource":"*"},
                          {"Effect":"Deny","Action":"logs:PutLogEvents",
                           "Resource":"arn:aws:logs:us-east-1:000000000000:log-group:%s:log-stream:%s"}
                        ]}
                        """.formatted(group, STREAM)));
                request(signer, "PutLogEvents", compressed ? gzip(allowed) : allowed, encoding)
                        .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
                request(signer, "PutLogEvents", compressed ? gzip(denied) : denied, encoding)
                        .then().statusCode(200);
                assertEquals(1,
                        logs.getLogEvents(request -> request.logGroupName(group).logStreamName(STREAM))
                                .events().size());
                assertEquals("denied",
                        logs.getLogEvents(request -> request.logGroupName(group).logStreamName(deniedStream))
                                .events().getFirst().message());
                logs.tagLogGroup(request -> request.logGroupName(group).tags(Map.of("protected", "true")));
                iam.putUserPolicy(request -> request.userName(user).policyName("one-stream").policyDocument("""
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"logs:PutLogEvents","Resource":"*"},
                          {"Effect":"Deny","Action":"logs:PutLogEvents","Resource":"*",
                           "Condition":{"StringEquals":{"aws:ResourceTag/protected":"true"}}}
                        ]}
                        """));
                request(signer, "PutLogEvents", compressed ? gzip(allowed) : allowed, encoding)
                        .then().statusCode(400).body("__type", equalTo("AccessDeniedException"));
                assertEquals(1, logs.getLogEvents(request -> request.logGroupName(group).logStreamName(STREAM))
                        .events().size());
                logs.tagLogGroup(request -> request.logGroupName(group).tags(Map.of("protected", "false")));
                request(signer, "PutLogEvents", compressed ? gzip(allowed) : allowed, encoding)
                        .then().statusCode(200);
                assertEquals(2, logs.getLogEvents(request -> request.logGroupName(group).logStreamName(STREAM))
                        .events().size());
            } finally {
                if (key != null) {
                    String keyId = key.accessKeyId();
                    iam.deleteAccessKey(request -> request.userName(user).accessKeyId(keyId));
                }
                iam.deleteUserPolicy(request -> request.userName(user).policyName("one-stream"));
                iam.deleteUser(request -> request.userName(user));
            }
        }
    }

    @Test
    void otherLogsOperationsKeepTheirExistingBodyHandling() throws Exception {
        request(ROOT, "DescribeLogGroups", gzip("{}".getBytes(StandardCharsets.UTF_8)), "gzip")
                .then().statusCode(400).body("__type", equalTo("SerializationException"));
    }

    private byte[] publication(String stream, String message, long timestamp) throws Exception {
        return JSON.writeValueAsBytes(Map.of(
                "logGroupName", group, "logStreamName", stream,
                "logEvents", new Object[]{Map.of("timestamp", timestamp, "message", message)}));
    }

    private Response request(AwsRequestSigner signer, String operation, byte[] body, String encoding) throws Exception {
        Map<String, String> signed = new HashMap<>();
        signed.put("x-amz-target", "Logs_20140328." + operation);
        if (encoding != null) {
            signed.put("content-encoding", encoding);
        }
        return given().headers(signed).headers(signer.headersFor("POST", endpoint, signed, body))
                .contentType("application/x-amz-json-1.1").body(body).post(endpoint);
    }

    private void assertEmpty(String stream) {
        assertTrue(logs.getLogEvents(request -> request.logGroupName(group).logStreamName(stream)).events().isEmpty());
    }

    private static byte[] gzip(byte[] plain) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (GZIPOutputStream compressed = new GZIPOutputStream(result)) {
            compressed.write(plain);
        }
        return result.toByteArray();
    }
}
