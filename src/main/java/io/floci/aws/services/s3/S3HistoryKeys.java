package io.floci.aws.services.s3;

import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.s3.model.S3Object;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Version metadata lives outside the namespace of public object keys. */
final class S3HistoryKeys {
    static final String RESERVED_PREFIX = "\0version/";

    private S3HistoryKeys() {
    }

    static String prefix(String bucket) {
        return bucket + "/" + RESERVED_PREFIX;
    }

    static String key(String bucket, String objectKey, String version) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectKey.getBytes(StandardCharsets.UTF_8));
        return prefix(bucket) + encoded + "/" + version;
    }

    static void migrate(StorageBackend<String, S3Object> store) {
        if (store instanceof AccountAwareStorageBackend<?> aware) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<S3Object> typed = (AccountAwareStorageBackend<S3Object>) aware;
            for (Map.Entry<String, S3Object> entry : typed.scanAllAccountsWithRawKeys().entrySet()) {
                S3Object object = entry.getValue();
                String raw = entry.getKey();
                String legacy;
                String account;
                boolean unscoped;
                if (isLegacyHistory(raw, object)) {
                    legacy = raw;
                    account = typed.defaultAccountId();
                    unscoped = true;
                } else if (AccountAwareStorageBackend.hasAccountPrefix(raw)
                        && isLegacyHistory(raw.substring(13), object)) {
                    legacy = raw.substring(13);
                    account = raw.substring(0, 12);
                    unscoped = false;
                } else {
                    continue;
                }
                Optional<S3Object> migrated = typed.getForAccountMigratingLegacyKeys(
                        account, key(object.getBucketName(), object.getKey(), object.getVersionId()),
                        List.of(legacy), candidate -> sameIdentity(object, candidate), unscoped);
                if (migrated.isEmpty()) {
                    throw new IllegalStateException("S3 history migration destination has a different object identity");
                }
            }
            return;
        }
        Map<String, S3Object> entries = new LinkedHashMap<>();
        for (String storedKey : store.keys()) {
            store.get(storedKey).ifPresent(object -> entries.put(storedKey, object));
        }
        for (Map.Entry<String, S3Object> entry : entries.entrySet()) {
            S3Object object = entry.getValue();
            if (!isLegacyHistory(entry.getKey(), object)) {
                continue;
            }
            String destination = key(object.getBucketName(), object.getKey(), object.getVersionId());
            Optional<S3Object> existing = store.get(destination);
            if (existing.isPresent() && !sameIdentity(object, existing.get())) {
                throw new IllegalStateException("S3 history migration destination has a different object identity");
            }
            if (existing.isEmpty()) {
                store.put(destination, object);
            }
            store.delete(entry.getKey());
        }
    }

    private static boolean isLegacyHistory(String storedKey, S3Object object) {
        if (object.getBucketName() == null || object.getKey() == null || object.getVersionId() == null) {
            return false;
        }
        String current = object.getBucketName() + "/" + object.getKey();
        if (!storedKey.startsWith(current) || storedKey.length() == current.length()) {
            return false;
        }
        String suffix = "#v#" + object.getVersionId();
        int offset = current.length();
        // The old collision could re-archive this same version under its history-shaped key.
        // Match every suffix against the stored identity, never against user-key text alone.
        while (storedKey.startsWith(suffix, offset)) {
            offset += suffix.length();
        }
        return offset == storedKey.length();
    }

    private static boolean sameIdentity(S3Object first, S3Object second) {
        return Objects.equals(first.getBucketName(), second.getBucketName())
                && Objects.equals(first.getKey(), second.getKey())
                && Objects.equals(first.getVersionId(), second.getVersionId());
    }
}
