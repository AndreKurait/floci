package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.IamConditionContextResolver;
import io.floci.aws.services.cloudwatch.logs.model.LogDestination;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.floci.aws.services.iam.RequestPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Account grants, checked again for each delivery so revocation stops subsequent data. */
@ApplicationScoped
public class CloudWatchLogsDestinationAccess {
    private static final Set<String> CONDITION_KEYS = Set.of(
            "aws:principalaccount", "aws:resourceaccount", "aws:requestedregion");
    private final IamPolicyEvaluator evaluator;
    private final ObjectMapper mapper;

    @Inject
    public CloudWatchLogsDestinationAccess(IamPolicyEvaluator evaluator, ObjectMapper mapper) {
        this.evaluator = evaluator;
        this.mapper = mapper;
    }

    public void requireAccountAccess(LogDestination destination, String sourceAccount, String sourceRegion) {
        AwsArnUtils.Arn logical = AwsArnUtils.parse(destination.getArn());
        if (logical.accountId().equals(sourceAccount)) {
            return;
        }
        String document = destination.getAccessPolicy();
        requireAccountPolicy(document);
        String principal = new AwsArnUtils.Arn(logical.partition(), "iam", "", sourceAccount, "root").toString();
        Map<String, List<String>> context = IamConditionContextResolver.withGlobalContext(
                Map.of(), logical.toString(), sourceRegion, sourceAccount, logical.accountId(),
                "logs:PutSubscriptionFilter");
        ResourcePolicyDecision decision = evaluator.evaluateResourcePolicyFor(List.of(document),
                RequestPrincipal.iam(principal), "logs:PutSubscriptionFilter", logical.toString(), context);
        if (decision != ResourcePolicyDecision.ALLOW && decision != ResourcePolicyDecision.ALLOW_ACCOUNT) {
            throw denied("The destination access policy does not allow the source account.");
        }
    }

    /**
     * Runtime delivery represents an account, not the IAM identity that originally created a filter.
     * Refuse identity/organization-dependent policies instead of manufacturing that identity.
     */
    private void requireAccountPolicy(String document) {
        if (document == null || document.isBlank()) {
            throw denied("A cross-account destination requires an access policy.");
        }
        if (document.contains("${")) {
            throw denied("Policy variables are unsupported for logical destination delivery.");
        }
        JsonNode policy;
        try {
            policy = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(document);
        } catch (JsonProcessingException failure) {
            throw denied("The destination access policy is not valid JSON.");
        }
        if (policy == null || !policy.isObject()
                || policy.has("Version") && !Set.of("2012-10-17", "2008-10-17").contains(policy.path("Version").asText())) {
            throw denied("The destination access policy has an unsupported document shape or version.");
        }
        JsonNode statements = policy == null ? null : policy.get("Statement");
        List<JsonNode> items = new ArrayList<>();
        if (statements != null && statements.isObject()) {
            items.add(statements);
        } else if (statements != null && statements.isArray()) {
            statements.forEach(items::add);
        } else {
            throw denied("The destination access policy requires statements.");
        }
        for (JsonNode statement : items) {
            if (!statement.isObject() || statement.has("NotPrincipal")
                    || !accountPrincipals(statement.get("Principal"))) {
                throw denied("Logical delivery currently supports account principals and wildcard principals.");
            }
            if (!statement.path("Effect").isTextual()
                    || !Set.of("Allow", "Deny").contains(statement.path("Effect").textValue())
                    || !oneTextMember(statement, "Action", "NotAction")
                    || !oneTextMember(statement, "Resource", "NotResource")) {
                throw denied("Each destination statement requires a valid effect, actions and resources.");
            }
            JsonNode conditions = statement.get("Condition");
            if (conditions != null) {
                if (!conditions.isObject() || conditions.isEmpty()) {
                    throw denied("Destination policy conditions must be nonempty objects.");
                }
                Iterator<Map.Entry<String, JsonNode>> operators = conditions.fields();
                while (operators.hasNext()) {
                    Map.Entry<String, JsonNode> entry = operators.next();
                    if (!IamPolicyEvaluator.isSupportedConditionOperator(entry.getKey())) {
                        throw denied("The destination access policy has an unsupported condition operator.");
                    }
                    JsonNode operator = entry.getValue();
                    if (!operator.isObject() || operator.isEmpty()) {
                        throw denied("Destination policy conditions must be nonempty objects.");
                    }
                    Iterator<Map.Entry<String, JsonNode>> keys = operator.fields();
                    while (keys.hasNext()) {
                        Map.Entry<String, JsonNode> condition = keys.next();
                        if (!CONDITION_KEYS.contains(condition.getKey().toLowerCase(Locale.ROOT))) {
                            throw denied("Logical delivery supports PrincipalAccount, ResourceAccount and "
                                    + "RequestedRegion conditions; identity and organization conditions are unsupported.");
                        }
                        if (!conditionValue(condition.getValue())) {
                            throw denied("Condition values must be scalars or nonempty lists of scalars.");
                        }
                    }
                }
            }
        }
    }

    private static boolean oneTextMember(JsonNode statement, String positive, String negative) {
        if (statement.has(positive) == statement.has(negative)) {
            return false;
        }
        JsonNode value = statement.has(positive) ? statement.get(positive) : statement.get(negative);
        if (value.isTextual()) {
            return !value.textValue().isBlank();
        }
        if (!value.isArray() || value.isEmpty()) {
            return false;
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static boolean conditionValue(JsonNode value) {
        if (value.isTextual() || value.isBoolean() || value.isNumber()) {
            return true;
        }
        if (!value.isArray() || value.isEmpty()) {
            return false;
        }
        for (JsonNode item : value) {
            if (!item.isTextual() && !item.isBoolean() && !item.isNumber()) {
                return false;
            }
        }
        return true;
    }

    private static boolean accountPrincipals(JsonNode principal) {
        if (principal == null) {
            return false;
        }
        if (principal.isObject()) {
            return principal.size() == 1 && principal.has("AWS") && accountPrincipals(principal.get("AWS"));
        }
        if (principal.isArray()) {
            if (principal.isEmpty()) {
                return false;
            }
            for (JsonNode value : principal) {
                if (!accountPrincipals(value) || !value.isTextual()) {
                    return false;
                }
            }
            return true;
        }
        if (!principal.isTextual()) {
            return false;
        }
        String value = principal.textValue();
        return "*".equals(value) || value.matches("[0-9]{12}")
                || value.matches("arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::[0-9]{12}:root");
    }

    private static AwsException denied(String message) {
        return new AwsException("AccessDeniedException", message, 400);
    }
}
