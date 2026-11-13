package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.services.iam.AccessDeniedMessages;
import io.github.hectorvent.floci.services.iam.AccessDeniedMessages.PolicyType;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator.TrustDecision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.SimulationDecision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.IamService.CallerArns;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.ServiceRoleAuthorizer;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether CloudFormation may read another stack's outputs through the role a
 * {@code Fn::GetStackOutput} names. On AWS CloudFormation assumes the role as the stack's
 * execution role, or without one as the caller of the stack operation. The selected identity is
 * captured before asynchronous execution; the output reader's trust, identity policies,
 * permissions boundary, session policy and SCPs are evaluated using the shared IAM evaluators.
 */
@ApplicationScoped
public class GetStackOutputRoleAuthorizer {

    private static final String DESCRIBE_STACKS = "cloudformation:DescribeStacks";
    private static final String SESSION_NAME = "GetStackOutput";
    private static final String ROOT_ALLOW =
            "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}";

    /** An operation carries its principal and permission ceilings, never the request's credentials. */
    public record ExecutionIdentity(CallerArns caller, CallerContext policies) {}

    private final IamService iamService;
    private final AssumeRolePolicyEvaluator trustEvaluator;
    private final IamPolicyEvaluator policyEvaluator;
    private final EmulatorConfig config;
    private final ServiceRoleAuthorizer serviceRoles;
    private final RequestContext requestContext;
    private final Instance<ScpProvider> scpProvider;

    @Inject
    public GetStackOutputRoleAuthorizer(IamService iamService, AssumeRolePolicyEvaluator trustEvaluator,
                                        IamPolicyEvaluator policyEvaluator, EmulatorConfig config,
                                        ServiceRoleAuthorizer serviceRoles, RequestContext requestContext,
                                        Instance<ScpProvider> scpProvider) {
        this.iamService = iamService;
        this.trustEvaluator = trustEvaluator;
        this.policyEvaluator = policyEvaluator;
        this.config = config;
        this.serviceRoles = serviceRoles;
        this.requestContext = requestContext;
        this.scpProvider = scpProvider;
    }

    /** Capture the API caller while its request scope and temporary-session restrictions are available. */
    public ExecutionIdentity captureCaller(String accountId, String partition) {
        String accessKey = null;
        try {
            accessKey = requestContext.getAccessKeyId();
        } catch (ContextNotActiveException expected) {
            // Internal stack operations can run without an HTTP request.
        }
        CallerArns caller = accessKey == null ? null : iamService.resolveCallerArns(accessKey).orElse(null);
        CallerContext policies = accessKey == null ? null : iamService.resolveCallerContext(accessKey);
        if (caller == null) {
            caller = CallerArns.accountRoot(
                    AwsArnUtils.Arn.global(partition, "iam", accountId, "root").toString(), accountId);
        }
        if (policies == null) {
            policies = CallerContext.of(accessKey == null || accountId.equals(accessKey)
                    ? List.of(ROOT_ALLOW) : List.of());
        }
        return new ExecutionIdentity(caller, policies);
    }

