package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SqsQueuePolicyCfnSdkIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestHTTPResource("/")
    URI endpoint;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({
        "us-east-1,false", "us-east-1,true",
        "us-gov-west-1,false", "us-gov-west-1,true",
        "cn-north-1,false", "cn-north-1,true"
    })
    void policyAndDestinationUpdatesReachQueuesAndDeleteDetachesThem(String region, boolean serialized) throws Exception {
        try (CloudFormationClient cfn = cfn(region); SqsClient sqs = sqs(region)) {
            String first = queue(sqs, region);
            String second = queue(sqs, region);
            String third = queue(sqs, region);
            String stack = stack(cfn, region, template(List.of(first, second), "Before", serialized, false));
            waitFor(cfn, stack, StackStatus.CREATE_COMPLETE);
            Map<String, String> originalOutputs = outputs(cfn, stack);
            assertEquals(originalOutputs.get("Reference"), originalOutputs.get("Id"));
            assertFalse(originalOutputs.get("Id").isBlank());
            JsonNode before = policy(sqs, first);
            assertEquals(before, policy(sqs, second));
            assertEquals("Before", before.path("Statement").get(0).path("Sid").asText());
            assertFalse(before.toString().contains("${AWS::"));

            cfn.updateStack(request -> request.stackName(stack)
                    .templateBody(template(List.of(second, third), "After", serialized, false)));
            waitFor(cfn, stack, StackStatus.UPDATE_COMPLETE);
            assertEquals(originalOutputs, outputs(cfn, stack));
            assertNull(policy(sqs, first));
            assertEquals(policy(sqs, second), policy(sqs, third));
            assertEquals("After", policy(sqs, second).path("Statement").get(0).path("Sid").asText());

            deleteStack(cfn, stack);
            assertNull(policy(sqs, second));
            assertNull(policy(sqs, third));
        }
    }

    @Test
    void aLaterResourceFailureRestoresPoliciesAndTargetsBeforeStackDeletion() throws Exception {
        try (CloudFormationClient cfn = cfn("us-east-1"); SqsClient sqs = sqs("us-east-1")) {
            String original = queue(sqs, "us-east-1");
            String added = queue(sqs, "us-east-1");
            String previousAddedPolicy = JSON.writeValueAsString(document("External"));
            sqs.setQueueAttributes(request -> request.queueUrl(added)
                    .attributes(Map.of(QueueAttributeName.POLICY, previousAddedPolicy)));
            String stack = stack(cfn, "us-east-1", template(List.of(original), "Before", false, false));
            waitFor(cfn, stack, StackStatus.CREATE_COMPLETE);
            JsonNode before = policy(sqs, original);
            Map<String, String> originalOutputs = outputs(cfn, stack);

            cfn.updateStack(request -> request.stackName(stack)
                    .templateBody(template(List.of(added), "After", false, true)));
            waitFor(cfn, stack, StackStatus.UPDATE_ROLLBACK_COMPLETE);
            assertTrue(cfn.describeStackEvents(request -> request.stackName(stack)).stackEvents().stream()
                    .anyMatch(event -> "Policy".equals(event.logicalResourceId())
                            && "UPDATE_COMPLETE".equals(event.resourceStatusAsString())));
            assertEquals(before, policy(sqs, original));
            assertEquals(JSON.readTree(previousAddedPolicy), policy(sqs, added));
            assertEquals(originalOutputs, outputs(cfn, stack));

            deleteStack(cfn, stack);
            assertNull(policy(sqs, original));
            assertEquals(JSON.readTree(previousAddedPolicy), policy(sqs, added));
        }
    }

    @Test
    void policyReferencesResolveAfterTheQueueIsCreated() throws Exception {
        try (CloudFormationClient cfn = cfn("us-east-1"); SqsClient sqs = sqs("us-east-1")) {
            String stack = stack(cfn, "us-east-1", """
                    {"Resources":{
                      "Queue":{"Type":"AWS::SQS::Queue"},
                      "Policy":{"Type":"AWS::SQS::QueuePolicy","Properties":{
                        "Queues":[{"Ref":"Queue"}],
                        "PolicyDocument":{"Version":"2012-10-17","Statement":[{
                          "Effect":"Allow","Action":"SQS:SendMessage","Principal":{"Service":"sns.amazonaws.com"},
                          "Resource":{"Fn::GetAtt":["Queue","Arn"]}}]}}}},
                     "Outputs":{"QueueUrl":{"Value":{"Ref":"Queue"}}}}
                    """);
            waitFor(cfn, stack, StackStatus.CREATE_COMPLETE);
            String url = outputs(cfn, stack).get("QueueUrl");
            String arn = sqs.getQueueAttributes(request -> request.queueUrl(url)
                    .attributeNames(QueueAttributeName.QUEUE_ARN)).attributes().get(QueueAttributeName.QUEUE_ARN);
            assertEquals(arn, policy(sqs, url).path("Statement").get(0).path("Resource").asText());
            deleteStack(cfn, stack);
            assertThrows(QueueDoesNotExistException.class, () -> sqs.getQueueAttributes(request ->
                    request.queueUrl(url).attributeNames(QueueAttributeName.QUEUE_ARN)));
        }
    }

    private static String template(List<String> queues, String sid, boolean serialized, boolean fail) {
        try {
            ObjectNode document = JSON.valueToTree(document(sid));
            Object policy;
            if (serialized) {
                policy = Map.of("Fn::Sub", JSON.writeValueAsString(document));
            } else {
                ((ObjectNode) document.path("Statement").get(0))
                        .putObject("Resource").put("Fn::Sub",
                                "arn:${AWS::Partition}:sqs:${AWS::Region}:${AWS::AccountId}:*");
                policy = document;
            }
            Map<String, Object> resources = new LinkedHashMap<>();
            resources.put("Policy", Map.of("Type", "AWS::SQS::QueuePolicy",
                    "Properties", Map.of("Queues", queues, "PolicyDocument", policy)));
            if (fail) {
                resources.put("Failure", Map.of("Type", "AWS::SecretsManager::Secret", "DependsOn", "Policy",
                        "Properties", Map.of("SecretString", "explicit",
                                "GenerateSecretString", Map.of("PasswordLength", 32))));
            }
            return JSON.writeValueAsString(Map.of("Resources", resources, "Outputs", Map.of(
                    "Reference", Map.of("Value", Map.of("Ref", "Policy")),
                    "Id", Map.of("Value", Map.of("Fn::GetAtt", List.of("Policy", "Id"))))));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> document(String sid) {
        Map<String, Object> conditions = Map.of(
                "ArnLike", Map.of("aws:SourceArn", "arn:*:sns:*:*:inventory-*"),
                "ForAnyValue:StringEquals", Map.of("aws:SourceAccount", List.of("111122223333", "444455556666")));
        Map<String, Object> statement = Map.of(
                "Sid", sid, "Effect", "Allow",
                "Principal", Map.of("Service", List.of("sns.amazonaws.com", "sns.ap-southeast-7.amazonaws.com")),
                "Action", "SQS:SendMessage",
                "Resource", "arn:${AWS::Partition}:sqs:${AWS::Region}:${AWS::AccountId}:*",
                "Condition", conditions);
        return Map.of("Version", "2012-10-17", "Statement", List.of(statement));
    }

    private static JsonNode policy(SqsClient sqs, String queue) throws Exception {
        String policy = sqs.getQueueAttributes(request -> request.queueUrl(queue)
                .attributeNames(QueueAttributeName.POLICY)).attributes().get(QueueAttributeName.POLICY);
        return policy == null || policy.isEmpty() ? null : JSON.readTree(policy);
    }

    private String queue(SqsClient sqs, String region) {
        String url = sqs.createQueue(request -> request.queueName("cfn-policy-" + UUID.randomUUID())).queueUrl();
        cleanup.register(() -> {
            try (SqsClient owner = sqs(region)) {
                owner.deleteQueue(request -> request.queueUrl(url));
            }
        });
        return url;
    }

    private String stack(CloudFormationClient cfn, String region, String template) {
        String name = "sqs-policy-" + UUID.randomUUID();
        cfn.createStack(request -> request.stackName(name).templateBody(template));
        cleanup.register(() -> {
            try (CloudFormationClient owner = cfn(region)) {
                deleteStack(owner, name);
            }
        });
        return name;
    }

    private static void waitFor(CloudFormationClient cfn, String name, StackStatus status) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertEquals(status, cfn.describeStacks(request -> request.stackName(name)).stacks().getFirst().stackStatus()));
    }

    private static void deleteStack(CloudFormationClient cfn, String name) {
        if (stackDeleted(cfn, name)) {
            return;
        }
        cfn.deleteStack(request -> request.stackName(name));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                .until(() -> stackDeleted(cfn, name));
    }

    private static boolean stackDeleted(CloudFormationClient cfn, String name) {
        try {
            return cfn.describeStacks(request -> request.stackName(name)).stacks().getFirst()
                    .stackStatus() == StackStatus.DELETE_COMPLETE;
        } catch (CloudFormationException expected) {
            if ("ValidationError".equals(expected.awsErrorDetails().errorCode())
                    && expected.awsErrorDetails().errorMessage().contains("does not exist")) {
                return true;
            }
            throw expected;
        }
    }

    private static Map<String, String> outputs(CloudFormationClient cfn, String name) {
        return cfn.describeStacks(request -> request.stackName(name)).stacks().getFirst().outputs().stream()
                .collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private CloudFormationClient cfn(String region) {
        return CloudFormationClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("000000000000", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private SqsClient sqs(String region) {
        return SqsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("000000000000", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
