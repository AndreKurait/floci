package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamAccountAliasOwnershipTest {

    private static final String FIRST = "111111111111";
    private static final String SECOND = "222222222222";

    @TempDir
    Path temporary;

    @Test
    void anotherAccountCannotClaimAnOwnedAliasOrLoseItsOwnAlias() {
        Fixture fixture = new Fixture();
        fixture.service.createAccountAlias("first-owner");
        fixture.account.set(SECOND);
        fixture.service.createAccountAlias("second-owner");

        AwsException error = assertThrows(AwsException.class,
                () -> fixture.service.createAccountAlias("first-owner"));

        assertEquals("EntityAlreadyExists", error.getErrorCode());
        assertEquals("second-owner", fixture.service.getAccountAlias().orElseThrow());
        fixture.account.set(FIRST);
        assertEquals("first-owner", fixture.service.getAccountAlias().orElseThrow());
    }

    @Test
    void replacingAnAliasReleasesOnlyTheOldName() {
        Fixture fixture = new Fixture();
        fixture.service.createAccountAlias("old-owner");
        fixture.service.createAccountAlias("new-owner");
        fixture.account.set(SECOND);
        fixture.service.createAccountAlias("old-owner");
        assertEquals("old-owner", fixture.service.getAccountAlias().orElseThrow());
        assertThrows(AwsException.class, () -> fixture.service.createAccountAlias("new-owner"));
    }

    @Test
    void deletingAnAliasReleasesItToAnotherAccount() {
        Fixture fixture = new Fixture();
        fixture.service.createAccountAlias("former-owner");
        fixture.account.set(SECOND);
        assertThrows(AwsException.class, () -> fixture.service.deleteAccountAlias("former-owner"));
        fixture.account.set(FIRST);
        fixture.service.deleteAccountAlias("former-owner");
        fixture.account.set(SECOND);
        fixture.service.createAccountAlias("former-owner");
        assertEquals("former-owner", fixture.service.getAccountAlias().orElseThrow());
    }

    @Test
    void existingPersistedOwnershipPreventsAConflictingClaim() {
        Fixture fixture = new Fixture();
        fixture.raw.put(FIRST + "/account-alias", "persisted-owner");
        fixture.account.set(SECOND);
        assertThrows(AwsException.class, () -> fixture.service.createAccountAlias("persisted-owner"));
        assertTrue(fixture.service.getAccountAlias().isEmpty());
    }

    @Test
    void legacyOwnershipPreventsAConflictingClaim() {
        Fixture fixture = new Fixture();
        fixture.raw.put("account-alias", "legacy-owner");
        fixture.account.set(SECOND);
        assertThrows(AwsException.class, () -> fixture.service.createAccountAlias("legacy-owner"));
        assertEquals("legacy-owner", fixture.raw.get("account-alias").orElseThrow());
        assertTrue(fixture.raw.get(SECOND + "/account-alias").isEmpty());
    }

    @Test
    void lookupReturnsTheOwnerWithoutChangingTheRequestAccount() {
        Fixture fixture = new Fixture();
        fixture.service.createAccountAlias("registered-owner");
        fixture.account.set(SECOND);
        assertEquals(FIRST, fixture.service.findAccountIdByAlias("registered-owner").orElseThrow());
        assertEquals(SECOND, fixture.account.get());
        assertTrue(fixture.service.getAccountAlias().isEmpty());
        assertTrue(fixture.service.findAccountIdByAlias("unknown-owner").isEmpty());
        assertEquals(1, fixture.raw.keys().size());
    }

    @Test
    void lookupTracksReplacementAndDeletionWithoutStaleOwnership() {
        Fixture fixture = new Fixture();
        fixture.service.createAccountAlias("before-replacement");
        fixture.service.createAccountAlias("after-replacement");
        assertTrue(fixture.service.findAccountIdByAlias("before-replacement").isEmpty());
        assertEquals(FIRST, fixture.service.findAccountIdByAlias("after-replacement").orElseThrow());
        fixture.service.deleteAccountAlias("after-replacement");
        assertTrue(fixture.service.findAccountIdByAlias("after-replacement").isEmpty());
        fixture.account.set(SECOND);
        fixture.service.createAccountAlias("after-replacement");
        assertEquals(SECOND, fixture.service.findAccountIdByAlias("after-replacement").orElseThrow());
    }

    @Test
    void lookupRejectsAmbiguousHistoricalOwnershipWithoutChangingIt() {
        Fixture fixture = new Fixture();
        fixture.raw.put(FIRST + "/account-alias", "duplicate-owner");
        fixture.raw.put(SECOND + "/account-alias", "duplicate-owner");
        AwsException error = assertThrows(AwsException.class,
                () -> fixture.service.findAccountIdByAlias("duplicate-owner"));
        assertEquals("InternalFailure", error.getErrorCode());
        assertEquals(2, fixture.raw.keys().size());
        assertEquals("duplicate-owner", fixture.raw.get(FIRST + "/account-alias").orElseThrow());
        assertEquals("duplicate-owner", fixture.raw.get(SECOND + "/account-alias").orElseThrow());
    }

    @Test
    void onlyTheDefaultAccountCanReadAndRemoveItsLegacyAlias() {
        Fixture fixture = new Fixture();
        fixture.raw.put("account-alias", "legacy-owner");
        fixture.account.set(SECOND);
        assertTrue(fixture.service.getAccountAlias().isEmpty());
        assertEquals(FIRST, fixture.service.findAccountIdByAlias("legacy-owner").orElseThrow());
        assertTrue(fixture.raw.get(SECOND + "/account-alias").isEmpty());
        fixture.account.set(FIRST);
        assertEquals("legacy-owner", fixture.service.getAccountAlias().orElseThrow());
        fixture.service.deleteAccountAlias("legacy-owner");
        assertTrue(fixture.raw.keys().isEmpty());
    }

    @Test
    void replacingTheDefaultAccountsLegacyAliasDoesNotLeaveAStaleName() {
        Fixture fixture = new Fixture();
        fixture.raw.put("account-alias", "legacy-owner");
        fixture.service.createAccountAlias("replacement-owner");
        assertTrue(fixture.service.findAccountIdByAlias("legacy-owner").isEmpty());
        fixture.service.deleteAccountAlias("replacement-owner");
        assertTrue(fixture.service.getAccountAlias().isEmpty());
        assertTrue(fixture.raw.keys().isEmpty());
    }

    @Test
    void configuredSeedCannotTakeAnotherAccountsAlias() {
        Fixture fixture = new Fixture(new InMemoryStorage<>(), "seeded-owner");
        fixture.raw.put(SECOND + "/account-alias", "seeded-owner");
        AwsException error = assertThrows(AwsException.class, fixture.service::seedDefaults);
        assertEquals("EntityAlreadyExists", error.getErrorCode());
        assertTrue(fixture.service.getAccountAlias().isEmpty());
        assertEquals(SECOND, fixture.service.findAccountIdByAlias("seeded-owner").orElseThrow());
    }

    @Test
    void ownershipSurvivesPersistentStoreReload() {
        Path file = temporary.resolve("aliases.json");
        PersistentStorage<String, String> original = new PersistentStorage<>(
                file, new TypeReference<Map<String, String>>() {});
        original.load();
        Fixture firstRun = new Fixture(original, null);
        firstRun.service.createAccountAlias("reloaded-owner");
        original.flushOrThrow();

        PersistentStorage<String, String> reloaded = new PersistentStorage<>(
                file, new TypeReference<Map<String, String>>() {});
        reloaded.load();
        Fixture secondRun = new Fixture(reloaded, null);
        secondRun.account.set(SECOND);
        assertEquals(FIRST, secondRun.service.findAccountIdByAlias("reloaded-owner").orElseThrow());
        assertThrows(AwsException.class, () -> secondRun.service.createAccountAlias("reloaded-owner"));
        assertTrue(secondRun.service.getAccountAlias().isEmpty());
    }

    @Test
    void lookupValidatesAliasSyntax() {
        Fixture fixture = new Fixture();
        for (String alias : List.of("Uppercase", "ab", "two--hyphens", "invalid_owner")) {
            AwsException error = assertThrows(AwsException.class,
                    () -> fixture.service.findAccountIdByAlias(alias));
            assertEquals("ValidationError", error.getErrorCode());
        }
        assertThrows(AwsException.class, () -> fixture.service.findAccountIdByAlias(null));
    }

    @Test
    void concurrentAccountsHaveExactlyOneWinner() throws Exception {
        Fixture fixture = new Fixture();
        int contenders = 8;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(contenders)) {
            for (int index = 0; index < contenders; index++) {
                String accountId = String.format("%012d", index + 1);
                results.add(executor.submit(() -> {
                    fixture.account.set(accountId);
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        fixture.service.createAccountAlias("contested-owner");
                        return true;
                    } catch (AwsException error) {
                        assertEquals("EntityAlreadyExists", error.getErrorCode());
                        return false;
                    } finally {
                        fixture.account.remove();
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get(10, TimeUnit.SECONDS) ? 1 : 0;
            }
            assertEquals(1, winners);
        } finally {
            start.countDown();
        }
        assertEquals(1, fixture.raw.keys().size());
    }

    private static final class Fixture {
        private final ThreadLocal<String> account = ThreadLocal.withInitial(() -> FIRST);
        private final StorageBackend<String, String> raw;
        private final IamService service;

        private Fixture() {
            this(new InMemoryStorage<>(), null);
        }

        @SuppressWarnings("unchecked")
        private Fixture(StorageBackend<String, String> raw, String seed) {
            this.raw = raw;
            RequestContext context = mock(RequestContext.class);
            when(context.getAccountId()).thenAnswer(invocation -> account.get());
            Instance<RequestContext> contexts = mock(Instance.class);
            when(contexts.get()).thenReturn(context);
            AccountAwareStorageBackend<String> aliases =
                    new AccountAwareStorageBackend<>(raw, contexts, FIRST);
            service = new IamService(
                    new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                    new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                    new InMemoryStorage<>(), aliases, new InMemoryStorage<>(), new InMemoryStorage<>(),
                    new InMemoryStorage<>(), new RegionResolver("us-east-1", FIRST), false, seed);
        }
    }
}
