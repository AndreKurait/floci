package io.floci.aws.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ElbV2DnsNameStrategyConfigTest {
    @ConfigMapping(prefix = "floci.services.elbv2")
    interface Settings extends EmulatorConfig.ElbV2ServiceConfig {}

    @Test
    void defaultsToTheFlociForm() {
        assertEquals(EmulatorConfig.ElbV2ServiceConfig.DnsNameStrategy.FLOCI, settings(Map.of()).dnsNameStrategy());
    }

    @Test
    void selectsTheAwsForm() {
        assertEquals(EmulatorConfig.ElbV2ServiceConfig.DnsNameStrategy.AWS,
                settings(Map.of("floci.services.elbv2.dns-name-strategy", "aws")).dnsNameStrategy());
    }

    @Test
    void rejectsAnUnknownForm() {
        assertThrows(RuntimeException.class,
                () -> settings(Map.of("floci.services.elbv2.dns-name-strategy", "anything")));
    }

    private static Settings settings(Map<String, String> properties) {
        return new SmallRyeConfigBuilder().addDefaultInterceptors().withMapping(Settings.class)
                .withSources(new PropertiesConfigSource(properties, "dns-name-strategy-test", 400))
                .build().getConfigMapping(Settings.class);
    }
}
