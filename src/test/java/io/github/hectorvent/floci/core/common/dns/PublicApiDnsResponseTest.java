package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.netty.util.NetUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicApiDnsResponseTest {
    private static final String UPSTREAM = "172.18.4.1";
    private static final String PUBLIC = EmulatorConfig.VpcRoutingConfig.PUBLIC_AWS_API_ADDRESS;
    private final PublicApiDnsResponse rewriter = new PublicApiDnsResponse(UPSTREAM);

    @Test
    void changesOnlySelectedAnswerAddressesAndPreservesTheOriginalPacket() throws Exception {
        byte[] original = response(UPSTREAM, 0);
        byte[] saved = original.clone();
        assertArrayEquals(response(PUBLIC, 0), rewriter.rewrite(original));
        assertArrayEquals(saved, original);
    }

    @Test
    void unrelatedAnswersAndMatchingAuthorityOrAdditionalRecordsRemainUnchanged() throws Exception {
        byte[] original = response("198.51.100.9", 0);
        assertSame(original, rewriter.rewrite(original));
    }

    @ParameterizedTest
    @ValueSource(ints = {0x8183, 0x8182, 0x8380, 0x81a0, 0x0180, 0xa980, 0x81c0})
    void negativeTruncatedAuthenticatedAndNonQueryAnswersRemainUnchanged(int flags) throws Exception {
        byte[] original = response(UPSTREAM, 0);
        ByteBuffer.wrap(original).putShort(2, (short) flags);
        assertSame(original, rewriter.rewrite(original));
    }

    @ParameterizedTest
    @ValueSource(ints = {24, 46, 250})
    void signaturesPreventChangingAnyRecord(int signatureType) throws Exception {
        byte[] original = response(UPSTREAM, signatureType);
        assertSame(original, rewriter.rewrite(original));
    }

    @Test
    void aTruncatedPacketCannotBePartiallyRewritten() throws Exception {
        byte[] complete = response(UPSTREAM, 0);
        for (int length = 0; length < complete.length; length++) {
            byte[] truncated = Arrays.copyOf(complete, length);
            assertSame(truncated, rewriter.rewrite(truncated), "truncated at " + length);
        }
        assertNull(rewriter.rewrite(null));
    }

    @Test
    void invalidCompressionAndMultipleQuestionsRemainUnchanged() throws Exception {
        byte[] invalid = response(UPSTREAM, 0);
        ByteBuffer.wrap(invalid).putShort(12, (short) 0xc00c);
        assertSame(invalid, rewriter.rewrite(invalid));
        byte[] multiple = response(UPSTREAM, 0);
        ByteBuffer.wrap(multiple).putShort(4, (short) 2);
        assertSame(multiple, rewriter.rewrite(multiple));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "peer.example", "127.0.0.1:443", "::1", "999.1.2.3"})
    void theConfiguredUpstreamMustBeAnIpv4Address(String invalid) {
        assertThrows(IllegalArgumentException.class, () -> new PublicApiDnsResponse(invalid));
    }

    private static byte[] response(String selected, int signatureType) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream packet = new DataOutputStream(bytes)) {
            packet.writeShort(67);
            packet.writeShort(0x8180);
            packet.writeShort(1);
            packet.writeShort(4);
            packet.writeShort(1);
            packet.writeShort(signatureType == 0 ? 1 : 2);
            packet.write(name("peer.example"));
            packet.writeShort(1);
            packet.writeShort(1);
            record(packet, null, 5, 45, name("alias.peer.example"));
            record(packet, "alias.peer.example", 1, 23, address(selected));
            record(packet, "other.example", 1, 19, address("198.51.100.77"));
            record(packet, null, 16, 11, new byte[]{4, (byte) 172, 18, 4, 1});
            record(packet, null, 1, 7, address(UPSTREAM));
            record(packet, null, 1, 5, address(UPSTREAM));
            if (signatureType != 0) {
                record(packet, null, signatureType, 0, new byte[8]);
            }
        }
        return bytes.toByteArray();
    }

    private static void record(DataOutputStream packet, String owner, int type, int ttl, byte[] data)
            throws IOException {
        if (owner == null) {
            packet.writeShort(0xc00c);
        } else {
            packet.write(name(owner));
        }
        packet.writeShort(type);
        packet.writeShort(1);
        packet.writeInt(ttl);
        packet.writeShort(data.length);
        packet.write(data);
    }

    private static byte[] address(String value) {
        return NetUtil.createByteArrayFromIpAddressString(value);
    }

    private static byte[] name(String value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            for (String label : value.split("\\.")) {
                byte[] encoded = label.getBytes(StandardCharsets.US_ASCII);
                data.writeByte(encoded.length);
                data.write(encoded);
            }
            data.writeByte(0);
        }
        return bytes.toByteArray();
    }
}
