package io.github.hectorvent.floci.services.redshift.proxy;

import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.PostgresWire;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.rds.proxy.PasswordValidator;
import io.github.hectorvent.floci.services.rds.proxy.RdsProxyTlsCertificates;
import io.github.hectorvent.floci.services.rds.proxy.RdsSigV4Validator;
import io.github.hectorvent.floci.services.redshift.RedshiftAuditLogPublisher;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.TestTime;
import io.github.hectorvent.floci.testutil.FreePorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedshiftAuthProxyTest {

    private static final int BIND_ATTEMPTS = 40;
    private static final Duration BIND_RETRY_PAUSE = Duration.ofMillis(25);
    /** The last bind attempt comes after one pause for each attempt before it: 975 ms in. */
    private static final Duration LAST_BIND_ATTEMPT_AT = BIND_RETRY_PAUSE.multipliedBy(BIND_ATTEMPTS - 1);

    @Test
    void failedLoginAuditsTheRequestedUserAndDatabase() throws Exception {
        RedshiftAuditLogPublisher audit = mock(RedshiftAuditLogPublisher.class);
        int port = FreePorts.anyInterfacePort();
        proxy = new RedshiftAuthProxy("111111111111:c1", "localhost", 1, "admin", "password", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, password) -> PasswordValidator.AuthResult.REJECT,
                mock(S3Service.class), mock(IamService.class), "111111111111", List.of(), 5000, 5000, 10, null, audit, null);
        proxy.start(port);
        try (Socket client = new Socket("localhost", port)) {
            client.setSoTimeout(5000);
            client.getOutputStream().write(PostgresWire.startupMessage(Map.of("user", "analyst", "database", "analytics")));
            client.getOutputStream().flush();
            assertEquals(9, client.getInputStream().readNBytes(9).length);
            PostgresWire.write(client.getOutputStream(), 'p', "bad\0".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            assertEquals('E', client.getInputStream().read());
            verify(audit, timeout(2000)).publishRegisteredConnection(eq("111111111111"), eq("c1"), eq("analytics"),
                    eq("analyst"), eq("authentication failure"), anyString(), anyInt());
        }
    }

    @Test
    void successfulLoginAndActivityUseTheRequestedIdentity() throws Exception {
        fakeBackend = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            try (Socket backend = fakeBackend.accept()) {
                PostgresWire.readStartup(backend.getInputStream(), 10000, "startup");
                PostgresWire.write(backend.getOutputStream(), 'R', new byte[4]);
                PostgresWire.write(backend.getOutputStream(), 'Z', new byte[]{'I'});
                backend.getOutputStream().flush();
                PostgresWire.read(backend.getInputStream(), 10000, "query");
            } catch (IOException expected) {
                // Closing the test sockets ends the fake server.
            }
        });
        RedshiftAuditLogPublisher audit = mock(RedshiftAuditLogPublisher.class);
        int port = FreePorts.anyInterfacePort();
        proxy = new RedshiftAuthProxy("111111111111:c1", "localhost", fakeBackend.getLocalPort(), "admin", "password", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, password) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT,
                mock(S3Service.class), mock(IamService.class), "111111111111", List.of(), 5000, 5000, 10, null, audit, null);
        proxy.start(port);
        try (Socket client = new Socket("localhost", port)) {
            client.setSoTimeout(5000);
            client.getOutputStream().write(PostgresWire.startupMessage(Map.of("user", "analyst", "database", "analytics")));
            client.getOutputStream().flush();
            assertEquals(9, client.getInputStream().readNBytes(9).length);
            PostgresWire.write(client.getOutputStream(), 'p', "password\0".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            assertEquals('R', PostgresWire.read(client.getInputStream(), 10000, "authenticated").type());
            assertEquals('Z', PostgresWire.read(client.getInputStream(), 10000, "ready").type());
            PostgresWire.write(client.getOutputStream(), 'Q', "SELECT 1\0".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            verify(audit, timeout(2000)).publishRegisteredActivity("111111111111", "c1", "analytics", "analyst", "SELECT 1");
            verify(audit, timeout(2000)).publishRegisteredConnection(eq("111111111111"), eq("c1"), eq("analytics"),
                    eq("analyst"), eq("authenticated"), anyString(), anyInt());
        }
    }

    @TempDir
    Path tempDir;

    private RedshiftAuthProxy proxy;
    private ServerSocket fakeBackend;

    @AfterEach
    void tearDown() throws IOException {
        if (proxy != null) {
            proxy.stop();
        }
        if (fakeBackend != null && !fakeBackend.isClosed()) {
            fakeBackend.close();
        }
    }

    @Test
    void bridgesClientBytesToTheBackendAfterASuccessfulPasswordAuth() throws Exception {
        // The fake backend accepts one connection and records the first bytes it receives
        // from the proxy (the forwarded PostgreSQL startup packet).
        fakeBackend = new ServerSocket(0);
        AtomicReference<byte[]> seenByBackend = new AtomicReference<>();
        CountDownLatch backendDone = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try (Socket s = fakeBackend.accept()) {
                InputStream in = s.getInputStream();
                byte[] buf = new byte[256];
                int n = in.read(buf);
                byte[] out = new byte[Math.max(n, 0)];
                System.arraycopy(buf, 0, out, 0, out.length);
                seenByBackend.set(out);
                backendDone.countDown();
            } catch (IOException ignored) {
                backendDone.countDown();
            }
        });

        int proxyPort = FreePorts.anyInterfacePort();
        proxy = new RedshiftAuthProxy("111111111111:c1", "localhost", fakeBackend.getLocalPort(),
                "admin", "Secret123", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, pw) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT,
                mock(S3Service.class), mock(IamService.class), 5000, 5000, 100);
        proxy.start(proxyPort);

        try (Socket client = new Socket("localhost", proxyPort)) {
            // Minimal PostgreSQL v3 startup: length(4) + protocol(4) + "user\0admin\0\0"
            OutputStream out = client.getOutputStream();
            byte[] params = "user\0admin\0\0".getBytes(StandardCharsets.UTF_8);
            int len = 8 + params.length;
            out.write(new byte[]{(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len});
            out.write(new byte[]{0, 3, 0, 0}); // protocol 196608
            out.write(params);
            out.flush();

            // Read the AuthenticationCleartextPassword request ('R') and reply with a password message.
            InputStream in = client.getInputStream();
            assertEquals('R', in.read());
            in.readNBytes(7); // remaining length(4) + auth code(3 of the 4-byte int)

            OutputStream pw = client.getOutputStream();
            byte[] pwBytes = "Secret123\0".getBytes(StandardCharsets.UTF_8);
            pw.write('p');
            int pwLen = 4 + pwBytes.length;
            pw.write(new byte[]{(byte) (pwLen >>> 24), (byte) (pwLen >>> 16), (byte) (pwLen >>> 8), (byte) pwLen});
            pw.write(pwBytes);
            pw.flush();

            assertTrue(backendDone.await(5, TimeUnit.SECONDS), "backend never saw the forwarded startup packet");
            byte[] forwarded = seenByBackend.get();
            assertTrue(forwarded != null && forwarded.length > 0, "proxy forwarded no bytes to the backend");
        }
    }

    @Test
    void neverConnectsToTheBackendWhenTheClientDropsBeforeSendingAStartupPacket() throws Exception {
        // The backend connection is opened only after the client's startup message has been
        // validated; a client that vanishes beforehand must never cause a backend connection.
        fakeBackend = new ServerSocket(0);
        fakeBackend.setSoTimeout(500);

        int proxyPort = FreePorts.anyInterfacePort();
        proxy = new RedshiftAuthProxy("111111111111:c1", "localhost", fakeBackend.getLocalPort(),
                "admin", "Secret123", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, pw) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT,
                mock(S3Service.class), mock(IamService.class), 5000, 5000, 100);
        proxy.start(proxyPort);

        // Connect, then drop without ever sending a startup packet.
        new Socket("localhost", proxyPort).close();

        assertThrows(SocketTimeoutException.class, fakeBackend::accept,
                "proxy connected to the backend before validating the client's startup message");
    }

    @Test
    void idleClientIsDroppedAfterTheConfiguredHandshakeTimeout() throws Exception {
        fakeBackend = new ServerSocket(0);
        int proxyPort = FreePorts.anyInterfacePort();
        proxy = new RedshiftAuthProxy("111111111111:c1", "localhost", fakeBackend.getLocalPort(),
                "admin", "Secret123", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, pw) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT,
                mock(S3Service.class), mock(IamService.class), 200, 5000, 100);
        proxy.start(proxyPort);

        try (Socket client = new Socket("localhost", proxyPort)) {
            client.setSoTimeout(5000);
            // Send nothing; the proxy must drop the connection once the handshake timeout
            // elapses. The client observes this as EOF once the proxy closes its end.
            int result = client.getInputStream().read();
            assertEquals(-1, result, "proxy did not drop an idle client after the handshake timeout");
        }
    }

    @Test
    void refusesAConnectionBeyondTheConfiguredLimit() throws Exception {
        fakeBackend = new ServerSocket(0);
        int proxyPort = FreePorts.anyInterfacePort();
        proxy = new RedshiftAuthProxy("111111111111:c1", "localhost", fakeBackend.getLocalPort(),
                "admin", "Secret123", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, pw) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT,
                mock(S3Service.class), mock(IamService.class), 5000, 5000, 1);
        proxy.start(proxyPort);

        // Hold the proxy's single connection permit from the test thread itself, so the
        // refusal below is deterministic rather than a race against a real first connection.
        Field permitsField = RedshiftAuthProxy.class.getDeclaredField("connectionPermits");
        permitsField.setAccessible(true);
        Semaphore permits = (Semaphore) permitsField.get(proxy);
        assertTrue(permits.tryAcquire(), "expected the single configured permit to be available");

        try (Socket client = new Socket("localhost", proxyPort)) {
            int result = client.getInputStream().read();
            assertEquals(-1, result, "connection was not refused once the connection limit was reached");
        } finally {
            permits.release();
        }
    }

    @Test
    void activeConnectionsCountsOpenClientsAndFallsOnClose() throws Exception {
        fakeBackend = new ServerSocket(0);
        int proxyPort = FreePorts.anyInterfacePort();
        proxy = new RedshiftAuthProxy("111111111111:c1", "localhost", fakeBackend.getLocalPort(),
                "admin", "Secret123", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, pw) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT,
                mock(S3Service.class), mock(IamService.class), 5000, 5000, 5);
        proxy.start(proxyPort);
        assertEquals(0, proxy.activeConnections());

        try (Socket first = new Socket("localhost", proxyPort); Socket second = new Socket("localhost", proxyPort)) {
            awaitActiveConnections(2);
            first.close();
            awaitActiveConnections(1);
        }
        awaitActiveConnections(0);
    }

    private void awaitActiveConnections(int expected) {
        await().atMost(Duration.ofSeconds(5)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(1))
                .until(proxy::activeConnections, equalTo(expected));
    }

    @Test
    void startRetriesTheBindWhileThePortIsMomentarilyStillInUse() throws Exception {
        TestTime time = TestTime.create();
        fakeBackend = new ServerSocket(0);
        int proxyPort = FreePorts.anyInterfacePort();
        AtomicInteger bindAttempts = new AtomicInteger();

        // Hold the port, mimicking a just-closed predecessor the kernel has not released
        // yet; free it 200 ms into the retry window so the retrying bind can finally take it.
        ServerSocket squatter = squatOn(proxyPort);
        time.scheduler().schedule(() -> closeSquatter(squatter), 200, TimeUnit.MILLISECONDS);

        proxy = proxyRetryingTheBindOn(time, bindAttempts);
        proxy.start(proxyPort); // must not throw despite the port being busy at first

        assertEquals(Duration.ofMillis(200), Duration.between(TestTime.DEFAULT_START, time.now()),
                "the bind is retried every 25 ms until the port is free");
        assertEquals(9, bindAttempts.get(), "eight attempts find the port busy, the ninth binds it");
        assertPortAccepts(proxyPort);
    }

    @Test
    void startBindsAPortThatIsFreedJustBeforeTheLastAttempt() throws Exception {
        TestTime time = TestTime.create();
        fakeBackend = new ServerSocket(0);
        int proxyPort = FreePorts.anyInterfacePort();
        AtomicInteger bindAttempts = new AtomicInteger();
        ServerSocket squatter = squatOn(proxyPort);
        time.scheduler().schedule(() -> closeSquatter(squatter),
                LAST_BIND_ATTEMPT_AT.toMillis(), TimeUnit.MILLISECONDS);

        proxy = proxyRetryingTheBindOn(time, bindAttempts);
        proxy.start(proxyPort);

        assertEquals(LAST_BIND_ATTEMPT_AT, Duration.between(TestTime.DEFAULT_START, time.now()));
        assertEquals(BIND_ATTEMPTS, bindAttempts.get());
        assertPortAccepts(proxyPort);
    }

    @Test
    void startFailsWithTheBindFailureWhenThePortIsStillInUseAtTheLastAttempt() throws Exception {
        TestTime time = TestTime.create();
        fakeBackend = new ServerSocket(0);
        int proxyPort = FreePorts.anyInterfacePort();
        AtomicInteger bindAttempts = new AtomicInteger();
        ServerSocket squatter = squatOn(proxyPort);
        try {
            // One retry pause later than the last attempt: nothing is left to take the freed port.
            time.scheduler().schedule(() -> closeSquatter(squatter),
                    LAST_BIND_ATTEMPT_AT.plus(BIND_RETRY_PAUSE).toMillis(), TimeUnit.MILLISECONDS);
            RedshiftAuthProxy retrying = proxyRetryingTheBindOn(time, bindAttempts);

            assertThrows(BindException.class, () -> retrying.start(proxyPort));

            assertEquals(BIND_ATTEMPTS, bindAttempts.get());
            assertEquals(0, time.pendingTasks(), "the port was freed only after the last attempt had failed");
        } finally {
            closeSquatter(squatter);
        }
    }

    @Test
    void startDoesNotRetryAFailureThatIsNotABusyPort() {
        TestTime time = TestTime.create();
        AtomicInteger bindAttempts = new AtomicInteger();
        IOException refused = new IOException("not a bind failure");
        RedshiftAuthProxy failing = new RedshiftAuthProxy("111111111111:c1", "localhost", 1,
                "admin", "Secret123", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, pw) -> PasswordValidator.AuthResult.REJECT,
                mock(S3Service.class), mock(IamService.class), 5000, 5000, 100) {
            @Override
            ServerSocket listenSocket(int proxyPort, int backlog) throws IOException {
                bindAttempts.incrementAndGet();
                throw refused;
            }

            @Override
            void pauseBeforeBindRetry(long millis) {
                time.sleepAdvancing(Duration.ofMillis(millis));
            }
        };

        assertSame(refused, assertThrows(IOException.class, () -> failing.start(0)));

        assertEquals(1, bindAttempts.get());
        assertEquals(TestTime.DEFAULT_START, time.now(), "no retry pause was taken");
    }

    /** A proxy whose pause between bind attempts passes on {@code time} and whose attempts are counted. */
    private RedshiftAuthProxy proxyRetryingTheBindOn(TestTime time, AtomicInteger bindAttempts) {
        return new RedshiftAuthProxy("111111111111:c1", "localhost", fakeBackend.getLocalPort(),
                "admin", "Secret123", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, pw) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT,
                mock(S3Service.class), mock(IamService.class), 5000, 5000, 100) {
            @Override
            ServerSocket listenSocket(int proxyPort, int backlog) throws IOException {
                bindAttempts.incrementAndGet();
                return super.listenSocket(proxyPort, backlog);
            }

            @Override
            void pauseBeforeBindRetry(long millis) {
                time.sleepAdvancing(Duration.ofMillis(millis));
            }
        };
    }

    private static ServerSocket squatOn(int port) throws IOException {
        ServerSocket squatter = new ServerSocket();
        squatter.setReuseAddress(true);
        squatter.bind(new InetSocketAddress(port));
        return squatter;
    }

    private static void closeSquatter(ServerSocket squatter) {
        try {
            squatter.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void updateMasterPasswordSwapsTheSnapshotUsedForNewConnections() throws Exception {
        fakeBackend = new ServerSocket(0);
        int proxyPort = FreePorts.anyInterfacePort();
        proxy = new RedshiftAuthProxy("111111111111:c1", "localhost", fakeBackend.getLocalPort(),
                "admin", "old", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, pw) -> PasswordValidator.AuthResult.MASTER_EQUIVALENT,
                mock(S3Service.class), mock(IamService.class), 5000, 5000, 100);
        proxy.start(proxyPort);

        proxy.updateMasterPassword("rotated");

        Field f = RedshiftAuthProxy.class.getDeclaredField("masterPassword");
        f.setAccessible(true);
        assertEquals("rotated", f.get(proxy));
    }

    @Test
    void bindsWithABurstTolerantAcceptBacklog() throws Exception {
        AtomicInteger acceptBacklog = new AtomicInteger();
        proxy = new RedshiftAuthProxy("111111111111:c1", "localhost", 1,
                "admin", "Secret123", "dev",
                mock(RdsSigV4Validator.class), realTls(), (user, pw) -> PasswordValidator.AuthResult.REJECT,
                mock(S3Service.class), mock(IamService.class), 5000, 5000, 100) {
            @Override
            ServerSocket listenSocket(int proxyPort, int backlog) throws IOException {
                acceptBacklog.set(backlog);
                return super.listenSocket(proxyPort, backlog);
            }
        };
        proxy.start(0);
        assertEquals(4096, acceptBacklog.get(),
                "a connection burst must not overflow the proxy's accept queue");
    }

    private RdsProxyTlsCertificates realTls() {
        return new RdsProxyTlsCertificates(FlociCertificateAuthority.loadOrCreate(tempDir.resolve("tls")),
                new CertificateGenerator());
    }

    /** {@code start} has bound the listener by the time it returns, so the first connect is accepted. */
    private static void assertPortAccepts(int port) throws IOException {
        try (Socket client = new Socket("localhost", port)) {
            assertTrue(client.isConnected());
        }
    }
}
