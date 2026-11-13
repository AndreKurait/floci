package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class DynamoDbReplicaAutoScalingIamIntegrationTest {
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void updateAndDescribePermissionsAreIndependentAndScopedToTheExactTable() {
        String account = "000000000000";
        String allowed = "replica-autoscale-iam-" + UUID.randomUUID();
        String denied = allowed + "-denied";
        try (DynamoDbClient root = AwsTestClients.create(DynamoDbClient.builder(), account, Region.US_EAST_1)) {
            String allowedArn = create(root, allowed);
            String deniedArn = create(root, denied);
            try {
                String name = "replica-updater-" + UUID.randomUUID();
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(account, "iam"), name);
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(account, "iam"), name, "update-one", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                        "Action":"dynamodb:UpdateTableReplicaAutoScaling","Resource":"%s"}]}
                        """.formatted(allowedArn));
                try (DynamoDbClient user = AwsTestClients.create(DynamoDbClient.builder(),
                        credentials.accessKeyId(), credentials.secretAccessKey(), Region.US_EAST_1)) {
                    assertEquals(4L, user.updateTableReplicaAutoScaling(b -> b.tableName(allowedArn)
                            .provisionedWriteCapacityAutoScalingUpdate(s -> s.minimumUnits(4L)))
                            .tableAutoScalingDescription().replicas().getFirst()
                            .replicaProvisionedWriteCapacityAutoScalingSettings().minimumUnits());
                    denied(() -> user.updateTableReplicaAutoScaling(b -> b.tableName(deniedArn)
                            .provisionedWriteCapacityAutoScalingUpdate(s -> s.minimumUnits(99L))));
                    denied(() -> user.describeTableReplicaAutoScaling(b -> b.tableName(allowed)));
                    denied(() -> user.describeTable(b -> b.tableName(allowed)));
                    EnforcementFixtures.putUserPolicy(RestAssured.port, auth(account, "iam"), name, "update-one", """
                            {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                            "Action":"dynamodb:DescribeTableReplicaAutoScaling","Resource":"%s"}]}
                            """.formatted(allowedArn));
                    assertEquals(4L, user.describeTableReplicaAutoScaling(b -> b.tableName(allowedArn))
                            .tableAutoScalingDescription().replicas().getFirst()
                            .replicaProvisionedWriteCapacityAutoScalingSettings().minimumUnits());
                    denied(() -> user.describeTableReplicaAutoScaling(b -> b.tableName(deniedArn)));
                    denied(() -> user.updateTableReplicaAutoScaling(b -> b.tableName(allowed)
                            .provisionedWriteCapacityAutoScalingUpdate(s -> s.minimumUnits(99L))));
                }
                assertNull(root.describeTableReplicaAutoScaling(b -> b.tableName(denied))
                        .tableAutoScalingDescription().replicas().getFirst().replicaProvisionedWriteCapacityAutoScalingSettings());
            } finally {
                root.deleteTable(b -> b.tableName(allowed));
                root.deleteTable(b -> b.tableName(denied));
            }
        }
    }

    private static void denied(Executable action) {
        DynamoDbException exception = assertThrows(DynamoDbException.class, action);
        assertEquals("AccessDeniedException", exception.awsErrorDetails().errorCode());
    }

    private static String create(DynamoDbClient client, String name) {
        return client.createTable(b -> b.tableName(name).billingMode(BillingMode.PAY_PER_REQUEST)
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build()))
                .tableDescription().tableArn();
    }
}
