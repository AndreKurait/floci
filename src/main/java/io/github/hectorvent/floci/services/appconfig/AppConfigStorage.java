package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Region view of a factory-owned account store. Older, regionless keys belong only to
 * the configured default region; new writes always record their region explicitly.
 */
final class AppConfigStorage<V> implements StorageBackend<String, V> {
    private final StorageBackend<String, V> delegate;
    private final RegionResolver regions;

    AppConfigStorage(StorageFactory factory, RegionResolver regions, String service, String file,
                     TypeReference<Map<String, V>> type) {
        this.delegate = factory.create(service, file, type);
        this.regions = regions;
    }

    @Override
    public void put(String key, V value) {
        if (!logicalKey(key)) {
            throw new IllegalArgumentException("AppConfig storage keys cannot contain a region prefix");
        }
        delegate.put(prefix() + key, value);
        if (defaultRegion() && read(key).isPresent()) {
            delegate.delete(key);
        }
    }

    /**
     * Publishes new region-scoped snapshots in one backend commit. Legacy entries stay shadowed
     * until normal deletion, avoiding a second fallible write after the replacement commits.
     */
    @Override
    public void putAllAtomically(Map<String, V> entries) {
        Map<String, V> scoped = new LinkedHashMap<>();
        String regionPrefix = prefix();
        entries.forEach((key, value) -> {
            if (!logicalKey(key)) {
                throw new IllegalArgumentException("AppConfig storage keys cannot contain a region prefix");
            }
            scoped.put(regionPrefix + key, value);
        });
        delegate.putAllAtomically(scoped);
    }

    @Override
    public Optional<V> get(String key) {
        if (!logicalKey(key)) {
            return Optional.empty();
        }
        return read(prefix() + key)
                .or(() -> defaultRegion() ? read(key) : Optional.empty());
    }

    private Optional<V> read(String key) {
        if (delegate instanceof AccountAwareStorageBackend<V> aware) {
            String account = aware.accountId();
            return aware.getForAccount(account, key).or(() -> aware.getForAccountMigratingLegacy(
                    account, key, ignored -> aware.getLegacyForAccount(account, key).isPresent()));
        }
        return delegate.get(key);
    }

    @Override
    public void delete(String key) {
        if (logicalKey(key)) {
            if (defaultRegion()) {
                delegate.delete(key);
            }
            delegate.delete(prefix() + key);
        }
    }

    @Override
    public List<V> scan(Predicate<String> keyFilter) {
        List<V> values = new ArrayList<>();
        for (String key : keys()) {
            if (keyFilter.test(key)) {
                get(key).ifPresent(values::add);
            }
        }
        return values;
    }

    @Override
    public Set<String> keys() {
        String prefix = prefix();
        boolean includeLegacy = defaultRegion();
        Set<String> keys = new HashSet<>();
        for (String stored : delegate.keys()) {
            if (stored.startsWith(prefix)) {
                String key = stored.substring(prefix.length());
                if (logicalKey(key)) {
                    keys.add(key);
                }
            } else if (includeLegacy && logicalKey(stored)) {
                keys.add(stored);
            }
        }
        return keys;
    }

    record ScopedKey(String accountId, String region, String key) {}

    /** Enumerates owners for background work without borrowing the request's account or region. */
    Set<ScopedKey> scopedKeys(Predicate<V> values) {
        List<AccountEntry<V>> entries;
        if (delegate instanceof AccountAwareStorageBackend<V> aware) {
            entries = aware.scanAllAccountEntries(ignored -> true);
        } else {
            entries = new ArrayList<>();
            for (String key : delegate.keys()) {
                delegate.get(key).ifPresent(value -> entries.add(new AccountEntry<>(regions.getAccountId(), key, value)));
            }
        }
        Set<ScopedKey> keys = new HashSet<>();
        for (AccountEntry<V> entry : entries) {
            if (!values.test(entry.value())) {
                continue;
            }
            int slash = entry.key().indexOf('/');
            String region = slash < 0 ? regions.getDefaultRegion() : entry.key().substring(0, slash);
            String key = slash < 0 ? entry.key() : entry.key().substring(slash + 1);
            if (!region.isBlank() && logicalKey(key)) {
                keys.add(new ScopedKey(entry.accountId(), region, key));
            }
        }
        return keys;
    }

    @Override
    public void clear() {
        keys().forEach(this::delete);
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void load() {
        delegate.load();
    }

    private String prefix() {
        return regions.getRegion() + "/";
    }

    private boolean defaultRegion() {
        return regions.getRegion().equals(regions.getDefaultRegion());
    }

    private static boolean logicalKey(String key) {
        // Resource IDs, configuration tokens and the service's "::" composite keys contain no slash.
        return key != null && !key.contains("/");
    }
}
