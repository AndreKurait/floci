package io.github.hectorvent.floci.core.common.dns;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.CurrentContainerNetworkResolver;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddedDnsServerAddressTest {

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
