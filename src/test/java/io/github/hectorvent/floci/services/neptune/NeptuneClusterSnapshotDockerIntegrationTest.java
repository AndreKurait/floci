package io.github.hectorvent.floci.services.neptune;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.neptune.model.NeptuneCluster;
import io.github.hectorvent.floci.services.neptune.model.NeptuneClusterSnapshot;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.apache.tinkerpop.gremlin.driver.Client;
import org.apache.tinkerpop.gremlin.driver.Cluster;
import org.apache.tinkerpop.gremlin.util.ser.GraphSONMessageSerializerV3;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.neptune.NeptuneClient;
import software.amazon.awssdk.services.neptune.model.DBClusterSnapshot;
import software.amazon.awssdk.services.neptune.model.NeptuneException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.RdsRequests.assumeDocker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SDK control requests with an independent official Gremlin driver and real TinkerGraph data. */
@QuarkusTest
@Tag("docker")
@TestProfile(SidecarContainersProfile.class)
class NeptuneClusterSnapshotDockerIntegrationTest {
    private static final String READ = """
            def vertices=g.V().order().by(T.id).project('id','label','name','count','active')
                .by(T.id).by(T.label).by('name').by('count').by('active').toList();
            def edges=g.E().order().by(T.id).project('id','label','out','in','weight')
                .by(T.id).by(T.label).by(outV().id()).by(inV().id()).by('weight').toList();
            [['vertices':vertices,'edges':edges]]
            """;
    private static final Map<String, Object> ORIGINAL = Map.of(
            "vertices", List.of(
                    Map.of("id", 1L, "label", "person", "name", "Alpha", "count", 7, "active", true),
                    Map.of("id", 2L, "label", "person", "name", "Beta", "count", 12, "active", false)),
            "edges", List.of(Map.of("id", 3L, "label", "knows", "out", 1L, "in", 2L, "weight", 0.75d)));

    @Inject
    NeptuneService service;

    @Inject
    ContainerLifecycleManager containers;

    @Inject
    StorageFactory storageFactory;

