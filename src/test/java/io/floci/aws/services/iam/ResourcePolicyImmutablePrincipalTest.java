package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.IamPolicyEvaluator.ResourcePolicyDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ResourcePolicyImmutablePrincipalTest {
    private static final String ROLE = "arn:aws:iam::111111111111:role/team/source";
    private static final String SESSION = "arn:aws:sts::111111111111:assumed-role/source/session";
    private static final String USER = "arn:aws:iam::111111111111:user/team/source";
    private static final String TARGET = "arn:aws:iam::222222222222:role/target";
    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(new ObjectMapper());

    @Test
    void boundRoleMatchesItsIssuedIdentityAndNotASameArnReplacement() {
        RequestPrincipal original = role("AROAORIGINAL:session");
        RequestPrincipal replacement = role("AROAREPLACEMENT:session");

        assertEquals("AROAORIGINAL", original.identityId());
        assertEquals(ROLE, original.roleArn());
        assertEquals(ResourcePolicyDecision.ALLOW, decide("Allow", "Principal", "AROAORIGINAL", original));
        assertEquals(ResourcePolicyDecision.NEUTRAL, decide("Allow", "Principal", "AROAORIGINAL", replacement));
        assertEquals(ResourcePolicyDecision.NEUTRAL, decide("Allow", "Principal", "AROAREPLACEMENT", original));
        assertEquals(ResourcePolicyDecision.ALLOW, decide("Allow", "Principal", "AROAREPLACEMENT", replacement));
    }

    @Test
    void boundUserKeepsDirectUserGrantSemanticsAndRejectsAReplacement() {
        RequestPrincipal original = user("AIDAORIGINAL");

        assertEquals(ResourcePolicyDecision.ALLOW_DIRECT_IAM_USER,
                decide("Allow", "Principal", "AIDAORIGINAL", original));
        assertEquals(ResourcePolicyDecision.NEUTRAL,
                decide("Allow", "Principal", "AIDAORIGINAL", user("AIDAREPLACEMENT")));
    }

    @Test
    void anArnOnlyCallerCannotInventItsIdentityThroughConditionContext() {
        String policy = policy("Allow", "Principal", "AROAORIGINAL");
        Map<String, List<String>> spoofed = Map.of("aws:userid", List.of("AROAORIGINAL:session"));

        assertEquals(ResourcePolicyDecision.NEUTRAL, evaluator.evaluateResourcePolicyFor(List.of(policy),
                RequestPrincipal.roleSession(SESSION, ROLE), "sts:AssumeRole", TARGET, spoofed));
        assertEquals(ResourcePolicyDecision.NEUTRAL, evaluator.evaluateResourcePolicyFor(List.of(policy),
                role("AROAREPLACEMENT:session"), "sts:AssumeRole", TARGET, spoofed));
    }

    @Test
    void immutableIdentityDenyAndNotPrincipalKeepTheirExactScope() {
        RequestPrincipal original = role("AROAORIGINAL:session");
        RequestPrincipal replacement = role("AROAREPLACEMENT:session");

        assertEquals(ResourcePolicyDecision.EXPLICIT_DENY, decide("Deny", "Principal", "AROAORIGINAL", original));
        assertEquals(ResourcePolicyDecision.NEUTRAL, decide("Deny", "Principal", "AROAORIGINAL", replacement));
        assertEquals(ResourcePolicyDecision.EXPLICIT_DENY,
                decide("Deny", "NotPrincipal", "AROAORIGINAL", original));
        String accountAndIdentity = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny",
                "NotPrincipal":{"AWS":["111111111111","AROAORIGINAL"]},
                "Action":"sts:AssumeRole","Resource":"%s"}]}""".formatted(TARGET);
        assertEquals(ResourcePolicyDecision.NEUTRAL, evaluator.evaluateResourcePolicyFor(
                List.of(accountAndIdentity), original, "sts:AssumeRole", TARGET, Map.of()));
        assertEquals(ResourcePolicyDecision.EXPLICIT_DENY, evaluator.evaluateResourcePolicyFor(
                List.of(accountAndIdentity), replacement, "sts:AssumeRole", TARGET, Map.of()));
        RequestPrincipal bounded = RequestPrincipal.caller(new IamService.CallerArns(SESSION, ROLE,
                IamService.CallerArns.TYPE_ASSUMED_ROLE, "AROAORIGINAL:session", null, true));
        assertEquals(ResourcePolicyDecision.EXPLICIT_DENY, evaluator.evaluateResourcePolicyFor(
                List.of(accountAndIdentity), bounded, "sts:AssumeRole", TARGET, Map.of()));
        assertEquals(ResourcePolicyDecision.NEUTRAL, decide("Allow", "Principal", "AROA*", original));
    }

    @ParameterizedTest
    @CsvSource({"AssumedRole,AIDAORIGINAL:session", "AssumedRole,AROAinvalid:session", "User,AROAORIGINAL",
            "User,AIDAORIGINAL:session", "Account,AIDAORIGINAL", "FederatedUser,AROAORIGINAL:session"})
    void identityMustMatchTheVerifiedCallerType(String type, String id) {
        RequestPrincipal principal = RequestPrincipal.caller(new IamService.CallerArns(SESSION, ROLE, type, id, null));

        assertNull(principal.identityId());
        assertEquals(ResourcePolicyDecision.NEUTRAL, decide("Allow", "Principal", "AROAORIGINAL", principal));
        assertEquals(ResourcePolicyDecision.NEUTRAL, decide("Allow", "Principal", "AIDAORIGINAL", principal));
    }

    private RequestPrincipal role(String id) {
        return RequestPrincipal.caller(new IamService.CallerArns(SESSION, ROLE,
                IamService.CallerArns.TYPE_ASSUMED_ROLE, id, null));
    }

    private RequestPrincipal user(String id) {
        return RequestPrincipal.caller(new IamService.CallerArns(USER, USER,
                IamService.CallerArns.TYPE_USER, id, "source"));
    }

    private ResourcePolicyDecision decide(String effect, String element, String id, RequestPrincipal principal) {
        return evaluator.evaluateResourcePolicyFor(List.of(policy(effect, element, id)), principal,
                "sts:AssumeRole", TARGET, Map.of());
    }

    private String policy(String effect, String element, String id) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"%s","%s":{"AWS":"%s"},
                "Action":"sts:AssumeRole","Resource":"%s"}]}""".formatted(effect, element, id, TARGET);
    }
}
