package io.github.hectorvent.floci.services.ssm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SsmAgentMessageTest {
    // AWS agent contracts/agentmessage.go: big-endian fields, space padding, UUID low word first.
    private static final byte[] FRAME = HexFormat.of().parseHex("""
            00000074
            6167656e745f6a6f62 2020202020202020 2020202020202020 20202020202020
            00000001 0000000000000001 0000000000000000 0000000000000000
            8899aabbccddeeff 0011223344556677
            44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a
            00000000 00000002 7b7d
            """.replaceAll("\\s", ""));

    @Test
    void encodingMatchesTheAgentWireLayout() {
        SsmAgentMessage message = new SsmAgentMessage("agent_job",
                UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), 1,
                "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(122, FRAME.length);
        assertArrayEquals(FRAME, message.encode());
    }

    @Test
    void decodesTheAgentWireLayout() {
        SsmAgentMessage message = SsmAgentMessage.decode(FRAME);
        assertAll(
                () -> assertEquals("agent_job", message.type()),
                () -> assertEquals(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), message.id()),
                () -> assertEquals(1, message.createdDate()),
                () -> assertEquals("{}", new String(message.payload(), StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, 36, 80, 116, 120})
    void rejectsCorruptHeadersAndPayloads(int offset) {
        byte[] corrupt = FRAME.clone();
        corrupt[offset] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> SsmAgentMessage.decode(corrupt));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, 119, 262145})
    void rejectsIncompleteOrOversizedMessages(int length) {
        assertThrows(IllegalArgumentException.class, () -> SsmAgentMessage.decode(new byte[length]));
    }
}
