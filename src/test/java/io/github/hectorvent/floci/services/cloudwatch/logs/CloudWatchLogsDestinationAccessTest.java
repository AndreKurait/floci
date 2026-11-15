package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogDestination;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudWatchLogsDestinationAccessTest {
    private static final String SOURCE = "111111111111";
    private static final String RECIPIENT = "222222222222";
    private static final String ARN = "arn:aws:logs:us-east-1:" + RECIPIENT + ":destination:owned";
    private final ObjectMapper mapper = new ObjectMapper();
    private final CloudWatchLogsDestinationAccess access =
            new CloudWatchLogsDestinationAccess(new IamPolicyEvaluator(mapper), mapper);

    @ParameterizedTest
    @ValueSource(strings = {"111111111111", "arn:aws:iam::111111111111:root", "*"})
    void typedAccountGrantsRequireMatchingActionResourceAndGlobalConditions(String principal) {
        LogDestination destination = destination("""
                {"Statement":[{"Effect":"Allow","Principal":{"AWS":"%s"},
                "Action":"logs:PutSubscriptionFilter","Resource":"%s",
                "Condition":{"StringEquals":{"aws:PrincipalAccount":"111111111111",
                "aws:ResourceAccount":"222222222222","aws:RequestedRegion":"us-east-1"}}}]}
                """.formatted(principal, ARN));
        assertDoesNotThrow(() -> access.requireAccountAccess(destination, SOURCE, "us-east-1"));
        denied(destination, "333333333333", "us-east-1");
        denied(destination, SOURCE, "us-west-2");
        destination.setAccessPolicy(destination.getAccessPolicy().replace("logs:PutSubscriptionFilter", "logs:DeleteDestination"));
        denied(destination, SOURCE, "us-east-1");
    }

    @Test
    void removingTheAccountGrantOrAddingExplicitDenyStopsLaterDelivery() {
        LogDestination destination = destination("""
                {"Statement":[{"Effect":"Allow","Principal":{"AWS":"111111111111"},
                "Action":"logs:PutSubscriptionFilter","Resource":"*"}]}
                """);
        assertDoesNotThrow(() -> access.requireAccountAccess(destination, SOURCE, "us-east-1"));
        destination.setAccessPolicy(destination.getAccessPolicy().replace("111111111111", "333333333333"));
        denied(destination, SOURCE, "us-east-1");
        destination.setAccessPolicy("""
                {"Statement":[{"Effect":"Allow","Principal":"*","Action":"logs:*","Resource":"*"},
                {"Effect":"Deny","Principal":{"AWS":"111111111111"},
                "Action":"logs:PutSubscriptionFilter","Resource":"*"}]}
                """);
        denied(destination, SOURCE, "us-east-1");
        destination.setAccessPolicy(null);
        denied(destination, SOURCE, "us-east-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"Principal\":{\"AWS\":\"arn:aws:iam::111111111111:user/author\"}",
            "\"Principal\":{\"AWS\":\"arn:aws:iam::111111111111:role/author\"}",
            "\"Principal\":{\"Service\":\"logs.amazonaws.com\"}",
            "\"NotPrincipal\":{\"AWS\":\"333333333333\"}",
            "\"Principal\":\"*\",\"Condition\":{\"ArnEquals\":{\"aws:PrincipalArn\":\"arn:aws:iam::111111111111:root\"}}",
            "\"Principal\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:PrincipalOrgID\":\"o-example\"}}",
            "\"Principal\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:PrincipalTag/team\":\"blue\"}}",
            "\"Principal\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:userid\":\"example\"}}",
            "\"Principal\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:SourceAccount\":\"111111111111\"}}",
            "\"Principal\":\"*\",\"Condition\":{\"StringEquals\":{\"aws:PrincipalAccount\":\"${aws:PrincipalAccount}\"}}"
    })
    void unsupportedIdentityDependentPoliciesNeverUseAManufacturedRootIdentity(String members) {
        LogDestination destination = destination("{\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"logs:PutSubscriptionFilter\",\"Resource\":\"*\","
                + members + "}]}");
        denied(destination, SOURCE, "us-east-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UnknownOperator", "NullIfExists", "ForAnyValue:Null"})
    void anUnsupportedDenyCannotDisappearBehindABroadAllow(String operator) {
        LogDestination destination = destination("""
                {"Statement":[{"Effect":"Allow","Principal":"*","Action":"logs:*","Resource":"*"},
                {"Effect":"Deny","Principal":"*","Action":"logs:*","Resource":"*",
                "Condition":{"%s":{"aws:PrincipalAccount":"111111111111"}}}]}
                """.formatted(operator));
        String original = destination.getAccessPolicy();
        denied(destination, SOURCE, "us-east-1");
        assertEquals(original, destination.getAccessPolicy());
        assertEquals(ARN, destination.getArn());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "[{}]", "[null]", "[[\"nested\"]]"})
    void malformedDenyConditionValuesCannotDisappearBehindABroadAllow(String value) {
        LogDestination destination = destination("""
                {"Statement":[{"Effect":"Allow","Principal":"*","Action":"logs:*","Resource":"*"},
                {"Effect":"Deny","Principal":"*","Action":"logs:*","Resource":"*",
                "Condition":{"StringEquals":{"aws:PrincipalAccount":%s}}}]}
                """.formatted(value));
        String original = destination.getAccessPolicy();
        denied(destination, SOURCE, "us-east-1");
        assertEquals(original, destination.getAccessPolicy());
        assertEquals(ARN, destination.getArn());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"Effect\":null,\"Action\":\"logs:*\",\"Resource\":\"*\"",
            "\"Effect\":\"Deny\",\"Action\":{},\"Resource\":\"*\"",
            "\"Effect\":\"Deny\",\"Action\":[null],\"Resource\":\"*\"",
            "\"Effect\":\"Deny\",\"Action\":\"logs:*\",\"Resource\":{}",
            "\"Effect\":\"Deny\",\"Action\":\"logs:*\",\"NotAction\":\"logs:Describe*\",\"Resource\":\"*\""
    })
    void malformedDenyStatementShapesCannotDisappearBehindABroadAllow(String members) {
        LogDestination destination = destination("""
                {"Statement":[{"Effect":"Allow","Principal":"*","Action":"logs:*","Resource":"*"},
                {"Principal":"*",%s}]}
                """.formatted(members));
        String original = destination.getAccessPolicy();
        denied(destination, SOURCE, "us-east-1");
        assertEquals(original, destination.getAccessPolicy());
        assertEquals(ARN, destination.getArn());
    }

    @Test
    void sameAccountDoesNotRequireACrossAccountPolicy() {
        assertDoesNotThrow(() -> access.requireAccountAccess(destination(null), RECIPIENT, "us-east-1"));
    }

    private void denied(LogDestination destination, String account, String region) {
        AwsException failure = assertThrows(AwsException.class,
                () -> access.requireAccountAccess(destination, account, region));
        assertEquals("AccessDeniedException", failure.getErrorCode());
    }

    private static LogDestination destination(String policy) {
        LogDestination destination = new LogDestination();
        destination.setArn(ARN);
        destination.setAccessPolicy(policy);
        return destination;
    }
}
