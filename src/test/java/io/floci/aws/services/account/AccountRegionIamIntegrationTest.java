package io.floci.aws.services.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.account.model.AccountRegionTransition;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.floci.aws.testing.SignedIamUsers.User;
import io.floci.aws.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.account.AccountClient;
import software.amazon.awssdk.services.account.model.AccountException;
import software.amazon.awssdk.services.account.model.ListRegionsRequest;
import software.amazon.awssdk.services.account.model.ListRegionsResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP signatures and IAM evaluation, with full state comparisons around refusals. */
@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
@Timeout(90)
class AccountRegionIamIntegrationTest {
    private static final Logger LOG = Logger.getLogger(AccountRegionIamIntegrationTest.class);
    private static final String TARGET = "ap-southeast-7";
    private static final String BODY = "{\"RegionName\":\"" + TARGET + "\"}";
    private static final ObjectMapper JSON = new ObjectMapper();
    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    StorageFactory factory;
    @Inject
    EmulatorConfig config;
    private AccountClient root;
    private AccountAwareStorageBackend<AccountRegionTransition> store;
    private Map<String, String> baseline;
    private Map<String, AccountRegionTransition> storedBaseline;
    private String marker;
    private String arn;

    @BeforeEach
    void reserveTheDisabledTargetInThisTestApplication() {
        marker = "account-iam-" + UUID.randomUUID();
        arn = "arn:aws:account::" + config.defaultAccountId() + ":account";
        root = AwsTestClients.create(AccountClient.builder().overrideConfiguration(c -> c
                .apiCallTimeout(Duration.ofSeconds(10)).retryPolicy(RetryPolicy.none())),
                "test", Region.US_EAST_1, endpoint);
        store = factory.create("account", "account-region-transitions.json",
                new TypeReference<Map<String, AccountRegionTransition>>() {});
        baseline = projection();
        assertEquals(34, baseline.size());
        assertEquals("DISABLED", baseline.get(TARGET));
        storedBaseline = snapshot();
        LOG.infov("ACCOUNT_REGION_IAM_RESERVED marker={0} baseline={1} stored={2}", marker, baseline, storedBaseline);
    }

    @AfterEach
    void restoreOnlyTheReservedTargetAndVerifyAllSiblingState() {
        try {
            String current = settle();
            if ("ENABLED".equals(current)) {
                Map<String, String> enabled = new LinkedHashMap<>(baseline);
                enabled.put(TARGET, "ENABLED");
                assertEquals(enabled, projection());
                LOG.infov("ACCOUNT_REGION_IAM_RESTORE_INTENT marker={0} target={1}", marker, TARGET);
                assertEquals(200, root.disableRegion(r -> r.regionName(TARGET)).sdkHttpResponse().statusCode());
                assertEquals("DISABLED", settle());
            } else {
                assertEquals("DISABLED", current);
            }
            assertEquals(baseline, projection());
            Map<String, AccountRegionTransition> beforeSiblings = new LinkedHashMap<>(storedBaseline);
            Map<String, AccountRegionTransition> afterSiblings = new LinkedHashMap<>(snapshot());
            String ownedKey = config.defaultAccountId() + "/aws/" + TARGET;
            beforeSiblings.remove(ownedKey);
            afterSiblings.remove(ownedKey);
            assertEquals(beforeSiblings, afterSiblings);
            LOG.infov("ACCOUNT_REGION_IAM_RESTORED marker={0} projection={1} stored={2}",
                    marker, projection(), snapshot());
        } finally {
            root.close();
        }
    }

