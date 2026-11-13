package io.github.hectorvent.floci.services.neptune;

import io.github.hectorvent.floci.services.rds.RdsService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.neptune.NeptuneClient;
import software.amazon.awssdk.services.neptune.model.NeptuneException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(NeptuneClusterSnapshotRoutingTest.Profile.class)
class NeptuneClusterSnapshotRoutingTest {
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.neptune.mock", "true", "floci.services.docdb.mock", "true",
                    "floci.services.rds.mock", "true");
        }
    }

    @Inject
    NeptuneService neptune;

    @Inject
    RdsService rds;

    @Test
    void familySnapshotsCannotReuseAnIdentifierAndLegacyAmbiguityIsRefused() {
        String prefix = "family-snapshot-" + UUID.randomUUID().toString().substring(0, 8);
        String graph = prefix + "-graph";
        String document = prefix + "-document";
        String relational = prefix + "-relational";
        String legacy = prefix + "-legacy";
        try {
            query("CreateDBCluster").formParam("DBClusterIdentifier", graph).formParam("Engine", "neptune")
                    .post("/").then().statusCode(200);
            for (Map.Entry<String, String> source : Map.of(document, "docdb", relational, "aurora-postgresql").entrySet()) {
                query("CreateDBCluster").formParam("DBClusterIdentifier", source.getKey())
                        .formParam("Engine", source.getValue()).formParam("MasterUsername", "snapshotadmin")
                        .formParam("MasterUserPassword", "snapshot-password-99").post("/").then().statusCode(200);
            }
            for (String source : List.of(graph, document, relational)) {
                query("CreateDBClusterSnapshot").formParam("DBClusterIdentifier", source)
                        .formParam("DBClusterSnapshotIdentifier", source + "-snapshot").post("/").then().statusCode(200);
            }
            for (String source : List.of(graph, document, relational)) {
                for (String owner : List.of(graph, document, relational)) {
                    query("CreateDBClusterSnapshot").formParam("DBClusterIdentifier", source)
                            .formParam("DBClusterSnapshotIdentifier", owner + "-snapshot").post("/").then()
                            .statusCode(400).body(containsString("DBClusterSnapshotAlreadyExistsFault"));
                }
            }
            try (NeptuneClient client = client("000000000000", Region.US_EAST_1)) {
                Map<String, String> engines = client.describeDBClusterSnapshots().dbClusterSnapshots().stream()
                        .filter(row -> row.dbClusterSnapshotIdentifier().startsWith(prefix))
                        .collect(Collectors.toMap(row -> row.dbClusterSnapshotIdentifier(),
                                row -> row.engine()));
                assertEquals(Map.of(graph + "-snapshot", "neptune", document + "-snapshot", "docdb",
                        relational + "-snapshot", "aurora-postgresql"), engines);
            }
            // Model a legacy store made before the Query dispatcher enforced shared identifiers.
            neptune.createDbClusterSnapshot(legacy, graph, Map.of());
            rds.createDbClusterSnapshot(legacy, relational, Map.of());
            for (String action : List.of("DescribeDBClusterSnapshots", "DeleteDBClusterSnapshot")) {
                query(action).formParam("DBClusterSnapshotIdentifier", legacy).post("/").then().statusCode(400)
                        .body(containsString("InvalidParameterCombination"));
            }
            query("RestoreDBClusterFromSnapshot").formParam("DBClusterIdentifier", prefix + "-target")
                    .formParam("SnapshotIdentifier", legacy).formParam("Engine", "neptune").post("/").then()
                    .statusCode(400).body(containsString("InvalidParameterCombination"));
            assertTrue(neptune.hasClusterSnapshot(legacy));
            assertTrue(rds.hasClusterSnapshot(legacy, "us-east-1"));
        } finally {
            if (neptune.hasClusterSnapshot(legacy)) {
                neptune.deleteDbClusterSnapshot(legacy);
            }
            if (rds.hasClusterSnapshot(legacy, "us-east-1")) {
                rds.deleteDbClusterSnapshot(legacy, "us-east-1");
            }
            for (String source : List.of(graph, document, relational)) {
                query("DeleteDBClusterSnapshot").formParam("DBClusterSnapshotIdentifier", source + "-snapshot").post("/");
                query("DeleteDBCluster").formParam("DBClusterIdentifier", source)
                        .formParam("SkipFinalSnapshot", "true").post("/");
            }
        }
    }

    @Test
    void identicalNamesRemainIsolatedByAccountAndRegionIncludingArnLookups() {
        String source = "snapshot-scope-" + UUID.randomUUID().toString().substring(0, 8);
        String snapshot = source + "-snapshot";
        try (NeptuneClient east = client("111111111111", Region.US_EAST_1);
             NeptuneClient west = client("111111111111", Region.US_WEST_2);
             NeptuneClient other = client("222222222222", Region.US_EAST_1)) {
            List<NeptuneClient> clients = List.of(east, west, other);
            try {
                for (int i = 0; i < clients.size(); i++) {
                    int retention = i + 3;
                    clients.get(i).createDBCluster(request ->
                            request.dbClusterIdentifier(source).engine("neptune").backupRetentionPeriod(retention));
                    clients.get(i).createDBClusterSnapshot(request ->
                            request.dbClusterIdentifier(source).dbClusterSnapshotIdentifier(snapshot));
                }
                String eastArn = east.describeDBClusterSnapshots(request ->
                        request.dbClusterSnapshotIdentifier(snapshot)).dbClusterSnapshots().getFirst()
                        .dbClusterSnapshotArn();
                for (NeptuneClient client : List.of(west, other)) {
                    assertEquals("DBClusterSnapshotNotFoundFault", assertThrows(NeptuneException.class,
                            () -> client.describeDBClusterSnapshots(request -> request.dbClusterSnapshotIdentifier(eastArn)))
                            .awsErrorDetails().errorCode());
                    assertEquals(snapshot, client.describeDBClusterSnapshots(request ->
                            request.dbClusterSnapshotIdentifier(snapshot)).dbClusterSnapshots().getFirst()
                            .dbClusterSnapshotIdentifier());
                }
                east.deleteDBClusterSnapshot(request -> request.dbClusterSnapshotIdentifier(snapshot));
                for (NeptuneClient client : List.of(west, other)) {
                    assertEquals(1, client.describeDBClusterSnapshots(request ->
                            request.dbClusterSnapshotIdentifier(snapshot)).dbClusterSnapshots().size());
                }
            } finally {
                for (NeptuneClient client : clients) {
                    try {
                        client.deleteDBClusterSnapshot(request -> request.dbClusterSnapshotIdentifier(snapshot));
                    } catch (NeptuneException exception) {
                        assertEquals("DBClusterSnapshotNotFoundFault", exception.awsErrorDetails().errorCode());
                    }
                    client.deleteDBCluster(request -> request.dbClusterIdentifier(source).skipFinalSnapshot(true));
                }
            }
        }
    }

    private static RequestSpecification query(String action) {
        return given().contentType("application/x-www-form-urlencoded")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=000000000000/20261112/us-east-1/rds/aws4_request, "
                        + "SignedHeaders=host, Signature=test").formParam("Action", action).formParam("Version", "2014-10-31");
    }

    private static NeptuneClient client(String account, Region region) {
        return NeptuneClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(region).credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(account, "test"))).build();
    }
}
