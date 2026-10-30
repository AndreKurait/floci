package io.github.hectorvent.floci.config;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.EcrException;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AccessKey;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.net.ssl.TrustManagerFactory;
import java.net.URI;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamSecureTransportIntegrationTest.Profile.class)
class IamSecureTransportIntegrationTest {
    public static class Profile extends SelfSignedTlsProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> configuration = new HashMap<>(super.getConfigOverrides());
            configuration.put("floci.port", "0");
            configuration.put("quarkus.http.test-port", "0");
            configuration.put("quarkus.http.test-ssl-port", "0");
            configuration.put("floci.services.iam.enforcement-enabled", "true");
            configuration.put("floci.auth.validate-signatures", "true");
            configuration.put("floci.services.s3.enforce-auth", "true");
            return configuration;
        }
    }

    @ConfigProperty(name = "quarkus.http.test-port")
    int httpPort;

    @ConfigProperty(name = "quarkus.http.test-ssl-port")
    int httpsPort;

    private final StaticCredentialsProvider root =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    private IamClient iam;
    private SdkHttpClient transport;
    private StaticCredentialsProvider caller;
    private AccessKey key;
    private String user;

    @BeforeEach
    void createCaller() throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("floci",
                FlociCertificateAuthority.loadOrCreate(Path.of(SelfSignedTlsProfile.TLS_DIR)).certificate());
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        transport = UrlConnectionHttpClient.builder().tlsTrustManagersProvider(managers::getTrustManagers).build();
        iam = IamClient.builder().endpointOverride(endpoint(false)).region(Region.US_EAST_1)
                .credentialsProvider(root).httpClient(transport).build();
        user = "secure-transport-" + UUID.randomUUID().toString().substring(0, 8);
        iam.createUser(request -> request.userName(user));
        key = iam.createAccessKey(request -> request.userName(user)).accessKey();
        caller = StaticCredentialsProvider.create(AwsBasicCredentials.create(key.accessKeyId(), key.secretAccessKey()));
    }

    @AfterEach
    void removeCaller() {
        try {
            if (iam != null && key != null) {
                for (String policy : iam.listUserPolicies(request -> request.userName(user)).policyNames()) {
                    iam.deleteUserPolicy(request -> request.userName(user).policyName(policy));
                }
                iam.deleteAccessKey(request -> request.userName(user).accessKeyId(key.accessKeyId()));
                iam.deleteUser(request -> request.userName(user));
            }
        } finally {
            if (iam != null) {
                iam.close();
            }
            if (transport != null) {
                transport.close();
            }
        }
    }

    @Test
    void bucketDenyUsesTlsInsteadOfForwardedHeaders() {
        policy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:*","Resource":"*"}]}
                """);
        String bucket = user + "-bucket";
        try (S3Client owner = s3(root, true); S3Client https = s3(caller, true); S3Client http = s3(caller, false)) {
            owner.createBucket(request -> request.bucket(bucket));
            try {
                owner.putBucketPolicy(request -> request.bucket(bucket).policy("""
                        {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":"*",
                        "Action":"s3:*","Resource":["arn:aws:s3:::%s","arn:aws:s3:::%s/*"],
                        "Condition":{"Bool":{"aws:SecureTransport":false}}}]}
                        """.formatted(bucket, bucket)));
                https.putObject(request -> request.bucket(bucket).key("tls")
                        .overrideConfiguration(configuration -> configuration.putHeader("X-Forwarded-Proto", "http")),
                        RequestBody.fromString("tls-data"));
                assertEquals("tls-data", https.getObjectAsBytes(request -> request.bucket(bucket).key("tls")).asUtf8String());
                S3Exception denied = assertThrows(S3Exception.class,
                        () -> http.putObject(request -> request.bucket(bucket).key("plain")
                                .overrideConfiguration(configuration -> configuration.putHeader("X-Forwarded-Proto", "https")),
                                RequestBody.fromString("must-be-refused")));
                assertEquals(403, denied.statusCode());
            } finally {
                owner.deleteBucketPolicy(request -> request.bucket(bucket));
                owner.deleteObject(request -> request.bucket(bucket).key("tls"));
                owner.deleteObject(request -> request.bucket(bucket).key("plain"));
                owner.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }

    @Test
    void ecrIdentityAllowReceivesSecureTransport() {
        policy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":"ecr:DescribeRepositories","Resource":"*",
                "Condition":{"Bool":{"aws:SecureTransport":true}}}]}
                """);
        String repository = user + "-repo";
        try (EcrClient owner = ecr(root, true); EcrClient https = ecr(caller, true); EcrClient http = ecr(caller, false)) {
            owner.createRepository(request -> request.repositoryName(repository));
            try {
                assertEquals(repository, https.describeRepositories(request -> request.repositoryNames(repository))
                        .repositories().getFirst().repositoryName());
                EcrException denied = assertThrows(EcrException.class,
                        () -> http.describeRepositories(request -> request.repositoryNames(repository)
                                .overrideConfiguration(configuration -> configuration.putHeader("X-Forwarded-Proto", "https"))));
                assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
            } finally {
                owner.deleteRepository(request -> request.repositoryName(repository));
            }
        }
    }

    @Test
    void perObjectAuthorizationAlsoReceivesTheActualTransport() {
        policy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:*","Resource":"*",
                "Condition":{"Bool":{"aws:SecureTransport":true}}}]}
                """);
        String bucket = user + "-bulk";
        try (S3Client owner = s3(root, true); S3Client https = s3(caller, true); S3Client http = s3(caller, false)) {
            owner.createBucket(request -> request.bucket(bucket));
            try {
                owner.putObject(request -> request.bucket(bucket).key("tls"), RequestBody.fromString("first"));
                owner.putObject(request -> request.bucket(bucket).key("plain"), RequestBody.fromString("second"));
                DeleteObjectsResponse removed = https.deleteObjects(request -> request.bucket(bucket)
                        .delete(objects -> objects.objects(object -> object.key("tls"))));
                assertTrue(removed.errors().isEmpty());
                assertEquals("tls", removed.deleted().getFirst().key());
                DeleteObjectsResponse denied = http.deleteObjects(request -> request.bucket(bucket)
                        .delete(objects -> objects.objects(object -> object.key("plain"))));
                assertEquals("AccessDenied", denied.errors().getFirst().code());
                assertEquals("second", owner.getObjectAsBytes(request -> request.bucket(bucket).key("plain")).asUtf8String());
            } finally {
                owner.deleteObject(request -> request.bucket(bucket).key("tls"));
                owner.deleteObject(request -> request.bucket(bucket).key("plain"));
                owner.deleteBucket(request -> request.bucket(bucket));
            }
        }
    }

    @Test
    void copySourceBucketPolicyUsesTheActualTransport() {
        policy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"s3:*","Resource":"*"}]}
                """);
        String source = user + "-source";
        String destination = user + "-destination";
        try (S3Client owner = s3(root, true); S3Client https = s3(caller, true); S3Client http = s3(caller, false)) {
            owner.createBucket(request -> request.bucket(source));
            try {
                owner.createBucket(request -> request.bucket(destination));
                try {
                    owner.putObject(request -> request.bucket(source).key("value"), RequestBody.fromString("source-data"));
                    owner.putBucketPolicy(request -> request.bucket(source).policy("""
                            {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":"*",
                            "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*",
                            "Condition":{"Bool":{"aws:SecureTransport":false}}}]}
                            """.formatted(source)));
                    https.copyObject(request -> request.copySource(source + "/value")
                            .bucket(destination).key("tls"));
                    assertEquals("source-data", owner.getObjectAsBytes(request -> request.bucket(destination).key("tls"))
                            .asUtf8String());
                    S3Exception denied = assertThrows(S3Exception.class,
                            () -> http.copyObject(request -> request.copySource(source + "/value")
                                    .bucket(destination).key("plain")
                                    .overrideConfiguration(configuration ->
                                            configuration.putHeader("X-Forwarded-Proto", "https"))));
                    assertEquals(403, denied.statusCode());
                } finally {
                    owner.deleteObject(request -> request.bucket(destination).key("tls"));
                    owner.deleteObject(request -> request.bucket(destination).key("plain"));
                    owner.deleteBucket(request -> request.bucket(destination));
                }
            } finally {
                owner.deleteBucketPolicy(request -> request.bucket(source));
                owner.deleteObject(request -> request.bucket(source).key("value"));
                owner.deleteBucket(request -> request.bucket(source));
            }
        }
    }

    private void policy(String document) {
        iam.putUserPolicy(request -> request.userName(user).policyName("transport").policyDocument(document));
    }

    private URI endpoint(boolean secure) {
        return URI.create((secure ? "https" : "http") + "://localhost:" + (secure ? httpsPort : httpPort));
    }

    private S3Client s3(StaticCredentialsProvider credentials, boolean secure) {
        return S3Client.builder().endpointOverride(endpoint(secure)).region(Region.US_EAST_1)
                .credentialsProvider(credentials).forcePathStyle(true).httpClient(transport).build();
    }

    private EcrClient ecr(StaticCredentialsProvider credentials, boolean secure) {
        return EcrClient.builder().endpointOverride(endpoint(secure)).region(Region.US_EAST_1)
                .credentialsProvider(credentials).httpClient(transport).build();
    }
}
