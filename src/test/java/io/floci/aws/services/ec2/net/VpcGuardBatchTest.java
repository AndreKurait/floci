package io.floci.aws.services.ec2.net;

import io.floci.aws.testing.ParallelLocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.LINUX)
@ResourceLock(ParallelLocks.PORTS)
class VpcGuardBatchTest {
    @TempDir
    Path directory;

    @Test
    void nativeAddressIsAdmittedOnlyAfterOneCompleteIpv6GuardBatch() throws Exception {
        Run result = run(nativeAddress(), 0);

        assertEquals(0, result.exit());
        assertEquals(List.of("nft -f -"), nftCalls(result));
        for (String chain : List.of("ingress", "egress", "forwarding")) {
            assertTrue(result.rules().contains("flush chain inet floci_vpc_address_space " + chain));
        }
        assertTrue(result.rules().contains(
                "ingress iifname \"eth1\" meta nfproto ipv6 drop"));
        assertTrue(result.rules().contains(
                "egress oifname \"eth1\" meta nfproto ipv6 drop"));
        assertTrue(result.rules().contains(
                "forwarding iifname \"eth1\" meta nfproto ipv6 drop"));
        assertTrue(result.rules().contains(
                "forwarding oifname \"eth1\" meta nfproto ipv6 drop"));
        assertTrue(result.calls().indexOf("nft -f -")
                < result.calls().indexOf("ip -4 address replace 10.0.1.10/24 dev eth1"));
        assertEquals("ip -4 route replace 10.0.0.0/16 dev eth1 src 10.0.1.10 proto 185",
                result.calls().getLast());
    }

    @Test
    void rejectedNativeGuardCannotAssignTheAddressOrRoute() throws Exception {
        Run result = run(nativeAddress(), 37);

        assertEquals(37, result.exit());
        assertEquals(List.of("nft -f -"), nftCalls(result));
        assertFalse(result.calls().stream().anyMatch(call -> call.contains(" replace ")));
    }

    @Test
    void natGuardInstallsAllDefaultDenyHooksInOneBatch() throws Exception {
        Run result = run(VpcRoutingCommands.natGuard(), 0);

        assertEquals(0, result.exit());
        assertEquals(List.of("nft -f -"), result.calls());
        assertTrue(result.rules().contains(
                "forwarding { type filter hook forward priority -5; policy drop; }"));
        assertTrue(result.rules().contains(
                "incoming { type filter hook input priority -5; policy drop; }"));
        assertTrue(result.rules().contains(
                "local_output { type filter hook output priority -5; policy drop; }"));
        assertTrue(result.rules().contains(
                "outgoing { type nat hook postrouting priority srcnat; }"));
        assertTrue(result.rules().contains("flush chain inet floci_public_nat forwarding"));
        assertTrue(result.rules().contains("flush chain inet floci_public_nat outgoing"));
    }

    @Test
    void rejectedNatGuardPropagatesTheFailure() throws Exception {
        Run result = run(VpcRoutingCommands.natGuard(), 37);

        assertEquals(37, result.exit());
        assertEquals(List.of("nft -f -"), result.calls());
    }

    private static String nativeAddress() {
        return NativeVpcAddressSpace.commands("eth1", "10.0.1.10",
                Cidr4.parse("10.0.1.0/24").orElseThrow(), Cidr4.parse("10.0.0.0/16").orElseThrow());
    }

    private static List<String> nftCalls(Run result) {
        return result.calls().stream().filter(call -> call.startsWith("nft ")).toList();
    }

    private Run run(String script, int nftExit) throws Exception {
        Path calls = directory.resolve("calls");
        Path rules = directory.resolve("rules");
        Path output = directory.resolve("output");
        String stubs = """
                ip() {
                    printf 'ip %s\\n' "$*" >> "$FLOCI_TEST_CALLS"
                }
                nft() {
                    printf 'nft %s\\n' "$*" >> "$FLOCI_TEST_CALLS"
                    if [ "$#" -ne 2 ] || [ "$1" != -f ] || [ "$2" != - ]; then
                        return 97
                    fi
                    cat >> "$FLOCI_TEST_RULES"
                    return "$FLOCI_TEST_NFT_EXIT"
                }
                """;
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", stubs + script)
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("FLOCI_TEST_CALLS", calls.toString());
        builder.environment().put("FLOCI_TEST_RULES", rules.toString());
        builder.environment().put("FLOCI_TEST_NFT_EXIT", Integer.toString(nftExit));
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "The guarded command did not finish");
            return new Run(process.exitValue(), Files.readAllLines(calls),
                    Files.exists(rules) ? Files.readString(rules) : "");
        } finally {
            process.destroyForcibly();
        }
    }

    private record Run(int exit, List<String> calls, String rules) {}
}
