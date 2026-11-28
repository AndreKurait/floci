package io.floci.aws.services.ecr;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.AuthorizationData;
import software.amazon.awssdk.services.ecr.model.InvalidParameterException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(EcrNativeAuthorizationSdkIntegrationTest.Profile.class)
class EcrNativeAuthorizationSdkIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;

    private EcrClient client(String region) {
        return EcrClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("333333333333", "local-test"))).build();
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,amazonaws.com", "cn-north-1,amazonaws.com.cn", "us-gov-west-1,amazonaws.com"})
    void nativeTokensUseRequestedAccountsAndPartition(String region, String suffix) {
        try (EcrClient client = client(region)) {
            List<AuthorizationData> data = client.getAuthorizationToken(r -> r.registryIds("111111111111", "222222222222"))
                    .authorizationData();
            assertEquals(2, data.size());
            for (int i = 0; i < data.size(); i++) {
                String account = i == 0 ? "111111111111" : "222222222222";
                assertEquals("https://" + account + ".dkr.ecr." + region + "." + suffix, data.get(i).proxyEndpoint());
                assertTrue(new String(Base64.getDecoder().decode(data.get(i).authorizationToken()),
                        StandardCharsets.UTF_8).startsWith("AWS:"));
                assertTrue(data.get(i).expiresAt().isAfter(Instant.now()));
            }
        }
    }

    @Test
    void omittedRegistryListUsesCallerAccountAndMalformedIdsReturnAwsError() {
        try (EcrClient client = client("us-east-1")) {
            assertEquals("https://333333333333.dkr.ecr.us-east-1.amazonaws.com",
                    client.getAuthorizationToken(r -> {}).authorizationData().getFirst().proxyEndpoint());
            assertThrows(InvalidParameterException.class,
                    () -> client.getAuthorizationToken(r -> r.registryIds("bad")));
            assertThrows(InvalidParameterException.class,
                    () -> client.getAuthorizationToken(r -> r.registryIds(List.<String>of())));
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecr.native-uri", "true");
        }
    }
}