    @Test
    void allFourActionsRequireGrantsAndExplicitDenialsPreserveTheWholeStore() throws Exception {
        try (User user = SignedIamUsers.createUser(endpoint, "account-none-");
             AccountClient caller = client(user)) {
            Map<String, AccountRegionTransition> before = snapshot();
            denied(() -> caller.listRegions(ListRegionsRequest.builder().build()));
            denied(() -> caller.getRegionOptStatus(r -> r.regionName(TARGET)));
            denied(() -> caller.enableRegion(r -> r.regionName(TARGET)));
            denied(() -> caller.disableRegion(r -> r.regionName(TARGET)));
            assertEquals(before, snapshot());
        }
        try (User user = user(allow("account:*", "*") + ","
                + "{\"Effect\":\"Deny\",\"Action\":\"account:*\",\"Resource\":\"" + arn + "\"}");
             AccountClient caller = client(user)) {
            Map<String, AccountRegionTransition> before = snapshot();
            denied(() -> caller.listRegions(ListRegionsRequest.builder().build()));
            denied(() -> caller.getRegionOptStatus(r -> r.regionName(TARGET)));
            denied(() -> caller.enableRegion(r -> r.regionName(TARGET)));
            denied(() -> caller.disableRegion(r -> r.regionName(TARGET)));
            assertEquals(before, snapshot());
        }
    }

    @Test
    void theExactOwnAccountArnAllowsAllFourTypedSdkOperations() throws Exception {
        try (User user = user(allow("account:*", arn)); AccountClient caller = client(user)) {
            assertEquals(34, caller.listRegions(ListRegionsRequest.builder().build()).regions().size());
            assertEquals(TARGET, caller.getRegionOptStatus(r -> r.regionName(TARGET)).regionName());
            assertEquals(200, caller.enableRegion(r -> r.regionName(TARGET)).sdkHttpResponse().statusCode());
            assertEquals("ENABLED", settle());
            assertEquals("ENABLED", caller.getRegionOptStatus(r -> r.regionName(TARGET)).regionOptStatusAsString());
            assertEquals(200, caller.disableRegion(r -> r.regionName(TARGET)).sdkHttpResponse().statusCode());
            assertEquals("DISABLED", settle());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"arn:aws:account::999988887777:account", "arn:aws-cn:account::000000000000:account"})
    void anArnForAnotherOwnerOrPartitionCannotAuthorizeAnyRegionOperation(String wrongArn) throws Exception {
        try (User user = user(allow("account:*", wrongArn)); AccountClient caller = client(user)) {
            Map<String, AccountRegionTransition> before = snapshot();
            denied(() -> caller.listRegions(ListRegionsRequest.builder().build()));
            denied(() -> caller.getRegionOptStatus(r -> r.regionName(TARGET)));
            denied(() -> caller.enableRegion(r -> r.regionName(TARGET)));
            denied(() -> caller.disableRegion(r -> r.regionName(TARGET)));
            assertEquals(before, snapshot());
        }
    }

    @Test
    void targetRegionConditionsReadTheActualUncachedBodyBeforeEachNamedAction() throws Exception {
        try (User user = user(condition("account:*", arn, TARGET)); AccountClient caller = client(user)) {
            Map<String, AccountRegionTransition> before = snapshot();
            denied(() -> caller.getRegionOptStatus(r -> r.regionName("ap-east-2")));
            denied(() -> caller.enableRegion(r -> r.regionName("ap-east-2")));
            denied(() -> caller.disableRegion(r -> r.regionName("ap-east-2")));
            assertEquals(before, snapshot());
            assertEquals("DISABLED", caller.getRegionOptStatus(r -> r.regionName(TARGET)).regionOptStatusAsString());
            assertEquals(200, caller.enableRegion(r -> r.regionName(TARGET)).sdkHttpResponse().statusCode());
            assertEquals("ENABLED", settle());
            policy(user, condition("account:*", arn, "ap-east-2"));
            before = snapshot();
            denied(() -> caller.disableRegion(r -> r.regionName(TARGET)));
            assertEquals(before, snapshot());
            policy(user, condition("account:*", arn, TARGET));
            assertEquals(200, caller.disableRegion(r -> r.regionName(TARGET)).sdkHttpResponse().statusCode());
        }
    }

