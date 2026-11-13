package io.floci.aws.services.kinesis;

import java.util.stream.Stream;

import static io.floci.aws.services.kinesis.KinesisResourcePolicyServiceTest.POLICY;

final class KinesisPolicyReviewCases {
    private KinesisPolicyReviewCases() {
    }

    static Stream<String> rejectedPolicies() {
        return Stream.of(
                POLICY + " ".repeat(25_000),
                POLICY.replace("\"Version\"", "\"Id\":\"" + "é".repeat(11_000) + "\",\"Version\""),
                POLICY.replace("\"kinesis:GetRecords\"", "\"*\""),
                POLICY.replace("\"kinesis:GetRecords\"", "[\"kinesis:GetRecords\",\"kinesis:Put*\"]"),
                POLICY.replace("\"Action\":\"kinesis:GetRecords\"", "\"NotAction\":\"kinesis:*\""),
                POLICY.replace("\"Action\":\"kinesis:GetRecords\"", "\"NotAction\":[\"kinesis:* \"]"),
                POLICY.replace("{\"AWS\":\"222222222222\"}", "\"*\""),
                POLICY.replace("{\"AWS\":\"222222222222\"}", "{\"AWS\":[\"222222222222\",\"*\"]}"),
                POLICY.replace("\"Principal\":{\"AWS\":\"222222222222\"}", "\"NotPrincipal\":\"*\""),
                POLICY.replace("\"Principal\":{\"AWS\":\"222222222222\"}",
                        "\"NotPrincipal\":{\"AWS\":[\"*\"]}"),
                POLICY.replace("{\"AWS\":\"222222222222\"}", "{\"Service\":\"lambda.amazonaws.com\"}"),
                POLICY.replace("{\"AWS\":\"222222222222\"}", "{\"Federated\":\"example.com\"}"),
                POLICY.replace("{\"AWS\":\"222222222222\"}", "{\"CanonicalUser\":\"abcdef\"}"),
                POLICY.replace("{\"AWS\":\"222222222222\"}", "{\"Unknown\":\"222222222222\"}"),
                POLICY.replace("{\"AWS\":\"222222222222\"}",
                        "{\"AWS\":\"222222222222\",\"Service\":\"lambda.amazonaws.com\"}"));
    }

    static String supportedPrincipals() {
        return POLICY.replace("\"222222222222\"", "[\"222222222222\","
                + "\"arn:aws:iam::222222222222:user/example\",\"arn:aws:iam::222222222222:role/example\"]");
    }
}
