package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.SnsException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native setup and guarded cleanup shared only by drift SDK tests. */
final class CfnDriftNativeFixture implements AutoCloseable {
    final String marker = UUID.randomUUID().toString();
    final String name = "native-drift-" + marker;
    final CloudFormationClient cfn;
    final SnsClient sns;
    final Stack stack;
    final Map<String, String> topics = new LinkedHashMap<>();

    CfnDriftNativeFixture(String account, Region region) {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, region);
        sns = AwsTestClients.create(SnsClient.builder(), account, region);
        String id = cfn.createStack(request -> request.stackName(name).templateBody("""
                {"Resources":{
                  "A":{"Type":"AWS::SNS::Topic","Properties":{"TopicName":"%s-a","DisplayName":"before"}},
                  "B":{"Type":"AWS::SNS::Topic","Properties":{"TopicName":"%s-b","DisplayName":"before"}}
                }}
                """.formatted(name, name)).tags(tag -> tag.key("OwnedDrift").value(marker))).stackId();
        stack = CfnStackWaits.awaitStatus(cfn, id, "CREATE_COMPLETE");
        for (String logical : new String[]{"A", "B"}) {
            String arn = cfn.describeStackResource(request -> request.stackName(id).logicalResourceId(logical))
                    .stackResourceDetail().physicalResourceId();
            assertEquals("before", sns.getTopicAttributes(request -> request.topicArn(arn))
                    .attributes().get("DisplayName"));
            topics.put(logical, arn);
        }
    }

    String detect() {
        return cfn.detectStackDrift(request -> request.stackName(stack.stackId())).stackDriftDetectionId();
    }

    void display(String logical, String value) {
        sns.setTopicAttributes(request -> request.topicArn(topics.get(logical))
                .attributeName("DisplayName").attributeValue(value));
    }

    @Override
    public void close() {
        try {
            Stack current = cfn.describeStacks(request -> request.stackName(name)).stacks().getFirst();
            assertEquals(stack.stackId(), current.stackId());
            assertEquals(stack.creationTime(), current.creationTime());
            assertTrue(current.tags().stream().anyMatch(tag ->
                    "OwnedDrift".equals(tag.key()) && marker.equals(tag.value())));
            cfn.deleteStack(request -> request.stackName(stack.stackId()));
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
                CloudFormationException absent = assertThrows(CloudFormationException.class,
                        () -> cfn.describeStacks(request -> request.stackName(name)));
                assertEquals(400, absent.statusCode());
                assertEquals("ValidationError", absent.awsErrorDetails().errorCode());
            });
            for (String arn : topics.values()) {
                SnsException absent = assertThrows(SnsException.class,
                        () -> sns.getTopicAttributes(request -> request.topicArn(arn)));
                assertEquals(404, absent.statusCode());
            }
        } finally {
            cfn.close();
            sns.close();
        }
    }
}
