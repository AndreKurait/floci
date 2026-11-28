package io.floci.aws.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.model.Info;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.CachedImageStubs;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.core.common.docker.DockerHostResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class Ec2MetadataNamespaceTest {

    @Test
    void ipv6NamespaceKeepsOwnershipAndEnablesFutureInterfaces() {
        Fixture fixture = new Fixture();
        assertEquals("helper-id", fixture.namespace.create("i-example", "111111111111", "us-east-1", 2200, true));
        ContainerSpec spec = fixture.createdSpec();
        assertEquals(Map.of("net.ipv6.conf.all.disable_ipv6", "0",
                "net.ipv6.conf.default.disable_ipv6", "0"), spec.linuxOptions().networkSysctls());
        assertEquals("true", spec.labels().get(Ec2MetadataNamespace.HELPER_LABEL));
        assertEquals("namespace-test/4566", spec.labels().get(ContainerStorageHelper.OWNER_LABEL));
        assertEquals(Map.of(22, 2200), spec.portBindings());
        assertEquals("127.0.0.1", spec.portBindingHostIps().get(22));
        assertEquals("test-network", spec.networkMode());
        assertEquals(List.of("192.0.2.53"), spec.dnsServers());
        assertEquals(List.of("sh", "-c"), spec.entrypoint());
        assertEquals(List.of("exec sleep 2147483647"), spec.cmd());
        assertTrue(spec.linuxOptions().capAdd().contains("NET_ADMIN"));
        assertFalse(spec.privileged());
    }

    @Test
    void ipv4OverloadPreservesDaemonSysctlDefaultsAndExplicitSshBinding() {
        Fixture fixture = new Fixture();
        when(fixture.ec2.sshHostBindAddress()).thenReturn("192.0.2.10");
        fixture.namespace.create("i-example", "111111111111", "us-east-1", 2200);
        ContainerSpec spec = fixture.createdSpec();
        assertTrue(spec.linuxOptions().networkSysctls().isEmpty());
        assertEquals("192.0.2.10", spec.portBindingHostIps().get(22));
        verifyNoInteractions(fixture.dockerClient);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingSshBindingFallsBackToLoopback(String address) {
        Fixture fixture = new Fixture();
        when(fixture.ec2.sshHostBindAddress()).thenReturn(address);
        fixture.namespace.create("i-example", "111111111111", "us-east-1", 2200);
        assertEquals("127.0.0.1", fixture.createdSpec().portBindingHostIps().get(22));
    }

    @Test
    void defaultHelperUsesExistingImageBeforeStartingNamespace() {
        Fixture fixture = new Fixture();
        when(fixture.ec2.metadataHelperImage()).thenReturn(Ec2MetadataNamespace.DEFAULT_IMAGE);
        InfoCmd info = mock(InfoCmd.class);
        when(fixture.dockerClient.infoCmd()).thenReturn(info);
        when(info.exec()).thenReturn(new Info().withArchitecture("x86_64").withOsType("linux"));
        CachedImageStubs.image(fixture.dockerClient, Ec2MetadataNamespace.DEFAULT_IMAGE, "linux", "amd64",
                CachedImageStubs.recipe("/docker/network-helper.Dockerfile"));
        fixture.namespace.create("i-example", "111111111111", "us-east-1", 2200);
        verify(fixture.dockerClient).inspectImageCmd(Ec2MetadataNamespace.DEFAULT_IMAGE);
        assertEquals(Ec2MetadataNamespace.DEFAULT_IMAGE, fixture.createdSpec().image());
    }

    @Test
    void lifecycleFailurePropagatesWithoutPublishingAHelperId() {
        Fixture fixture = new Fixture();
        IllegalStateException failure = new IllegalStateException("helper start failed");
        when(fixture.lifecycle.createAndStart(any())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> fixture.namespace.create("i-example", "111111111111", "us-east-1", 2200)));
    }

    private static class Fixture {
        final DockerClient dockerClient = mock(DockerClient.class);
        final ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        final EmulatorConfig config = mock(EmulatorConfig.class);
        final EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        final EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        final EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        final EmulatorConfig.DnsConfig dns = mock(EmulatorConfig.DnsConfig.class);
        final EmbeddedDnsServer dnsServer = mock(EmbeddedDnsServer.class);
        final DockerHostResolver host = mock(DockerHostResolver.class);
        final ContainerBuilder builder = new ContainerBuilder(config, host, dnsServer);
        final Ec2MetadataNamespace namespace = new Ec2MetadataNamespace(dockerClient, builder, lifecycle, config);

        Fixture() {
            when(config.services()).thenReturn(services);
            when(config.docker()).thenReturn(docker);
            when(config.dns()).thenReturn(dns);
            when(config.port()).thenReturn(4566);
            when(services.ec2()).thenReturn(ec2);
            when(services.dockerNetwork()).thenReturn(Optional.of("test-network"));
            when(docker.resourceNamespace()).thenReturn(Optional.of("namespace-test"));
            when(docker.imageRegistryBase()).thenReturn(Optional.empty());
            when(dnsServer.getServerIp()).thenReturn(Optional.of("192.0.2.53"));
            when(ec2.metadataHelperImage()).thenReturn("example/helper:1.0");
            when(ec2.sshHostBindAddress()).thenReturn("127.0.0.1");
            when(lifecycle.createAndStart(any())).thenReturn(
                    new ContainerLifecycleManager.ContainerInfo("helper-id", Map.of()));
        }

        ContainerSpec createdSpec() {
            ArgumentCaptor<ContainerSpec> capture = ArgumentCaptor.forClass(ContainerSpec.class);
            verify(lifecycle).createAndStart(capture.capture());
            return capture.getValue();
        }
    }
}