    @Test
    void blankAuthorizationPresignedRequestsStillCheckExactResourceAndTargetRegion() throws Exception {
        try (User user = user(condition("account:EnableRegion", arn, "ap-east-2"))) {
            Map<String, AccountRegionTransition> before = snapshot();
            assertEquals(403, presigned(user, "/enableRegion", BODY).statusCode());
            assertEquals(before, snapshot());
            policy(user, condition("account:EnableRegion", "arn:aws:account::999988887777:account", TARGET));
            assertEquals(403, presigned(user, "/enableRegion", BODY).statusCode());
            assertEquals(before, snapshot());
            policy(user, condition("account:EnableRegion", arn, TARGET));
            HttpResponse<String> accepted = presigned(user, "/enableRegion", BODY);
            assertEquals(200, accepted.statusCode(), accepted.body());
            assertEquals("", accepted.body());
            assertEquals("ENABLED", settle());
        }
    }

    @Test
    void listDoesNotAcquireATargetRegionConditionFromAnInjectedBodyMember() throws Exception {
        String missingCondition = "{\"Effect\":\"Allow\",\"Action\":\"account:ListRegions\",\"Resource\":\""
                + arn + "\",\"Condition\":{\"Null\":{\"account:TargetRegion\":\"true\"}}}";
        try (User user = user(missingCondition)) {
            HttpResponse<String> response = signed(user.signer("account"), "/listRegions", BODY, Map.of());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(34, JSON.readTree(response.body()).path("Regions").size());
            policy(user, condition("account:ListRegions", arn, TARGET));
            Map<String, AccountRegionTransition> before = snapshot();
            assertEquals(403, signed(user.signer("account"), "/listRegions", BODY, Map.of()).statusCode());
            assertEquals(before, snapshot());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "encoded", "trailing", "matrix"})
    void matchedHandlersKeepTheirActionsDespiteEncodedPathsAndOperationSpoofing(String variant) throws Exception {
        try (User user = user(allow("account:ListRegions", arn) + ","
                + "{\"Effect\":\"Deny\",\"Action\":[\"account:GetRegionOptStatus\",\"account:EnableRegion\","
                + "\"account:DisableRegion\"],\"Resource\":\"" + arn + "\"}")) {
            Map<String, AccountRegionTransition> before = snapshot();
            for (String operation : List.of("getRegionOptStatus", "enableRegion", "disableRegion")) {
                String path = "/" + (variant.equals("encoded") ? "%" + Integer.toHexString(operation.charAt(0))
                        + operation.substring(1) : operation);
                path += variant.equals("trailing") ? "/" : variant.equals("matrix") ? ";ignored=1" : "";
                path += "?Action=ListRegions&Operation=ListRegions";
                String body = "{\"RegionName\":\"us-east-1\",\"Action\":\"ListRegions\"}";
                HttpResponse<String> rootResponse = signed(AwsRequestSigner.signedAs("test", "test", "account"),
                        path, body, Map.of("x-amz-target", "Account.ListRegions"));
                assertEquals(operation.equals("getRegionOptStatus") ? 200 : 400,
                        rootResponse.statusCode(), rootResponse.body());
                HttpResponse<String> response = signed(user.signer("account"), path, body,
                        Map.of("x-amz-target", "Account.ListRegions"));
                assertEquals(403, response.statusCode(), response.body());
            }
            assertEquals(before, snapshot());
        }
    }

    @Test
    void malformedBodiesAndAValidlySignedWrongServiceCannotMutateRegionState() throws Exception {
        Map<String, AccountRegionTransition> before = snapshot();
        for (String body : List.of(BODY + " {}", "[]", "{\"RegionName\":3}")) {
            HttpResponse<String> response = signed(AwsRequestSigner.signedAs("test", "test", "account"),
                    "/enableRegion", body, Map.of());
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(response.body().contains("SerializationException"));
            assertEquals(before, snapshot());
        }
        try (User user = user(allow("account:EnableRegion", arn))) {
            assertEquals(403, signed(user.signer("account").withSecret("not-the-issued-secret"),
                    "/enableRegion", BODY, Map.of()).statusCode());
            assertEquals(403, signed(user.signer("s3"), "/enableRegion", BODY, Map.of()).statusCode());
            assertEquals(before, snapshot());
        }
    }

