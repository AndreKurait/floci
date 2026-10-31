package io.github.hectorvent.floci.core.storage;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class AccountRepairPageTest {
    @Test
    void boundedPagesReachEveryAccountAndLegacyRowWithoutMaterializingInventory() {
        ObservedStore raw = new ObservedStore();
        for (int index = 0; index < 8200; index++) {
            String owner = index < 4100 ? "111111111111" : "222222222222";
            raw.put(owner + "/region/" + String.format("%05d", index), "value-" + index);
        }
        raw.put("legacy", "old");
        AccountAwareStorageBackend<String> storage = new AccountAwareStorageBackend<>(raw, null, "333333333333");
        List<String> visited = new ArrayList<>();
        String cursor = null;
        while (true) {
            raw.reads = 0;
            Map<String, AccountAwareStorageBackend.AccountEntry<String>> page =
                    storage.scanAllAccountEntriesAfter(cursor, 4096);
            assertTrue(raw.reads <= 4096);
            assertEquals(0, raw.materialized);
            if (page.isEmpty()) {
                break;
            }
            for (Map.Entry<String, AccountAwareStorageBackend.AccountEntry<String>> row : page.entrySet()) {
                assertFalse(visited.contains(row.getKey()));
                visited.add(row.getKey());
                cursor = row.getKey();
                String owner = cursor.equals("legacy") ? "333333333333" : cursor.substring(0, 12);
                assertEquals(owner, row.getValue().accountId());
            }
        }
        assertEquals(8201, visited.size());
        assertEquals("legacy", visited.getLast());
        assertEquals(4096, storage.scanAllAccountEntriesAfter(null, 4096).size());
    }

    @Test
    void disappearingRowsProduceAnEmptyPageAndNextSweepReachesTheTail() {
        ObservedStore raw = new ObservedStore();
        raw.put("a", "first");
        raw.put("b", "last");
        raw.disappear = true;
        AccountAwareStorageBackend<String> storage = new AccountAwareStorageBackend<>(raw, null, "111111111111");
        assertTrue(storage.scanAllAccountEntriesAfter(null, 1).isEmpty());
        assertEquals(List.of("b"), new ArrayList<>(storage.scanAllAccountEntriesAfter(null, 1).keySet()));
        assertTrue(storage.scanAllAccountEntriesAfter("b", 1).isEmpty());
    }

    @Test
    void nonPositiveLimitIsRejectedBeforeReadingStorage() {
        ObservedStore raw = new ObservedStore();
        AccountAwareStorageBackend<String> storage = new AccountAwareStorageBackend<>(raw, null, "111111111111");
        assertThrows(IllegalArgumentException.class, () -> storage.scanAllAccountEntriesAfter(null, 0));
        assertThrows(IllegalArgumentException.class, () -> storage.scanAllAccountEntriesAfter(null, -1));
        assertEquals(0, raw.scans);
    }

    private static final class ObservedStore extends InMemoryStorage<String, String> {
        private int reads;
        private int materialized;
        private int scans;
        private boolean disappear;

        @Override
        public Set<String> keys() {
            throw new AssertionError("Repair must not copy the entire key set");
        }

        @Override
        public List<String> scan(Predicate<String> filter) {
            scans++;
            List<String> values = super.scan(filter);
            materialized += values.size();
            return values;
        }

        @Override
        public Optional<String> get(String key) {
            reads++;
            if (disappear) {
                disappear = false;
                delete(key);
            }
            return super.get(key);
        }
    }
}
