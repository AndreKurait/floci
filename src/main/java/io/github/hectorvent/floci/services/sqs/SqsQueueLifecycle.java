package io.github.hectorvent.floci.services.sqs;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes metadata creation and complete deletion for one scoped queue key. */
final class SqsQueueLifecycle {
    private final Map<String, Entry> entries = new HashMap<>();

    Lease enter(String key) {
        Entry entry;
        synchronized (entries) {
            entry = entries.computeIfAbsent(key, ignored -> new Entry());
            entry.users++;
        }
        entry.lock.lock();
        return new Lease(key, entry);
    }

    final class Lease implements AutoCloseable {
        private final String key;
        private final Entry entry;

        private Lease(String key, Entry entry) {
            this.key = key;
            this.entry = entry;
        }

        @Override
        public void close() {
            entry.lock.unlock();
            synchronized (entries) {
                if (--entry.users == 0) {
                    entries.remove(key, entry);
                }
            }
        }
    }

    private static final class Entry {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }
}
