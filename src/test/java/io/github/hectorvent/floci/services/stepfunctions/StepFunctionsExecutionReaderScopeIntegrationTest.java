package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
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
import software.amazon.awssdk.services.sfn.model.SfnException;

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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** SDK controls for the signing Region and partition of both public execution readers. */
@QuarkusTest
class StepFunctionsExecutionReaderScopeIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFINITION =
            "{\"StartAt\":\"Done\",\"States\":{\"Done\":{\"Type\":\"Pass\",\"End\":true}}}";
    private static final String ACCOUNT = "771661203101";

    @TestHTTPResource
    URI endpoint;

    @ParameterizedTest
    @CsvSource({"us-west-2,aws", "cn-north-1,aws-cn"})
    void publicReadersRefuseAnExecutionOutsideTheirSigningScope(String region, String partition) throws Exception {
        try (Cloud owner = new Cloud(endpoint, ACCOUNT, "us-east-1", "aws", "owner");
             Cloud foreign = new Cloud(endpoint, ACCOUNT, region, partition, "foreign")) {
            String machine = owner.create().stateMachineArn();
            String execution = owner.start(machine);
            Outcome ownerRead = owner.observe("owner-execution", () -> owner.sfn.describeExecution(request ->
                    request.executionArn(execution)));
            Outcome foreignExecution = foreign.observe("foreign-execution", () -> foreign.sfn.describeExecution(
                    request -> request.executionArn(execution)));
            Outcome foreignConfiguration = foreign.observe("foreign-configuration", () ->
                    foreign.sfn.describeStateMachineForExecution(request -> request.executionArn(execution)));
            assertAll(
                    () -> assertEquals(new Outcome(200, ""), ownerRead),
                    () -> assertEquals(new Outcome(400, "InvalidArn"), foreignExecution),
                    () -> assertEquals(new Outcome(400, "InvalidArn"), foreignConfiguration));
        }
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
        private final String name = "execution-scope-" + UUID.randomUUID();
        private final String machineArn;
        private final String role;
        private final Set<String> reservedMachines = new LinkedHashSet<>();
        private final List<String> startedExecutions = new ArrayList<>();
        private final Map<String, Outcome> observations = new LinkedHashMap<>();
        private final TapeHttpClient transport = new TapeHttpClient();
        private final SfnClient sfn;

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

        private CreateStateMachineResponse create() {
            reservedMachines.add(machineArn);
            CreateStateMachineResponse created = sfn.createStateMachine(request -> request
                    .name(name).definition(DEFINITION).roleArn(role));
            assertEquals(machineArn, created.stateMachineArn());
            return created;
        }

        private String start(String machine) {
            String execution = sfn.startExecution(request -> request.stateMachineArn(machine)
                    .name(UUID.randomUUID().toString()).input("{}")).executionArn();
            startedExecutions.add(execution);
            return execution;
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
                    Path directory = Path.of("target", "sfn-execution-scope");
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
