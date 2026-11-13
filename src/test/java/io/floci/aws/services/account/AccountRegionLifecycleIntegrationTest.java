package io.floci.aws.services.account;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.account.AccountClient;
import software.amazon.awssdk.services.account.model.AccountException;
import software.amazon.awssdk.services.account.model.AlternateContact;
import software.amazon.awssdk.services.account.model.GetRegionOptStatusResponse;
import software.amazon.awssdk.services.account.model.ListRegionsRequest;
import software.amazon.awssdk.services.account.model.ListRegionsResponse;
import software.amazon.awssdk.services.account.model.RegionOptStatus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@Timeout(90)
class AccountRegionLifecycleIntegrationTest {
    private static final Logger LOG = Logger.getLogger(AccountRegionLifecycleIntegrationTest.class);
    private static final String TARGET = "ap-southeast-7";
    private static final Set<String> STATUSES = Set.of(
            "DISABLED", "DISABLING", "ENABLED", "ENABLING", "ENABLED_BY_DEFAULT");
    private static final ExecutionAttribute<WireCall> WIRE = new ExecutionAttribute<>("account-region-wire");
    private final List<AccountClient> clients = new ArrayList<>();
    private final List<Reservation> reservations = new ArrayList<>();
    private final List<WireCall> calls = new ArrayList<>();
    private AccountClient client;
    private String account;
    private String marker;
    private boolean restoring;
    private int forwardCalls;
    private int restorationCalls;

    @BeforeEach
    void openClient() {
        account = newAccount();
        marker = "account-region-" + UUID.randomUUID();
        client = open(account, Region.US_EAST_1);
    }

    @AfterEach
    void restoreOnlyReservedBaselineTargetsAndRetainEveryRawPair() {
        restoring = true;
        List<Throwable> failures = new ArrayList<>();
        try {
            for (Reservation reservation : reservations) {
                if (!reservation.enableAttempted) {
                    continue;
                }
                try {
                    String current = settle(reservation.client);
                    assertEquals("DISABLED", reservation.baseline.get(TARGET));
                    if ("ENABLED".equals(current)) {
                        assertEquals(enabled(reservation.baseline), collect(reservation.client, 50, List.of()));
                        LOG.infov("ACCOUNT_REGION_RESTORE_INTENT marker={0} account={1} target={2} baseline={3}",
                                marker, reservation.account, TARGET, reservation.baseline);
                        reservation.disableAttempted = true;
                        assertEquals(200, reservation.client.disableRegion(request -> request.regionName(TARGET))
                                .sdkHttpResponse().statusCode());
                        assertEmptyMutationResponse();
                        assertEquals("DISABLED", settle(reservation.client));
                    } else {
                        assertEquals("DISABLED", current, "Unexpected state never permits an opposing mutation");
                    }
                    Map<String, String> full = collect(reservation.client, 50, List.of());
                    assertEquals(reservation.baseline, full);
                    assertEquals(full, collect(reservation.client, 17, List.of()));
                    assertEquals("DISABLED", named(reservation.client, TARGET));
                    LOG.infov("ACCOUNT_REGION_RESTORED marker={0} account={1} baseline={2} actual={3}",
                            marker, reservation.account, reservation.baseline, full);
                } catch (Throwable failure) {
                    LOG.errorv(failure, "ACCOUNT_REGION_RESTORE_RETAINED marker={0} account={1}",
                            marker, reservation.account);
                    failures.add(failure);
                }
            }
            try {
                for (WireCall call : calls) {
                    assertNotNull(call.request, "Every transmitted request retains its body");
                    assertNotNull(call.response, "Every completed response is captured before SDK parsing");
                    assertTrue(call.status >= 100);
                }
            } catch (Throwable failure) {
                failures.add(failure);
            }
        } finally {
            clients.forEach(AccountClient::close);
        }
        assertAll("All reserved targets and dispatched raw pairs are accounted for",
                failures.stream().map(failure -> () -> { throw failure; }));
    }

    @Test
    void listsTheCompleteCommercialCatalogIncludingTaipeiAndNewZealand() {
        Map<String, String> full = collect(client, 50, List.of());
        assertEquals(commercialBaseline(), full);
        assertEquals(full, collect(client, 17, List.of()));
        assertEquals(full, collect(client, 1, List.of()));
    }

    @Test
    void namedReadsReturnTypedDefaultAndOptInStatesWithExactNames() {
        assertEquals("ENABLED_BY_DEFAULT", named(client, "us-east-1"));
        assertEquals("DISABLED", named(client, "ap-east-2"));
        assertEquals("DISABLED", named(client, "ap-southeast-6"));
        assertEquals("DISABLED", named(client, TARGET));
        assertEquals(commercialBaseline(), collect(client, 50, List.of()));
    }

