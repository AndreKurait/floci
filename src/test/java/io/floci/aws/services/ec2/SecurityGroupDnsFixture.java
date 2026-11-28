package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.services.ec2.net.VpcRoutingManager;
import jakarta.enterprise.inject.Instance;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** Keeps real DNS ownership transitions while the firewall suites own Docker policy admission. */
public final class SecurityGroupDnsFixture {

    private SecurityGroupDnsFixture() {
    }

    public static SecurityGroupFirewallManager manager(DockerClient dockerClient, EmulatorConfig config) {
        return manager(dockerClient, config, mock(ContainerLifecycleManager.class), null);
    }

    @SuppressWarnings("unchecked")
    public static SecurityGroupFirewallManager manager(DockerClient dockerClient, EmulatorConfig config,
                                                        ContainerLifecycleManager lifecycle, VpcRoutingManager routing) {
        SecurityGroupFirewallManager manager = spy(rawManager(policyDocker(dockerClient),
                mock(ContainerBuilder.class), lifecycle, config,
                mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
        doNothing().when(manager).admit(anyString(), anyBoolean());
        if (routing != null) {
            Instance<VpcRoutingManager> reference = mock(Instance.class);
            when(reference.get()).thenReturn(routing);
            manager.vpcRouting = reference;
        }
        return manager;
    }

    /** DNS/lifecycle-only suites model clean writer records; policy suites use the real durable journal. */
    public static SecurityGroupFirewallManager rawManager(
            DockerClient docker, ContainerBuilder builder, ContainerLifecycleManager lifecycle,
            EmulatorConfig config, EmbeddedDnsServer dns) {
        PolicyWriterJournal journal = mock(PolicyWriterJournal.class);
        when(journal.acquire(anyString())).thenAnswer(ignored -> mock(PolicyWriterJournal.Lease.class));
        when(journal.helpers()).thenReturn(List.of());
        return new SecurityGroupFirewallManager(docker, builder, lifecycle, config, dns, journal);
    }

    /** DNS-only spies still execute quarantine; model a confirmed successful nft command. */
    static DockerClient policyDocker(DockerClient dockerClient) {
        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd(anyString())).thenReturn(copy);
        when(dockerClient.execCreateCmd(anyString())).thenAnswer(invocation -> {
            String helper = invocation.getArgument(0);
            ExecCreateCmd create = mock(ExecCreateCmd.class, RETURNS_SELF);
            ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
            when(created.getId()).thenReturn("dns-policy-" + helper);
            when(create.exec()).thenReturn(created);
            return create;
        });
        ExecStartCmd start = mock(ExecStartCmd.class);
        when(start.exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<?> callback = invocation.getArgument(0);
            callback.onComplete();
            return callback;
        });
        when(dockerClient.execStartCmd(anyString())).thenReturn(start);
        when(dockerClient.inspectExecCmd(anyString())).thenAnswer(invocation -> {
            String execId = invocation.getArgument(0);
            InspectExecCmd inspect = mock(InspectExecCmd.class);
            InspectExecResponse result = mock(InspectExecResponse.class);
            when(result.getId()).thenReturn(execId);
            when(result.getContainerID()).thenReturn(execId.substring("dns-policy-".length()));
            when(result.isRunning()).thenReturn(false);
            when(result.getExitCodeLong()).thenReturn(0L);
            when(inspect.exec()).thenReturn(result);
            return inspect;
        });
        return dockerClient;
    }

}
