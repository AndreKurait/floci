package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.CreateStateMachineResponse;
import software.amazon.awssdk.services.sfn.model.DescribeStateMachineForExecutionResponse;
import software.amazon.awssdk.services.sfn.model.SfnException;
import software.amazon.awssdk.services.sfn.model.UpdateStateMachineResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Independent SDK controls anchored to the saved AWS/current-native whole-response comparison.
 * The transport tape retains unknown JSON fields which the SDK model intentionally discards.
 */
@QuarkusTest
class StepFunctionsExecutionConfigContractIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFINITION =
            "{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"Result\":\"original\",\"End\":true}}}";
    private static final String ACCOUNT = "771661203001";
    private static final Outcome ABSENT = new Outcome(400, "ExecutionDoesNotExist");

    @TestHTTPResource
    URI endpoint;

    @Test
    void initialUnupdatedConfigurationOmitsRevisionId() throws Exception {
        try (Cloud cloud = cloud("initial-revision")) {
            CreateStateMachineResponse created = cloud.create();
            String execution = cloud.start(created.stateMachineArn());
            DescribeStateMachineForExecutionResponse described = cloud.describe(execution);
            JsonNode raw = cloud.raw();
            assertAll(
                    () -> assertNull(described.revisionId()),
                    () -> assertFalse(raw.has("revisionId"), raw.toPrettyString()),
                    () -> assertEquals(created.creationDate(), described.updateDate()));
        }
    }

    @Test
    void rawDefaultConfigurationMatchesTheWholeObservedAwsResponse() throws Exception {
        try (Cloud cloud = cloud("raw-default")) {
            CreateStateMachineResponse created = cloud.create();
            String execution = cloud.start(created.stateMachineArn());
            cloud.describe(execution);
            JsonNode actual = cloud.raw();
            ObjectNode expected = MAPPER.createObjectNode();
            expected.put("stateMachineArn", created.stateMachineArn());
            expected.put("name", cloud.name);
            expected.put("definition", DEFINITION);
            expected.put("roleArn", cloud.role);
            expected.set("updateDate", cloud.createRaw.get("creationDate"));
            expected.set("loggingConfiguration", MAPPER.readTree("""
                    {"__type":"com.amazonaws.swf.base.model#LoggingConfiguration",
                     "level":"OFF","includeExecutionData":false}
                    """));
            expected.set("tracingConfiguration", MAPPER.readTree("""
                    {"__type":"com.amazonaws.swf.base.model#TracingConfiguration","enabled":false}
                    """));
            expected.set("encryptionConfiguration", MAPPER.readTree("""
                    {"__type":"com.amazonaws.swf.base.model#EncryptionConfiguration","type":"AWS_OWNED_KEY"}
                    """));
            assertEquals(expected, actual);
        }
    }

    @Test
    void metadataOnlyChangesOnlyTheDefinitionInTheRawResponse() throws Exception {
        try (Cloud cloud = cloud("metadata")) {
            String execution = cloud.start(cloud.create().stateMachineArn());
            cloud.describe(execution);
            ObjectNode original = (ObjectNode) cloud.raw();
            cloud.sfn.describeStateMachineForExecution(request -> request
                    .executionArn(execution).includedData("ALL_DATA"));
            JsonNode allData = cloud.raw();
            DescribeStateMachineForExecutionResponse metadata = cloud.sfn.describeStateMachineForExecution(
                    request -> request.executionArn(execution).includedData("METADATA_ONLY"));
            JsonNode metadataRaw = cloud.raw();
            ObjectNode expectedMetadata = original.deepCopy().put("definition", "{}");
            assertAll(
                    () -> assertEquals(original, allData),
                    () -> assertEquals("{}", metadata.definition()),
                    () -> assertEquals(expectedMetadata, metadataRaw));
        }
    }

    @Test
    void originalWholeSnapshotSurvivesDefinitionAndRoleUpdates() throws Exception {
        try (Cloud cloud = cloud("snapshot")) {
            String machine = cloud.create().stateMachineArn();
            String execution = cloud.start(machine);
            cloud.describe(execution);
            JsonNode original = cloud.raw();
            cloud.sfn.updateStateMachine(request -> request.stateMachineArn(machine)
                    .definition(DEFINITION.replace("original", "updated")).roleArn(cloud.role + "-updated"));
            cloud.describe(execution);
            assertEquals(original, cloud.raw());
        }
    }

    @Test
    void updatedVersionUsesTheUpdateRevisionDateAndBaseArn() throws Exception {
        try (Cloud cloud = cloud("updated-version")) {
            String machine = cloud.create().stateMachineArn();
            String updatedDefinition = DEFINITION.replace("original", "updated");
            UpdateStateMachineResponse updated = cloud.sfn.updateStateMachine(request -> request
                    .stateMachineArn(machine).definition(updatedDefinition).publish(true));
            String execution = cloud.start(updated.stateMachineVersionArn());
            cloud.sfn.updateStateMachine(request -> request.stateMachineArn(machine)
                    .definition(DEFINITION.replace("original", "later")));
            DescribeStateMachineForExecutionResponse described = cloud.describe(execution);
            assertAll(
                    () -> assertEquals(machine, described.stateMachineArn()),
                    () -> assertEquals(updated.revisionId(), described.revisionId()),
                    () -> assertEquals(updated.updateDate(), described.updateDate()),
                    () -> assertEquals(updatedDefinition, described.definition()));
        }
    }

    @Test
    void laterVersionPublicationRetainsTheConfigurationUpdateDate() throws Exception {
        try (Cloud cloud = cloud("later-publication")) {
            String machine = cloud.create().stateMachineArn();
            UpdateStateMachineResponse updated = cloud.sfn.updateStateMachine(request -> request
                    .stateMachineArn(machine).definition(DEFINITION.replace("original", "updated")));
            await().atMost(Duration.ofSeconds(3)).pollInterval(Duration.ofMillis(10))
                    .until(() -> System.currentTimeMillis() > updated.updateDate().toEpochMilli() + 25);
            String version = cloud.sfn.publishStateMachineVersion(request -> request
                    .stateMachineArn(machine).revisionId(updated.revisionId())).stateMachineVersionArn();
            String execution = cloud.start(version);
            DescribeStateMachineForExecutionResponse described = cloud.describe(execution);
            assertAll(
                    () -> assertEquals(updated.updateDate(), described.updateDate()),
                    () -> assertEquals(updated.revisionId(), described.revisionId()));
        }
    }

    @Test
    void describeExecutionRefusesCompletedExecutionAfterConfirmedMachineDeletion() throws Exception {
        try (Cloud cloud = cloud("deleted-execution")) {
            String machine = cloud.create().stateMachineArn();
            String execution = cloud.start(machine);
            cloud.completed(execution);
            cloud.deleteAndConfirm(machine);
            Outcome actual = cloud.observe("after-delete", () -> cloud.sfn.describeExecution(request ->
                    request.executionArn(execution)));
            assertEquals(ABSENT, actual);
        }
    }

    @Test
    void describeConfigurationRefusesCompletedExecutionAfterConfirmedMachineDeletion() throws Exception {
        try (Cloud cloud = cloud("deleted-config")) {
            String machine = cloud.create().stateMachineArn();
            String execution = cloud.start(machine);
            cloud.completed(execution);
            cloud.deleteAndConfirm(machine);
            Outcome actual = cloud.observe("after-delete", () -> cloud.describe(execution));
            assertEquals(ABSENT, actual);
        }
    }

    @Test
    void recreatedMachineDoesNotRestoreOldExecutionVisibility() throws Exception {
        try (Cloud cloud = cloud("recreated-execution")) {
            String machine = cloud.create().stateMachineArn();
            String oldExecution = cloud.start(machine);
            cloud.completed(oldExecution);
            cloud.deleteAndConfirm(machine);
            Outcome afterDelete = cloud.observe("old-after-delete", () -> cloud.sfn.describeExecution(request ->
                    request.executionArn(oldExecution)));
            String replacement = cloud.create().stateMachineArn();
            String newExecution = cloud.start(replacement);
            cloud.completed(newExecution);
            Outcome afterRecreate = cloud.observe("old-after-recreate", () -> cloud.sfn.describeExecution(request ->
                    request.executionArn(oldExecution)));
            Outcome replacementRead = cloud.observe("replacement-read", () -> cloud.sfn.describeExecution(request ->
                    request.executionArn(newExecution)));
            assertAll(
                    () -> assertEquals(machine, replacement),
                    () -> assertEquals(ABSENT, afterDelete),
                    () -> assertEquals(ABSENT, afterRecreate),
                    () -> assertEquals(new Outcome(200, ""), replacementRead));
        }
    }

    @Test
    void recreatedMachineDoesNotRestoreOldConfigurationVisibility() throws Exception {
        try (Cloud cloud = cloud("recreated-config")) {
            String machine = cloud.create().stateMachineArn();
            String oldExecution = cloud.start(machine);
            cloud.completed(oldExecution);
            cloud.deleteAndConfirm(machine);
            Outcome afterDelete = cloud.observe("old-after-delete", () -> cloud.describe(oldExecution));
            String replacement = cloud.create().stateMachineArn();
            String newExecution = cloud.start(replacement);
            cloud.completed(newExecution);
            Outcome afterRecreate = cloud.observe("old-after-recreate", () -> cloud.describe(oldExecution));
            Outcome replacementRead = cloud.observe("replacement-read", () -> cloud.describe(newExecution));
            assertAll(
                    () -> assertEquals(machine, replacement),
                    () -> assertEquals(ABSENT, afterDelete),
                    () -> assertEquals(ABSENT, afterRecreate),
                    () -> assertEquals(new Outcome(200, ""), replacementRead));
        }
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "cn-north-1,aws-cn"})
    void configurationBelongsToTheSignedAccountAndPartition(String region, String partition) throws Exception {
        try (Cloud owner = new Cloud(endpoint, ACCOUNT, region, partition, "ownership");
             Cloud foreign = new Cloud(endpoint, "771661203002", region, partition, "foreign")) {
            String machine = owner.create().stateMachineArn();
            String execution = owner.start(machine);
            DescribeStateMachineForExecutionResponse described = owner.describe(execution);
            Outcome refused = foreign.observe("foreign-read", () -> foreign.describe(execution));
            assertAll(
                    () -> assertEquals(machine, described.stateMachineArn()),
                    () -> assertEquals(owner.role, described.roleArn()),
                    () -> assertEquals(ABSENT, refused));
        }
    }

    @Test
    void configurationRefusesAnExecutionFromAnotherSigningRegion() throws Exception {
        try (Cloud owner = cloud("region-owner");
             Cloud foreign = new Cloud(endpoint, ACCOUNT, "us-west-2", "aws", "region-foreign")) {
            String execution = owner.start(owner.create().stateMachineArn());
            Outcome refused = foreign.observe("foreign-region-read", () -> foreign.describe(execution));
            assertEquals(new Outcome(400, "InvalidArn"), refused);
        }
    }

    private Cloud cloud(String label) {
        return new Cloud(endpoint, ACCOUNT, "us-east-1", "aws", label);
    }

    private record Outcome(int status, String error) {
    }

    private record WireCall(String operation, String request, int status,
                            Map<String, List<String>> responseHeaders, String response) {
    }

    private static final class Cloud implements AutoCloseable {
        private final String account;
        private final String region;
        private final String label;
        private final String name = "execution-contract-" + UUID.randomUUID();
        private final String machineArn;
        private final String role;
        private final Set<String> reservedMachines = new LinkedHashSet<>();
        private final List<String> startedExecutions = new ArrayList<>();
        private final Map<String, Outcome> observations = new LinkedHashMap<>();
        private final TapeHttpClient transport = new TapeHttpClient();
        private final SfnClient sfn;
        private JsonNode createRaw;

        private Cloud(URI endpoint, String account, String region, String partition, String label) {
            this.account = account;
            this.region = region;
            this.label = label;
            machineArn = "arn:" + partition + ":states:" + region + ":" + account + ":stateMachine:" + name;
            role = "arn:" + partition + ":iam::" + account + ":role/execution";
            sfn = SfnClient.builder().endpointOverride(endpoint).region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                    .httpClient(transport).overrideConfiguration(config -> config
                            .apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10)))
                    .build();
        }

        private CreateStateMachineResponse create() throws IOException {
            reservedMachines.add(machineArn);
            CreateStateMachineResponse created = sfn.createStateMachine(request -> request
                    .name(name).definition(DEFINITION).roleArn(role));
            createRaw = raw();
            assertEquals(machineArn, created.stateMachineArn());
            return created;
        }

        private String start(String machine) {
            String execution = sfn.startExecution(request -> request.stateMachineArn(machine)
                    .name(UUID.randomUUID().toString()).input("{}")).executionArn();
            startedExecutions.add(execution);
            return execution;
        }

        private DescribeStateMachineForExecutionResponse describe(String execution) {
            return sfn.describeStateMachineForExecution(request -> request.executionArn(execution));
        }

        private JsonNode raw() throws IOException {
            return MAPPER.readTree(transport.calls.getLast().response());
        }

        private void completed(String execution) {
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                    assertEquals("SUCCEEDED", sfn.describeExecution(request -> request.executionArn(execution))
                            .statusAsString()));
        }

        private Outcome observe(String key, Supplier<?> request) {
            Outcome outcome;
            try {
                request.get();
                outcome = new Outcome(transport.calls.getLast().status(), "");
            } catch (SfnException failure) {
                outcome = new Outcome(failure.statusCode(), failure.awsErrorDetails().errorCode());
            }
            observations.put(key, outcome);
            return outcome;
        }

        private void deleteAndConfirm(String machine) {
            sfn.deleteStateMachine(request -> request.stateMachineArn(machine));
            Outcome absent = observe("machine-absence-" + observations.size(), () ->
                    sfn.describeStateMachine(request -> request.stateMachineArn(machine)));
            assertEquals(new Outcome(400, "StateMachineDoesNotExist"), absent);
        }

        @Override
        public void close() throws Exception {
            Throwable cleanupFailure = null;
            try {
                for (String machine : reservedMachines) {
                    try {
                        deleteAndConfirm(machine);
                    } catch (Throwable failure) {
                        if (cleanupFailure == null) {
                            cleanupFailure = failure;
                        } else {
                            cleanupFailure.addSuppressed(failure);
                        }
                    }
                }
            } finally {
                try {
                    Path directory = Path.of("target", "sfn-execution-contract");
                    Files.createDirectories(directory);
                    Map<String, Object> tape = new LinkedHashMap<>();
                    tape.put("account", account);
                    tape.put("region", region);
                    tape.put("label", label);
                    tape.put("reservedMachines", reservedMachines);
                    tape.put("startedExecutions", startedExecutions);
                    tape.put("observations", observations);
                    tape.put("calls", transport.calls);
                    tape.put("cleanupFailure", cleanupFailure == null ? null : cleanupFailure.toString());
                    MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(name + "-" + label + ".json")
                            .toFile(), tape);
                } finally {
                    sfn.close();
                    transport.close();
                }
            }
            if (cleanupFailure != null) {
                throw new AssertionError("Owned state-machine cleanup failed", cleanupFailure);
            }
        }
    }

    private static final class TapeHttpClient implements SdkHttpClient {
        private final SdkHttpClient delegate = UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(10)).build();
        private final List<WireCall> calls = new ArrayList<>();

        @Override
        public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            ExecutableHttpRequest prepared = delegate.prepareRequest(request);
            return new ExecutableHttpRequest() {
                @Override
                public HttpExecuteResponse call() throws IOException {
                    String requestBody = "";
                    if (request.contentStreamProvider().isPresent()) {
                        try (InputStream body = request.contentStreamProvider().orElseThrow().newStream()) {
                            requestBody = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    }
                    HttpExecuteResponse received = prepared.call();
                    byte[] bytes = new byte[0];
                    if (received.responseBody().isPresent()) {
                        try (AbortableInputStream body = received.responseBody().orElseThrow()) {
                            bytes = body.readAllBytes();
                        }
                    }
                    calls.add(new WireCall(request.httpRequest().firstMatchingHeader("X-Amz-Target").orElse(""),
                            requestBody, received.httpResponse().statusCode(), received.httpResponse().headers(),
                            new String(bytes, StandardCharsets.UTF_8)));
                    return HttpExecuteResponse.builder().response(received.httpResponse())
                            .responseBody(AbortableInputStream.create(new ByteArrayInputStream(bytes))).build();
                }

                @Override
                public void abort() {
                    prepared.abort();
                }
            };
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