    @Test
    void enablesOneRegionAndRestoresItsWholeAccountProjection() {
        Reservation reservation = reserve(client, account);
        enable(reservation);
        assertEquals("ENABLED", settle(client));
        Map<String, String> full = collect(client, 50, List.of());
        assertEquals(enabled(reservation.baseline), full);
        assertEquals(Map.of(TARGET, "ENABLED"), collect(client, 50, List.of("ENABLED")));
        assertEquals("ENABLED", named(client, TARGET));
    }

    @Test
    void filtersBeforePagingAndRejectsInvalidPaginationWithoutChangingState() {
        Map<String, String> baseline = collect(client, 50, List.of());
        assertEquals(commercialBaseline(), baseline);
        for (String status : STATUSES) {
            Map<String, String> expected = new LinkedHashMap<>();
            baseline.forEach((name, value) -> {
                if (status.equals(value)) {
                    expected.put(name, value);
                }
            });
            assertEquals(expected, collect(client, 7, List.of(status)));
        }
        refusal(400, "ValidationException", () -> client.listRegions(request -> request.maxResults(0)));
        refusal(400, "ValidationException", () -> client.listRegions(request -> request.maxResults(51)));
        refusal(400, "ValidationException", () -> client.listRegions(request -> request.nextToken("not-a-token")));
        refusal(400, "ValidationException", () -> client.listRegions(
                request -> request.regionOptStatusContainsWithStrings("NOT_A_STATUS")));
        assertEquals(baseline, collect(client, 50, List.of()));
    }

    @Test
    void enableRefusesADefaultEnabledRegionWithoutChangingItsStatus() {
        refusal(400, "ValidationException", () -> client.enableRegion(request -> request.regionName("us-east-1")));
        assertEquals("ENABLED_BY_DEFAULT", named(client, "us-east-1"));
        assertEquals(commercialBaseline(), collect(client, 50, List.of()));
    }

    @Test
    void disableRefusesADefaultEnabledRegionWithoutChangingItsStatus() {
        refusal(400, "ValidationException", () -> client.disableRegion(request -> request.regionName("us-east-1")));
        assertEquals("ENABLED_BY_DEFAULT", named(client, "us-east-1"));
        assertEquals(commercialBaseline(), collect(client, 50, List.of()));
    }

    @Test
    void invalidNamesAndExplicitStandaloneAccountIdsUseModeledErrors() {
        refusal(400, "ValidationException", () -> client.getRegionOptStatus(
                request -> request.regionName("not-a-region")));
        refusal(400, "ValidationException", () -> client.enableRegion(request -> request.regionName("")));
        refusal(400, "ValidationException", () -> client.disableRegion(request -> request.regionName("x".repeat(51))));
        refusal(400, "ValidationException", () -> client.listRegions(request -> request.accountId("123")));
        refusal(403, "AccessDeniedException", () -> client.listRegions(request -> request.accountId(account)));
        refusal(403, "AccessDeniedException", () -> client.getRegionOptStatus(
                request -> request.accountId(account).regionName(TARGET)));
        assertEquals(commercialBaseline(), collect(client, 50, List.of()));
    }

    @Test
    void oneAccountSharesStateAcrossSigningRegionsWhileAnotherAccountStaysSeparate() {
        AccountClient sameAccountWest = open(account, Region.US_WEST_2);
        AccountClient otherAccount = open(newAccount(), Region.US_EAST_1);
        Reservation reservation = reserve(client, account);
        Map<String, String> otherBaseline = collect(otherAccount, 50, List.of());
        assertEquals(reservation.baseline, collect(sameAccountWest, 50, List.of()));
        enable(reservation);
        assertEquals("ENABLED", settle(sameAccountWest));
        assertEquals(enabled(reservation.baseline), collect(sameAccountWest, 50, List.of()));
        assertEquals(otherBaseline, collect(otherAccount, 50, List.of()));
        assertEquals("DISABLED", named(otherAccount, TARGET));
    }

    @Test
    void partitionCatalogsRemainSeparateAndRejectTargetsFromAnotherPartition() {
        AccountClient china = open(account, Region.CN_NORTH_1);
        AccountClient gov = open(account, Region.US_GOV_WEST_1);
        assertEquals(Map.of("cn-north-1", "ENABLED_BY_DEFAULT", "cn-northwest-1", "ENABLED_BY_DEFAULT"),
                collect(china, 50, List.of()));
        assertEquals(Map.of("us-gov-east-1", "ENABLED_BY_DEFAULT", "us-gov-west-1", "ENABLED_BY_DEFAULT"),
                collect(gov, 50, List.of()));
        refusal(400, "ValidationException", () -> china.getRegionOptStatus(request -> request.regionName(TARGET)));
        refusal(400, "ValidationException", () -> gov.getRegionOptStatus(request -> request.regionName(TARGET)));
        assertEquals(commercialBaseline(), collect(client, 50, List.of()));
    }