    /** RoleARN belongs to this stack's account and must be assumable by CloudFormation. */
    public void validateExecutionRole(String roleArn, String accountId, String stackId, String partition) {
        if (roleArn == null) {
            return;
        }
        if (!AwsArnUtils.isArn(roleArn)) {
            throw new AwsException("ValidationError", "Role " + roleArn + " is invalid or cannot be assumed.", 400);
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(roleArn);
        if (!"iam".equals(parsed.service()) || !parsed.resource().startsWith("role/")
                || !accountId.equals(parsed.accountId()) || !partition.equals(parsed.partition())) {
            throw new AwsException("ValidationError", "Role " + roleArn + " is invalid or cannot be assumed.", 400);
        }
        serviceRoles.refusal(roleArn, accountId, ServicePrincipals.of("cloudformation"), stackId, List.of())
                .ifPresent(reason -> {
                    throw new AwsException("ValidationError", "Role " + roleArn
                            + " is invalid or cannot be assumed. " + reason, 400);
                });
    }

    public ExecutionIdentity executionIdentity(String roleArn, String accountId, String stackId,
                                                String partition, ExecutionIdentity caller) {
        if (roleArn == null) {
            return caller;
        }
        validateExecutionRole(roleArn, accountId, stackId, partition);
        return roleSession(roleArn, "AWSCloudFormation", partition);
    }

    /** Returns the reader's account after evaluating the actual executing principal's AssumeRole. */
    public String assume(String roleArn, ExecutionIdentity identity, String region, String partition) {
        Optional<IamRole> role = role(roleArn, partition);
        if (role.isEmpty() || (enforced() && !mayAssume(identity, role.get(), roleArn, region))) {
            throw new AwsException("AccessDenied", CloudFormationTemplateEngine.sdkErrorMessage(
                    AccessDeniedMessages.notAuthorized(identity.caller().callerArn(),
                            "sts:AssumeRole", roleArn, null),
                    "AWSSecurityTokenService", 403, "AccessDenied"), 403);
        }
        return AwsArnUtils.parse(roleArn).accountId();
    }

    private boolean mayAssume(ExecutionIdentity identity, IamRole role, String roleArn, String region) {
        String callerAccount = AwsArnUtils.parse(identity.caller().principalArn()).accountId();
        String roleAccount = AwsArnUtils.parse(roleArn).accountId();
        Map<String, List<String>> context = IamConditionContextResolver.withGlobalContext(
                Map.of("sts:RoleSessionName", List.of(SESSION_NAME)), roleArn, region, callerAccount, roleAccount);
        identity.caller().putPrincipalKeys(context);
        TrustDecision trust = trustEvaluator.decide(
                role.getAssumeRolePolicyDocument(), identity.caller(), callerAccount, context);
        if (trust != TrustDecision.ALLOW && trust != TrustDecision.ALLOW_ACCOUNT) {
            return false;
        }
        ResourcePolicyDecision grant = trust == TrustDecision.ALLOW
                ? ResourcePolicyDecision.ALLOW : ResourcePolicyDecision.ALLOW_ACCOUNT;
        ResourceAccountRelationship relationship = callerAccount.equals(roleAccount)
                ? ResourceAccountRelationship.SAME_ACCOUNT : ResourceAccountRelationship.CROSS_ACCOUNT;
        return policyEvaluator.evaluateResolvedResourcePolicy(policies(identity), grant, relationship,
                "sts:AssumeRole", roleArn, context) == Decision.ALLOW;
    }

    /** An explicit output-reader role uses the GetStackOutput session, after AssumeRole succeeded. */
    public void requireDescribeStacks(String roleArn, String stackId, String partition) {
        requireDescribeStacks(roleSession(roleArn, SESSION_NAME, partition), stackId);
    }

    /** Without a reader RoleArn, DescribeStacks uses the operation's execution role or caller. */
    public void requireDescribeStacks(ExecutionIdentity identity, String stackId) {
        if (!enforced()) {
            return;
        }
        AwsArnUtils.Arn stack = AwsArnUtils.parse(stackId);
        String callerAccount = AwsArnUtils.parse(identity.caller().principalArn()).accountId();
        Map<String, List<String>> context = IamConditionContextResolver.withGlobalContext(
                Map.of(), stackId, stack.region(), callerAccount, stack.accountId());
        identity.caller().putPrincipalKeys(context);
        SimulationDecision decision = policyEvaluator.simulatePrincipalPolicy(
                policies(identity), DESCRIBE_STACKS, stackId, context);
        if (decision == SimulationDecision.ALLOWED) {
            return;
        }
        throw new AwsException("AccessDenied", CloudFormationTemplateEngine.sdkErrorMessage(
                AccessDeniedMessages.notAuthorized(identity.caller().callerArn(), DESCRIBE_STACKS, stackId,
                        PolicyType.IDENTITY_BASED_POLICY.refusal(decision, DESCRIBE_STACKS, null)),
                "AmazonCloudFormation", 403, "AccessDenied"), 403);
    }

    private ExecutionIdentity roleSession(String roleArn, String sessionName, String partition) {
        AwsArnUtils.Arn arn = AwsArnUtils.parse(roleArn);
        String roleName = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        String session = AwsArnUtils.Arn.global(partition, "sts", arn.accountId(),
                "assumed-role/" + roleName + "/" + sessionName).toString();
        IamRole role = role(roleArn, partition).orElse(null);
        CallerContext policies = role == null ? CallerContext.of(List.of())
                : iamService.resolvePrincipalContext(roleArn);
        CallerArns principal = new CallerArns(session, roleArn, CallerArns.TYPE_ASSUMED_ROLE,
                role == null ? null : role.getRoleId() + ":" + sessionName, null,
                policies.boundaryPolicyDocument() != null, null, role == null ? Map.of() : role.getTags());
        return new ExecutionIdentity(principal, policies);
    }

    private CallerContext policies(ExecutionIdentity identity) {
        String account = AwsArnUtils.parse(identity.caller().principalArn()).accountId();
        List<List<String>> scps = scpProvider.isResolvable()
                ? scpProvider.get().effectiveScpLevels(account) : null;
        return identity.policies().withPrincipalArn(identity.caller().callerArn()).withScpLevels(scps);
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
