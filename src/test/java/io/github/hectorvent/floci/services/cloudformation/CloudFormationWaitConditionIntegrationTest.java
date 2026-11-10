package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(CloudFormationWaitConditionHandleIntegrationTest.StrictResources.class)
class CloudFormationWaitConditionIntegrationTest {
    @InjectMock
    ContainerReachableEndpoint reachableEndpoint;
    @TestHTTPResource("/")
    URI endpoint;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String stack = "wait-signals-" + UUID.randomUUID();
    private final List<URI> handles = new ArrayList<>();
    private CloudFormationClient cfn;
    private S3Client s3;
    private HttpClient http;

    @BeforeEach
    void clients() {
        when(reachableEndpoint.baseUrl(false)).thenReturn(endpoint.toString());
        String account = String.format(Locale.ROOT, "%012d",
                ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        cfn = AwsTestClients.create(CloudFormationClient.builder(), account, Region.US_EAST_1);
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), account, Region.US_EAST_1);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void cleanup() {
        try {
            if (cfn.describeStacks().stacks().stream().anyMatch(value -> stack.equals(value.stackName()))) {
                cfn.deleteStack(request -> request.stackName(stack));
                await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                        .untilAsserted(() ->
                        assertFalse(cfn.describeStacks().stacks().stream()
                                .anyMatch(value -> stack.equals(value.stackName()))));
            }
            for (URI handle : handles) {
                assertEquals(404, assertThrows(S3Exception.class,
                        () -> s3.headBucket(request -> request.bucket(handle.getPath().split("/")[1])))
                        .statusCode(), "Normal stack deletion must retire all signal versions and buckets");
            }
        } finally {
            http.close();
            s3.close();
            cfn.close();
        }
    }

    @Test
    void countZeroReleasesDependentResourcesAndReturnsEmptyData() throws Exception {
        create(template(0));
        awaitStatus("CREATE_COMPLETE");
        handle("Handle");
        Map<String, String> outputs = outputs();
        assertEquals(outputs.get("Reference"), outputs.get("Id"));
        assertEquals(mapper.createObjectNode(), mapper.readTree(outputs.get("Data")));
        assertEquals(1, s3.listBuckets().buckets().size());
        assertTrue(cfn.describeStackResources(request -> request.stackName(stack)).stackResources()
                .stream().anyMatch(resource -> "AfterWait".equals(resource.logicalResourceId())
                        && "CREATE_COMPLETE".equals(resource.resourceStatusAsString())));
    }

    @Test
    void uniqueSignalsGateDependenciesAndIgnoreRetransmittedDataOrFailure() throws Exception {
        create(template(2));
        URI handle = handle("Handle");
        assertTrue(s3.listBuckets().buckets().isEmpty());
        put(handle, "not JSON");
        put(handle, signal("SUCCESS", "first", "original"));
        put(handle, signal("SUCCESS", "first", "updated"));
        put(handle, signal("FAILURE", "first", "retransmitted"));
        assertEquals("CREATE_IN_PROGRESS", status());
        assertTrue(s3.listBuckets().buckets().isEmpty(), "Duplicate IDs cannot release the dependent bucket");
        put(handle, signal("SUCCESS", "second", "second value"));
        awaitStatus("CREATE_COMPLETE");
        assertEquals(mapper.valueToTree(Map.of("first", "original", "second", "second value")),
                mapper.readTree(outputs().get("Data")));
        assertEquals(1, s3.listBuckets().buckets().size());
    }

    @Test
    void failureSignalFailsTheResourceWithItsReason() throws Exception {
        create(template(2));
        URI handle = handle("Handle");
        put(handle, signal("SUCCESS", "first", "ready"));
        ObjectNode failed = signal("FAILURE", "second", "not ready");
        failed.put("Reason", "provider could not start");
        put(handle, failed);
        awaitStatus("CREATE_FAILED");
        assertTrue(cfn.describeStackEvents(request -> request.stackName(stack)).stackEvents().stream()
                .anyMatch(event -> "Wait".equals(event.logicalResourceId())
                        && "CREATE_FAILED".equals(event.resourceStatusAsString())
                        && event.resourceStatusReason().contains("provider could not start")));
        assertTrue(s3.listBuckets().buckets().isEmpty());
    }

