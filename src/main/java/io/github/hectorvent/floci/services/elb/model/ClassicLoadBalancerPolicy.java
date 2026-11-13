package io.github.hectorvent.floci.services.elb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClassicLoadBalancerPolicy(String policyName, String policyTypeName, Map<String, String> attributes) {
    public ClassicLoadBalancerPolicy {
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
