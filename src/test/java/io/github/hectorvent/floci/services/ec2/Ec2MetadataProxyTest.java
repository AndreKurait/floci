package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2MetadataProxyTest {

    @Test
    void ec2InstallCommandContainsRoutingDependenciesForSupportedPackageManagers() {
        String[] command = Ec2MetadataProxy.ec2InstallCommand();
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        assertTrue(script.contains("command -v iptables >/dev/null 2>&1; then exit 0; fi"));
        assertTrue(script.contains("apt-get install -y --no-install-recommends iproute2 socat curl ca-certificates iptables"));
        assertTrue(script.contains("dnf install -y --allowerasing iproute socat curl ca-certificates iptables-nft"));
        assertTrue(script.contains("yum install -y iproute socat curl ca-certificates iptables"));
        assertTrue(script.contains("apk add --no-cache iproute2 socat curl ca-certificates iptables"));
    }

    @Test
    void legacyInstallerKeepsItsExistingDependencyContract() {
        String script = Ec2MetadataProxy.installCommand()[2];
        assertFalse(script.contains("iptables"));
        assertTrue(script.contains("command -v curl >/dev/null 2>&1; then exit 0; fi"));
        assertTrue(script.contains("iproute2 socat curl ca-certificates >/dev/null"));
        assertTrue(script.contains("dnf install -y --allowerasing iproute socat curl ca-certificates >/dev/null"));
        assertTrue(script.contains("yum install -y iproute socat curl ca-certificates >/dev/null"));
    }

    @Test
    void startCommandRoutesOnlyImdsWithoutBindingAListenerOrLocalAddress() {
        String[] command = Ec2MetadataProxy.ec2RoutingCommand("10.0.0.1", 9169);
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        assertFalse(script.contains("ip addr add"));
        assertFalse(script.contains("socat"));
        assertFalse(script.contains("pid"));
        assertFalse(script.contains(" -F "));
        assertTrue(script.contains("target_ip='10.0.0.1'"));
        assertTrue(script.contains("-I OUTPUT 1 -d 169.254.169.254/32 -p tcp --dport 80"));
        assertTrue(script.contains("--comment floci-imds -j DNAT --to-destination \"$target_ip:9169\""));
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

        assertEquals(0, runShell(Ec2MetadataProxy.ec2InstallCommand(), directory, Map.of(
                "PATH", bin.toString(), "TEST_RECORD", record.toString(),
                "TEST_ARGUMENTS", arguments.toString())));

        assertEquals("dnf\n", Files.readString(record));
        assertEquals("install -y --allowerasing iproute socat curl ca-certificates iptables-nft\n",
                Files.readString(arguments));
    }

    @Test
    void imdsRoutingReusesOnlyItsExactRuleAndStillProbesOnEveryCall(@TempDir Path directory)
            throws Exception {
        Map<String, String> environment = routingTools(directory);
        Path rules = Path.of(environment.get("TEST_RULES"));
        Files.writeString(rules, "-A OUTPUT -d 192.0.2.1/32 -j ACCEPT\n");
        String[] command = Ec2MetadataProxy.ec2RoutingCommand("10.0.0.1", 9169);
        assertEquals(0, runShell(command, directory, environment), shellOutput(directory));
        String first = Files.readString(rules);
        assertEquals(0, runShell(command, directory, environment), shellOutput(directory));
        assertEquals(first, Files.readString(rules));
        assertTrue(first.contains("-A OUTPUT -d 192.0.2.1/32 -j ACCEPT\n"));
        assertEquals(1, Files.readAllLines(Path.of(environment.get("TEST_CALLS"))).stream()
                .filter(line -> line.contains("-I OUTPUT")).count());
        assertEquals(List.of("token", "metadata", "token", "metadata"),
                Files.readAllLines(Path.of(environment.get("TEST_REQUESTS"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy-address", "own-address", "conflicting-rule", "duplicate-rule"})
    void imdsRoutingRefusesConflictsWithoutChangingRules(String conflict, @TempDir Path directory)
            throws Exception {
        Map<String, String> environment = new HashMap<>(routingTools(directory));
        Path rules = Path.of(environment.get("TEST_RULES"));
        String rule = "-A OUTPUT -d 169.254.169.254/32 -p tcp --dport 80 -m comment --comment floci-imds"
                + " -j DNAT --to-destination 10.0.0.1:9169\n";
        switch (conflict) {
            case "legacy-address" -> environment.put("TEST_ADDRESSES", "1: lo inet 169.254.169.254/32 scope global lo");
            case "own-address" -> environment.put("TEST_ADDRESSES", "2: eth0 inet 10.0.0.1/24 scope global eth0");
            case "conflicting-rule" -> Files.writeString(rules, rule.replace("10.0.0.1", "10.0.0.2"));
            case "duplicate-rule" -> Files.writeString(rules, rule + rule);
            default -> throw new IllegalArgumentException(conflict);
        }
        String before = Files.readString(rules);
        assertEquals(1, runShell(Ec2MetadataProxy.ec2RoutingCommand("host.docker.internal", 9169),
                directory, environment), shellOutput(directory));
        assertEquals(before, Files.readString(rules));
        assertEquals("", Files.readString(Path.of(environment.get("TEST_REQUESTS"))));
        assertFalse(Files.readString(Path.of(environment.get("TEST_CALLS"))).contains("-I OUTPUT"));
    }

    @Test
    void imdsRoutingResolvesInsideGuestAndRefusesFailedReadinessOnReuse(@TempDir Path directory)
            throws Exception {
        Map<String, String> environment = new HashMap<>(routingTools(directory));
        String[] command = Ec2MetadataProxy.ec2RoutingCommand("host.docker.internal", 9169);
        assertEquals(0, runShell(command, directory, environment), shellOutput(directory));
        Path rules = Path.of(environment.get("TEST_RULES"));
        String before = Files.readString(rules);
        assertTrue(before.contains("--to-destination 10.0.0.1:9169"));
        environment.put("TEST_REJECT_TOKEN", "true");
        assertEquals(1, runShell(command, directory, environment), shellOutput(directory));
        assertEquals(before, Files.readString(rules));
        assertTrue(shellOutput(directory).contains("IMDSv2 routing readiness failed"));
        assertEquals(1, Files.readAllLines(Path.of(environment.get("TEST_REQUESTS"))).stream()
                .filter("metadata"::equals).count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "::1", "127.0.0.1", "0.0.0.0", "169.254.169.254",
            "224.0.0.1", "10.0.0.256", "10.0.00.1", "10.0.1", "10.0.0.1.1", "10.0.0.1.",
            ".10.0.0.1", "10..0.1", "10.0.0.1\n10.0.0.2"})
    void imdsRoutingRefusesInvalidResolvedTargetsBeforeWriting(String target, @TempDir Path directory)
            throws Exception {
        Map<String, String> environment = new HashMap<>(routingTools(directory));
        environment.put("TEST_RESOLVED_IP", target);
        assertEquals(1, runShell(Ec2MetadataProxy.ec2RoutingCommand("host.docker.internal", 9169),
                directory, environment), shellOutput(directory));
        assertEquals("", Files.readString(Path.of(environment.get("TEST_RULES"))));
        assertEquals("", Files.readString(Path.of(environment.get("TEST_CALLS"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "::1", "-option", "host/path", "host;id", "host'quote", "host\nname"})
    void imdsRoutingRejectsInvalidHostBeforeGeneratingShell(String host) {
        assertThrows(IllegalArgumentException.class, () -> Ec2MetadataProxy.ec2RoutingCommand(host, 9169));
    }

    @Test
    void imdsRoutingRejectsInvalidPortsBeforeGeneratingShell() {
        assertThrows(IllegalArgumentException.class, () -> Ec2MetadataProxy.ec2RoutingCommand("10.0.0.1", 0));
        assertThrows(IllegalArgumentException.class, () -> Ec2MetadataProxy.ec2RoutingCommand("10.0.0.1", 65536));
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

    private static Map<String, String> routingTools(Path directory) throws IOException {
        Path bin = Files.createDirectory(directory.resolve("bin"));
        Path rules = Files.createFile(directory.resolve("rules"));
        Path calls = Files.createFile(directory.resolve("calls"));
        Path requests = Files.createFile(directory.resolve("requests"));
        Path record = Files.createFile(directory.resolve("tools"));
        writeTool(bin, "ip", checkedToolEnvironment() + "printf '%s\\n' \"${TEST_ADDRESSES-}\"\n");
        writeTool(bin, "iptables", checkedToolEnvironment()
                + "printf '%s\\n' \"$*\" >> \"$TEST_CALLS\"\n"
                + "[ \"$1 $2 $3 $4\" = '-w 2 -t nat' ] || exit 81\n"
                + "shift 4\n"
                + "operation=$1; shift\n"
                + "[ \"$1\" = OUTPUT ] || exit 82\n"
                + "shift\n"
                + "case \"$operation\" in\n"
                + "  -S) /bin/cat \"$TEST_RULES\" ;;\n"
                + "  -C) /bin/grep -Fx -- \"-A OUTPUT $*\" \"$TEST_RULES\" >/dev/null ;;\n"
                + "  -I) [ \"$1\" = 1 ] || exit 83; shift; printf '%s\\n' \"-A OUTPUT $*\" >> \"$TEST_RULES\" ;;\n"
                + "  *) exit 84 ;;\nesac\n");
        writeTool(bin, "curl", checkedToolEnvironment()
                + "case \"$*\" in\n"
                + "  *'%{remote_ip}'*) printf '%s' \"${TEST_RESOLVED_IP-10.0.0.1}\" ;;\n"
                + "  *'/latest/api/token'*) printf 'token\\n' >> \"$TEST_REQUESTS\";"
                + " [ \"${TEST_REJECT_TOKEN-false}\" = false ] || exit 22; printf 'fixture-token' ;;\n"
                + "  *'/latest/meta-data/instance-id'*) case \"$*\" in\n"
                + "    *'X-aws-ec2-metadata-token: fixture-token'*) printf 'metadata\\n' >> \"$TEST_REQUESTS\" ;;\n"
                + "    *) exit 85 ;;\nesac ;;\n"
                + "  *) exit 86 ;;\nesac\n");
        writeTool(bin, "sleep", "exit 0\n");
        return Map.of("PATH", bin + ":" + System.getenv("PATH"), "TEST_RULES", rules.toString(),
                "TEST_CALLS", calls.toString(), "TEST_REQUESTS", requests.toString(),
                "TEST_RECORD", record.toString());
    }

    private static String shellOutput(Path directory) throws IOException {
        return Files.readString(directory.resolve("shell-output"));
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
