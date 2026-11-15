package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;
import software.amazon.awssdk.services.cloudfront.model.GetOriginAccessControlConfigResponse;
import software.amazon.awssdk.services.cloudfront.model.OriginAccessControlConfig;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudFrontOriginAccessControlIamIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void nativeSdkGrantUpdatesOnlyTheControlNamedByItsResourceArn() {
        String user = "oac-scoped-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), user);
        try (CloudFrontClient admin = client(ACCOUNT, "test");
             CloudFrontClient restricted = client(credentials.accessKeyId(), credentials.secretAccessKey())) {
            String allowed = create(admin);
            String other = create(admin);
            try {
                String allowedArn = "arn:aws:cloudfront::" + ACCOUNT + ":origin-access-control/" + allowed;
                EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), user, "update", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                          "Action":"cloudfront:UpdateOriginAccessControl","Resource":"%s"}]}
                        """.formatted(allowedArn));

                GetOriginAccessControlConfigResponse before =
                        admin.getOriginAccessControlConfig(request -> request.id(allowed));
                OriginAccessControlConfig changed = before.originAccessControlConfig().toBuilder()
                        .description("scoped SDK update").build();
                restricted.updateOriginAccessControl(request -> request.id(allowed)
                        .ifMatch(before.eTag()).originAccessControlConfig(changed));
                assertEquals(changed, admin.getOriginAccessControlConfig(request -> request.id(allowed))
                        .originAccessControlConfig());

                GetOriginAccessControlConfigResponse untouched =
                        admin.getOriginAccessControlConfig(request -> request.id(other));
                CloudFrontException failure = assertThrows(CloudFrontException.class,
                        () -> restricted.updateOriginAccessControl(request -> request.id(other)
                                .ifMatch(untouched.eTag()).originAccessControlConfig(
                                        untouched.originAccessControlConfig().toBuilder()
                                                .description("must be denied").build())));
                assertEquals(403, failure.statusCode());
                assertEquals("AccessDenied", failure.awsErrorDetails().errorCode());
                assertEquals(untouched.originAccessControlConfig(),
                        admin.getOriginAccessControlConfig(request -> request.id(other)).originAccessControlConfig());
            } finally {
                delete(admin, other);
                delete(admin, allowed);
            }
        }
    }

    @Test
    void encodedIdAndMisleadingActionHintsCannotChangeTheMatchedUpdatePermission() {
        String user = "oac-hints-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), user);
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), user, "read", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Action":"cloudfront:GetOriginAccessControl","Resource":"*"}]}
                """);
        try (CloudFrontClient admin = client(ACCOUNT, "test")) {
            String id = create(admin);
            try {
                GetOriginAccessControlConfigResponse before =
                        admin.getOriginAccessControlConfig(request -> request.id(id));
                String encodedId = "%%%02X".formatted((int) id.charAt(0)) + id.substring(1);
                given().urlEncodingEnabled(false).port(endpoint.getPort())
                        .header("Authorization", auth(credentials.accessKeyId(), "cloudfront"))
                        .header("X-Amz-Target", "CloudFront.GetOriginAccessControl")
                        .header("If-Match", before.eTag())
                        .contentType("application/xml").body("""
                                <OriginAccessControlConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                                  <Name>%s</Name><Description>must be denied</Description>
                                  <SigningProtocol>sigv4</SigningProtocol><SigningBehavior>always</SigningBehavior>
                                  <OriginAccessControlOriginType>s3</OriginAccessControlOriginType>
                                </OriginAccessControlConfig>
                                """.formatted(before.originAccessControlConfig().name()))
                        .when().put("/2020-05-31/origin-access-control/" + encodedId
                                + "/config?Action=GetOriginAccessControl")
                        .then().statusCode(403).body(containsString("cloudfront:UpdateOriginAccessControl"));
                assertEquals(before.originAccessControlConfig(),
                        admin.getOriginAccessControlConfig(request -> request.id(id)).originAccessControlConfig());
            } finally {
                delete(admin, id);
            }
        }
    }

    private CloudFrontClient client(String accessKey, String secretKey) {
        return CloudFrontClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey))).build();
    }

    private static String create(CloudFrontClient client) {
        return client.createOriginAccessControl(request -> request.originAccessControlConfig(config -> config
                        .name("oac-iam-" + UUID.randomUUID()).description("owned IAM control")
                        .signingProtocol("sigv4").signingBehavior("always").originAccessControlOriginType("s3")))
                .originAccessControl().id();
    }

    private static void delete(CloudFrontClient client, String id) {
        String etag = client.getOriginAccessControlConfig(request -> request.id(id)).eTag();
        client.deleteOriginAccessControl(request -> request.id(id).ifMatch(etag));
    }
}
