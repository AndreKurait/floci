package io.floci.aws.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.iam.IamService;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CloudWatchLogsException;
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateExportTaskRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.ExportTask;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudWatchLogsExportIamIntegrationTest {
    private static final String ACCOUNT = "147025836914";
    private static final Region REGION = Region.US_EAST_1;
    private static final ObjectMapper JSON = new ObjectMapper();
    @Inject
    IamService iamService;

    @Test
    void callerOnlyPutObjectAndCallerConditionsStaySeparateFromServiceAclAndSourceFacts() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.allow(Map.of("Bool", Map.of("aws:PrincipalIsAWSService", "false"),
                    "Null", Map.of("aws:SourceArn", "true", "aws:SourceAccount", "true",
                            "aws:PrincipalServiceName", "true", "s3:x-amz-acl", "true")), false);
            fixture.export();
        }
    }

    @Test
    void serviceReadHasNoWriteAclConditionAndServiceWriteCarriesItsRequiredAcl() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.allow(null, false);
            List<Object> statements = List.of(
                    Map.of("Effect", "Allow", "Principal", Map.of("Service", "logs.us-east-1.amazonaws.com"),
                            "Action", "s3:GetBucketAcl", "Resource", fixture.bucketArn,
                            "Condition", Map.of("StringEquals", Map.of("aws:SourceAccount", ACCOUNT),
                                    "ArnLike", Map.of("aws:SourceArn", fixture.groupArn),
                                    "Null", Map.of("s3:x-amz-acl", "true"))),
                    Map.of("Effect", "Allow", "Principal", Map.of("Service", "logs.us-east-1.amazonaws.com"),
                            "Action", "s3:PutObject", "Resource", fixture.bucketArn + "/*",
                            "Condition", Map.of("StringEquals", Map.of("aws:SourceAccount", ACCOUNT,
                                            "s3:x-amz-acl", "bucket-owner-full-control"),
                                    "ArnLike", Map.of("aws:SourceArn", fixture.groupArn))));
            fixture.s3.putBucketPolicy(request -> request.bucket(fixture.bucket).policy(json(statements)));
            fixture.export();
        }
    }

    @Test
    void missingCallerWritePermissionDeniesBeforeTaskPublicationThenExplicitAllowWorks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.policy(List.of(fixture.sourceAllow(fixture.groupArn, null)));
            fixture.denied();
            fixture.allow(null, false);
            fixture.export();
        }
    }

    @Test
    void exactSourceArnAndLiveSourceTagsControlAdmission() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.policy(List.of(fixture.sourceAllow(fixture.groupArn + "-other", null), fixture.objectAllow(null)));
            fixture.denied();
            fixture.allow(Map.of("StringEquals", Map.of("aws:ResourceTag/team", "blue")), true);
            fixture.root.tagLogGroup(request -> request.logGroupName(fixture.group).tags(Map.of("team", "red")));
            fixture.denied();
            fixture.allow(Map.of("StringEquals", Map.of("aws:ResourceTag/team", "red")), true);
            fixture.export();
        }
    }

    @Test
    void suffixedSourceArnStillHonorsAnExplicitBareArnDeny() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.policy(List.of(fixture.sourceAllow(fixture.groupArn + ":*", null), fixture.objectAllow(null),
                    Map.of("Effect", "Deny", "Action", "logs:CreateExportTask", "Resource", fixture.groupArn)));
            fixture.denied();
            fixture.policy(List.of(fixture.sourceAllow(fixture.groupArn + ":*", null), fixture.objectAllow(null)));
            fixture.export();
        }
    }

    @Test
    void liveBucketCallerDenyIsEvaluatedAlongsideTheIndependentServiceAllow() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.allow(null, false);
            List<Object> policy = new ArrayList<>(fixture.serviceStatements());
            policy.add(Map.of("Effect", "Deny", "Principal", Map.of("AWS", fixture.userArn),
                    "Action", "s3:PutObject", "Resource", fixture.bucketArn + "/*"));
            fixture.s3.putBucketPolicy(request -> request.bucket(fixture.bucket).policy(json(policy)));
            fixture.denied();
            fixture.s3.putBucketPolicy(request -> request.bucket(fixture.bucket).policy(json(fixture.serviceStatements())));
            fixture.export();
        }
    }

    @Test
    void permissionsBoundaryAlsoCapsTheAdditionalCallerWrite() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.allow(null, false);
            String boundary = fixture.iam.createPolicy(request -> request.policyName("boundary-" + fixture.marker)
                    .policyDocument(json(List.of(fixture.sourceAllow(fixture.groupArn, null))))).policy().arn();
            try {
                fixture.iam.putUserPermissionsBoundary(request -> request.userName(fixture.userName)
                        .permissionsBoundary(boundary));
                fixture.denied();
            } finally {
                fixture.iam.deleteUserPermissionsBoundary(request -> request.userName(fixture.userName));
                fixture.iam.deletePolicy(request -> request.policyArn(boundary));
            }
            fixture.export();
        }
    }

    @Test
    void nativeStsSessionPolicyAlsoCapsTheAdditionalCallerWrite() throws Exception {
        try (Fixture fixture = new Fixture();
                StsClient sts = AwsTestClients.create(StsClient.builder(), ACCOUNT, REGION)) {
            fixture.allow(null, false);
            String name = "export-session-" + fixture.marker;
            String arn = fixture.iam.createRole(request -> request.roleName(name).assumeRolePolicyDocument(json(List.of(
                    Map.of("Effect", "Allow", "Principal", Map.of("AWS", "arn:aws:iam::" + ACCOUNT + ":root"),
                            "Action", "sts:AssumeRole"))))).role().arn();
            List<String> sessions = new ArrayList<>();
            try {
                fixture.iam.putRolePolicy(request -> request.roleName(name).policyName("export")
                        .policyDocument(json(List.of(fixture.sourceAllow(fixture.groupArn, null), fixture.objectAllow(null)))));
                Credentials deniedSession = sts.assumeRole(request -> request.roleArn(arn).roleSessionName("limited")
                        .policy(json(List.of(fixture.sourceAllow(fixture.groupArn, null))))).credentials();
                sessions.add(deniedSession.accessKeyId());
                try (CloudWatchLogsClient caller = sessionClient(deniedSession)) {
                    fixture.denied(caller);
                }
                Credentials allowedSession = sts.assumeRole(request -> request.roleArn(arn).roleSessionName("allowed")
                        .policy(json(List.of(fixture.sourceAllow(fixture.groupArn, null), fixture.objectAllow(null))))).credentials();
                sessions.add(allowedSession.accessKeyId());
                try (CloudWatchLogsClient caller = sessionClient(allowedSession)) {
                    fixture.export(caller);
                }
            } finally {
                sessions.forEach(key -> iamService.unregisterSession(ACCOUNT, key));
                fixture.iam.deleteRolePolicy(request -> request.roleName(name).policyName("export"));
                fixture.iam.deleteRole(request -> request.roleName(name));
            }
        }
    }

    private static CloudWatchLogsClient sessionClient(Credentials credentials) {
        return AwsTestClients.create(CloudWatchLogsClient.builder(), AwsSessionCredentials.create(
                credentials.accessKeyId(), credentials.secretAccessKey(), credentials.sessionToken()),
                REGION, URI.create("http://localhost:" + RestAssured.port));
    }

    private static String json(List<?> statements) {
        try {
            return JSON.writeValueAsString(Map.of("Version", "2012-10-17", "Statement", statements));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final String marker = UUID.randomUUID().toString();
        private final String group = "/audit/export-iam/" + marker;
        private final String groupArn = "arn:aws:logs:us-east-1:" + ACCOUNT + ":log-group:" + group;
        private final String bucket = "export-iam-" + marker;
        private final String bucketArn = "arn:aws:s3:::" + bucket;
        private final String userName = "export-iam-" + marker;
        private final CloudWatchLogsClient root = AwsTestClients.create(CloudWatchLogsClient.builder(), ACCOUNT, REGION);
        private final IamClient iam = AwsTestClients.create(IamClient.builder(), ACCOUNT, REGION);
        private final S3Client s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), ACCOUNT, REGION);
        private final String userArn;
        private final AccessKey key;
        private final CloudWatchLogsClient caller;
        private final long eventTime;
        private final List<String> taskIds = new ArrayList<>();

        private Fixture() {
            userArn = iam.createUser(request -> request.userName(userName)).user().arn();
            key = iam.createAccessKey(request -> request.userName(userName)).accessKey();
            caller = AwsTestClients.create(CloudWatchLogsClient.builder(), key.accessKeyId(), key.secretAccessKey(), REGION);
            root.createLogGroup(request -> request.logGroupName(group).tags(Map.of("OwnedExport", marker, "team", "blue")));
            root.createLogStream(request -> request.logGroupName(group).logStreamName("stream"));
            eventTime = System.currentTimeMillis();
            root.putLogEvents(request -> request.logGroupName(group).logStreamName("stream")
                    .logEvents(InputLogEvent.builder().timestamp(eventTime).message("owned IAM export " + marker).build()));
            s3.createBucket(request -> request.bucket(bucket));
            s3.putBucketTagging(request -> request.bucket(bucket).tagging(Tagging.builder()
                    .tagSet(Tag.builder().key("OwnedExport").value(marker).build()).build()));
            s3.putBucketPolicy(request -> request.bucket(bucket).policy(json(serviceStatements())));
        }

        private List<Object> serviceStatements() {
            return List.of(
                    Map.of("Effect", "Allow", "Principal", Map.of("Service", "logs.us-east-1.amazonaws.com"),
                            "Action", "s3:GetBucketAcl", "Resource", bucketArn,
                            "Condition", Map.of("StringEquals", Map.of("aws:SourceAccount", ACCOUNT),
                                    "ArnLike", Map.of("aws:SourceArn", groupArn))),
                    Map.of("Effect", "Allow", "Principal", Map.of("Service", "logs.us-east-1.amazonaws.com"),
                            "Action", "s3:PutObject", "Resource", bucketArn + "/*",
                            "Condition", Map.of("StringEquals", Map.of("aws:SourceAccount", ACCOUNT,
                                            "s3:x-amz-acl", "bucket-owner-full-control"),
                                    "ArnLike", Map.of("aws:SourceArn", groupArn))));
        }

        private Map<String, Object> sourceAllow(String arn, Map<String, Object> conditions) {
            return statement("logs:CreateExportTask", arn, conditions);
        }

        private Map<String, Object> objectAllow(Map<String, Object> conditions) {
            return statement("s3:PutObject", bucketArn + "/prefix/*", conditions);
        }

        private Map<String, Object> statement(String action, String resource, Map<String, Object> conditions) {
            return conditions == null
                    ? Map.of("Effect", "Allow", "Action", action, "Resource", resource)
                    : Map.of("Effect", "Allow", "Action", action, "Resource", resource, "Condition", conditions);
        }

        private void allow(Map<String, Object> conditions, boolean sourceConditions) {
            policy(List.of(sourceAllow(groupArn, sourceConditions ? conditions : null),
                    objectAllow(sourceConditions ? null : conditions)));
        }

        private void policy(List<?> statements) {
            iam.putUserPolicy(request -> request.userName(userName).policyName("export").policyDocument(json(statements)));
        }

        private CreateExportTaskRequest request() {
            return CreateExportTaskRequest.builder().logGroupName(group).destination(bucket)
                    .destinationPrefix("prefix").taskName(marker).from(0L).to(eventTime + 1000).build();
        }

        private void denied() {
            denied(caller);
        }

        private void denied(CloudWatchLogsClient client) {
            CloudWatchLogsException denied = assertThrows(CloudWatchLogsException.class, () -> client.createExportTask(request()));
            assertTrue(denied.awsErrorDetails().errorCode().contains("AccessDenied"));
            assertTrue(root.describeExportTasks().exportTasks().stream().noneMatch(task -> group.equals(task.logGroupName())));
            assertTrue(s3.listObjectsV2(request -> request.bucket(bucket)).contents().isEmpty());
        }

        private void export() throws Exception {
            export(caller);
        }

        private void export(CloudWatchLogsClient client) throws Exception {
            String id = client.createExportTask(request()).taskId();
            taskIds.add(id);
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20)).untilAsserted(() -> {
                ExportTask task = root.describeExportTasks(request -> request.taskId(id)).exportTasks().getFirst();
                assertEquals("COMPLETED", task.status().codeAsString(), task.status().message());
            });
            List<S3Object> objects = s3.listObjectsV2(request -> request.bucket(bucket)).contents();
            assertFalse(objects.isEmpty());
            for (S3Object object : objects) {
                assertTrue(object.key().startsWith("prefix/" + id + "/"));
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(
                        s3.getObjectAsBytes(request -> request.bucket(bucket).key(object.key())).asByteArray()))) {
                    assertTrue(new String(gzip.readAllBytes(), StandardCharsets.UTF_8).contains("owned IAM export " + marker));
                }
            }
        }

        @Override
        public void close() {
            try {
                for (String id : taskIds) {
                    ExportTask task = root.describeExportTasks(request -> request.taskId(id)).exportTasks().getFirst();
                    assertEquals(group, task.logGroupName());
                    assertEquals(bucket, task.destination());
                    if (!Set.of("COMPLETED", "FAILED", "CANCELLED").contains(task.status().codeAsString())) {
                        root.cancelExportTask(request -> request.taskId(id));
                        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertEquals("CANCELLED",
                                root.describeExportTasks(request -> request.taskId(id)).exportTasks().getFirst().status().codeAsString()));
                    }
                }
                assertEquals(marker, root.listTagsLogGroup(request -> request.logGroupName(group)).tags().get("OwnedExport"));
                root.deleteLogGroup(request -> request.logGroupName(group));
                assertTrue(root.describeLogGroups(request -> request.logGroupNamePrefix(group)).logGroups().isEmpty());
                assertEquals(marker, s3.getBucketTagging(request -> request.bucket(bucket)).tagSet().getFirst().value());
                for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket)).contents()) {
                    assertTrue(taskIds.stream().anyMatch(id -> object.key().startsWith("prefix/" + id + "/")));
                    assertEquals(object.eTag(), s3.headObject(request -> request.bucket(bucket).key(object.key())).eTag());
                    s3.deleteObject(request -> request.bucket(bucket).key(object.key()));
                }
                assertTrue(s3.listObjectsV2(request -> request.bucket(bucket)).contents().isEmpty());
                s3.deleteBucket(request -> request.bucket(bucket));
                iam.deleteUserPolicy(request -> request.userName(userName).policyName("export"));
                iam.deleteAccessKey(request -> request.userName(userName).accessKeyId(key.accessKeyId()));
                iam.deleteUser(request -> request.userName(userName));
            } finally {
                caller.close();
                root.close();
                s3.close();
                iam.close();
            }
        }
    }
}
