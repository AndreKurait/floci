package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.parallel.Isolated;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeAccountPoliciesResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.InvalidParameterException;
import software.amazon.awssdk.services.cloudwatchlogs.model.LimitExceededException;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutAccountPolicyResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutDestinationResponse;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.StreamDescriptionSummary;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("CloudWatch Logs cross-account management APIs")
@Isolated
class CloudWatchLogsCrossAccountPolicyTest {
    private static final String DESTINATION_NAME = TestFixtures.uniqueName("floci-sdk-cross-account-destination");
    private static final String POLICY_NAME = TestFixtures.uniqueName("floci-sdk-cross-account-transformer");

    @Test
    @DisplayName("uses AWS SDK models for destination and account policy operations")
    void destinationAndAccountPolicyOperationsUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Uses emulator-only account policy and destination resources");

        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient();
             IamClient iam = TestFixtures.iamClient();
             KinesisClient kinesis = TestFixtures.kinesisClient()) {
            List<Executable> cleanup = new ArrayList<>();
            assertAll("destination and account policy lifecycle", () -> {
                PutDestinationResponse putDestination = createDestination(logs, iam, kinesis, cleanup);

                assertThat(putDestination.destination()).isNotNull();
                assertThat(putDestination.destination().destinationName()).isEqualTo(DESTINATION_NAME);
                assertThat(putDestination.destination().arn()).contains(":logs:" + TestFixtures.region().id() + ":");

                logs.putDestinationPolicy(request -> request
                        .destinationName(DESTINATION_NAME)
                        .accessPolicy("{\"Version\":\"2012-10-17\",\"Statement\":[]}"));

                PutAccountPolicyResponse putPolicy = logs.putAccountPolicy(request -> request
                        .policyName(POLICY_NAME)
                        .policyType("TRANSFORMER_POLICY")
                        .policyDocument("[{\"parseJSON\":{}}]")
                        .selectionCriteria("LogGroupNamePrefix = \"/floci/sdk-cross-account/\"")
                        .scope("ALL"));
                cleanup.add(0, () -> logs.deleteAccountPolicy(request -> request
                        .policyName(POLICY_NAME).policyType("TRANSFORMER_POLICY")));

                assertThat(putPolicy.accountPolicy()).isNotNull();
                assertThat(putPolicy.accountPolicy().policyName()).isEqualTo(POLICY_NAME);
                assertThat(putPolicy.accountPolicy().policyTypeAsString()).isEqualTo("TRANSFORMER_POLICY");

                DescribeAccountPoliciesResponse policies = logs.describeAccountPolicies(request -> request.policyType("TRANSFORMER_POLICY"));
                assertThat(policies.accountPolicies())
                        .anySatisfy(policy -> {
                            assertThat(policy.policyName()).isEqualTo(POLICY_NAME);
                            assertThat(policy.selectionCriteria())
                                    .isEqualTo("LogGroupNamePrefix = \"/floci/sdk-cross-account/\"");
                        });

                String fieldIndexName = TestFixtures.uniqueName("floci-sdk-field-index-quoted-value");
                PutAccountPolicyResponse fieldIndex = logs.putAccountPolicy(request -> request
                        .policyName(fieldIndexName)
                        .policyType("FIELD_INDEX_POLICY")
                        .policyDocument("{\"Fields\":[\"requestId\"]}")
                        .selectionCriteria("LogGroupNamePrefix = \"/DataSourceName/DataSourceType/\"")
                        .scope("ALL"));
                cleanup.add(0, () -> logs.deleteAccountPolicy(request -> request
                        .policyName(fieldIndexName).policyType("FIELD_INDEX_POLICY")));

                assertThat(fieldIndex.accountPolicy().selectionCriteria())
                        .isEqualTo("LogGroupNamePrefix = \"/DataSourceName/DataSourceType/\"");

                String globalIndexName = TestFixtures.uniqueName("floci-sdk-field-index-global");
                assertThatThrownBy(() -> {
                    logs.putAccountPolicy(request -> request
                            .policyName(globalIndexName)
                            .policyType("FIELD_INDEX_POLICY")
                            .policyDocument("{\"Fields\":[\"requestId\"]}")
                            .scope("ALL"));
                    cleanup.add(0, () -> logs.deleteAccountPolicy(request -> request
                            .policyName(globalIndexName).policyType("FIELD_INDEX_POLICY")));
                }).isInstanceOfSatisfying(LimitExceededException.class, error ->
                        assertThat(error.awsErrorDetails().errorCode()).isEqualTo("LimitExceededException"));
            }, () -> assertAll("remove destination, policies, role and stream fixtures", cleanup.stream()));
        }
    }

    private static PutDestinationResponse createDestination(
            CloudWatchLogsClient logs, IamClient iam, KinesisClient kinesis, List<Executable> cleanup) {
        String name = TestFixtures.uniqueName("floci-sdk-logs");
        kinesis.createStream(request -> request.streamName(name).shardCount(1));
        cleanup.add(0, () -> kinesis.deleteStream(request -> request.streamName(name).enforceConsumerDeletion(true)));
        StreamDescriptionSummary stream = kinesis.describeStreamSummary(request -> request.streamName(name))
                .streamDescriptionSummary();
        assertThat(stream.streamStatusAsString()).isEqualTo("ACTIVE");
        String streamArn = stream.streamARN();
        String accountId = streamArn.split(":")[4];
        String sourceArn = TestFixtures.arn("logs", accountId, "*");
        String principal = "logs.amazonaws.com"; // partition-literal: universal Logs service principal in every partition
        String roleArn = iam.createRole(request -> request.roleName(name).assumeRolePolicyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Principal":{"Service":"%s"},"Action":"sts:AssumeRole",
                "Condition":{"StringEquals":{"aws:SourceAccount":"%s"},
                "ArnLike":{"aws:SourceArn":"%s"}}}]}
                """.formatted(principal, accountId, sourceArn))).role().arn();
        cleanup.add(0, () -> iam.deleteRole(request -> request.roleName(name)));
        iam.putRolePolicy(request -> request.roleName(name).policyName("delivery").policyDocument("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":"kinesis:PutRecord","Resource":"%s"}]}
                """.formatted(streamArn)));
        cleanup.add(0, () -> iam.deleteRolePolicy(request -> request.roleName(name).policyName("delivery")));
        PutDestinationResponse response = logs.putDestination(request -> request
                .destinationName(DESTINATION_NAME).targetArn(streamArn).roleArn(roleArn));
        cleanup.add(0, () -> logs.deleteDestination(request -> request.destinationName(DESTINATION_NAME)));
        return response;
    }

    @Test
    @DisplayName("returns the modeled SDK error for mixed field-index selection criteria")
    void mixedFieldIndexCriteriaReturnsSdkInvalidParameterException() {
        assumeFalse(TestFixtures.isRealAws(), "Avoids mutating account-level policies in real AWS");

        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient()) {
            assertThatThrownBy(() -> logs.putAccountPolicy(request -> request
                    .policyName("floci-sdk-invalid-mixed-index")
                    .policyType("FIELD_INDEX_POLICY")
                    .policyDocument("{\"Fields\":[\"requestId\"]}")
                    .selectionCriteria("LogGroupNamePrefix = \"/floci/\" AND DataSourceName = \"amazon_vpc\" AND DataSourceType = \"flow\"")
                    .scope("ALL")))
                    .isInstanceOfSatisfying(InvalidParameterException.class, error ->
                            assertThat(error.awsErrorDetails().errorCode()).isEqualTo("InvalidParameterException"));
        }
    }
}
