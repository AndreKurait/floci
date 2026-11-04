package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.testing.ValidateSignaturesProfile;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Ec2Exception;

import java.net.URI;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
class Ec2SignatureErrorIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;

    @Test
    void sdkParsesAnUnknownAccessKeyWithSignatureValidation() {
        try (Ec2Client client = Ec2Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        "AKIAUNKNOWNACCESSKEY", "some-secret"))).build()) {
            Ec2Exception denied = assertThrows(Ec2Exception.class, client::describeVpcs);
            assertEquals(403, denied.statusCode());
            assertEquals("AuthFailure", denied.awsErrorDetails().errorCode());
            assertNotNull(denied.requestId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownKeysOnSignedAndPresignedGetUseEc2Envelope(boolean presigned) throws Exception {
        AwsRequestSigner signer = AwsRequestSigner.signedAs("AKIAUNKNOWNACCESSKEY", "some-secret", "ec2");
        Map<String, String> query = Map.of("Action", "DescribeVpcs", "Version", "2016-11-15");
        if (presigned) {
            given().urlEncodingEnabled(false)
                    .get(signer.presignedUri("GET", endpoint, query, new byte[0])).then()
                    .statusCode(403).body("Response.Errors.Error.Code", equalTo("AuthFailure"))
                    .body("Response.RequestID", not(emptyOrNullString()));
        } else {
            given().filter(signer).queryParams(query).get(endpoint).then()
                    .statusCode(403).body("Response.Errors.Error.Code", equalTo("AuthFailure"))
                    .body("Response.RequestID", not(emptyOrNullString()));
        }
    }

    @Test
    void anEc2SigningScopeDoesNotChangeRestJsonErrors() {
        given().filter(AwsRequestSigner.signedAs("AKIAUNKNOWNACCESSKEY", "some-secret", "ec2"))
                .get("/2015-03-31/functions").then()
                .statusCode(403).contentType("application/json")
                .body("__type", equalTo("UnrecognizedClientException"));
    }
}
