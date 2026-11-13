package io.github.hectorvent.floci.services.docdb;

import io.github.hectorvent.floci.testing.DocDbMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.docdb.DocDbClient;
import software.amazon.awssdk.services.docdb.model.DBCluster;
import software.amazon.awssdk.services.docdb.model.DBClusterSnapshot;
import software.amazon.awssdk.services.docdb.model.DocDbException;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Native SDK Query/signing coverage; the profile deliberately models only metadata snapshots. */
@QuarkusTest
@TestProfile(DocDbMockProfile.class)
class DocDbClusterSnapshotSdkIntegrationTest {
    private static final String ACCOUNT = "111111111111";

    @Test
    void snapshotOutlivesSourceAndRestoresThroughItsArn() {
        String source = "sdk-docdb-" + UUID.randomUUID();
        String snapshot = source + "-snap";
        String target = source + "-restore";
        try (DocDbClient client = client(ACCOUNT, Region.US_EAST_1)) {
            try {
                DBCluster original = create(client, source);
                DBClusterSnapshot created = client.createDBClusterSnapshot(request -> request
                        .dbClusterIdentifier(source).dbClusterSnapshotIdentifier(snapshot)).dbClusterSnapshot();
                assertEquals("docdb", created.engine());
                assertEquals("available", created.status());
                assertEquals(source, created.dbClusterIdentifier());
                assertEquals("manual", created.snapshotType());
                assertEquals(100, created.percentProgress());
                assertEquals(original.masterUsername(), created.masterUsername());
                assertEquals(original.engineVersion(), created.engineVersion());
                assertNotNull(created.snapshotCreateTime());
                String arn = created.dbClusterSnapshotArn();
                assertTrue(arn.contains(":rds:us-east-1:" + ACCOUNT + ":cluster-snapshot:" + snapshot));
                assertEquals(snapshot, client.describeDBClusterSnapshots(request -> request
                        .dbClusterSnapshotIdentifier(arn)).dbClusterSnapshots().getFirst().dbClusterSnapshotIdentifier());
                assertTrue(client.describeDBClusterSnapshots(request -> request.dbClusterIdentifier(source))
                        .dbClusterSnapshots().stream().anyMatch(value -> arn.equals(value.dbClusterSnapshotArn())));
                client.deleteDBCluster(request -> request.dbClusterIdentifier(source).skipFinalSnapshot(true));
                DBCluster restored = client.restoreDBClusterFromSnapshot(request -> request
                        .dbClusterIdentifier(target).snapshotIdentifier(arn).engine("docdb")).dbCluster();
                assertEquals(target, restored.dbClusterIdentifier());
                assertEquals("available", restored.status());
                assertEquals("docdb", restored.engine());
                assertEquals(original.masterUsername(), restored.masterUsername());
                assertEquals(original.engineVersion(), restored.engineVersion());
                assertNotEquals(original.dbClusterArn(), restored.dbClusterArn());
                assertNotEquals(original.dbClusterResourceId(), restored.dbClusterResourceId());
                assertTrue(restored.dbClusterMembers().isEmpty());
                assertEquals(restored.dbClusterArn(), client.describeDBClusters(request -> request
                        .dbClusterIdentifier(target)).dbClusters().getFirst().dbClusterArn());
                client.deleteDBClusterSnapshot(request -> request.dbClusterSnapshotIdentifier(arn));
                assertError("DBClusterSnapshotNotFoundFault", () -> client.describeDBClusterSnapshots(request -> request
                        .dbClusterSnapshotIdentifier(snapshot)));
                assertEquals(target, client.describeDBClusters(request -> request.dbClusterIdentifier(target))
                        .dbClusters().getFirst().dbClusterIdentifier());
            } finally {
                deleteCluster(client, target);
                deleteCluster(client, source);
                deleteSnapshot(client, snapshot);
            }
        }
    }

    @Test
    void sameNamedSnapshotsStayInTheirAccountAndRegion() {
        String source = "sdk-scope-" + UUID.randomUUID();
        String snapshot = source + "-snap";
        String target = source + "-restore";
        try (DocDbClient owner = client(ACCOUNT, Region.US_EAST_1);
             DocDbClient otherAccount = client("222222222222", Region.US_EAST_1);
             DocDbClient otherRegion = client(ACCOUNT, Region.EU_WEST_1)) {
            try {
                for (DocDbClient client : new DocDbClient[]{owner, otherAccount, otherRegion}) {
                    create(client, source);
                    client.createDBClusterSnapshot(request -> request.dbClusterIdentifier(source)
                            .dbClusterSnapshotIdentifier(snapshot));
                }
                String ownerArn = owner.describeDBClusterSnapshots(request -> request
                        .dbClusterSnapshotIdentifier(snapshot)).dbClusterSnapshots().getFirst().dbClusterSnapshotArn();
                for (DocDbClient foreign : new DocDbClient[]{otherAccount, otherRegion}) {
                    assertError("DBClusterSnapshotNotFoundFault", () -> foreign.restoreDBClusterFromSnapshot(request ->
                            request.dbClusterIdentifier(target).snapshotIdentifier(ownerArn).engine("docdb")));
                    assertError("DBClusterNotFoundFault", () -> foreign.describeDBClusters(request ->
                            request.dbClusterIdentifier(target)));
                    assertNotEquals(ownerArn, foreign.describeDBClusterSnapshots(request ->
                            request.dbClusterSnapshotIdentifier(snapshot)).dbClusterSnapshots()
                            .getFirst().dbClusterSnapshotArn());
                }
                assertEquals(ownerArn, owner.describeDBClusterSnapshots(request -> request
                        .dbClusterSnapshotIdentifier(snapshot)).dbClusterSnapshots().getFirst().dbClusterSnapshotArn());
            } finally {
                for (DocDbClient client : new DocDbClient[]{owner, otherAccount, otherRegion}) {
                    deleteCluster(client, target);
                    deleteCluster(client, source);
                    deleteSnapshot(client, snapshot);
                }
            }
        }
    }

    private static DBCluster create(DocDbClient client, String source) {
        return client.createDBCluster(request -> request.dbClusterIdentifier(source).engine("docdb")
                .masterUsername("snapshotadmin").masterUserPassword("SnapshotOnlyPassword234!")).dbCluster();
    }

    private static DocDbClient client(String account, Region region) {
        return DocDbClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(region)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                .build();
    }

    private static void assertError(String code, Runnable request) {
        DocDbException exception = assertThrows(DocDbException.class, request::run);
        assertEquals(code, exception.awsErrorDetails().errorCode());
    }

    private static void deleteCluster(DocDbClient client, String id) {
        try {
            client.deleteDBCluster(request -> request.dbClusterIdentifier(id).skipFinalSnapshot(true));
        } catch (DocDbException exception) {
            assertEquals("DBClusterNotFoundFault", exception.awsErrorDetails().errorCode());
        }
    }

    private static void deleteSnapshot(DocDbClient client, String id) {
        try {
            client.deleteDBClusterSnapshot(request -> request.dbClusterSnapshotIdentifier(id));
        } catch (DocDbException exception) {
            assertEquals("DBClusterSnapshotNotFoundFault", exception.awsErrorDetails().errorCode());
        }
    }
}
