package io.github.hectorvent.floci.services.s3;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;

class S3KmsCipherTest {
    @Test
    void authenticatedFramesRejectTamperingTruncationAndWrongSizes() throws Exception {
        byte[] key = new byte[32];
        byte[] prefix = new byte[S3KmsCipher.PREFIX_SIZE];
        new Random(42).nextBytes(key);
        new Random(43).nextBytes(prefix);
        for (int size : new int[] {0, 1, 65535, 65536, 65537, 131072, 2_000_000}) {
            byte[] plain = new byte[size];
            new Random(size).nextBytes(plain);
            byte[] stored;
            try (InputStream in = S3KmsCipher.encrypt(new ByteArrayInputStream(plain), key, prefix)) {
                stored = in.readAllBytes();
            }
            if (stored.length != S3KmsCipher.storedSize(size)) {
                throw new AssertionError("Stored length");
            }
            try (InputStream in = S3KmsCipher.decrypt(new ByteArrayInputStream(stored), key, prefix, size)) {
                if (!Arrays.equals(plain, in.readAllBytes())) {
                    throw new AssertionError("Round trip");
                }
            }
            expectFailure(Arrays.copyOf(stored, stored.length - 1), key, prefix, size);
            byte[] corrupt = stored.clone();
            corrupt[corrupt.length - 1] ^= 1;
            expectFailure(corrupt, key, prefix, size);
            byte[] trailing = Arrays.copyOf(stored, stored.length + 1);
            expectFailure(trailing, key, prefix, size);
            if (size > 0) {
                expectFailure(stored, key, prefix, size - 1);
                corrupt = stored.clone();
                corrupt[4] ^= 1;
                expectFailure(corrupt, key, prefix, size);
                try (InputStream in = S3KmsCipher.decrypt(new ByteArrayInputStream(stored), key, prefix, size)) {
                    int offset = size / 2;
                    in.skipNBytes(offset);
                    if (!Arrays.equals(Arrays.copyOfRange(plain, offset, size), in.readAllBytes())) {
                        throw new AssertionError("Range");
                    }
                }
            }
        }
        // A content-length-bounded caller must authenticate EOF even if it never asks for another byte.
        byte[] stored;
        try (InputStream in = S3KmsCipher.encrypt(new ByteArrayInputStream(new byte[65536]), key, prefix)) {
            stored = in.readAllBytes();
        }
        try (InputStream in = S3KmsCipher.decrypt(
                new ByteArrayInputStream(Arrays.copyOf(stored, stored.length - 20)), key, prefix, 65536)) {
            try {
                in.readNBytes(65536);
                throw new AssertionError("Missing final tag was accepted by bounded read");
            } catch (IOException expected) {
                // Expected before releasing the last authenticated data chunk.
            }
        }
    }

    private static void expectFailure(byte[] stored, byte[] key, byte[] prefix, long size) throws IOException {
        try (InputStream in = S3KmsCipher.decrypt(new ByteArrayInputStream(stored), key, prefix, size)) {
            try {
                in.readAllBytes();
                throw new AssertionError("Invalid ciphertext accepted");
            } catch (IOException expected) {
                // Authentication or framing refusal is required.
            }
        }
    }
}