    @Test
    void actualSignedEastAndWestRequestsShareOneAccountRegionState() throws Exception {
        try (User user = user(condition("account:*", arn, TARGET))) {
            HttpResponse<String> westBefore = signed(user.signer("account").inRegion("us-west-2"),
                    "/getRegionOptStatus", BODY, Map.of());
            assertEquals(200, westBefore.statusCode(), westBefore.body());
            assertEquals("DISABLED", JSON.readTree(westBefore.body()).path("RegionOptStatus").asText());
            assertEquals(200, signed(user.signer("account").inRegion("us-east-1"),
                    "/enableRegion", BODY, Map.of()).statusCode());
            assertEquals("ENABLED", settle());
            HttpResponse<String> westAfter = signed(user.signer("account").inRegion("us-west-2"),
                    "/getRegionOptStatus", BODY, Map.of());
            assertEquals(200, westAfter.statusCode(), westAfter.body());
            assertEquals(TARGET, JSON.readTree(westAfter.body()).path("RegionName").asText());
            assertEquals("ENABLED", JSON.readTree(westAfter.body()).path("RegionOptStatus").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"plain", "encoded", "trailing", "matrix"})
    void everyMatchedRegionMethodRequiresAccountScopeForHeaderAndQueryCredentials(String variant) throws Exception {
        try (User user = user(allow("account:*", arn))) {
            for (boolean query : List.of(false, true)) {
                for (String operation : List.of("listRegions", "getRegionOptStatus", "enableRegion", "disableRegion")) {
                    String path = "/" + (variant.equals("encoded") ? "%"
                            + Integer.toHexString(operation.charAt(0)) + operation.substring(1) : operation);
                    path += variant.equals("trailing") ? "/" : variant.equals("matrix") ? ";ignored=1" : "";
                    String body = operation.equals("listRegions") ? "{}" : BODY;
                    Map<String, AccountRegionTransition> before = snapshot();
                    HttpResponse<String> refused = scopedRequest(user.signer("s3"), path, body, query);
                    assertEquals(403, refused.statusCode(), refused.body());
                    assertEquals("InvalidSignatureException", JSON.readTree(refused.body()).path("__type").asText());
                    assertTrue(refused.body().contains("Credential should be scoped to correct service: 'account'"));
                    assertEquals(before, snapshot());
                    HttpResponse<String> accepted = scopedRequest(user.signer("account"), path, body, query);
                    assertEquals(200, accepted.statusCode(), accepted.body());
                    if (operation.equals("listRegions")) {
                        assertEquals(34, JSON.readTree(accepted.body()).path("Regions").size());
                        assertFalse(JSON.readTree(accepted.body()).has("NextToken"));
                        assertEquals(before, snapshot());
                    } else if (operation.equals("getRegionOptStatus")) {
                        assertEquals(TARGET, JSON.readTree(accepted.body()).path("RegionName").asText());
                        assertEquals("DISABLED", JSON.readTree(accepted.body()).path("RegionOptStatus").asText());
                        assertEquals(before, snapshot());
                    } else {
                        assertEquals("", accepted.body());
                        String expected = operation.equals("enableRegion") ? "ENABLED" : "DISABLED";
                        assertEquals(expected, settle());
                        Map<String, String> complete = new LinkedHashMap<>(baseline);
                        complete.put(TARGET, expected);
                        assertEquals(complete, projection());
                    }
                }
            }
        }
    }

    private HttpResponse<String> scopedRequest(AwsRequestSigner signer, String path, String body,
                                               boolean query) throws Exception {
        return query
                ? presigned(signer, path, body, Map.of("Action", "ListRegions", "Operation", "ListRegions"))
                : signed(signer, path + "?Action=ListRegions&Operation=ListRegions", body,
                        Map.of("x-amz-target", "Account.ListRegions"));
    }

    private AccountClient client(User user) {
        return AwsTestClients.create(AccountClient.builder().overrideConfiguration(c -> c
                .apiCallTimeout(Duration.ofSeconds(10)).retryPolicy(RetryPolicy.none())),
                user.accessKeyId(), user.secretAccessKey(), Region.US_EAST_1, endpoint);
    }

    private HttpResponse<String> presigned(User user, String path, String body) throws Exception {
        return presigned(user.signer("account"), path, body, Map.of());
    }

    private HttpResponse<String> presigned(AwsRequestSigner signer, String path, String body,
                                           Map<String, String> extraQuery) throws Exception {
        URI signed = signer.presignedUri("POST", endpoint.resolve(path), extraQuery,
                body.getBytes(StandardCharsets.UTF_8));
        assertTrue(signed.getRawQuery().contains("X-Amz-Signature="));
        return send(HttpRequest.newBuilder(signed).timeout(Duration.ofSeconds(10))
                .header("Authorization", "").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), path, body);
    }

