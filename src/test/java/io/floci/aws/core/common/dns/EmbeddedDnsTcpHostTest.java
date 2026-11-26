package io.floci.aws.core.common.dns;

import io.floci.aws.testing.ParallelLocks;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The native host listener retains its ownership boundary on TCP as well as UDP. */
@ResourceLock(value = ParallelLocks.PORTS, mode = ResourceAccessMode.READ)
class EmbeddedDnsTcpHostTest {

    @ParameterizedTest
    @CsvSource({"bucket.localhost.floci.io,0,1", "not-held.example.invalid,5,0"})
    void hostTcpAnswersOwnedNamesAndRefusesUnknownNames(String name, int rcode, int answers) throws Exception {
        Vertx vertx = Vertx.vertx();
        EmbeddedDnsServer dns = new EmbeddedDnsServer(List.of());
        EmbeddedDnsServer.Listeners listeners = null;
        try {
            listeners = dns.bindAll(vertx, EmbeddedDnsServer.LOOPBACK, 0, false)
                    .toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(Optional.empty(), dns.getServerIp(), "The host listener is not given to containers");
            try (Socket socket = new Socket(EmbeddedDnsServer.LOOPBACK, listeners.tcp().actualPort())) {
                socket.setSoTimeout(5000);
                byte[] query = DnsTestQuery.query(name, 1, 0x4321);
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.writeShort(query.length);
                output.write(query);
                output.flush();
                DataInputStream input = new DataInputStream(socket.getInputStream());
                ByteBuffer response = ByteBuffer.wrap(input.readNBytes(input.readUnsignedShort()));
                assertEquals((short) 0x4321, response.getShort(0));
                assertEquals(rcode, response.getShort(2) & 0x000F);
                assertEquals(answers, response.getShort(6));
            }
        } finally {
            try {
                if (listeners != null) {
                    listeners.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                }
            } finally {
                vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }
}
