package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Evaluates repository API requests against the target registry's policy and owner, including
 * absent repositories, so an identity grant alone cannot authorize cross-account access.
 */
@ApplicationScoped
public class EcrResourcePolicyProvider implements ResourcePolicyProvider {

    private final EcrService service;

    @Inject
    public EcrResourcePolicyProvider(EcrService service) {
        this.service = service;
    }

    @Override
    public List<ResourcePolicy> getResourcePolicies(String credentialScope, String resourceArn) {
        if (!AwsArnUtils.isArnFor(resourceArn, "ecr")) {
            return List.of();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(resourceArn);
        if (!arn.accountId().matches("\\d{12}") || arn.region().isBlank()
                || !arn.resource().startsWith("repository/")) {
            return List.of();
        }
        String name = arn.resource().substring("repository/".length());
        String policy = service.registryRepository(name, arn.accountId(), arn.region())
                .map(Repository::getRepositoryPolicyText).orElse(null);
        return List.of(new ResourcePolicy(policy, arn.accountId()));
    }
}
