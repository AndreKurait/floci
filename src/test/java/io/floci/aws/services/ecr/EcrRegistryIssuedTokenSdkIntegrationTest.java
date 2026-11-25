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

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(EcrRegistryIssuedTokenSdkIntegrationTest.Profile.class)
class EcrRegistryIssuedTokenSdkIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    EcrRegistryAuthorization authorization;

    @Test
    void signedSdkTokenRetainsActualCallerAtMainEndpoint() throws Exception {
        String policy = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"ecr:GetAuthorizationToken\",\"Resource\":\"*\"}}";
        try (SignedIamUsers.User user = SignedIamUsers.createUser(endpoint, "ecr-token-", "token", policy);
             EcrClient client = client(user.accessKeyId(), user.secretAccessKey());
             EcrClient forged = client(user.accessKeyId(), "wrong-secret")) {
            assertThrows(EcrException.class, () -> forged.getAuthorizationToken());
            AuthorizationData token = client.getAuthorizationToken().authorizationData().getFirst();
            assertTrue(token.proxyEndpoint().contains("localhost"));
            EcrRegistryAuthorization.Identity caller = authorization.authenticate("Basic " + token.authorizationToken(), "us-west-2");
            assertTrue(caller.caller().principalArn().endsWith(":user/" + user.name()));
            assertEquals(user.accessKeyId(), caller.token().accessKeyId());
            assertEquals("us-west-2", caller.token().region());
            assertEquals(401, assertThrows(AwsException.class, () -> authorization.authenticate(
                    "Basic " + token.authorizationToken(), "us-east-1")).getHttpStatus());
            assertThrows(AwsException.class,
                    () -> authorization.requirePermission(caller, "ecr:BatchGetImage",
                            "arn:aws:ecr:us-west-2:137112412989:repository/amazonlinux", null));
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
            values.put("floci.default-region", "us-east-1");
            return values;
        }
    }
}
