package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sts.StsClient;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamBootstrapPrincipalsIntegrationTest.Profile.class)
class IamBootstrapPrincipalsIntegrationTest {
    private static final String FIRST_KEY = "AKIAABCDEFGHIJKLMNOP";
    private static final String SECOND_KEY = "AKIAQRSTUVWXYZABCDEF";
    private static final String SECRET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN0123456789";
    @TestHTTPResource("/")
    URI endpoint;

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try {
                Path file = Files.createTempFile("floci-bootstrap-test-", ".json",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                file.toFile().deleteOnExit();
                Files.writeString(file, """
                        [{"accountId":"222222222222","partition":"aws","userName":"bootstrap-admin",
                          "accessKeyId":"%s","secretAccessKey":"%s","grant":"AdministratorAccess"},
                         {"accountId":"333333333333","partition":"aws","userName":"bootstrap-admin",
                          "accessKeyId":"%s","secretAccessKey":"%s","grant":"AdministratorAccess"}]
                        """.formatted(FIRST_KEY, SECRET, SECOND_KEY, SECRET));
                return Map.of("floci.default-account-id", "111111111111",
                        "floci.services.iam.bootstrap-principals-file", file.toString(),
                        "floci.services.iam.seed-deployer-principal", "false",
                        "floci.services.iam.enforcement-enabled", "true",
                        "floci.auth.validate-signatures", "true",
                        "floci.services.s3.enforce-auth", "true",
                        "quarkus.http.host", "127.0.0.1",
                        "quarkus.http.test-port", "0");
            } catch (IOException error) {
                throw new IllegalStateException("Could not create isolated bootstrap test input");
            }
        }
    }

    private StaticCredentialsProvider credentials(String key, String secret) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret));
    }

    private S3Client s3(String key, String secret) {
        return S3Client.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                .credentialsProvider(credentials(key, secret)).forcePathStyle(true).build();
    }

    @Test
    void signedSdkCreatesNativeStackAndReadsS3InDeclaredAccount() {
        String name = "bootstrap-" + UUID.randomUUID().toString().substring(0, 8);
        try (StsClient sts = StsClient.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                     .credentialsProvider(credentials(FIRST_KEY, SECRET)).build();
             CloudFormationClient cfn = CloudFormationClient.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                     .credentialsProvider(credentials(FIRST_KEY, SECRET)).build();
             CloudFormationClient other = CloudFormationClient.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                     .credentialsProvider(credentials(SECOND_KEY, SECRET)).build();
             S3Client s3 = s3(FIRST_KEY, SECRET)) {
            assertEquals("222222222222", sts.getCallerIdentity().account());
            assertEquals("arn:aws:iam::222222222222:user/bootstrap-admin", sts.getCallerIdentity().arn());
            String stackId = cfn.createStack(request -> request.stackName(name).templateBody("""
                    {"Resources":{"Bucket":{"Type":"AWS::S3::Bucket","Properties":{"BucketName":"%s"}}}}
                    """.formatted(name))).stackId();
            assertTrue(stackId.startsWith("arn:aws:cloudformation:eu-west-1:222222222222:stack/"));
            try {
                assertTrue(cfn.waiter().waitUntilStackCreateComplete(request -> request.stackName(name),
                        configuration -> configuration.waitTimeout(Duration.ofSeconds(60))).matched().response().isPresent());
                assertThrows(CloudFormationException.class, () -> other.describeStacks(request -> request.stackName(name)));
                s3.putObject(request -> request.bucket(name).key("proof"), RequestBody.fromString("authenticated"));
                assertEquals("authenticated", s3.getObjectAsBytes(request -> request.bucket(name).key("proof")).asUtf8String());
                s3.deleteObject(request -> request.bucket(name).key("proof"));
            } finally {
                cfn.deleteStack(request -> request.stackName(name));
            }
        }
    }

    @Test
    void wrongSecretUnknownKeyAndExplicitIamDenyRemainDenied() {
        try (S3Client wrong = s3(FIRST_KEY, new StringBuilder(SECRET).reverse().toString());
             S3Client unknown = s3("AKIAUNKNOWNKEYABCDEF", SECRET);
             IamClient iam = IamClient.builder().endpointOverride(endpoint).region(Region.EU_WEST_1)
                     .credentialsProvider(credentials(SECOND_KEY, SECRET)).build()) {
            assertEquals(403, assertThrows(S3Exception.class, wrong::listBuckets).statusCode());
            assertEquals(403, assertThrows(S3Exception.class, unknown::listBuckets).statusCode());
            iam.putUserPolicy(request -> request.userName("bootstrap-admin").policyName("deny-create")
                    .policyDocument("""
                            {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Action":"iam:CreateUser","Resource":"*"}]}
                            """));
            try {
                assertEquals(403, assertThrows(IamException.class,
                        () -> iam.createUser(request -> request.userName("must-not-exist"))).statusCode());
            } finally {
                iam.deleteUserPolicy(request -> request.userName("bootstrap-admin").policyName("deny-create"));
            }
        }
    }
}
