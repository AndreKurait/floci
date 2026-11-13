package io.floci.aws.services.account;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.account.model.AccountRegion;
import io.floci.aws.services.account.model.AccountRegionTransition;
import io.floci.aws.services.organizations.OrganizationsService;
import io.floci.aws.services.organizations.model.Organization;
import io.floci.aws.services.organizations.model.OrganizationAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(20)
class AccountRegionServiceTest {
    private static final String OWNER = "111122223333";
    private static final String OTHER = "444455556666";
    private static final String TARGET = "ap-southeast-7";
    private static final String KEY = "aws/" + TARGET;
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final RegionResolver regions = mock(RegionResolver.class);
    private final OrganizationsService organizations = mock(OrganizationsService.class);
    private AccountAwareStorageBackend<AccountRegionTransition> store;
    private InMemoryStorage<String, AccountRegionTransition> backend;
    private AccountRegionService service;

    @BeforeEach
    void createFreshState() {
        when(regions.getPartition()).thenReturn("aws");
        backend = new InMemoryStorage<>();
        configure(backend);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void configure(InMemoryStorage<String, AccountRegionTransition> selectedBackend) {
        store = new AccountAwareStorageBackend<>(selectedBackend, null, OWNER);
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("account"), eq("account-region-transitions.json"), any(TypeReference.class)))
                .thenReturn((AccountAwareStorageBackend) store);
        when(factory.create(eq("account"), eq("account-alternate-contacts.json"), any(TypeReference.class)))
                .thenReturn((AccountAwareStorageBackend) AccountAwareStorageBackend.inMemory(OWNER));
        service = new AccountRegionService(factory, new AccountService(factory, organizations), regions, clock);
    }

    @Test
    void defaultCatalogIncludesBothNewRegionsAndNeverWritesOnReads() {
        List<AccountRegion> result = service.listRegions(OWNER, mapper.createObjectNode()).items();
        assertEquals(34, result.size());
        assertEquals(17, result.stream().filter(row -> "ENABLED_BY_DEFAULT".equals(row.regionOptStatus())).count());
        assertEquals(17, result.stream().filter(row -> "DISABLED".equals(row.regionOptStatus())).count());
        assertTrue(result.contains(new AccountRegion("ap-east-2", "DISABLED")));
        assertTrue(result.contains(new AccountRegion("ap-southeast-6", "DISABLED")));
        assertEquals("ENABLED_BY_DEFAULT", status(OWNER, "us-east-1"));
        assertTrue(snapshot().isEmpty());
    }

    @Test
    void oneDetachedIntentMovesThroughEachStateAtItsStoredDeadline() {
        service.enableRegion(OWNER, target(TARGET));
        AccountRegionTransition enabling = store.getForAccount(OWNER, KEY).orElseThrow();
        assertEquals(OWNER, enabling.accountId());
        assertEquals("aws", enabling.partition());
        assertEquals(TARGET, enabling.regionName());
        assertEquals("ENABLED", enabling.terminalStatus());
        assertEquals(clock.millis() + 500, enabling.deadlineMillis());
        assertEquals("ENABLING", status(OWNER, TARGET));
        clock.advance(499);
        assertEquals("ENABLING", status(OWNER, TARGET));
        clock.advance(1);
        assertEquals("ENABLED", status(OWNER, TARGET));
        assertSame(enabling, store.getForAccount(OWNER, KEY).orElseThrow());
        service.disableRegion(OWNER, target(TARGET));
        AccountRegionTransition disabling = store.getForAccount(OWNER, KEY).orElseThrow();
        assertNotSame(enabling, disabling);
        assertNotEquals(enabling.revision(), disabling.revision());
        assertEquals("ENABLED", enabling.terminalStatus(), "Previously returned immutable intent is unchanged");
        assertEquals("DISABLING", status(OWNER, TARGET));
        clock.advance(500);
        assertEquals("DISABLED", status(OWNER, TARGET));
        assertEquals("DISABLED", status(OTHER, TARGET));
        assertEquals(Set.of(OWNER + "/" + KEY), snapshot().keySet());
    }

    @Test
    void oppositeTransitionsConflictWithoutChangingAnyOwnedOrSiblingRecord() {
        service.enableRegion(OWNER, target(TARGET));
        service.enableRegion(OTHER, target("ap-east-2"));
        Map<String, AccountRegionTransition> before = snapshot();
        refusal(409, "ConflictException", () -> service.disableRegion(OWNER, target(TARGET)));
        assertEquals(before, snapshot());
        clock.advance(500);
        service.disableRegion(OWNER, target(TARGET));
        before = snapshot();
        refusal(409, "ConflictException", () -> service.enableRegion(OWNER, target(TARGET)));
        assertEquals(before, snapshot());
    }

    @Test
    void repeatedSameDirectionAndTerminalCallsDoNotWriteAnotherGeneration() {
        service.disableRegion(OWNER, target(TARGET));
        assertTrue(snapshot().isEmpty());
        service.enableRegion(OWNER, target(TARGET));
        Map<String, AccountRegionTransition> first = snapshot();
        service.enableRegion(OWNER, target(TARGET));
        assertEquals(first, snapshot());
        clock.advance(500);
        service.enableRegion(OWNER, target(TARGET));
        assertEquals(first, snapshot());
        service.disableRegion(OWNER, target(TARGET));
        Map<String, AccountRegionTransition> second = snapshot();
        service.disableRegion(OWNER, target(TARGET));
        clock.advance(500);
        service.disableRegion(OWNER, target(TARGET));
        assertEquals(second, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "not-a-region", "", " ap-east-2", "AP-EAST-2", "cn-north-1"})
    void invalidOrDefaultMutationTargetsPreserveTheWholeStore(String name) {
        service.enableRegion(OTHER, target(TARGET));
        Map<String, AccountRegionTransition> before = snapshot();
        refusal(400, "ValidationException", () -> service.enableRegion(OWNER, target(name)));
        refusal(400, "ValidationException", () -> service.disableRegion(OWNER, target(name)));
        assertEquals(before, snapshot());
    }

    @Test
    void wrongShapesAndPaginationBoundsAreRefusedBeforeWriting() throws Exception {
        service.enableRegion(OTHER, target(TARGET));
        Map<String, AccountRegionTransition> before = snapshot();
        for (String json : List.of("[]", "null", "1", "\"body\"")) {
            JsonNode request = mapper.readTree(json);
            refusal(400, "SerializationException", () -> service.enableRegion(OWNER, request));
        }
        refusal(400, "SerializationException",
                () -> service.getRegionOptStatus(OWNER, mapper.createObjectNode().put("RegionName", 1)));
        refusal(400, "ValidationException", () -> service.getRegionOptStatus(OWNER, mapper.createObjectNode()));
        refusal(400, "ValidationException", () -> service.getRegionOptStatus(OWNER, target("x".repeat(51))));
        for (String json : List.of("{\"MaxResults\":0}", "{\"MaxResults\":51}", "{\"MaxResults\":1.5}",
                "{\"MaxResults\":\"1\"}", "{\"MaxResults\":2147483648}", "{\"NextToken\":123}",
                "{\"NextToken\":\"" + "x".repeat(1001) + "\"}", "{\"RegionOptStatusContains\":[\"bad\"]}")) {
            JsonNode request = mapper.readTree(json);
            refusal(400, "ValidationException", () -> service.listRegions(OWNER, request));
        }
        refusal(400, "SerializationException", () -> service.listRegions(OWNER,
                mapper.createObjectNode().put("RegionOptStatusContains", "ENABLED")));
        assertEquals(before, snapshot());
    }

    @Test
    void filtersApplyBeforePagingAndEveryPageCompletesWithoutDuplicates() {
        ObjectNode request = mapper.createObjectNode().put("MaxResults", 7);
        request.putArray("RegionOptStatusContains").add("DISABLED").add("DISABLED");
        Set<String> names = new HashSet<>();
        int pages = 0;
        String token;
        do {
            PaginatedResult<AccountRegion> page = service.listRegions(OWNER, request);
            assertTrue(page.items().size() <= 7);
            for (AccountRegion region : page.items()) {
                assertEquals("DISABLED", region.regionOptStatus());
                assertTrue(names.add(region.regionName()));
            }
            token = page.nextToken();
            if (token != null) {
                request.put("NextToken", token);
            }
            pages++;
        } while (token != null && pages < 10);
        assertNull(token);
        assertEquals(3, pages);
        assertEquals(17, names.size());
        assertTrue(names.containsAll(Set.of("ap-east-2", "ap-southeast-6")));
        assertTrue(snapshot().isEmpty());
    }

    @Test
    void signedTokensBindAccountPartitionFiltersPageSizeAndContents() {
        String token = service.listRegions(OWNER, mapper.createObjectNode().put("MaxResults", 1)).nextToken();
        assertNotNull(token);
        ObjectNode request = mapper.createObjectNode().put("MaxResults", 1).put("NextToken", token);
        assertEquals(1, service.listRegions(OWNER, request).items().size());
        refusal(400, "ValidationException", () -> service.listRegions(OTHER, request));
        when(regions.getPartition()).thenReturn("aws-cn");
        refusal(400, "ValidationException", () -> service.listRegions(OWNER, request));
        when(regions.getPartition()).thenReturn("aws");
        refusal(400, "ValidationException", () -> service.listRegions(OWNER, request.deepCopy().put("MaxResults", 2)));
        ObjectNode filtered = request.deepCopy();
        filtered.putArray("RegionOptStatusContains").add("DISABLED");
        refusal(400, "ValidationException", () -> service.listRegions(OWNER, filtered));
        refusal(400, "ValidationException", () -> service.listRegions(OWNER,
                request.deepCopy().put("NextToken", token.substring(0, token.length() - 1) + "!")));
        assertTrue(snapshot().isEmpty());
    }

    @Test
    void transitionSettlementAndRoundTripInvalidateEarlierTokenGenerations() {
        String before = firstToken();
        service.enableRegion(OWNER, target(TARGET));
        refusedToken(before);
        String enabling = firstToken();
        clock.advance(500);
        refusedToken(enabling);
        service.disableRegion(OWNER, target(TARGET));
        clock.advance(500);
        assertEquals("DISABLED", status(OWNER, TARGET));
        refusedToken(before);
        String settled = firstToken();
        service.beforeReset();
        service.clear();
        service.afterReset();
        refusedToken(settled);
    }

    @Test
    void partitionsUseTheResolvedRequestPartitionAndAccountsStaySeparate() {
        service.enableRegion(OWNER, target(TARGET));
        clock.advance(500);
        assertEquals("ENABLED", status(OWNER, TARGET));
        assertEquals("DISABLED", status(OTHER, TARGET));
        Map<String, AccountRegionTransition> before = snapshot();
        when(regions.getPartition()).thenReturn("aws-cn");
        assertEquals(Set.of(new AccountRegion("cn-north-1", "ENABLED_BY_DEFAULT"),
                new AccountRegion("cn-northwest-1", "ENABLED_BY_DEFAULT")),
                Set.copyOf(service.listRegions(OWNER, mapper.createObjectNode()).items()));
        refusal(400, "ValidationException", () -> service.getRegionOptStatus(OWNER, target(TARGET)));
        assertEquals(before, snapshot());
    }

    @Test
    void foreignIdentityOrInvalidStoredIntentIsNeverAdoptedOrMutated() {
        for (AccountRegionTransition invalid : List.of(
                new AccountRegionTransition(OTHER, "aws", TARGET, "ENABLED", clock.millis(), "revision"),
                new AccountRegionTransition(OWNER, "aws-cn", TARGET, "ENABLED", clock.millis(), "revision"),
                new AccountRegionTransition(OWNER, "aws", "ap-east-2", "ENABLED", clock.millis(), "revision"),
                new AccountRegionTransition(OWNER, "aws", TARGET, null, clock.millis(), "revision"),
                new AccountRegionTransition(OWNER, "aws", TARGET, "ENABLED", -1, "revision"),
                new AccountRegionTransition(OWNER, "aws", TARGET, "ENABLED", clock.millis(), null))) {
            backend.put(OWNER + "/" + KEY, invalid);
            Map<String, AccountRegionTransition> before = snapshot();
            refusal(500, "InternalServerException", () -> service.getRegionOptStatus(OWNER, target(TARGET)));
            refusal(500, "InternalServerException", () -> service.enableRegion(OWNER, target(TARGET)));
            assertEquals(before, snapshot());
        }
    }

    @Test
    void unprefixedLegacyAndOtherAccountRowsAreNeverMigratedOnRead() {
        AccountRegionTransition foreign = new AccountRegionTransition(OTHER, "aws", TARGET,
                "ENABLED", clock.millis(), "foreign");
        backend.put(KEY, foreign);
        backend.put(OTHER + "/" + KEY, foreign);
        Map<String, AccountRegionTransition> before = snapshot();
        assertEquals("DISABLED", status(OWNER, TARGET));
        assertEquals("ENABLED", status(OTHER, TARGET));
        assertEquals(34, service.listRegions(OWNER, mapper.createObjectNode()).items().size());
        assertEquals(before, snapshot());
    }

    @Test
    void organizationTargetsReuseTheExistingTrustedManagementAndDelegatedChecks() {
        Organization organization = new Organization();
        organization.setFeatureSet("ALL");
        organization.setMasterAccountId(OWNER);
        OrganizationAccount management = new OrganizationAccount();
        OrganizationAccount member = new OrganizationAccount();
        when(organizations.describeOrganization(OWNER)).thenReturn(organization);
        when(organizations.describeAccount(OWNER, OWNER)).thenReturn(management);
        when(organizations.describeAccount(OWNER, OTHER)).thenReturn(member);
        ObjectNode request = target(TARGET).put("AccountId", OTHER);
        refusal(403, "AccessDeniedException", () -> service.enableRegion(OWNER, request));
        assertTrue(snapshot().isEmpty());
        organization.getEnabledServicePrincipals().put("account.amazonaws.com", Instant.now());
        service.enableRegion(OWNER, request);
        clock.advance(500);
        assertEquals("ENABLED", status(OTHER, TARGET));
        assertEquals("DISABLED", status(OWNER, TARGET));
        Map<String, AccountRegionTransition> before = snapshot();
        refusal(400, "ValidationException", () -> service.enableRegion(OWNER, target(TARGET).put("AccountId", OWNER)));
        organization.setFeatureSet("CONSOLIDATED_BILLING");
        refusal(403, "AccessDeniedException", () -> service.disableRegion(OWNER, request));
        assertEquals(before, snapshot());
    }

    @Test
    void aStandaloneOrForeignOrganizationTargetCannotWriteState() {
        when(organizations.describeOrganization(OWNER))
                .thenThrow(new AwsException("AWSOrganizationsNotInUseException", "standalone", 400));
        refusal(403, "AccessDeniedException",
                () -> service.enableRegion(OWNER, target(TARGET).put("AccountId", OTHER)));
        refusal(400, "ValidationException",
                () -> service.enableRegion(OWNER, target(TARGET).put("AccountId", "123")));
        refusal(400, "SerializationException",
                () -> service.enableRegion(OWNER, target(TARGET).put("AccountId", 123)));
        assertTrue(snapshot().isEmpty());
    }

    @Test
    void resetDrainsAnAdmittedWriteAndRefusesWritesUntilClearCompletes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch resettingStarted = new CountDownLatch(1);
        InMemoryStorage<String, AccountRegionTransition> blocking = new InMemoryStorage<>() {
            @Override
            public void putAllAtomically(Map<String, AccountRegionTransition> entries) {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                super.putAllAtomically(entries);
            }
        };
        configure(blocking);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> write = workers.submit(() -> service.enableRegion(OWNER, target(TARGET)));
            Future<?> drain;
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                drain = workers.submit(() -> {
                    resettingStarted.countDown();
                    service.beforeReset();
                });
                assertTrue(resettingStarted.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> drain.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            write.get(10, TimeUnit.SECONDS);
            drain.get(10, TimeUnit.SECONDS);
            Map<String, AccountRegionTransition> before = snapshot();
            refusal(500, "InternalServerException", () -> service.enableRegion(OTHER, target(TARGET)));
            assertEquals(before, snapshot());
            blocking.clear(); // StorageFactory's wipe occurs after every beforeReset hook.
            service.clear();
            assertTrue(snapshot().isEmpty());
            refusal(500, "InternalServerException", () -> service.getRegionOptStatus(OWNER, target(TARGET)));
            service.afterReset();
            assertEquals("DISABLED", status(OWNER, TARGET));
            service.enableRegion(OTHER, target(TARGET));
            assertEquals(Set.of(OTHER + "/" + KEY), snapshot().keySet());
        }
    }

    @Test
    void failedClearCanResumeWithoutDiscardingTheCommittedIntent() {
        InMemoryStorage<String, AccountRegionTransition> failing = new InMemoryStorage<>() {
            @Override
            public void clear() {
                throw new IllegalStateException("injected clear failure");
            }
        };
        configure(failing);
        service.enableRegion(OWNER, target(TARGET));
        Map<String, AccountRegionTransition> before = snapshot();
        service.beforeReset();
        assertThrows(IllegalStateException.class, service::clear);
        service.afterReset();
        clock.advance(500);
        assertEquals("ENABLED", status(OWNER, TARGET));
        assertEquals(before, snapshot());
        service.afterReset(); // The orchestrator may call this without a preceding beforeReset.
        service.disableRegion(OWNER, target(TARGET));
        assertEquals("DISABLING", status(OWNER, TARGET));
    }

    private ObjectNode target(String name) {
        return mapper.createObjectNode().put("RegionName", name);
    }

    private String status(String owner, String name) {
        return service.getRegionOptStatus(owner, target(name)).regionOptStatus();
    }

    private Map<String, AccountRegionTransition> snapshot() {
        return Map.copyOf(store.scanAllAccountsWithRawKeys());
    }

    private String firstToken() {
        return service.listRegions(OWNER, mapper.createObjectNode().put("MaxResults", 1)).nextToken();
    }

    private void refusedToken(String token) {
        refusal(400, "ValidationException", () -> service.listRegions(OWNER,
                mapper.createObjectNode().put("MaxResults", 1).put("NextToken", token)));
    }

    private static void refusal(int status, String code, Runnable action) {
        AwsException exception = assertThrows(AwsException.class, action::run);
        assertEquals(status, exception.getHttpStatus());
        assertEquals(code, exception.getErrorCode());
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            assertEquals(ZoneOffset.UTC, zone);
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
