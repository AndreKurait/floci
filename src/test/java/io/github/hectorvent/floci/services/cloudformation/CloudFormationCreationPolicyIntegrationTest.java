package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(CloudFormationWaitConditionHandleIntegrationTest.StrictResources.class)
class CloudFormationCreationPolicyIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String stack = "creation-signals-" + UUID.randomUUID();
    private String account;
    private CloudFormationClient cfn;
    private Ec2Client ec2;
    private AutoScalingClient scaling;
    private S3Client s3;

    @BeforeEach
    void clients() {
        account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
        ec2 = AwsTestClients.create(Ec2Client.builder(), account, Region.US_EAST_1);
        scaling = AwsTestClients.create(AutoScalingClient.builder(), account, Region.US_EAST_1);
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), account, Region.US_EAST_1);
    }

    @AfterEach
    void cleanup() {
        try {
            if (cfn.describeStacks().stacks().stream().noneMatch(value -> stack.equals(value.stackName()))) {
                return;
            }
            if ("CREATE_IN_PROGRESS".equals(status()) || "UPDATE_IN_PROGRESS".equals(status())) {
                signal(cfn, stack, "Compute", "cleanup", "FAILURE");
                await().atMost(Duration.ofSeconds(10)).until(() -> !status().endsWith("_IN_PROGRESS"));
            }
            cfn.deleteStack(request -> request.stackName(stack));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertFalse(cfn.describeStacks().stacks().stream()
                            .anyMatch(value -> stack.equals(value.stackName()))));
            assertTrue(s3.listBuckets().buckets().isEmpty());
            assertTrue(scaling.describeAutoScalingGroups().autoScalingGroups().isEmpty());
            assertTrue(ec2.describeInstances().reservations().stream().flatMap(value -> value.instances().stream())
                    .allMatch(instance -> "terminated".equals(instance.state().nameAsString())));
        } finally {
            s3.close();
            scaling.close();
            ec2.close();
            cfn.close();
        }
    }

    @Test
    void groupPercentageWaitsForDistinctInstanceSignalsBeforeCreatingDependants() throws Exception {
        create(groupTemplate());
        String group = physicalId();
        List<String> instances = scaling.describeAutoScalingGroups(request -> request.autoScalingGroupNames(group))
                .autoScalingGroups().getFirst().instances().stream().map(value -> value.instanceId()).toList();
        assertEquals(3, instances.size());
        assertPending();
        signal(cfn, stack, "Compute", instances.getFirst(), "SUCCESS");
        signal(cfn, stack, "Compute", instances.getFirst(), "SUCCESS");
        assertPending();
        assertEquals(1, signalEvents().size(), "A retransmitted success is not a second instance");
        String stackId = cfn.describeStacks(request -> request.stackName(stack)).stacks().getFirst().stackId();
        signal(cfn, stackId, "Compute", instances.get(1), "SUCCESS");
        awaitStatus("CREATE_COMPLETE");
        assertEquals(1, s3.listBuckets().buckets().size());
        assertEquals(2, signalEvents().size());
        StackEvent completion = events().stream().filter(event -> "Compute".equals(event.logicalResourceId())
                && "CREATE_COMPLETE".equals(event.resourceStatusAsString())).findFirst().orElseThrow();
        assertTrue(signalEvents().stream().allMatch(event -> !event.timestamp().isAfter(completion.timestamp())));
    }

    @Test
    void omittedDesiredCapacityUsesMinSizeAndPreservesLaterScalingOnUpdate() throws Exception {
        ObjectNode template = groupTemplate();
        template.withObject("/Resources/Compute/Properties").remove("DesiredCapacity");
        template.withObject("/Resources/Compute/Properties").put("MaxSize", 4);
        create(template);
        String group = physicalId();
        assertEquals(3, scaling.describeAutoScalingGroups(request -> request.autoScalingGroupNames(group))
                .autoScalingGroups().getFirst().desiredCapacity());
        List<String> instances = scaling.describeAutoScalingGroups(request -> request.autoScalingGroupNames(group))
                .autoScalingGroups().getFirst().instances().stream().map(value -> value.instanceId()).toList();
        assertEquals(3, instances.size());
        signal(cfn, stack, "Compute", instances.getFirst(), "SUCCESS");
        signal(cfn, stack, "Compute", instances.get(1), "SUCCESS");
        awaitStatus("CREATE_COMPLETE");
        scaling.setDesiredCapacity(request -> request.autoScalingGroupName(group).desiredCapacity(4));
        template.withObject("/Resources/Compute/Properties").put("Cooldown", 600);
        cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
        awaitStatus("UPDATE_COMPLETE");
        assertEquals(group, physicalId());
        assertEquals(4, scaling.describeAutoScalingGroups(request -> request.autoScalingGroupNames(group))
                .autoScalingGroups().getFirst().desiredCapacity());
        assertEquals(2, signalEvents().size(), "An in-place group update does not require creation signals");
    }

    @Test
    void failedSignalRollsBackTheInstanceAndNeverCreatesTheDependant() throws Exception {
        create(instanceTemplate("ami-12345678", "PT30S"));
        String instance = physicalId();
        signal(cfn, stack, "Compute", instance, "FAILURE");
        awaitStatus("ROLLBACK_COMPLETE");
        assertTrue(events().stream().anyMatch(event -> "CREATE_FAILED".equals(event.resourceStatusAsString())
                && event.resourceStatusReason().contains("FAILURE")));
        assertTrue(s3.listBuckets().buckets().isEmpty());
        assertTerminated(instance);
    }

    @Test
    void failedGroupSignalRollsBackItsInstancesAndLaunchConfiguration() throws Exception {
        create(groupTemplate());
        String group = physicalId();
        List<String> instances = scaling.describeAutoScalingGroups(request -> request.autoScalingGroupNames(group))
                .autoScalingGroups().getFirst().instances().stream().map(value -> value.instanceId()).toList();
        signal(cfn, stack, "Compute", instances.getFirst(), "FAILURE");
        awaitStatus("ROLLBACK_COMPLETE");
        assertTrue(scaling.describeAutoScalingGroups().autoScalingGroups().isEmpty());
        assertTrue(scaling.describeLaunchConfigurations().launchConfigurations().isEmpty());
        instances.forEach(this::assertTerminated);
        assertTrue(s3.listBuckets().buckets().isEmpty());
    }

    @Test
    void disablingRollbackKeepsTheFailedInstanceUnderStackDeletionOwnership() throws Exception {
        ObjectNode template = instanceTemplate("ami-12345678", "PT30S");
        cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()).disableRollback(true));
        String instance = physicalId();
        signal(cfn, stack, "Compute", instance, "FAILURE");
        awaitStatus("CREATE_FAILED");
        assertEquals("running", ec2.describeInstances(request -> request.instanceIds(instance))
                .reservations().getFirst().instances().getFirst().state().nameAsString());
        assertTrue(s3.listBuckets().buckets().isEmpty());
    }

    @Test
    void unsignaledInstanceUsesThePolicyDeadlineAndRollsBack() throws Exception {
        long started = System.nanoTime();
        create(instanceTemplate("ami-12345678", "PT1S"));
        awaitStatus("ROLLBACK_COMPLETE");
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) >= 0);
        assertTrue(events().stream().anyMatch(event -> "CREATE_FAILED".equals(event.resourceStatusAsString())
                && event.resourceStatusReason().contains("received 0 of 1")));
        assertTrue(s3.listBuckets().buckets().isEmpty());
    }

    @Test
    void signalsAreScopedToTheStackAccountRegionAndLogicalResource() throws Exception {
        create(instanceTemplate("ami-12345678", "PT30S"));
        String instance = physicalId();
        String foreignAccount = "000000000000".equals(account) ? "111111111111" : "000000000000";
        try (CloudFormationClient otherAccount = AwsTestClients.create(
                CloudFormationClient.builder(), foreignAccount, Region.US_EAST_1);
             CloudFormationClient otherRegion = AwsTestClients.create(
                     CloudFormationClient.builder(), account, Region.US_WEST_2)) {
            assertThrows(CloudFormationException.class, () ->
                    signal(otherAccount, stack, "Compute", instance, "SUCCESS"));
            assertThrows(CloudFormationException.class, () ->
                    signal(otherRegion, stack, "Compute", instance, "SUCCESS"));
        }
        assertThrows(CloudFormationException.class, () ->
                signal(cfn, stack, "Unknown", instance, "SUCCESS"));
        assertThrows(CloudFormationException.class, () ->
                signal(cfn, stack, "Compute", "x".repeat(65), "SUCCESS"));
        assertPending();
        assertTrue(signalEvents().isEmpty());
        signal(cfn, stack, "Compute", instance, "SUCCESS");
        awaitStatus("CREATE_COMPLETE");
    }

    @Test
    void tagUpdateKeepsTheInstanceWithoutRequiringAnotherCreationSignal() throws Exception {
        ObjectNode template = instanceTemplate("ami-12345678", "PT30S");
        create(template);
        String instance = physicalId();
        signal(cfn, stack, "Compute", instance, "SUCCESS");
        awaitStatus("CREATE_COMPLETE");
        template.withObject("/Resources/Compute/Properties").putArray("Tags")
                .addObject().put("Key", "stage").put("Value", "updated");
        cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
        awaitStatus("UPDATE_COMPLETE");
        assertEquals(instance, physicalId());
        assertEquals(1, signalEvents().size());
        assertTrue(ec2.describeInstances(request -> request.instanceIds(instance)).reservations().getFirst()
                .instances().getFirst().tags().stream()
                .anyMatch(tag -> "stage".equals(tag.key()) && "updated".equals(tag.value())));
    }

    @Test
    void replacementRequiresAFreshSignalBeforeRetiringTheOriginalInstance() throws Exception {
        ObjectNode template = instanceTemplate("ami-12345678", "PT30S");
        create(template);
        String original = physicalId();
        signal(cfn, stack, "Compute", original, "SUCCESS");
        awaitStatus("CREATE_COMPLETE");
        template.withObject("/Resources/Compute/Properties").put("ImageId", "ami-87654321");
        cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
        awaitStatus("UPDATE_IN_PROGRESS");
        String replacement = physicalId();
        assertNotEquals(original, replacement);
        assertEquals("running", ec2.describeInstances(request -> request.instanceIds(original))
                .reservations().getFirst().instances().getFirst().state().nameAsString());
        signal(cfn, stack, "Compute", replacement, "SUCCESS");
        awaitStatus("UPDATE_COMPLETE");
        assertEquals(replacement, physicalId());
        assertTerminated(original);
        assertTrue(signalEvents().stream().anyMatch(event -> "UPDATE_IN_PROGRESS".equals(
                event.resourceStatusAsString()) && event.resourceStatusReason().contains(replacement)));
    }

    @Test
    void failedReplacementRestoresTheOriginalInstance() throws Exception {
        ObjectNode template = instanceTemplate("ami-12345678", "PT30S");
        create(template);
        String original = physicalId();
        signal(cfn, stack, "Compute", original, "SUCCESS");
        awaitStatus("CREATE_COMPLETE");
        template.withObject("/Resources/Compute/Properties").put("ImageId", "ami-87654321");
        cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
        awaitStatus("UPDATE_IN_PROGRESS");
        String replacement = physicalId();
        assertNotEquals(original, replacement);
        signal(cfn, stack, "Compute", replacement, "FAILURE");
        awaitStatus("UPDATE_ROLLBACK_COMPLETE");
        assertEquals(original, physicalId());
        assertEquals("running", ec2.describeInstances(request -> request.instanceIds(original))
                .reservations().getFirst().instances().getFirst().state().nameAsString());
        assertTerminated(replacement);
    }

    private ObjectNode instanceTemplate(String imageId, String timeout) throws Exception {
        ObjectNode template = JSON.readValue("""
                {"Resources":{
                  "Compute":{"Type":"AWS::EC2::Instance","CreationPolicy":{"ResourceSignal":{"Timeout":"PT30S"}},
                    "Properties":{"ImageId":"ami-12345678","InstanceType":"t3.micro"}},
                  "Dependent":{"Type":"AWS::S3::Bucket","DependsOn":"Compute"}
                }}
                """, ObjectNode.class);
        template.withObject("/Resources/Compute/CreationPolicy/ResourceSignal").put("Timeout", timeout);
        template.withObject("/Resources/Compute/Properties").put("ImageId", imageId);
        return template;
    }

    private ObjectNode groupTemplate() throws Exception {
        return JSON.readValue("""
                {"Resources":{
                  "Launch":{"Type":"AWS::AutoScaling::LaunchConfiguration","Properties":{
                    "ImageId":"ami-12345678","InstanceType":"t3.micro"}},
                  "Compute":{"Type":"AWS::AutoScaling::AutoScalingGroup",
                    "CreationPolicy":{"AutoScalingCreationPolicy":{"MinSuccessfulInstancesPercent":50},
                      "ResourceSignal":{"Timeout":"PT30S"}},
                    "Properties":{"LaunchConfigurationName":{"Ref":"Launch"},
                      "MinSize":3,"MaxSize":3,"DesiredCapacity":3,"AvailabilityZones":["us-east-1a"]}},
                  "Dependent":{"Type":"AWS::S3::Bucket","DependsOn":"Compute"}
                }}
                """, ObjectNode.class);
    }

    private void create(ObjectNode template) {
        cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()));
    }

    private void assertPending() {
        assertEquals("CREATE_IN_PROGRESS", status());
        StackResource resource = cfn.describeStackResources(request -> request.stackName(stack)).stackResources()
                .stream().filter(value -> "Compute".equals(value.logicalResourceId())).findFirst().orElseThrow();
        assertEquals("CREATE_IN_PROGRESS", resource.resourceStatusAsString());
        assertTrue(s3.listBuckets().buckets().isEmpty());
    }

    private void assertTerminated(String instance) {
        assertEquals("terminated", ec2.describeInstances(request -> request.instanceIds(instance))
                .reservations().getFirst().instances().getFirst().state().nameAsString());
    }

    private String physicalId() {
        return cfn.describeStackResources(request -> request.stackName(stack)).stackResources().stream()
                .filter(value -> "Compute".equals(value.logicalResourceId())).findFirst().orElseThrow()
                .physicalResourceId();
    }

    private String status() {
        return cfn.describeStacks(request -> request.stackName(stack)).stacks().getFirst().stackStatusAsString();
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals(expected, status()));
    }

    private List<StackEvent> events() {
        return cfn.describeStackEvents(request -> request.stackName(stack)).stackEvents();
    }

    private List<StackEvent> signalEvents() {
        return events().stream().filter(event -> "Compute".equals(event.logicalResourceId())
                && event.resourceStatusReason() != null
                && event.resourceStatusReason().startsWith("Received ")).toList();
    }

    private static void signal(CloudFormationClient client, String stackName, String logicalId,
                               String uniqueId, String status) {
        client.signalResource(request -> request.stackName(stackName).logicalResourceId(logicalId)
                .uniqueId(uniqueId).status(status));
    }
}
