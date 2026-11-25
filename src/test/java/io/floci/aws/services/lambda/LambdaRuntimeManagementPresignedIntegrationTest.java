package io.floci.aws.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.auth.SigV4RequestValidator;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvalidParameterValueException;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class LambdaRuntimeManagementPresignedIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;
    @Inject
    ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT"})
    void signedRegionSelectsTheSameFunctionForTagsAndExecution(String method) throws Exception {
        String user = "RuntimeRegion" + UUID.randomUUID().toString().substring(0, 10);
        iam.createUser(user, "/");
        AccessKey key = null;
        boolean policyCreated = false;
        try (LambdaRuntimeManagementIntegrationTest.Owned east =
                     new LambdaRuntimeManagementIntegrationTest.Owned(endpoint, "us-east-1");
             LambdaClient west = LambdaClient.builder().endpointOverride(endpoint).region(Region.US_WEST_2)
                     .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                     .build()) {
            key = iam.createAccessKey(user);
            String westArn = east.arn.replace(":us-east-1:", ":us-west-2:");
            String role = east.lambda.getFunction(r -> r.functionName(east.name)).configuration().role();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("index.js"));
                zip.write("exports.handler = async event => event;".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            try {
                west.createFunction(r -> r.functionName(east.name).role(role).runtime("nodejs20.x").handler("index.handler")
                        .code(c -> c.zipFile(SdkBytes.fromByteArray(bytes.toByteArray())))
                        .tags(Map.of("team", "allowed")));
                east.lambda.putRuntimeManagementConfig(r -> r.functionName(east.name).updateRuntimeOn("FunctionUpdate"));
                east.lambda.tagResource(r -> r.resource(east.arn).tags(Map.of("team", "blocked")));
                west.putRuntimeManagementConfig(r -> r.functionName(east.name).updateRuntimeOn("FunctionUpdate"));
                iam.putUserPolicy(user, "RuntimeSettings", mapper.writeValueAsString(Map.of("Version", "2012-10-17",
                        "Statement", List.of(Map.of("Effect", "Allow", "Action", "lambda:*RuntimeManagementConfig",
                                        "Resource", List.of(east.arn, westArn)),
                                Map.of("Effect", "Deny", "Action", "lambda:*RuntimeManagementConfig", "Resource", "*",
                                        "Condition", Map.of("StringEquals", Map.of("aws:ResourceTag/team", "blocked")))))));
                policyCreated = true;
                String body = "PUT".equals(method) ? "{\"UpdateRuntimeOn\":\"Auto\"}" : "";
                URI request = presign(key, method, east.name, body);
                given().urlEncodingEnabled(false).contentType("application/json").body(body).request(method, request)
                        .then().statusCode(200).body("FunctionArn", equalTo(westArn))
                        .body("UpdateRuntimeOn", equalTo("GET".equals(method) ? "FunctionUpdate" : "Auto"));
                assertEquals("FunctionUpdate", east.get(null).updateRuntimeOnAsString());
                assertEquals("GET".equals(method) ? "FunctionUpdate" : "Auto",
                        west.getRuntimeManagementConfig(r -> r.functionName(east.name)).updateRuntimeOnAsString());
                west.tagResource(r -> r.resource(westArn).tags(Map.of("team", "blocked")));
                given().urlEncodingEnabled(false).contentType("application/json").body(body).request(method, request)
                        .then().statusCode(403);
                assertEquals("FunctionUpdate", east.get(null).updateRuntimeOnAsString());
            } finally {
                try {
                    west.deleteFunction(r -> r.functionName(east.name));
                } catch (ResourceNotFoundException ignored) {
                    // Creation may have failed before storing the function.
                }
                assertThrows(ResourceNotFoundException.class, () -> west.getFunction(r -> r.functionName(east.name)));
            }
        } finally {
            if (key != null) {
                if (policyCreated) {
                    iam.deleteUserPolicy(user, "RuntimeSettings");
                }
                iam.deleteAccessKey(user, key.getAccessKeyId());
            }
            iam.deleteUser(user);
        }
    }

    @Test
    void malformedArnReturnsModeledSdkErrors() {
        try (LambdaClient lambda = LambdaClient.builder().endpointOverride(endpoint).region(Region.US_WEST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build()) {
            InvalidParameterValueException read = assertThrows(InvalidParameterValueException.class,
                    () -> lambda.getRuntimeManagementConfig(r -> r.functionName("arn:foo")));
            assertEquals(400, read.statusCode());
            InvalidParameterValueException write = assertThrows(InvalidParameterValueException.class,
                    () -> lambda.putRuntimeManagementConfig(r -> r.functionName("arn:foo").updateRuntimeOn("Auto")));
            assertEquals(400, write.statusCode());
        }
    }

    private URI presign(AccessKey key, String method, String name, String body) throws Exception {
        String path = "/2021-07-20/functions/" + name + "/runtime-management-config";
        String date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
        String scope = date.substring(0, 8) + "/us-west-2/lambda/aws4_request";
        TreeMap<String, String> query = new TreeMap<>(Map.of("X-Amz-Algorithm", "AWS4-HMAC-SHA256",
                "X-Amz-Credential", key.getAccessKeyId() + "/" + scope, "X-Amz-Date", date,
                "X-Amz-Expires", "60", "X-Amz-SignedHeaders", "host"));
        String encoded = query.entrySet().stream().map(e -> e.getKey() + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        String canonical = method + "\n" + path + "\n" + encoded + "\nhost:" + endpoint.getAuthority()
                + "\n\nhost\n" + Digests.sha256Hex(body.getBytes(StandardCharsets.UTF_8));
        String toSign = "AWS4-HMAC-SHA256\n" + date + "\n" + scope + "\n" + Digests.sha256Hex(canonical);
        String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(
                SigV4RequestValidator.deriveSigningKey(key.getSecretAccessKey(), date.substring(0, 8), "us-west-2", "lambda"), toSign));
        return endpoint.resolve(path + "?" + encoded + "&X-Amz-Signature=" + signature);
    }
}
