package io.github.hectorvent.floci.services.cognitoidentity;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Base64Url;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.services.iam.IamService;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleWithWebIdentityResponse;
import software.amazon.awssdk.services.sts.model.StsException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(CognitoIdentityUnknownIssuerIntegrationTest.NoEnforcementProfile.class)
class CognitoIdentityUnknownIssuerIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    @TestHTTPResource
    URI endpoint;
    @Inject
    IamService iam;
    @Inject
    EmulatorConfig config;

    public static class NoEnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "false");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnknownIssuerCannotBypassCognitoTrustWhenIamEnforcementIsDisabled(boolean mixedTrust) {
        assertFalse(config.services().iam().enforcementEnabled());
        String name = "cognito-unknown-" + UUID.randomUUID();
        String issuer = "https://unknown-" + UUID.randomUUID() + ".example.test";
        String provider = "arn:aws:iam::" + ACCOUNT + ":oidc-provider/" + issuer.substring(8);
        String trust = "{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Federated\":\""
                + ServicePrincipals.of("cognito-identity") + "\"},\"Action\":\"sts:AssumeRoleWithWebIdentity\"}"
                + (mixedTrust ? ",{\"Effect\":\"Allow\",\"Principal\":{\"Federated\":\"" + provider
                        + "\"},\"Action\":\"sts:AssumeRoleWithWebIdentity\"}" : "") + "]}";
        String token = Base64Url.encode("{\"alg\":\"RS256\",\"kid\":\"unknown\"}") + "."
                + Base64Url.encode("{\"iss\":\"" + issuer + "\",\"sub\":\"app\",\"aud\":\"sts.amazonaws.com\"}")
                + ".invalid";
        List<String> sessions = new ArrayList<>();
        try (IamClient admin = IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCOUNT, "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
                StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                        .credentialsProvider(AnonymousCredentialsProvider.create())
                        .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String roleArn = admin.createRole(r -> r.roleName(name).assumeRolePolicyDocument(trust)).role().arn();
            try {
                StsException denied = assertThrows(StsException.class, () -> {
                    AssumeRoleWithWebIdentityResponse response = sts.assumeRoleWithWebIdentity(r -> r.roleArn(roleArn)
                            .roleSessionName("unknown-issuer").webIdentityToken(token));
                    sessions.add(response.credentials().accessKeyId());
                });
                assertEquals("InvalidIdentityToken", denied.awsErrorDetails().errorCode());
                assertEquals(400, denied.statusCode());
                assertTrue(sessions.isEmpty());
            } finally {
                try {
                    for (String accessKey : sessions) {
                        iam.unregisterSession(ACCOUNT, accessKey);
                        assertTrue(iam.resolveCallerArns(accessKey).isEmpty());
                    }
                } finally {
                    admin.deleteRole(r -> r.roleName(name));
                    assertThrows(NoSuchEntityException.class, () -> admin.getRole(r -> r.roleName(name)));
                }
            }
        }
    }
}
