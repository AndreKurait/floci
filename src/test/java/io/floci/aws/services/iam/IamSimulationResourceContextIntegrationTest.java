package io.floci.aws.services.iam;

import io.floci.aws.testing.SignedIamEnforcementProfile;
import io.floci.aws.testing.SignedIamUsers;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.ContextEntry;
import software.amazon.awssdk.services.iam.model.ContextKeyTypeEnum;
import software.amazon.awssdk.services.iam.model.EvaluationResult;
import software.amazon.awssdk.services.iam.model.PolicyEvaluationDecisionType;
import software.amazon.awssdk.services.iam.model.ResourceSpecificResult;
import software.amazon.awssdk.services.iam.model.SimulateCustomPolicyRequest;
import software.amazon.awssdk.services.iam.model.SimulateCustomPolicyResponse;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(SignedIamEnforcementProfile.class)
class IamSimulationResourceContextIntegrationTest {
    private static final String PUBLIC = "arn:aws:s3:::simulation-bucket/public/item";
    private static final String PRIVATE = "arn:aws:s3:::simulation-bucket/private/item";
    private static final String ALLOW = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"s3:Get*","Resource":"*"}]}
            """;
    @TestHTTPResource("/")
    URI endpoint;

    @ParameterizedTest
    @ValueSource(strings = {"aws:ResourceArn", "aws:ARN"})
    void customPolicyUsesEachActionAndResourcePair(String key) {
        try (IamClient iam = client()) {
            List<EvaluationResult> results = iam.simulateCustomPolicy(request -> request
                    .policyInputList(ALLOW, denyPrivate(key))
                    .actionNames("s3:GetObject", "s3:GetObjectTagging")
                    .resourceArns(PUBLIC, PRIVATE)).evaluationResults();
            assertEquals(List.of("s3:GetObject", "s3:GetObjectTagging"),
                    results.stream().map(EvaluationResult::evalActionName).toList());
            assertEquals(List.of(PUBLIC, PRIVATE, PUBLIC, PRIVATE),
                    resources(results));
            assertEquals(List.of("allowed", "explicitDeny", "allowed", "explicitDeny"), resourceDecisions(results));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws:ResourceArn", "aws:ARN"})
    void principalPolicyUsesEachResourceArn(String key) throws Exception {
        try (SignedIamUsers.User user = SignedIamUsers.createUser(
                endpoint, "simulation-resource-", "read-public", allowPublic(key));
             IamClient iam = client()) {
            String arn = iam.getUser(request -> request.userName(user.name())).user().arn();
            List<EvaluationResult> results = iam.simulatePrincipalPolicy(request -> request
                    .policySourceArn(arn).actionNames("s3:GetObject")
                    .resourceArns(PUBLIC, PRIVATE)).evaluationResults();
            assertEquals(List.of(PUBLIC, PRIVATE), resources(results));
            assertEquals(List.of("allowed", "implicitDeny"), resourceDecisions(results));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "aws:ResourceArn, aws:ResourceArn, allowed",
            "aws:ResourceArn, AWS:RESOURCEARN, allowed",
            "aws:ResourceArn, aws:resourcearn, allowed",
            "AWS:RESOURCEARN, AWS:RESOURCEARN, allowed",
            "aws:ResourceArn, aws:ARN, explicitDeny",
            "aws:ARN, aws:ARN, allowed",
            "aws:ARN, aws:arn, allowed",
            "aws:ARN, aws:Arn, allowed",
            "aws:ARN, AWS:ARN, explicitDeny",
            "AWS:ARN, aws:ARN, allowed",
            "AWS:ARN, AWS:ARN, explicitDeny",
            "aws:ARN, aws:ResourceArn, explicitDeny"
    })
    void explicitContextUsesAwsNamespaceWithoutChangingTheResource(
            String conditionKey, String contextKey, String expected) {
        try (IamClient iam = client()) {
            List<EvaluationResult> results = iam.simulateCustomPolicy(request -> request
                    .policyInputList(ALLOW, denyPrivate(conditionKey)).actionNames("s3:GetObject").resourceArns(PRIVATE)
                    .contextEntries(ContextEntry.builder().contextKeyName(contextKey)
                            .contextKeyValues(PUBLIC).contextKeyType(ContextKeyTypeEnum.STRING).build()))
                    .evaluationResults();
            assertEquals(1, results.size());
            assertEquals(List.of(PRIVATE), resources(results));
            assertEquals(expected, results.getFirst().evalDecisionAsString());
        }
    }

    @Test
    void pagesKeepResourceContextIndependent() {
        try (IamClient iam = client()) {
            SimulateCustomPolicyRequest.Builder request = SimulateCustomPolicyRequest.builder()
                    .policyInputList(ALLOW, denyPrivate("aws:ResourceArn"))
                    .actionNames("s3:GetObject", "s3:GetObjectTagging")
                    .resourceArns(PUBLIC, PRIVATE, PUBLIC + "-next").maxItems(1);
            List<String> resources = List.of(PUBLIC, PRIVATE, PUBLIC + "-next");
            List<String> actions = List.of("s3:GetObject", "s3:GetObjectTagging");
            for (int i = 0; i < actions.size(); i++) {
                SimulateCustomPolicyResponse response = iam.simulateCustomPolicy(request.build());
                assertEquals(1, response.evaluationResults().size());
                EvaluationResult result = response.evaluationResults().getFirst();
                assertEquals(actions.get(i), result.evalActionName());
                assertEquals("explicitDeny", result.evalDecisionAsString());
                assertEquals(resources, resources(response.evaluationResults()));
                assertEquals(List.of("allowed", "explicitDeny", "allowed"),
                        resourceDecisions(response.evaluationResults()));
                assertEquals(i < actions.size() - 1, Boolean.TRUE.equals(response.isTruncated()));
                if (Boolean.TRUE.equals(response.isTruncated())) {
                    assertNotNull(response.marker());
                }
                request.marker(response.marker());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws:ResourceArn", "aws:ARN"})
    void omittedResourceUsesWildcardContext(String key) {
        try (IamClient iam = client()) {
            List<EvaluationResult> results = iam.simulateCustomPolicy(request -> request
                    .policyInputList(allowExact(key, "*")).actionNames("s3:GetObject")).evaluationResults();
            assertEquals(1, results.size());
            assertFalse(results.getFirst().hasResourceSpecificResults());
            assertEquals(PolicyEvaluationDecisionType.ALLOWED, results.getFirst().evalDecision());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws:ResourceArn", "aws:ARN"})
    void permissionsBoundaryUsesResourceContext(String key) {
        try (IamClient iam = client()) {
            List<EvaluationResult> results = iam.simulateCustomPolicy(request -> request
                    .policyInputList(ALLOW).permissionsBoundaryPolicyInputList(allowPublic(key))
                    .actionNames("s3:GetObject").resourceArns(PUBLIC, PRIVATE)).evaluationResults();
            assertEquals(List.of("allowed", "implicitDeny"), resourceDecisions(results));
        }
    }

    @Test
    void unrelatedMissingConditionRemainsAbsent() {
        try (IamClient iam = client()) {
            List<EvaluationResult> results = iam.simulateCustomPolicy(request -> request
                    .policyInputList(allowExact("aws:SourceArn", PUBLIC)).actionNames("s3:GetObject")
                    .resourceArns(PUBLIC)).evaluationResults();
            assertEquals(List.of("implicitDeny"), resourceDecisions(results));
            assertEquals(List.of("aws:SourceArn"), results.getFirst().missingContextValues());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws-us-gov:s3:::simulation-bucket/public/item",
            "arn:aws-cn:s3:::simulation-bucket/public/item"
    })
    void resourceArnRetainsItsPartition(String resource) {
        try (IamClient iam = client()) {
            List<EvaluationResult> results = iam.simulateCustomPolicy(request -> request
                    .policyInputList(allowExact("aws:ResourceArn", resource)).actionNames("s3:GetObject")
                    .resourceArns(resource)).evaluationResults();
            assertEquals(1, results.size());
            assertEquals(List.of(resource), resources(results));
            assertEquals(PolicyEvaluationDecisionType.ALLOWED, results.getFirst().evalDecision());
        }
    }

    private IamClient client() {
        return IamClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static List<String> resources(List<EvaluationResult> results) {
        return results.stream().flatMap(result -> result.resourceSpecificResults().stream())
                .map(ResourceSpecificResult::evalResourceName).toList();
    }

    private static List<String> resourceDecisions(List<EvaluationResult> results) {
        return results.stream().flatMap(result -> result.resourceSpecificResults().stream())
                .map(ResourceSpecificResult::evalResourceDecisionAsString).toList();
    }

    private static String denyPrivate(String key) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Deny","Action":"s3:Get*","Resource":"*",
                   "Condition":{"StringNotLike":{"%s":"arn:aws:s3:::simulation-bucket/public/*"}}}]}
                """.formatted(key);
    }

    private static String allowPublic(String key) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:Get*","Resource":"*",
                   "Condition":{"StringLike":{"%s":"arn:aws:s3:::simulation-bucket/public/*"}}}]}
                """.formatted(key);
    }

    private static String allowExact(String key, String value) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:Get*","Resource":"*",
                   "Condition":{"StringEquals":{"%s":"%s"}}}]}
                """.formatted(key, value);
    }
}
