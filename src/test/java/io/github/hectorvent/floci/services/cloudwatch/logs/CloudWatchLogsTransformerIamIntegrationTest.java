package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;

import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsTransformerIntegrationTest.processor;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudWatchLogsTransformerIamIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final Region REGION = Region.US_EAST_1;

    @Test
    void nativeRegisteredUserUsesExactGroupResourcesTagsAndWildcardPreview() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String group = "/transform/iam-" + suffix;
        String sibling = group + "-sibling";
        String userName = "transform-" + suffix;
        try (CloudWatchLogsClient root = AwsTestClients.create(CloudWatchLogsClient.builder(), ACCOUNT, REGION);
                IamClient iam = AwsTestClients.create(IamClient.builder(), ACCOUNT, REGION)) {
            root.createLogGroup(request -> request.logGroupName(group).tags(Map.of("team", "blue")));
            root.createLogGroup(request -> request.logGroupName(sibling));
            iam.createUser(request -> request.userName(userName));
            AccessKey key = iam.createAccessKey(request -> request.userName(userName)).accessKey();
            boolean previewPolicy = false;
            try {
                String arn = "arn:aws:logs:us-east-1:" + ACCOUNT + ":log-group:" + group;
                String policy = """
                        {"Version":"2012-10-17","Statement":[
                         {"Effect":"Allow","Action":"logs:*Transformer","Resource":"%s"},
                         {"Effect":"Deny","Action":"logs:GetTransformer","Resource":"%s",
                          "Condition":{"StringEquals":{"aws:ResourceTag/team":"red"}}}]}
                        """.formatted(arn, arn);
                iam.putUserPolicy(request -> request.userName(userName).policyName("group").policyDocument(policy));
                try (CloudWatchLogsClient user = AwsTestClients.create(CloudWatchLogsClient.builder(),
                        key.accessKeyId(), key.secretAccessKey(), REGION)) {
                    user.putTransformer(request -> request.logGroupIdentifier(group).transformerConfig(processor("parsed")));
                    assertEquals(arn, user.getTransformer(request -> request.logGroupIdentifier(arn)).logGroupIdentifier());
                    assertEquals("parsed", user.getTransformer(request -> request.logGroupIdentifier(group))
                            .transformerConfig().getFirst().parseJSON().destination());
                    denied(() -> user.getTransformer(request -> request.logGroupIdentifier(sibling)));
                    denied(() -> user.putTransformer(request -> request.logGroupIdentifier(sibling)
                            .transformerConfig(processor(null))));
                    denied(() -> user.deleteTransformer(request -> request.logGroupIdentifier(sibling)));
                    assertTrue(root.getTransformer(request -> request.logGroupIdentifier(sibling)).transformerConfig().isEmpty());
                    denied(() -> user.testTransformer(request -> request.transformerConfig(processor(null))
                            .logEventMessages("{}")));
                    root.tagLogGroup(request -> request.logGroupName(group).tags(Map.of("team", "red")));
                    denied(() -> user.getTransformer(request -> request.logGroupIdentifier(group)));
                    denied(() -> user.getTransformer(request -> request.logGroupIdentifier(arn)));
                    root.tagLogGroup(request -> request.logGroupName(group).tags(Map.of("team", "blue")));
                    assertEquals(arn, user.getTransformer(request -> request.logGroupIdentifier(group)).logGroupIdentifier());
                    iam.putUserPolicy(request -> request.userName(userName).policyName("preview").policyDocument("""
                            {"Version":"2012-10-17","Statement":[
                              {"Effect":"Allow","Action":"logs:TestTransformer","Resource":"*"}]}
                            """));
                    previewPolicy = true;
                    String transformed = user.testTransformer(request -> request.transformerConfig(processor("parsed"))
                            .logEventMessages("{\"value\":7}")).transformedLogs().getFirst().transformedEventMessage();
                    assertEquals(7, new ObjectMapper().readTree(transformed).path("parsed").path("value").intValue());
                    user.deleteTransformer(request -> request.logGroupIdentifier(arn));
                    assertTrue(user.getTransformer(request -> request.logGroupIdentifier(group)).transformerConfig().isEmpty());
                }
            } finally {
                iam.deleteUserPolicy(request -> request.userName(userName).policyName("group"));
                if (previewPolicy) {
                    iam.deleteUserPolicy(request -> request.userName(userName).policyName("preview"));
                }
                iam.deleteAccessKey(request -> request.userName(userName).accessKeyId(key.accessKeyId()));
                iam.deleteUser(request -> request.userName(userName));
                root.deleteLogGroup(request -> request.logGroupName(group));
                root.deleteLogGroup(request -> request.logGroupName(sibling));
            }
        }
    }

    private static void denied(Runnable request) {
        CloudWatchLogsException error = assertThrows(CloudWatchLogsException.class, request::run);
        assertEquals("AccessDeniedException", error.awsErrorDetails().errorCode());
    }
}
