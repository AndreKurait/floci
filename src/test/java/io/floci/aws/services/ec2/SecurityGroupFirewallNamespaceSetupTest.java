package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Info;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A daemon stalled for minutes (overlay unmounts that sync the whole filesystem) while Lambda
 * workloads started, and their namespaces failed with "Cannot prepare protected network
 * namespace". The helper's resolver setup commands had a fixed 10-second budget, so a command
 * the stalled daemon was still running failed the whole namespace.
 */
class SecurityGroupFirewallNamespaceSetupTest {

    private static final long STALLED_COMMAND_MILLIS = 11_000;

    @TempDir
    Path persistent;

    @Test
    void aResolverSetupCommandSlowedByAStalledDaemonStillPreparesTheNamespace() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        // The security-group writer journal keeps its fence under the persistent data path.
        when(config.storage().persistentPath()).thenReturn(persistent.toString());
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(true);
        when(config.network().securityGroupEnforcement().helperImage()).thenReturn("example/network-helper:test");
        when(config.docker().resourceNamespace()).thenReturn(Optional.empty());
        when(config.docker().apiTimeoutSeconds()).thenReturn(30);
        when(config.port()).thenReturn(4566);

        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        Info info = mock(Info.class);
        when(info.getOsType()).thenReturn("linux");
        when(docker.infoCmd().exec()).thenReturn(info);

        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        ContainerSpec spec = new ContainerSpec(
                "example/network-helper:test", "floci-aws-sg-fn", List.of(), null, null, null, Map.of(),
                List.of(), null, List.of(), List.of(), List.of(), Map.of(), null, false, null,
                List.of(), null, null, List.of());
        when(builder.build()).thenReturn(spec);

        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.createAndStart(spec)).thenReturn(new ContainerLifecycleManager.ContainerInfo("helper-1", Map.of()));
        when(lifecycle.resolveContainerNetworkIp(eq("helper-1"), any())).thenReturn("172.18.0.5");

        EmbeddedDnsServer dns = mock(EmbeddedDnsServer.class);
        when(dns.getServerIp()).thenReturn(Optional.of("172.29.0.2"));

        // Three commands run in the helper, in order: the initial nft ruleset, the resolver
        // file, and the resolver route. The resolver file is the one the stalled daemon delays.
        ExecCreateCmd create = mock(ExecCreateCmd.class, RETURNS_SELF);
        when(docker.execCreateCmd("helper-1")).thenReturn(create);
        ExecCreateCmdResponse nft = execResponse("exec-nft");
        ExecCreateCmdResponse resolverFile = execResponse("exec-resolver");
        ExecCreateCmdResponse route = execResponse("exec-route");
        when(create.exec()).thenReturn(nft, resolverFile, route);
        ExecStartCmd nftStart = startCompletingAfter(0);
        ExecStartCmd resolverFileStart = startCompletingAfter(STALLED_COMMAND_MILLIS);
        ExecStartCmd routeStart = startCompletingAfter(0);
        when(docker.execStartCmd("exec-nft")).thenReturn(nftStart);
        when(docker.execStartCmd("exec-resolver")).thenReturn(resolverFileStart);
        when(docker.execStartCmd("exec-route")).thenReturn(routeStart);
        // Each command ends with an exact terminal result: this exec, in this helper, finished, exit 0.
        for (String execId : List.of("exec-nft", "exec-resolver", "exec-route")) {
            InspectExecResponse finished = mock(InspectExecResponse.class);
            when(finished.getId()).thenReturn(execId);
            when(finished.getContainerID()).thenReturn("helper-1");
            when(finished.isRunning()).thenReturn(false);
            when(finished.getExitCodeLong()).thenReturn(0L);
            InspectExecCmd inspect = mock(InspectExecCmd.class);
            when(inspect.exec()).thenReturn(finished);
            when(docker.inspectExecCmd(execId)).thenReturn(inspect);
        }

        SecurityGroupFirewallManager manager = new SecurityGroupFirewallManager(docker, containerBuilder,
                lifecycle, config, dns);

        SecurityGroupFirewallManager.Namespace namespace = manager.createNamespace("lambda", "fn-container",
                "111111111111", "us-east-1", Optional.empty(), null, Map.of(), null, null);

        assertEquals("helper-1", namespace.helperId());
        // Each setup command ran once: a timed-out command keeps running in the helper, and running
        // the route again would fail on the route it already added.
        verify(create, times(3)).exec();
        verify(lifecycle, never()).removeIfExists(anyString());
    }

    private static ExecCreateCmdResponse execResponse(String id) {
        ExecCreateCmdResponse response = mock(ExecCreateCmdResponse.class);
        when(response.getId()).thenReturn(id);
        return response;
    }

    private static ExecStartCmd startCompletingAfter(long millis) {
        ExecStartCmd start = mock(ExecStartCmd.class, RETURNS_SELF);
        when(start.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            if (millis == 0) {
                callback.onComplete();
            } else {
                Thread completion = new Thread(() -> {
                    try {
                        Thread.sleep(millis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    callback.onComplete();
                });
                completion.setDaemon(true);
                completion.start();
            }
            return callback;
        });
        return start;
    }
}
