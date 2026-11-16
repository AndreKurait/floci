package io.github.hectorvent.floci.services.kms;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;

/** Internal condition names for cryptographic KMS grant constraints. */
public final class KmsGrantConditions {
    private KmsGrantConditions() {
    }

    public static String encryptionContextKey(String key) {
        // Cryptographic grant constraint keys ignore case; values remain literal.
        return "floci:kms:EncryptionContext:" + HexFormat.of().formatHex(key.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }
}
