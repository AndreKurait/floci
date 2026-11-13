package io.floci.aws.services.cloudfront;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;
import software.amazon.awssdk.services.cloudfront.model.CreateFieldLevelEncryptionConfigResponse;
import software.amazon.awssdk.services.cloudfront.model.FieldLevelEncryptionConfig;
import software.amazon.awssdk.services.cloudfront.model.FieldLevelEncryptionProfileConfig;
import software.amazon.awssdk.services.cloudfront.model.GetFieldLevelEncryptionProfileResponse;
import software.amazon.awssdk.services.cloudfront.model.GetFieldLevelEncryptionResponse;
import software.amazon.awssdk.services.cloudfront.model.QueryArgProfile;
import software.amazon.awssdk.services.cloudfront.model.UpdateFieldLevelEncryptionConfigResponse;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudFrontFieldEncryptionSdkIntegrationTest {
    @Test
    void bothPayloadRootsReadLatestNestedConfigurationAndDeleteRealResource() throws Exception {
        try (CloudFrontClient client = AwsTestClients.create(CloudFrontClient.builder(), "200000000001", Region.US_EAST_1);
             CloudFrontFieldEncryptionFixtures fixture = new CloudFrontFieldEncryptionFixtures(client)) {
            FieldLevelEncryptionConfig original = fixture.config(fixture.reference, "old", true);
            CreateFieldLevelEncryptionConfigResponse created = fixture.create(original);
            String id = created.fieldLevelEncryption().id();
            GetFieldLevelEncryptionResponse first = client.getFieldLevelEncryption(r -> r.id(id));
            assertEquals(original, first.fieldLevelEncryption().fieldLevelEncryptionConfig());
            assertEquals(original, client.getFieldLevelEncryptionConfig(r -> r.id(id)).fieldLevelEncryptionConfig());
            assertEquals(created.eTag(), first.eTag());
            assertEquals(created.fieldLevelEncryption().lastModifiedTime(), first.fieldLevelEncryption().lastModifiedTime());
            assertNotNull(first.fieldLevelEncryption().lastModifiedTime());
            FieldLevelEncryptionConfig controlConfig = fixture.config(fixture.reference + "-control", "control", true);
            String control = fixture.create(controlConfig).fieldLevelEncryption().id();
            FieldLevelEncryptionConfig replacement = fixture.config(fixture.reference, "replacement", false)
                    .toBuilder().comment(null).build();
            UpdateFieldLevelEncryptionConfigResponse updated = client.updateFieldLevelEncryptionConfig(r ->
                    r.id(id).ifMatch(first.eTag()).fieldLevelEncryptionConfig(replacement));
            GetFieldLevelEncryptionResponse read = client.getFieldLevelEncryption(r -> r.id(id));
            assertEquals(id, read.fieldLevelEncryption().id());
            assertEquals(replacement, read.fieldLevelEncryption().fieldLevelEncryptionConfig());
            assertEquals(replacement, client.getFieldLevelEncryptionConfig(r -> r.id(id)).fieldLevelEncryptionConfig());
            assertNull(read.fieldLevelEncryption().fieldLevelEncryptionConfig().contentTypeProfileConfig());
            assertNull(read.fieldLevelEncryption().fieldLevelEncryptionConfig().comment());
            assertNotEquals(first.eTag(), read.eTag());
            assertEquals(updated.eTag(), read.eTag());
            assertEquals(updated.fieldLevelEncryption().lastModifiedTime(), read.fieldLevelEncryption().lastModifiedTime());
            assertFalse(read.fieldLevelEncryption().lastModifiedTime().isBefore(first.fieldLevelEncryption().lastModifiedTime()));
            assertEquals(controlConfig, client.getFieldLevelEncryption(r -> r.id(control))
                    .fieldLevelEncryption().fieldLevelEncryptionConfig());
            fixture.delete(id);
            assertEquals(404, assertThrows(CloudFrontException.class,
                    () -> client.getFieldLevelEncryptionConfig(r -> r.id(id))).statusCode());
            assertEquals(controlConfig, client.getFieldLevelEncryption(r -> r.id(control))
                    .fieldLevelEncryption().fieldLevelEncryptionConfig());
        }
    }

    @Test
    void inconsistentCollectionsRejectCreateAndUpdateWithoutChangingStoredState() throws Exception {
        try (CloudFrontClient client = AwsTestClients.create(CloudFrontClient.builder(), "200000000002", Region.US_EAST_1);
             CloudFrontFieldEncryptionFixtures fixture = new CloudFrontFieldEncryptionFixtures(client)) {
            FieldLevelEncryptionConfig config = fixture.config(fixture.reference, "profile", true);
            CreateFieldLevelEncryptionConfigResponse created = fixture.create(config);
            String id = created.fieldLevelEncryption().id();
            for (boolean content : new boolean[]{false, true}) {
                FieldLevelEncryptionConfig invalid = content
                        ? config.toBuilder().contentTypeProfileConfig(config.contentTypeProfileConfig().toBuilder()
                                .contentTypeProfiles(config.contentTypeProfileConfig().contentTypeProfiles().toBuilder()
                                        .quantity(2).build()).build()).build()
                        : config.toBuilder().queryArgProfileConfig(config.queryArgProfileConfig().toBuilder()
                                .queryArgProfiles(config.queryArgProfileConfig().queryArgProfiles().toBuilder()
                                        .quantity(2).build()).build()).build();
                CloudFrontException rejectedCreate = assertThrows(CloudFrontException.class,
                        () -> client.createFieldLevelEncryptionConfig(r -> r.fieldLevelEncryptionConfig(invalid)));
                assertEquals("InconsistentQuantities", rejectedCreate.awsErrorDetails().errorCode());
                assertEquals(400, rejectedCreate.statusCode());
                CloudFrontException rejectedUpdate = assertThrows(CloudFrontException.class,
                        () -> client.updateFieldLevelEncryptionConfig(r -> r.id(id).ifMatch(created.eTag())
                                .fieldLevelEncryptionConfig(invalid)));
                assertEquals("InconsistentQuantities", rejectedUpdate.awsErrorDetails().errorCode());
                GetFieldLevelEncryptionResponse retained = client.getFieldLevelEncryption(r -> r.id(id));
                assertEquals(config, retained.fieldLevelEncryption().fieldLevelEncryptionConfig());
                assertEquals(created.eTag(), retained.eTag());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,200000000003,300000000003", "us-gov-west-1,200000000004,300000000004",
            "cn-north-1,200000000005,300000000005"})
    void credentialAccountsOwnConfigurationsAndProfileReferences(String region, String owner, String other)
            throws Exception {
        try (CloudFrontClient first = AwsTestClients.create(CloudFrontClient.builder(), owner, Region.of(region));
             CloudFrontClient second = AwsTestClients.create(CloudFrontClient.builder(), other, Region.of(region));
             CloudFrontFieldEncryptionFixtures a = new CloudFrontFieldEncryptionFixtures(first);
             CloudFrontFieldEncryptionFixtures b = new CloudFrontFieldEncryptionFixtures(second)) {
            FieldLevelEncryptionConfig config = a.config(a.reference, "owned", true);
            String id = a.create(config).fieldLevelEncryption().id();
            assertEquals(404, assertThrows(CloudFrontException.class,
                    () -> second.getFieldLevelEncryption(r -> r.id(id))).statusCode());
            assertEquals(404, assertThrows(CloudFrontException.class,
                    () -> second.getFieldLevelEncryptionConfig(r -> r.id(id))).statusCode());
            FieldLevelEncryptionConfig foreign = b.config(b.reference, "other", false).toBuilder()
                    .queryArgProfileConfig(q -> q.forwardWhenQueryArgProfileIsUnknown(false)
                            .queryArgProfiles(p -> p.quantity(1).items(QueryArgProfile.builder()
                                    .profileId(a.profile).queryArg("foreign").build()))).build();
            CloudFrontException rejected = assertThrows(CloudFrontException.class,
                    () -> second.createFieldLevelEncryptionConfig(r -> r.fieldLevelEncryptionConfig(foreign)));
            assertEquals("NoSuchFieldLevelEncryptionProfile", rejected.awsErrorDetails().errorCode());
            assertEquals(404, rejected.statusCode());
            CreateFieldLevelEncryptionConfigResponse ownConfig = b.create(b.config(b.reference, "own", true));
            assertEquals(404, assertThrows(CloudFrontException.class,
                    () -> second.updateFieldLevelEncryptionConfig(r -> r.id(ownConfig.fieldLevelEncryption().id())
                            .ifMatch(ownConfig.eTag()).fieldLevelEncryptionConfig(foreign))).statusCode());
            assertEquals(ownConfig.fieldLevelEncryption().fieldLevelEncryptionConfig(), second
                    .getFieldLevelEncryptionConfig(r -> r.id(ownConfig.fieldLevelEncryption().id()))
                    .fieldLevelEncryptionConfig());
            assertEquals(ownConfig.eTag(), second.getFieldLevelEncryption(r -> r.id(ownConfig.fieldLevelEncryption().id()))
                    .eTag());
            assertEquals(404, assertThrows(CloudFrontException.class,
                    () -> second.getFieldLevelEncryptionProfile(r -> r.id(a.profile))).statusCode());
            assertEquals(404, assertThrows(CloudFrontException.class,
                    () -> second.getFieldLevelEncryptionProfileConfig(r -> r.id(a.profile))).statusCode());
            GetFieldLevelEncryptionProfileResponse ownProfile = second.getFieldLevelEncryptionProfile(r -> r.id(b.profile));
            FieldLevelEncryptionProfileConfig foreignKey = ownProfile.fieldLevelEncryptionProfile()
                    .fieldLevelEncryptionProfileConfig().toBuilder().encryptionEntities(e -> e.quantity(1)
                            .items(ownProfile.fieldLevelEncryptionProfile().fieldLevelEncryptionProfileConfig()
                                    .encryptionEntities().items().getFirst().toBuilder().publicKeyId(a.key).build())).build();
            assertEquals(404, assertThrows(CloudFrontException.class,
                    () -> second.createFieldLevelEncryptionProfile(r -> r.fieldLevelEncryptionProfileConfig(foreignKey)))
                    .statusCode());
            assertEquals(404, assertThrows(CloudFrontException.class,
                    () -> second.updateFieldLevelEncryptionProfile(r -> r.id(b.profile).ifMatch(ownProfile.eTag())
                            .fieldLevelEncryptionProfileConfig(foreignKey))).statusCode());
            assertEquals(ownProfile.fieldLevelEncryptionProfile(), second.getFieldLevelEncryptionProfile(r -> r.id(b.profile))
                    .fieldLevelEncryptionProfile());
            assertEquals(ownProfile.eTag(), second.getFieldLevelEncryptionProfileConfig(r -> r.id(b.profile)).eTag());
            assertEquals(config, first.getFieldLevelEncryption(r -> r.id(id))
                    .fieldLevelEncryption().fieldLevelEncryptionConfig());
        }
    }

    @Test
    void absentOptionalMappingsAndExplicitZeroCollectionsRemainDistinct() throws Exception {
        try (CloudFrontClient client = AwsTestClients.create(CloudFrontClient.builder(), "200000000006", Region.US_EAST_1);
             CloudFrontFieldEncryptionFixtures fixture = new CloudFrontFieldEncryptionFixtures(client)) {
            FieldLevelEncryptionConfig original = fixture.config(fixture.reference, "profile", true);
            CreateFieldLevelEncryptionConfigResponse created = fixture.create(original);
            FieldLevelEncryptionConfig empty = FieldLevelEncryptionConfig.builder().callerReference(fixture.reference)
                    .queryArgProfileConfig(q -> q.forwardWhenQueryArgProfileIsUnknown(true)
                            .queryArgProfiles(p -> p.quantity(0))).build();
            client.updateFieldLevelEncryptionConfig(r -> r.id(created.fieldLevelEncryption().id())
                    .ifMatch(created.eTag()).fieldLevelEncryptionConfig(empty));
            FieldLevelEncryptionConfig returned = client.getFieldLevelEncryption(r -> r.id(created.fieldLevelEncryption().id()))
                    .fieldLevelEncryption().fieldLevelEncryptionConfig();
            assertEquals(empty, returned);
            assertEquals(0, returned.queryArgProfileConfig().queryArgProfiles().quantity());
            assertTrue(returned.queryArgProfileConfig().queryArgProfiles().items().isEmpty());
            assertNull(returned.contentTypeProfileConfig());
        }
    }
}
