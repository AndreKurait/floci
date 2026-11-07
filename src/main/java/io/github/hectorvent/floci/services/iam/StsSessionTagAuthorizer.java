package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator.TrustDecision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Evaluates supplementary session permissions through the same trust and caller policy pipeline. */
@ApplicationScoped
public class StsSessionTagAuthorizer {
    private static final String ACTION = "sts:TagSession";
    private static final String ROOT_ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}";

    private final IamService iam;
    private final AssumeRolePolicyEvaluator trust;
    private final IamPolicyEvaluator evaluator;
    private final Instance<ScpProvider> scpProvider;

    @Inject
    public StsSessionTagAuthorizer(IamService iam, AssumeRolePolicyEvaluator trust, IamPolicyEvaluator evaluator,
                                   Instance<ScpProvider> scpProvider) {
        this.iam = iam;
        this.trust = trust;
        this.evaluator = evaluator;
        this.scpProvider = scpProvider;
    }

    public boolean allows(String accessKey, IamService.CallerArns principal, String callerAccount,
                           IamRole role, Map<String, List<String>> requestContext) {
        return allowsPermission(accessKey, principal, callerAccount, role, requestContext, ACTION, false);
    }

    public boolean allowsSourceIdentity(String accessKey, IamService.CallerArns principal, String callerAccount,
                                        IamRole role, Map<String, List<String>> requestContext) {
        return allowsPermission(accessKey, principal, callerAccount, role, requestContext,
                "sts:SetSourceIdentity", IamService.CallerArns.TYPE_ASSUMED_ROLE.equals(principal.principalType()));
    }

    private boolean allowsPermission(String accessKey, IamService.CallerArns principal, String callerAccount,
                                     IamRole role, Map<String, List<String>> requestContext, String action,
                                     boolean requireIdentityPermission) {
        Map<String, List<String>> context = new HashMap<>(requestContext);
        principal.putPrincipalKeys(context);
        TrustDecision trustDecision = trust.decide(role.getAssumeRolePolicyDocument(), principal,
                callerAccount, context, action);
        ResourcePolicyDecision grant = switch (trustDecision) {
            case ALLOW -> ResourcePolicyDecision.ALLOW;
            case ALLOW_ACCOUNT -> ResourcePolicyDecision.ALLOW_ACCOUNT;
            case EXPLICIT_DENY -> ResourcePolicyDecision.EXPLICIT_DENY;
            case NEUTRAL -> ResourcePolicyDecision.NEUTRAL;
        };
        if (trustDecision != TrustDecision.ALLOW && trustDecision != TrustDecision.ALLOW_ACCOUNT) {
            return false;
        }
        CallerContext caller = iam.resolveCallerContext(accessKey);
        if (caller == null && IamService.CallerArns.TYPE_ACCOUNT.equals(principal.principalType())
                && (callerAccount.equals(accessKey) || "test".equals(accessKey))) {
            caller = CallerContext.of(List.of(ROOT_ALLOW));
        }
        if (caller == null) {
            return false;
        }
        List<List<String>> scps = scpProvider.isResolvable()
                ? scpProvider.get().effectiveScpLevels(callerAccount) : null;
        String roleAccount = AwsArnUtils.parse(role.getArn()).accountId();
        ResourceAccountRelationship relationship = callerAccount.equals(roleAccount)
                ? ResourceAccountRelationship.SAME_ACCOUNT : ResourceAccountRelationship.CROSS_ACCOUNT;
        CallerContext effective = caller.withPrincipalArn(principal.callerArn()).withScpLevels(scps);
        if (requireIdentityPermission && evaluator.evaluate(effective, null, action, role.getArn(), context)
                != Decision.ALLOW) {
            return false;
        }
        return evaluator.evaluateResolvedResourcePolicy(effective, grant, relationship,
                action, role.getArn(), context) == Decision.ALLOW;
    }
}
