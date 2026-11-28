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
class VpcNextHopRecoveryTest {
    @TempDir
    Path directory;

    @Test
    void restoredRoutesRefreshOnlyTheirDistinctNextHopNeighbors() throws Exception {
        Run result = run(List.of(
                new VpcRoutingCommands.Hop("192.0.2.1", "10.0.1.10", "eth1", false),
                new VpcRoutingCommands.Hop("203.0.113.2", "10.0.1.10", "eth1", false),
                new VpcRoutingCommands.Hop("198.51.100.3", "172.18.0.4", "eth2", true)), 0);

        assertEquals(0, result.exit());
        List<String> neighbors = result.calls().stream().filter(call -> call.contains(" neigh ")).toList();
        assertEquals(List.of(
                "ip -4 neigh flush to 10.0.1.10/32 dev eth1",
                "ip -4 neigh flush to 172.18.0.4/32 dev eth2"), neighbors);
        for (String neighbor : neighbors) {
            assertTrue(result.calls().indexOf(neighbor) < result.calls().indexOf("nft -f -"));
        }
        assertTrue(result.calls().contains(
                "ip -4 route replace 192.0.2.1/32 via 10.0.1.10 dev eth1 src 10.0.2.10 proto 186"));
        assertTrue(result.rules().contains(
                "egress ip daddr 198.51.100.3 oifname \"eth2\" ct state established,related counter accept"));
        assertTrue(result.rules().contains("egress counter drop"));
    }

    @Test
    void routesWithoutManagedNextHopsLeaveNeighborsUntouched() throws Exception {
        Run result = run(List.of(), 0);

        assertEquals(0, result.exit());
        assertFalse(result.calls().stream().anyMatch(call -> call.contains(" neigh ")));
        assertTrue(result.rules().contains("egress counter drop"));
    }

    @Test
    void failedNeighborRefreshDoesNotPublishPacketPermissions() throws Exception {
        Run result = run(List.of(
                new VpcRoutingCommands.Hop("192.0.2.1", "10.0.1.10", "eth1", false)), 37);

        assertEquals(37, result.exit());
        assertFalse(result.calls().contains("nft -f -"));
        assertEquals("", result.rules());
    }

    private Run run(List<VpcRoutingCommands.Hop> hops, int neighborExit) throws Exception {
        Path calls = directory.resolve("calls");
        Path rules = directory.resolve("rules");
        Path output = directory.resolve("output");
        String stubs = """
                ip() {
                    printf 'ip %s\\n' "$*" >> "$FLOCI_TEST_CALLS"
                    if [ "$1" = -4 ] && [ "$2" = neigh ]; then
                        return "$FLOCI_TEST_NEIGHBOR_EXIT"
                    fi
                }
                nft() {
                    printf 'nft %s\\n' "$*" >> "$FLOCI_TEST_CALLS"
                    cat >> "$FLOCI_TEST_RULES"
                }
                """;
        String script = VpcRoutingCommands.endpoint("10.0.2.10", "eth1", List.of(), hops,
                List.of(), List.of(), List.of(), false, null, List.of());
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", stubs + script)
                .redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("FLOCI_TEST_CALLS", calls.toString());
        builder.environment().put("FLOCI_TEST_RULES", rules.toString());
        builder.environment().put("FLOCI_TEST_NEIGHBOR_EXIT", Integer.toString(neighborExit));
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "The route command did not finish");
            return new Run(process.exitValue(), Files.readAllLines(calls),
                    Files.exists(rules) ? Files.readString(rules) : "");
        } finally {
            process.destroyForcibly();
        }
    }

    private record Run(int exit, List<String> calls, String rules) {}
}
