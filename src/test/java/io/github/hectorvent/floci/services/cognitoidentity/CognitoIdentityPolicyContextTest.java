package io.github.hectorvent.floci.services.cognitoidentity;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.AssumeRolePolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.RequestPrincipal;
import io.github.hectorvent.floci.services.iam.model.CallerContext;
import io.github.hectorvent.floci.services.iam.model.VerifiedIdentityContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CognitoIdentityPolicyContextTest {
    private static final String PREFIX = "cognito-identity.amazonaws.com:";
    private static final String POOL = "us-east-1:11111111-1111-1111-1111-111111111111";
    private static final String ROLE = "arn:aws:iam::000000000000:role/cognito-context";
    private static final String SESSION = "arn:aws:sts::000000000000:assumed-role/cognito-context/session";
    private static final String RESOURCE = "arn:aws:appsync:us-east-1:000000000000:apis/context/types/Query/fields/value";
    private static final String ALLOW = "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"*\",\"Resource\":\"*\"}}";
    private final ObjectMapper mapper = new ObjectMapper();
    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);

    @Test
    void directCallerAndSimulationEntriesKeepVerifiedConditionsAndOverrideSuppliedClaims() throws Exception {
        CallerContext caller = caller(POOL, deny(false));
        Map<String, List<String>> forged = Map.of(PREFIX.toUpperCase(Locale.ROOT) + "AUD", List.of("wrong"));
        assertEquals(IamPolicyEvaluator.Decision.DENY,
                evaluator.evaluate(caller, null, "appsync:GraphQL", RESOURCE, null));
        assertEquals(IamPolicyEvaluator.Decision.DENY, evaluator.evaluateResolvedResourcePolicy(caller,
                IamPolicyEvaluator.ResourcePolicyDecision.NEUTRAL, "appsync:GraphQL", RESOURCE, forged));
        assertEquals(IamPolicyEvaluator.SimulationDecision.EXPLICIT_DENY,
                evaluator.simulatePrincipalPolicy(caller, "rds-db:connect", RESOURCE, forged));
        assertEquals(IamPolicyEvaluator.Decision.ALLOW,
                evaluator.evaluate(caller("wrong", deny(false)), null, "appsync:GraphQL", RESOURCE, null));
        CallerContext ordinary = CallerContext.of(List.of(ALLOW, deny(false)))
                .withVerifiedIdentityContext(new VerifiedIdentityContext(Map.of()));
        assertEquals(IamPolicyEvaluator.Decision.ALLOW, evaluator.evaluate(ordinary, null,
                "appsync:GraphQL", RESOURCE, Map.of(PREFIX + "aud", List.of(POOL),
                        PREFIX + "amr", List.of("unauthenticated"))));
    }

    @Test
    void resourcePolicyPrepassesReceiveFactsBeforeTheirDecision() throws Exception {
        IamService.CallerArns arns = arns(POOL);
        assertEquals(IamPolicyEvaluator.ResourcePolicyDecision.EXPLICIT_DENY,
                evaluator.evaluateResourcePolicyFor(List.of(deny(true)), RequestPrincipal.caller(arns),
                        "appsync:GraphQL", RESOURCE, Map.of(PREFIX + "aud", List.of("wrong"))));
        assertEquals(IamPolicyEvaluator.ResourcePolicyDecision.NEUTRAL,
                evaluator.evaluateResourcePolicyFor(List.of(deny(true)), RequestPrincipal.caller(arns("wrong")),
                        "appsync:GraphQL", RESOURCE, null));
        assertEquals(IamPolicyEvaluator.Decision.DENY,
                evaluator.evaluate(caller(POOL, ALLOW), List.of(deny(true)), "appsync:GraphQL", RESOURCE, null));
    }

    @Test
    void trustPrepassesSeeVerifiedFactsAndSyntheticServicesDoNotInheritThem() throws Exception {
        AssumeRolePolicyEvaluator trust = new AssumeRolePolicyEvaluator(mapper, evaluator);
        String policy = mapper.writeValueAsString(Map.of("Statement", List.of(
                Map.of("Effect", "Allow", "Action", "sts:AssumeRole", "Principal", Map.of("AWS", ROLE)),
                Map.of("Effect", "Deny", "Action", "sts:AssumeRole", "Principal", Map.of("AWS", ROLE),
                        "Condition", condition()))));
        assertEquals(AssumeRolePolicyEvaluator.TrustDecision.EXPLICIT_DENY,
                trust.decide(policy, arns(POOL), "000000000000", Map.of(PREFIX + "aud", List.of("wrong"))));
        assertEquals(AssumeRolePolicyEvaluator.TrustDecision.ALLOW,
                trust.decide(policy, arns("wrong"), "000000000000", Map.of()));
        assertEquals(IamPolicyEvaluator.ResourcePolicyDecision.NEUTRAL,
                evaluator.evaluateResourcePolicyFor(List.of(deny(true)), RequestPrincipal.service("lambda.amazonaws.com"),
                        "appsync:GraphQL", RESOURCE, Map.of()));
    }

    @Test
    void verifiedValuesAreImmutableAndSurvivePolicyAndPrincipalCopies() {
        List<String> values = new ArrayList<>(List.of(POOL));
        Map<String, List<String>> input = new HashMap<>(Map.of("aud", values));
        VerifiedIdentityContext facts = new VerifiedIdentityContext(input);
        values.set(0, "changed");
        input.clear();
        assertEquals(List.of(POOL), facts.cognitoClaims().get("aud"));
        assertThrows(UnsupportedOperationException.class, () -> facts.cognitoClaims().put("sub", List.of("changed")));
        assertThrows(UnsupportedOperationException.class, () -> facts.cognitoClaims().get("aud").add("changed"));
        CallerContext copied = CallerContext.of(List.of(ALLOW)).withVerifiedIdentityContext(facts)
                .withScpLevels(List.of(List.of(ALLOW))).withIdentityPolicy(ALLOW).withPrincipalArn(SESSION);
        assertSame(facts, copied.verifiedIdentityContext());
        IamService.CallerArns arns = new IamService.CallerArns(SESSION, ROLE).withVerifiedIdentityContext(facts);
        assertSame(facts, RequestPrincipal.caller(arns).verifiedIdentityContext());
        Map<String, List<String>> context = new HashMap<>(Map.of(PREFIX.toUpperCase(Locale.ROOT) + "AUD", List.of("changed")));
        arns.putPrincipalKeys(context);
        assertEquals(List.of(POOL), context.get(PREFIX + "aud"));
        assertFalse(context.containsKey(PREFIX.toUpperCase(Locale.ROOT) + "AUD"));
        assertSame(facts, copied.withManagedSessionPolicies(List.of(ALLOW)).verifiedIdentityContext());
        assertSame(facts, arns.withPrincipalTags(Map.of("key", "value")).withSourceIdentity("source")
                .withTokenIssueTime(Instant.EPOCH).withMultiFactorAuthPresent(true)
                .withMultiFactorAuthAge(2L).verifiedIdentityContext());
    }

    @Test
    void verifiedAndOrdinaryCallersPreserveNullNormalizationAndFirstCaseInsensitiveCondition() throws Exception {
        Map<String, List<String>> supplied = new LinkedHashMap<>();
        supplied.put(null, List.of("ignored"));
        supplied.put("null-list", null);
        supplied.put("AWS:RequestedRegion", Arrays.asList(null, "us-east-1"));
        supplied.put("aws:requestedregion", List.of("wrong-later-value"));
        supplied.put(PREFIX + "aud", Arrays.asList(null, "forged"));
        String allowRegion = mapper.writeValueAsString(Map.of("Statement", Map.of(
                "Effect", "Allow", "Action", "*", "Resource", "*", "Condition",
                Map.of("StringEquals", Map.of("aws:RequestedRegion", "us-east-1")))));
        CallerContext policies = CallerContext.of(List.of(allowRegion, deny(false)));
        CallerContext ordinary = policies.withVerifiedIdentityContext(new VerifiedIdentityContext(Map.of()));
        assertEquals(IamPolicyEvaluator.Decision.ALLOW,
                evaluator.evaluate(ordinary, null, "appsync:GraphQL", RESOURCE, supplied));
        CallerContext guest = policies.withVerifiedIdentityContext(facts(POOL));
        assertEquals(IamPolicyEvaluator.Decision.DENY,
                evaluator.evaluate(guest, null, "appsync:GraphQL", RESOURCE, supplied));
        assertEquals(IamPolicyEvaluator.SimulationDecision.EXPLICIT_DENY,
                evaluator.simulatePrincipalPolicy(guest, "rds-db:connect", RESOURCE, supplied));
        Map<String, List<String>> merged = facts(POOL).mergeInto(supplied);
        assertEquals(List.of("us-east-1"), merged.get("AWS:RequestedRegion"));
        assertEquals(List.of(POOL), merged.get(PREFIX + "aud"));
        assertFalse(merged.containsKey(null));
        assertFalse(merged.containsKey("null-list"));
        assertThrows(UnsupportedOperationException.class, () -> merged.put("new", List.of("value")));
        assertThrows(UnsupportedOperationException.class, () -> merged.get("AWS:RequestedRegion").add("new"));
        assertEquals(Arrays.asList(null, "us-east-1"), supplied.get("AWS:RequestedRegion"));
    }

    @Test
    void syntheticPolicySimulationKeepsExplicitCallerSuppliedContext() throws Exception {
        assertEquals(IamPolicyEvaluator.SimulationDecision.EXPLICIT_DENY,
                evaluator.simulatePrincipalPolicy(CallerContext.of(List.of(ALLOW, deny(false))), "appsync:GraphQL",
                        RESOURCE, Map.of(PREFIX + "aud", List.of(POOL), PREFIX + "amr", List.of("unauthenticated"))));
    }

    private CallerContext caller(String audience, String policy) {
        return CallerContext.of(List.of(ALLOW, policy)).withPrincipalArn(SESSION)
                .withVerifiedIdentityContext(facts(audience));
    }

    private IamService.CallerArns arns(String audience) {
        return new IamService.CallerArns(SESSION, ROLE).withVerifiedIdentityContext(facts(audience));
    }

    private VerifiedIdentityContext facts(String audience) {
        return new VerifiedIdentityContext(Map.of("aud", List.of(audience), "amr", List.of("unauthenticated")));
    }

    private Map<String, Object> condition() {
        return Map.of("StringEquals", Map.of(PREFIX + "aud", POOL),
                "ForAnyValue:StringEquals", Map.of(PREFIX + "amr", "unauthenticated"));
    }

    private String deny(boolean resourcePolicy) throws Exception {
        Map<String, Object> statement = new HashMap<>(Map.of("Effect", "Deny", "Action", "*",
                "Resource", "*", "Condition", condition()));
        if (resourcePolicy) {
            statement.put("Principal", "*");
        }
        return mapper.writeValueAsString(Map.of("Statement", statement));
    }
}
