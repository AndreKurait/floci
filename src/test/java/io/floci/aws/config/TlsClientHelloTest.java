package io.floci.aws.config;

import io.netty.handler.codec.DecoderException;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLParameters;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsClientHelloTest {
    @Test
    void readsARealClientHelloSplitAcrossIndividualNetworkBytes() throws Exception {
        byte[] packet = clientHello("service.example.test");
        try (TlsClientHello hello = new TlsClientHello()) {
            for (int index = 0; index < packet.length; index++) {
                assertEquals(index == packet.length - 1, hello.append(Buffer.buffer(new byte[]{packet[index]})));
            }
            assertEquals("service.example.test", hello.hostname());
        }
    }

    @Test
    void readsAClientHelloAcrossMultipleTlsRecords() throws Exception {
        byte[] packet = clientHello("service.example.test");
        int middle = (packet.length - 5) / 2;
        Buffer first = record(packet, 5, middle);
        Buffer second = record(packet, 5 + middle, packet.length - 5 - middle);
        try (TlsClientHello hello = new TlsClientHello()) {
            assertFalse(hello.append(first));
            assertTrue(hello.append(second));
            assertEquals("service.example.test", hello.hostname());
        }
    }

    @Test
    void completesWithoutSelectingAHostnameWhenTheClientOmitsSni() throws Exception {
        try (TlsClientHello hello = new TlsClientHello()) {
            assertTrue(hello.append(Buffer.buffer(clientHello(null))));
            assertNull(hello.hostname());
        }
    }

    @Test
    void refusesOversizedHandshakeBeforeAllocatingItsDeclaredPayload() {
        byte[] packet = {22, 3, 3, 0, 4, 1, 1, 0, 1};
        try (TlsClientHello hello = new TlsClientHello()) {
            assertThrows(DecoderException.class, () -> hello.append(Buffer.buffer(packet)));
        }
    }

    @Test
    void discardsAnIncompleteHandshakeOnClose() {
        try (TlsClientHello hello = new TlsClientHello()) {
            assertFalse(hello.append(Buffer.buffer(new byte[]{22, 3})));
        }
    }

    private static Buffer record(byte[] original, int offset, int length) {
        return Buffer.buffer(new byte[]{22, original[1], original[2], (byte) (length >> 8), (byte) length})
                .appendBytes(original, offset, length);
    }

    private static byte[] clientHello(String hostname) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);
        SSLEngine engine = context.createSSLEngine();
        engine.setUseClientMode(true);
        SSLParameters parameters = engine.getSSLParameters();
        parameters.setServerNames(hostname == null ? List.of() : List.of(new SNIHostName(hostname)));
        engine.setSSLParameters(parameters);
        engine.beginHandshake();
        ByteBuffer packet = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        SSLEngineResult result = engine.wrap(ByteBuffer.allocate(0), packet);
        assertEquals(SSLEngineResult.Status.OK, result.getStatus());
        packet.flip();
        byte[] bytes = new byte[packet.remaining()];
        packet.get(bytes);
        return bytes;
    }
}
