package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class DynamoDbGzipIntegrationTest {

    private static final String JSON_1_0 = "application/x-amz-json-1.0";
    private static final AwsRequestSigner ROOT = AwsRequestSigner.signedAs("test", "test", "dynamodb");

    @TestHTTPResource("/")
    URI endpoint;

    @BeforeAll
    static void configureAwsContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void plainAndCompressedRequestsShareTheTableLifecycle(boolean compressed) throws Exception {
        String table = tableName();
        call(ROOT, "CreateTable", createBody(table), compressed).then().statusCode(200);
        try {
            call(ROOT, "PutItem", putBody(table, "tenant", "7"), compressed).then().statusCode(200);
            call(ROOT, "GetItem", getBody(table, "tenant"), compressed).then().statusCode(200)
                    .body("Item.value.N", equalTo("7"));
            call(ROOT, "Query", queryBody(table, "tenant"), compressed).then().statusCode(200)
                    .body("Count", equalTo(1)).body("Items[0].value.N", equalTo("7"));
            call(ROOT, "UpdateItem", """
                    {"TableName":"%s","Key":{"pk":{"S":"tenant"}},"UpdateExpression":"SET #v = :v",
                     "ExpressionAttributeNames":{"#v":"value"},
                     "ExpressionAttributeValues":{":v":{"N":"8"}},"ReturnValues":"ALL_NEW"}
                    """.formatted(table), compressed).then().statusCode(200)
                    .body("Attributes.value.N", equalTo("8"));
            call(ROOT, "DeleteItem", """
                    {"TableName":"%s","Key":{"pk":{"S":"tenant"}},"ReturnValues":"ALL_OLD"}
                    """.formatted(table), compressed).then().statusCode(200)
                    .body("Attributes.value.N", equalTo("8"));
            call(ROOT, "GetItem", getBody(table, "tenant"), compressed).then().statusCode(200)
                    .body("Item", nullValue());
            call(ROOT, "PutItem", """
                    {"TableName":"%s","Item":{"value":{"N":"7"}}}
                    """.formatted(table), compressed).then().statusCode(400)
                    .body("__type", equalTo("ValidationException"));
        } finally {
            call(ROOT, "DeleteTable", "{\"TableName\":\"" + table + "\"}", compressed)
                    .then().statusCode(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "GZIP", "gzip, identity", "gzip, gzip", "gzip, br"})
    void recognizedCodingsReachTheModernAndLegacyHandlers(String encoding) throws Exception {
        request(ROOT, "DynamoDB_20120810.ListTables",
                gzip("{\"Limit\":1}".getBytes(StandardCharsets.UTF_8)), encoding).then().statusCode(200);
        String table = tableName();
        request(ROOT, "DynamoDB_20111205.DescribeTable",
                gzip(("{\"TableName\":\"" + table + "\"}").getBytes(StandardCharsets.UTF_8)), encoding)
                .then().statusCode(400)
                .body("__type", equalTo("com.amazonaws.dynamodb.v20111205#ResourceNotFoundException"));
    }

    @Test
    void aLaterGzipCodingDoesNotTriggerInflation() throws Exception {
        request(ROOT, "DynamoDB_20120810.ListTables",
                gzip("{\"Limit\":1}".getBytes(StandardCharsets.UTF_8)), "identity, gzip")
                .then().statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @ParameterizedTest
    @ValueSource(ints = {10 * 1024 * 1024 + 1, 16_000_000})
    void decodedRequestsWithinTheVerifiedDynamoDbLimitAreAccepted(int size) throws Exception {
        request(ROOT, "DynamoDB_20120810.ListTables", gzip(padded("{\"Limit\":1}", size)), "gzip")
                .then().statusCode(200);
    }

    @Test
    void anOversizeCompressedCreateDoesNotLeaveATableBehind() throws Exception {
        String table = tableName();
        request(ROOT, "DynamoDB_20120810.CreateTable", gzip(padded(createBody(table), 16_000_001)), "gzip")
                .then().statusCode(413).body("__type", equalTo("RequestEntityTooLargeException"));
        call(ROOT, "DescribeTable", "{\"TableName\":\"" + table + "\"}", false).then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-header", "truncated", "invalid-crc"})
    void malformedCompressedCreatesCannotAllocateATable(String corruption) throws Exception {
        String table = tableName();
        byte[] body = gzip(createBody(table).getBytes(StandardCharsets.UTF_8));
        switch (corruption) {
            case "invalid-header" -> body[0] = 0;
            case "truncated" -> body = Arrays.copyOf(body, body.length - 5);
            case "invalid-crc" -> body[body.length - 8] ^= 1;
            default -> throw new IllegalArgumentException(corruption);
        }
        request(ROOT, "DynamoDB_20120810.CreateTable", body, "gzip").then().statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
        call(ROOT, "DescribeTable", "{\"TableName\":\"" + table + "\"}", false).then().statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void wireAuthenticationRejectsMalformedCompressedBytesBeforeInflation() throws Exception {
        request(ROOT.withSecret("wrong-secret"), "DynamoDB_20120810.CreateTable", new byte[]{1, 2, 3}, "gzip")
                .then().statusCode(403).body("__type", equalTo("InvalidSignatureException"));
    }

    @Test
    void decodedInvalidJsonAndMissingFieldsKeepTheirExistingErrors() throws Exception {
        call(ROOT, "ListTables", "{", true).then().statusCode(400)
                .body("__type", equalTo("SerializationException"));
        call(ROOT, "DescribeTable", "{}", true).then().statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void streamsRetainsItsExistingCompressedBodyHandling() throws Exception {
        request(ROOT, "DynamoDBStreams_20120810.ListStreams",
                gzip("{\"Limit\":1}".getBytes(StandardCharsets.UTF_8)), "gzip")
                .then().statusCode(400).body("__type", equalTo("SerializationException"));
    }

    @Test
    void iamSeesTheDecodedTableAndLeadingKeysAndTheHandlerReadsTheSameBody() throws Exception {
        String table = tableName();
        String user = "gzip-" + UUID.randomUUID();
        String key = null;
        call(ROOT, "CreateTable", createBody(table), false).then().statusCode(200);
        iam("CreateUser", Map.of("UserName", user)).then().statusCode(200);
        try {
            Response credentials = iam("CreateAccessKey", Map.of("UserName", user));
            credentials.then().statusCode(200);
            key = credentials.xmlPath().getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
            String secret = credentials.xmlPath()
                    .getString("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.SecretAccessKey");
            iam("PutUserPolicy", Map.of("UserName", user, "PolicyName", "one-partition",
                    "PolicyDocument", """
                            {"Version":"2012-10-17","Statement":[{
                              "Effect":"Allow","Action":["dynamodb:GetItem","dynamodb:PutItem","dynamodb:Query"],
                              "Resource":"arn:aws:dynamodb:us-east-1:000000000000:table/%s",
                              "Condition":{"ForAllValues:StringEquals":{"dynamodb:LeadingKeys":["tenant"]},
                                           "Null":{"dynamodb:LeadingKeys":"false"}}
                            }]}
                            """.formatted(table))).then().statusCode(200);
            AwsRequestSigner signer = AwsRequestSigner.signedAs(key, secret, "dynamodb");
            call(signer, "PutItem", putBody(table, "tenant", "9"), true).then().statusCode(200);
            call(signer, "GetItem", getBody(table, "tenant"), true).then().statusCode(200)
                    .body("Item.value.N", equalTo("9"));
            call(signer, "Query", queryBody(table, "tenant"), true).then().statusCode(200)
                    .body("Count", equalTo(1)).body("Items[0].value.N", equalTo("9"));
            call(signer, "PutItem", putBody(table, "other", "7"), true).then().statusCode(400)
                    .body("__type", equalTo("AccessDeniedException"));
            call(signer, "GetItem", getBody(table + "-other", "tenant"), true).then().statusCode(400)
                    .body("__type", equalTo("AccessDeniedException"));
            call(ROOT, "GetItem", getBody(table, "other"), false).then().statusCode(200)
                    .body("Item", nullValue());
            call(ROOT, "GetItem", getBody(table, "tenant"), false).then().statusCode(200)
                    .body("Item.value.N", equalTo("9"));
        } finally {
            if (key != null) {
                iam("DeleteAccessKey", Map.of("UserName", user, "AccessKeyId", key)).then().statusCode(200);
            }
            iam("DeleteUserPolicy", Map.of("UserName", user, "PolicyName", "one-partition"));
            iam("DeleteUser", Map.of("UserName", user)).then().statusCode(200);
            call(ROOT, "DeleteTable", "{\"TableName\":\"" + table + "\"}", false).then().statusCode(200);
        }
    }

    private Response call(AwsRequestSigner signer, String operation, String body, boolean compressed)
            throws Exception {
        byte[] plain = body.getBytes(StandardCharsets.UTF_8);
        return request(signer, "DynamoDB_20120810." + operation, compressed ? gzip(plain) : plain,
                compressed ? "gzip" : null);
    }

    private Response request(AwsRequestSigner signer, String target, byte[] body, String encoding) throws Exception {
        Map<String, String> signed = new HashMap<>();
        signed.put("x-amz-target", target);
        if (encoding != null) {
            signed.put("content-encoding", encoding);
        }
        return given().headers(signed).headers(signer.headersFor("POST", endpoint, signed, body))
                .contentType(JSON_1_0).body(body).post(endpoint);
    }

    private Response iam(String action, Map<String, String> parameters) throws Exception {
        String body = "Action=" + action + "&Version=2010-05-08&" + parameters.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return given().headers(AwsRequestSigner.signedAs("test", "test", "iam")
                        .headersFor("POST", endpoint, Map.of(), body.getBytes(StandardCharsets.UTF_8)))
                .contentType("application/x-www-form-urlencoded").body(body).post(endpoint);
    }

    private static String tableName() {
        return "gzip-" + UUID.randomUUID();
    }

    private static String createBody(String table) {
        return """
                {"TableName":"%s","KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}],
                 "BillingMode":"PAY_PER_REQUEST"}
                """.formatted(table);
    }

    private static String putBody(String table, String key, String value) {
        return """
                {"TableName":"%s","Item":{"pk":{"S":"%s"},"value":{"N":"%s"}}}
                """.formatted(table, key, value);
    }

    private static String getBody(String table, String key) {
        return """
                {"TableName":"%s","Key":{"pk":{"S":"%s"}},"ConsistentRead":true}
                """.formatted(table, key);
    }

    private static String queryBody(String table, String key) {
        return """
                {"TableName":"%s","KeyConditionExpression":"pk = :pk",
                 "ExpressionAttributeValues":{":pk":{"S":"%s"}},"ConsistentRead":true}
                """.formatted(table, key);
    }

    private static byte[] padded(String json, int size) {
        byte[] plain = json.getBytes(StandardCharsets.UTF_8);
        byte[] padded = Arrays.copyOf(plain, size);
        Arrays.fill(padded, plain.length, padded.length, (byte) ' ');
        return padded;
    }

    private static byte[] gzip(byte[] plain) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        try (GZIPOutputStream compressed = new GZIPOutputStream(result)) {
            compressed.write(plain);
        }
        return result.toByteArray();
    }
}
