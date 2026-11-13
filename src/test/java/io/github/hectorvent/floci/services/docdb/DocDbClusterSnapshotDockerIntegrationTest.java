package io.github.hectorvent.floci.services.docdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.testing.SidecarContainersProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.docdb.DocDbClient;
import software.amazon.awssdk.services.docdb.model.DBClusterSnapshot;
import software.amazon.awssdk.services.docdb.model.DocDbException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.RdsRequests.assumeDocker;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SDK control requests with real authenticated MongoDB documents and mongodump/mongorestore. */
@QuarkusTest
@Tag("docker")
@TestProfile(SidecarContainersProfile.class)
class DocDbClusterSnapshotDockerIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGINAL = """
            [{"_id":"alpha","counter":7,"active":true,"nested":{"city":"Oslo"},"labels":["a","b"]},
             {"_id":"beta","counter":12,"active":false,"nested":{"city":"Lima"},"labels":["c"]}]
            """;
    private static final String CHANGED = """
            [{"_id":"beta","counter":999,"active":false,"nested":{"city":"Lima"},"labels":["later"]},
             {"_id":"gamma","counter":3}]
            """;
    private static final String DOCUMENTS =
            "print(JSON.stringify(db.getSiblingDB('snapshot_test').documents.find().sort({_id:1}).toArray()))";
    private static final String ADMIN_DOCUMENTS =
            "print(JSON.stringify(db.getSiblingDB('admin').snapshot_notes.find().sort({_id:1}).toArray()))";

    @Inject
    DocDbService service;

    @Inject
    ContainerLifecycleManager containers;

    @Test
    void storedArchiveRestoresOriginalDocumentsAfterSourceMutationAndDeletion() throws Exception {
        assumeDocker();
        String source = "docdb-data-" + UUID.randomUUID().toString().substring(0, 12);
        String target = source + "-restored";
        String snapshot = source + "-snapshot";
        String secondSnapshot = source + "-second";
        List<String> ownedContainers = new ArrayList<>();
        try (DocDbClient client = client()) {
            try {
                client.createDBCluster(request -> request.dbClusterIdentifier(source).engine("docdb")
                        .masterUsername("archiveadmin").masterUserPassword("ArchiveOnlyPassword234!"));
                String sourceContainer = service.getDbCluster(source).getContainerId();
                assertNotNull(sourceContainer, "This test requires a real MongoDB backend");
                ownedContainers.add(sourceContainer);
                await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1))
                        .untilAsserted(() -> assertEquals(0L, mongo(sourceContainer,
                                "quit(db.hello().isWritablePrimary ? 0 : 3)").exitCode()));
                execute(sourceContainer, "const c=db.getSiblingDB('snapshot_test').documents;"
                        + "c.insertMany(" + ORIGINAL + ");c.createIndex({counter:1},{name:'by_counter'});"
                        + "const a=db.getSiblingDB('admin').snapshot_notes;"
                        + "a.insertOne({_id:'note',value:5});a.createIndex({value:1},{name:'by_value'});"
                        + "const d=db.getSiblingDB('snapshot_test');"
                        + "d.createRole({role:'archive_reader',privileges:[{resource:{db:'snapshot_test',"
                        + "collection:'documents'},actions:['find']}],roles:[]});"
                        + "d.createUser({user:'archive_reader',pwd:'ReaderOnlyPassword234!',"
                        + "roles:[{role:'archive_reader',db:'snapshot_test'}]});");
                assertEquals(JSON.readTree(ORIGINAL), documents(sourceContainer));
                assertEquals(JSON.readTree(ORIGINAL), readerDocuments(sourceContainer));

                DBClusterSnapshot created = client.createDBClusterSnapshot(request -> request
                        .dbClusterIdentifier(source).dbClusterSnapshotIdentifier(snapshot)).dbClusterSnapshot();
                assertEquals("available", created.status());
                assertFalse(service.getDbClusterSnapshot(snapshot).isMetadataOnly());
                assertTrue(service.getDbClusterSnapshot(snapshot).getSizeBytes() > 0);

                execute(sourceContainer, "const c=db.getSiblingDB('snapshot_test').documents;"
                        + "c.deleteOne({_id:'alpha'});"
                        + "c.updateOne({_id:'beta'},{$set:{counter:999,labels:['later']}});"
                        + "c.insertOne({_id:'gamma',counter:3});");
                assertEquals(JSON.readTree(CHANGED), documents(sourceContainer));
                client.deleteDBCluster(request -> request.dbClusterIdentifier(source).skipFinalSnapshot(true));
                assertGone(sourceContainer);

                client.restoreDBClusterFromSnapshot(request -> request.dbClusterIdentifier(target)
                        .snapshotIdentifier(created.dbClusterSnapshotArn()).engine("docdb"));
                String targetContainer = service.getDbCluster(target).getContainerId();
                assertNotNull(targetContainer);
                ownedContainers.add(targetContainer);
                assertNotEquals(sourceContainer, targetContainer);
                assertEquals(JSON.readTree(ORIGINAL), documents(targetContainer));
                assertTrue(JSON.readTree(execute(targetContainer,
                        "print(JSON.stringify(db.getSiblingDB('snapshot_test').documents.getIndexes()"
                                + ".map(index => index.name).includes('by_counter')))")).asBoolean());
                assertEquals(JSON.readTree("[{\"_id\":\"note\",\"value\":5}]"),
                        JSON.readTree(execute(targetContainer, ADMIN_DOCUMENTS)));
                assertTrue(JSON.readTree(execute(targetContainer,
                        "print(JSON.stringify(db.getSiblingDB('admin').snapshot_notes.getIndexes()"
                                + ".map(index => index.name).includes('by_value')))")).asBoolean());
                assertEquals(JSON.readTree(ORIGINAL), readerDocuments(targetContainer));

                // A fresh authenticated dump also proves the restored master credentials remain usable.
                assertEquals("available", client.createDBClusterSnapshot(request -> request
                        .dbClusterIdentifier(target).dbClusterSnapshotIdentifier(secondSnapshot))
                        .dbClusterSnapshot().status());
                client.deleteDBClusterSnapshot(request -> request.dbClusterSnapshotIdentifier(snapshot));
                assertEquals(JSON.readTree(ORIGINAL), documents(targetContainer));
            } finally {
                deleteSnapshot(client, secondSnapshot);
                deleteSnapshot(client, snapshot);
                deleteCluster(client, target);
                deleteCluster(client, source);
                for (String containerId : ownedContainers) {
                    assertGone(containerId);
                }
            }
        }
    }

    private JsonNode documents(String containerId) throws Exception {
        return JSON.readTree(execute(containerId, DOCUMENTS));
    }

    private JsonNode readerDocuments(String containerId) throws Exception {
        ContainerExec.Result result = ContainerExec.run(containers.getDockerClient(), containerId, new String[]{
                "mongosh", "--quiet", "--host", "127.0.0.1", "--port", "27017", "--username", "archive_reader",
                "--password", "ReaderOnlyPassword234!", "--authenticationDatabase", "snapshot_test",
                "--eval", DOCUMENTS}, 30);
        assertEquals(0L, result.exitCode(), result::summary);
        assertFalse(result.timedOut());
        return JSON.readTree(result.stdout());
    }

    private ContainerExec.Result mongo(String containerId, String script) {
        return ContainerExec.run(containers.getDockerClient(), containerId, new String[]{
                "bash", "-c", "exec mongosh --quiet --host 127.0.0.1 --port 27017 "
                + "--username \"$MONGO_INITDB_ROOT_USERNAME\" --password \"$MONGO_INITDB_ROOT_PASSWORD\" "
                + "--authenticationDatabase admin --eval \"$1\"", "snapshot-test", script}, 30);
    }

    private String execute(String containerId, String script) {
        ContainerExec.Result result = mongo(containerId, script);
        assertEquals(0L, result.exitCode(), result::summary);
        assertFalse(result.timedOut());
        return result.stdout().trim();
    }

    private void assertGone(String containerId) {
        assertThrows(NotFoundException.class, () -> containers.getDockerClient().inspectContainerCmd(containerId).exec());
    }

    private static DocDbClient client() {
        return DocDbClient.builder().endpointOverride(URI.create("http://localhost:" + RestAssured.port))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("000000000000", "test"))).build();
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
