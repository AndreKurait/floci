package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.iam.IamPolicyEvaluator.Outcome;
import io.floci.aws.services.iam.IamPolicyEvaluator.PolicyLayer;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourceAccountRelationship;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import io.floci.aws.services.iam.IamPolicyEvaluator.SimulationDecision;
import io.floci.aws.services.iam.IamService.CallerArns;
import io.floci.aws.services.iam.model.CallerContext;
import io.floci.aws.services.iam.model.PolicySource;
import io.floci.aws.services.iam.model.VerifiedIdentityContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IamComposedIdentityContextTest {
    private static final String ACTION = "s3:GetObject";
    private static final String RESOURCE = "arn:aws:s3:::bucket/key";
    private static final String ALLOW = """
            {"Statement":[{"Effect":"Allow","Action":"s3:GetObject","Resource":"*"}]}
            """;
    private static final String DENY = """
            {"Statement":[{"Effect":"Deny","Action":"s3:GetObject","Resource":"*"}]}
            """;
    private static final String CONDITIONAL_DENY = """
            {"Statement":[{"Effect":"Deny","Action":"s3:GetObject","Resource":"*",
              "Condition":{"StringEquals":{"cognito-identity.amazonaws.com:aud":"verified-pool"}}}]}
            """;
    private static final VerifiedIdentityContext VERIFIED =
            new VerifiedIdentityContext(Map.of("aud", List.of("verified-pool")));
    private static final Map<String, List<String>> SPOOFED =
            Map.of("COGNITO-IDENTITY.AMAZONAWS.COM:AUD", List.of("spoofed-pool"));
    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    @Test
    void managedSessionDenyAppearsInDecisionAndSimulation() {
        CallerContext caller = CallerContext.named(List.of(ALLOW), List.of(PolicySource.input("1")), null)
                .withManagedSessionPolicies(List.of(ALLOW, DENY));
        IamPolicyEvaluator.DecisionRecord record = evaluator.explain(caller, ResourcePolicyDecision.NEUTRAL,
                ResourceAccountRelationship.SAME_ACCOUNT, ACTION, RESOURCE, Map.of());
        assertEquals(Outcome.EXPLICIT_DENY, record.outcome());
        assertEquals(PolicyLayer.SESSION, record.layer());
        assertEquals(Decision.DENY, evaluator.evaluate(caller, null, ACTION, RESOURCE, Map.of()));
        assertEquals(SimulationDecision.EXPLICIT_DENY,
                evaluator.simulate(caller, ACTION, RESOURCE, Map.of()).decision());
        assertTrue(evaluator.deniesExplicitly(caller, ACTION, RESOURCE, Map.of()));
    }

    @Test
    void managedSessionAllowsAreCombinedBeforeIdentityIntersection() {
        CallerContext caller = CallerContext.of(List.of(ALLOW))
                .withManagedSessionPolicies(List.of("{\"Statement\":[]}", ALLOW));
        assertEquals(Decision.ALLOW, evaluator.evaluate(caller, null, ACTION, RESOURCE, Map.of()));
        assertEquals(SimulationDecision.ALLOWED,
                evaluator.simulate(caller, ACTION, RESOURCE, Map.of()).decision());
        assertEquals(Decision.DENY, evaluator.evaluate(caller.withBoundaryPolicy(DENY), null,
                ACTION, RESOURCE, Map.of()));
    }

    @Test
    void verifiedFactsOverrideSpoofedValuesInAllCallerEvaluationPaths() {
        CallerContext caller = CallerContext.of(List.of(ALLOW, CONDITIONAL_DENY))
                .withVerifiedIdentityContext(VERIFIED);
        assertEquals(Decision.DENY, evaluator.evaluate(caller, null, ACTION, RESOURCE, SPOOFED));
        assertEquals(Outcome.EXPLICIT_DENY, evaluator.explain(caller, ResourcePolicyDecision.NEUTRAL,
                ResourceAccountRelationship.SAME_ACCOUNT, ACTION, RESOURCE, SPOOFED).outcome());
        assertEquals(SimulationDecision.EXPLICIT_DENY,
                evaluator.simulatePrincipalPolicy(caller, ACTION, RESOURCE, SPOOFED));
        assertEquals(SimulationDecision.EXPLICIT_DENY,
                evaluator.simulate(caller, ACTION, RESOURCE, SPOOFED).decision());
        assertTrue(evaluator.deniesExplicitly(caller, ACTION, RESOURCE, SPOOFED));
    }

    @Test
    void callerCopiesRetainPolicySourcesSessionCeilingsAndVerifiedFacts() {
        PolicySource source = PolicySource.input("1");
        CallerContext caller = CallerContext.named(List.of(ALLOW), List.of(source), null)
                .withManagedSessionPolicies(List.of(DENY)).withVerifiedIdentityContext(VERIFIED)
                .withScpLevels(List.of(List.of(ALLOW))).withIdentityPolicy(ALLOW)
                .withBoundaryPolicy(ALLOW).withPrincipalArn("arn:aws:iam::123456789012:role/test");
        assertEquals(source, caller.identityPolicySource(0));
        assertEquals(PolicySource.unnamed(), caller.identityPolicySource(1));
        assertEquals(List.of(DENY), caller.sessionPolicyDocuments());
        assertEquals(VERIFIED, caller.verifiedIdentityContext());
        assertEquals(Decision.DENY, evaluator.evaluate(caller, null, ACTION, RESOURCE, Map.of()));
    }

    @Test
    void principalCopiesRetainOrganizationMfaAndVerifiedFacts() {
        CallerArns caller = CallerArns.accountRoot("arn:aws:iam::123456789012:root", "123456789012")
                .withVerifiedIdentityContext(VERIFIED)
                .withOrganization(new PrincipalOrganization.OrganizationContext("o-example", "123456789012", "o-example/r-root/"))
                .withSourceIdentity("source").withTokenIssueTime(Instant.parse("2026-01-01T00:00:00Z"))
                .withSessionMfa(new CallerArns.SessionMfa(true, 42L)).withPrincipalTags(Map.of("team", "blue"));
        Map<String, List<String>> context = new LinkedHashMap<>(SPOOFED);
        caller.putPrincipalKeys(context);
        assertEquals(List.of("verified-pool"), context.get("cognito-identity.amazonaws.com:aud"));
        assertFalse(context.containsKey("COGNITO-IDENTITY.AMAZONAWS.COM:AUD"));
        assertEquals(List.of("o-example"), context.get("aws:PrincipalOrgID"));
        assertEquals(List.of("42"), context.get("aws:MultiFactorAuthAge"));
        assertEquals(List.of("blue"), context.get("aws:PrincipalTag/team"));
        assertEquals(List.of("source"), context.get("aws:SourceIdentity"));
        assertEquals(VERIFIED, RequestPrincipal.caller(caller).verifiedIdentityContext());
    }

    @Test
    void nullableMfaCompatibilityDoesNotInventAnAuthenticationAge() {
        CallerArns caller = CallerArns.accountRoot("arn:aws:iam::123456789012:root", "123456789012")
                .withMultiFactorAuthPresent(true);
        Map<String, List<String>> context = new LinkedHashMap<>();
        caller.putPrincipalKeys(context);
        assertEquals(List.of("true"), context.get("aws:MultiFactorAuthPresent"));
        assertFalse(context.containsKey("aws:MultiFactorAuthAge"));
        context.clear();
        caller.withMultiFactorAuthAge(17L).putPrincipalKeys(context);
        assertEquals(List.of("17"), context.get("aws:MultiFactorAuthAge"));
        context.clear();
        caller.withMultiFactorAuthPresent(null).putPrincipalKeys(context);
        assertFalse(context.containsKey("aws:MultiFactorAuthPresent"));
        assertFalse(context.containsKey("aws:MultiFactorAuthAge"));
    }
}
