package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsCanonicalUserId;
import io.github.hectorvent.floci.services.s3.S3ServerAccessLoggingSdkTest.Held;
import io.github.hectorvent.floci.services.s3.S3ServerAccessLoggingSdkTest.LogRow;
import io.github.hectorvent.floci.services.s3.S3ServerAccessLoggingSdkTest.Owned;
import io.github.hectorvent.floci.testing.S3EnforceAuthProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Actual signed/error responses under the existing S3 authentication and policy enforcement profile. */
@QuarkusTest
@TestProfile(S3EnforceAuthProfile.class)
@Timeout(90)
class S3ServerAccessLoggingAuthSdkTest {

    private static final String ACCOUNT = "000000000000";

    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @ValueSource(ints = {403, 404})
    void authenticatedErrorsRetainTheVerifiedRequesterAndSignatureFields(int status) {
        try (Owned owned = owned("verified-error-" + status)) {
            Held source = owned.bucket("source", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            Held target = owned.bucket("target", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            owned.put(source, "object");
            owned.allow(target, source, false);
            owned.enable(source, target, "logs/", "SimplePrefix");
            if (status == 403) {
                owned.s3.putBucketPolicy(b -> b.bucket(source.name).policy("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":"*",
                         "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/object"}]}
                        """.formatted(source.name)));
            }
            S3Exception failure = assertThrows(S3Exception.class, () -> owned.s3.getObjectAsBytes(b ->
                    b.bucket(source.name).key(status == 404 ? "missing" : "object")));
            String code = status == 404 ? "NoSuchKey" : "AccessDenied";
            assertEquals(status, failure.statusCode());
            assertEquals(code, failure.awsErrorDetails().errorCode());
            LogRow row = owned.awaitRows(target, "logs/", Set.of(requestId(failure))).get(requestId(failure));
            assertEquals(Integer.toString(status), row.field(10));
            assertEquals(code, row.field(11));
            assertEquals(AwsCanonicalUserId.forAccount(ACCOUNT), row.field(5));
            assertEquals("SigV4", row.field(20));
            assertEquals("AuthHeader", row.field(22));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"forged", "anonymous"})
    void authenticationFailuresAndAnonymousRequestsDoNotClaimARequester(String kind) {
        try (Owned owned = owned("unverified-" + kind)) {
            Held source = owned.bucket("source", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            Held target = owned.bucket("target", owned.s3, ACCOUNT, Region.US_EAST_1, false, false);
            owned.put(source, "object");
            owned.allow(target, source, false);
            owned.enable(source, target, "logs/", "SimplePrefix");
            S3Client client = owned.client(ACCOUNT, Region.US_EAST_1, null, "anonymous".equals(kind),
                    AwsBasicCredentials.create("test", "incorrect-secret"));
            S3Exception failure = assertThrows(S3Exception.class, () ->
                    client.getObjectAsBytes(b -> b.bucket(source.name).key("object")));
            String code = "forged".equals(kind) ? "SignatureDoesNotMatch" : "AccessDenied";
            assertEquals(403, failure.statusCode());
            assertEquals(code, failure.awsErrorDetails().errorCode());
            LogRow row = owned.awaitRows(target, "logs/", Set.of(requestId(failure))).get(requestId(failure));
            assertEquals("403", row.field(10));
            assertEquals(code, row.field(11));
            assertEquals("-", row.field(5));
            assertEquals("-", row.field(20));
            assertEquals("-", row.field(22));
            assertEquals("payload", owned.s3.getObjectAsBytes(b -> b.bucket(source.name).key("object")).asUtf8String());
        }
    }

    private Owned owned(String caseName) {
        S3ServerAccessLoggingSdkTest fixture = new S3ServerAccessLoggingSdkTest();
        fixture.endpoint = endpoint;
        return fixture.new Owned(caseName, AwsBasicCredentials.create("test", "test"));
    }

    private static String requestId(S3Exception failure) {
        return failure.awsErrorDetails().sdkHttpResponse().firstMatchingHeader("x-amz-request-id").orElseThrow();
    }
}
