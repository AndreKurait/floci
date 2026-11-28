package io.floci.aws.services.codedeploy;

import io.floci.aws.core.common.ServiceRoleCallAuthorizer;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;

import static org.mockito.Mockito.mock;

final class CodeDeployTestRoles {
    private CodeDeployTestRoles() {}

    static ServiceRoleCallAuthorizer allowAll() {
        return mock(ServiceRoleCallAuthorizer.class, invocation -> Decision.ALLOW);
    }
}
