package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.parallel.Isolated;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeAccountPoliciesResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.InvalidParameterException;
import software.amazon.awssdk.services.cloudwatchlogs.model.LimitExceededException;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutAccountPolicyResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutDestinationResponse;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.ResourceNotFoundException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@DisplayName("CloudWatch Logs cross-account management APIs")
@Isolated
class CloudWatchLogsCrossAccountPolicyTest {

    @Test
    @DisplayName("uses AWS SDK models for destination and account policy operations")
    void destinationAndAccountPolicyOperationsUseAwsSdk() {
        assumeFalse(TestFixtures.isRealAws(), "Avoids mutating account-level policies in real AWS");

        String marker = "floci-sdk-logs-" + UUID.randomUUID();
        // DeleteAccountPolicy is not implemented; retain the original reusable account-policy fixtures.
        String policyName = "floci-sdk-cross-account-transformer";
        String fieldIndexName = "floci-sdk-field-index-quoted-value";
        Deque<Executable> cleanup = new ArrayDeque<>();
        try (CloudWatchLogsClient logs = TestFixtures.cloudWatchLogsClient();
                KinesisClient kinesis = TestFixtures.kinesisClient();
                IamClient iam = TestFixtures.iamClient()) {
            try {
                cleanup.push(() -> removeIfPresent(() -> kinesis.deleteStream(request -> request.streamName(marker))));
                kinesis.createStream(request -> request.streamName(marker).shardCount(1));
                String streamArn = kinesis.describeStreamSummary(request -> request.streamName(marker))
                        .streamDescriptionSummary().streamARN();
                cleanup.push(() -> removeIfPresent(() -> iam.deleteRole(request -> request.roleName(marker))));
                String roleArn = iam.createRole(request -> request.roleName(marker).assumeRolePolicyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Principal":{"Service":"logs.amazonaws.com"},"Action":"sts:AssumeRole"}]}
                        """)).role().arn();
                cleanup.push(() -> removeIfPresent(() -> iam.deleteRolePolicy(request -> request.roleName(marker).policyName("delivery"))));
                iam.putRolePolicy(request -> request.roleName(marker).policyName("delivery").policyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Action":"kinesis:PutRecord","Resource":"%s"}]}
                        """.formatted(streamArn)));
                cleanup.push(() -> removeIfPresent(() -> logs.deleteDestination(request -> request.destinationName(marker))));
                PutDestinationResponse putDestination = logs.putDestination(request -> request
                        .destinationName(marker)
                        .targetArn(streamArn)
                        .roleArn(roleArn));

                assertThat(putDestination.destination()).isNotNull();
                assertThat(putDestination.destination().destinationName()).isEqualTo(marker);
                assertThat(putDestination.destination().arn()).contains(":logs:" + TestFixtures.region().id() + ":");

                logs.putDestinationPolicy(request -> request
                        .destinationName(marker)
                        .accessPolicy("{\"Version\":\"2012-10-17\",\"Statement\":[]}"));

                PutAccountPolicyResponse putPolicy = logs.putAccountPolicy(request -> request
                        .policyName(policyName)
                        .policyType("TRANSFORMER_POLICY")
                        .policyDocument("[{\"parseJSON\":{}}]")
                        .selectionCriteria("LogGroupNamePrefix = \"/floci/sdk-cross-account/\"")
                        .scope("ALL"));

                assertThat(putPolicy.accountPolicy()).isNotNull();
                assertThat(putPolicy.accountPolicy().policyName()).isEqualTo(policyName);
                assertThat(putPolicy.accountPolicy().policyTypeAsString()).isEqualTo("TRANSFORMER_POLICY");

                DescribeAccountPoliciesResponse policies = logs.describeAccountPolicies(request -> request.policyType("TRANSFORMER_POLICY"));
                assertThat(policies.accountPolicies())
                        .anySatisfy(policy -> {
                            assertThat(policy.policyName()).isEqualTo(policyName);
                            assertThat(policy.selectionCriteria())
                                    .isEqualTo("LogGroupNamePrefix = \"/floci/sdk-cross-account/\"");
                        });

                PutAccountPolicyResponse fieldIndex = logs.putAccountPolicy(request -> request
                        .policyName(fieldIndexName)
                        .policyType("FIELD_INDEX_POLICY")
                        .policyDocument("{\"Fields\":[\"requestId\"]}")
                        .selectionCriteria("LogGroupNamePrefix = \"/DataSourceName/DataSourceType/\"")
                        .scope("ALL"));

                assertThat(fieldIndex.accountPolicy().selectionCriteria())
                        .isEqualTo("LogGroupNamePrefix = \"/DataSourceName/DataSourceType/\"");

                assertThatThrownBy(() -> logs.putAccountPolicy(request -> request
                        .policyName("floci-sdk-field-index-global")
                        .policyType("FIELD_INDEX_POLICY")
                        .policyDocument("{\"Fields\":[\"requestId\"]}")
                        .scope("ALL")))
                        .isInstanceOfSatisfying(LimitExceededException.class, error ->
                                assertThat(error.awsErrorDetails().errorCode()).isEqualTo("LimitExceededException"));
            } finally {
                assertAll("Remove owned destination, role and stream", cleanup);
            }
            assertThatThrownBy(() -> iam.getRole(request -> request.roleName(marker)))
                    .isInstanceOf(NoSuchEntityException.class);
            assertThatThrownBy(() -> kinesis.describeStreamSummary(request -> request.streamName(marker)))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(logs.describeDestinations(request -> request.destinationNamePrefix(marker)).destinations()).isEmpty();
        }
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

    private static void removeIfPresent(Executable deletion) throws Throwable {
        try {
            deletion.execute();
        } catch (AwsServiceException expected) {
            if (!Set.of("ResourceNotFoundException", "NoSuchEntity").contains(expected.awsErrorDetails().errorCode())) {
                throw expected;
            }
            // Cleanup is registered before creation, so a failed create may leave no resource.
        }
    }
}
