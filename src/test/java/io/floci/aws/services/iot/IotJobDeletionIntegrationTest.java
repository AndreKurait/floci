package io.floci.aws.services.iot;

import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.IotException;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iotjobsdataplane.IotJobsDataPlaneClient;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IotJobDeletionIntegrationTest {
    private static final String ACCOUNT = "000000000000";
    @TestHTTPResource("/")
    URI baseUri;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void sdkForcedDeletionRemovesItsExecutionsAndPreservesOtherJobs() {
        try (IotClient client = AwsTestClients.create(IotClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            String thing = name();
            String arn = client.createThing(request -> request.thingName(thing)).thingArn();
            String job = name();
            String other = name();
            try {
                createJob(client, job, arn);
                createJob(client, other, arn);
                IotException refusal = assertThrows(IotException.class,
                        () -> client.deleteJob(request -> request.jobId(job)));
                assertEquals(409, refusal.statusCode());
                assertEquals("InvalidStateTransitionException", refusal.awsErrorDetails().errorCode());
                assertEquals(job, client.describeJob(request -> request.jobId(job)).job().jobId());
                assertEquals(200, client.deleteJob(request -> request.jobId(job).force(true)).sdkHttpResponse().statusCode());
                assertThrows(ResourceNotFoundException.class, () -> client.describeJob(request -> request.jobId(job)));
                assertThrows(ResourceNotFoundException.class, () -> client.deleteJob(request -> request.jobId(job).force(true)));
                assertEquals(List.of(other), client.listJobExecutionsForThing(request -> request.thingName(thing))
                        .executionSummaries().stream().map(item -> item.jobId()).toList());
                assertEquals(other, client.describeJob(request -> request.jobId(other)).job().jobId());
            } finally {
                deleteIfPresent(client, job);
                deleteIfPresent(client, other);
                client.deleteThing(request -> request.thingName(thing));
            }
        }
    }

    @Test
    void sdkSnapshotCompletesAfterItsFinalExecutionAndDeletesWithoutForce() {
        try (IotClient client = AwsTestClients.create(IotClient.builder(), ACCOUNT, Region.US_EAST_1);
                IotJobsDataPlaneClient data = AwsTestClients.create(IotJobsDataPlaneClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            String first = name();
            String second = name();
            String firstArn = client.createThing(request -> request.thingName(first)).thingArn();
            String secondArn = client.createThing(request -> request.thingName(second)).thingArn();
            String job = name();
            try {
                client.createJob(request -> request.jobId(job).targets(firstArn, secondArn).document("{}"));
                data.startNextPendingJobExecution(request -> request.thingName(first));
                data.updateJobExecution(request -> request.thingName(first).jobId(job).status("SUCCEEDED"));
                assertEquals("IN_PROGRESS", client.describeJob(request -> request.jobId(job)).job().statusAsString());
                assertEquals(409, assertThrows(IotException.class,
                        () -> client.deleteJob(request -> request.jobId(job))).statusCode());
                data.startNextPendingJobExecution(request -> request.thingName(second));
                data.updateJobExecution(request -> request.thingName(second).jobId(job).status("FAILED"));
                assertEquals("COMPLETED", client.describeJob(request -> request.jobId(job)).job().statusAsString());
                assertEquals(200, client.deleteJob(request -> request.jobId(job)).sdkHttpResponse().statusCode());
                assertThrows(ResourceNotFoundException.class, () -> client.describeJob(request -> request.jobId(job)));
                assertTrue(client.listJobExecutionsForThing(request -> request.thingName(first)).executionSummaries().isEmpty());
                assertTrue(client.listJobExecutionsForThing(request -> request.thingName(second)).executionSummaries().isEmpty());
            } finally {
                deleteIfPresent(client, job);
                client.deleteThing(request -> request.thingName(first));
                client.deleteThing(request -> request.thingName(second));
            }
        }
    }

    @Test
    void sdkDeletionIsIsolatedByAccountAndRegion() {
        try (IotClient own = AwsTestClients.create(IotClient.builder(), ACCOUNT, Region.US_EAST_1);
                IotClient account = AwsTestClients.create(IotClient.builder(), "111111111111", Region.US_EAST_1);
                IotClient region = AwsTestClients.create(IotClient.builder(), ACCOUNT, Region.US_WEST_2)) {
            String thing = name();
            String job = name();
            String arn = own.createThing(request -> request.thingName(thing)).thingArn();
            try {
                createJob(own, job, arn);
                for (IotClient foreign : List.of(account, region)) {
                    assertThrows(ResourceNotFoundException.class,
                            () -> foreign.deleteJob(request -> request.jobId(job).force(true)));
                }
                assertEquals(job, own.describeJob(request -> request.jobId(job)).job().jobId());
                own.deleteJob(request -> request.jobId(job).force(true));
            } finally {
                deleteIfPresent(own, job);
                own.deleteThing(request -> request.thingName(thing));
            }
        }
    }

    @Test
    void sdkIamGrantAndExplicitDenyUseTheActualJobArn() {
        try (IotClient root = AwsTestClients.create(IotClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            String thing = name();
            String arn = root.createThing(request -> request.thingName(thing)).thingArn();
            String allowed = name();
            String denied = name() + "_encoded";
            String other = name();
            try {
                for (String job : List.of(allowed, denied, other)) {
                    createJob(root, job, arn);
                }
                String user = name();
                String rootAuth = auth(ACCOUNT, "iam");
                String key = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), rootAuth, user).accessKeyId();
                String allowedArn = root.describeJob(request -> request.jobId(allowed)).job().jobArn();
                String deniedArn = root.describeJob(request -> request.jobId(denied)).job().jobArn();
                EnforcementFixtures.putUserPolicy(baseUri.getPort(), rootAuth, user, "delete-jobs", """
                        {"Version":"2012-10-17","Statement":[
                          {"Effect":"Allow","Action":"iot:DeleteJob","Resource":["%s","%s"]},
                          {"Effect":"Allow","Action":"iot:DeleteThing","Resource":"*"},
                          {"Effect":"Deny","Action":"iot:DeleteJob","Resource":"%s"}]}
                        """.formatted(allowedArn, deniedArn, deniedArn));
                try (IotClient scoped = AwsTestClients.create(IotClient.builder(), key, Region.US_EAST_1)) {
                    assertEquals(403, assertThrows(IotException.class,
                            () -> scoped.deleteJob(request -> request.jobId(other).force(true))).statusCode());
                    assertEquals(403, assertThrows(IotException.class,
                            () -> scoped.deleteJob(request -> request.jobId(denied).force(true))).statusCode());
                    given().header("Authorization", auth(key, "iot"))
                            .header("X-Amz-Target", "AWSIotService.DeleteThing")
                            .queryParam("Action", "DeleteThing").queryParam("force", true)
                            .urlEncodingEnabled(false).delete("/jobs/" + denied.replace("_", "%5F"))
                            .then().statusCode(403);
                    assertEquals(200, scoped.deleteJob(request -> request.jobId(allowed).force(true))
                            .sdkHttpResponse().statusCode());
                }
                assertEquals(denied, root.describeJob(request -> request.jobId(denied)).job().jobId());
                assertEquals(other, root.describeJob(request -> request.jobId(other)).job().jobId());
            } finally {
                for (String job : List.of(allowed, denied, other)) {
                    deleteIfPresent(root, job);
                }
                root.deleteThing(request -> request.thingName(thing));
            }
        }
    }

    @Test
    void malformedOptionsPreserveTheJobAndDecodedValidIdsReturnAnEmptyBody() {
        try (IotClient client = AwsTestClients.create(IotClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            String thing = name();
            String arn = client.createThing(request -> request.thingName(thing)).thingArn();
            String job = name() + "_encoded";
            String authorization = auth(ACCOUNT, "iot");
            try {
                createJob(client, job, arn);
                given().header("Authorization", authorization).queryParam("force", "wrong")
                        .delete("/jobs/" + job).then().statusCode(400);
                given().header("Authorization", authorization).queryParam("force", true).queryParam("namespaceId", "namespace")
                        .delete("/jobs/" + job).then().statusCode(400);
                assertEquals(job, client.describeJob(request -> request.jobId(job)).job().jobId());
                given().header("Authorization", authorization).queryParam("force", true).urlEncodingEnabled(false)
                        .delete("/jobs/" + job.replace("_", "%5F")).then().statusCode(200).body(emptyString());
                assertThrows(ResourceNotFoundException.class, () -> client.describeJob(request -> request.jobId(job)));
            } finally {
                deleteIfPresent(client, job);
                client.deleteThing(request -> request.thingName(thing));
            }
        }
    }

    private static String name() {
        return "delete-job-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void createJob(IotClient client, String job, String target) {
        client.createJob(request -> request.jobId(job).targets(target).document("{}"));
    }

    private static void deleteIfPresent(IotClient client, String job) {
        try {
            client.deleteJob(request -> request.jobId(job).force(true));
        } catch (ResourceNotFoundException expected) {
            // These tests deliberately delete the resource before verifying absence.
        }
    }
}