    private HttpResponse<String> signed(AwsRequestSigner signer, String path, String body,
                                        Map<String, String> extra) throws Exception {
        URI uri = endpoint.resolve(path);
        Map<String, String> headers = signer.headersFor("POST", uri, extra, body.getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        extra.forEach(request::header);
        headers.forEach((key, value) -> {
            if (!key.equalsIgnoreCase("host")) {
                request.header(key, value);
            }
        });
        String authorization = headers.get("Authorization");
        if (authorization == null) {
            authorization = headers.get("authorization");
        }
        assertNotNull(authorization);
        assertTrue(authorization.contains("/aws4_request"));
        return send(request.build(), path, body);
    }

    private HttpResponse<String> send(HttpRequest request, String path, String body) throws Exception {
        LOG.infov("ACCOUNT_REGION_IAM_HTTP_SIGNED marker={0} uri={1} headers64={2}", marker,
                request.uri().toASCIIString(), Base64.getEncoder().encodeToString(
                        JSON.writeValueAsBytes(request.headers().map())));
        LOG.infov("ACCOUNT_REGION_IAM_HTTP_REQUEST marker={0} path={1} body64={2}", marker, path,
                Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            LOG.infov("ACCOUNT_REGION_IAM_HTTP_RESPONSE marker={0} status={1} headers={2} body64={3}",
                    marker, response.statusCode(), response.headers().map(),
                    Base64.getEncoder().encodeToString(response.body().getBytes(StandardCharsets.UTF_8)));
            return response;
        }
    }

    private Map<String, String> projection() {
        ListRegionsResponse response = root.listRegions(r -> r.maxResults(50));
        assertNull(response.nextToken());
        Map<String, String> rows = new LinkedHashMap<>();
        response.regions().forEach(region -> assertNull(rows.put(region.regionName(), region.regionOptStatusAsString())));
        return Map.copyOf(rows);
    }

    private String settle() {
        for (int attempt = 0; attempt < 100; attempt++) {
            String state = root.getRegionOptStatus(r -> r.regionName(TARGET)).regionOptStatusAsString();
            if (!List.of("ENABLING", "DISABLING").contains(state)) {
                return state;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
        }
        throw new AssertionError("Reserved Region did not settle");
    }

    private Map<String, AccountRegionTransition> snapshot() {
        return Map.copyOf(store.scanAllAccountsWithRawKeys());
    }

    private User user(String statements) throws Exception {
        return SignedIamUsers.createUser(endpoint, "account-region-", "regions", document(statements));
    }

    private void policy(User user, String statements) throws Exception {
        SignedIamUsers.iam(endpoint, "PutUserPolicy", Map.of("UserName", user.name(), "PolicyName", "regions",
                "PolicyDocument", document(statements))).then().statusCode(200);
    }

    private static String document(String statements) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}";
    }

    private static String allow(String action, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String condition(String action, String resource, String target) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource
                + "\",\"Condition\":{\"StringEquals\":{\"account:TargetRegion\":\"" + target + "\"}}}";
    }

    private static void denied(Runnable action) {
        AccountException failure = assertThrows(AccountException.class, action::run);
        assertEquals(403, failure.statusCode());
        assertTrue(failure.awsErrorDetails().errorCode().contains("AccessDenied"));
    }
}
