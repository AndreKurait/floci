package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.List;
import java.util.regex.Pattern;

/** Resolves session policy statements before credentials become visible to any consumer. */
final class StsSessionPolicies {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ARN_MEMBER = Pattern.compile("PolicyArns\\.member\\.[0-9]+\\.arn");

    private StsSessionPolicies() {
    }

    record Resolved(String inline, List<String> managedArns) {
        Resolved {
            managedArns = List.copyOf(managedArns);
        }
    }

    static Resolved resolve(MultivaluedMap<String, String> params, String accountId, String partition,
                          IamService iamService) {
        String inline = params.getFirst("Policy");
        List<String> arns = params.keySet().stream().filter(key -> ARN_MEMBER.matcher(key).matches())
                .sorted().map(params::getFirst).toList();
        int length = inline == null ? 0 : inline.length();
        for (String arn : arns) {
            if (arn == null || arn.isBlank()) {
                throw new AwsException("ValidationError", "Session policy ARN must not be empty.", 400);
            }
            length += arn.length();
        }
        if (arns.size() > 10 || length > 2048) {
            throw new AwsException("ValidationError", "Session policies exceed the request size limit.", 400);
        }
        if (arns.isEmpty()) {
            return new Resolved(inline, List.of());
        }
        if (inline != null) {
            validate(inline);
        }
        for (String arn : arns) {
            AwsArnUtils.Arn parsed;
            try {
                parsed = AwsArnUtils.parse(arn);
            } catch (IllegalArgumentException malformed) {
                throw new AwsException("MalformedPolicyDocument", "Invalid session policy ARN.", 400);
            }
            if (!partition.equals(parsed.partition()) || !"iam".equals(parsed.service())
                    || !parsed.region().isEmpty() || !parsed.resource().startsWith("policy/")
                    || (!accountId.equals(parsed.accountId()) && !AwsManagedPolicies.isManagedPolicyArn(arn))) {
                throw new AwsException("MalformedPolicyDocument", "Session policy must belong to the session account.", 400);
            }
            String document = iamService.sessionPolicyDocument(accountId, arn);
            validate(document);
        }
        return new Resolved(inline, arns);
    }

    private static void validate(String document) {
        try {
            JsonNode policy = MAPPER.readTree(document);
            JsonNode statements = policy == null ? null : policy.get("Statement");
            if (statements == null || (!statements.isObject() && !statements.isArray())) {
                throw new AwsException("MalformedPolicyDocument", "Session policy requires Statement.", 400);
            }
            if (statements.isArray()) {
                for (JsonNode statement : statements) {
                    if (!statement.isObject()) {
                        throw new AwsException("MalformedPolicyDocument", "Invalid session policy statement.", 400);
                    }
                }
            }
        } catch (JsonProcessingException malformed) {
            throw new AwsException("MalformedPolicyDocument", "Invalid session policy document.", 400);
        }
    }
}
