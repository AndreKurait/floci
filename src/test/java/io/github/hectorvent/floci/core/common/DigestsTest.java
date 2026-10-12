package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DigestsTest {

    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void sha256MatchesTheFipsTestVector() {
        assertEquals(ABC_SHA256, HexFormat.of().formatHex(Digests.sha256("abc".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void sha256HexIsLowercaseHexOfTheDigest() {
        assertEquals(ABC_SHA256, Digests.sha256Hex("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sha256HexOfTextDigestsItsUtf8Bytes() {
        assertEquals(Digests.sha256Hex("é".getBytes(StandardCharsets.UTF_8)), Digests.sha256Hex("é"));
    }
}
