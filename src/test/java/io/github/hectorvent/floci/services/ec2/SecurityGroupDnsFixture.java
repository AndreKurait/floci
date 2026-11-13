package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/** Keeps real DNS ownership transitions while the firewall suites own Docker policy admission. */
public final class SecurityGroupDnsFixture {

    private SecurityGroupDnsFixture() {
    }

    public static SecurityGroupFirewallManager manager(DockerClient dockerClient, EmulatorConfig config) {
        SecurityGroupFirewallManager manager = spy(new SecurityGroupFirewallManager(dockerClient,
                mock(ContainerBuilder.class), mock(ContainerLifecycleManager.class), config,
                mock(EmbeddedDnsServer.class)));
        doNothing().when(manager).reconcileAll();
        doNothing().when(manager).admit(anyString(), anyBoolean());
        return manager;
    }
}
