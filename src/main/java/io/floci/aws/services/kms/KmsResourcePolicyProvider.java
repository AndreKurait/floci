package io.floci.aws.services.kms;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Supplies owner-scoped key policies and grants without changing request or storage state. */
@ApplicationScoped
public class KmsResourcePolicyProvider implements ResourcePolicyProvider {
    private static final String KEY_PREFIX = "key/";
    private final ObjectMapper mapper;
    private final KmsService service;

    @Inject
    public KmsResourcePolicyProvider(KmsService service, ObjectMapper mapper) {
        this.mapper = mapper;
        this.service = service;
    }

    public KmsResourcePolicyProvider(KmsService service) {
        this(service, new ObjectMapper());
    }

    public KmsResourcePolicyProvider(KmsService service, RequestContext request, ObjectMapper mapper) {
        this(service, mapper);
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String scope, String resource) {
        Optional<AwsArnUtils.Arn> parsed = ResourcePolicyProvider.regionalArn(resource, "kms");
        if (parsed.isEmpty()) {
            return List.of();
        }
        AwsArnUtils.Arn arn = parsed.get();
        if (!arn.resource().startsWith(KEY_PREFIX) || arn.resource().length() == KEY_PREFIX.length()
                || arn.resource().contains("*")) {
            return List.of();
        }
        Optional<KmsService.KeyPolicyMaterial> material = service.findKeyPolicyMaterial(resource);
        List<ResourcePolicy> policies = new ArrayList<>();
        policies.add(new ResourcePolicy(material.map(KmsService.KeyPolicyMaterial::policy).filter(policy -> !policy.isBlank()).orElse(null),
                arn.accountId(), true));
        material.ifPresent(key -> key.grants().forEach(grant -> policies.add(
                new ResourcePolicy(grantPolicy(grant, resource, key.symmetric()), arn.accountId(), true))));
        return List.copyOf(policies);
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String scope, String action, String resource) {
        return action != null && action.startsWith("kms:") ? getResourcePolicies(scope, resource) : List.of();
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
                            conditions.put("ForAllValues:StringEqualsIgnoreCase", Map.of("kms:EncryptionContextKeys",
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
