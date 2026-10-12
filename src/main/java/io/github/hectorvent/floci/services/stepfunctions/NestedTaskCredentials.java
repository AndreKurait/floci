package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Set;

@ApplicationScoped
public class NestedTaskCredentials {
    private final IamService iam;
    private final EmulatorConfig config;

    @Inject
    public NestedTaskCredentials(IamService iam, EmulatorConfig config) {
        this.iam = iam;
        this.config = config;
    }

    String account(JsonNode credentials, String resource, StateMachine machine) {
        StatesIntegration integration = StatesIntegration.parse(resource).orElse(null);
        boolean nested = integration != null
                && ((integration.isAnySuffix("states", "startExecution")
                    && Set.of("", ".sync", ".sync:2").contains(integration.suffix()))
                    || integration.isSdk("sfn", "startExecution")
                    || integration.isSdk("sfn", "startSyncExecution"));
        if (!nested || config.services().iam().enforcementEnabled()) {
            throw new AslExecutor.FailStateException("States.Runtime",
                    "Task Credentials are supported only for nested executions with IAM enforcement disabled");
        }
        if (!credentials.isObject() || credentials.size() != 1 || !credentials.path("RoleArn").isTextual()) {
            throw new AslExecutor.FailStateException("States.Runtime",
                    "Task Credentials must resolve to an object containing RoleArn");
        }
        String roleArn = credentials.path("RoleArn").asText();
        AwsArnUtils.Arn role;
        try {
            role = AwsArnUtils.parse(roleArn);
        } catch (IllegalArgumentException error) {
            throw new AslExecutor.FailStateException("States.Runtime", "Task Credentials RoleArn is invalid");
        }
        AwsArnUtils.Arn owner = AwsArnUtils.parse(machine.getStateMachineArn());
        if (!"iam".equals(role.service()) || !role.region().isEmpty()
                || !role.resource().startsWith("role/") || !role.accountId().matches("\\d{12}")
                || !role.partition().equals(owner.partition())) {
            throw new AslExecutor.FailStateException("States.Runtime",
                    "Task Credentials must name an IAM role in the execution partition");
        }
        String name = role.resource().substring(role.resource().lastIndexOf('/') + 1);
        if (iam.findRole(role.accountId(), name).filter(found -> IamService.roleArnMatches(roleArn, found)).isEmpty()) {
            throw new AslExecutor.FailStateException("States.Permissions", "Task Credentials role does not exist");
        }
        return role.accountId();
    }
}
