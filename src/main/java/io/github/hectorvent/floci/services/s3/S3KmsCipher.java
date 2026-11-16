package io.github.hectorvent.floci.services.s3;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Floci's internal, versioned SSE-KMS body framing, not an AWS wire or storage format.
 * Each bounded chunk is authenticated before any of its plaintext is returned. A final authenticated
 * empty chunk distinguishes EOF from truncation, including for an empty object. The random 96-bit
 * per-body prefix and 32-bit chunk counter form a unique GCM IV under the upload's data key.
 */
final class S3KmsCipher {

    static final int CHUNK_SIZE = 64 * 1024;
    static final int PREFIX_SIZE = 12;
    private static final int TAG_SIZE = 16;
    private static final int VERSION = 1;

    private S3KmsCipher() {
    }

    static long storedSize(long plaintextSize) {
        if (plaintextSize < 0) {
            throw new IllegalArgumentException("Negative plaintext size");
        }
        long chunks = plaintextSize / CHUNK_SIZE + (plaintextSize % CHUNK_SIZE == 0 ? 0 : 1) + 1;
        return Math.addExact(plaintextSize, Math.multiplyExact(chunks, TAG_SIZE + Integer.BYTES));
    }

    static InputStream encrypt(InputStream plaintext, byte[] dataKey, byte[] noncePrefix) {
        return new FramedStream(plaintext, dataKey, noncePrefix, true, -1);
    }

    static InputStream decrypt(InputStream ciphertext, byte[] dataKey, byte[] noncePrefix, long plaintextSize) {
        if (plaintextSize < 0) {
            throw new IllegalArgumentException("Negative plaintext size");
        }
        return new FramedStream(ciphertext, dataKey, noncePrefix, false, plaintextSize);
    }

    private static final class FramedStream extends InputStream {
        private final InputStream source;
        private final byte[] key;
        private final byte[] prefix;
        private final boolean encrypting;
        private InputStream chunk = InputStream.nullInputStream();
        private long counter;
        private boolean finished;
        private boolean closed;
        private boolean failed;
        private long remaining;

        FramedStream(InputStream source, byte[] key, byte[] prefix, boolean encrypting, long size) {
            Objects.requireNonNull(source);
            if (key.length != 32 || prefix.length != PREFIX_SIZE) {
                throw new IllegalArgumentException("Invalid SSE-KMS envelope material");
            }
            this.source = source;
            this.key = key.clone();
            this.prefix = prefix.clone();
            this.encrypting = encrypting;
            this.remaining = size;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (closed || failed) {
                throw new IOException("SSE-KMS stream is closed or failed authentication");
            }
            if (length == 0) {
                return 0;
            }
            try {
                while (true) {
                    int count = chunk.read(bytes, offset, length);
                    if (count >= 0) {
                        return count;
                    }
                    if (finished) {
                        return -1;
                    }
                    nextChunk();
                }
            } catch (IOException | RuntimeException error) {
                Arrays.fill(key, (byte) 0);
                finished = true;
                failed = true;
                throw error;
            }
        }

        private void nextChunk() throws IOException {
            if (counter > 0xffff_ffffL) {
                throw new IOException("SSE-KMS body exceeds the chunk counter limit");
            }
            if (encrypting) {
                byte[] plaintext = source.readNBytes(CHUNK_SIZE);
                byte[] encrypted;
                try {
                    encrypted = crypt(Cipher.ENCRYPT_MODE, plaintext, plaintext.length);
                } finally {
                    Arrays.fill(plaintext, (byte) 0);
                }
                byte[] framed = ByteBuffer.allocate(Integer.BYTES + encrypted.length)
                        .putInt(encrypted.length - TAG_SIZE).put(encrypted).array();
                chunk = new ByteArrayInputStream(framed);
                finished = encrypted.length == TAG_SIZE;
            } else {
                byte[] header = source.readNBytes(Integer.BYTES);
                if (header.length != Integer.BYTES) {
                    throw new IOException("Truncated SSE-KMS chunk header");
                }
                int size = ByteBuffer.wrap(header).getInt();
                if (size < 0 || size > CHUNK_SIZE) {
                    throw new IOException("Invalid SSE-KMS chunk length");
                }
                byte[] ciphertext = source.readNBytes(size + TAG_SIZE);
                if (ciphertext.length != size + TAG_SIZE) {
                    throw new IOException("Truncated SSE-KMS authenticated chunk");
                }
                byte[] plaintext = crypt(Cipher.DECRYPT_MODE, ciphertext, size);
                if (size > remaining || size == 0 && remaining != 0) {
                    throw new IOException("SSE-KMS body differs from its declared size");
                }
                remaining -= size;
                if (remaining == 0 && size != 0) {
                    counter++;
                    byte[] terminal = source.readNBytes(Integer.BYTES + TAG_SIZE);
                    if (terminal.length != Integer.BYTES + TAG_SIZE
                            || ByteBuffer.wrap(terminal).getInt() != 0) {
                        throw new IOException("Missing SSE-KMS authenticated EOF");
                    }
                    crypt(Cipher.DECRYPT_MODE, Arrays.copyOfRange(terminal, Integer.BYTES, terminal.length), 0);
                }
                if (remaining == 0) {
                    if (source.read() != -1) {
                        throw new IOException("Trailing data after SSE-KMS authenticated EOF");
                    }
                    finished = true;
                }
                chunk = new ByteArrayInputStream(plaintext);
            }
            counter++;
            if (finished) {
                Arrays.fill(key, (byte) 0);
            }
        }

        private byte[] crypt(int mode, byte[] bytes, int size) throws IOException {
            byte[] iv = ByteBuffer.allocate(PREFIX_SIZE + Integer.BYTES)
                    .put(prefix).putInt((int) counter).array();
            byte[] authenticatedHeader = ByteBuffer.allocate(3 * Integer.BYTES)
                    .putInt(VERSION).putInt((int) counter).putInt(size).array();
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                cipher.updateAAD(authenticatedHeader);
                return cipher.doFinal(bytes);
            } catch (GeneralSecurityException error) {
                throw new IOException("SSE-KMS chunk authentication or encryption failed", error);
            }
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                Arrays.fill(key, (byte) 0);
                source.close();
            }
        }
    }
}
