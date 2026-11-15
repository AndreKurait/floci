package io.github.hectorvent.floci.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountAwareLegacyReadOnlyServiceTest {
    private static final String DEFAULT = "111111111111";
    private static final String OWNER = "222222222222";

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1::stream", "us-east-1::arn:aws:kinesis:us-east-1:222222222222:stream/s/consumer/c:1"})
    void ownerApprovedLegacyLookupIsReadOnlyAndDoesNotScan(String key) {
        InMemoryStorage<String, String> raw = spy(new InMemoryStorage<String, String>());
        raw.put(key, OWNER);
        AccountAwareStorageBackend<String> scoped = new AccountAwareStorageBackend<>(raw, null, DEFAULT);
        clearInvocations(raw);
        assertEquals(Optional.of(OWNER), scoped.getForAccountIncludingLegacyReadOnly(OWNER, key, OWNER::equals));
        assertTrue(scoped.getForAccountIncludingLegacyReadOnly(DEFAULT, key, DEFAULT::equals).isEmpty());
        verify(raw, never()).put(any(), any());
        verify(raw, never()).delete(any());
        verify(raw, never()).flush();
        verify(raw, never()).keys();
        assertEquals(Map.of(key, OWNER), scoped.scanAllAccountsWithRawKeys());
    }

    @Test
    void scopedPresenceTakesPrecedenceEvenWhenCallerWillRefuseItsOwner() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put("resource", OWNER);
        raw.put(OWNER + "/resource", DEFAULT);
        AccountAwareStorageBackend<String> scoped = new AccountAwareStorageBackend<>(raw, null, DEFAULT);
        assertEquals(Optional.of(DEFAULT),
                scoped.getForAccountIncludingLegacyReadOnly(OWNER, "resource", OWNER::equals));
        assertTrue(scoped.getForAccountIncludingLegacyReadOnly(OWNER, "resource", OWNER::equals)
                .filter(OWNER::equals).isEmpty());
        assertEquals(Optional.of(DEFAULT), scoped.getForAccountMigratingLegacy(OWNER, "resource", OWNER::equals));
        assertEquals(Map.of("resource", OWNER, OWNER + "/resource", DEFAULT), scoped.scanAllAccountsWithRawKeys());
    }

    @Test
    void accountPrefixedKeyCannotBecomeALegacyCandidateEvenWithPermissivePredicate() {
        InMemoryStorage<String, String> raw = new InMemoryStorage<>();
        raw.put(DEFAULT + "/resource", OWNER);
        AccountAwareStorageBackend<String> scoped = new AccountAwareStorageBackend<>(raw, null, DEFAULT);
        assertTrue(scoped.getForAccountIncludingLegacyReadOnly(OWNER, DEFAULT + "/resource", value -> true).isEmpty());
        assertEquals(Map.of(DEFAULT + "/resource", OWNER), scoped.scanAllAccountsWithRawKeys());
    }

    @Test
    void readOnlyLookupWaitsForAnInFlightLegacyMigrationAndThenFindsScopedValue() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryStorage<String, String> raw = new InMemoryStorage<>() {
            @Override
            public void put(String key, String value) {
                if (key.equals(OWNER + "/resource")) {
                    publishing.countDown();
                    await(release);
                }
                super.put(key, value);
            }
        };
        raw.put("resource", OWNER);
        AccountAwareStorageBackend<String> scoped = new AccountAwareStorageBackend<>(raw, null, DEFAULT);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<String>> migration = executor.submit(() ->
                    scoped.getForAccountMigratingLegacy(OWNER, "resource", OWNER::equals));
            try {
                assertTrue(publishing.await(10, TimeUnit.SECONDS));
                CountDownLatch reading = new CountDownLatch(1);
                Future<Optional<String>> read = executor.submit(() -> {
                    reading.countDown();
                    return scoped.getForAccountIncludingLegacyReadOnly(OWNER, "resource", OWNER::equals);
                });
                assertTrue(reading.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> read.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(Optional.of(OWNER), migration.get(10, TimeUnit.SECONDS));
                assertEquals(Optional.of(OWNER), read.get(10, TimeUnit.SECONDS));
                assertEquals(Map.of(OWNER + "/resource", OWNER), scoped.scanAllAccountsWithRawKeys());
            } finally {
                release.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
