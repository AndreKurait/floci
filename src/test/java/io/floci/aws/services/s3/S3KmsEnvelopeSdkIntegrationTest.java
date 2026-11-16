package io.floci.aws.services.s3;

import io.floci.aws.services.iam.IamService;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.IamEnforcementProfile;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GrantConstraints;
import software.amazon.awssdk.services.kms.model.KmsException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void numericForeignCallerRetainsItsAccountForDelegatedKms() {
        String foreignAccount = "222222222222";
        String ownerAccount = keyArn.split(":")[4];
        String credential = foreignAccount + "/" + LocalDate.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.BASIC_ISO_DATE) + "/us-east-1/s3/aws4_request";
        s3.putBucketPolicy(request -> request.bucket(bucket).policy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:root"},
                   "Action":["s3:PutObject","s3:GetObject"],"Resource":"arn:aws:s3:::%s/*"}]}
                """.formatted(foreignAccount, bucket)));
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:root"},
                   "Action":"kms:*","Resource":"*"},
                  {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:root"},
                   "Action":["kms:GenerateDataKey","kms:Decrypt"],"Resource":"*",
                   "Condition":{"StringEquals":{"kms:CallerAccount":"%s"}}}]}
                """;
        kms.putKeyPolicy(request -> request.keyId(keyArn).policyName("default")
                .policy(policy.formatted(ownerAccount, foreignAccount, foreignAccount)));
        s3.putBucketEncryption(request -> request.bucket(bucket).serverSideEncryptionConfiguration(configuration ->
                configuration.rules(rule -> rule.applyServerSideEncryptionByDefault(defaults ->
                        defaults.sseAlgorithm(ServerSideEncryption.AWS_KMS).kmsMasterKeyID(keyArn)))));
        given().multiPart("key", "numeric")
                .multiPart("x-amz-credential", credential)
                .multiPart("file", "upload.txt", "numeric caller".getBytes(StandardCharsets.UTF_8), "text/plain")
                .when().post(endpoint.resolve(bucket)).then().statusCode(204);
        assertEquals("numeric caller", s3.getObjectAsBytes(request -> request.bucket(bucket)
                .key("numeric")).asUtf8String());
        kms.putKeyPolicy(request -> request.keyId(keyArn).policyName("default")
                .policy(policy.formatted(ownerAccount, foreignAccount, "333333333333")));
        given().multiPart("key", "denied-numeric")
                .multiPart("x-amz-credential", credential)
                .multiPart("file", "upload.txt", "denied".getBytes(StandardCharsets.UTF_8), "text/plain")
                .when().post(endpoint.resolve(bucket)).then().statusCode(403);
        assertEquals(1, s3.listObjectsV2(request -> request.bucket(bucket)).keyCount());
    }

    @Test
    void nativeDescribeKeyHonorsCallerAccountWithoutEncryptionContext() {
        String principal = iam.getUser(user).getArn();
        String account = principal.split(":")[4];
        String policy = """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Principal":{"AWS":"%s"},
                   "Action":"kms:DescribeKey","Resource":"*",
                   "Condition":{"StringEquals":{"kms:CallerAccount":"%s"}}},
                  {"Effect":"Allow","Principal":{"AWS":"arn:aws:iam::%s:root"},
                   "Action":["kms:PutKeyPolicy","kms:ScheduleKeyDeletion"],"Resource":"*"}]}
                """;
        iam.putUserPolicy(user, "Objects", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:DescribeKey","Resource":"*"}]}
                """);
        try (KmsClient nativeCaller = AwsTestClients.create(KmsClient.builder(),
                access.getAccessKeyId(), access.getSecretAccessKey(), Region.US_EAST_1, endpoint)) {
            kms.putKeyPolicy(request -> request.keyId(keyArn).policyName("default")
                    .policy(policy.formatted(principal, account, account)));
            assertEquals(keyArn, nativeCaller.describeKey(request -> request.keyId(keyArn)).keyMetadata().arn());
            kms.putKeyPolicy(request -> request.keyId(keyArn).policyName("default")
                    .policy(policy.formatted(principal, "999999999999", account)));
            assertThrows(KmsException.class, () -> nativeCaller.describeKey(request -> request.keyId(keyArn)));
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
    void multipartCustomerKeyDoesNotRequireBucketDefaultKmsPermission() {
        s3.putBucketEncryption(request -> request.bucket(bucket).serverSideEncryptionConfiguration(configuration ->
                configuration.rules(rule -> rule.applyServerSideEncryptionByDefault(defaults ->
                        defaults.sseAlgorithm(ServerSideEncryption.AWS_KMS).kmsMasterKeyID(keyArn)))));
        String customerKey = Base64.getEncoder().encodeToString(new byte[32]);
        String customerMd5 = "cLyPS3KoaSFGi/joRB3OUQ==";
        String upload = caller.createMultipartUpload(request -> request.bucket(bucket).key("customer-parts")
                .sseCustomerAlgorithm("AES256").sseCustomerKey(customerKey).sseCustomerKeyMD5(customerMd5)).uploadId();
        byte[] data = "customer multipart bytes".getBytes(StandardCharsets.UTF_8);
        String tag = caller.uploadPart(request -> request.bucket(bucket).key("customer-parts").uploadId(upload)
                .partNumber(1).sseCustomerAlgorithm("AES256").sseCustomerKey(customerKey)
                .sseCustomerKeyMD5(customerMd5), RequestBody.fromBytes(data)).eTag();
        caller.completeMultipartUpload(request -> request.bucket(bucket).key("customer-parts").uploadId(upload)
                .sseCustomerAlgorithm("AES256").sseCustomerKey(customerKey).sseCustomerKeyMD5(customerMd5)
                .multipartUpload(parts -> parts.parts(part -> part.partNumber(1).eTag(tag))));
        assertArrayEquals(data, caller.getObjectAsBytes(request -> request.bucket(bucket).key("customer-parts")
                .sseCustomerAlgorithm("AES256").sseCustomerKey(customerKey).sseCustomerKeyMD5(customerMd5)).asByteArray());
        assertNull(caller.headObject(request -> request.bucket(bucket).key("customer-parts")
                .sseCustomerAlgorithm("AES256").sseCustomerKey(customerKey).sseCustomerKeyMD5(customerMd5))
                .ssekmsKeyId());
        assertThrows(S3Exception.class, () -> caller.getObjectAsBytes(request ->
                request.bucket(bucket).key("customer-parts")));
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
    void nativeGrantConstraintValuesRemainLiteral() {
        String principal = iam.getUser(user).getArn();
        String account = principal.split(":")[4];
        kms.putKeyPolicy(request -> request.keyId(keyArn).policyName("default").policy("""
                {"Version":"2012-10-17","Statement":[]}
                """).bypassPolicyLockoutSafetyCheck(true));
        kms.createGrant(request -> request.keyId(keyArn).granteePrincipal(principal)
                .operationsWithStrings("GenerateDataKey")
                .constraints(constraints -> constraints.encryptionContextEquals(
                        Map.of("purpose", "${aws:PrincipalAccount}"))));
        try (KmsClient direct = AwsTestClients.create(KmsClient.builder(), access.getAccessKeyId(),
                access.getSecretAccessKey(), Region.US_EAST_1, endpoint)) {
            assertThrows(KmsException.class, () -> direct.generateDataKey(request -> request.keyId(keyArn)
                    .keySpec("AES_256").encryptionContext(Map.of("purpose", account))));
            assertEquals(32, direct.generateDataKey(request -> request.keyId(keyArn).keySpec("AES_256")
                    .encryptionContext(Map.of("purpose", "${aws:PrincipalAccount}"))).plaintext().asByteArray().length);
            assertThrows(KmsException.class, () -> direct.generateDataKey(request -> request.keyId(keyArn)
                    .keySpec("AES_256").encryptionContext(Map.of("purpose", "${aws:principalaccount}"))));
        }
    }

    @Test
    void nativeGrantManagementOperationsIgnoreEncryptionContextConstraints() {
        String principal = iam.getUser(user).getArn();
        kms.putKeyPolicy(request -> request.keyId(keyArn).policyName("default").policy("""
                {"Version":"2012-10-17","Statement":[]}
                """).bypassPolicyLockoutSafetyCheck(true));
        String grant = kms.createGrant(request -> request.keyId(keyArn).granteePrincipal(principal)
                .operationsWithStrings("DescribeKey", "RetireGrant", "GenerateDataKey")
                .constraints(constraints -> constraints.encryptionContextEquals(Map.of("purpose", "allowed"))))
                .grantId();
        try (KmsClient direct = AwsTestClients.create(KmsClient.builder(), access.getAccessKeyId(),
                access.getSecretAccessKey(), Region.US_EAST_1, endpoint)) {
            assertEquals(keyArn, direct.describeKey(request -> request.keyId(keyArn)).keyMetadata().arn());
            assertThrows(KmsException.class, () -> direct.generateDataKey(request -> request.keyId(keyArn)
                    .keySpec("AES_256")));
            assertThrows(KmsException.class, () -> direct.generateDataKey(request -> request.keyId(keyArn)
                    .keySpec("AES_256").encryptionContext(Map.of("purpose", "denied"))));
            assertEquals(32, direct.generateDataKey(request -> request.keyId(keyArn).keySpec("AES_256")
                    .encryptionContext(Map.of("purpose", "allowed"))).plaintext().asByteArray().length);
            direct.retireGrant(request -> request.keyId(keyArn).grantId(grant));
            assertThrows(KmsException.class, () -> direct.describeKey(request -> request.keyId(keyArn)));
        }
    }

    @Test
    void nativeGrantRetirementIgnoresSourceArnButDescribeKeyDoesNot() {
        String principal = iam.getUser(user).getArn();
        kms.putKeyPolicy(request -> request.keyId(keyArn).policyName("default").policy("""
                {"Version":"2012-10-17","Statement":[]}
                """).bypassPolicyLockoutSafetyCheck(true));
        String grant = kms.createGrant(request -> request.keyId(keyArn).granteePrincipal(principal)
                .operationsWithStrings("DescribeKey", "RetireGrant")
                .constraints(constraints -> constraints.sourceArn(
                        "arn:aws:lambda:us-east-1:" + principal.split(":")[4] + ":function:grant-source"))).grantId();
        try (KmsClient direct = AwsTestClients.create(KmsClient.builder(), access.getAccessKeyId(),
                access.getSecretAccessKey(), Region.US_EAST_1, endpoint)) {
            assertThrows(KmsException.class, () -> direct.describeKey(request -> request.keyId(keyArn)));
            direct.retireGrant(request -> request.keyId(keyArn).grantId(grant));
            assertEquals(0, kms.listGrants(request -> request.keyId(keyArn)).grants().size());
        }
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

    @Test
    void childGrantCannotEscalateOperationsOrCombineParents() {
        String principal = iam.getUser(user).getArn();
        kms.createGrant(request -> request.keyId(keyArn).granteePrincipal(principal)
                .operationsWithStrings("CreateGrant", "Encrypt"));
        kms.createGrant(request -> request.keyId(keyArn).granteePrincipal(principal)
                .operationsWithStrings("Decrypt"));
        try (KmsClient direct = AwsTestClients.create(KmsClient.builder(), access.getAccessKeyId(),
                access.getSecretAccessKey(), Region.US_EAST_1, endpoint)) {
            int before = kms.listGrants(request -> request.keyId(keyArn)).grants().size();
            assertEquals(400, assertThrows(KmsException.class, () -> direct.createGrant(request ->
                    request.keyId(keyArn).granteePrincipal(principal).operationsWithStrings("Decrypt"))).statusCode());
            assertEquals(before, kms.listGrants(request -> request.keyId(keyArn)).grants().size());
            direct.createGrant(request -> request.keyId(keyArn).granteePrincipal(principal)
                    .operationsWithStrings("Encrypt"));
        }
    }

    @Test
    void childGrantMustRetainOrStrengthenParentConstraints() {
        String principal = iam.getUser(user).getArn();
        kms.createGrant(request -> request.keyId(keyArn).granteePrincipal(principal)
                .operationsWithStrings("CreateGrant", "Decrypt")
                .constraints(GrantConstraints.builder().encryptionContextSubset(Map.of("Department", "IT")).build()));
        try (KmsClient direct = AwsTestClients.create(KmsClient.builder(), access.getAccessKeyId(),
                access.getSecretAccessKey(), Region.US_EAST_1, endpoint)) {
            int before = kms.listGrants(request -> request.keyId(keyArn)).grants().size();
            for (Map<String, String> pairs : List.of(Map.<String, String>of(), Map.of("Department", "HR"),
                    Map.of("department", "IT"))) {
                assertThrows(KmsException.class, () -> direct.createGrant(request -> request.keyId(keyArn)
                        .granteePrincipal(principal).operationsWithStrings("Decrypt")
                        .constraints(GrantConstraints.builder().encryptionContextSubset(pairs).build())));
            }
            assertEquals(before, kms.listGrants(request -> request.keyId(keyArn)).grants().size());
            direct.createGrant(request -> request.keyId(keyArn).granteePrincipal(principal)
                    .operationsWithStrings("Decrypt").constraints(GrantConstraints.builder()
                            .encryptionContextEquals(Map.of("Department", "IT", "Purpose", "Test")).build()));
        }
    }

    @Test
    void independentPolicyCreateGrantIsNotLimitedByParentGrant() {
        String principal = iam.getUser(user).getArn();
        kms.createGrant(request -> request.keyId(keyArn).granteePrincipal(principal)
                .operationsWithStrings("CreateGrant"));
        iam.putUserPolicy(user, "Objects", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:CreateGrant","Resource":"*"}]}
                """);
        try (KmsClient direct = AwsTestClients.create(KmsClient.builder(), access.getAccessKeyId(),
                access.getSecretAccessKey(), Region.US_EAST_1, endpoint)) {
            direct.createGrant(request -> request.keyId(keyArn).granteePrincipal(principal)
                    .operationsWithStrings("Decrypt"));
        }
    }

    @Test
    void nativeReEncryptUsesDirectionalGrantsAndContexts() {
        String destination = kms.createKey().keyMetadata().arn();
        try {
            verifyReEncryptGrants(destination, Map.of(), Map.of());
        } finally {
            kms.scheduleKeyDeletion(request -> request.keyId(destination).pendingWindowInDays(7));
        }
    }

    @Test
    void nativeReEncryptUsesSeparateContextsOnTheSameKey() {
        verifyReEncryptGrants(keyArn, Map.of("purpose", "source"), Map.of("purpose", "destination"));
    }

    private void verifyReEncryptGrants(String destination, Map<String, String> sourceContext,
                                       Map<String, String> destinationContext) {
        String principal = iam.getUser(user).getArn();
        iam.putUserPolicy(user, "Objects", """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"kms:*","Resource":"*"}]}
                """);
        SdkBytes ciphertext = kms.encrypt(request -> request.keyId(keyArn)
                .plaintext(SdkBytes.fromUtf8String("directional grant body"))
                .encryptionContext(sourceContext)).ciphertextBlob();
        for (String key : keyArn.equals(destination)
                ? List.of(keyArn) : List.of(keyArn, destination)) {
            kms.putKeyPolicy(request -> request.keyId(key).policyName("default")
                    .policy("{\"Version\":\"2012-10-17\",\"Statement\":[]}")
                    .bypassPolicyLockoutSafetyCheck(true));
        }
        String sourceGrant = kms.createGrant(request -> request.keyId(keyArn).granteePrincipal(principal)
                .operationsWithStrings("ReEncryptFrom")
                .constraints(sourceContext.isEmpty() ? null :
                        GrantConstraints.builder()
                                .encryptionContextEquals(sourceContext).build())).grantId();
        try (KmsClient direct = AwsTestClients.create(KmsClient.builder(), access.getAccessKeyId(),
                access.getSecretAccessKey(), Region.US_EAST_1, endpoint)) {
            assertThrows(KmsException.class, () -> direct.reEncrypt(request -> request.ciphertextBlob(ciphertext)
                    .destinationKeyId(destination).sourceEncryptionContext(sourceContext)
                    .destinationEncryptionContext(destinationContext)));
            String destinationGrant = kms.createGrant(request -> request.keyId(destination).granteePrincipal(principal)
                    .operationsWithStrings("ReEncryptTo")
                    .constraints(destinationContext.isEmpty() ? null :
                            GrantConstraints.builder()
                                    .encryptionContextEquals(destinationContext).build())).grantId();
            SdkBytes result = direct.reEncrypt(request -> request.ciphertextBlob(ciphertext)
                    .destinationKeyId(destination).sourceEncryptionContext(sourceContext)
                    .destinationEncryptionContext(destinationContext)).ciphertextBlob();
            assertEquals("directional grant body", kms.decrypt(request -> request.ciphertextBlob(result)
                    .encryptionContext(destinationContext)).plaintext().asUtf8String());
            iam.putUserPolicy(user, "Objects", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"kms:*","Resource":"*"},
                      {"Effect":"Deny","Action":"kms:ReEncryptTo","Resource":"%s"}]}
                    """.formatted(destination));
            assertThrows(KmsException.class, () -> direct.reEncrypt(request -> request.ciphertextBlob(ciphertext)
                    .destinationKeyId(destination).sourceEncryptionContext(sourceContext)
                    .destinationEncryptionContext(destinationContext)));
            iam.putUserPolicy(user, "Objects", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"kms:*","Resource":"*"}]}
                    """);
            kms.revokeGrant(request -> request.keyId(keyArn).grantId(sourceGrant));
            assertThrows(KmsException.class, () -> direct.reEncrypt(request -> request.sourceKeyId(keyArn)
                    .ciphertextBlob(ciphertext).destinationKeyId(destination).sourceEncryptionContext(sourceContext)
                    .destinationEncryptionContext(destinationContext)));
            kms.revokeGrant(request -> request.keyId(destination).grantId(destinationGrant));
        }
    }

    private void put(String object) {
        s3.putObject(request -> request.bucket(bucket).key(object)
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(keyArn),
                RequestBody.fromString("KMS envelope protected body"));
    }
}
