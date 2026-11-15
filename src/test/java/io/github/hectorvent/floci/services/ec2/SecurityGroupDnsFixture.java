package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;

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
        SecurityGroupFirewallManager manager = spy(new SecurityGroupFirewallManager(policyDocker(dockerClient),
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class), config,
                mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
        doNothing().when(manager).admit(anyString(), anyBoolean());
        return manager;
    }
    /** DNS-only spies still execute quarantine; model a confirmed successful nft command. */
    static DockerClient policyDocker(DockerClient dockerClient) {
        CopyArchiveToContainerCmd copy = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd(anyString())).thenReturn(copy);
        ExecCreateCmd create = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn("dns-policy-exec");
        when(create.exec()).thenReturn(created);
        when(dockerClient.execCreateCmd(anyString())).thenReturn(create);
        ExecStartCmd start = mock(ExecStartCmd.class);
        when(start.exec(any())).thenAnswer(invocation -> {
            ResultCallback.Adapter<?> callback = invocation.getArgument(0);
            callback.onComplete();
            return callback;
        });
        when(dockerClient.execStartCmd("dns-policy-exec")).thenReturn(start);
        InspectExecCmd inspect = mock(InspectExecCmd.class);
        InspectExecResponse result = mock(InspectExecResponse.class);
        when(result.isRunning()).thenReturn(false);
        when(result.getExitCodeLong()).thenReturn(0L);
        when(inspect.exec()).thenReturn(result);
        when(dockerClient.inspectExecCmd("dns-policy-exec")).thenReturn(inspect);
        return dockerClient;
    }

}
