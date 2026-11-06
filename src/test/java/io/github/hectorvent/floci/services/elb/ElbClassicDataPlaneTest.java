package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElbClassicDataPlaneTest {

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "LOCALHOST.", "s3.localhost", "127.0.0.1", "[::1]",
            "192.0.2.100", "localhost.floci.io", "s3.localhost.floci.io.",
            "localhost.localstack.cloud", "custom.floci.test", "s3.custom.floci.test",
            "configured-base.test", "service.extra.test", "s3.amazonaws.com"})
    void emulatorAuthoritiesCannotBeClaimedByPublicAliases(String host) {
        assertTrue(ElbClassicDataPlane.isEmulatorHost(host, config()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"public.example.test", "localhost.floci.io.outside.test",
            "notlocalhost", "extra.test.outside.test"})
    void customerAuthoritiesRemainEligibleForPublicAliases(String host) {
        assertFalse(ElbClassicDataPlane.isEmulatorHost(host, config()));
    }

    private static EmulatorConfig config() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DnsConfig dns = mock(EmulatorConfig.DnsConfig.class);
        when(config.hostname()).thenReturn(Optional.of("custom.floci.test"));
        when(config.baseUrl()).thenReturn("http://configured-base.test:4566");
        when(config.dns()).thenReturn(dns);
        when(dns.extraSuffixes()).thenReturn(Optional.of(List.of("EXTRA.TEST.")));
        when(dns.spoofAwsEndpoints()).thenReturn(true);
        return config;
    }
}
