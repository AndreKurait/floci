package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(CloudFormationWaitConditionHandleIntegrationTest.StrictResources.class)
class CloudFormationWaitConditionHandleIntegrationTest {
    public static class StrictResources implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.cloudformation.allow-stub-unsupported-resource-types", "false");
        }
    }

    @InjectMock
    ContainerReachableEndpoint reachableEndpoint;
    @TestHTTPResource("/")
    URI endpoint;
    private final String stack = "wait-handle-" + UUID.randomUUID();
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
                        () -> s3.headBucket(request -> request.bucket(bucket(handle)))).statusCode(),
                        "Deleting the stack must retire every backing signal bucket");
            }
        } finally {
            http.close();
            s3.close();
            cfn.close();
        }
    }

    @Test
    void referenceAndIdAreUsableSignedS3UrlsWithVersionedSignals() throws Exception {
        create(template());
        Map<String, String> outputs = outputs();
        URI handle = remember(outputs.get("Handle"));
        assertEquals(handle.toString(), outputs.get("Id"));
        assertTrue(handle.getQuery().contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"));
        assertTrue(handle.getQuery().contains("X-Amz-Security-Token="));
        assertTrue(s3.listBuckets().buckets().isEmpty(), "Service storage is not a customer bucket");
        assertEquals(200, signal(handle, "first"));
        assertEquals(200, signal(handle, "second"));
        assertEquals(2, s3.listObjectVersions(request -> request.bucket(bucket(handle))).versions().size());
        assertTrue(s3.getObjectAsBytes(request -> request.bucket(bucket(handle)).key("signals"))
                .asUtf8String().contains("second"));
        assertEquals(403, http.send(HttpRequest.newBuilder(handle).timeout(Duration.ofSeconds(3))
                .GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(403, signal(URI.create(handle.toString().replace("/signals?", "/other?")), "tampered"));
    }

    @Test
    void stackUpdatesKeepTheOriginalHandleAndItsSignals() throws Exception {
        create(template());
        URI handle = remember(outputs().get("Handle"));
        assertEquals(200, signal(handle, "before"));
        ObjectNode changed = template();
        changed.withObject("/Resources").putObject("Bucket").put("Type", "AWS::S3::Bucket");
        cfn.updateStack(request -> request.stackName(stack).templateBody(changed.toString()));
        awaitStatus("UPDATE_COMPLETE");
        assertEquals(handle.toString(), outputs().get("Handle"));
        assertEquals(200, signal(handle, "after"));
        assertEquals(2, s3.listObjectVersions(request -> request.bucket(bucket(handle))).versions().size());
    }

    @Test
    void independentHandlesDoNotShareSignalsOrDeletionState() throws Exception {
        ObjectNode template = template();
        template.withObject("/Resources").putObject("Other").put("Type", "AWS::CloudFormation::WaitConditionHandle");
        template.withObject("/Outputs").putObject("Other").putObject("Value").put("Ref", "Other");
        create(template);
        URI first = remember(outputs().get("Handle"));
        URI second = remember(outputs().get("Other"));
        assertNotEquals(first, second);
        assertEquals(200, signal(first, "first"));
        assertEquals(200, signal(second, "second"));
        assertEquals(1, s3.listObjectVersions(request -> request.bucket(bucket(first))).versions().size());
        assertEquals(1, s3.listObjectVersions(request -> request.bucket(bucket(second))).versions().size());
        assertTrue(s3.listBuckets().buckets().isEmpty());
    }

    @Test
    void unsignaledWaitConditionTimesOutAndRollsBackItsRealHandle() throws Exception {
        ObjectNode template = template();
        ObjectNode wait = template.withObject("/Resources").putObject("Wait");
        wait.put("Type", "AWS::CloudFormation::WaitCondition").put("DependsOn", "Handle");
        wait.putObject("Properties").put("Timeout", "1").putObject("Handle").put("Ref", "Handle");
        cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()));
        awaitStatus("ROLLBACK_COMPLETE");
        List<StackEvent> events = cfn.describeStackEvents(request -> request.stackName(stack)).stackEvents();
        URI handle = remember(events.stream().filter(event -> "Handle".equals(event.logicalResourceId())
                        && "CREATE_COMPLETE".equals(event.resourceStatusAsString()))
                .map(StackEvent::physicalResourceId).findFirst().orElseThrow());
        assertTrue(events.stream().anyMatch(event -> "Wait".equals(event.logicalResourceId())
                && "CREATE_FAILED".equals(event.resourceStatusAsString())
                && event.resourceStatusReason().contains("timed out")));
        assertEquals(404, signal(handle, "after-rollback"));
    }

    private URI remember(String url) {
        URI handle = URI.create(url);
        handles.add(handle);
        return handle;
    }

    private int signal(URI url, String id) throws Exception {
        String json = "{\"Status\":\"SUCCESS\",\"UniqueId\":\"" + id
                + "\",\"Data\":\"" + id + "\",\"Reason\":\"configured\"}";
        return http.send(HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(3))
                        .PUT(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String bucket(URI handle) {
        return handle.getPath().split("/")[1];
    }

    private void create(ObjectNode template) {
        cfn.createStack(request -> request.stackName(stack).templateBody(template.toString()));
        awaitStatus("CREATE_COMPLETE");
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(10)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(10))
                .untilAsserted(() ->
                assertEquals(expected, cfn.describeStacks(request -> request.stackName(stack))
                        .stacks().getFirst().stackStatusAsString()));
    }

    private Map<String, String> outputs() {
        return cfn.describeStacks(request -> request.stackName(stack)).stacks().getFirst().outputs()
                .stream().collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private static ObjectNode template() {
        ObjectNode template = new ObjectMapper().createObjectNode();
        template.putObject("Resources").putObject("Handle").put("Type", "AWS::CloudFormation::WaitConditionHandle");
        ObjectNode outputs = template.putObject("Outputs");
        outputs.putObject("Handle").putObject("Value").put("Ref", "Handle");
        outputs.putObject("Id").putObject("Value").putArray("Fn::GetAtt").add("Handle").add("Id");
        return template;
    }
}
