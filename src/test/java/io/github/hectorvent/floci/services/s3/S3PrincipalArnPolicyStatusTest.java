package io.github.hectorvent.floci.services.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision.ALLOW;
import static io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.ResourcePolicyDecision.NEUTRAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3PrincipalArnPolicyStatusTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ARN = "arn:aws:iam::111122223333:role/publisher";
    private static final String OTHER_ARN = "arn:aws:iam::111122223333:role/unlisted";
    private static final String OBJECT_ARN = "arn:aws:s3:::bootstrap/object";

    @ParameterizedTest
    @ValueSource(strings = {"StringEquals", "ArnEquals",
            "ForAnyValue:StringEquals", "ForAnyValue:ArnEquals"})
    void emptyPositivePrincipalArnAlternativesCannotGrantPublicAccess(String operator) {
        for (String values : List.of("\"\"", "[]", "[\"\"]", "[\"\",\"\"]",
                "[\"\",\"" + ARN + "\"]")) {
            assertFalse(isPublic(policy(operator, "aws:PrincipalArn", values)), operator + ": " + values);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"StringNotEquals", "ArnNotEquals", "StringEqualsIfExists",
            "ArnEqualsIfExists", "ForAllValues:StringEquals", "ForAllValues:ArnEquals",
            "StringLike", "ArnLike"})
    void emptyOptionalNegatedOrVacuousConditionsRemainPublic(String operator) {
        for (String values : List.of("\"\"", "[]", "[\"\"]", "[\"\",\"" + ARN + "\"]")) {
            assertTrue(isPublic(policy(operator, "aws:PrincipalArn", values)), operator + ": " + values);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws:SourceVpc", "aws:SourceAccount", "aws:PrincipalAccount",
            "aws:SourceArn", "s3:x-amz-acl"})
    void otherConditionKeysDoNotTreatEmptyValuesAsFixed(String key) {
        for (String values : List.of("\"\"", "[]", "[\"\"]")) {
            assertTrue(isPublic(policy("StringEquals", key, values)), key + ": " + values);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"*\"", "\"?\"", "\"${aws:PrincipalArn}\"",
            "[\"\",\"*\"]", "[\"\",\"?\"]", "[\"\",\"${aws:PrincipalArn}\"]",
            "null", "[null]", "[\"\",null]", "false", "{}"})
    void wildcardVariableAndMalformedAlternativesRemainPublic(String values) {
        for (String operator : List.of("StringEquals", "ArnEquals")) {
            assertTrue(isPublic(policy(operator, "aws:PrincipalArn", values)), operator + ": " + values);
        }
    }

    @Test
    void anotherPublicStatementStillMakesTheWholePolicyPublic() {
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":"*","Action":"s3:GetObject","Resource":"*",
                   "Condition":{"StringEquals":{"aws:PrincipalArn":""}}},
                  {"Effect":"Allow","Principal":"*","Action":"s3:GetObject","Resource":"*"}]}
                """;
        assertTrue(isPublic(policy));
    }

    @Test
    void emptyPrincipalAlternativesDoNotChangeNativeIamAuthorization() {
        IamPolicyEvaluator iam = new IamPolicyEvaluator(MAPPER);
        for (String values : List.of("\"\"", "[]", "[\"\"]")) {
            String policy = policy("StringEquals", "aws:PrincipalArn", values);
            assertEquals(NEUTRAL, iam.evaluateResourcePolicy(
                    List.of(policy), null, "s3:GetObject", OBJECT_ARN, Map.of()));
            assertEquals(NEUTRAL, iam.evaluateResourcePolicy(List.of(policy), ARN,
                    "s3:GetObject", OBJECT_ARN, Map.of("aws:PrincipalArn", List.of(ARN))));
        }
        String policy = policy("StringEquals", "aws:PrincipalArn", "[\"\",\"" + ARN + "\"]");
        assertEquals(ALLOW, iam.evaluateResourcePolicy(List.of(policy), ARN,
                "s3:GetObject", OBJECT_ARN, Map.of("aws:PrincipalArn", List.of(ARN))));
        assertEquals(NEUTRAL, iam.evaluateResourcePolicy(List.of(policy), OTHER_ARN,
                "s3:GetObject", OBJECT_ARN, Map.of("aws:PrincipalArn", List.of(OTHER_ARN))));
    }

    private static boolean isPublic(String policy) {
        return S3PublicAccessEvaluator.policyIsPublic(MAPPER, policy);
    }

    private static String policy(String operator, String key, String values) {
        return """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Principal":{"AWS":"*"},
                  "Action":"s3:GetObject","Resource":"arn:aws:s3:::bootstrap/*",
                  "Condition":{"%s":{"%s":%s}}}]}
                """.formatted(operator, key, values);
    }
}
