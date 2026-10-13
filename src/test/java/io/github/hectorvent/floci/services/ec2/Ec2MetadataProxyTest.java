package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2MetadataProxyTest {

    @Test
    void installCommandContainsSupportedPackageManagers() {
        String[] command = Ec2MetadataProxy.installCommand();
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        assertTrue(script.contains("command -v ip >/dev/null 2>&1 && command -v socat >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then exit 0; fi"));
        assertTrue(script.contains("apt-get install -y --no-install-recommends iproute2 socat curl ca-certificates"));
        assertTrue(script.contains("dnf install -y --allowerasing iproute socat curl ca-certificates"));
        assertTrue(script.contains("yum install -y iproute socat curl ca-certificates"));
        assertTrue(script.contains("apk add --no-cache iproute2 socat curl ca-certificates"));
    }

    @Test
    void startCommandAttachesAddressIdempotentlyAndTargetsHostAndPort() {
        String[] command = Ec2MetadataProxy.startCommand("10.0.0.1", 9169);
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        // Attaches address if missing
        assertTrue(script.contains("ip addr show dev lo | grep -q '169.254.169.254/32' || ip addr add 169.254.169.254/32 dev lo"));
        // Idempotent when pid file exists and process is alive
        assertTrue(script.contains("if [ -f /tmp/floci-imds-proxy.pid ] && kill -0 \"$(cat /tmp/floci-imds-proxy.pid)\" 2>/dev/null; then\n  exit 0\nfi"));
        // Targets configured Floci host and IMDS port
        assertTrue(script.contains("nohup socat TCP-LISTEN:80,bind=169.254.169.254,fork,reuseaddr TCP:10.0.0.1:9169 >/tmp/floci-imds-proxy.log 2>&1 &"));
        // Verifies through an IMDSv2 token even when tokenless access is refused.
        assertTrue(script.contains("-X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60'"));
        assertTrue(script.contains("http://169.254.169.254/latest/api/token"));
        assertTrue(script.contains("-H \"X-aws-ec2-metadata-token: $token\" http://169.254.169.254/latest/meta-data/instance-id"));
    }

    @Test
    void podIdentityStartCommandAttachesAddressIdempotentlyAndTargetsHostAndPort() {
        String[] command = Ec2MetadataProxy.podIdentityStartCommand("10.0.0.1", 4566);
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        assertTrue(script.contains("ip addr show dev lo | grep -q '169.254.170.23/32' || ip addr add 169.254.170.23/32 dev lo"));
        assertTrue(script.contains("if [ -f /tmp/floci-pod-identity-proxy.pid ] && kill -0 \"$(cat /tmp/floci-pod-identity-proxy.pid)\" 2>/dev/null; then\n  exit 0\nfi"));
        assertTrue(script.contains("nohup socat TCP-LISTEN:80,bind=169.254.170.23,fork,reuseaddr TCP:10.0.0.1:4566 >/tmp/floci-pod-identity-proxy.log 2>&1 &"));
        assertTrue(script.contains("http://169.254.170.23/v1/credentials"));
    }

    @Test
    void preferredMetadataSourceIpPrefersConfiguredNetworkOverBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.2");
        ContainerNetwork vpc = new ContainerNetwork();
        vpc.withIpv4Address("10.0.0.2");

        Optional<String> ip = Ec2MetadataProxy.preferredMetadataSourceIp(Map.of("bridge", bridge, "custom-net", vpc));
        assertTrue(ip.isPresent());
        assertEquals("10.0.0.2", ip.get());
    }

    @Test
    void preferredMetadataSourceIpFallsBackToBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.2");

        Optional<String> ip = Ec2MetadataProxy.preferredMetadataSourceIp(Map.of("bridge", bridge));
        assertTrue(ip.isPresent());
        assertEquals("172.17.0.2", ip.get());
    }

    @Test
    void preferredMetadataSourceIpReturnsEmptyWhenNoNetworks() {
        assertTrue(Ec2MetadataProxy.preferredMetadataSourceIp(null).isEmpty());
        assertTrue(Ec2MetadataProxy.preferredMetadataSourceIp(Map.of()).isEmpty());
    }

    @Test
    void installCommandIsolatesSystemToolsWithoutChangingTheCallingEnvironment(@TempDir Path directory)
            throws Exception {
        Path bin = Files.createDirectory(directory.resolve("bin"));
        Path record = directory.resolve("tools");
        Path arguments = directory.resolve("arguments");
        writeTool(bin, "dnf", checkedToolEnvironment()
                + "printf '%s\\n' \"$*\" > \"$TEST_ARGUMENTS\"\n");

        assertEquals(0, runShell(Ec2MetadataProxy.installCommand(), directory, Map.of(
                "PATH", bin.toString(), "TEST_RECORD", record.toString(),
                "TEST_ARGUMENTS", arguments.toString())));

        assertEquals("dnf\n", Files.readString(record));
        assertEquals("install -y --allowerasing iproute socat curl ca-certificates\n",
                Files.readString(arguments));
    }

    @Test
    void startCommandIsolatesAddressProxyAndProbeTools(@TempDir Path directory) throws Exception {
        Path bin = Files.createDirectory(directory.resolve("bin"));
        Path record = directory.resolve("tools");
        Path proxyReady = directory.resolve("proxy-ready");
        writeTool(bin, "ip", checkedToolEnvironment() + "printf '127.0.0.1/32\\n'\n");
        writeTool(bin, "socat", checkedToolEnvironment() + ": > \"$TEST_PROXY_READY\"\n");
        writeTool(bin, "curl", checkedToolEnvironment()
                + "for attempt in 1 2 3 4 5 6 7 8 9 10; do\n"
                + "  [ -f \"$TEST_PROXY_READY\" ] && exit 0\n"
                + "  /bin/sleep 0.1\n"
                + "done\nexit 1\n");
        String name = "unit-" + UUID.randomUUID();
        try {
            String[] command = Ec2MetadataProxy.startCommand(
                    name, "127.0.0.1", 80, "127.0.0.1", 9169, "curl fixture && exit 0");
            assertEquals(0, runShell(command, directory, Map.of(
                    "PATH", bin + ":" + System.getenv("PATH"),
                    "TEST_RECORD", record.toString(), "TEST_PROXY_READY", proxyReady.toString())));
            List<String> tools = Files.readAllLines(record);
            assertEquals(3, tools.size());
            assertTrue(tools.containsAll(List.of("ip", "socat", "curl")));
        } finally {
            Files.deleteIfExists(Path.of("/tmp/floci-" + name + "-proxy.pid"));
            Files.deleteIfExists(Path.of("/tmp/floci-" + name + "-proxy.log"));
        }
    }

    @ParameterizedTest
    @CsvSource({"200, fixture-token, true", "403, rejected, false", "200, '', false"})
    void readinessUsesARealTokenExchangeWithoutTokenlessFallback(
            int tokenStatus, String tokenBody, boolean ready, @TempDir Path directory) throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest/api/token", exchange -> {
            requests.add(exchange.getRequestMethod() + " token "
                    + exchange.getRequestHeaders().getFirst("X-aws-ec2-metadata-token-ttl-seconds"));
            byte[] body = tokenBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(tokenStatus, body.length == 0 ? -1 : body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/latest/meta-data/instance-id", exchange -> {
            String token = exchange.getRequestHeaders().getFirst("X-aws-ec2-metadata-token");
            requests.add(exchange.getRequestMethod() + " metadata " + token);
            byte[] body = "i-fixture".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders("fixture-token".equals(token) ? 200 : 401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String probe = Ec2MetadataProxy.imdsProbeCommand("http://127.0.0.1:" + server.getAddress().getPort());
            int result = runShell(new String[]{"sh", "-c", probe}, directory,
                    Map.of("NO_PROXY", "127.0.0.1", "no_proxy", "127.0.0.1"), false);
            String output = Files.readString(directory.resolve("shell-output"));
            assertEquals(ready, result == 0, output);
            assertEquals(ready ? List.of("PUT token 60", "GET metadata fixture-token")
                    : List.of("PUT token 60"), requests, output);
            assertFalse(requests.contains("GET metadata null"));
        } finally {
            server.stop(0);
        }
    }

    private static String checkedToolEnvironment() {
        return "[ -z \"${LD_LIBRARY_PATH+x}\" ] || exit 91\n"
                + "[ -z \"${PYTHONPATH+x}\" ] || exit 92\n"
                + "[ -z \"${PYTHONHOME+x}\" ] || exit 93\n"
                + "[ \"$KEEP_SETTING\" = fixture-value ] || exit 94\n"
                + "printf '%s\\n' \"${0##*/}\" >> \"$TEST_RECORD\"\n";
    }

    private static void writeTool(Path bin, String name, String script) throws IOException {
        Path tool = bin.resolve(name);
        Files.writeString(tool, "#!/bin/sh\nset -eu\n" + script);
        assertTrue(tool.toFile().setExecutable(true));
    }

    private static int runShell(String[] command, Path directory, Map<String, String> environment)
            throws Exception {
        return runShell(command, directory, environment, true);
    }

    private static int runShell(String[] command, Path directory, Map<String, String> environment,
                                boolean applicationOverrides) throws Exception {
        Map<String, String> parentEnvironment = Map.copyOf(System.getenv());
        List<String> arguments = new ArrayList<>(List.of(command));
        arguments.set(0, "/bin/sh");
        ProcessBuilder builder = new ProcessBuilder(arguments);
        builder.environment().putAll(environment);
        if (applicationOverrides) {
            builder.environment().putAll(Map.of(
                    "LD_LIBRARY_PATH", "/fixture/application/lib",
                    "PYTHONPATH", "/fixture/application/modules",
                    "PYTHONHOME", "/fixture/application/python",
                    "KEEP_SETTING", "fixture-value"));
        }
        Map<String, String> configuredEnvironment = Map.copyOf(builder.environment());
        Path output = directory.resolve("shell-output");
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Generated helper shell exceeded its bound");
            assertEquals(configuredEnvironment, builder.environment());
            assertEquals(parentEnvironment, System.getenv());
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }
}
