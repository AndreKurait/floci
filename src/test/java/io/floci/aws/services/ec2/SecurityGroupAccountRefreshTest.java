package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.ExecStartCmd;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.PrefixListId;
import io.floci.aws.services.ec2.model.SecurityGroup;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SecurityGroupAccountRefreshTest {

    @ParameterizedTest
    @ValueSource(strings = {"account", "region"})
    void refreshRetainsOtherOwnersGroupsAndPrefixLists(String boundary) throws Exception {
        Fixture fixture = new Fixture(boundary);
        SecurityGroup replacement = group(9090);
        Map<String, List<String>> replacementLists = Map.of("pl-shared", List.of("203.0.113.0/24"));
        fixture.manager.refreshPolicies("111111111111", "us-east-1",
                Map.of("sg-shared", replacement), replacementLists);
        String ownerRules = fixture.scripts.get("helper-owner");
        String otherRules = fixture.scripts.get("helper-other");
        assertTrue(ownerRules.contains("tcp dport 9090"));
        assertTrue(ownerRules.contains("203.0.113.0/24"));
        assertFalse(ownerRules.contains("tcp dport 8080"));
        assertTrue(otherRules.contains("tcp dport 8080"));
        assertTrue(otherRules.contains("192.0.2.0/24"));
        assertFalse(otherRules.contains("tcp dport 9090"));
        assertFalse(otherRules.contains("203.0.113.0/24"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "region"})
    void missingGroupQuarantinesOnlyTheUpdatingOwner(String boundary) throws Exception {
        Fixture fixture = new Fixture(boundary);
        assertThrows(IllegalStateException.class, () -> fixture.manager.refreshPolicies(
                "111111111111", "us-east-1", Map.of(), Map.of()));
        verify(fixture.docker).copyArchiveToContainerCmd("helper-owner");
        verify(fixture.docker, never()).copyArchiveToContainerCmd("helper-other");
    }

    private static SecurityGroup group(int port) {
        SecurityGroup group = new SecurityGroup();
        group.setGroupId("sg-shared");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(port);
        permission.setToPort(port);
        PrefixListId prefix = new PrefixListId();
        prefix.setPrefixListId("pl-shared");
        permission.setPrefixListIds(List.of(prefix));
        group.setIpPermissions(List.of(permission));
        return group;
    }

    private static final class Fixture {
        private final DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        private final SecurityGroupFirewallManager manager;
        private final Map<String, String> scripts = new HashMap<>();
        private final Map<String, List<String>> originalLists = Map.of("pl-shared", List.of("192.0.2.0/24"));

        private Fixture(String boundary) throws Exception {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
            when(dns.getServerIp()).thenReturn(Optional.empty());
            manager = spy(SecurityGroupDnsFixture.rawManager(docker, mock(ContainerBuilder.class),
                    mock(ContainerLifecycleManager.class), config, dns));
            doNothing().when(manager).reconcileAll();
            doNothing().when(manager).admit(anyString(), anyBoolean());
            SecurityGroup otherGroup = group(8080);
            SecurityGroup ownerGroup = group(8080);
            manager.register(endpoint("111111111111", "us-east-1", "owner", ownerGroup),
                    "helper-owner", originalLists);
            manager.register(endpoint(boundary.equals("account") ? "222222222222" : "111111111111",
                    boundary.equals("region") ? "us-west-2" : "us-east-1", "other", otherGroup),
                    "helper-other", originalLists);
            for (String helper : List.of("helper-owner", "helper-other")) {
                CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
                when(docker.copyArchiveToContainerCmd(helper)).thenReturn(copy);
                doAnswer(invocation -> {
                    InputStream input = invocation.getArgument(0);
                    try (TarArchiveInputStream tar = new TarArchiveInputStream(input)) {
                        assertNotNull(tar.getNextEntry());
                        scripts.put(helper, new String(tar.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    return copy;
                }).when(copy).withTarInputStream(any(InputStream.class));
                when(docker.inspectContainerCmd(helper).exec().getState().getRunning()).thenReturn(true);
                when(docker.execCreateCmd(helper).withAttachStdout(true).withAttachStderr(true)
                        .withCmd("nft", "-f", "/tmp/floci-sg.nft").exec().getId()).thenReturn(helper);
                when(docker.inspectExecCmd(helper).exec().getExitCodeLong()).thenReturn(0L);
                when(docker.inspectExecCmd(helper).exec().getId()).thenReturn(helper);
                when(docker.inspectExecCmd(helper).exec().getContainerID()).thenReturn(helper);
                when(docker.inspectExecCmd(helper).exec().isRunning()).thenReturn(false);
                ExecStartCmd start = docker.execStartCmd(helper);
                doAnswer(invocation -> {
                    ResultCallback.Adapter<?> callback = invocation.getArgument(0);
                    callback.onComplete();
                    return callback;
                }).when(start).exec(any());
            }
            doCallRealMethod().when(manager).reconcileAll();
            clearInvocations(docker);
        }

        private SecurityGroupNftCompiler.Endpoint endpoint(String account, String region, String id,
                                                           SecurityGroup group) {
            String suffix = id.equals("owner") ? "1" : "2";
            return new SecurityGroupNftCompiler.Endpoint(account, region, "vpc-" + id, "eni-" + id,
                    "10.0.0." + suffix, "172.18.0." + suffix, Set.of("sg-shared"), List.of(group), List.of(), true, Set.of())
                    .withIpv6Addresses(List.of("2001:db8::" + suffix));
        }
    }
}
