package io.github.hectorvent.floci.services.elb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancerPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassicSslPoliciesTest {
    @Test
    void tls12ReferenceUsesOnlyItsCapturedProtocolsAndCipherList() {
        ClassicLoadBalancerPolicy policy = ClassicSslPolicies.create("tls12", ClassicSslPolicies.TYPE,
                Map.of("Reference-Security-Policy", "ELBSecurityPolicy-TLS-1-2-2017-01"));
        String actual = ClassicSslPolicies.options(policy);
        assertTrue(actual.startsWith(" ssl-min-ver TLSv1.2 ssl-max-ver TLSv1.2 ciphers "));
        assertFalse(actual.contains("@SECLEVEL"));
        assertFalse(actual.contains("prefer-client-ciphers"));
        List<String> ciphers = List.of(actual.substring(actual.indexOf(" ciphers ") + 9).split(":"));
        assertEquals(12, ciphers.size());
        assertEquals("ECDHE-ECDSA-AES128-GCM-SHA256", ciphers.getFirst());
        assertTrue(ciphers.contains("ECDHE-RSA-AES128-GCM-SHA256"));
        assertFalse(ciphers.contains("ECDHE-RSA-AES128-SHA"));
    }

    @Test
    void customPolicyCanSelectClientOrderAndDisableAnIntermediateProtocol() {
        ClassicLoadBalancerPolicy policy = ClassicSslPolicies.create("custom", ClassicSslPolicies.TYPE, Map.of(
                "Protocol-TLSv1", "true", "Protocol-TLSv1.2", "true", "AES128-SHA256", "true"));
        assertEquals(" ssl-min-ver TLSv1.0 ssl-max-ver TLSv1.2 no-tlsv11"
                + " ciphers AES128-SHA256:@SECLEVEL=0 prefer-client-ciphers", ClassicSslPolicies.options(policy));
    }

    @ParameterizedTest
    @MethodSource("invalidAttributes")
    void unusableOrUnsupportedConfigurationsCannotBeStored(Map<String, String> attributes) {
        assertThrows(AwsException.class,
                () -> ClassicSslPolicies.create("invalid", ClassicSslPolicies.TYPE, attributes));
    }

    static Stream<Map<String, String>> invalidAttributes() {
        return Stream.of(Map.of(), Map.of("Protocol-TLSv1.2", "true"),
                Map.of("AES128-SHA256", "true"),
                Map.of("Reference-Security-Policy", "unknown"),
                Map.of("Reference-Security-Policy", "ELBSecurityPolicy-2016-08", "Protocol-TLSv1", "false"),
                Map.of("Protocol-TLSv1.2", "true", "AES128-SHA256", "yes"),
                Map.of("Protocol-TLSv1.2", "true", "AES128-SHA256\nbind :9999", "true"),
                Map.of("Protocol-TLSv1.2", "true", "DES-CBC3-SHA", "true"),
                Map.of("Protocol-SSLv3", "true", "AES128-SHA256", "true"));
    }

    @Test
    void unknownTypeAndRecognizedUnsupportedTypesHaveDistinctErrors() {
        assertEquals("PolicyTypeNotFound", assertThrows(AwsException.class,
                () -> ClassicSslPolicies.create("name", "does-not-exist", Map.of())).getErrorCode());
        assertEquals("UnsupportedOperation", assertThrows(AwsException.class,
                () -> ClassicSslPolicies.create("name", "ProxyProtocolPolicyType", Map.of())).getErrorCode());
    }

    @Test
    void policyAndListenerBindingsSurviveThePersistedModelSerialization() throws Exception {
        Map<String, String> input = new LinkedHashMap<>(Map.of("Protocol-TLSv1.2", "true", "AES128-SHA256", "true"));
        ClassicLoadBalancerPolicy policy = ClassicSslPolicies.create("named", ClassicSslPolicies.TYPE, input);
        ClassicLoadBalancer original = new ClassicLoadBalancer();
        original.setPolicies(Map.of("named", policy));
        original.setListenerPolicies(Map.of(443, List.of("named")));
        input.put("Protocol-TLSv1", "true");
        ObjectMapper mapper = new ObjectMapper();
        ClassicLoadBalancer restored = mapper.readValue(mapper.writeValueAsBytes(original), ClassicLoadBalancer.class);

        assertEquals(original.getPolicies(), restored.getPolicies());
        assertEquals(original.getListenerPolicies(), restored.getListenerPolicies());
        assertEquals("false", restored.getPolicies().get("named").attributes().get("Protocol-TLSv1"));
        assertThrows(UnsupportedOperationException.class,
                () -> restored.getPolicies().get("named").attributes().put("Protocol-TLSv1", "true"));
    }
}