    @Test
    void anotherHandleCannotSatisfyTheWait() throws Exception {
        ObjectNode template = template(1);
        template.withObject("/Resources").putObject("Other").put("Type", "AWS::CloudFormation::WaitConditionHandle");
        template.withObject("/Resources/Wait").putArray("DependsOn").add("Handle").add("Other");
        create(template);
        URI selected = handle("Handle");
        URI other = handle("Other");
        put(other, signal("SUCCESS", "foreign", "not selected"));
        assertEquals("CREATE_IN_PROGRESS", status());
        assertTrue(s3.listBuckets().buckets().isEmpty());
        put(selected, signal("SUCCESS", "selected", "ready"));
        awaitStatus("CREATE_COMPLETE");
        assertEquals(mapper.valueToTree(Map.of("selected", "ready")), mapper.readTree(outputs().get("Data")));
    }

    @Test
    void unchangedUpdatesKeepIdentityAndDataWithoutRequiringMoreSignals() throws Exception {
        ObjectNode template = template(1);
        create(template);
        URI handle = handle("Handle");
        put(handle, signal("SUCCESS", "ready", "preserved"));
        awaitStatus("CREATE_COMPLETE");
        Map<String, String> before = outputs();
        template.withObject("/Outputs").putObject("Additional").put("Value", "updated output");
        cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
        awaitStatus("UPDATE_COMPLETE");
        assertEquals(before.get("Reference"), outputs().get("Reference"));
        assertEquals(before.get("Data"), outputs().get("Data"));
    }

    @Test
    void changingWaitPropertiesFailsAndRestoresThePriorResult() {
        ObjectNode template = template(0);
        create(template);
        awaitStatus("CREATE_COMPLETE");
        handle("Handle");
        Map<String, String> before = outputs();
        template.withObject("/Resources/Wait/Properties").put("Count", 1);
        cfn.updateStack(request -> request.stackName(stack).templateBody(template.toString()));
        awaitStatus("UPDATE_ROLLBACK_COMPLETE");
        assertEquals(before.get("Reference"), outputs().get("Reference"));
        assertEquals(before.get("Data"), outputs().get("Data"));
        assertTrue(cfn.describeStackEvents(request -> request.stackName(stack)).stackEvents().stream()
                .anyMatch(event -> "Wait".equals(event.logicalResourceId())
                        && event.resourceStatusReason() != null
                        && event.resourceStatusReason().contains("cannot be updated")));
    }

    private void create(ObjectNode template) {
        cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()).disableRollback(true));
    }

    private URI handle(String logicalId) {
        await().atMost(Duration.ofSeconds(5)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() ->
                assertTrue(cfn.describeStackResources(request -> request.stackName(stack)).stackResources().stream()
                        .anyMatch(resource -> logicalId.equals(resource.logicalResourceId())
                                && resource.physicalResourceId() != null
                                && "CREATE_COMPLETE".equals(resource.resourceStatusAsString()))));
        URI handle = URI.create(cfn.describeStackResources(request -> request.stackName(stack)).stackResources().stream()
                .filter(resource -> logicalId.equals(resource.logicalResourceId()))
                .findFirst().orElseThrow().physicalResourceId());
        handles.add(handle);
        return handle;
    }

    private void put(URI handle, ObjectNode signal) throws Exception {
        put(handle, signal.toString());
    }

    private void put(URI handle, String signal) throws Exception {
        assertEquals(200, http.send(HttpRequest.newBuilder(handle).timeout(Duration.ofSeconds(3))
                        .PUT(HttpRequest.BodyPublishers.ofString(signal)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    private ObjectNode signal(String status, String id, String data) {
        return mapper.createObjectNode().put("Status", status).put("UniqueId", id)
                .put("Data", data).put("Reason", "configured");
    }

    private String status() {
        return cfn.describeStacks(request -> request.stackName(stack)).stacks().getFirst().stackStatusAsString();
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() -> assertEquals(expected, status()));
    }

    private Map<String, String> outputs() {
        return cfn.describeStacks(request -> request.stackName(stack)).stacks().getFirst().outputs()
                .stream().collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private ObjectNode template(int count) {
        ObjectNode template = mapper.createObjectNode();
        ObjectNode resources = template.putObject("Resources");
        resources.putObject("Handle").put("Type", "AWS::CloudFormation::WaitConditionHandle");
        ObjectNode wait = resources.putObject("Wait").put("Type", "AWS::CloudFormation::WaitCondition");
        wait.putObject("Properties").put("Count", count).put("Timeout", "8")
                .putObject("Handle").put("Ref", "Handle");
        resources.putObject("AfterWait").put("Type", "AWS::S3::Bucket").put("DependsOn", "Wait");
        ObjectNode outputs = template.putObject("Outputs");
        outputs.putObject("Reference").putObject("Value").put("Ref", "Wait");
        outputs.putObject("Id").putObject("Value").putArray("Fn::GetAtt").add("Wait").add("Id");
        outputs.putObject("Data").putObject("Value").putArray("Fn::GetAtt").add("Wait").add("Data");
        return template;
    }
}
