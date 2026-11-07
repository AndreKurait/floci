package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A real firewall manager whose Docker copy and nft exec calls are recorded and checked. */
class SecurityGroupFirewallPeerFixture {

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String VPC = "vpc-a";
    static final Pattern PEER_ACCEPT = Pattern.compile(
            "add rule inet floci_sg (?:ingress|egress) ip (?:saddr|daddr) (172\\.18\\.\\d+\\.\\d+) .*accept");

    /** Docker networks reported for a container id, such as Floci's own container. */
    final Map<String, Map<String, ContainerNetwork>> containerNetworks = new ConcurrentHashMap<>();

    /** Every nft script installed, in order, and each helper's currently installed script. */
    final List<String[]> installs = Collections.synchronizedList(new ArrayList<>());
    final Map<String, String> installed = new ConcurrentHashMap<>();
    final Set<String> stoppedHelpers = ConcurrentHashMap.newKeySet();
    private final Map<String, String> transportByHelper = new ConcurrentHashMap<>();
    final List<String> violations = Collections.synchronizedList(new ArrayList<>());

    void assertFinal(int count) {
        List<SecurityGroupNftCompiler.Endpoint> all = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            all.add(endpoint(index));
        }
        all.sort(Comparator.comparing(SecurityGroupNftCompiler.Endpoint::eniId));
        for (int index = 0; index < count; index++) {
            assertEquals(canonical(SecurityGroupNftCompiler.compile(endpoint(index), all, Map.of(), List.of(), false)),
                    canonical(installed.get(helper(index))), "helper " + index + " does not hold its complete policy");
        }
    }

    /**
     * A policy with each run of adjacent rules sharing a chain and verdict sorted. Reordering such a
     * run never changes which packets a first-match chain accepts, so equal forms are equal policies.
     */
    static List<String> canonical(String script) {
        List<String> result = new ArrayList<>();
        List<String> run = new ArrayList<>();
        String runKey = null;
        for (String line : script == null ? List.<String>of() : script.lines().toList()) {
            String key = line.startsWith("add rule inet floci_sg ")
                    ? line.split(" ")[4] + (line.endsWith(" accept") ? " accept" : line.endsWith(" drop") ? " drop" : " " + line)
                    : line;
            if (!key.equals(runKey)) {
                run.sort(null);
                result.addAll(run);
                run.clear();
                runKey = key;
            }
            run.add(line);
        }
        run.sort(null);
        result.addAll(run);
        return result;
    }

    SecurityGroupFirewallManager manager() {
        return manager(Optional.empty());
    }

    /** A manager in a Floci whose own container address, where its DNS listens, is {@code flociAddress}. */
    SecurityGroupFirewallManager manager(Optional<String> flociAddress) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().vpcNetworks().isolateAddressSpaces()).thenReturn(false);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
        when(dns.getServerIp()).thenReturn(flociAddress);
        return new SecurityGroupFirewallManager(docker(), mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class), config, dns);
    }

    private DockerClient docker() {
        DockerClient docker = mock(DockerClient.class);
        when(docker.copyArchiveToContainerCmd(anyString())).thenAnswer(invocation -> {
            String helper = invocation.getArgument(0);
            CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
            AtomicReference<InputStream> tar = new AtomicReference<>();
            when(copy.withTarInputStream(any())).thenAnswer(call -> {
                tar.set(call.getArgument(0));
                return copy;
            });
            when(copy.exec()).thenAnswer(call -> {
                record(helper, read(tar.get()));
                return null;
            });
            return copy;
        });
        when(docker.execCreateCmd(anyString())).thenAnswer(invocation -> {
            ExecCreateCmd create = mock(ExecCreateCmd.class, RETURNS_SELF);
            ExecCreateCmdResponse response = mock(ExecCreateCmdResponse.class);
            when(response.getId()).thenReturn("exec");
            when(create.exec()).thenReturn(response);
            return create;
        });
        ExecStartCmd start = mock(ExecStartCmd.class);
        when(docker.execStartCmd("exec")).thenReturn(start);
        when(start.exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<?> callback = invocation.getArgument(0);
            callback.onComplete();
            return callback;
        });
        InspectExecCmd inspectExec = mock(InspectExecCmd.class);
        InspectExecResponse exited = mock(InspectExecResponse.class);
        when(exited.getExitCodeLong()).thenReturn(0L);
        when(inspectExec.exec()).thenReturn(exited);
        when(docker.inspectExecCmd("exec")).thenReturn(inspectExec);
        when(docker.inspectContainerCmd(anyString())).thenAnswer(invocation -> {
            String helper = invocation.getArgument(0);
            InspectContainerCmd inspect = mock(InspectContainerCmd.class);
            InspectContainerResponse response = mock(InspectContainerResponse.class, RETURNS_DEEP_STUBS);
            when(response.getState().getRunning()).thenReturn(!stoppedHelpers.contains(helper));
            Map<String, ContainerNetwork> networks = containerNetworks.get(helper);
            if (networks != null) {
                when(response.getNetworkSettings().getNetworks()).thenReturn(networks);
            }
            when(inspect.exec()).thenReturn(response);
            return inspect;
        });
        return docker;
    }

    /**
     * Records an installation and checks the safety invariant at that instant: a policy that accepts
     * a managed peer's address is installed only when that peer's own installed policy already names
     * this endpoint, so the peer never lets it fall through to broader CIDR rules.
     */
    /** Helpers whose next nft installation fails once, as a Docker exec error would. */
    final Set<String> failNextInstall = ConcurrentHashMap.newKeySet();

    private void record(String helper, String script) {
        if (failNextInstall.remove(helper)) {
            throw new IllegalStateException("injected nft failure for " + helper);
        }
        String self = transportByHelper.get(helper);
        Matcher accept = PEER_ACCEPT.matcher(script);
        while (accept.find()) {
            String peerAddress = accept.group(1);
            transportByHelper.forEach((peerHelper, address) -> {
                if (address.equals(peerAddress) && !peerHelper.equals(helper)) {
                    String peerPolicy = installed.get(peerHelper);
                    if (peerPolicy == null || !peerPolicy.contains(" " + self + " ")) {
                        violations.add(helper + " accepts " + peerHelper + " before that peer enforces "
                                + self);
                    }
                }
            });
        }
        installs.add(new String[]{helper, script});
        installed.put(helper, script);
    }

    private static String read(InputStream input) throws IOException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(input)) {
            tar.getNextEntry();
            return new String(tar.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    String helper(int index) {
        return "helper-" + index;
    }

    SecurityGroupNftCompiler.Endpoint endpoint(int index) {
        String transport = "172.18.0." + (2 + index);
        transportByHelper.put(helper(index), transport);
        return new SecurityGroupNftCompiler.Endpoint(ACCOUNT, REGION, VPC, "eni-" + index,
                "10.0.1." + (10 + index), transport, Set.of("sg-a"), List.of(group()));
    }

    /**
     * Members accept each other, and the group also opens SSH to the Docker transport range. That
     * CIDR must never reach a managed peer through its transport address: only its drop stops it.
     */
    private static SecurityGroup group() {
        SecurityGroup group = new SecurityGroup();
        group.setGroupId("sg-a");
        group.setVpcId(VPC);
        group.setOwnerId(ACCOUNT);
        IpPermission members = new IpPermission();
        members.setIpProtocol("-1");
        UserIdGroupPair self = new UserIdGroupPair();
        self.setGroupId("sg-a");
        members.setUserIdGroupPairs(List.of(self));
        IpPermission ssh = new IpPermission();
        ssh.setIpProtocol("tcp");
        ssh.setFromPort(22);
        ssh.setToPort(22);
        ssh.setIpRanges(List.of(new IpRange("172.18.0.0/16")));
        group.setIpPermissions(List.of(members, ssh));
        IpPermission egress = new IpPermission();
        egress.setIpProtocol("-1");
        egress.setIpRanges(List.of(new IpRange("0.0.0.0/0")));
        group.setIpPermissionsEgress(List.of(egress));
        return group;
    }
}
