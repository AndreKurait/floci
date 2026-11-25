package io.floci.aws.services.lambda.launcher;

import com.github.dockerjava.api.model.AuthConfig;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.ecr.registry.EcrWorkloadImagePull;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.lambda.model.LambdaFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Resolves the function's owning identity before a private ECR image enters the cache. */
@ApplicationScoped
public class LambdaImagePullService {
    private final EmulatorConfig config;
    private final ServiceRoleAuthorizer roles;
    private final EcrWorkloadImagePull pulls;

    @Inject
    public LambdaImagePullService(EmulatorConfig config, ServiceRoleAuthorizer roles, EcrWorkloadImagePull pulls) {
        this.config = config;
        this.roles = roles;
        this.pulls = pulls;
    }

    public <T> T pull(LambdaFunction function, String imageUri, Function<AuthConfig, T> operation) {
        if (!config.services().iam().enforcementEnabled() || EcrRegistryManager.ecrImage(imageUri).isEmpty()) {
            return operation.apply(null);
        }
        String functionArn = function.getFunctionArn();
        String accountId = function.getAccountId();
        if (!AwsArnUtils.isArnFor(functionArn, "lambda")) {
            throw denied("The function identity is unavailable.");
        }
        AwsArnUtils.Arn source = AwsArnUtils.parse(functionArn);
        if (!source.resource().startsWith("function:") || source.resource().equals("function:")
                || accountId != null && !source.accountId().equals(accountId)) {
            throw denied("The function identity is unavailable.");
        }
        String roleArn = function.getRole();
        Optional<String> refusal = roles.refusal(roleArn, source.accountId(),
                ServicePrincipals.of("lambda"), functionArn, List.of());
        if (refusal.isPresent()) {
            throw denied(refusal.get());
        }
        return pulls.withLambdaPull(imageUri, roleArn, functionArn, operation);
    }

    private static AwsException denied(String reason) {
        return new AwsException("AccessDeniedException", "Unable to pull function image: " + reason, 403);
    }
}
