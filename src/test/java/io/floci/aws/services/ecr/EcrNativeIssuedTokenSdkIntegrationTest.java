package io.floci.aws.services.ecr;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ecr.registry.EcrRegistryAuthorization;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.AuthorizationData;
import software.amazon.awssdk.services.ecr.model.EcrException;
import software.amazon.awssdk.services.ecr.model.InvalidParameterException;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(EcrNativeIssuedTokenSdkIntegrationTest.Profile.class)
class EcrNativeIssuedTokenSdkIntegrationTest {
    private static final String POLICY =
            "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"ecr:GetAuthorizationToken\",\"Resource\":\"*\"}}";

    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    EcrRegistryAuthorization authorization;

    @Test
    void requestedRegistryTokensRetainCallerWithoutGrantingForeignAccess() throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ecr-native-", "token", POLICY);
             EcrClient client = client(user.accessKeyId(), user.secretAccessKey())) {
            List<String> registries = List.of("111111111111", "222222222222");
            List<AuthorizationData> tokens = client.getAuthorizationToken(r -> r.registryIds(registries))
                    .authorizationData();

            assertEquals(registries.size(), tokens.size());
            for (int i = 0; i < tokens.size(); i++) {
                AuthorizationData token = tokens.get(i);
                assertEquals("https://" + registries.get(i) + ".dkr.ecr.us-west-2.amazonaws.com", token.proxyEndpoint());
                EcrRegistryAuthorization.Identity caller = assertDoesNotThrow(
                        () -> authorization.authenticate("Basic " + token.authorizationToken(), "us-west-2"));
                assertEquals(user.accessKeyId(), caller.token().accessKeyId());
                assertEquals("us-west-2", caller.token().region());
                assertEquals(401, assertThrows(AwsException.class, () -> authorization.authenticate(
                        "Basic " + token.authorizationToken(), "us-east-1")).getHttpStatus());
                assertTrue(caller.caller().principalArn().endsWith(":user/" + user.name()));
                String resource = "arn:aws:ecr:us-west-2:" + registries.get(i) + ":repository/private";
                assertEquals(403, assertThrows(AwsException.class,
                        () -> authorization.requirePermission(caller, "ecr:BatchGetImage", resource, null)).getHttpStatus());
            }
        }
    }

    @Test
    void omittedRegistryUsesIssuedCallerAndInvalidRequestsRemainDenied() throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ecr-native-", "token", POLICY);
             EcrClient client = client(user.accessKeyId(), user.secretAccessKey());
             EcrClient forged = client(user.accessKeyId(), "wrong-secret")) {
            AuthorizationData token = client.getAuthorizationToken().authorizationData().getFirst();
            EcrRegistryAuthorization.Identity caller = authorization.authenticate("Basic " + token.authorizationToken(), "us-west-2");
            assertEquals(user.accessKeyId(), caller.token().accessKeyId());
            assertEquals("us-west-2", caller.token().region());
            assertEquals(401, assertThrows(AwsException.class, () -> authorization.authenticate(
                    "Basic " + token.authorizationToken(), "us-east-1")).getHttpStatus());
            assertEquals("https://" + caller.token().accountId() + ".dkr.ecr.us-west-2.amazonaws.com", token.proxyEndpoint());
            assertThrows(InvalidParameterException.class, () -> client.getAuthorizationToken(r -> r.registryIds("bad")));
            assertThrows(EcrException.class, () -> forged.getAuthorizationToken(r -> r.registryIds("111111111111")));
            assertThrows(AwsException.class, () -> authorization.authenticate("Basic QVdTOmZsb2Np", "us-west-2"));
        }
    }

    private EcrClient client(String key, String secret) {
        return EcrClient.builder().endpointOverride(endpoint).region(Region.US_WEST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret))).build();
    }

    public static class Profile extends SignedIamEnforcementProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> values = new HashMap<>(super.getConfigOverrides());
            values.put("floci.services.ecr.native-uri", "true");
            return values;
        }
    }
}
