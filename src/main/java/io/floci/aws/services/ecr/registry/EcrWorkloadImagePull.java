package io.floci.aws.services.ecr.registry;

import com.github.dockerjava.api.model.AuthConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ecr.EcrService;
import io.floci.aws.services.ecr.model.Repository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Authorizes an explicitly resolved workload role before entering the image cache. */
@ApplicationScoped
public class EcrWorkloadImagePull {
    private final EcrService ecr;
    private final EcrRegistryAuthorization authorization;

    @Inject
    public EcrWorkloadImagePull(EcrService ecr, EcrRegistryAuthorization authorization) {
        this.ecr = ecr;
        this.authorization = authorization;
    }

    /** Service-specific role selection and trust validation belong to the owning service. */
    @ActivateRequestContext
    public <T> T withRolePull(String imageUri, String roleArn, String sourceArn, Function<AuthConfig, T> pull) {
        return withRolePull(imageUri, roleArn, sourceArn, pull, false, false);
    }

    @ActivateRequestContext
    public <T> T withAuthenticatedRolePull(String imageUri, String roleArn, String sourceArn, Function<AuthConfig, T> pull) {
        return withRolePull(imageUri, roleArn, sourceArn, pull, true, false);
    }

    @ActivateRequestContext
    public <T> T withLambdaPull(String imageUri, String roleArn, String sourceArn, Function<AuthConfig, T> pull) {
        return withRolePull(imageUri, roleArn, sourceArn, pull, false, true);
    }

    @ActivateRequestContext
    public <T> T withEmrServerlessPull(String imageUri, String applicationArn, Function<AuthConfig, T> pull) {
        Optional<EcrRegistryManager.EcrImage> selected = EcrRegistryManager.ecrImage(imageUri);
        if (selected.isEmpty() || !authorization.enforced()) {
            return pull.apply(null);
        }
        EcrRegistryManager.EcrImage image = selected.get();
        Repository repository = ecr.registryRepository(image.repository(), image.registryId(), image.region())
                .orElseThrow(() -> new AwsException("NAME_UNKNOWN", "Repository does not exist", 404));
        String resource = AwsArnUtils.Arn.of("ecr", image.region(), image.registryId(),
                "repository/" + image.repository()).toString();
        Map<String, List<String>> conditions = repositoryConditions(repository);
        String registry = imageUri.substring(0, imageUri.indexOf('/'));
        try (EcrRegistryAuthorization.PullLease lease = authorization.issueEmrServerlessPull(
                applicationArn, resource, repository.getRepositoryPolicyText(), conditions, registry)) {
            return pull.apply(lease.credentials());
        }
    }

    private <T> T withRolePull(String imageUri, String roleArn, String sourceArn,
                               Function<AuthConfig, T> pull, boolean authenticatedPull, boolean lambdaPull) {
        Optional<EcrRegistryManager.EcrImage> selected = EcrRegistryManager.ecrImage(imageUri);
        if (selected.isEmpty() || !authorization.enforced()) {
            return pull.apply(null);
        }
        EcrRegistryManager.EcrImage image = selected.get();
        Repository repository = ecr.registryRepository(image.repository(), image.registryId(), image.region())
                .orElseThrow(() -> new AwsException("NAME_UNKNOWN", "Repository does not exist", 404));
        String resource = AwsArnUtils.Arn.of("ecr", image.region(), image.registryId(),
                "repository/" + image.repository()).toString();
        Map<String, List<String>> conditions = repositoryConditions(repository);
        String registry = imageUri.substring(0, imageUri.indexOf('/'));
        EcrRegistryAuthorization.PullLease issued;
        if (lambdaPull) {
            issued = authorization.issueLambdaPull(
                    roleArn, sourceArn, resource, repository.getRepositoryPolicyText(), conditions, registry);
        } else if (authenticatedPull) {
            issued = authorization.issueAuthenticatedRolePull(
                    roleArn, sourceArn, resource, repository.getRepositoryPolicyText(), conditions, registry);
        } else {
            issued = authorization.issueRolePull(
                    roleArn, sourceArn, resource, repository.getRepositoryPolicyText(), conditions, registry);
        }
        try (EcrRegistryAuthorization.PullLease lease = issued) {
            return pull.apply(lease.credentials());
        }
    }

    private static Map<String, List<String>> repositoryConditions(Repository repository) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        repository.getTags().forEach((key, value) -> {
            conditions.put("aws:ResourceTag/" + key, List.of(value));
            conditions.put("ecr:ResourceTag/" + key, List.of(value));
        });
        return conditions;
    }
}
