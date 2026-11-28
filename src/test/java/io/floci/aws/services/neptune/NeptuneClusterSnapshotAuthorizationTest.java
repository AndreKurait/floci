package io.floci.aws.services.neptune;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.neptune.NeptuneClient;
import software.amazon.awssdk.services.neptune.model.NeptuneException;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Actual rds-signed Neptune SDK calls with a real IAM user's narrowly scoped grants. */
@QuarkusTest
@TestProfile(NeptuneClusterSnapshotAuthorizationTest.Profile.class)
class NeptuneClusterSnapshotAuthorizationTest {
    public static class Profile extends IamEnforcementProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.services.neptune.mock", "true");
            return overrides;
        }
    }

    @Inject
    NeptuneService service;

    @Test
    void scopedCreateReadRestoreAndDeleteCannotActOnAnotherClusterOrSnapshot() throws Exception {
        String prefix = "neptune-iam-" + UUID.randomUUID().toString().substring(0, 8);
        String source = prefix + "-source";
        String other = prefix + "-other";
        String snapshot = prefix + "-snapshot";
        String otherSnapshot = prefix + "-other-snapshot";
        String target = prefix + "-target";
        String user = prefix + "-user";
        String arn = "arn:aws:rds:us-east-1:000000000000:";
        StaticCredentialsProvider adminCredentials = credentials("000000000000", "test");
        try (NeptuneClient admin = client(adminCredentials);
             IamClient iam = IamClient.builder().endpointOverride(endpoint()).region(Region.US_EAST_1)
                     .credentialsProvider(adminCredentials).build()) {
            iam.createUser(request -> request.userName(user));
            AccessKey key = null;
            try {
                key = iam.createAccessKey(request -> request.userName(user)).accessKey();
                admin.createDBCluster(request -> request.dbClusterIdentifier(source).engine("neptune"));
                admin.createDBCluster(request -> request.dbClusterIdentifier(other).engine("neptune"));
                try (NeptuneClient scoped = client(credentials(key.accessKeyId(), key.secretAccessKey()))) {
                    policy(iam, user, "CreateDBClusterSnapshot", arn + "cluster:" + source);
                    denied(() -> scoped.createDBClusterSnapshot(request ->
                            request.dbClusterIdentifier(source).dbClusterSnapshotIdentifier(snapshot)));
                    assertFalse(service.hasClusterSnapshot(snapshot));
                    policy(iam, user, "CreateDBClusterSnapshot",
                            arn + "cluster:" + source, arn + "cluster-snapshot:" + snapshot);
                    denied(() -> scoped.createDBClusterSnapshot(request ->
                            request.dbClusterIdentifier(other).dbClusterSnapshotIdentifier(snapshot)));
                    assertFalse(service.hasClusterSnapshot(snapshot));
                    assertEquals(snapshot, scoped.createDBClusterSnapshot(request ->
                            request.dbClusterIdentifier(source).dbClusterSnapshotIdentifier(snapshot))
                            .dbClusterSnapshot().dbClusterSnapshotIdentifier());
                    admin.createDBClusterSnapshot(request ->
                            request.dbClusterIdentifier(other).dbClusterSnapshotIdentifier(otherSnapshot));

                    policy(iam, user, "DescribeDBClusterSnapshots", arn + "cluster-snapshot:" + snapshot);
                    assertEquals(source, scoped.describeDBClusterSnapshots(request ->
                            request.dbClusterSnapshotIdentifier(snapshot)).dbClusterSnapshots().getFirst()
                            .dbClusterIdentifier());
                    denied(() -> scoped.describeDBClusterSnapshots(request ->
                            request.dbClusterSnapshotIdentifier(otherSnapshot)));
                    denied(scoped::describeDBClusterSnapshots);

                    policy(iam, user, "RestoreDBClusterFromSnapshot",
                            arn + "cluster:" + target, arn + "cluster-snapshot:" + snapshot);
                    denied(() -> scoped.restoreDBClusterFromSnapshot(request ->
                            request.dbClusterIdentifier(target + "-other").snapshotIdentifier(snapshot).engine("neptune")));
                    denied(() -> scoped.restoreDBClusterFromSnapshot(request ->
                            request.dbClusterIdentifier(target).snapshotIdentifier(otherSnapshot).engine("neptune")));
                    assertFalse(service.hasCluster(target));
                    assertEquals(target, scoped.restoreDBClusterFromSnapshot(request ->
                            request.dbClusterIdentifier(target).snapshotIdentifier(snapshot).engine("neptune"))
                            .dbCluster().dbClusterIdentifier());

                    policy(iam, user, "DeleteDBClusterSnapshot", arn + "cluster-snapshot:" + snapshot);
                    denied(() -> scoped.deleteDBClusterSnapshot(request ->
                            request.dbClusterSnapshotIdentifier(otherSnapshot)));
                    assertTrue(service.hasClusterSnapshot(otherSnapshot));
                    assertEquals(snapshot, scoped.deleteDBClusterSnapshot(request ->
                            request.dbClusterSnapshotIdentifier(snapshot)).dbClusterSnapshot().dbClusterSnapshotIdentifier());
                    assertFalse(service.hasClusterSnapshot(snapshot));
                    assertTrue(service.hasCluster(source));
                    assertTrue(service.hasCluster(target));
                }
            } finally {
                for (String id : List.of(snapshot, otherSnapshot)) {
                    if (service.hasClusterSnapshot(id)) {
                        admin.deleteDBClusterSnapshot(request -> request.dbClusterSnapshotIdentifier(id));
                    }
                }
                for (String id : List.of(target, source, other)) {
                    if (service.hasCluster(id)) {
                        admin.deleteDBCluster(request -> request.dbClusterIdentifier(id).skipFinalSnapshot(true));
                    }
                }
                if (key != null) {
                    String accessKeyId = key.accessKeyId();
                    iam.deleteAccessKey(request -> request.userName(user).accessKeyId(accessKeyId));
                }
                for (String name : iam.listUserPolicies(request -> request.userName(user)).policyNames()) {
                    iam.deleteUserPolicy(request -> request.userName(user).policyName(name));
                }
                iam.deleteUser(request -> request.userName(user));
            }
        }
    }

    private static void denied(Runnable request) {
        NeptuneException error = assertThrows(NeptuneException.class, request::run);
        assertEquals(403, error.statusCode());
        assertEquals("AccessDenied", error.awsErrorDetails().errorCode());
    }

    private static void policy(IamClient iam, String user, String action, String... resources) throws Exception {
        String document = new ObjectMapper().writeValueAsString(Map.of("Version", "2012-10-17",
                "Statement", List.of(Map.of("Effect", "Allow", "Action", "rds:" + action, "Resource", resources))));
        iam.putUserPolicy(request -> request.userName(user).policyName("snapshot").policyDocument(document));
    }

    private static URI endpoint() {
        return URI.create("http://localhost:" + RestAssured.port);
    }

    private static StaticCredentialsProvider credentials(String accessKey, String secret) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret));
    }

    private static NeptuneClient client(StaticCredentialsProvider credentials) {
        return NeptuneClient.builder().endpointOverride(endpoint()).region(Region.US_EAST_1)
                .credentialsProvider(credentials).build();
    }
}
