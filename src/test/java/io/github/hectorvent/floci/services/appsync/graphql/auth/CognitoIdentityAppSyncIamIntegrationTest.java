package io.github.hectorvent.floci.services.appsync.graphql.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct AppSync authentication/authorization checks using real IAM storage and SDK signatures.
 * This deliberately does not claim to exercise the GraphQL sidecar or Cognito token issuance.
 */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class CognitoIdentityAppSyncIamIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String API = "cognito-direct-context";
    private static final String PREFIX = "cognito-identity.amazonaws.com:";
    private static final String POOL = "us-east-1:11111111-1111-1111-1111-111111111111";
    private static final String BODY = "{\"query\":\"{ public secret }\"}";
    private static final String REQUEST_ARN = "arn:aws:appsync:" + REGION + ":" + ACCOUNT + ":apis/" + API + "/*";
    private static final String SECRET_ARN = IamAuthValidator.fieldArn(REGION, ACCOUNT, API, "Query", "secret");
    private static final String PUBLIC_ARN = IamAuthValidator.fieldArn(REGION, ACCOUNT, API, "Query", "public");

    @Inject IamService iamService;
    @Inject IamAuthValidator validator;
    @Inject ObjectMapper mapper;

    @Test
    void requestExplicitDenyUsesVerifiedAudienceAndAuthenticationMethod() throws Exception {
        RequestScopes.callAsChecked(ACCOUNT, REGION, () -> {
            exercise(REQUEST_ARN, true);
            return null;
        });
    }

    @Test
    void fieldExplicitDenySurvivesSuccessfulRequestAuthorization() throws Exception {
        RequestScopes.callAsChecked(ACCOUNT, REGION, () -> {
            exercise(SECRET_ARN, false);
            return null;
        });
    }

    private void exercise(String deniedResource, boolean denyRequest) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String roleName = "cognito-appsync-" + suffix;
        String userName = "ordinary-appsync-" + suffix;
        List<String> sessions = new ArrayList<>();
        String userKey = null;
        boolean roleCreated = false;
        boolean rolePolicyCreated = false;
        boolean userCreated = false;
        boolean userPolicyCreated = false;
        try {
            String trust = mapper.writeValueAsString(Map.of("Version", "2012-10-17", "Statement",
                    Map.of("Effect", "Allow", "Principal", Map.of("Service", "lambda.amazonaws.com"),
                            "Action", "sts:AssumeRole")));
            IamRole role = iamService.createRole(roleName, "/", trust, null, 3600, Map.of());
            roleCreated = true;
            String policy = mapper.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", List.of(
                    Map.of("Effect", "Allow", "Action", "appsync:GraphQL", "Resource", "*"),
                    Map.of("Effect", "Deny", "Action", "appsync:GraphQL", "Resource", deniedResource,
                            "Condition", Map.of(
                                    "StringEquals", Map.of(PREFIX + "aud", POOL),
                                    "ForAnyValue:StringEquals", Map.of(PREFIX + "amr", "unauthenticated"))))));
            iamService.putRolePolicy(roleName, "appsync", policy);
            rolePolicyCreated = true;

            AwsSessionCredentials guest = register(role, POOL, "unauthenticated", sessions);
            AwsSessionCredentials otherAudience = register(role, POOL + "-other", "unauthenticated", sessions);
            AwsSessionCredentials authenticated = register(role, POOL, "authenticated", sessions);
            iamService.createUser(userName, "/");
            userCreated = true;
            iamService.putUserPolicy(userName, "appsync", policy);
            userPolicyCreated = true;
            AccessKey key = iamService.createAccessKey(userName);
            userKey = key.getAccessKeyId();
            AwsCredentials ordinary = AwsBasicCredentials.create(userKey, key.getSecretAccessKey());

            Map<String, String> signedGuest = signed(guest);
            assertTrue(signedGuest.get("Authorization").toLowerCase(Locale.ROOT).contains("x-amz-security-token"));
            if (denyRequest) {
                AppSyncTransportException refusal = assertThrows(AppSyncTransportException.class,
                        () -> authorize(signedGuest));
                assertEquals(401, refusal.getHttpStatus());
                assertEquals("UnauthorizedException", refusal.getErrorType());
                assertEquals("Permission denied", refusal.getMessage());
            } else {
                assertEquals(guest.accessKeyId(), authorize(signedGuest).get("user"));
                assertFalse(validator.isFieldDenied(guest.accessKeyId(), PUBLIC_ARN));
            }
            assertTrue(validator.isFieldDenied(guest.accessKeyId(), SECRET_ARN));

            for (AwsCredentials allowed : List.of(otherAudience, authenticated, ordinary)) {
                assertEquals(allowed.accessKeyId(), authorize(signed(allowed)).get("user"));
                assertFalse(validator.isFieldDenied(allowed.accessKeyId(), SECRET_ARN));
                assertFalse(validator.isFieldDenied(allowed.accessKeyId(), PUBLIC_ARN));
            }

            // Positive credentials still require their real signature and registered session token.
            AwsSessionCredentials wrongToken = AwsSessionCredentials.create(
                    otherAudience.accessKeyId(), otherAudience.secretAccessKey(), "wrong-session-token");
            assertThrows(AppSyncTransportException.class, () -> authorize(signed(wrongToken)));
            Map<String, String> wrongSignature = new LinkedHashMap<>(signed(otherAudience));
            wrongSignature.put("Authorization", wrongSignature.get("Authorization").replaceFirst(
                    "Signature=[0-9a-f]", "Signature=z"));
            assertThrows(AppSyncTransportException.class, () -> authorize(wrongSignature));
        } finally {
            sessions.forEach(key -> iamService.unregisterSession(ACCOUNT, key));
            if (userKey != null) {
                iamService.deleteAccessKey(userName, userKey);
            }
            if (userPolicyCreated) {
                iamService.deleteUserPolicy(userName, "appsync");
            }
            if (userCreated) {
                iamService.deleteUser(userName);
            }
            if (rolePolicyCreated) {
                iamService.deleteRolePolicy(roleName, "appsync");
            }
            if (roleCreated) {
                iamService.deleteRole(roleName);
            }
        }
    }

    private AwsSessionCredentials register(IamRole role, String audience, String method, List<String> sessions) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String accessKey = "ASIA" + suffix.substring(0, 16).toUpperCase(Locale.ROOT);
        String secret = UUID.randomUUID().toString();
        String token = UUID.randomUUID().toString();
        iamService.registerCognitoIdentitySession(ACCOUNT, accessKey, secret, token, role.getArn(),
                Instant.now().plusSeconds(3600), null, ACCOUNT, "CognitoDirectAppSync",
                role.getRoleId() + ":CognitoDirectAppSync", Map.of(
                        "aud", List.of(audience), "amr", List.of(method), "sub", List.of(suffix)));
        sessions.add(accessKey);
        assertNotNull(iamService.resolveCallerContext(accessKey));
        return AwsSessionCredentials.create(accessKey, secret, token);
    }

    private Map<String, Object> authorize(Map<String, String> headers) {
        AuthRequestInfo info = new AuthRequestInfo("{ public secret }", null, Map.of(), List.of("127.0.0.1"),
                "cognito-direct-context", ACCOUNT, REGION, headers, BODY);
        return validator.validateRequest(headers.get("Authorization"), API, info);
    }

    @SuppressWarnings("deprecation")
    private static Map<String, String> signed(AwsCredentials credentials) {
        byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
        SdkHttpFullRequest request = SdkHttpFullRequest.builder().protocol("https")
                .host("appsync.us-east-1.amazonaws.com").encodedPath("/v1/apis/" + API + "/graphql")
                .method(SdkHttpMethod.POST).contentStreamProvider(() -> new ByteArrayInputStream(body)).build();
        SdkHttpFullRequest signed = Aws4Signer.create().sign(request, Aws4SignerParams.builder()
                .awsCredentials(credentials).signingName("appsync").signingRegion(Region.US_EAST_1).build());
        Map<String, String> headers = new LinkedHashMap<>();
        signed.headers().forEach((name, values) -> headers.put(name, String.join(",", values)));
        headers.put("Authorization", signed.firstMatchingHeader("Authorization").orElseThrow());
        return headers;
    }
}
