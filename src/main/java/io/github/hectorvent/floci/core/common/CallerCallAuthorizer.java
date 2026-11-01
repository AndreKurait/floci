package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Evaluates a downstream call using an already authenticated caller's credentials. */
@ApplicationScoped
public class CallerCallAuthorizer {

    private static final String ROOT_ALLOW_ALL =
            "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}";

    private final EmulatorConfig config;
    private final IamService iam;
    private final IamPolicyEvaluator evaluator;
    private final Instance<ResourcePolicyProvider> providers;
    private final Instance<ScpProvider> scpProvider;

    @Inject
    public CallerCallAuthorizer(EmulatorConfig config, IamService iam, IamPolicyEvaluator evaluator,
                                Instance<ResourcePolicyProvider> providers, Instance<ScpProvider> scpProvider) {
        this.config = config;
        this.iam = iam;
        this.evaluator = evaluator;
        this.providers = providers;
        this.scpProvider = scpProvider;
    }

    /** Whether endpoints require authenticated callers for opt-in IAM policy evaluation. */
    public boolean enforcementEnabled() {
        return config.services().iam().enforcementEnabled();
    }

    /**
     * The entry point must verify the signature before supplying this identity. Re-resolving it
     * here preserves current policy, session and boundary state without trusting request headers.
     * The verified local test credential represents account root; its implicit identity grant
     * still goes through resource policy and SCP evaluation.
     */
    public Decision authorize(String accessKey, String account, String callerArn, String action,
                              String resourceArn, String partition) {
        return authorize(accessKey, account, callerArn, action, resourceArn, partition, Map.of());
    }

    /** Evaluates an inbound request using transport facts supplied by its endpoint. */
    public Decision authorizeRequest(String accessKey, String account, String callerArn, String action,
                                     String resourceArn, String partition, String sourceIp, boolean secureTransport) {
        Map<String, List<String>> context = new HashMap<>();
        if (sourceIp != null && !sourceIp.isBlank()) {
            context.put("aws:SourceIp", List.of(sourceIp));
        }
        context.put("aws:SecureTransport", List.of(Boolean.toString(secureTransport)));
        return authorize(accessKey, account, callerArn, action, resourceArn, partition, context);
    }

    private Decision authorize(String accessKey, String account, String callerArn, String action,
                               String resourceArn, String partition, Map<String, List<String>> requestContext) {
        if (!enforcementEnabled()) {
            return Decision.ALLOW;
        }
        if (accessKey == null || account == null || !AwsArnUtils.isArn(callerArn)
                || !AwsArnUtils.isArn(resourceArn)) {
            return Decision.DENY;
        }
        AwsArnUtils.Arn principal = AwsArnUtils.parse(callerArn);
        AwsArnUtils.Arn resource = AwsArnUtils.parse(resourceArn);
        boolean localRoot = "test".equals(accessKey) && "iam".equals(principal.service())
                && principal.region().isEmpty() && "root".equals(principal.resource());
        if (!principal.partition().equals(partition) || !resource.partition().equals(partition)
                || !principal.accountId().equals(account)
                || (!localRoot && !iam.resolveAccountId(accessKey).filter(account::equals).isPresent())) {
            return Decision.DENY;
        }
        return RequestScopes.callAs(account, () -> authorizeCaller(accessKey, callerArn, action, resourceArn, resource, localRoot, requestContext));
    }

    private Decision authorizeCaller(String accessKey, String callerArn, String action, String resourceArn,
                                     AwsArnUtils.Arn resource, boolean localRoot, Map<String, List<String>> requestContext) {
        Instant now = Instant.now();
        if (iam.isInactiveAccessKey(accessKey) || iam.isExpiredSession(accessKey, now)) {
            return Decision.DENY;
        }
        CallerContext caller = localRoot ? CallerContext.of(List.of(ROOT_ALLOW_ALL))
                : iam.resolveCallerContext(accessKey, now);
        IamService.CallerArns arns = localRoot
                ? IamService.CallerArns.accountRoot(callerArn, AwsArnUtils.parse(callerArn).accountId())
                : iam.resolveCallerArns(accessKey).orElse(null);
        if (caller == null || arns == null || !callerArn.equals(arns.callerArn())) {
            return Decision.DENY;
        }
        String account = AwsArnUtils.parse(callerArn).accountId();
        List<ResourcePolicy> policies = ResourcePolicyProviders.policiesFor(providers, resource.service(), resourceArn);
        String owner = policies.isEmpty() ? resource.accountId() : policies.getFirst().ownerAccountId();
        List<String> documents = policies.stream().map(ResourcePolicy::policyDocument)
                .filter(document -> document != null && !document.isBlank()).toList();
        List<List<String>> scps = scpProvider.isResolvable()
                ? scpProvider.get().effectiveScpLevels(account) : null;
        Map<String, List<String>> context = new HashMap<>(requestContext);
        arns.putPrincipalKeys(context);
        context = IamConditionContextResolver.withGlobalContext(context, resourceArn, resource.region(), account, owner);
        ResourcePolicyDecision grant = evaluator.evaluateResourcePolicyFor(documents.isEmpty() ? null : documents,
                RequestPrincipal.caller(arns), action, resourceArn, context);
        ResourceAccountRelationship relationship = account.equals(owner)
                ? ResourceAccountRelationship.SAME_ACCOUNT : ResourceAccountRelationship.CROSS_ACCOUNT;
        return evaluator.evaluateResolvedResourcePolicy(caller.withPrincipalArn(callerArn).withScpLevels(scps),
                grant, relationship, action, resourceArn, context);
    }
}
