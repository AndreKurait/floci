package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class Ec2IamErrorIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void sdkParsesDenialAndAllowsTheSameUserAfterPermissionIsGranted() {
        String name = "ec2-denial-" + UUID.randomUUID();
        UserCredentials credentials = user(name);
        try (Ec2Client client = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        credentials.accessKeyId(), credentials.secretAccessKey()))).build()) {
            Ec2Exception denied = assertThrows(Ec2Exception.class, client::describeVpcs);
            assertEquals(403, denied.statusCode());
            assertEquals("UnauthorizedOperation", denied.awsErrorDetails().errorCode());
            assertNotNull(denied.requestId());
            EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth("000000000000", "iam"), name,
                    "describe", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"ec2:DescribeVpcs","Resource":"*"}
                    ]}
                    """);
            assertNotNull(client.describeVpcs().vpcs());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void signedAndPresignedGetDenialsUseEc2Envelope(boolean presigned) throws Exception {
        UserCredentials credentials = user("ec2-get-denial-" + UUID.randomUUID());
        AwsRequestSigner signer = AwsRequestSigner.signedAs(
                credentials.accessKeyId(), credentials.secretAccessKey(), "ec2");
        Map<String, String> query = Map.of("Action", "DescribeVpcs", "Version", "2016-11-15");
        if (presigned) {
            given().urlEncodingEnabled(false)
                    .get(signer.presignedUri("GET", endpoint, query, new byte[0])).then()
                    .statusCode(403).body("Response.Errors.Error.Code", equalTo("UnauthorizedOperation"))
                    .body("Response.RequestID", not(emptyOrNullString()));
        } else {
            given().filter(signer).queryParams(query).get(endpoint).then()
                    .statusCode(403).body("Response.Errors.Error.Code", equalTo("UnauthorizedOperation"))
                    .body("Response.RequestID", not(emptyOrNullString()));
        }
    }

    private UserCredentials user(String name) {
        return EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth("000000000000", "iam"), name);
    }

    @Test
    void sdkParsesAnUnknownAccessKeyWithIamEnforcement() {
        try (Ec2Client client = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        "AKIAUNKNOWNACCESSKEY", "some-secret"))).build()) {
            Ec2Exception denied = assertThrows(Ec2Exception.class, client::describeVpcs);
            assertEquals(403, denied.statusCode());
            assertEquals("AuthFailure", denied.awsErrorDetails().errorCode());
            assertNotNull(denied.requestId());
        }
    }
}
