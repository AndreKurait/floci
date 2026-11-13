package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.api.model.VersionComponent;
import com.github.dockerjava.api.model.VersionPlatform;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EcsHostAliasTest {

    @Test
    void dockerEngineOnLinuxRewritesToTheBridgeGateway() {
        DockerClient docker = engine("Docker Engine - Community", "Ubuntu 24.04 LTS", "172.18.0.1");

        EcsHostAlias.Resolution resolution = new EcsHostAlias(() -> docker, () -> true).resolve();

        assertEquals("172.18.0.1", resolution.gatewayAddress());
        assertFalse(resolution.addHostEntry());
        assertEquals("http://172.18.0.1:4566/v4/abc?x=1", resolution.rewrite("http://host.docker.internal:4566/v4/abc?x=1"));
        assertEquals("http://172.18.0.1:4566", resolution.rewrite("http://HOST.DOCKER.INTERNAL:4566"));
        assertEquals("http://floci:4566", resolution.rewrite("http://floci:4566"));
        assertEquals("172.18.0.1", resolution.rewriteHost("host.docker.internal"));
        assertEquals("floci", resolution.rewriteHost("floci"));
    }

    @Test
    void podmanAndDockerDesktopKeepTheName() {
        DockerClient podman = engine("Podman Engine", "fedora", "10.88.0.1");
        DockerClient desktop = engine("Docker Desktop 4.40.0", "Docker Desktop", "172.17.0.1");

        for (DockerClient docker : List.of(podman, desktop)) {
            EcsHostAlias.Resolution resolution = new EcsHostAlias(() -> docker, () -> true).resolve();
            assertNull(resolution.gatewayAddress());
            assertFalse(resolution.addHostEntry());
            assertEquals("http://host.docker.internal:4566", resolution.rewrite("http://host.docker.internal:4566"));
        }
    }

    @Test
    void notLinuxKeepsTheNameWithoutAskingDocker() {
        DockerClient docker = mock(DockerClient.class);

        EcsHostAlias.Resolution resolution = new EcsHostAlias(() -> docker, () -> false).resolve();

        assertEquals(EcsHostAlias.Resolution.KEEP_NAME, resolution);
        verify(docker, never()).versionCmd();
    }

    @Test
    void anUnansweredLookupKeepsTheEntryAndIsRetriedOnlyAfterABackoff() {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(docker.versionCmd().exec()).thenThrow(new RuntimeException("connection refused"));
        AtomicLong clock = new AtomicLong(1_000L);
        EcsHostAlias alias = new EcsHostAlias(() -> docker, () -> true, clock::get);

        assertTrue(alias.resolve().addHostEntry());
        assertTrue(alias.resolve().addHostEntry());
        verify(docker.versionCmd(), times(1)).exec();

        clock.addAndGet(EcsHostAlias.RETRY_AFTER_NANOS);
        assertTrue(alias.resolve().addHostEntry());
        verify(docker.versionCmd(), times(2)).exec();
    }

    @Test
    void aDistributionDockerBuildWithoutAPlatformNameIsStillDockerEngine() {
        DockerClient docker = engine(null, "Engine", "Debian GNU/Linux 13", "172.17.0.1");

        assertEquals("172.17.0.1", new EcsHostAlias(() -> docker, () -> true).resolve().gatewayAddress());
    }

    @Test
    void theRewriteKeepsEverythingButTheHostAsWritten() {
        EcsHostAlias.Resolution resolution = new EcsHostAlias.Resolution("172.17.0.1", false);

        assertEquals("http://u%40x@172.17.0.1:4566/a%2Fb?q=%20#f",
                resolution.rewrite("http://u%40x@host.docker.internal:4566/a%2Fb?q=%20#f"));
        assertEquals("http://172.17.0.1", resolution.rewrite("http://host.docker.internal"));
    }

    @Test
    void anUnidentifiedEngineOrMissingGatewayKeepsTheEntry() {
        DockerClient unknown = engine("Something Else", "Something Else", "linux", "172.17.0.1");
        DockerClient noGateway = engine("Docker Engine - Community", "Ubuntu", null);

        assertTrue(new EcsHostAlias(() -> unknown, () -> true).resolve().addHostEntry());
        assertTrue(new EcsHostAlias(() -> noGateway, () -> true).resolve().addHostEntry());
    }

    @Test
    void hostAliasEntryMatchesOnlyTheAliasName() {
        assertTrue(EcsHostAlias.isHostAliasEntry("host.docker.internal:host-gateway"));
        assertTrue(EcsHostAlias.isHostAliasEntry("Host.Docker.Internal:10.0.0.1"));
        assertFalse(EcsHostAlias.isHostAliasEntry("host.docker.internal:"));
        assertFalse(EcsHostAlias.isHostAliasEntry("floci:10.0.0.1"));
        assertFalse(EcsHostAlias.isHostAliasEntry(null));
    }

    private static DockerClient engine(String platformName, String operatingSystem, String gateway) {
        return engine(platformName, "Engine", operatingSystem, gateway);
    }

    private static DockerClient engine(String platformName, String componentName, String operatingSystem,
                                       String gateway) {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        Version version = mock(Version.class);
        when(version.getPlatform()).thenReturn(new VersionPlatform().withName(platformName));
        VersionComponent component = mock(VersionComponent.class);
        when(component.getName()).thenReturn(componentName);
        when(version.getComponents()).thenReturn(List.of(component));
        when(docker.versionCmd().exec()).thenReturn(version);
        Info info = mock(Info.class);
        when(info.getOperatingSystem()).thenReturn(operatingSystem);
        when(docker.infoCmd().exec()).thenReturn(info);
        Network bridge = mock(Network.class, RETURNS_DEEP_STUBS);
        Network.Ipam.Config ipam = mock(Network.Ipam.Config.class);
        when(ipam.getGateway()).thenReturn(gateway);
        when(bridge.getIpam().getConfig()).thenReturn(List.of(ipam));
        when(docker.inspectNetworkCmd().withNetworkId("bridge").exec()).thenReturn(bridge);
        return docker;
    }
}
