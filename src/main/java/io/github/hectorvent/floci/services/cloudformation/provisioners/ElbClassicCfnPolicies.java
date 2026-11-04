package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.elb.ClassicSslPolicies;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancerPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reconciles the policy children of one Classic CloudFormation resource. */
final class ElbClassicCfnPolicies {
    static final String MANAGED = "__FlociClassicManagedPolicies";
    private static final String DEFAULT = "ELBSecurityPolicy-2016-08";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    record Plan(Map<String, ClassicLoadBalancerPolicy> policies, Map<Integer, List<String>> bindings) {}

    private ElbClassicCfnPolicies() {}

    static Plan prepare(JsonNode props, List<ClassicListener> listeners) {
        Map<Integer, List<String>> bindings = new LinkedHashMap<>();
        Set<Integer> ports = new LinkedHashSet<>();
        for (int index = 0; index < listeners.size(); index++) {
            int port = listeners.get(index).getLoadBalancerPort();
            ports.add(port);
            JsonNode listener = props.path("Listeners").get(index);
            if (listener.hasNonNull("PolicyNames")) {
                bindings.put(port, strings(listener, "PolicyNames"));
            }
        }
        Map<String, ClassicLoadBalancerPolicy> policies = new LinkedHashMap<>();
        for (JsonNode policy : array(props, "Policies")) {
            if (!array(policy, "InstancePorts").isEmpty()) {
                throw new AwsException("UnsupportedOperation",
                        "Classic backend policy InstancePorts are not supported by Floci.", 400);
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            for (JsonNode attribute : array(policy, "Attributes")) {
                String name = attribute.path("Name").asText();
                if (attributes.putIfAbsent(name, attribute.path("Value").asText()) != null) {
                    throw invalid("Repeated Classic policy attribute " + name);
                }
            }
            ClassicLoadBalancerPolicy desired = ClassicSslPolicies.create(policy.path("PolicyName").asText(),
                    policy.path("PolicyType").asText(), attributes);
            if (policies.putIfAbsent(desired.policyName(), desired) != null) {
                throw invalid("Repeated Classic PolicyName " + desired.policyName());
            }
            for (JsonNode value : array(policy, "LoadBalancerPorts")) {
                int port;
                try {
                    port = Integer.parseInt(value.asText());
                } catch (NumberFormatException failure) {
                    throw invalid("Classic policy LoadBalancerPorts must be integers.");
                }
                if (!ports.contains(port)) {
                    throw invalid("Classic policy references an undeclared listener port " + port);
                }
                Set<String> combined = new LinkedHashSet<>(bindings.getOrDefault(port, List.of()));
                combined.add(desired.policyName());
                bindings.put(port, List.copyOf(combined));
            }
        }
        for (ClassicListener listener : listeners) {
            boolean tls = "HTTPS".equalsIgnoreCase(listener.getProtocol()) || "SSL".equalsIgnoreCase(listener.getProtocol());
            bindings.putIfAbsent(listener.getLoadBalancerPort(), tls ? List.of(DEFAULT) : List.of());
            List<String> names = bindings.get(listener.getLoadBalancerPort());
            if (names.size() > 1 || !tls && !names.isEmpty()) {
                throw invalid("An SSL negotiation policy requires one HTTPS or SSL listener policy.");
            }
        }
        return new Plan(Collections.unmodifiableMap(policies), Collections.unmodifiableMap(bindings));
    }

    static void apply(ElbClassicService service, String region, String name, Plan plan, StackResource resource) {
        List<String> previous = managed(resource);
        List<String> temporary = new ArrayList<>();
        for (ClassicLoadBalancerPolicy desired : plan.policies().values()) {
            ClassicLoadBalancerPolicy current = service.requireLoadBalancer(region, name)
                    .getPolicies().get(desired.policyName());
            if (desired.equals(current)) {
                continue;
            }
            if (current != null) {
                Map<Integer, List<String>> bindings = service.requireLoadBalancer(region, name).getListenerPolicies();
                List<Integer> attached = bindings.entrySet().stream()
                        .filter(entry -> entry.getValue().contains(current.policyName())).map(Map.Entry::getKey).toList();
                if (!attached.isEmpty()) {
                    String copy = "cfn-policy-" + UUID.randomUUID();
                    service.createLoadBalancerPolicy(region, name, copy, current.policyTypeName(), current.attributes());
                    temporary.add(copy);
                    for (int port : attached) {
                        service.setLoadBalancerPoliciesOfListener(region, name, port, List.of(copy));
                    }
                }
                service.deleteLoadBalancerPolicy(region, name, desired.policyName());
            }
            service.createLoadBalancerPolicy(region, name, desired.policyName(),
                    desired.policyTypeName(), desired.attributes());
        }
        for (Map.Entry<Integer, List<String>> binding : plan.bindings().entrySet()) {
            List<String> actual = service.requireLoadBalancer(region, name).getListenerPolicies()
                    .getOrDefault(binding.getKey(), List.of());
            if (!actual.equals(binding.getValue())) {
                service.setLoadBalancerPoliciesOfListener(region, name, binding.getKey(), binding.getValue());
            }
        }
        for (String policy : previous) {
            if (!plan.policies().containsKey(policy)) {
                service.deleteLoadBalancerPolicy(region, name, policy);
            }
        }
        for (String policy : temporary) {
            service.deleteLoadBalancerPolicy(region, name, policy);
        }
        resource.getAttributes().put(MANAGED, MAPPER.valueToTree(plan.policies().keySet()).toString());
    }

    private static List<String> managed(StackResource resource) {
        String encoded = resource.getAttributes().get(MANAGED);
        if (encoded == null) {
            return List.of();
        }
        try {
            return MAPPER.readValue(encoded, new TypeReference<>() {});
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot read the Classic resource's managed-policy snapshot", failure);
        }
    }

    private static List<String> strings(JsonNode parent, String property) {
        List<String> result = new ArrayList<>();
        array(parent, property).forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static JsonNode array(JsonNode parent, String property) {
        JsonNode value = parent.path(property);
        if (!value.isMissingNode() && !value.isNull() && !value.isArray()) {
            throw invalid("Classic " + property + " must be an array.");
        }
        return value;
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", message, 400);
    }
}
