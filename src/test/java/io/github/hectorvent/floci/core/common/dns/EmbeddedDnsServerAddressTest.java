package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EmbeddedDnsServerAddressTest {

    @Test
    void explicitListenerUsesItsAddressWithoutResolvingAContainer() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.dns().listenAddress()).thenReturn(Optional.of("127.0.0.2"));
        CurrentContainerNetworkResolver resolver = mock(CurrentContainerNetworkResolver.class);

        assertEquals("127.0.0.2", EmbeddedDnsServer.resolveServerIp(config, resolver));
        verifyNoInteractions(resolver);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::1", "224.0.0.1", "resolver.example.test", "not an address"})
    void listenerRequiresAConcreteIpv4Address(String value) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.dns().listenAddress()).thenReturn(Optional.of(value));
        CurrentContainerNetworkResolver resolver = mock(CurrentContainerNetworkResolver.class);

        assertThrows(IllegalArgumentException.class, () -> EmbeddedDnsServer.resolveServerIp(config, resolver));
        verifyNoInteractions(resolver);
    }

    @Test
    void usesReachableContainerAddressInsteadOfSystemHostname() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        CurrentContainerNetworkResolver resolver = mock(CurrentContainerNetworkResolver.class);
        when(resolver.resolveContainerIp()).thenReturn(Optional.of("172.26.0.2"));

        assertEquals("172.26.0.2", EmbeddedDnsServer.resolveServerIp(config, resolver));
    }

    @Test
    void explicitNetworkCannotSilentlyFallBackToAnotherHostInterface() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dockerNetwork()).thenReturn(Optional.of("services-network"));
        CurrentContainerNetworkResolver resolver = mock(CurrentContainerNetworkResolver.class);
        when(resolver.resolveContainerIp()).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> EmbeddedDnsServer.resolveServerIp(config, resolver));
    }

    @Test
    void unconfiguredStandaloneContainerRetainsHostnameFallback() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().dockerNetwork()).thenReturn(Optional.empty());
        CurrentContainerNetworkResolver resolver = mock(CurrentContainerNetworkResolver.class);
        when(resolver.resolveContainerIp()).thenReturn(Optional.empty());

        assertEquals(InetAddress.getLocalHost().getHostAddress(),
                EmbeddedDnsServer.resolveServerIp(config, resolver));
    }
}
