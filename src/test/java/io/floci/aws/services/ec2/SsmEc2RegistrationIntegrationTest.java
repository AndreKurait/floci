package io.floci.aws.services.ec2;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.Digests;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ec2.model.InstanceState;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.SessionCredential;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.EncoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.parsing.Parser;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.AwsS3V4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.auth.signer.params.AwsS3V4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SsmEc2RegistrationIntegrationTest.IdentityProfile.class)
class SsmEc2RegistrationIntegrationTest {
    private static final String ACCOUNT = "246813579012";
    private static final String REGION = "us-east-1";
    @Inject IamService iam;
    @Inject Ec2Service ec2;
    @Inject ObjectMapper mapper;
    @Inject Clock clock;
    @TestHTTPResource URI endpoint;
    private Instance instance;
    private Ec2InstanceCredentials issuer;
    private SessionCredential credential;
    private String publicKey;

    public static class IdentityProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true",
                    "floci.auth.validate-signatures", "false",
                    "floci.services.iam.lambda-identity-verification-enabled", "false");
        }
    }

    @BeforeEach
    void launchAndIssueIdentity() throws Exception {
        String id = given().header("Authorization", rootAuthorization())
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "RunInstances").formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", "1").formParam("MaxCount", "1")
                .post("/").then().statusCode(200).extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        instance = ec2.findInstanceForAccount(ACCOUNT, REGION, id).orElseThrow();
        instance.setState(InstanceState.running());
        issuer = new Ec2InstanceCredentials(iam);
        issuer.register(instance);
        credential = issuer.identity(instance, ACCOUNT, Instant.now()).orElseThrow();
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        publicKey = Base64.getEncoder().encodeToString(keys.generateKeyPair().getPublic().getEncoded());
    }

    @AfterEach
    void cleanup() {
        if (issuer != null) {
            issuer.clear();
        }
        if (instance != null) {
            given().header("Authorization", rootAuthorization())
                    .contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "TerminateInstances").formParam("InstanceId.1", instance.getInstanceId())
                    .post("/").then().statusCode(200);
        }
    }

    @Test
    void anInstanceCanRegisterItsOwnPublicKeyWithoutAcquiringGeneralSsmPermissions() throws Exception {
        String body = registration(instance.getInstanceId(), publicKey);
        signed("RegisterManagedInstance", REGION, body, body, false).then().defaultParser(Parser.JSON).statusCode(200)
                .body("InstanceId", equalTo(instance.getInstanceId()));
        signed("RegisterManagedInstance", REGION, body, body, false).then().defaultParser(Parser.JSON).statusCode(200)
                .body("InstanceId", equalTo(instance.getInstanceId()));
        Response unrelated = signed("GetParameter", REGION, "{\"Name\":\"not-granted\"}",
                "{\"Name\":\"not-granted\"}", false);
        assertTrue(unrelated.statusCode() >= 400);
        assertTrue(unrelated.asString().contains("AccessDenied"));
        List<String> policies = iam.resolveCallerContext(credential.getAccessKeyId()).identityPolicies();
        assertTrue(policies == null || policies.isEmpty());
    }

    @Test
    void stockAgentRsaKeyTypeRegistersWithoutRelaxingKeyValidation() throws Exception {
        String body = mapper.writeValueAsString(Map.of("Fingerprint", instance.getInstanceId(),
                "PublicKey", publicKey, "PublicKeyType", "Rsa"));
        signed("RegisterManagedInstance", REGION, body, body, false).then().defaultParser(Parser.JSON)
                .statusCode(200).body("InstanceId", equalTo(instance.getInstanceId()));
        String invalid = mapper.writeValueAsString(Map.of("Fingerprint", instance.getInstanceId(),
                "PublicKey", "not-a-public-key", "PublicKeyType", "Rsa"));
        signed("RegisterManagedInstance", REGION, invalid, invalid, false).then().defaultParser(Parser.JSON)
                .statusCode(400).body("__type", equalTo("ValidationException"));
    }

    @Test
    void lowercasePublicKeyTypeRemainsCompatible() throws Exception {
        String body = mapper.writeValueAsString(Map.of("Fingerprint", instance.getInstanceId(),
                "PublicKey", publicKey, "PublicKeyType", "rsa"));
        signed("RegisterManagedInstance", REGION, body, body, false).then().defaultParser(Parser.JSON)
                .statusCode(200).body("InstanceId", equalTo(instance.getInstanceId()));
    }

    @Test
    void officialKeyTypeStillRejectsWeakAndNonRsaPublicKeys() throws Exception {
        for (String algorithm : List.of("RSA", "EC")) {
            KeyPairGenerator keys = KeyPairGenerator.getInstance(algorithm);
            keys.initialize("RSA".equals(algorithm) ? 1024 : 256);
            String key = Base64.getEncoder().encodeToString(keys.generateKeyPair().getPublic().getEncoded());
            String body = registration(instance.getInstanceId(), key);
            signed("RegisterManagedInstance", REGION, body, body, false).then().defaultParser(Parser.JSON)
                    .statusCode(400).body("__type", equalTo("ValidationException"));
        }
    }

    @Test
    void registrationDoesNotReportTheAgentOnlineBeforeItsHeartbeat() throws Exception {
        String body = registration(instance.getInstanceId(), publicKey);
        signed("RegisterManagedInstance", REGION, body, body, false).then().statusCode(200);
        given().header("Authorization", rootAuthorization().replace("/ec2/", "/ssm/"))
                .header("X-Amz-Target", "AmazonSSM.DescribeInstanceInformation")
                .contentType("application/x-amz-json-1.1")
                .body("{}".getBytes(StandardCharsets.UTF_8))
                .post("/").then().defaultParser(Parser.JSON).statusCode(200)
                .body("InstanceInformationList.size()", equalTo(0));
    }

    @Test
    void registrationRejectsUnsignedForgedAndModifiedRequestsWithGlobalSignatureCheckingOff() throws Exception {
        String body = registration(instance.getInstanceId(), publicKey);
        assertTrue(given().header("X-Amz-Target", "AmazonSSM.RegisterManagedInstance")
                .contentType("application/x-amz-json-1.1").body(body.getBytes(StandardCharsets.UTF_8))
                .post("/").statusCode() >= 400);
        signed("RegisterManagedInstance", REGION, body, body, true).then().defaultParser(Parser.JSON)
                .statusCode(403).body("__type", equalTo("InvalidSignatureException"));
        signed("RegisterManagedInstance", REGION, body, body + " ", false).then().defaultParser(Parser.JSON)
                .statusCode(403).body("__type", equalTo("InvalidSignatureException"));
    }

    @Test
    void registrationRejectsAnUnsignedPayloadEvenWithAValidSignature() throws Exception {
        String body = registration(instance.getInstanceId(), publicKey);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        URI signingEndpoint = new URI("https", null, endpoint.getHost(), endpoint.getPort(), "/", null, null);
        SdkHttpFullRequest request = SdkHttpFullRequest.builder().uri(signingEndpoint).method(SdkHttpMethod.POST)
                .putHeader("X-Amz-Target", "AmazonSSM.RegisterManagedInstance")
                .putHeader("Content-Type", "application/x-amz-json-1.1")
                .contentStreamProvider(() -> new ByteArrayInputStream(bytes)).build();
        SdkHttpFullRequest signed = AwsS3V4Signer.create().sign(request, AwsS3V4SignerParams.builder()
                .awsCredentials(AwsSessionCredentials.create(credential.getAccessKeyId(),
                        credential.getSecretAccessKey(), credential.getSessionToken()))
                .signingName("ssm").signingRegion(Region.of(REGION)).enablePayloadSigning(false)
                .enableChunkedEncoding(false).build());
        assertEquals("UNSIGNED-PAYLOAD", signed.firstMatchingHeader("x-amz-content-sha256").orElseThrow());
        RequestSpecification client = given().config(RestAssuredConfig.config().encoderConfig(
                EncoderConfig.encoderConfig().appendDefaultContentCharsetToContentTypeIfUndefined(false)));
        signed.headers().forEach((name, values) -> values.forEach(value -> client.header(name, value)));
        client.body(bytes).post("/").then().defaultParser(Parser.JSON).statusCode(403)
                .body("__type", equalTo("InvalidSignatureException"));
        signed("RegisterManagedInstance", REGION, body, body, false).then().statusCode(200);
    }

    @Test
    void registrationRequiresAnyDeclaredPayloadDigestToMatchTheBody() throws Exception {
        String body = registration(instance.getInstanceId(), publicKey);
        signedWithHash(body, Digests.sha256Hex(body.getBytes(StandardCharsets.UTF_8))).then().statusCode(200);
        signedWithHash(body, "0".repeat(64)).then().defaultParser(Parser.JSON).statusCode(403)
                .body("__type", equalTo("InvalidSignatureException"));
    }

    private Response signedWithHash(String body, String hash) {
        return signed("RegisterManagedInstance", REGION, body, body, false, hash);
    }

    @Test
    void identityCannotRegisterAnotherInstanceOrAnotherRegion() throws Exception {
        String other = registration("i-00000000000000001", publicKey);
        signed("RegisterManagedInstance", REGION, other, other, false).then().defaultParser(Parser.JSON).statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
        String own = registration(instance.getInstanceId(), publicKey);
        signed("RegisterManagedInstance", "us-west-2", own, own, false).then().defaultParser(Parser.JSON).statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void stoppedAndRevokedInstancesCannotRegister() throws Exception {
        String body = registration(instance.getInstanceId(), publicKey);
        instance.setState(InstanceState.stopped());
        signed("RegisterManagedInstance", REGION, body, body, false).then().statusCode(400);
        instance.setState(InstanceState.running());
        issuer.unregister(instance);
        assertTrue(signed("RegisterManagedInstance", REGION, body, body, false).statusCode() >= 400);
    }

    @Test
    void malformedPublicKeysAreRejectedAfterAuthentication() throws Exception {
        String body = registration(instance.getInstanceId(), "not-a-public-key");
        signed("RegisterManagedInstance", REGION, body, body, false).then().defaultParser(Parser.JSON).statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void expiredCredentialsCannotRegister() throws Exception {
        String body = registration(instance.getInstanceId(), publicKey);
        credential.setExpiration(clock.instant().minusSeconds(1));
        assertTrue(signed("RegisterManagedInstance", REGION, body, body, false).statusCode() >= 400);
    }

    @Test
    void restoredCredentialsCannotReestablishLiveInstanceIdentity() throws Exception {
        String body = registration(instance.getInstanceId(), publicKey);
        SessionCredential restored = mapper.readValue(mapper.writeValueAsBytes(credential), SessionCredential.class);
        assertNull(restored.getEc2InstanceArn());
        iam.registerEc2InstanceSession(restored);
        signed("RegisterManagedInstance", REGION, body, body, false).then().defaultParser(Parser.JSON).statusCode(403)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void legacyFieldNamesCannotReplaceTheAgentsAuthenticatedFingerprint() throws Exception {
        String legacy = mapper.writeValueAsString(
                Map.of("InstanceId", instance.getInstanceId(), "PublicKey", publicKey, "KeyType", "rsa"));
        signed("RegisterManagedInstance", REGION, legacy, legacy, false).then().defaultParser(Parser.JSON)
                .statusCode(400).body("__type", equalTo("AccessDeniedException"));
        String conflict = mapper.writeValueAsString(Map.of("Fingerprint", "i-00000000000000001",
                "InstanceId", instance.getInstanceId(), "PublicKey", publicKey, "PublicKeyType", "rsa"));
        signed("RegisterManagedInstance", REGION, conflict, conflict, false).then().defaultParser(Parser.JSON)
                .statusCode(400).body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void theAgentsPublicKeyTypeIsRequiredAndMustBeRsa() throws Exception {
        for (Map<String, String> fields : List.of(
                Map.of("Fingerprint", instance.getInstanceId(), "PublicKey", publicKey),
                Map.of("Fingerprint", instance.getInstanceId(), "PublicKey", publicKey, "KeyType", "rsa"),
                Map.of("Fingerprint", instance.getInstanceId(), "PublicKey", publicKey, "PublicKeyType", "ecc"),
                Map.of("Fingerprint", instance.getInstanceId(), "PublicKey", publicKey, "PublicKeyType", "RSA"),
                Map.of("Fingerprint", instance.getInstanceId(), "PublicKey", publicKey, "PublicKeyType", "rSa"))) {
            String body = mapper.writeValueAsString(fields);
            signed("RegisterManagedInstance", REGION, body, body, false).then().defaultParser(Parser.JSON)
                    .statusCode(400).body("__type", equalTo("ValidationException"));
        }
    }

    private String registration(String instanceId, String key) throws Exception {
        // amazon-ssm-agent 3.3.5700.0 sends Fingerprint and auth.KeyType = "Rsa" unchanged.
        // Its auth/rsa_key.go encodes the RSA public key as base64 X.509 SubjectPublicKeyInfo.
        return mapper.writeValueAsString(Map.of("Fingerprint", instanceId, "PublicKey", key, "PublicKeyType", "Rsa"));
    }

    private Response signed(String operation, String region, String signedBody, String sentBody, boolean forged) {
        return signed(operation, region, signedBody, sentBody, forged, null);
    }

    private Response signed(String operation, String region, String signedBody, String sentBody,
                            boolean forged, String hash) {
        byte[] bytes = signedBody.getBytes(StandardCharsets.UTF_8);
        SdkHttpFullRequest.Builder builder = SdkHttpFullRequest.builder().uri(endpoint).method(SdkHttpMethod.POST)
                .putHeader("X-Amz-Target", "AmazonSSM." + operation)
                .putHeader("Content-Type", "application/x-amz-json-1.1")
                .contentStreamProvider(() -> new ByteArrayInputStream(bytes));
        if (hash != null) {
            builder.putHeader("x-amz-content-sha256", hash);
        }
        SdkHttpFullRequest request = builder.build();
        SdkHttpFullRequest signed = Aws4Signer.create().sign(request, Aws4SignerParams.builder()
                .awsCredentials(AwsSessionCredentials.create(credential.getAccessKeyId(),
                        forged ? "wrong-secret" : credential.getSecretAccessKey(), credential.getSessionToken()))
                .signingName("ssm").signingRegion(Region.of(region)).build());
        RequestSpecification client = given().config(RestAssuredConfig.config().encoderConfig(
                EncoderConfig.encoderConfig().appendDefaultContentCharsetToContentTypeIfUndefined(false)));
        signed.headers().forEach((name, values) -> values.forEach(value -> client.header(name, value)));
        return client.body(sentBody.getBytes(StandardCharsets.UTF_8)).post("/");
    }

    private static String rootAuthorization() {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20261113/" + REGION
                + "/ec2/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
