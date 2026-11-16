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

/** Operations on existing KMS keys require a resource grant, even within the caller's account. */
@ApplicationScoped
public class KmsResourcePolicyProvider implements ResourcePolicyProvider {
    private final ObjectMapper mapper;
    private final KmsService service;
    private final RequestContext request;

    @Inject
    public KmsResourcePolicyProvider(KmsService service, RequestContext request, ObjectMapper mapper) {
        this.mapper = mapper;
        this.service = service;
        this.request = request;
    }

    public KmsResourcePolicyProvider(KmsService service) {
        this(service, null, new ObjectMapper());
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String scope, String resource) {
        Optional<AwsArnUtils.Arn> parsed = ResourcePolicyProvider.regionalArn(resource, "kms");
        if (parsed.isEmpty()) {
            return List.of();
        }
        AwsArnUtils.Arn arn = parsed.get();
        if (!arn.resource().startsWith("key/") || arn.resource().length() == 4 || arn.resource().contains("*")) {
            return List.of();
        }
        String policy = service.findKeyPolicy(resource).orElse(null);
        if (request == null || service.findKeyArnOfAccount(resource, arn.accountId(), arn.region()).isEmpty()) {
            return List.of(new ResourcePolicy(policy, arn.accountId(), true));
        }
        String previous = request.getAccountId();
        request.setAccountId(arn.accountId());
        try {
            policy = (String) service.getKeyPolicy(resource, arn.region()).get("Policy");
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

    private String grantPolicy(Map<?, ?> grant, String resource, boolean symmetric) {
        List<Map<String, Object>> statements = new ArrayList<>();
        if (grant.get("Operations") instanceof List<?> operations) {
            for (Object operation : operations) {
                statements.add(grantStatement(grant, resource, symmetric, operation.toString()));
            }
        }
        // Grant constraints are literal values, not IAM policy-variable expressions.
        Map<String, Object> policy = Map.of("Version", "2008-10-17", "Statement", statements);
        return mapper.valueToTree(policy).toString();
    }

    private Map<String, Object> grantStatement(Map<?, ?> grant, String resource, boolean symmetric, String operation) {
        Map<String, Object> statement = new LinkedHashMap<>();
        statement.put("Effect", "Allow");
        statement.put("Principal", Map.of("AWS", grant.get("GranteePrincipal")));
        statement.put("Action", "kms:" + operation);
        statement.put("Resource", resource);
        if ("CreateGrant".equals(operation)) {
            statement.put("Condition", KmsGrantConditions.parentConditions(grant));
            return statement;
        }
        Map<String, Object> conditions = new LinkedHashMap<>();
        Map<String, Object> equalities = new LinkedHashMap<>();
        if (grant.get("Constraints") instanceof Map<?, ?> constraints) {
            for (String member : List.of("EncryptionContextSubset", "EncryptionContextEquals")) {
                if (symmetric && !"DescribeKey".equals(operation) && !"RetireGrant".equals(operation)
                        && constraints.get(member) instanceof Map<?, ?> context) {
                    context.forEach((key, value) -> equalities.put("kms:EncryptionContext:" + key, value));
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
            if (!"RetireGrant".equals(operation) && constraints.get("SourceArn") instanceof String sourceArn) {
                equalities.put("aws:SourceArn", sourceArn);
            }
        }
        if (!equalities.isEmpty()) {
            conditions.put("StringEquals", equalities);
        }
        if (!conditions.isEmpty()) {
            statement.put("Condition", conditions);
        }
        return statement;
    }

}
