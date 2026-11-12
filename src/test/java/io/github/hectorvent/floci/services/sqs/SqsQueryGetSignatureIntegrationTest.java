package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.github.hectorvent.floci.testutil.S3RequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class SqsQueryGetSignatureIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @CsvSource({
        "us-east-1, aws, false, false",
        "us-east-1, aws, true, true",
        "us-gov-west-1, aws-us-gov, false, true",
        "us-gov-west-1, aws-us-gov, true, false"
    })
    void signedQueuePathGetsReachSqsAndRejectTamperedSignatures(
            String region, String partition, boolean presigned, boolean trailingSlash) throws Exception {
        String name = "query-get-" + UUID.randomUUID();
        try (SqsClient sqs = client(region)) {
            String queueUrl = sqs.createQueue(r -> r.queueName(name)).queueUrl();
            String path = URI.create(queueUrl).getPath() + (trailingSlash ? "/" : "");
            AwsRequestSigner signer = AwsRequestSigner.signedAs("test", "test", "sqs").inRegion(region);
            try {
                request(signer, path, attributes(), presigned).then().statusCode(200)
                        .body(containsString("arn:" + partition + ":sqs:" + region + ":000000000000:" + name));
                request(signer.withSecret("wrong-secret"), path, attributes(), presigned).then().statusCode(403)
                        .contentType(containsString("xml"))
                        .body("ErrorResponse.Error.Code", equalTo("SignatureDoesNotMatch"));
            } finally {
                sqs.deleteQueue(r -> r.queueUrl(queueUrl));
            }
            request(signer, path, attributes(), presigned).then().statusCode(400)
                    .body("ErrorResponse.Error.Code", equalTo("AWS.SimpleQueueService.NonExistentQueue"));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queryQueueUrlCannotOverridePathOrItsIamResource(boolean presigned) throws Exception {
        String region = "us-west-2";
        String allowed = "query-allowed-" + UUID.randomUUID();
        String denied = "query-denied-" + UUID.randomUUID();
        try (SqsClient sqs = client(region)) {
            String allowedUrl = sqs.createQueue(r -> r.queueName(allowed)).queueUrl();
            String deniedUrl = sqs.createQueue(r -> r.queueName(denied)).queueUrl();
            try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "sqs-query-", "queue-read", """
                    {"Version":"2012-10-17","Statement":[{
                      "Effect":"Allow","Action":"sqs:GetQueueAttributes",
                      "Resource":"arn:aws:sqs:us-west-2:000000000000:%s",
                      "Condition":{"StringEquals":{"aws:RequestedRegion":"us-west-2"}}
                    }]}
                    """.formatted(allowed))) {
                AwsRequestSigner signer = user.signer("sqs").inRegion(region);
                request(signer, URI.create(allowedUrl).getPath(), attributes(), presigned).then().statusCode(200)
                        .body(containsString(allowed));
                Map<String, String> misleading = Map.of("Action", "GetQueueAttributes", "Version", "2012-11-05",
                        "AttributeName.1", "QueueArn", "QueueUrl", allowedUrl);
                request(signer, URI.create(deniedUrl).getPath(), misleading, presigned).then().statusCode(403)
                        .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
                request(signer.inRegion("us-east-1"), URI.create(allowedUrl).getPath(), attributes(), presigned)
                        .then().statusCode(403).body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
            } finally {
                sqs.deleteQueue(r -> r.queueUrl(allowedUrl));
                sqs.deleteQueue(r -> r.queueUrl(deniedUrl));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void prefixedQueryPathIsRejectedAfterSignatureVerification(boolean presigned) throws Exception {
        String path = "/queue/us-east-1/000000000000/not-a-queue";
        AwsRequestSigner signer = AwsRequestSigner.signedAs("test", "test", "sqs");
        request(signer, path, attributes(), presigned).then().statusCode(404)
                .body("ErrorResponse.Error.Code", equalTo("InvalidAddress"));
        request(signer.withSecret("wrong-secret"), path, attributes(), presigned).then().statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("SignatureDoesNotMatch"));
    }

    @Test
    void sqsActionOnAnS3SignedQueueLikeObjectPathStaysWithS3() throws Exception {
        String bucket = "123456789012";
        String path = "/" + bucket + "/query-get-object-" + UUID.randomUUID();
        given().filter(S3RequestSigner.signedAs("test", "test")).put("/" + bucket).then().statusCode(200);
        try {
            given().filter(S3RequestSigner.signedAs("test", "test")).body("s3-object").put(path)
                    .then().statusCode(200);
            given().filter(S3RequestSigner.signedAs("test", "test")).queryParam("Action", "GetQueueAttributes")
                    .get(path).then().statusCode(200).body(equalTo("s3-object"));
        } finally {
            given().filter(S3RequestSigner.signedAs("test", "test")).delete(path).then().statusCode(204);
            given().filter(S3RequestSigner.signedAs("test", "test")).delete("/" + bucket).then().statusCode(204);
        }
    }

    private SqsClient client(String region) {
        return SqsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static Map<String, String> attributes() {
        return Map.of("Action", "GetQueueAttributes", "Version", "2012-11-05", "AttributeName.1", "QueueArn");
    }

    private Response request(AwsRequestSigner signer, String path, Map<String, String> query, boolean presigned)
            throws Exception {
        URI resource = endpoint.resolve(path);
        if (presigned) {
            return given().urlEncodingEnabled(false).get(signer.presignedUri("GET", resource, query, new byte[0]));
        }
        String encoded = query.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        URI uri = URI.create(resource + "?" + encoded);
        return given().urlEncodingEnabled(false).headers(signer.headersFor("GET", uri, Map.of(), new byte[0]))
                .get(uri);
    }
}