    @Test
    void storedGraphArchiveRestoresVerticesEdgesAndPropertiesAfterSourceDeletion() throws Exception {
        assumeDocker();
        String source = "neptune-archive-" + UUID.randomUUID().toString().substring(0, 10);
        String target = source + "-restored";
        String snapshotId = source + "-snapshot";
        String secondId = source + "-second";
        List<String> ownedContainers = new ArrayList<>();
        try (NeptuneClient api = api()) {
            try {
                api.createDBCluster(request -> request.dbClusterIdentifier(source).engine("neptune")
                        .backupRetentionPeriod(5).preferredBackupWindow("02:00-03:00"));
                NeptuneCluster sourceCluster = service.getDbCluster(source);
                assertNotNull(sourceCluster.getContainerId());
                ownedContainers.add(sourceCluster.getContainerId());
                DBClusterSnapshot snapshot;
                String storedArchive;
                Cluster sourceConnection = graph(sourceCluster);
                try {
                    Client client = sourceConnection.connect();
                    evaluate(client, """
                            g.addV('person').property(T.id,1L).property('name','Alpha')
                                .property('count',7).property('active',true).iterate();
                            g.addV('person').property(T.id,2L).property('name','Beta')
                                .property('count',12).property('active',false).iterate();
                            g.V(1L).addE('knows').to(__.V(2L)).property(T.id,3L).property('weight',0.75d).iterate();true
                            """);
                    assertEquals(ORIGINAL, evaluate(client, READ));
                    snapshot = api.createDBClusterSnapshot(request -> request.dbClusterIdentifier(source)
                            .dbClusterSnapshotIdentifier(snapshotId).tags(tag -> tag.key("archive").value("original")))
                            .dbClusterSnapshot();
                    assertEquals("available", snapshot.status());
                    assertEquals("neptune", snapshot.engine());
                    assertFalse(service.getDbClusterSnapshot(snapshotId).metadataOnly());
                    storedArchive = service.getDbClusterSnapshot(snapshotId).archiveBase64();
                    assertTrue(storedArchive.length() > 0);
                    assertEquals(snapshot.dbClusterSnapshotArn(), api.describeDBClusterSnapshots(request ->
                            request.dbClusterSnapshotIdentifier(snapshotId)).dbClusterSnapshots().getFirst()
                            .dbClusterSnapshotArn());
                    assertTrue(api.describeDBClusterSnapshots().dbClusterSnapshots().stream()
                            .anyMatch(row -> snapshotId.equals(row.dbClusterSnapshotIdentifier())));
                    assertEquals("original", api.listTagsForResource(request ->
                            request.resourceName(snapshot.dbClusterSnapshotArn())).tagList().getFirst().value());
                    evaluate(client, "g.V(1L).drop().iterate();g.V(2L).property('name','Changed').iterate();true");
                    assertEquals(Map.of("vertices", List.of(Map.of("id", 2L, "label", "person", "name", "Changed",
                            "count", 12, "active", false)), "edges", List.of()), evaluate(client, READ));
                    assertEquals(storedArchive, service.getDbClusterSnapshot(snapshotId).archiveBase64());
                } finally {
                    sourceConnection.close();
                }
                api.deleteDBCluster(request -> request.dbClusterIdentifier(source).skipFinalSnapshot(true));
                assertGone(sourceCluster.getContainerId());
                api.restoreDBClusterFromSnapshot(request -> request.dbClusterIdentifier(target)
                        .snapshotIdentifier(snapshot.dbClusterSnapshotArn()).engine("neptune"));
                NeptuneCluster restored = service.getDbCluster(target);
                assertNotNull(restored.getContainerId());
                ownedContainers.add(restored.getContainerId());
                assertNotEquals(sourceCluster.getContainerId(), restored.getContainerId());
                assertEquals(5, restored.getBackupRetentionPeriod());
                Cluster targetConnection = graph(restored);
                try {
                    Client client = targetConnection.connect();
                    assertEquals(ORIGINAL, evaluate(client, READ));
                    api.createDBClusterSnapshot(request ->
                            request.dbClusterIdentifier(target).dbClusterSnapshotIdentifier(secondId));
                    assertFalse(service.getDbClusterSnapshot(secondId).metadataOnly());
                    api.deleteDBClusterSnapshot(request -> request.dbClusterSnapshotIdentifier(snapshotId));
                    assertEquals(ORIGINAL, evaluate(client, READ));
                    assertEquals("DBClusterSnapshotNotFoundFault", assertThrows(NeptuneException.class,
                            () -> api.describeDBClusterSnapshots(request -> request.dbClusterSnapshotIdentifier(snapshotId)))
                            .awsErrorDetails().errorCode());
                } finally {
                    targetConnection.close();
                }
            } finally {
                for (String id : List.of(snapshotId, secondId)) {
                    if (service.hasClusterSnapshot(id)) {
                        api.deleteDBClusterSnapshot(request -> request.dbClusterSnapshotIdentifier(id));
                    }
                }
                for (String id : List.of(target, source)) {
                    if (service.hasCluster(id)) {
                        api.deleteDBCluster(request -> request.dbClusterIdentifier(id).skipFinalSnapshot(true));
                    }
                }
                ownedContainers.forEach(this::assertGone);
            }
        }
    }

    @Test
    void anEmptyRealGraphHasARestorableArchive() throws Exception {
        assumeDocker();
        String source = "neptune-empty-" + UUID.randomUUID().toString().substring(0, 10);
        String target = source + "-restored";
        String snapshot = source + "-snapshot";
        List<String> ownedContainers = new ArrayList<>();
        try (NeptuneClient api = api()) {
            try {
                api.createDBCluster(request -> request.dbClusterIdentifier(source).engine("neptune"));
                ownedContainers.add(service.getDbCluster(source).getContainerId());
                api.createDBClusterSnapshot(request ->
                        request.dbClusterIdentifier(source).dbClusterSnapshotIdentifier(snapshot));
                assertFalse(service.getDbClusterSnapshot(snapshot).metadataOnly());
                assertNotNull(service.getDbClusterSnapshot(snapshot).archiveBase64());
                api.deleteDBCluster(request -> request.dbClusterIdentifier(source).skipFinalSnapshot(true));
                assertGone(ownedContainers.getFirst());
                api.restoreDBClusterFromSnapshot(request ->
                        request.dbClusterIdentifier(target).snapshotIdentifier(snapshot).engine("neptune"));
                NeptuneCluster restored = service.getDbCluster(target);
                ownedContainers.add(restored.getContainerId());
                Cluster connection = graph(restored);
                try {
                    assertEquals(Map.of("vertices", List.of(), "edges", List.of()), evaluate(connection.connect(), READ));
                } finally {
                    connection.close();
                }
            } finally {
                if (service.hasClusterSnapshot(snapshot)) {
                    api.deleteDBClusterSnapshot(request -> request.dbClusterSnapshotIdentifier(snapshot));
                }
                for (String id : List.of(target, source)) {
                    if (service.hasCluster(id)) {
                        api.deleteDBCluster(request -> request.dbClusterIdentifier(id).skipFinalSnapshot(true));
                    }
                }
                ownedContainers.forEach(this::assertGone);
            }
        }
    }

