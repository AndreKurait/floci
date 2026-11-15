package io.github.hectorvent.floci.services.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.account.model.AccountRegionTransition;
import io.github.hectorvent.floci.testing.SignedIamEnforcementProfile;
import io.github.hectorvent.floci.testing.SignedIamUsers;
import io.github.hectorvent.floci.testing.SignedIamUsers.User;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(AccountRegionUnknownPartitionIamIntegrationTest.ChinaUnknownRegionProfile.class)
class AccountRegionUnknownPartitionIamIntegrationTest {
    private static final Logger LOG = Logger.getLogger(AccountRegionUnknownPartitionIamIntegrationTest.class);
    private static final String UNKNOWN = "local-account-region";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    StorageFactory factory;

    @ParameterizedTest
    @ValueSource(strings = {"listRegions", "getRegionOptStatus", "enableRegion", "disableRegion"})
    void allFourActionsAuthorizeTheActualRequestPartitionForAnAllowedUnknownSigningRegion(String method)
            throws Exception {
        AccountAwareStorageBackend<AccountRegionTransition> store = factory.create(
                "account", "account-region-transitions.json", new TypeReference<Map<String, AccountRegionTransition>>() {});
        Map<String, AccountRegionTransition> before = Map.copyOf(store.scanAllAccountsWithRawKeys());
        String action = "account:" + Character.toUpperCase(method.charAt(0)) + method.substring(1);
        String body = method.equals("listRegions") ? "{}" : "{\"RegionName\":\"cn-north-1\"}";
        String commercial = "arn:aws:account::000000000000:account";
        String china = "arn:aws-cn:account::000000000000:account";
        try (User user = SignedIamUsers.createUser(endpoint, "account-unknown-", "region",
                policy(action, commercial))) {
            HttpResponse<String> denied = call(user.signer("account").inRegion(UNKNOWN), method, body, false);
            assertEquals(403, denied.statusCode(), denied.body());
            assertEquals(before, store.scanAllAccountsWithRawKeys());
            SignedIamUsers.iam(endpoint, "PutUserPolicy", Map.of("UserName", user.name(), "PolicyName", "region",
                    "PolicyDocument", policy(action, china))).then().statusCode(200);
            HttpResponse<String> accepted = call(user.signer("account").inRegion(UNKNOWN), method, body, false);
            verifyReached(method, accepted);
            verifyReached(method, call(user.signer("account").inRegion(UNKNOWN), method, body, true));
            assertEquals(before, store.scanAllAccountsWithRawKeys());
        }
    }

    @Test
    void anUnknownSigningRegionUsesTheChinaCatalogRatherThanACommercialFallback() throws Exception {
        try (User user = SignedIamUsers.createUser(endpoint, "account-catalog-", "region",
                policy("account:ListRegions", "arn:aws-cn:account::000000000000:account"))) {
            HttpResponse<String> response = call(user.signer("account").inRegion(UNKNOWN), "listRegions", "{}", false);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(JSON.readTree("""
                    {"Regions":[{"RegionName":"cn-north-1","RegionOptStatus":"ENABLED_BY_DEFAULT"},
                    {"RegionName":"cn-northwest-1","RegionOptStatus":"ENABLED_BY_DEFAULT"}]}
                    """), JSON.readTree(response.body()));
        }
    }

    private static String policy(String action, String resource) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"" + action
                + "\",\"Resource\":\"" + resource + "\"}]}";
    }

    private void verifyReached(String method, HttpResponse<String> response) throws Exception {
        int expected = method.equals("enableRegion") || method.equals("disableRegion") ? 400 : 200;
        assertEquals(expected, response.statusCode(), response.body());
        if (expected == 400) {
            assertEquals("ValidationException", JSON.readTree(response.body()).path("__type").asText());
        } else if (method.equals("getRegionOptStatus")) {
            assertEquals(JSON.readTree("{\"RegionName\":\"cn-north-1\",\"RegionOptStatus\":\"ENABLED_BY_DEFAULT\"}"),
                    JSON.readTree(response.body()));
        } else {
            assertEquals(2, JSON.readTree(response.body()).path("Regions").size());
            assertFalse(JSON.readTree(response.body()).has("NextToken"));
        }
    }

    private HttpResponse<String> call(AwsRequestSigner signer, String method, String body, boolean presigned)
            throws Exception {
        URI uri = endpoint.resolve("/" + method);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpRequest.Builder request;
        if (presigned) {
            URI signed = signer.presignedUri("POST", uri, Map.of(), bytes);
            assertTrue(signed.getRawQuery().contains(UNKNOWN));
            request = HttpRequest.newBuilder(signed).header("Authorization", "");
        } else {
            Map<String, String> headers = signer.headersFor("POST", uri, Map.of(), bytes);
            assertTrue(headers.values().stream().anyMatch(value -> value.contains("/" + UNKNOWN + "/account/aws4_request")));
            request = HttpRequest.newBuilder(uri);
            headers.forEach((key, value) -> {
                if (!key.equalsIgnoreCase("host")) {
                    request.header(key, value);
                }
            });
        }
        request.timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            LOG.infov("ACCOUNT_REGION_UNKNOWN_SCOPE method={0} signingRegion={1} presigned={2} status={3} body={4}",
                    method, UNKNOWN, presigned, response.statusCode(), response.body());
            return response;
        }
    }

    public static final class ChinaUnknownRegionProfile extends SignedIamEnforcementProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> values = new LinkedHashMap<>(super.getConfigOverrides());
            values.put("floci.default-region", "cn-north-1");
            values.put("floci.partitions.id", "aws-cn");
            values.put("floci.partitions.allow-unknown-regions", "true");
            return values;
        }
    }
}
