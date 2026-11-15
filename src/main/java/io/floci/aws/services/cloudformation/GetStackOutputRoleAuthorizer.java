package io.floci.aws.services.cloudformation;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.iam.AccessDeniedMessages;
import io.floci.aws.services.iam.AccessDeniedMessages.PolicyType;
import io.floci.aws.services.iam.AssumeRolePolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator.SimulationDecision;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether CloudFormation may read another stack's outputs through the role a
 * {@code Fn::GetStackOutput} names. On AWS CloudFormation assumes the role as the stack's
 * execution role, or without one as the caller of the stack operation, and reads the outputs with
 * DescribeStacks in a session named {@code GetStackOutput} (measured, eu-west-1, 2026-10-16).
 * Floci preserves its account-based output-role checks independently of the verified caller
 * snapshot exposed to resource providers. The role has to exist, and with IAM enforcement on,
 * its trust policy has to allow a principal in the
 * consuming account and its policies have to allow DescribeStacks on the stack, as
 * {@code AssumeRole} and {@code AppSyncDataSourceAuthorizer} evaluate a role.
 */
@ApplicationScoped
public class GetStackOutputRoleAuthorizer {

    private static final String DESCRIBE_STACKS = "cloudformation:DescribeStacks";
    private static final String SESSION_NAME = "GetStackOutput";

    private final IamService iamService;
    private final AssumeRolePolicyEvaluator trustEvaluator;
    private final IamPolicyEvaluator policyEvaluator;
    private final EmulatorConfig config;
    private final RequestContext requestContext;

    public GetStackOutputRoleAuthorizer(IamService iamService, AssumeRolePolicyEvaluator trustEvaluator,
                                        IamPolicyEvaluator policyEvaluator, EmulatorConfig config) {
        this(iamService, trustEvaluator, policyEvaluator, config, null);
    }

    @Inject
    public GetStackOutputRoleAuthorizer(IamService iamService, AssumeRolePolicyEvaluator trustEvaluator,
                                        IamPolicyEvaluator policyEvaluator, EmulatorConfig config,
                                        RequestContext requestContext) {
        this.iamService = iamService;
        this.trustEvaluator = trustEvaluator;
        this.policyEvaluator = policyEvaluator;
        this.config = config;
        this.requestContext = requestContext;
    }

    /**
     * Returns the account whose stacks {@code roleArn} reads for a stack in {@code consumerAccount}.
     *
     * @throws AwsException STS's AccessDenied, as CloudFormation reports it, when the role does not
     *                      exist in {@code partition} or, with IAM enforcement on, does not trust
     *                      the consuming account
     */
    public String assume(String roleArn, String consumerAccount, String partition) {
        Optional<IamRole> role = role(roleArn, partition);
        if (role.isEmpty() || (enforced() && !trustEvaluator.allowsAnyPrincipalOf(
                role.get().getAssumeRolePolicyDocument(), consumerAccount, partition))) {
            throw new AwsException("AccessDenied", CloudFormationTemplateEngine.sdkErrorMessage(
                    AccessDeniedMessages.notAuthorized(
                            AwsArnUtils.Arn.global(partition, "iam", consumerAccount, "root").toString(),
                            "sts:AssumeRole", roleArn, null),
                    "AWSSecurityTokenService", 403, "AccessDenied"), 403);
        }
        return AwsArnUtils.parse(roleArn).accountId();
    }

    /**
     * With IAM enforcement on, refuses a read of {@code stackId} that the policies of the role
     * {@link #assume} accepted do not allow, with the AccessDenied DescribeStacks answers its
     * session (measured: "because no identity-based policy allows the cloudformation:DescribeStacks
     * action").
     */
    public void requireDescribeStacks(String roleArn, String stackId, String partition) {
        if (!enforced()) {
            return;
        }
        IamRole role = role(roleArn, partition).orElseThrow();
        CallerContext caller = iamService.resolvePrincipalContext(role.getArn());
        AwsArnUtils.Arn stack = AwsArnUtils.parse(stackId);
        SimulationDecision decision = policyEvaluator.simulatePrincipalPolicy(caller, DESCRIBE_STACKS, stackId,
                Map.of("aws:RequestedRegion", List.of(stack.region())));
        if (decision == SimulationDecision.ALLOWED) {
            return;
        }
        String session = AwsArnUtils.Arn.global(partition, "sts", stack.accountId(),
                "assumed-role/" + role.getRoleName() + "/" + SESSION_NAME).toString();
        throw new AwsException("AccessDenied", CloudFormationTemplateEngine.sdkErrorMessage(
                AccessDeniedMessages.notAuthorized(session, DESCRIBE_STACKS, stackId,
                        PolicyType.IDENTITY_BASED_POLICY.refusal(decision, DESCRIBE_STACKS, null)),
                "AmazonCloudFormation", 403, "AccessDenied"), 403);
    }

    /** Capture only an authenticated caller; legacy and account-only scopes have no authority. */
    CloudFormationExecutionScope.Identity captureCaller() {
        if (requestContext == null) {
            return null;
        }
        try {
            String key = requestContext.getAccessKeyId();
            if (!requestContext.isSignatureVerifiedFor(key)) {
                return null;
            }
            Optional<IamService.CallerArns> caller = iamService.resolveCallerArns(key);
            CallerContext policies = iamService.resolveCallerContext(key);
            if (caller.isEmpty() || policies == null
                    || !AwsArnUtils.parse(caller.get().callerArn()).accountId()
                            .equals(requestContext.getAccountId())) {
                return null;
            }
            String account = AwsArnUtils.parse(caller.get().callerArn()).accountId();
            policies = policies.withScpLevels(iamService.effectiveScpLevels(account));
            return new CloudFormationExecutionScope.Identity(caller.get(), policies);
        } catch (ContextNotActiveException ignored) {
            // Pollers and internal operations outside an HTTP request have no caller authority.
            return null;
        }
    }

    private boolean enforced() {
        return config.services().iam().enforcementEnabled();
    }

    /** The role {@code roleArn} names, matched as STS {@code AssumeRole} matches a role ARN. */
    private Optional<IamRole> role(String roleArn, String partition) {
        if (!AwsArnUtils.isArn(roleArn)) {
            return Optional.empty();
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
        if (!"iam".equals(arn.service()) || !partition.equals(arn.partition())
                || !arn.resource().startsWith("role/")) {
            return Optional.empty();
        }
        String roleName = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        return iamService.findRole(arn.accountId(), roleName)
                .filter(role -> IamService.roleArnMatches(roleArn, role));
    }
}