    @Test
    void existingAlternateContactSdkLifecycleRemainsAvailable() {
        boolean attempted = false;
        refusal(404, "ResourceNotFoundException", () -> client.getAlternateContact(
                request -> request.alternateContactType("SECURITY")));
        try {
            attempted = true;
            assertEquals(200, client.putAlternateContact(request -> request.alternateContactType("SECURITY")
                    .name(marker).title("Owner").emailAddress("account-audit@example.com")
                    .phoneNumber("+12025550123")).sdkHttpResponse().statusCode());
            AlternateContact actual = client.getAlternateContact(
                    request -> request.alternateContactType("SECURITY")).alternateContact();
            assertEquals(AlternateContact.builder().alternateContactType("SECURITY")
                    .name(marker).title("Owner").emailAddress("account-audit@example.com")
                    .phoneNumber("+12025550123").build(), actual);
        } finally {
            if (attempted) {
                AlternateContact actual = client.getAlternateContact(
                        request -> request.alternateContactType("SECURITY")).alternateContact();
                assertEquals(marker, actual.name(), "Delete only the exact contact in the owned account");
                assertEquals(200, client.deleteAlternateContact(
                        request -> request.alternateContactType("SECURITY")).sdkHttpResponse().statusCode());
                refusal(404, "ResourceNotFoundException", () -> client.getAlternateContact(
                        request -> request.alternateContactType("SECURITY")));
            }
        }
    }

