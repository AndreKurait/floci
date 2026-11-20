package io.floci.aws.services.ecs.container;

import com.github.dockerjava.api.model.AuthConfig;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import io.floci.aws.services.ecr.registry.EcrWorkloadImagePull;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.LaunchType;
import io.floci.aws.services.ecs.model.TaskDefinition;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.iam.model.IamRole;
import io.floci.aws.services.iam.model.InstanceProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Selects the agent's image-pull identity independently of the task's application credentials. */
@ApplicationScoped
public class EcsImagePullService {
    private record PullRole(String arn, String servicePrincipal, String sourceArn) {}

    private final EmulatorConfig config;
    private final Ec2Service ec2;
    private final IamService iam;
    private final ServiceRoleAuthorizer roles;
    private final EcrWorkloadImagePull pulls;

    @Inject
    public EcsImagePullService(EmulatorConfig config, Ec2Service ec2, IamService iam,
                              ServiceRoleAuthorizer roles, EcrWorkloadImagePull pulls) {
        this.config = config;
        this.ec2 = ec2;
        this.iam = iam;
        this.roles = roles;
        this.pulls = pulls;
    }

    public <T> T pull(EcsTask task, TaskDefinition definition, String containerInstanceId,
                      String imageUri, Function<Supplier<AuthConfig>, T> operation) {
        if (!config.services().iam().enforcementEnabled() || EcrRegistryManager.ecrImage(imageUri).isEmpty()) {
            return operation.apply(null);
        }
        if (!AwsArnUtils.isArnFor(task.getTaskArn(), "ecs")) {
            throw denied("The task identity is unavailable.");
        }
        AwsArnUtils.Arn source = AwsArnUtils.parse(task.getTaskArn());
        if (!source.resource().startsWith("task/")) {
            throw denied("The task identity is unavailable.");
        }
        PullRole role = switch (task.getLaunchType()) {
            case EC2 -> instanceRole(source, containerInstanceId);
            case FARGATE, EXTERNAL, MANAGED_INSTANCES -> new PullRole(executionRole(task, definition),
                    ServicePrincipals.of("ecs-tasks"), task.getTaskArn());
            case null, default -> throw denied("The image-pull identity for this launch type is unavailable.");
        };
        Optional<String> refusal = roles.refusal(role.arn(), source.accountId(),
                role.servicePrincipal(), role.sourceArn(), List.of());
        if (refusal.isPresent()) {
            throw denied(refusal.get());
        }
        return pulls.withAuthenticatedRolePull(imageUri, role.arn(), task.getTaskArn(), operation);
    }

    private static String executionRole(EcsTask task, TaskDefinition definition) {
        String override = task.getOverrides() == null ? null : task.getOverrides().getExecutionRoleArn();
        String role = override != null ? override : definition.getExecutionRoleArn();
        if (role == null || role.isBlank()) {
            throw denied("The task execution role is unavailable.");
        }
        return role;
    }

    private PullRole instanceRole(AwsArnUtils.Arn task, String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw denied("The container instance is unavailable.");
        }
        Instance instance = ec2.findInstanceForAccount(task.accountId(), task.region(), instanceId)
                .orElseThrow(() -> denied("The container instance is unavailable."));
        String profileArn = instance.getIamInstanceProfileArn();
        if (!AwsArnUtils.isArnFor(profileArn, "iam")) {
            throw denied("The container instance profile is unavailable.");
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(profileArn);
        if (!parsed.resource().startsWith("instance-profile/") || !parsed.accountId().equals(task.accountId())
                || !parsed.partition().equals(task.partition())) {
            throw denied("The container instance profile is unavailable.");
        }
        String name = parsed.resource().substring(parsed.resource().lastIndexOf('/') + 1);
        InstanceProfile profile = iam.findInstanceProfile(task.accountId(), name)
                .filter(found -> profileArn.equals(found.getArn()))
                .orElseThrow(() -> denied("The container instance profile is unavailable."));
        if (profile.getRoleNames().size() != 1 || instance.getIamInstanceProfileId() != null
                && !instance.getIamInstanceProfileId().equals(profile.getInstanceProfileId())) {
            throw denied("The container instance profile is unavailable.");
        }
        IamRole role = iam.findRole(task.accountId(), profile.getRoleNames().getFirst())
                .orElseThrow(() -> denied("The container instance role is unavailable."));
        String source = AwsArnUtils.Arn.of("ec2", task.region(), task.accountId(), "instance/" + instanceId).toString();
        return new PullRole(role.getArn(), ServicePrincipals.of("ec2"), source);
    }

    private static AwsException denied(String reason) {
        return new AwsException("ResourceInitializationError", "Unable to pull container image: " + reason, 400);
    }
}
