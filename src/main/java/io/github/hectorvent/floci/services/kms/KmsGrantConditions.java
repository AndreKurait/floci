package io.github.hectorvent.floci.services.kms;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Case-preserving internal condition names for KMS grant constraints. */
public final class KmsGrantConditions {
    private KmsGrantConditions() {
    }

    public static String encryptionContextKey(String key) {
        // IAM condition names ignore case; encoding preserves case-sensitive grant keys.
        return "floci:kms:EncryptionContext:" + HexFormat.of().formatHex(key.getBytes(StandardCharsets.UTF_8));
    }
}
