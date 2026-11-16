package io.floci.aws.services.kms;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.IamPolicyEvaluator;
import io.floci.aws.services.iam.IamPolicyEvaluator.Decision;
import io.floci.aws.services.iam.model.CallerContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KmsGrantConditionsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final IamPolicyEvaluator evaluator = new IamPolicyEvaluator(mapper);

    @Test
    void operationsAreBoundedAndCannotBeOmitted() {
        assertEquals(Decision.ALLOW, decision(Map.of(), Map.of(), List.of("Decrypt")));
        assertEquals(Decision.DENY, decision(Map.of(), Map.of(), List.of("Encrypt")));
        assertEquals(Decision.DENY, decision(Map.of(), Map.of(), List.of("Decrypt", "Encrypt")));
        assertEquals(Decision.DENY, decision(Map.of(), Map.of(), List.of()));
    }

    @Test
    void subsetMayStrengthenButCannotDropOrChangePairs() {
        Map<String, Object> parent = Map.of("EncryptionContextSubset", Map.of("Department", "IT"));
        assertEquals(Decision.ALLOW, decision(parent, parent, List.of("Decrypt")));
        assertEquals(Decision.ALLOW, decision(parent, Map.of("EncryptionContextEquals",
                Map.of("Department", "IT", "Purpose", "Test")), List.of("Decrypt")));
        assertEquals(Decision.ALLOW, decision(parent, Map.of("EncryptionContextSubset",
                Map.of("Department", "IT", "Purpose", "Test")), List.of("Decrypt")));
        assertEquals(Decision.DENY, decision(parent, Map.of(), List.of("Decrypt")));
        assertEquals(Decision.DENY, decision(parent, Map.of("EncryptionContextSubset",
                Map.of("department", "IT")), List.of("Decrypt")));
        assertEquals(Decision.DENY, decision(parent, Map.of("EncryptionContextEquals",
                Map.of("Department", "it")), List.of("Decrypt")));
    }

    @Test
    void equalityCannotBecomeSubsetOrGainPairs() {
        Map<String, Object> parent = Map.of("EncryptionContextEquals", Map.of("Department", "IT"));
        assertEquals(Decision.ALLOW, decision(parent, parent, List.of("Decrypt")));
        assertEquals(Decision.DENY, decision(parent, Map.of("EncryptionContextSubset",
                Map.of("Department", "IT")), List.of("Decrypt")));
        assertEquals(Decision.DENY, decision(parent, Map.of("EncryptionContextEquals",
                Map.of("Department", "IT", "Purpose", "Test")), List.of("Decrypt")));
        assertEquals(Decision.DENY, decision(parent, Map.of(), List.of("Decrypt")));
    }

    @Test
    void emptyEqualityStillRequiresEmptyEquality() {
        Map<String, Object> parent = Map.of("EncryptionContextEquals", Map.of());
        assertEquals(Decision.ALLOW, decision(parent, parent, List.of("Decrypt")));
        assertEquals(Decision.DENY, decision(parent, Map.of(), List.of("Decrypt")));
        assertEquals(Decision.DENY, decision(parent, Map.of("EncryptionContextSubset", Map.of()), List.of("Decrypt")));
        assertEquals(Decision.DENY, decision(parent, Map.of("EncryptionContextEquals", Map.of("x", "y")), List.of("Decrypt")));
    }

    @Test
    void constraintVariablesAndSpecialKeysRemainLiteral() {
        Map<String, Object> parent = Map.of("EncryptionContextEquals",
                Map.of("Department:*é", "${aws:username}"));
        assertEquals(Decision.ALLOW, decision(parent, parent, List.of("Decrypt")));
        assertEquals(Decision.DENY, decision(parent, Map.of("EncryptionContextEquals",
                Map.of("Department:*é", "Alice")), List.of("Decrypt")));
    }

    @Test
    void sourceConstraintMustBeRetainedAndMatchInvocation() {
        String source = "arn:aws:lambda:us-east-1:123456789012:function:caller";
        Map<String, Object> parent = Map.of("SourceArn", source);
        Map<String, List<String>> context = KmsGrantConditions.requestContext(mapper.valueToTree(
                Map.of("Operations", List.of("Decrypt"), "Constraints", parent)));
        assertEquals(Decision.DENY, evaluate(parent, context));
        context.put("aws:SourceArn", List.of(source));
        assertEquals(Decision.ALLOW, evaluate(parent, context));
        Map<String, List<String>> missingChild = KmsGrantConditions.requestContext(mapper.valueToTree(
                Map.of("Operations", List.of("Decrypt"), "Constraints", Map.of())));
        missingChild.put("aws:SourceArn", List.of(source));
        assertEquals(Decision.DENY, evaluate(parent, missingChild));
        context.put("aws:SourceArn", List.of(source + "-other"));
        assertEquals(Decision.DENY, evaluate(parent, context));
    }

    @Test
    void retirementTargetsMatchOnlyTheirOwnIdOrToken() {
        Map<String, Object> grant = Map.of("GrantId", "grant-one", "GrantToken", "token-one");
        assertEquals(Decision.ALLOW, retirementDecision(grant, Map.of("GrantId", "grant-one")));
        assertEquals(Decision.ALLOW, retirementDecision(grant, Map.of("GrantToken", "token-one")));
        assertEquals(Decision.DENY, retirementDecision(grant, Map.of("GrantId", "grant-two")));
        assertEquals(Decision.DENY, retirementDecision(grant, Map.of("GrantToken", "token-two")));
        assertEquals(Decision.DENY, retirementDecision(grant, Map.of()));
    }

    @Test
    void retirementTokenTakesPrecedenceOverAnOtherwisePermittedId() {
        Map<String, Object> grant = Map.of("GrantId", "grant-one", "GrantToken", "token-one");
        assertEquals(Decision.DENY, retirementDecision(grant,
                Map.of("GrantId", "grant-one", "GrantToken", "token-two")));
        assertEquals(Decision.ALLOW, retirementDecision(grant,
                Map.of("GrantId", "grant-one", "GrantToken", " ")));
    }

    private Decision retirementDecision(Map<String, Object> grant, Map<String, Object> request) {
        String policy = mapper.valueToTree(Map.of("Version", "2008-10-17", "Statement", List.of(Map.of(
                "Effect", "Allow", "Action", "kms:RetireGrant", "Resource", "*",
                "Condition", KmsGrantConditions.retirementConditions(grant))))).toString();
        return evaluator.evaluate(CallerContext.of(List.of(policy)), null, "kms:RetireGrant", "*",
                KmsGrantConditions.retirementContext(mapper.valueToTree(request)));
    }

    private Decision decision(Map<String, Object> parent, Map<String, Object> child, List<String> operations) {
        return evaluate(parent, KmsGrantConditions.requestContext(mapper.valueToTree(Map.of("Operations", operations,
                "Constraints", child))));
    }

    private Decision evaluate(Map<String, Object> parent, Map<String, List<String>> context) {
        Map<String, Object> grant = Map.of("Operations", List.of("CreateGrant", "Decrypt"), "Constraints", parent);
        String policy = mapper.valueToTree(Map.of("Version", "2008-10-17", "Statement", List.of(Map.of(
                "Effect", "Allow", "Action", "kms:CreateGrant", "Resource", "*",
                "Condition", KmsGrantConditions.parentConditions(grant))))).toString();
        return evaluator.evaluate(CallerContext.of(List.of(policy)), null, "kms:CreateGrant", "*",
                context);
    }
}
