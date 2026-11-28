package io.floci.aws.services.eks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.VendoredResources;
import io.floci.aws.services.eks.model.AccessPolicy;

import java.util.ArrayList;
import java.util.List;

/** Access-policy names from the vendored EKS ListAccessPolicies catalog. */
public final class EksAccessPolicyCatalog {
    private static final List<String> POLICY_NAMES = VendoredResources.read("eks/access-policies.json",
            "EKS access policy catalog", in -> names(new ObjectMapper().readTree(in)));

    private static List<String> names(JsonNode catalog) {
        List<String> names = new ArrayList<>();
        catalog.path("accessPolicies").forEach(policy -> names.add(policy.path("name").asText()));
        return List.copyOf(names);
    }

    private EksAccessPolicyCatalog() {}

    public static PaginatedResult<AccessPolicy> list(String partition, Integer maxResults, String nextToken) {
        if (maxResults != null && (maxResults < 1 || maxResults > 100)) {
            throw new AwsException("InvalidParameterException", "maxResults must be between 1 and 100", 400);
        }
        List<AccessPolicy> policies = POLICY_NAMES.stream()
                .map(name -> new AccessPolicy(AwsArnUtils.Arn.global(
                        partition, "eks", "aws", "cluster-access-policy/" + name).toString(), name)) // partition-literal: AWS-owned policy account.
                .toList();
        return Pagination.paginate(policies, AccessPolicy::name, maxResults == null ? 100 : maxResults,
                nextToken, "eks:access-policies:v1:" + partition,
                token -> new AwsException("InvalidParameterException", "Invalid nextToken.", 400));
    }
}
