package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.RequestPrincipal;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProvider.ResourcePolicy;
import io.github.hectorvent.floci.services.iam.ResourcePolicyProviders;
import io.github.hectorvent.floci.services.iam.ScpProvider;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

/** Evaluates a service's configured execution role, separately from direct service-principal calls. */
@ApplicationScoped
public class ServiceRoleCallAuthorizer {

    private final EmulatorConfig config;
    private final IamService iam;
    private final AssumeRolePolicyEvaluator trust;
    private final IamPolicyEvaluator evaluator;
    private final Instance<ResourcePolicyProvider> providers;
    private final Instance<ScpProvider> scpProvider;

    @Inject
    public ServiceRoleCallAuthorizer(EmulatorConfig config, IamService iam, AssumeRolePolicyEvaluator trust,
                                     IamPolicyEvaluator evaluator, Instance<ResourcePolicyProvider> providers,
                                     Instance<ScpProvider> scpProvider) {
        this.config = config;
        this.iam = iam;
        this.trust = trust;
        this.evaluator = evaluator;
        this.providers = providers;
        this.scpProvider = scpProvider;
    }

    /**
     * Checks service trust and the role session's identity, resource, boundary and SCP policies.
     * The caller supplies the service-specific trust source, not the event payload's account.
     */
    public Decision authorize(String service, String roleArn, String action, String resourceArn,
                              String sourceArn, String sourceAccount) {
        if (!config.services().iam().enforcementEnabled()) {
            return Decision.ALLOW;
        }
        if (!AwsArnUtils.isArnFor(roleArn, "iam") || !AwsArnUtils.isArn(resourceArn)
                || !AwsArnUtils.isArn(sourceArn)) {
            return Decision.DENY;
        }
        AwsArnUtils.Arn roleId = AwsArnUtils.parse(roleArn);
        AwsArnUtils.Arn source = AwsArnUtils.parse(sourceArn);
        if (!roleId.region().isEmpty() || !roleId.resource().startsWith("role/")
                || !roleId.accountId().equals(sourceAccount) || !source.accountId().equals(sourceAccount)
                || !roleId.partition().equals(source.partition())
                || !roleId.partition().equals(AwsArnUtils.parse(resourceArn).partition())) {
            return Decision.DENY;
        }
        String roleName = roleId.resource().substring(roleId.resource().lastIndexOf('/') + 1);
        IamRole role = iam.findRole(roleId.accountId(), roleName)
                .filter(found -> IamService.roleArnMatches(roleArn, found)).orElse(null);
        if (role == null || !trust.allowsService(role.getAssumeRolePolicyDocument(), ServicePrincipals.of(service),
                sourceArn, sourceAccount)) {
            return Decision.DENY;
        }
        AwsArnUtils.Arn resource = AwsArnUtils.parse(resourceArn);
        List<ResourcePolicy> policies = ResourcePolicyProviders.policiesFor(providers, resource.service(), resourceArn);
        String owner = policies.isEmpty() ? resource.accountId() : policies.getFirst().ownerAccountId();
        List<String> documents = policies.stream().map(ResourcePolicy::policyDocument)
                .filter(document -> document != null && !document.isBlank()).toList();
        CallerContext caller = iam.resolvePrincipalContext(role.getArn());
        List<List<String>> scps = scpProvider.isResolvable()
                ? scpProvider.get().effectiveScpLevels(roleId.accountId()) : null;
        Map<String, List<String>> context = IamConditionContextResolver.withGlobalContext(
                Map.of("aws:PrincipalArn", List.of(role.getArn()),
                        "aws:PrincipalType", List.of(IamService.CallerArns.TYPE_ASSUMED_ROLE)),
                resourceArn, resource.region(), roleId.accountId(), owner);
        String sessionArn = AwsArnUtils.Arn.global(roleId.partition(), "sts", roleId.accountId(),
                "assumed-role/" + roleName).toString();
        ResourcePolicyDecision grant = evaluator.evaluateResourcePolicyFor(documents.isEmpty() ? null : documents,
                new RequestPrincipal(RequestPrincipal.Type.IAM, sessionArn, role.getArn(), null,
                        role.getPermissionsBoundaryArn() != null), action, resourceArn, context);
        ResourceAccountRelationship relationship = roleId.accountId().equals(owner)
                ? ResourceAccountRelationship.SAME_ACCOUNT : ResourceAccountRelationship.CROSS_ACCOUNT;
        return evaluator.evaluateResolvedResourcePolicy(caller.withScpLevels(scps), grant, relationship,
                action, resourceArn, context);
    }
}
