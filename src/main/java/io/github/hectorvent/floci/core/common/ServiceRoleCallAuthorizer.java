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

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Evaluates a service's configured execution role, separately from direct service-principal calls. */
@ApplicationScoped
public class ServiceRoleCallAuthorizer {

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final EmulatorConfig config;
    private final IamService iam;
    private final AssumeRolePolicyEvaluator trust;
    private final IamPolicyEvaluator evaluator;
    private final Instance<ResourcePolicyProvider> providers;
    private final Instance<ScpProvider> scpProvider;

    public ServiceRoleCallAuthorizer(EmulatorConfig config, IamService iam, AssumeRolePolicyEvaluator trust,
                                     IamPolicyEvaluator evaluator, Instance<ResourcePolicyProvider> providers,
                                     Instance<ScpProvider> scpProvider) {
        this(config, iam, trust, evaluator, providers, scpProvider, Clock.systemUTC());
    }

    @Inject
    public ServiceRoleCallAuthorizer(EmulatorConfig config, IamService iam, AssumeRolePolicyEvaluator trust,
                                     IamPolicyEvaluator evaluator, Instance<ResourcePolicyProvider> providers,
                                     Instance<ScpProvider> scpProvider, Clock clock) {
        this.clock = clock;
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
        if (!AwsArnUtils.isArn(sourceArn)) {
            return Decision.DENY;
        }
        AwsArnUtils.Arn source = AwsArnUtils.parse(sourceArn);
        if (!source.accountId().equals(sourceAccount)) {
            return Decision.DENY;
        }
        return authorizeRole(service, roleArn, action, resourceArn, sourceAccount,
                source.partition(), sourceArn, sourceAccount);
    }

    /**
     * Evaluates service trust without asserting service-specific source condition keys.
     * The owning account still constrains which execution role the service may use.
     */
    public Decision authorizeWithoutSourceContext(String service, String roleArn, String action,
                                                  String resourceArn, String ownerAccount, String ownerPartition) {
        if (!config.services().iam().enforcementEnabled()) {
            return Decision.ALLOW;
        }
        if (!AwsArnUtils.isArn(resourceArn)
                || !AwsArnUtils.parse(resourceArn).partition().equals(ownerPartition)) {
            return Decision.DENY;
        }
        return authorizeRole(service, roleArn, action, resourceArn, ownerAccount,
                ownerPartition, null, null);
    }

    private Decision authorizeRole(String service, String roleArn, String action, String resourceArn,
                                   String ownerAccount, String partition, String sourceArn, String sourceAccount) {
        if (!AwsArnUtils.isArnFor(roleArn, "iam") || !AwsArnUtils.isArn(resourceArn)) {
            return Decision.DENY;
        }
        if (!AwsArnUtils.parse(resourceArn).partition().equals(partition)) {
            return Decision.DENY;
        }
        AwsArnUtils.Arn roleId = AwsArnUtils.parse(roleArn);
        if (!roleId.region().isEmpty() || !roleId.resource().startsWith("role/")
                || !roleId.accountId().equals(ownerAccount) || !roleId.partition().equals(partition)
                || !roleId.partition().equals(AwsArnUtils.parse(resourceArn).partition())) {
            return Decision.DENY;
        }
        String roleName = roleId.resource().substring(roleId.resource().lastIndexOf('/') + 1);
        IamRole role = iam.findRole(roleId.accountId(), roleName)
                .filter(found -> IamService.roleArnMatches(roleArn, found)).orElse(null);
        Instant now = clock.instant();
        if (role == null || !trust.allowsService(role.getAssumeRolePolicyDocument(), ServicePrincipals.of(service),
                sourceArn, sourceAccount, now)) {
            return Decision.DENY;
        }
        String accessKeyId = "ASIA" + randomHex();
        String sessionName = "floci-service-" + randomHex();
        String assumedRoleId = role.getRoleId() == null ? null : role.getRoleId() + ":" + sessionName;
        try (RoleSession session = new RoleSession(iam, roleId.accountId(), accessKeyId)) {
            iam.registerSessionForAccount(roleId.accountId(), accessKeyId, randomBase64(30), randomBase64(150),
                    role.getArn(), now.plusSeconds(900), null, sessionName, assumedRoleId);
            IamService.CallerArns principal = iam.resolveCallerArns(accessKeyId).orElse(null);
            CallerContext caller = iam.resolveCallerContext(accessKeyId, now);
            if (principal == null || caller == null || !role.getArn().equals(principal.principalArn())) {
                return Decision.DENY;
            }
            return evaluateSession(action, resourceArn, role, principal, caller, now);
        }
    }

    private record RoleSession(IamService iam, String accountId, String accessKeyId) implements AutoCloseable {
        @Override
        public void close() {
            iam.unregisterSession(accountId, accessKeyId);
        }
    }

    private Decision evaluateSession(String action, String resourceArn, IamRole role,
                                     IamService.CallerArns principal, CallerContext caller, Instant now) {
        AwsArnUtils.Arn roleId = AwsArnUtils.parse(role.getArn());
        AwsArnUtils.Arn resource = AwsArnUtils.parse(resourceArn);
        List<ResourcePolicy> policies = ResourcePolicyProviders.policiesFor(providers, resource.service(), resourceArn);
        String owner = policies.isEmpty() ? resource.accountId() : policies.getFirst().ownerAccountId();
        List<String> documents = policies.stream().map(ResourcePolicy::policyDocument)
                .filter(document -> document != null && !document.isBlank()).toList();
        List<List<String>> scps = scpProvider.isResolvable()
                ? scpProvider.get().effectiveScpLevels(roleId.accountId()) : null;
        Map<String, List<String>> principalContext = new HashMap<>(
                Map.of("aws:CurrentTime", List.of(now.toString()),
                        "aws:EpochTime", List.of(Long.toString(now.getEpochSecond()))));
        principal.putPrincipalKeys(principalContext);
        Map.copyOf(role.getTags()).forEach((key, value) ->
                principalContext.put("aws:PrincipalTag/" + key, List.of(value)));
        Map<String, List<String>> context = IamConditionContextResolver.withGlobalContext(
                principalContext, resourceArn, resource.region(), roleId.accountId(), owner);
        ResourcePolicyDecision grant = evaluator.evaluateResourcePolicyFor(documents.isEmpty() ? null : documents,
                RequestPrincipal.caller(principal), action, resourceArn, context);
        ResourceAccountRelationship relationship = roleId.accountId().equals(owner)
                ? ResourceAccountRelationship.SAME_ACCOUNT : ResourceAccountRelationship.CROSS_ACCOUNT;
        return evaluator.evaluateResolvedResourcePolicy(caller.withScpLevels(scps), grant, relationship,
                action, resourceArn, context);
    }

    private String randomHex() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private String randomBase64(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
