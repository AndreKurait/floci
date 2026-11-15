package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;
import software.amazon.awssdk.services.cloudfront.model.FieldLevelEncryptionConfig;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CloudFrontFieldEncryptionIamIntegrationTest {
    @TestHTTPResource("/")
    URI endpoint;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "us-gov-west-1,aws-us-gov", "cn-north-1,aws-cn"})
    void bothGettersUseCredentialAccountAndScopedPartitionArnDespiteSpoofedAction(String region, String partition)
            throws Exception {
        String account = "235711131719";
        String rootAuth = auth(account, region, "iam");
        String user = "fle-reader-" + UUID.randomUUID();
        UserCredentials credentials = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), rootAuth, user);
        try (CloudFrontClient root = AwsTestClients.create(CloudFrontClient.builder(), account, Region.of(region));
             CloudFrontFieldEncryptionFixtures fixture = new CloudFrontFieldEncryptionFixtures(root);
             CloudFrontClient reader = AwsTestClients.create(CloudFrontClient.builder(),
                     credentials.accessKeyId(), credentials.secretAccessKey(), Region.of(region))) {
            FieldLevelEncryptionConfig config = fixture.config(fixture.reference, "owned", true);
            String id = fixture.create(config).fieldLevelEncryption().id();
            String resource = "arn:" + partition + ":cloudfront::" + account + ":field-level-encryption-config/" + id;
            String action = "cloudfront:GetFieldLevelEncryption*";
            policy(rootAuth, user, statement("Allow", action, resource));
            assertEquals(config, reader.getFieldLevelEncryption(r -> r.id(id))
                    .fieldLevelEncryption().fieldLevelEncryptionConfig());
            assertEquals(config, reader.getFieldLevelEncryptionConfig(r -> r.id(id)).fieldLevelEncryptionConfig());
            policy(rootAuth, user, statement("Allow", action, resource + "-wrong"));
            assertEquals(403, assertThrows(CloudFrontException.class,
                    () -> reader.getFieldLevelEncryption(r -> r.id(id))).statusCode());
            assertEquals(403, assertThrows(CloudFrontException.class,
                    () -> reader.getFieldLevelEncryptionConfig(r -> r.id(id))).statusCode());
            policy(rootAuth, user, statement("Allow", "cloudfront:*", "*") + ","
                    + statement("Deny", action, resource));
            assertEquals(403, assertThrows(CloudFrontException.class,
                    () -> reader.getFieldLevelEncryption(r -> r.id(id))).statusCode());
            for (String suffix : new String[]{"", "/config"}) {
                given().header("Authorization", auth(credentials.accessKeyId(), region, "cloudfront"))
                        .header("X-Amz-Target", "CloudFront.ListDistributions")
                        .queryParam("Action", "ListDistributions")
                        .get("/2020-05-31/field-level-encryption/" + id + suffix)
                        .then().statusCode(403);
            }
            String profileResource = "arn:" + partition + ":cloudfront::" + account
                    + ":field-level-encryption-profile/" + fixture.profile;
            policy(rootAuth, user, statement("Allow", action, profileResource));
            assertEquals(fixture.profileConfig(fixture.reference, "AuditProvider", "private*"), reader
                    .getFieldLevelEncryptionProfileConfig(r -> r.id(fixture.profile)).fieldLevelEncryptionProfileConfig());
            assertEquals(fixture.profile, reader.getFieldLevelEncryptionProfile(r -> r.id(fixture.profile))
                    .fieldLevelEncryptionProfile().id());
            policy(rootAuth, user, statement("Allow", action, profileResource + "-wrong"));
            assertEquals(403, assertThrows(CloudFrontException.class,
                    () -> reader.getFieldLevelEncryptionProfile(r -> r.id(fixture.profile))).statusCode());
            assertEquals(403, assertThrows(CloudFrontException.class,
                    () -> reader.getFieldLevelEncryptionProfileConfig(r -> r.id(fixture.profile))).statusCode());
            policy(rootAuth, user, statement("Allow", "cloudfront:*", "*") + ","
                    + statement("Deny", action, profileResource));
            assertEquals(403, assertThrows(CloudFrontException.class,
                    () -> reader.getFieldLevelEncryptionProfile(r -> r.id(fixture.profile))).statusCode());
            assertEquals(403, assertThrows(CloudFrontException.class,
                    () -> reader.getFieldLevelEncryptionProfileConfig(r -> r.id(fixture.profile))).statusCode());
            for (String suffix : new String[]{"", "/config"}) {
                given().header("Authorization", auth(credentials.accessKeyId(), region, "cloudfront"))
                        .header("X-Amz-Target", "CloudFront.ListDistributions")
                        .queryParam("Action", "ListDistributions")
                        .get("/2020-05-31/field-level-encryption-profile/" + fixture.profile + suffix)
                        .then().statusCode(403);
            }
            assertEquals(config, root.getFieldLevelEncryption(r -> r.id(id))
                    .fieldLevelEncryption().fieldLevelEncryptionConfig());
        }
    }

    private void policy(String authorization, String user, String statements) {
        EnforcementFixtures.putUserPolicy(endpoint.getPort(), authorization, user, "fle-read",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }
}
