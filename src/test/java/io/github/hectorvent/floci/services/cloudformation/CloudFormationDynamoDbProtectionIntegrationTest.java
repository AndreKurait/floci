package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stack success must preserve the table's protection, data and deletion behavior. */
@QuarkusTest
class CloudFormationDynamoDbProtectionIntegrationTest {
    private static final String TEMPLATE = """
            {"Parameters":{"Protection":{"Type":"String","Default":"true"}},
             "Resources":{"Orders":{"Type":"AWS::DynamoDB::Table","Properties":{
               "TableName":"%s","BillingMode":"PAY_PER_REQUEST",
               "KeySchema":[{"AttributeName":"pk","KeyType":"HASH"}],
               "AttributeDefinitions":[{"AttributeName":"pk","AttributeType":"S"}],
               "Tags":[{"Key":"revision","Value":"%s"}]%s}}%s}}
            """;
    private static final String FAILURE = """
            ,"BadSecret":{"Type":"AWS::SecretsManager::Secret","DependsOn":"Orders",
              "Properties":{"Name":"%s","SecretString":"explicit",
                "GenerateSecretString":{"PasswordLength":32}}}
            """;

    @TestHTTPResource("/") URI endpoint;

    private CloudFormationClient cloudFormation;
    private DynamoDbClient dynamoDb;
    private String stack;
    private String table;

    @BeforeEach
    void clients() {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"));
        cloudFormation = CloudFormationClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        dynamoDb = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(credentials).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        stack = "cfn-protection-" + UUID.randomUUID();
        table = "orders-" + UUID.randomUUID();
    }

    @AfterEach
    void cleanup() {
        try {
            try {
                if (describe().deletionProtectionEnabled()) {
                    protection(false);
                }
            } catch (ResourceNotFoundException expected) {
                // A completed stack delete already removed this test's table.
            }
            if (!"DELETE_COMPLETE".equals(status())) {
                cloudFormation.deleteStack(request -> request.stackName(stack));
                awaitStatus("DELETE_COMPLETE");
            }
            assertThrows(ResourceNotFoundException.class, this::describe);
        } finally {
            cloudFormation.close();
            dynamoDb.close();
        }
    }

    @Test
    void declaredProtectionBlocksBothApiAndStackDeletionUntilExplicitlyDisabled() {
        create(",\"DeletionProtectionEnabled\":{\"Ref\":\"Protection\"}");
        assertTrue(describe().deletionProtectionEnabled());
        DynamoDbException refused = assertThrows(DynamoDbException.class, () ->
                dynamoDb.deleteTable(request -> request.tableName(table)));
        assertEquals("ValidationException", refused.awsErrorDetails().errorCode());
        cloudFormation.deleteStack(request -> request.stackName(stack));
        awaitStatus("DELETE_FAILED");
        assertTrue(describe().deletionProtectionEnabled());
        protection(false);
        cloudFormation.deleteStack(request -> request.stackName(stack));
        awaitStatus("DELETE_COMPLETE");
        assertThrows(ResourceNotFoundException.class, this::describe);
    }

    @Test
    void stackDeletionHonoursProtectionEnabledOutsideCloudFormation() {
        create("");
        protection(true);
        cloudFormation.deleteStack(request -> request.stackName(stack));
        awaitStatus("DELETE_FAILED");
        assertTrue(describe().deletionProtectionEnabled());
    }

    @Test
    void enablingProtectionOnUpdateRetainsTheTable() {
        create("");
        String identity = describe().tableId();
        update(",\"DeletionProtectionEnabled\":true", false);
        awaitStatus("UPDATE_COMPLETE");
        assertEquals(identity, describe().tableId());
        assertTrue(describe().deletionProtectionEnabled());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disablingOrRemovingThePropertyRetainsTheSameTable(boolean remove) {
        create(",\"DeletionProtectionEnabled\":true");
        String identity = describe().tableId();
        update(remove ? "" : ",\"DeletionProtectionEnabled\":false", false);
        awaitStatus("UPDATE_COMPLETE");
        assertEquals(identity, describe().tableId());
        assertFalse(describe().deletionProtectionEnabled());
    }

    @Test
    void unrelatedUpdatesPreserveProtectionTheStackNeverManaged() {
        create("");
        protection(true);
        update("", false);
        awaitStatus("UPDATE_COMPLETE");
        assertTrue(describe().deletionProtectionEnabled());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedUpdatesRestoreProtectionAndKeepData(boolean initial) {
        create(",\"DeletionProtectionEnabled\":" + initial);
        Map<String, AttributeValue> key = Map.of("pk", AttributeValue.fromS("survives"));
        dynamoDb.putItem(request -> request.tableName(table).item(key));
        update(",\"DeletionProtectionEnabled\":" + !initial, true);
        awaitStatus("UPDATE_ROLLBACK_COMPLETE");
        assertEquals(initial, describe().deletionProtectionEnabled());
        assertEquals(key, dynamoDb.getItem(request -> request.tableName(table).key(key)).item());
    }

    @Test
    void createRollbackKeepsAProtectedTableUntilExplicitCleanup() {
        cloudFormation.createStack(request -> request.stackName(stack)
                .templateBody(TEMPLATE.formatted(table, "before", ",\"DeletionProtectionEnabled\":true",
                        FAILURE.formatted(stack))));
        awaitStatus("ROLLBACK_FAILED");
        assertTrue(describe().deletionProtectionEnabled());
    }

    private void create(String properties) {
        cloudFormation.createStack(request -> request.stackName(stack)
                .templateBody(TEMPLATE.formatted(table, "before", properties, "")));
        awaitStatus("CREATE_COMPLETE");
    }

    private void update(String properties, boolean fail) {
        cloudFormation.updateStack(request -> request.stackName(stack)
                .templateBody(TEMPLATE.formatted(table, "after", properties,
                        fail ? FAILURE.formatted(stack) : "")));
    }

    private TableDescription describe() {
        return dynamoDb.describeTable(request -> request.tableName(table)).table();
    }

    private void protection(boolean enabled) {
        dynamoDb.updateTable(request -> request.tableName(table).deletionProtectionEnabled(enabled));
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(15)).pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(25))
                .untilAsserted(() -> assertEquals(expected, status()));
    }

    private String status() {
        try {
            return cloudFormation.describeStacks(request -> request.stackName(stack))
                    .stacks().getFirst().stackStatusAsString();
        } catch (CloudFormationException failure) {
            if ("ValidationError".equals(failure.awsErrorDetails().errorCode())
                    && failure.awsErrorDetails().errorMessage().contains("does not exist")) {
                return "DELETE_COMPLETE";
            }
            throw failure;
        }
    }
}
