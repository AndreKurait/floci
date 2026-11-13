package io.github.hectorvent.floci.services.ssm;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/** The binary control-channel envelope used by the AWS SSM agent. */
record SsmAgentMessage(String type, UUID id, long createdDate, byte[] payload) {
    static final int HEADER_LENGTH = 116;
    static final int PAYLOAD_OFFSET = 120;
    static final int MAX_MESSAGE_BYTES = 256 * 1024;

    SsmAgentMessage {
        if (type == null || !type.matches("[a-z_]{1,32}") || id == null || createdDate <= 0
                || payload == null || payload.length > MAX_MESSAGE_BYTES - PAYLOAD_OFFSET) {
            throw new IllegalArgumentException("Invalid SSM control-channel message.");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    static SsmAgentMessage create(String type, byte[] payload) {
        return new SsmAgentMessage(type, UUID.randomUUID(), Instant.now().toEpochMilli(), payload);
    }

    byte[] encode() {
        byte[] bytes = new byte[PAYLOAD_OFFSET + payload.length];
        Arrays.fill(bytes, 4, 36, (byte) ' ');
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.putInt(HEADER_LENGTH);
        buffer.put(type.getBytes(StandardCharsets.US_ASCII));
        buffer.position(36);
        buffer.putInt(1);
        buffer.putLong(createdDate);
        buffer.putLong(0);
        buffer.putLong(0);
        // The agent writes the UUID's least-significant 64-bit word first.
        buffer.putLong(id.getLeastSignificantBits());
        buffer.putLong(id.getMostSignificantBits());
        buffer.put(digest(payload));
        buffer.putInt(0);
        buffer.putInt(payload.length);
        buffer.put(payload);
        return bytes;
    }

    static SsmAgentMessage decode(byte[] bytes) {
        if (bytes == null || bytes.length < PAYLOAD_OFFSET || bytes.length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Invalid SSM control-channel message length.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != HEADER_LENGTH) {
            throw new IllegalArgumentException("Invalid SSM control-channel header length.");
        }
        byte[] type = new byte[32];
        buffer.get(type);
        if (buffer.getInt() != 1) {
            throw new IllegalArgumentException("Unsupported SSM control-channel schema version.");
        }
        long createdDate = buffer.getLong();
        buffer.position(64);
        long leastSignificant = buffer.getLong();
        UUID id = new UUID(buffer.getLong(), leastSignificant);
        byte[] expectedDigest = new byte[32];
        buffer.get(expectedDigest);
        buffer.getInt();
        if (buffer.getInt() != bytes.length - PAYLOAD_OFFSET) {
            throw new IllegalArgumentException("Invalid SSM control-channel payload length.");
        }
        byte[] payload = Arrays.copyOfRange(bytes, PAYLOAD_OFFSET, bytes.length);
        if (!MessageDigest.isEqual(expectedDigest, digest(payload))) {
            throw new IllegalArgumentException("Invalid SSM control-channel payload digest.");
        }
        return new SsmAgentMessage(new String(type, StandardCharsets.US_ASCII).trim(),
                id, createdDate, payload);
    }

    private static byte[] digest(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("The JVM does not provide SHA-256.", impossible);
        }
    }
}
