package io.github.hectorvent.floci.services.elb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancerPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The supported Classic negotiation policies, captured from the public ELB API. */
public final class ClassicSslPolicies {
    static final String TYPE = "SSLNegotiationPolicyType";
    static final String DEFAULT = "ELBSecurityPolicy-2016-08";
    private static final String REFERENCE = "Reference-Security-Policy";
    private static final String SERVER_ORDER = "Server-Defined-Cipher-Order";
    private static final List<String> PROTOCOLS = List.of("TLSv1", "TLSv1.1", "TLSv1.2");
    private static final List<String> CIPHERS = List.of(
            "ECDHE-ECDSA-AES128-GCM-SHA256", "ECDHE-RSA-AES128-GCM-SHA256",
            "ECDHE-ECDSA-AES128-SHA256", "ECDHE-RSA-AES128-SHA256",
            "ECDHE-ECDSA-AES128-SHA", "ECDHE-RSA-AES128-SHA",
            "ECDHE-ECDSA-AES256-GCM-SHA384", "ECDHE-RSA-AES256-GCM-SHA384",
            "ECDHE-ECDSA-AES256-SHA384", "ECDHE-RSA-AES256-SHA384",
            "ECDHE-ECDSA-AES256-SHA", "ECDHE-RSA-AES256-SHA",
            "AES128-GCM-SHA256", "AES128-SHA256", "AES128-SHA",
            "AES256-GCM-SHA384", "AES256-SHA256", "AES256-SHA");
    private static final Set<String> OTHER_TYPES = Set.of("AppCookieStickinessPolicyType",
            "LBCookieStickinessPolicyType", "BackendServerAuthenticationPolicyType",
            "PublicKeyPolicyType", "ProxyProtocolPolicyType");
    private static final Map<String, ClassicLoadBalancerPolicy> PREDEFINED = load();

    private ClassicSslPolicies() {}

    static Map<String, ClassicLoadBalancerPolicy> predefined() {
        return PREDEFINED;
    }