    private AccountClient open(String owner, Region region) {
        AccountClient created = AwsTestClients.create(AccountClient.builder().overrideConfiguration(config -> config
                .apiCallTimeout(Duration.ofSeconds(10)).apiCallAttemptTimeout(Duration.ofSeconds(8))
                .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(new WireReceipt(owner, region))),
                owner, region, UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(6)));
        clients.add(created);
        return created;
    }

    private static String newAccount() {
        return Long.toString(700000000000L + ThreadLocalRandom.current().nextLong(100000000000L));
    }

    private Reservation reserve(AccountClient ownedClient, String owner) {
        Map<String, String> baseline = collect(ownedClient, 50, List.of());
        assertEquals(commercialBaseline(), baseline);
        assertEquals("DISABLED", named(ownedClient, TARGET));
        Reservation reservation = new Reservation(ownedClient, owner, Map.copyOf(baseline));
        reservations.add(reservation);
        LOG.infov("ACCOUNT_REGION_RESERVATION marker={0} account={1} target={2} baseline={3}",
                marker, owner, TARGET, baseline);
        return reservation;
    }

    private void enable(Reservation reservation) {
        reservation.enableAttempted = true;
        LOG.infov("ACCOUNT_REGION_ENABLE_INTENT marker={0} account={1} target={2}",
                marker, reservation.account, TARGET);
        assertEquals(200, reservation.client.enableRegion(request -> request.regionName(TARGET))
                .sdkHttpResponse().statusCode());
        assertEmptyMutationResponse();
    }

    private void assertEmptyMutationResponse() {
        WireCall call = calls.getLast();
        assertEquals(200, call.status);
        assertArrayEquals(new byte[0], call.response, "Account mutation success has an empty HTTP body");
    }

    private String named(AccountClient ownedClient, String region) {
        GetRegionOptStatusResponse response = ownedClient.getRegionOptStatus(request -> request.regionName(region));
        LOG.infov("ACCOUNT_REGION_NAMED marker={0} requested={1} response={2}", marker, region, response);
        assertEquals(200, response.sdkHttpResponse().statusCode());
        assertEquals(region, response.regionName());
        assertTrue(STATUSES.contains(response.regionOptStatusAsString()));
        assertNotEquals(RegionOptStatus.UNKNOWN_TO_SDK_VERSION, response.regionOptStatus());
        return response.regionOptStatusAsString();
    }

    private String settle(AccountClient ownedClient) {
        for (int poll = 0; poll < 60; poll++) {
            String status = named(ownedClient, TARGET);
            if (!Set.of("ENABLING", "DISABLING").contains(status)) {
                return status;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the owned target", failure);
            }
        }
        throw new AssertionError("Owned target did not reach a terminal state within the fixture budget");
    }

    private Map<String, String> collect(AccountClient ownedClient, int size, List<String> filters) {
        Map<String, String> records = new LinkedHashMap<>();
        Set<String> tokens = new HashSet<>();
        String token = null;
        for (int page = 0; page < 50; page++) {
            ListRegionsRequest request = ListRegionsRequest.builder().maxResults(size)
                    .regionOptStatusContainsWithStrings(filters).nextToken(token).build();
            ListRegionsResponse response = ownedClient.listRegions(request);
            LOG.infov("ACCOUNT_REGION_PAGE marker={0} request={1} response={2}", marker, request, response);
            assertEquals(200, response.sdkHttpResponse().statusCode());
            assertTrue(response.regions().size() <= size);
            response.regions().forEach(value -> {
                assertNotNull(value.regionName());
                assertFalse(value.regionName().isEmpty());
                assertTrue(STATUSES.contains(value.regionOptStatusAsString()));
                assertNotEquals(RegionOptStatus.UNKNOWN_TO_SDK_VERSION, value.regionOptStatus());
                assertNull(records.put(value.regionName(), value.regionOptStatusAsString()),
                        "A complete collection contains each Region exactly once");
            });
            token = response.nextToken();
            if (token == null) {
                return records;
            }
            assertFalse(token.isEmpty());
            assertTrue(tokens.add(token), "Pagination must make progress");
            assertFalse(response.regions().isEmpty());
        }
        throw new AssertionError("Account collection did not terminate within 50 pages");
    }

    private static void refusal(int status, String code, Runnable call) {
        AccountException error = assertThrows(AccountException.class, call::run);
        assertEquals(status, error.statusCode());
        assertEquals(code, error.awsErrorDetails().errorCode());
    }

    private static Map<String, String> enabled(Map<String, String> baseline) {
        Map<String, String> result = new LinkedHashMap<>(baseline);
        result.put(TARGET, "ENABLED");
        return result;
    }

    private static Map<String, String> commercialBaseline() {
        Map<String, String> result = new LinkedHashMap<>();
        List.of("ap-northeast-1", "ap-northeast-2", "ap-northeast-3", "ap-south-1",
                "ap-southeast-1", "ap-southeast-2", "ca-central-1", "eu-central-1", "eu-north-1",
                "eu-west-1", "eu-west-2", "eu-west-3", "sa-east-1", "us-east-1", "us-east-2",
                "us-west-1", "us-west-2").forEach(name -> result.put(name, "ENABLED_BY_DEFAULT"));
        List.of("af-south-1", "ap-east-1", "ap-east-2", "ap-south-2", "ap-southeast-3",
                "ap-southeast-4", "ap-southeast-5", "ap-southeast-6", "ap-southeast-7", "ca-west-1",
                "eu-central-2", "eu-south-1", "eu-south-2", "il-central-1", "me-central-1",
                "me-south-1", "mx-central-1").forEach(name -> result.put(name, "DISABLED"));
        return result;
    }

    private static final class Reservation {
        private final AccountClient client;
        private final String account;
        private final Map<String, String> baseline;
        private boolean enableAttempted;
        private boolean disableAttempted;

        private Reservation(AccountClient client, String account, Map<String, String> baseline) {
            this.client = client;
            this.account = account;
            this.baseline = baseline;
        }
    }

    private static final class WireCall {
        private final int id;
        private byte[] request;
        private byte[] response;
        private int status;

        private WireCall(int id) {
            this.id = id;
        }
    }

    private final class WireReceipt implements ExecutionInterceptor {
        private final String owner;
        private final Region region;

        private WireReceipt(String owner, Region region) {
            this.owner = owner;
            this.region = region;
        }

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertTrue((restoring ? restorationCalls++ : forwardCalls++) < 200,
                    "Independent forward and restoration call budgets");
            WireCall call = new WireCall(calls.size() + 1);
            calls.add(call);
            attributes.putAttribute(WIRE, call);
            try (InputStream stream = context.requestBody().orElseThrow().contentStreamProvider().newStream()) {
                call.request = stream.readNBytes(65537);
                LOG.infov("ACCOUNT_REGION_REQUEST marker={0} id={1} account={2} region={3} method={4} uri={5} base64={6}",
                        marker, call.id, owner, region, context.httpRequest().method(),
                        context.httpRequest().getUri(), Base64.getEncoder().encodeToString(call.request));
                assertTrue(call.request.length <= 65536, "Request archival byte bound");
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
                                                               ExecutionAttributes attributes) {
            WireCall call = attributes.getAttribute(WIRE);
            call.status = context.httpResponse().statusCode();
            if (context.responseBody().isEmpty()) {
                call.response = new byte[0];
                LOG.infov("ACCOUNT_REGION_RESPONSE marker={0} id={1} status={2} headers={3} base64=",
                        marker, call.id, call.status, context.httpResponse().headers());
                return Optional.empty();
            }
            try (InputStream stream = context.responseBody().orElseThrow()) {
                call.response = stream.readNBytes(65537);
                LOG.infov("ACCOUNT_REGION_RESPONSE marker={0} id={1} status={2} headers={3} base64={4}",
                        marker, call.id, call.status, context.httpResponse().headers(),
                        Base64.getEncoder().encodeToString(call.response));
                assertTrue(call.response.length <= 65536, "Response archival byte bound");
                return Optional.of(new ByteArrayInputStream(call.response));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }
}
