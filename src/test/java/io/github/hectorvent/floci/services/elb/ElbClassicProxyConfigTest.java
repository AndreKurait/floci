package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.core.common.CertificateReferenceResolver.CertificateMaterial;
import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElbClassicProxyConfigTest {
    @Test
    void listenersUseTheirOwnCertificateAndNativeBackendPortWithoutPreemptingHealthAdmission() {
        ClassicLoadBalancer loadBalancer = loadBalancer("HTTPS", "HTTPS");
        CertificateMaterial material = new CertificateMaterial("owned", "certificate", "private-key", "chain");
        ElbClassicProxyConfig.Rendered result = ElbClassicProxyConfig.render(loadBalancer, "10.9.1.5",
                List.of(new ElbClassicProxyConfig.Backend("instance", "10.9.1.8")), reference -> {
                    assertEquals("owned", reference);
                    return material;
                });
        String config = new String(result.files().get("haproxy.cfg"), StandardCharsets.UTF_8);
        assertTrue(config.contains("bind 10.9.1.5:443 ssl crt /usr/local/etc/haproxy/listener-443.pem"));
        assertTrue(config.contains("server target_0 10.9.1.8:8443 ssl verify none disabled"));
        assertTrue(config.contains("X-Forwarded-Proto https"));
        assertFalse(config.contains("private-key"));
        assertEquals("certificate\nchain\nprivate-key\n",
                new String(result.files().get("listener-443.pem"), StandardCharsets.UTF_8));
        assertEquals(List.of("listener_443/target_0"), result.servers().get("instance"));
    }

    @Test
    void tcpForwardingAddsNoHttpHeadersOrTls() {
        ElbClassicProxyConfig.Rendered result = ElbClassicProxyConfig.render(loadBalancer("TCP", "TCP"),
                "10.9.1.5", List.of(new ElbClassicProxyConfig.Backend("instance", "10.9.1.8")),
                reference -> { throw new AssertionError("TCP must not read certificates"); });
        String config = new String(result.files().get("haproxy.cfg"), StandardCharsets.UTF_8);
        assertTrue(config.contains("mode tcp"));
        assertTrue(config.contains("server target_0 10.9.1.8:8443 disabled"));
        assertFalse(config.contains("ssl"));
        assertFalse(config.contains("http-request"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"backend.example", "10.9.1.8\n    bind :9999", "::1", "999.9.1.8"})
    void rejectsHostnamesIpv6AndConfigInjectionInBackendAddresses(String address) {
        assertThrows(IllegalArgumentException.class, () -> ElbClassicProxyConfig.render(
                loadBalancer("HTTP", "HTTP"), "10.9.1.5",
                List.of(new ElbClassicProxyConfig.Backend("instance", address)), reference -> null));
    }

    private static ClassicLoadBalancer loadBalancer(String protocol, String backend) {
        ClassicLoadBalancer result = new ClassicLoadBalancer();
        ClassicListener listener = new ClassicListener();
        listener.setProtocol(protocol);
        listener.setInstanceProtocol(backend);
        listener.setLoadBalancerPort(443);
        listener.setInstancePort(8443);
        listener.setSslCertificateId("owned");
        result.setListeners(List.of(listener));
        return result;
    }
}