    public static ClassicLoadBalancerPolicy create(String name, String type, Map<String, String> supplied) {
        requireValue(name, "PolicyName");
        requireValue(type, "PolicyTypeName");
        if (name.startsWith("ELBSecurityPolicy-") || name.startsWith("ELBSample-")) {
            throw invalid("PolicyName uses a prefix reserved for predefined policies.");
        }
        if (!TYPE.equals(type)) {
            throw new AwsException(OTHER_TYPES.contains(type) ? "UnsupportedOperation" : "PolicyTypeNotFound",
                    "Policy type " + type + " is not supported by Floci.", 400);
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        if (supplied.containsKey(REFERENCE)) {
            if (supplied.size() != 1) {
                throw invalid("Reference-Security-Policy must be the only supplied attribute.");
            }
            ClassicLoadBalancerPolicy reference = PREDEFINED.get(supplied.get(REFERENCE));
            if (reference == null) {
                throw new AwsException("UnsupportedOperation",
                        "Reference security policy " + supplied.get(REFERENCE) + " is not supported by Floci.", 400);
            }
            attributes.putAll(reference.attributes());
        } else {
            PREDEFINED.get(DEFAULT).attributes().keySet().forEach(attribute -> attributes.put(attribute, "false"));
            supplied.forEach((attribute, value) -> {
                if (!attributes.containsKey(attribute) || !"true".equals(value) && !"false".equals(value)) {
                    throw invalid("Unknown negotiation attribute or non-boolean value: " + attribute);
                }
                attributes.put(attribute, value);
            });
        }
        ClassicLoadBalancerPolicy policy = new ClassicLoadBalancerPolicy(name, type, attributes);
        options(policy);
        return policy;
    }

    static void requireValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", field + " is required.", 400);
        }
    }

    static boolean tls(ClassicListener listener) {
        return "HTTPS".equalsIgnoreCase(listener.getProtocol()) || "SSL".equalsIgnoreCase(listener.getProtocol());
    }

    static ClassicLoadBalancerPolicy effective(ClassicLoadBalancer loadBalancer, ClassicListener listener) {
        List<String> names = loadBalancer.getListenerPolicies().get(listener.getLoadBalancerPort());
        if (names == null || names.isEmpty()) {
            return PREDEFINED.get(DEFAULT);
        }
        if (names.size() != 1) {
            throw invalid("A TLS listener requires one SSL negotiation policy.");
        }
        ClassicLoadBalancerPolicy policy = loadBalancer.getPolicies().get(names.getFirst());
        if (policy == null) {
            throw new AwsException("PolicyNotFound", "Policy " + names.getFirst() + " does not exist.", 400);
        }
        return policy;
    }

    static Map<Integer, Map<String, String>> listenerSettings(ClassicLoadBalancer loadBalancer) {
        Map<Integer, Map<String, String>> result = new LinkedHashMap<>();
        for (ClassicListener listener : loadBalancer.getListeners()) {
            if (tls(listener)) {
                result.put(listener.getLoadBalancerPort(), effective(loadBalancer, listener).attributes());
            }
        }
        return result;
    }

    static String options(ClassicLoadBalancerPolicy policy) {
        if (!TYPE.equals(policy.policyTypeName())) {
            throw invalid("A TLS listener requires an SSL negotiation policy.");
        }
        List<String> protocols = PROTOCOLS.stream()
                .filter(protocol -> enabled(policy, "Protocol-" + protocol)).toList();
        List<String> ciphers = CIPHERS.stream().filter(cipher -> enabled(policy, cipher)).toList();
        for (Map.Entry<String, String> attribute : policy.attributes().entrySet()) {
            String name = attribute.getKey();
            if ("true".equals(attribute.getValue()) && !SERVER_ORDER.equals(name)
                    && !CIPHERS.contains(name) && PROTOCOLS.stream().noneMatch(p -> name.equals("Protocol-" + p))) {
                throw new AwsException("UnsupportedOperation",
                        "Negotiation attribute " + name + " is not supported by Floci.", 400);
            }
        }
        if (protocols.isEmpty() || ciphers.isEmpty()) {
            throw invalid("An SSL negotiation policy must enable at least one protocol and one cipher.");
        }
        StringBuilder result = new StringBuilder(" ssl-min-ver ")
                .append(version(protocols.getFirst())).append(" ssl-max-ver ").append(version(protocols.getLast()));
        if (!enabled(policy, "Protocol-TLSv1.1") && protocols.contains("TLSv1") && protocols.contains("TLSv1.2")) {
            result.append(" no-tlsv11");
        }
        result.append(" ciphers ").append(String.join(":", ciphers));
        if (protocols.contains("TLSv1") || protocols.contains("TLSv1.1")) {
            // OpenSSL 3 otherwise refuses the legacy protocols explicitly enabled by this policy.
            result.append(":@SECLEVEL=0");
        }
        if (!enabled(policy, SERVER_ORDER)) {
            result.append(" prefer-client-ciphers");
        }
        return result.toString();
    }

    private static boolean enabled(ClassicLoadBalancerPolicy policy, String name) {
        return "true".equals(policy.attributes().get(name));
    }

    private static String version(String value) {
        return "TLSv1".equals(value) ? "TLSv1.0" : value;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidConfigurationRequest", message, 409);
    }

    private static Map<String, ClassicLoadBalancerPolicy> load() {
        try (InputStream input = ClassicSslPolicies.class.getResourceAsStream("/elb/classic-ssl-policies.json")) {
            if (input == null) {
                throw new IllegalStateException("Classic negotiation policy catalog is missing");
            }
            Map<String, Map<String, String>> data = new ObjectMapper().readValue(input, new TypeReference<>() {});
            Map<String, ClassicLoadBalancerPolicy> result = new LinkedHashMap<>();
            data.forEach((name, attributes) ->
                    result.put(name, new ClassicLoadBalancerPolicy(name, TYPE, attributes)));
            return Collections.unmodifiableMap(result);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read Classic negotiation policy catalog", failure);
        }
    }
}
