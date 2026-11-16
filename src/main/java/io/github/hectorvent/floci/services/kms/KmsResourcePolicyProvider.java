package io.github.hectorvent.floci.services.kms;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Operations on existing KMS keys require a resource grant, even within the caller's account. */
@ApplicationScoped
public class KmsResourcePolicyProvider implements ResourcePolicyProvider {
    private final ObjectMapper mapper;
    private final KmsService service;
    private final RequestContext request;

    public KmsResourcePolicyProvider(KmsService service, RequestContext request, ObjectMapper mapper) {
        this.mapper = mapper;
        this.service = service;
        this.request = request;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String scope, String resource) {
        if (!"kms".equals(scope) || !AwsArnUtils.isArnFor(resource, "kms")) {
            return List.of();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resource);
        if (!arn.resource().startsWith("key/") || arn.resource().length() == 4 || arn.resource().contains("*")) {
            return List.of();
        }
        String previous = request.getAccountId();
        request.setAccountId(arn.accountId());
        try {
            String policy = (String) service.getKeyPolicy(resource, arn.region()).get("Policy");
            List<ResourcePolicy> policies = new ArrayList<>();
            policies.add(new ResourcePolicy(policy, arn.accountId(), true));
            String marker = null;
            do {
                Map<String, Object> page = service.listGrants(resource, arn.region(), marker, 100, null, null);
                if (page.get("Grants") instanceof List<?> grants) {
                    for (Object entry : grants) {
                        if (entry instanceof Map<?, ?> grant) {
                            policies.add(new ResourcePolicy(grantPolicy(grant, resource, "SYMMETRIC_DEFAULT".equals(service.describeKey(resource, arn.region()).getKeySpec().name())), arn.accountId(), true));
                        }
                    }
                }
                marker = (String) page.get("NextMarker");
            } while (marker != null);
            return policies;
        } finally {
            request.setAccountId(previous);
        }
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String scope, String action, String resource) {
        return action.startsWith("kms:") ? getResourcePolicies(scope, resource) : List.of();
    }
    private String grantPolicy(Map<?, ?> grant, String resource, boolean symmetric) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("Effect", "Allow");
        statement.put("Principal", Map.of("AWS", grant.get("GranteePrincipal")));
        List<String> actions = new ArrayList<>();
        if (grant.get("Operations") instanceof List<?> operations) {
            operations.forEach(operation -> actions.add("kms:" + operation));
        }
        statement.put("Action", actions);
        statement.put("Resource", resource);
        Map<String, Object> conditions = new LinkedHashMap<>();
        Map<String, Object> equalities = new LinkedHashMap<>();
        if (grant.get("Constraints") instanceof Map<?, ?> constraints) {
            for (String member : List.of("EncryptionContextSubset", "EncryptionContextEquals")) {
                if (symmetric && constraints.get(member) instanceof Map<?, ?> context) {
                    context.forEach((key, value) -> equalities.put(KmsGrantConditions.encryptionContextKey(key.toString()), value));
                    if ("EncryptionContextEquals".equals(member)) {
                        if (context.isEmpty()) {
                            conditions.put("Null", Map.of("kms:EncryptionContextKeys", "true"));
                        } else {
                            conditions.put("ForAllValues:StringEquals", Map.of("kms:EncryptionContextKeys",
                                    context.keySet().stream().map(Object::toString).toList()));
                        }
                    }
                }
            }
            if (constraints.get("SourceArn") instanceof String sourceArn) {
                equalities.put("aws:SourceArn", sourceArn);
            }
        }
        if (!equalities.isEmpty()) {
            conditions.put("StringEquals", equalities);
        }
        if (!conditions.isEmpty()) {
            statement.put("Condition", conditions);
        }
        Map<String, Object> policy = Map.of("Version", "2012-10-17", "Statement", List.of(statement));
        return mapper.valueToTree(policy).toString();
    }

}
