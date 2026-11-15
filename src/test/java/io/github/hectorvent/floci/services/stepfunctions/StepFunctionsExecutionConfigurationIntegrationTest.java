package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.ExecutionConfiguration;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.CreateStateMachineResponse;
import software.amazon.awssdk.services.sfn.model.DescribeStateMachineForExecutionResponse;
import software.amazon.awssdk.services.sfn.model.ExecutionDoesNotExistException;
import software.amazon.awssdk.services.sfn.model.InvalidArnException;
import software.amazon.awssdk.services.sfn.model.SfnException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class StepFunctionsExecutionConfigurationIntegrationTest {
    private static final String DEFINITION = """
            {"StartAt":"Done","States":{"Done":{"Type":"Pass","Result":"original","End":true}}}
            """;
    @Inject
    StepFunctionsService service;
    @Inject
    ObjectMapper mapper;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "cn-north-1,aws-cn"})
    void executionRetainsDefinitionAndRoleThroughUpdatesAndDeletion(String region, String partition) {
        try (Cloud cloud = new Cloud("771661202001", region, partition)) {
            CreateStateMachineResponse created = cloud.create(DEFINITION);
            String execution = cloud.start(created.stateMachineArn());
            DescribeStateMachineForExecutionResponse original = cloud.describe(execution);
            assertEquals(created.stateMachineArn(), original.stateMachineArn());
            assertEquals(DEFINITION, original.definition());
            assertEquals(cloud.role, original.roleArn());
            assertEquals(created.creationDate(), original.updateDate());
            assertFalse(original.tracingConfiguration().enabled());
            cloud.sfn.updateStateMachine(request -> request.stateMachineArn(created.stateMachineArn())
                    .definition(DEFINITION.replace("original", "changed")).roleArn(cloud.role + "-changed")
                    .tracingConfiguration(config -> config.enabled(true)));
            String changedExecution = cloud.start(created.stateMachineArn());
            DescribeStateMachineForExecutionResponse changed = cloud.describe(changedExecution);
            assertNotEquals(original.definition(), changed.definition());
            assertNotEquals(original.roleArn(), changed.roleArn());
            assertTrue(changed.tracingConfiguration().enabled());
            assertTrue(original.equalsBySdkFields(cloud.describe(execution)));
            cloud.completed(execution);
            cloud.completed(changedExecution);
            cloud.sfn.deleteStateMachine(request -> request.stateMachineArn(created.stateMachineArn()));
            assertThrows(ExecutionDoesNotExistException.class, () -> cloud.describe(execution));
            assertThrows(ExecutionDoesNotExistException.class, () -> cloud.describe(changedExecution));
        }
    }

    @Test
    void versionAndAliasExecutionsCaptureTheResolvedVersion() {
        try (Cloud cloud = new Cloud("771661202002", "us-east-1", "aws")) {
            CreateStateMachineResponse created = cloud.create(DEFINITION);
            String version = created.stateMachineVersionArn();
            String alias = cloud.sfn.createStateMachineAlias(request -> request.name("LIVE")
                    .routingConfiguration(route -> route.stateMachineVersionArn(version).weight(100)))
                    .stateMachineAliasArn();
            try {
                cloud.sfn.updateStateMachine(request -> request.stateMachineArn(created.stateMachineArn())
                        .definition(DEFINITION.replace("original", "current")));
                for (String selected : List.of(version, alias)) {
                    String execution = cloud.start(selected);
                    assertEquals(DEFINITION, cloud.describe(execution).definition());
                    assertEquals(created.stateMachineArn(), cloud.describe(execution).stateMachineArn());
                    assertEquals(version, cloud.sfn.describeExecution(request -> request.executionArn(execution))
                            .stateMachineVersionArn());
                }
                assertTrue(cloud.describe(cloud.start(created.stateMachineArn())).definition().contains("current"));
            } finally {
                cloud.sfn.deleteStateMachineAlias(request -> request.stateMachineAliasArn(alias));
            }
        }
    }

    @Test
    void metadataOnlyAndErrorsDoNotExposeAnotherExecution() {
        try (Cloud cloud = new Cloud("771661202003", "us-east-1", "aws");
             Cloud foreign = new Cloud("771661202004", "us-east-1", "aws")) {
            String machine = cloud.create(DEFINITION).stateMachineArn();
            String execution = cloud.start(machine);
            DescribeStateMachineForExecutionResponse metadata = cloud.sfn.describeStateMachineForExecution(request ->
                    request.executionArn(execution).includedData("METADATA_ONLY"));
            assertEquals("{}", metadata.definition());
            assertEquals(cloud.role, metadata.roleArn());
            assertThrows(ExecutionDoesNotExistException.class, () -> foreign.describe(execution));
            assertThrows(ExecutionDoesNotExistException.class, () -> cloud.describe(execution + "-missing"));
            assertThrows(InvalidArnException.class, () -> cloud.describe(machine));
            SfnException invalid = assertThrows(SfnException.class, () ->
                    cloud.sfn.describeStateMachineForExecution(request -> request.executionArn(execution)
                            .includedData("INVALID")));
            assertEquals("ValidationException", invalid.awsErrorDetails().errorCode());
        }
    }

    @Test
    void persistedExecutionKeepsItsConfigurationAndLegacyDataDoesNotGuessCurrentDefinition() throws Exception {
        try (Cloud cloud = new Cloud("771661202005", "us-east-1", "aws")) {
            String machine = cloud.create(DEFINITION).stateMachineArn();
            String execution = cloud.start(machine);
            cloud.completed(execution);
            Execution stored = RequestScopes.callAs(cloud.account, cloud.region,
                    () -> service.describeExecution(execution));
            Execution restored = mapper.readValue(mapper.writeValueAsBytes(stored), Execution.class);
            assertEquals(stored.getStateMachineConfiguration(), restored.getStateMachineConfiguration());
            ExecutionConfiguration read = RequestScopes.callAs(cloud.account, cloud.region,
                    () -> service.describeStateMachineForExecution(execution));
            ((ObjectNode) read.tracingConfiguration()).put("enabled", true);
            assertFalse(cloud.describe(execution).tracingConfiguration().enabled());
            stored.setStateMachineConfiguration(null);
            AwsException legacy = assertThrows(AwsException.class, () -> RequestScopes.callAs(
                    cloud.account, cloud.region, () -> service.describeStateMachineForExecution(execution)));
            assertEquals("UnsupportedOperation", legacy.getErrorCode());
            assertTrue(legacy.getMessage().contains("original definition is unavailable"));
        }
    }

    private static final class Cloud implements AutoCloseable {
        private final String account;
        private final String region;
        private final String role;
        private final SfnClient sfn;
        private final List<String> machines = new ArrayList<>();

        private Cloud(String account, String region, String partition) {
            this.account = account;
            this.region = region;
            role = "arn:" + partition + ":iam::" + account + ":role/execution";
            sfn = SfnClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                    .region(Region.of(region)).credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(account, "test")))
                    .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        }

        private CreateStateMachineResponse create(String definition) {
            CreateStateMachineResponse created = sfn.createStateMachine(request -> request
                    .name("execution-config-" + UUID.randomUUID()).definition(definition).roleArn(role).publish(true));
            machines.add(created.stateMachineArn());
            return created;
        }

        private String start(String machine) {
            return sfn.startExecution(request -> request.stateMachineArn(machine)
                    .name(UUID.randomUUID().toString()).input("{}")).executionArn();
        }

        private DescribeStateMachineForExecutionResponse describe(String execution) {
            return sfn.describeStateMachineForExecution(request -> request.executionArn(execution));
        }

        private void completed(String execution) {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("SUCCEEDED",
                    sfn.describeExecution(request -> request.executionArn(execution)).statusAsString()));
        }

        @Override
        public void close() {
            try {
                for (String machine : machines) {
                    sfn.deleteStateMachine(request -> request.stateMachineArn(machine));
                }
            } finally {
                sfn.close();
            }
        }
    }
}
