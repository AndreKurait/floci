package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RequestContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Existing tags on each resource named by an ELBv2 Query request. */
@ApplicationScoped
public class ElbV2ConditionKeys {

    private final Instance<ElbV2Service> service;
    private final RequestContext requestContext;
    private final EmulatorConfig config;

    public ElbV2ConditionKeys(Instance<ElbV2Service> service, RequestContext requestContext,
                             EmulatorConfig config) {
        this.service = service;
        this.requestContext = requestContext;
        this.config = config;
    }

    public List<Map<String, List<String>>> resourceContexts(String action, Map<String, String> form) {
        if (action.startsWith("elasticloadbalancing:Create")) {
            return List.of();
        }
        List<String> arns = new ArrayList<>();
        for (String parameter : List.of("TargetGroupArn", "LoadBalancerArn", "ListenerArn", "RuleArn")) {
            if (form.containsKey(parameter)) {
                arns.add(form.get(parameter));
            }
        }
        for (String parameter : List.of("ResourceArns", "TargetGroupArns", "LoadBalancerArns",
                "ListenerArns", "RuleArns")) {
            for (int i = 1; form.containsKey(parameter + ".member." + i); i++) {
                arns.add(form.get(parameter + ".member." + i));
            }
        }
        List<Map<String, List<String>>> contexts = new ArrayList<>();
        for (String arn : arns) {
            contexts.add(resourceContext(arn));
        }
        return contexts;
    }

    private Map<String, List<String>> resourceContext(String arn) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        String region = requestContext.getRegion() == null ? config.defaultRegion() : requestContext.getRegion();
        String account = requestContext.getAccountId() == null
                ? config.defaultAccountId() : requestContext.getAccountId();
        Optional<AwsArnUtils.Arn> parsed = AwsArnUtils.parseIfArnIn(arn, "elasticloadbalancing", region);
        if (parsed.isEmpty() || !account.equals(parsed.get().accountId()) || !service.isResolvable()) {
            return conditions;
        }
        service.get().describeTags(List.of(arn)).getOrDefault(arn, Map.of())
                .forEach((key, value) -> conditions.put("aws:ResourceTag/" + key, List.of(value)));
        return conditions;
    }
}