    @Test
    void corruptStoredArchiveRemovesTheRealPartialTargetAndPreservesTheSource() throws Exception {
        assumeDocker();
        String source = "neptune-corrupt-" + UUID.randomUUID().toString().substring(0, 10);
        String target = source + "-restored";
        String snapshotId = source + "-snapshot";
        String containerId = null;
        try (NeptuneClient api = api()) {
            try {
                api.createDBCluster(request -> request.dbClusterIdentifier(source).engine("neptune"));
                NeptuneCluster original = service.getDbCluster(source);
                containerId = original.getContainerId();
                Cluster connection = graph(original);
                try {
                    Client client = connection.connect();
                    evaluate(client, "g.addV('person').property(T.id,1L).property('name','retained').iterate();true");
                    api.createDBClusterSnapshot(request ->
                            request.dbClusterIdentifier(source).dbClusterSnapshotIdentifier(snapshotId));
                    NeptuneClusterSnapshot snapshot = service.getDbClusterSnapshot(snapshotId);
                    String damagedArchive = Base64.getEncoder().encodeToString(
                            (new String(Base64.getDecoder().decode(snapshot.archiveBase64()), StandardCharsets.UTF_8)
                                    + "\ninvalid-json\n").getBytes(StandardCharsets.UTF_8));
                    // Corruption of owned persisted data is a test fixture, not an archive-upload API.
                    NeptuneClusterSnapshot corrupted = new NeptuneClusterSnapshot(snapshot.dbClusterSnapshotIdentifier(),
                            snapshot.dbClusterSnapshotArn(), snapshot.dbClusterIdentifier(), snapshot.dbClusterArn(),
                            snapshot.engineVersion(), snapshot.iamDatabaseAuthenticationEnabled(), snapshot.clusterCreateTime(),
                            snapshot.snapshotCreateTime(), snapshot.settings(), snapshot.tags(), false, damagedArchive);
                    storageFactory.create("neptune", "neptune-cluster-snapshots.json",
                                    new TypeReference<Map<String, NeptuneClusterSnapshot>>() {})
                            .putForAccount("000000000000", "us-east-1/" + snapshotId, corrupted);
                    NeptuneException error = assertThrows(NeptuneException.class, () -> api.restoreDBClusterFromSnapshot(
                            request -> request.dbClusterIdentifier(target).snapshotIdentifier(snapshotId).engine("neptune")));
                    assertEquals(400, error.statusCode());
                    assertEquals("InvalidRestoreFault", error.awsErrorDetails().errorCode());
                    assertFalse(service.hasCluster(target));
                    assertEquals(damagedArchive, service.getDbClusterSnapshot(snapshotId).archiveBase64());
                    assertEquals("retained", evaluate(client, "g.V(1L).values('name').toList()"));
                    assertTrue(containers.getDockerClient().listContainersCmd().withShowAll(true)
                            .withNameFilter(List.of(target)).exec().isEmpty(), "The partially loaded target must be removed");
                } finally {
                    connection.close();
                }
            } finally {
                if (service.hasClusterSnapshot(snapshotId)) {
                    api.deleteDBClusterSnapshot(request -> request.dbClusterSnapshotIdentifier(snapshotId));
                }
                for (String id : List.of(target, source)) {
                    if (service.hasCluster(id)) {
                        api.deleteDBCluster(request -> request.dbClusterIdentifier(id).skipFinalSnapshot(true));
                    }
                }
                if (containerId != null) {
                    assertGone(containerId);
                }
            }
        }
    }

    private static Cluster graph(NeptuneCluster cluster) {
        return Cluster.build(cluster.getContainerHost()).port(cluster.getContainerPort())
                .serializer(new GraphSONMessageSerializerV3()).create();
    }

    private static Object evaluate(Client client, String script) throws Exception {
        return client.submit(script).all().get(30, TimeUnit.SECONDS).getFirst().getObject();
    }

    private void assertGone(String id) {
        assertThrows(NotFoundException.class, () -> containers.getDockerClient().inspectContainerCmd(id).exec());
    }

    private static NeptuneClient api() {
        return NeptuneClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("000000000000", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofMinutes(3)))
                .build();
    }
}
