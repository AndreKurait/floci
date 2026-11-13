package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.services.eks.model.AccessPolicy;

import java.util.List;

/** Access-policy names from the AWS emulator f515581 catalog, checked on 2026-11-12. */
public final class EksAccessPolicyCatalog {
    private static final List<String> POLICY_NAMES = List.of(
            "AIDevOpsAgentAccessPolicy",
            "AWSBatchClusterPolicy",
            "AWSBatchNamespacePolicy",
            "AmazonAIOpsAssistantPolicy",
            "AmazonARCRegionSwitchScalingPolicy",
            "AmazonEKSACKPolicy",
            "AmazonEKSAdminPolicy",
            "AmazonEKSAdminViewPolicy",
            "AmazonEKSArgoCDClusterPolicy",
            "AmazonEKSArgoCDPolicy",
            "AmazonEKSAutoNodePolicy",
            "AmazonEKSBlockStorageClusterPolicy",
            "AmazonEKSBlockStoragePolicy",
            "AmazonEKSClusterAdminPolicy",
            "AmazonEKSComputeClusterPolicy",
            "AmazonEKSComputePolicy",
            "AmazonEKSEditPolicy",
            "AmazonEKSEventPolicy",
            "AmazonEKSHybridPolicy",
            "AmazonEKSKROPolicy",
            "AmazonEKSLoadBalancingClusterPolicy",
            "AmazonEKSLoadBalancingPolicy",
            "AmazonEKSNetworkingClusterPolicy",
            "AmazonEKSNetworkingPolicy",
            "AmazonEKSPodIdentityPolicy",
            "AmazonEKSSecretAdminPolicy",
            "AmazonEKSSecretReaderPolicy",
            "AmazonEKSServiceAccountImpersonationPolicy",
            "AmazonEKSViewPolicy",
            "AmazonEMRClusterPolicy",
            "AmazonEMRJobPolicy",
            "AmazonSagemakerHyperpodClusterPolicy",
            "AmazonSagemakerHyperpodControllerPolicy",
            "AmazonSagemakerHyperpodInferencePolicy",
            "AmazonSagemakerHyperpodSpacePolicy",
            "AmazonSagemakerHyperpodSpaceTemplatePolicy",
            "AmazonSagemakerHyperpodSystemNamespacePolicy",
            "AmazonSagemakerHyperpodTrainingPolicy",
            "AmazonSagemakerHyperpodUserClusterPolicy");

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
