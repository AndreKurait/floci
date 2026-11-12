package io.floci.aws.services.s3tables;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3tables.S3TablesClient;
import software.amazon.awssdk.services.s3tables.model.NotFoundException;
import software.amazon.awssdk.services.s3tables.model.S3TablesException;

import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3TablesMetricsIamIntegrationTest {
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void eachActionUsesItsOwnPermissionAndExactBucketResource() {
        String account = "000000000000";
        String userName = "metrics-user-" + UUID.randomUUID();
        try (S3TablesClient root = AwsTestClients.create(S3TablesClient.builder(), account, Region.US_EAST_1)) {
            String allowed = root.createTableBucket(r -> r.name("metrics-allowed-" + UUID.randomUUID())).arn();
            String denied = root.createTableBucket(r -> r.name("metrics-denied-" + UUID.randomUUID())).arn();
            try {
                EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                        cleanup, RestAssured.port, auth(account, "iam"), userName);
                try (S3TablesClient user = AwsTestClients.create(S3TablesClient.builder(),
                        credentials.accessKeyId(), credentials.secretAccessKey(), Region.US_EAST_1)) {
                    policy(account, userName, "GetTableBucket", allowed);
                    forbidden(() -> user.putTableBucketMetricsConfiguration(r -> r.tableBucketARN(allowed)
                            .overrideConfiguration(c -> c.putHeader("X-Amz-Target", "S3Tables.GetTableBucket"))));
                    policy(account, userName, "PutTableBucketMetricsConfiguration", allowed);
                    user.putTableBucketMetricsConfiguration(r -> r.tableBucketARN(allowed));
                    forbidden(() -> user.putTableBucketMetricsConfiguration(r -> r.tableBucketARN(denied)));
                    forbidden(() -> user.getTableBucketMetricsConfiguration(r -> r.tableBucketARN(allowed)));
                    forbidden(() -> user.deleteTableBucketMetricsConfiguration(r -> r.tableBucketARN(allowed)));
                    assertEquals("EntireBucket", root.getTableBucketMetricsConfiguration(r -> r.tableBucketARN(allowed)).id());
                    assertThrows(NotFoundException.class,
                            () -> root.getTableBucketMetricsConfiguration(r -> r.tableBucketARN(denied)));

                    policy(account, userName, "GetTableBucketMetricsConfiguration", allowed);
                    assertEquals("EntireBucket", user.getTableBucketMetricsConfiguration(r -> r.tableBucketARN(allowed)).id());
                    forbidden(() -> user.getTableBucketMetricsConfiguration(r -> r.tableBucketARN(denied)));
                    forbidden(() -> user.putTableBucketMetricsConfiguration(r -> r.tableBucketARN(allowed)));
                    forbidden(() -> user.deleteTableBucketMetricsConfiguration(r -> r.tableBucketARN(allowed)));

                    policy(account, userName, "DeleteTableBucketMetricsConfiguration", allowed);
                    user.deleteTableBucketMetricsConfiguration(r -> r.tableBucketARN(allowed));
                    forbidden(() -> user.deleteTableBucketMetricsConfiguration(r -> r.tableBucketARN(denied)));
                    forbidden(() -> user.putTableBucketMetricsConfiguration(r -> r.tableBucketARN(allowed)));
                    forbidden(() -> user.getTableBucketMetricsConfiguration(r -> r.tableBucketARN(allowed)));
                    assertThrows(NotFoundException.class,
                            () -> root.getTableBucketMetricsConfiguration(r -> r.tableBucketARN(allowed)));
                }
            } finally {
                root.deleteTableBucket(r -> r.tableBucketARN(allowed));
                root.deleteTableBucket(r -> r.tableBucketARN(denied));
            }
        }
    }

    private void policy(String account, String user, String action, String arn) {
        EnforcementFixtures.putUserPolicy(RestAssured.port, auth(account, "iam"), user, "metrics-scope", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3tables:%s","Resource":"%s"}]}
                """.formatted(action, arn));
    }

    private static void forbidden(Executable action) {
        S3TablesException exception = assertThrows(S3TablesException.class, action);
        assertEquals(403, exception.statusCode());
        assertEquals("AccessDeniedException", exception.awsErrorDetails().errorCode());
    }
}
