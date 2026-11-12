package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AutoScalingSettingsDescription;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ReplicaAutoScalingDescription;
import software.amazon.awssdk.services.dynamodb.model.ReplicaGlobalSecondaryIndexAutoScalingUpdate;
import software.amazon.awssdk.services.dynamodb.model.ReplicationGroupUpdate;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TableAutoScalingDescription;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DynamoDbReplicaAutoScalingIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbClient client;
    private String table;
    private String arn;

    @BeforeEach
    void createOwnedTableAndLogicalReplica() {
        client = AwsTestClients.create(DynamoDbClient.builder(), ACCOUNT, Region.US_EAST_1);
        table = "replica-autoscale-" + UUID.randomUUID();
        arn = create(client, table);
        client.updateTable(b -> b.tableName(table).replicaUpdates(
                ReplicationGroupUpdate.builder().create(c -> c.regionName("us-west-2")).build()));
    }

    @AfterEach
    void deleteOwnedTable() {
        try (DynamoDbClient owned = client) {
            owned.deleteTable(b -> b.tableName(table));
        }
    }

    @Test
    void populatedSdkSettingsRoundTripAcrossLogicalReplicasAndIndexes() {
        TableDescription before = client.describeTable(b -> b.tableName(table)).table();
        TableAutoScalingDescription initial = describe();
        assertEquals(List.of("us-east-1", "us-west-2"), initial.replicas().stream()
                .map(ReplicaAutoScalingDescription::regionName).toList());
        assertNull(initial.replicas().getFirst().replicaProvisionedWriteCapacityAutoScalingSettings());
        String configuredRole = arn.replace(":dynamodb:us-east-1:", ":iam::")
                .replace("table/" + table, "role/configured-metadata-only");
        TableAutoScalingDescription updated = client.updateTableReplicaAutoScaling(b -> b.tableName(arn)
                .provisionedWriteCapacityAutoScalingUpdate(s -> s.minimumUnits(3L).maximumUnits(40L)
                        .autoScalingDisabled(false).autoScalingRoleArn(configuredRole)
                        .scalingPolicyUpdate(p -> p.policyName("explicit-policy")
                                .targetTrackingScalingPolicyConfiguration(t -> t.targetValue(65.5)
                                        .disableScaleIn(false).scaleInCooldown(0).scaleOutCooldown(0))))
                .globalSecondaryIndexUpdates(g -> g.indexName("by-category")
                        .provisionedWriteCapacityAutoScalingUpdate(s -> s.minimumUnits(2L).maximumUnits(20L)))
                .replicaUpdates(r -> r.regionName("us-west-2")
                        .replicaProvisionedReadCapacityAutoScalingUpdate(s -> s.minimumUnits(4L).maximumUnits(30L))
                        .replicaGlobalSecondaryIndexUpdates(ReplicaGlobalSecondaryIndexAutoScalingUpdate.builder()
                                .indexName("by-category")
                                .provisionedReadCapacityAutoScalingUpdate(s -> s.minimumUnits(5L).maximumUnits(50L))
                                .build()))).tableAutoScalingDescription();
        assertEquals(updated, describe());
        for (ReplicaAutoScalingDescription replica : updated.replicas()) {
            assertEquals("ACTIVE", replica.replicaStatusAsString());
            AutoScalingSettingsDescription write = replica.replicaProvisionedWriteCapacityAutoScalingSettings();
            assertEquals(3L, write.minimumUnits());
            assertEquals(40L, write.maximumUnits());
            assertFalse(write.autoScalingDisabled());
            assertEquals(configuredRole, write.autoScalingRoleArn());
            assertEquals("explicit-policy", write.scalingPolicies().getFirst().policyName());
            assertEquals(65.5, write.scalingPolicies().getFirst().targetTrackingScalingPolicyConfiguration().targetValue());
            assertFalse(write.scalingPolicies().getFirst().targetTrackingScalingPolicyConfiguration().disableScaleIn());
            assertEquals(0, write.scalingPolicies().getFirst().targetTrackingScalingPolicyConfiguration().scaleInCooldown());
            assertEquals(2L, replica.globalSecondaryIndexes().getFirst()
                    .provisionedWriteCapacityAutoScalingSettings().minimumUnits());
        }
        assertNull(updated.replicas().getFirst().replicaProvisionedReadCapacityAutoScalingSettings());
        assertEquals(4L, updated.replicas().get(1).replicaProvisionedReadCapacityAutoScalingSettings().minimumUnits());
        assertEquals(5L, updated.replicas().get(1).globalSecondaryIndexes().getFirst()
                .provisionedReadCapacityAutoScalingSettings().minimumUnits());
        TableDescription after = client.describeTable(b -> b.tableName(table)).table();
        assertEquals(before.provisionedThroughput(), after.provisionedThroughput(), "settings do not drive capacity");
        assertEquals(before.replicas(), after.replicas());
        assertEquals(before.globalSecondaryIndexes(), after.globalSecondaryIndexes());
    }

    @Test
    void partialUpdatesPreserveFalseZeroPolicyAndOtherReplicaSettingsWithoutInventingDefaults() {
        client.updateTableReplicaAutoScaling(b -> b.tableName(table)
                .provisionedWriteCapacityAutoScalingUpdate(s -> s.minimumUnits(9007199254740993L)));
        AutoScalingSettingsDescription initial = describe().replicas().getFirst()
                .replicaProvisionedWriteCapacityAutoScalingSettings();
        assertEquals(9007199254740993L, initial.minimumUnits());
        assertNull(initial.maximumUnits());
        assertNull(initial.autoScalingDisabled());
        assertNull(initial.autoScalingRoleArn());
        assertFalse(initial.hasScalingPolicies());
        client.updateTableReplicaAutoScaling(b -> b.tableName(table)
                .provisionedWriteCapacityAutoScalingUpdate(s -> s.maximumUnits(9007199254741000L)
                        .autoScalingDisabled(false).scalingPolicyUpdate(p -> p
                                .targetTrackingScalingPolicyConfiguration(t -> t.targetValue(70.25)
                                        .disableScaleIn(false).scaleInCooldown(0).scaleOutCooldown(0)))));
        TableAutoScalingDescription populated = describe();
        assertNull(populated.replicas().getFirst().replicaProvisionedWriteCapacityAutoScalingSettings()
                .scalingPolicies().getFirst().policyName());
        assertEquals(populated, client.updateTableReplicaAutoScaling(b -> b.tableName(table)
                .provisionedWriteCapacityAutoScalingUpdate(s -> { })
                .replicaUpdates(r -> r.regionName("us-west-2").replicaGlobalSecondaryIndexUpdates(List.of())))
                .tableAutoScalingDescription());
        assertEquals(populated, client.updateTableReplicaAutoScaling(b -> b.tableName(table)).tableAutoScalingDescription());
        client.updateTableReplicaAutoScaling(b -> b.tableName(table)
                .provisionedWriteCapacityAutoScalingUpdate(s -> s.scalingPolicyUpdate(p -> p
                        .targetTrackingScalingPolicyConfiguration(t -> t.targetValue(80.5)))));
        AutoScalingSettingsDescription changed = describe().replicas().getFirst()
                .replicaProvisionedWriteCapacityAutoScalingSettings();
        assertEquals(9007199254740993L, changed.minimumUnits());
        assertFalse(changed.autoScalingDisabled());
        assertFalse(changed.scalingPolicies().getFirst().targetTrackingScalingPolicyConfiguration().disableScaleIn());
        assertEquals(0, changed.scalingPolicies().getFirst().targetTrackingScalingPolicyConfiguration().scaleOutCooldown());
    }

    @Test
    void refusedMultiMemberUpdateLeavesEverySettingUnchanged() {
        client.updateTableReplicaAutoScaling(b -> b.tableName(table)
                .provisionedWriteCapacityAutoScalingUpdate(s -> s.minimumUnits(5L).maximumUnits(30L)));
        TableAutoScalingDescription original = describe();
        assertError("ValidationException", () -> client.updateTableReplicaAutoScaling(b -> b.tableName(table)
                .provisionedWriteCapacityAutoScalingUpdate(s -> s.minimumUnits(31L))));
        assertError("ResourceNotFoundException", () -> client.updateTableReplicaAutoScaling(b -> b.tableName(table)
                .provisionedWriteCapacityAutoScalingUpdate(s -> s.maximumUnits(100L))
                .replicaUpdates(r -> r.regionName("absent"))));
        assertError("ResourceNotFoundException", () -> client.updateTableReplicaAutoScaling(b -> b.tableName(table)
                .provisionedWriteCapacityAutoScalingUpdate(s -> s.maximumUnits(100L))
                .globalSecondaryIndexUpdates(i -> i.indexName("absent"))));
        assertEquals(original, describe());
    }

    @Test
    void accountRegionAndFullArnOwnershipAreEnforcedEvenForMatchingTableNames() {
        try (DynamoDbClient otherAccount = AwsTestClients.create(DynamoDbClient.builder(), "222222222222", Region.US_EAST_1);
             DynamoDbClient otherRegion = AwsTestClients.create(DynamoDbClient.builder(), ACCOUNT, Region.US_WEST_2)) {
            create(otherAccount, table);
            create(otherRegion, table);
            try {
                client.updateTableReplicaAutoScaling(b -> b.tableName(table)
                        .provisionedWriteCapacityAutoScalingUpdate(s -> s.minimumUnits(7L)));
                assertNull(otherAccount.describeTableReplicaAutoScaling(b -> b.tableName(table))
                        .tableAutoScalingDescription().replicas().getFirst().replicaProvisionedWriteCapacityAutoScalingSettings());
                assertNull(otherRegion.describeTableReplicaAutoScaling(b -> b.tableName(table))
                        .tableAutoScalingDescription().replicas().getFirst().replicaProvisionedWriteCapacityAutoScalingSettings());
                assertError("ResourceNotFoundException", () -> otherAccount.describeTableReplicaAutoScaling(b -> b.tableName(arn)));
                assertError("ResourceNotFoundException", () -> client.updateTableReplicaAutoScaling(b -> b
                        .tableName(arn.replace(ACCOUNT, "222222222222"))
                        .provisionedWriteCapacityAutoScalingUpdate(s -> s.minimumUnits(99L))));
                assertError("ValidationException", () -> otherRegion.describeTableReplicaAutoScaling(b -> b.tableName(arn)));
                assertError("ResourceNotFoundException", () -> client.describeTableReplicaAutoScaling(b -> b
                        .tableName(arn.replace("arn:aws:", "arn:aws-us-gov:"))));
                assertEquals(7L, describe().replicas().getFirst().replicaProvisionedWriteCapacityAutoScalingSettings().minimumUnits());
            } finally {
                otherAccount.deleteTable(b -> b.tableName(table));
                otherRegion.deleteTable(b -> b.tableName(table));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"aws,us-east-1", "aws-cn,cn-north-1", "aws-us-gov,us-gov-west-1"})
    void exactPartitionTableArnWorks(String partition, String region) {
        String ownedName = table + "-partition";
        try (DynamoDbClient regional = AwsTestClients.create(DynamoDbClient.builder(), ACCOUNT, Region.of(region))) {
            String ownedArn = create(regional, ownedName);
            try {
                assertTrue(ownedArn.startsWith("arn:" + partition + ":dynamodb:" + region + ":" + ACCOUNT + ":table/"));
                assertEquals(11L, regional.updateTableReplicaAutoScaling(b -> b.tableName(ownedArn)
                        .provisionedWriteCapacityAutoScalingUpdate(s -> s.minimumUnits(11L)))
                        .tableAutoScalingDescription().replicas().getFirst()
                        .replicaProvisionedWriteCapacityAutoScalingSettings().minimumUnits());
                assertEquals(ownedName, regional.describeTableReplicaAutoScaling(b -> b.tableName(ownedArn))
                        .tableAutoScalingDescription().tableName());
            } finally {
                regional.deleteTable(b -> b.tableName(ownedName));
            }
        }
    }

    @Test
    void jsonCborAndRpcV2PreserveExactTypedValues() throws Exception {
        for (String protocol : List.of("json", "cbor", "rpc-v2-cbor")) {
            ObjectMapper codec = "json".equals(protocol) ? mapper : new ObjectMapper(new CBORFactory());
            boolean rpc = "rpc-v2-cbor".equals(protocol);
            Map<String, Object> request = Map.of("TableName", table,
                    "ProvisionedWriteCapacityAutoScalingUpdate", Map.of("MinimumUnits", 9007199254740993L,
                            "MaximumUnits", 9007199254740999L, "AutoScalingDisabled", false));
            for (String operation : List.of("UpdateTableReplicaAutoScaling", "DescribeTableReplicaAutoScaling")) {
                RequestSpecification call = given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                        + "/20260101/us-east-1/dynamodb/aws4_request, SignedHeaders=host, Signature=test");
                if (rpc) {
                    call.header("smithy-protocol", "rpc-v2-cbor").contentType("application/cbor");
                } else {
                    call.header("X-Amz-Target", "DynamoDB_20120810." + operation)
                            .contentType("json".equals(protocol) ? "application/x-amz-json-1.0" : "application/x-amz-cbor-1.1");
                }
                byte[] result = call.body(codec.writeValueAsBytes(operation.startsWith("Update") ? request
                                : Map.of("TableName", table)))
                        .post(rpc ? "/service/DynamoDB_20120810/operation/" + operation : "/")
                        .then().statusCode(200).extract().asByteArray();
                JsonNode settings = codec.readTree(result).path("TableAutoScalingDescription").path("Replicas").get(0)
                        .path("ReplicaProvisionedWriteCapacityAutoScalingSettings");
                assertEquals(9007199254740993L, settings.path("MinimumUnits").longValue());
                assertTrue(settings.path("AutoScalingDisabled").isBoolean());
                assertFalse(settings.path("AutoScalingDisabled").booleanValue());
            }
        }
    }

    @Test
    void tableDeletionAndRecreationDoNotInheritSettings() {
        client.updateTableReplicaAutoScaling(b -> b.tableName(table)
                .provisionedWriteCapacityAutoScalingUpdate(s -> s.minimumUnits(5L)));
        client.deleteTable(b -> b.tableName(table));
        assertError("ResourceNotFoundException", () -> client.describeTableReplicaAutoScaling(b -> b.tableName(table)));
        create(client, table);
        assertNull(describe().replicas().getFirst().replicaProvisionedWriteCapacityAutoScalingSettings());
    }

    private TableAutoScalingDescription describe() {
        return client.describeTableReplicaAutoScaling(b -> b.tableName(table)).tableAutoScalingDescription();
    }

    private static String create(DynamoDbClient client, String name) {
        return client.createTable(b -> b.tableName(name).billingMode(BillingMode.PROVISIONED)
                .provisionedThroughput(t -> t.readCapacityUnits(5L).writeCapacityUnits(5L))
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("category").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder().indexName("by-category")
                        .keySchema(KeySchemaElement.builder().attributeName("category").keyType(KeyType.HASH).build())
                        .projection(p -> p.projectionType("ALL"))
                        .provisionedThroughput(t -> t.readCapacityUnits(5L).writeCapacityUnits(5L)).build()))
                .tableDescription().tableArn();
    }

    private static void assertError(String code, Executable action) {
        DynamoDbException exception = assertThrows(DynamoDbException.class, action);
        assertEquals(400, exception.statusCode());
        assertEquals(code, exception.awsErrorDetails().errorCode());
    }
}
