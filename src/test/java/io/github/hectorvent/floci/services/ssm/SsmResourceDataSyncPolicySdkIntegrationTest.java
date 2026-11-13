package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.github.hectorvent.floci.testing.S3IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.InventoryItem;
import software.amazon.awssdk.services.ssm.model.ResourceDataSyncItem;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(S3IamEnforcementProfile.class)
class SsmResourceDataSyncPolicySdkIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(830022230000L);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String SYNC_NAME = "central-inventory";

    @BeforeAll
    static void configureJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({
        "us-east-1,us-west-2,aws",
        "us-gov-west-1,us-gov-east-1,aws-us-gov"
    })
    void bucketPolicyControlsCrossAccountDeliveryAndRepairsRetryAutomatically(
            String sourceRegion, String bucketRegion, String partition) throws Exception {
        String sourceAccount = Long.toString(ACCOUNTS.incrementAndGet());
        String bucketAccount = Long.toString(ACCOUNTS.incrementAndGet());
        String bucket = "inventory-policy-" + UUID.randomUUID();
        String instance = "i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        String key = "reports/AWS:Application/accountid=" + sourceAccount + "/region=" + sourceRegion
                + "/resourcetype=ManagedInstanceInventory/" + instance + ".json";
        try (Identity source = new Identity(sourceAccount, sourceRegion);
             Identity owner = new Identity(bucketAccount, bucketRegion);
             SsmClient ssm = source.ssm(sourceRegion);
             SsmClient foreign = owner.ssm(sourceRegion);
             S3Client s3 = owner.s3(bucketRegion)) {
            register(sourceAccount, sourceRegion, instance);
            register(bucketAccount, sourceRegion, instance);
            s3.createBucket(request -> request.bucket(bucket).createBucketConfiguration(
                    configuration -> configuration.locationConstraint(bucketRegion)));
            boolean created = false;
            try {
                upload(ssm, instance, "source-report");
                upload(foreign, instance, "foreign-report");
                ssm.createResourceDataSync(request -> request.syncName(SYNC_NAME).s3Destination(destination ->
                        destination.bucketName(bucket).region(bucketRegion).prefix("reports").syncFormat("JsonSerDe")));
                created = true;
                ResourceDataSyncItem denied = awaitState(ssm, "Failed", "s3:GetBucketAcl");
                assertNull(denied.lastSuccessfulSyncTime());
                assertTrue(s3.listObjectsV2(request -> request.bucket(bucket)).contents().isEmpty());

                ObjectNode wrongSource = policy(bucket, sourceAccount, sourceRegion, partition);
                wrongSource.withArray("/Statement").get(1).withObject("/Condition/StringEquals")
                        .put("aws:SourceAccount", bucketAccount);
                s3.putBucketPolicy(request -> request.bucket(bucket).policy(wrongSource.toString()));
                awaitState(ssm, "Failed", "s3:PutObject");
                assertTrue(s3.listObjectsV2(request -> request.bucket(bucket)).contents().isEmpty());

                ObjectNode correct = policy(bucket, sourceAccount, sourceRegion, partition);
                s3.putBucketPolicy(request -> request.bucket(bucket).policy(correct.toString()));
                ResourceDataSyncItem successful = awaitState(ssm, "Successful", "successfully");
                Instant lastSuccess = successful.lastSuccessfulSyncTime();
                assertNotNull(lastSuccess);
                String original = s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asUtf8String();
                assertTrue(original.contains("source-report"));
                assertEquals(1, s3.listObjectsV2(request -> request.bucket(bucket)).contents().size(),
                        "The bucket must not receive another account's report for the same instance ID.");
                String ownerId = s3.getBucketAcl(request -> request.bucket(bucket)).owner().id();
                assertTrue(s3.getObjectAcl(request -> request.bucket(bucket).key(key)).grants().stream()
                        .anyMatch(grant -> ownerId.equals(grant.grantee().id())
                                && "FULL_CONTROL".equals(grant.permissionAsString())));

                ObjectNode explicitDeny = correct.deepCopy();
                ((ObjectNode) explicitDeny.withArray("/Statement").get(1)).put("Effect", "Deny");
                s3.putBucketPolicy(request -> request.bucket(bucket).policy(explicitDeny.toString()));
                upload(ssm, instance, "changed-report");
                ResourceDataSyncItem failedUpdate = awaitState(ssm, "Failed", "s3:PutObject");
                assertEquals(lastSuccess, failedUpdate.lastSuccessfulSyncTime());
                assertEquals(original, s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asUtf8String());

                ObjectNode wrongSignature = correct.deepCopy();
                wrongSignature.withArray("/Statement").get(2).withObject("/Condition/StringNotEquals")
                        .put("s3:signatureversion", "AWS");
                s3.putBucketPolicy(request -> request.bucket(bucket).policy(wrongSignature.toString()));
                ResourceDataSyncItem signatureDenied = awaitState(ssm, "Failed", "s3:GetBucketAcl");
                assertEquals(lastSuccess, signatureDenied.lastSuccessfulSyncTime());
                assertEquals(original, s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asUtf8String());

                s3.putBucketPolicy(request -> request.bucket(bucket).policy(correct.toString()));
                awaitState(ssm, "Successful", "successfully");
                assertTrue(s3.getObjectAsBytes(request -> request.bucket(bucket).key(key))
                        .asUtf8String().contains("changed-report"));
                ssm.deleteResourceDataSync(request -> request.syncName(SYNC_NAME));
                created = false;
                assertNotNull(s3.headObject(request -> request.bucket(bucket).key(key)).eTag());
            } finally {
                try {
                    if (created) {
                        ssm.deleteResourceDataSync(request -> request.syncName(SYNC_NAME));
                    }
                } finally {
                    for (S3Object object : s3.listObjectsV2(request -> request.bucket(bucket)).contents()) {
                        s3.deleteObject(request -> request.bucket(bucket).key(object.key()));
                    }
                    s3.deleteBucketPolicy(request -> request.bucket(bucket));
                    s3.deleteBucket(request -> request.bucket(bucket));
                }
            }
        }
    }

    private static ResourceDataSyncItem awaitState(SsmClient client, String status, String message) {
        AtomicReference<ResourceDataSyncItem> observed = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            ResourceDataSyncItem item = client.listResourceDataSync().resourceDataSyncItems().getFirst();
            assertEquals(status, item.lastStatusAsString(), item::toString);
            assertTrue(item.lastSyncStatusMessage().contains(message), item::toString);
            observed.set(item);
        });
        return observed.get();
    }

    private static ObjectNode policy(String bucket, String account, String region, String partition) {
        ObjectNode policy = JSON.objectNode().put("Version", "2012-10-17");
        ObjectNode read = policy.putArray("Statement").addObject().put("Effect", "Allow")
                .put("Action", "s3:GetBucketAcl").put("Resource", "arn:" + partition + ":s3:::" + bucket);
        read.putObject("Principal").put("Service", "ssm.amazonaws.com");
        ObjectNode write = policy.withArray("/Statement").addObject().put("Effect", "Allow")
                .put("Action", "s3:PutObject")
                .put("Resource", "arn:" + partition + ":s3:::" + bucket + "/reports/*/accountid=" + account + "/*");
        write.putObject("Principal").put("Service", "ssm.amazonaws.com");
        ObjectNode conditions = write.putObject("Condition");
        conditions.putObject("StringEquals").put("aws:SourceAccount", account)
                .put("s3:x-amz-acl", "bucket-owner-full-control");
        conditions.putObject("ArnLike").put("aws:SourceArn",
                "arn:" + partition + ":ssm:" + region + ":" + account + ":resource-data-sync/" + SYNC_NAME);
        conditions.putObject("Null").put("aws:SecureTransport", "true").put("s3:TlsVersion", "true")
                .put("aws:SourceIp", "true").put("aws:VpcSourceIp", "true");
        ObjectNode signature = policy.withArray("/Statement").addObject().put("Effect", "Deny")
                .put("Action", "s3:*");
        signature.putArray("Resource").add("arn:" + partition + ":s3:::" + bucket)
                .add("arn:" + partition + ":s3:::" + bucket + "/*");
        signature.putObject("Principal").put("Service", "ssm.amazonaws.com");
        signature.putObject("Condition").putObject("StringNotEquals")
                .put("s3:signatureversion", "AWS4-HMAC-SHA256");
        ObjectNode transport = policy.withArray("/Statement").addObject().put("Effect", "Deny")
                .put("Action", "s3:*");
        transport.set("Resource", signature.get("Resource").deepCopy());
        transport.putObject("Principal").put("Service", "ssm.amazonaws.com");
        transport.putObject("Condition").putObject("Bool").put("aws:SecureTransport", "false");
        return policy;
    }

    private static void upload(SsmClient client, String instance, String name) {
        client.putInventory(request -> request.instanceId(instance)
                .items(InventoryItem.builder().typeName("AWS:Application").schemaVersion("1.1")
                        .captureTime("2026-01-02T03:04:05Z").content(Map.of("Name", name)).build()));
    }

    private static void register(String account, String region, String instance) {
        given().header("X-Amz-Target", "AmazonSSM.UpdateInstanceInformation")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20260102/" + region
                        + "/ssm/aws4_request, SignedHeaders=host, Signature=example")
                .contentType("application/x-amz-json-1.1")
                .body(Map.of("InstanceId", instance, "AgentName", "amazon-ssm-agent",
                        "PlatformType", "Linux", "PlatformName", "Example Linux", "AgentVersion", "3.3.0"))
                .post("/").then().statusCode(200);
    }

    private static final class Identity implements AutoCloseable {
        private final String name = "inventory-sync-" + UUID.randomUUID();
        private final int port = RestAssured.port;
        private final String authorization;
        private final IamClient root;
        private final AccessKey key;

        private Identity(String account, String region) {
            authorization = EnforcementFixtures.auth(account, region, "iam");
            root = IamClient.builder().endpointOverride(endpoint()).region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account, "test")))
                    .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
            boolean created = false;
            try {
                root.createUser(request -> request.userName(name));
                created = true;
                root.putUserPolicy(request -> request.userName(name).policyName("admin").policyDocument("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"*","Resource":"*"}]}
                        """));
                key = root.createAccessKey(request -> request.userName(name)).accessKey();
            } catch (RuntimeException | Error failure) {
                try {
                    if (created) {
                        EnforcementFixtures.removeUser(port, authorization, name);
                    }
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                } finally {
                    root.close();
                }
                throw failure;
            }
        }

        private SsmClient ssm(String region) {
            return SsmClient.builder().endpointOverride(endpoint()).region(Region.of(region))
                    .credentialsProvider(credentials()).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        }

        private S3Client s3(String region) {
            return S3Client.builder().endpointOverride(endpoint()).region(Region.of(region)).forcePathStyle(true)
                    .credentialsProvider(credentials()).httpClientBuilder(UrlConnectionHttpClient.builder()).build();
        }

        private StaticCredentialsProvider credentials() {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(key.accessKeyId(), key.secretAccessKey()));
        }

        @Override
        public void close() {
            try {
                EnforcementFixtures.removeUser(port, authorization, name);
            } finally {
                root.close();
            }
        }

        private static URI endpoint() {
            return URI.create("http://localhost:" + RestAssured.port);
        }
    }
}
