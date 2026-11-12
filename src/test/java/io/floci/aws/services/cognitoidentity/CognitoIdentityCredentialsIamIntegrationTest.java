package io.floci.aws.services.cognitoidentity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentity.CognitoIdentityClient;
import software.amazon.awssdk.services.cognitoidentity.model.CognitoIdentityException;
import software.amazon.awssdk.services.cognitoidentity.model.Credentials;
import software.amazon.awssdk.services.cognitoidentity.model.GetOpenIdTokenForDeveloperIdentityResponse;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityResponse;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class CognitoIdentityCredentialsIamIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    private static final String PRINCIPAL = "cognito-identity.amazonaws.com";
    private static final String PROVIDER = "login.example.test";
    private static final AwsCredentialsProvider ROOT = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    @TestHTTPResource URI endpoint;
    @Inject IamService iamService;

    @Test
    void guestScopeDownClassicPermissionsSessionTokenAndTrustDenyAreEnforcedOnSignedRequests() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String roleName = "cognito-guest-" + suffix;
        String bucket = "cognito-guest-" + suffix;
        List<String> sessions = new ArrayList<>();
        try (CognitoIdentityClient admin = identities(ROOT);
             CognitoIdentityClient publicClient = identities(AnonymousCredentialsProvider.create());
             IamClient iam = iam(ROOT); S3Client rootS3 = s3(ROOT);
             StsClient anonymousSts = sts(AnonymousCredentialsProvider.create())) {
            String pool = pool(admin, suffix);
            String roleArn = null;
            try {
                roleArn = iam.createRole(r -> r.roleName(roleName).assumeRolePolicyDocument(trust(pool, "Allow"))).role().arn();
                String exactRoleArn = roleArn;
                iam.putRolePolicy(r -> r.roleName(roleName).policyName("data").policyDocument(
                        "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::"
                        + bucket + "/allowed\",\"Condition\":{\"StringEquals\":{\"" + PRINCIPAL + ":aud\":\"" + pool
                        + "\"},\"ForAnyValue:StringEquals\":{\"" + PRINCIPAL + ":amr\":\"unauthenticated\"}}},"
                        + "{\"Effect\":\"Allow\",\"Action\":\"iam:GetRole\",\"Resource\":\"" + exactRoleArn + "\"}]}"));
                admin.setIdentityPoolRoles(r -> r.identityPoolId(pool).roles(Map.of("unauthenticated", exactRoleArn)));
                rootS3.createBucket(r -> r.bucket(bucket));
                rootS3.putObject(r -> r.bucket(bucket).key("allowed"), RequestBody.fromString("allowed-content"));
                rootS3.putObject(r -> r.bucket(bucket).key("denied"), RequestBody.fromString("private-content"));
                String identity = publicClient.getId(r -> r.identityPoolId(pool)).identityId();
                Credentials guest = publicClient.getCredentialsForIdentity(r -> r.identityId(identity)).credentials();
                sessions.add(guest.accessKeyId());
                AwsCredentialsProvider guestProvider = session(guest.accessKeyId(), guest.secretKey(), guest.sessionToken());
                try (S3Client guestS3 = s3(guestProvider); IamClient guestIam = iam(guestProvider);
                     StsClient guestSts = sts(guestProvider)) {
                    assertEquals("allowed-content", guestS3.getObjectAsBytes(r -> r.bucket(bucket).key("allowed")).asUtf8String());
                    assertEquals(403, assertThrows(S3Exception.class,
                            () -> guestS3.getObjectAsBytes(r -> r.bucket(bucket).key("denied"))).statusCode());
                    assertEquals(403, assertThrows(IamException.class,
                            () -> guestIam.getRole(r -> r.roleName(roleName))).statusCode());
                    assertEquals("arn:aws:sts::" + ACCOUNT + ":assumed-role/" + roleName + "/CognitoIdentityCredentials",
                            guestSts.getCallerIdentity().arn());
                }
                String token = publicClient.getOpenIdToken(r -> r.identityId(identity)).token();
                AssumeRoleWithWebIdentityResponse classic = anonymousSts.assumeRoleWithWebIdentity(r ->
                        r.roleArn(exactRoleArn).roleSessionName("classic").webIdentityToken(token));
                sessions.add(classic.credentials().accessKeyId());
                try (IamClient classicIam = iam(session(classic.credentials().accessKeyId(),
                        classic.credentials().secretAccessKey(), classic.credentials().sessionToken()))) {
                    assertEquals(exactRoleArn, classicIam.getRole(r -> r.roleName(roleName)).role().arn());
                }
                try (StsClient wrongToken = sts(session(guest.accessKeyId(), guest.secretKey(), "wrong"));
                     StsClient missingToken = sts(StaticCredentialsProvider.create(
                             AwsBasicCredentials.create(guest.accessKeyId(), guest.secretKey())))) {
                    assertEquals(403, assertThrows(StsException.class, wrongToken::getCallerIdentity).statusCode());
                    assertEquals(403, assertThrows(StsException.class, missingToken::getCallerIdentity).statusCode());
                }
                iam.updateAssumeRolePolicy(r -> r.roleName(roleName).policyDocument(trust(pool, "Deny")));
                assertEquals("InvalidIdentityPoolConfigurationException", assertThrows(CognitoIdentityException.class,
                        () -> publicClient.getCredentialsForIdentity(r -> r.identityId(identity))).awsErrorDetails().errorCode());
                assertEquals("AccessDenied", assertThrows(StsException.class, () -> anonymousSts.assumeRoleWithWebIdentity(r ->
                        r.roleArn(exactRoleArn).roleSessionName("denied").webIdentityToken(token))).awsErrorDetails().errorCode());
            } finally {
                sessions.forEach(key -> iamService.unregisterSession(ACCOUNT, key));
                rootS3.deleteObject(r -> r.bucket(bucket).key("allowed"));
                rootS3.deleteObject(r -> r.bucket(bucket).key("denied"));
                rootS3.deleteBucket(r -> r.bucket(bucket));
                admin.deleteIdentityPool(r -> r.identityPoolId(pool));
                if (roleArn != null) {
                    iam.deleteRolePolicy(r -> r.roleName(roleName).policyName("data"));
                    iam.deleteRole(r -> r.roleName(roleName));
                }
            }
        }
    }

    @Test
    void scopedDeveloperManagementAndExactIssuedIssuerLeadToAuthenticatedRealRoleSession() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String roleName = "cognito-dev-" + suffix;
        String userName = "cognito-manager-" + suffix;
        List<String> sessions = new ArrayList<>();
        try (CognitoIdentityClient admin = identities(ROOT); IamClient iam = iam(ROOT);
             CognitoIdentityClient publicClient = identities(AnonymousCredentialsProvider.create());
             StsClient anonymousSts = sts(AnonymousCredentialsProvider.create())) {
            String pool = pool(admin, suffix);
            String otherPool = pool(admin, suffix + "other");
            String accessKeyId = null;
            try {
                String roleArn = iam.createRole(r -> r.roleName(roleName).assumeRolePolicyDocument(trust(pool, "Allow"))).role().arn();
                iam.putRolePolicy(r -> r.roleName(roleName).policyName("data").policyDocument(
                        "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"iam:GetRole\",\"Resource\":\"" + roleArn + "\"}}"));
                admin.setIdentityPoolRoles(r -> r.identityPoolId(pool).roles(Map.of("authenticated", roleArn)));
                iam.createUser(r -> r.userName(userName));
                iam.putUserPolicy(r -> r.userName(userName).policyName("manage").policyDocument(
                        "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":[\"cognito-identity:GetOpenIdTokenForDeveloperIdentity\","
                        + "\"cognito-identity:LookupDeveloperIdentity\"],\"Resource\":\"arn:aws:cognito-identity:us-east-1:"
                        + ACCOUNT + ":identitypool/" + pool + "\"}}"));
                AccessKey userKey = iam.createAccessKey(r -> r.userName(userName)).accessKey();
                accessKeyId = userKey.accessKeyId();
                GetOpenIdTokenForDeveloperIdentityResponse developer;
                try (CognitoIdentityClient manager = identities(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(userKey.accessKeyId(), userKey.secretAccessKey())))) {
                    developer = manager.getOpenIdTokenForDeveloperIdentity(r -> r.identityPoolId(pool)
                            .logins(Map.of(PROVIDER, "alice")));
                    assertEquals(developer.identityId(), manager.lookupDeveloperIdentity(r -> r.identityPoolId(pool)
                            .developerUserIdentifier("alice")).identityId());
                    assertThrows(CognitoIdentityException.class, () -> manager.getOpenIdTokenForDeveloperIdentity(r ->
                            r.identityPoolId(otherPool).logins(Map.of(PROVIDER, "alice"))));
                    assertTrue(admin.listIdentities(r -> r.identityPoolId(otherPool).maxResults(60)).identities().isEmpty());
                }
                String issuer = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(developer.token().split("\\.")[1]))
                        .path("iss").asText();
                Credentials enhanced = publicClient.getCredentialsForIdentity(r -> r.identityId(developer.identityId())
                        .logins(Map.of(issuer, developer.token()))).credentials();
                sessions.add(enhanced.accessKeyId());
                try (IamClient assumed = iam(session(enhanced.accessKeyId(), enhanced.secretKey(), enhanced.sessionToken()))) {
                    assertEquals(roleArn, assumed.getRole(r -> r.roleName(roleName)).role().arn());
                }
                assertThrows(CognitoIdentityException.class, () -> publicClient.getCredentialsForIdentity(r ->
                        r.identityId(developer.identityId()).logins(Map.of("https://" + issuer, developer.token()))));
                String unsigned = developer.token().substring(0, developer.token().lastIndexOf('.') + 1);
                assertThrows(CognitoIdentityException.class, () -> publicClient.getCredentialsForIdentity(r ->
                        r.identityId(developer.identityId()).logins(Map.of(issuer, unsigned))));
                AssumeRoleWithWebIdentityResponse classic = anonymousSts.assumeRoleWithWebIdentity(r -> r.roleArn(roleArn)
                        .roleSessionName("developer").webIdentityToken(developer.token()));
                sessions.add(classic.credentials().accessKeyId());
                assertEquals("arn:aws:sts::" + ACCOUNT + ":assumed-role/" + roleName + "/developer", classic.assumedRoleUser().arn());
                iam.updateAssumeRolePolicy(r -> r.roleName(roleName).policyDocument(trust(pool, "Deny")));
                assertThrows(CognitoIdentityException.class, () -> publicClient.getCredentialsForIdentity(r ->
                        r.identityId(developer.identityId()).logins(Map.of(issuer, developer.token()))));
                assertThrows(StsException.class, () -> anonymousSts.assumeRoleWithWebIdentity(r -> r.roleArn(roleArn)
                        .roleSessionName("denied").webIdentityToken(developer.token())));
            } finally {
                sessions.forEach(key -> iamService.unregisterSession(ACCOUNT, key));
                admin.deleteIdentityPool(r -> r.identityPoolId(pool));
                admin.deleteIdentityPool(r -> r.identityPoolId(otherPool));
                if (accessKeyId != null) {
                    String key = accessKeyId;
                    iam.deleteAccessKey(r -> r.userName(userName).accessKeyId(key));
                    iam.deleteUserPolicy(r -> r.userName(userName).policyName("manage"));
                    iam.deleteUser(r -> r.userName(userName));
                }
                iam.deleteRolePolicy(r -> r.roleName(roleName).policyName("data"));
                iam.deleteRole(r -> r.roleName(roleName));
            }
        }
    }

    private String trust(String pool, String effect) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":{\"Effect\":\"" + effect
                + "\",\"Principal\":{\"Federated\":\"" + PRINCIPAL + "\"},\"Action\":\"sts:AssumeRoleWithWebIdentity\","
                + "\"Condition\":{\"StringEquals\":{\"" + PRINCIPAL + ":aud\":\"" + pool + "\"},"
                + "\"ForAnyValue:StringEquals\":{\"" + PRINCIPAL + ":amr\":[\"authenticated\",\"unauthenticated\"]}}}}";
    }

    private String pool(CognitoIdentityClient client, String suffix) {
        return client.createIdentityPool(r -> r.identityPoolName("iam-" + suffix).allowUnauthenticatedIdentities(true)
                .allowClassicFlow(true).developerProviderName(PROVIDER)).identityPoolId();
    }

    private AwsCredentialsProvider session(String key, String secret, String token) {
        return StaticCredentialsProvider.create(AwsSessionCredentials.create(key, secret, token));
    }

    private CognitoIdentityClient identities(AwsCredentialsProvider credentials) {
        return CognitoIdentityClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
    }

    private IamClient iam(AwsCredentialsProvider credentials) {
        return IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1).credentialsProvider(credentials).build();
    }

    private S3Client s3(AwsCredentialsProvider credentials) {
        return S3Client.builder().endpointOverride(endpoint).region(Region.US_EAST_1).credentialsProvider(credentials)
                .forcePathStyle(true).build();
    }

    private StsClient sts(AwsCredentialsProvider credentials) {
        return StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1).credentialsProvider(credentials).build();
    }
}
