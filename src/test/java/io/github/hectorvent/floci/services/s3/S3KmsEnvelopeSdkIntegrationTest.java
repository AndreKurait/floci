package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KmsException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3KmsEnvelopeSdkIntegrationTest {

    @TestHTTPResource("/")
    URI endpoint;
    @Inject
    IamService iam;
    private S3Client s3;
    private S3Client caller;
    private KmsClient kms;
    private String bucket;
    private String keyArn;
    private String user;
    private AccessKey access;

    @BeforeEach
    void setup() {
        s3 = AwsTestClients.create(S3Client.builder().forcePathStyle(true), "test", Region.US_EAST_1, endpoint);
        kms = AwsTestClients.create(KmsClient.builder(), "test", Region.US_EAST_1, endpoint);
        bucket = "kms-envelope-" + UUID.randomUUID();
        s3.createBucket(request -> request.bucket(bucket));
        keyArn = kms.createKey().keyMetadata().arn();
        user = "S3Envelope" + UUID.randomUUID();
        iam.createUser(user, "/");
        access = iam.createAccessKey(user);
        iam.putUserPolicy(user, "Objects", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"s3:*","Resource":"*"}
                ]}
                """);
        caller = AwsTestClients.create(S3Client.builder().forcePathStyle(true),
                access.getAccessKeyId(), access.getSecretAccessKey(), Region.US_EAST_1, endpoint);
    }

    @AfterEach
    void cleanup() {
        try {
            s3.listMultipartUploads(request -> request.bucket(bucket)).uploads().forEach(upload ->
                    s3.abortMultipartUpload(request -> request.bucket(bucket).key(upload.key())
                            .uploadId(upload.uploadId())));
            s3.listObjectsV2(request -> request.bucket(bucket)).contents().forEach(object ->
                    s3.deleteObject(request -> request.bucket(bucket).key(object.key())));
            s3.deleteBucket(request -> request.bucket(bucket));
            iam.deleteUserPolicy(user, "Objects");
            iam.deleteAccessKey(user, access.getAccessKeyId());
            iam.deleteUser(user);
            kms.scheduleKeyDeletion(request -> request.keyId(keyArn).pendingWindowInDays(7));
        } finally {
            caller.close();
            s3.close();
            kms.close();
        }
    }

    @Test
    void putRequiresAnExistingKmsKey() {
        assertThrows(S3Exception.class, () -> s3.putObject(request -> request.bucket(bucket).key("missing")
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(UUID.randomUUID().toString()),
                RequestBody.fromString("must not be stored")));
        assertEquals(0, s3.listObjectsV2(request -> request.bucket(bucket)).keyCount());
    }

    @Test
    void putRequiresGenerateDataKeyPermission() {
        S3Exception denied = assertThrows(S3Exception.class, () -> caller.putObject(request ->
                        request.bucket(bucket).key("denied").serverSideEncryption(ServerSideEncryption.AWS_KMS)
                                .ssekmsKeyId(keyArn), RequestBody.fromString("must not be stored")));
        assertEquals(403, denied.statusCode());
        assertEquals(0, s3.listObjectsV2(request -> request.bucket(bucket)).keyCount());
    }

    @Test
    void getRequiresDecryptPermission() {
        put("private");
        S3Exception denied = assertThrows(S3Exception.class, () ->
                caller.getObjectAsBytes(request -> request.bucket(bucket).key("private")));
        assertEquals(403, denied.statusCode());
    }

    @Test
    void disablingTheWrappingKeyPreventsReadingTheObject() {
        put("disabled");
        kms.disableKey(request -> request.keyId(keyArn));
        assertThrows(S3Exception.class, () ->
                s3.getObjectAsBytes(request -> request.bucket(bucket).key("disabled")));
    }

    @Test
    void multipartInitiationRequiresGenerateDataKeyPermission() {
        S3Exception denied = assertThrows(S3Exception.class, () -> caller.createMultipartUpload(request ->
                request.bucket(bucket).key("multipart").serverSideEncryption(ServerSideEncryption.AWS_KMS)
                        .ssekmsKeyId(keyArn)));
        assertEquals(403, denied.statusCode());
        assertEquals(0, s3.listMultipartUploads(request -> request.bucket(bucket)).uploads().size());
    }

    @Test
    void authorizedCallerRoundTripsAndCopiesEncryptedRanges() {
        iam.putUserPolicy(user, "Objects", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:*","kms:GenerateDataKey","kms:Decrypt"],"Resource":"*"}
                ]}
                """);
        byte[] body = new byte[150_000];
        new Random(24).nextBytes(body);
        caller.putObject(request -> request.bucket(bucket).key("roundtrip")
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(keyArn),
                RequestBody.fromBytes(body));
        assertArrayEquals(body, caller.getObjectAsBytes(request -> request.bucket(bucket).key("roundtrip")).asByteArray());
        assertArrayEquals(Arrays.copyOfRange(body, 65_530, 65_560),
                caller.getObjectAsBytes(request -> request.bucket(bucket).key("roundtrip")
                        .range("bytes=65530-65559")).asByteArray());
        caller.copyObject(request -> request.copySource(bucket + "/roundtrip").destinationBucket(bucket)
                .destinationKey("copy").serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(keyArn));
        assertArrayEquals(body, caller.getObjectAsBytes(request -> request.bucket(bucket).key("copy")).asByteArray());
    }

    @Test
    void multipartReplacementCompletesAndAbortRemovesUpload() {
        String upload = s3.createMultipartUpload(request -> request.bucket(bucket).key("parts")
                .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(keyArn)).uploadId();
        s3.uploadPart(request -> request.bucket(bucket).key("parts").uploadId(upload).partNumber(1),
                RequestBody.fromString("old part"));
        String tag = s3.uploadPart(request -> request.bucket(bucket).key("parts").uploadId(upload).partNumber(1),
                RequestBody.fromString("replacement")).eTag();
        s3.completeMultipartUpload(request -> request.bucket(bucket).key("parts").uploadId(upload)
                .multipartUpload(parts -> parts.parts(part -> part.partNumber(1).eTag(tag))));
        assertEquals("replacement", s3.getObjectAsBytes(request -> request.bucket(bucket).key("parts")).asUtf8String());
        String abort = s3.createMultipartUpload(request -> request.bucket(bucket).key("abort")
                .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(keyArn)).uploadId();
        s3.uploadPart(request -> request.bucket(bucket).key("abort").uploadId(abort).partNumber(1),
                RequestBody.fromString("discarded"));
        s3.abortMultipartUpload(request -> request.bucket(bucket).key("abort").uploadId(abort));
        assertEquals(0, s3.listMultipartUploads(request -> request.bucket(bucket)).uploads().size());
    }

    @Test
    void keyPolicyServiceAndEncryptionContextConditionsAreEnforced() {
        String principal = iam.getUser(user).getArn();
        String policy = """
                {"Version":"2012-10-17","Statement":[{
                  "Effect":"Allow","Principal":{"AWS":"%s"},
                  "Action":["kms:GenerateDataKey","kms:Decrypt"],"Resource":"*",
                  "Condition":{"StringEquals":{
                    "kms:ViaService":"s3.us-east-1.amazonaws.com",
                    "kms:EncryptionContext:aws:s3:arn":"arn:aws:s3:::%s/allowed"
                  }}
                }]}
                """.formatted(principal, bucket);
        kms.putKeyPolicy(request -> request.keyId(keyArn).policyName("default").policy(policy)
                .bypassPolicyLockoutSafetyCheck(true));
        caller.putObject(request -> request.bucket(bucket).key("allowed")
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(keyArn),
                RequestBody.fromString("conditional body"));
        assertEquals("conditional body", caller.getObjectAsBytes(request -> request.bucket(bucket).key("allowed"))
                .asUtf8String());
        assertThrows(S3Exception.class, () -> caller.putObject(request -> request.bucket(bucket).key("wrong-context")
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(keyArn),
                RequestBody.fromString("refused")));
        try (KmsClient direct = AwsTestClients.create(KmsClient.builder(), access.getAccessKeyId(),
                access.getSecretAccessKey(), Region.US_EAST_1, endpoint)) {
            assertThrows(KmsException.class, () ->
                    direct.generateDataKey(request -> request.keyId(keyArn).keySpec("AES_256")
                            .encryptionContext(Map.of("aws:s3:arn", "arn:aws:s3:::" + bucket + "/allowed"))));
        }
    }

    @Test
    void explicitKeyPolicyDenyOverridesCallerIdentityGrant() {
        iam.putUserPolicy(user, "Objects", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:*","kms:*"],"Resource":"*"}
                ]}
                """);
        kms.putKeyPolicy(request -> request.keyId(keyArn).policyName("default").policy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Deny","Principal":"*","Action":"kms:GenerateDataKey","Resource":"*"}
                ]}
                """).bypassPolicyLockoutSafetyCheck(true));
        assertThrows(S3Exception.class, () -> caller.putObject(request -> request.bucket(bucket).key("denied-policy")
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(keyArn),
                RequestBody.fromString("refused")));
        assertEquals(0, s3.listObjectsV2(request -> request.bucket(bucket)).keyCount());
    }

    @Test
    void awsManagedS3KeyAllowsOnlyDelegatedSameAccountUse() {
        caller.putObject(request -> request.bucket(bucket).key("managed")
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS), RequestBody.fromString("managed body"));
        assertEquals("managed body", caller.getObjectAsBytes(request -> request.bucket(bucket).key("managed"))
                .asUtf8String());
    }

    @Test
    void nativeGrantEnforcesEncryptionContextAndRevocation() {
        String principal = iam.getUser(user).getArn();
        kms.putKeyPolicy(request -> request.keyId(keyArn).policyName("default").policy("""
                {"Version":"2012-10-17","Statement":[]}
                """).bypassPolicyLockoutSafetyCheck(true));
        String grant = kms.createGrant(request -> request.keyId(keyArn).granteePrincipal(principal)
                .operationsWithStrings("GenerateDataKey", "Decrypt")
                .constraints(constraints -> constraints.encryptionContextEquals(
                        Map.of("aws:s3:arn", "arn:aws:s3:::" + bucket + "/granted")))).grantId();
        caller.putObject(request -> request.bucket(bucket).key("granted")
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(keyArn),
                RequestBody.fromString("grant body"));
        assertEquals("grant body", caller.getObjectAsBytes(request -> request.bucket(bucket).key("granted"))
                .asUtf8String());
        assertThrows(S3Exception.class, () -> caller.putObject(request -> request.bucket(bucket).key("not-granted")
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(keyArn),
                RequestBody.fromString("refused")));
        kms.revokeGrant(request -> request.keyId(keyArn).grantId(grant));
        assertThrows(S3Exception.class, () -> caller.getObjectAsBytes(request -> request.bucket(bucket).key("granted")));
    }

    @Test
    void identityPolicyCannotManufactureItsOwnKeyGrant() {
        iam.putUserPolicy(user, "Objects", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["s3:*","kms:*"],"Resource":"*"}
                ]}
                """);
        kms.putKeyPolicy(request -> request.keyId(keyArn).policyName("default").policy("""
                {"Version":"2012-10-17","Statement":[]}
                """).bypassPolicyLockoutSafetyCheck(true));
        try (KmsClient direct = AwsTestClients.create(KmsClient.builder(), access.getAccessKeyId(),
                access.getSecretAccessKey(), Region.US_EAST_1, endpoint)) {
            assertThrows(KmsException.class, () ->
                    direct.createGrant(request -> request.keyId(keyArn).granteePrincipal(iam.getUser(user).getArn())
                            .operationsWithStrings("GenerateDataKey", "Decrypt")));
            assertThrows(KmsException.class, () ->
                    direct.putKeyPolicy(request -> request.keyId(keyArn).policyName("default").policy("""
                            {"Version":"2012-10-17","Statement":[{
                              "Effect":"Allow","Principal":"*","Action":"kms:*","Resource":"*"
                            }]}
                            """).bypassPolicyLockoutSafetyCheck(true)));
        }
    }

    private void put(String object) {
        s3.putObject(request -> request.bucket(bucket).key(object)
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(keyArn),
                RequestBody.fromString("KMS envelope protected body"));
    }
}
