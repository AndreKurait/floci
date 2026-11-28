package io.floci.aws.services.elb;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.CertificateReferenceResolver.CertificateMaterial;
import io.floci.aws.services.elb.model.ClassicListener;
import io.floci.aws.services.elb.model.ClassicLoadBalancer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
        assertTrue(config.contains("bind 10.9.1.5:443 ssl crt /usr/local/etc/haproxy/listener-443.pem no-alpn proto h1"));
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

    @Test
    void tlsListenerUsesItsAssignedProtocolAndCipherPolicy() {
        ClassicLoadBalancer loadBalancer = loadBalancer("SSL", "TCP");
        loadBalancer.setPolicies(Map.of("restricted", ClassicSslPolicies.create("restricted",
                "SSLNegotiationPolicyType", Map.of("Protocol-TLSv1.2", "true",
                        "ECDHE-RSA-AES128-GCM-SHA256", "true", "Server-Defined-Cipher-Order", "true"))));
        loadBalancer.setListenerPolicies(Map.of(443, List.of("restricted")));

        String config = renderedConfig(loadBalancer);

        assertTrue(config.contains("no-alpn ssl-min-ver TLSv1.2 ssl-max-ver TLSv1.2"
                + " ciphers ECDHE-RSA-AES128-GCM-SHA256\n"));
        assertFalse(config.contains("proto h1"));
        assertFalse(config.contains("prefer-client-ciphers"));
        assertTrue(config.contains("mode tcp"));
    }

    @Test
    void tlsListenerWithoutAssignmentUsesDefaultPolicy() {
        String config = renderedConfig(loadBalancer("HTTPS", "HTTP"));

        assertTrue(config.contains("no-alpn proto h1 ssl-min-ver TLSv1.0 ssl-max-ver TLSv1.2"));
        assertTrue(config.contains("ECDHE-RSA-AES128-GCM-SHA256"));
        assertTrue(config.contains(":@SECLEVEL=0"));
    }

    @Test
    void missingAssignedPolicyCannotSilentlyRenderDefaultTlsSettings() {
        ClassicLoadBalancer loadBalancer = loadBalancer("HTTPS", "HTTP");
        loadBalancer.setListenerPolicies(Map.of(443, List.of("missing")));

        assertThrows(AwsException.class, () -> renderedConfig(loadBalancer));
    }

    @Test
    void plainHttpPinsHttp1WithoutLoadingCertificatesOrTlsPolicies() {
        ClassicLoadBalancer loadBalancer = loadBalancer("HTTP", "HTTP");
        ElbClassicProxyConfig.Rendered result = ElbClassicProxyConfig.render(loadBalancer, "10.9.1.5",
                List.of(), reference -> { throw new AssertionError("HTTP must not read certificates"); });
        String config = new String(result.files().get("haproxy.cfg"), StandardCharsets.UTF_8);

        assertTrue(config.contains("bind 10.9.1.5:443 proto h1\n"));
        assertFalse(config.contains("ssl-min-ver"));
        assertFalse(config.contains("no-alpn"));
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

    private static String renderedConfig(ClassicLoadBalancer loadBalancer) {
        ElbClassicProxyConfig.Rendered result = ElbClassicProxyConfig.render(loadBalancer, "10.9.1.5",
                List.of(), reference -> new CertificateMaterial("owned", "certificate", "private-key", null));
        return new String(result.files().get("haproxy.cfg"), StandardCharsets.UTF_8);
    }
}
