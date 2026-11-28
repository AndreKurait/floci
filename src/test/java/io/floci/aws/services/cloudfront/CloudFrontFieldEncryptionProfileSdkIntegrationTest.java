package io.floci.aws.services.cloudfront;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;
import software.amazon.awssdk.services.cloudfront.model.FieldLevelEncryptionProfileConfig;
import software.amazon.awssdk.services.cloudfront.model.GetFieldLevelEncryptionProfileConfigResponse;
import software.amazon.awssdk.services.cloudfront.model.GetFieldLevelEncryptionProfileResponse;
import software.amazon.awssdk.services.cloudfront.model.UpdateFieldLevelEncryptionProfileResponse;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudFrontFieldEncryptionProfileSdkIntegrationTest {
    @Test
    void bothProfileGettersReturnExactOwnedEntitiesAndFullReplacementPreservesSibling() throws Exception {
        try (CloudFrontClient client = AwsTestClients.create(CloudFrontClient.builder(), "200000000007", Region.US_EAST_1);
             CloudFrontFieldEncryptionFixtures fixture = new CloudFrontFieldEncryptionFixtures(client);
             CloudFrontFieldEncryptionFixtures sibling = new CloudFrontFieldEncryptionFixtures(client)) {
            GetFieldLevelEncryptionProfileResponse original = client.getFieldLevelEncryptionProfile(r -> r.id(fixture.profile));
            FieldLevelEncryptionProfileConfig expected = fixture.profileConfig(fixture.reference, "AuditProvider", "private*");
            assertEquals(expected, original.fieldLevelEncryptionProfile().fieldLevelEncryptionProfileConfig());
            assertEquals(expected, client.getFieldLevelEncryptionProfileConfig(r -> r.id(fixture.profile))
                    .fieldLevelEncryptionProfileConfig());
            assertEquals(original.eTag(), client.getFieldLevelEncryptionProfileConfig(r -> r.id(fixture.profile)).eTag());
            FieldLevelEncryptionProfileConfig replacement = fixture.profileConfig(fixture.reference,
                    "ReplacementProvider", "billing*", "Payment").toBuilder().comment(null).build();
            UpdateFieldLevelEncryptionProfileResponse updated = client.updateFieldLevelEncryptionProfile(r -> r
                    .id(fixture.profile).ifMatch(original.eTag()).fieldLevelEncryptionProfileConfig(replacement));
            GetFieldLevelEncryptionProfileResponse read = client.getFieldLevelEncryptionProfile(r -> r.id(fixture.profile));
            GetFieldLevelEncryptionProfileConfigResponse config = client.getFieldLevelEncryptionProfileConfig(r -> r.id(fixture.profile));
            assertEquals(replacement, updated.fieldLevelEncryptionProfile().fieldLevelEncryptionProfileConfig());
            assertEquals(replacement, read.fieldLevelEncryptionProfile().fieldLevelEncryptionProfileConfig());
            assertEquals(replacement, config.fieldLevelEncryptionProfileConfig());
            assertEquals(fixture.profile, read.fieldLevelEncryptionProfile().id());
            assertEquals(updated.eTag(), read.eTag());
            assertEquals(updated.eTag(), config.eTag());
            assertNotEquals(original.eTag(), read.eTag());
            assertNotNull(read.fieldLevelEncryptionProfile().lastModifiedTime());
            assertEquals(updated.fieldLevelEncryptionProfile().lastModifiedTime(), read.fieldLevelEncryptionProfile().lastModifiedTime());
            assertFalse(read.fieldLevelEncryptionProfile().lastModifiedTime()
                    .isBefore(original.fieldLevelEncryptionProfile().lastModifiedTime()));
            assertNull(config.fieldLevelEncryptionProfileConfig().comment());
            assertEquals(sibling.profileConfig(sibling.reference, "AuditProvider", "private*"), client
                    .getFieldLevelEncryptionProfileConfig(r -> r.id(sibling.profile)).fieldLevelEncryptionProfileConfig());
            String deleted = fixture.profile;
            client.deleteFieldLevelEncryptionProfile(r -> r.id(deleted).ifMatch(read.eTag()));
            fixture.profile = null;
            assertEquals(404, assertThrows(CloudFrontException.class,
                    () -> client.getFieldLevelEncryptionProfile(r -> r.id(deleted))).statusCode());
            assertEquals(404, assertThrows(CloudFrontException.class,
                    () -> client.getFieldLevelEncryptionProfileConfig(r -> r.id(deleted))).statusCode());
            assertEquals(sibling.profileConfig(sibling.reference, "AuditProvider", "private*"), client
                    .getFieldLevelEncryptionProfile(r -> r.id(sibling.profile)).fieldLevelEncryptionProfile()
                    .fieldLevelEncryptionProfileConfig());
        }
    }

    @Test
    void profileQuantityAndMissingKeyRefusalsPreserveStoredEntitiesAndEtag() throws Exception {
        try (CloudFrontClient client = AwsTestClients.create(CloudFrontClient.builder(), "200000000008", Region.US_EAST_1);
             CloudFrontFieldEncryptionFixtures fixture = new CloudFrontFieldEncryptionFixtures(client)) {
            GetFieldLevelEncryptionProfileResponse before = client.getFieldLevelEncryptionProfile(r -> r.id(fixture.profile));
            FieldLevelEncryptionProfileConfig original = before.fieldLevelEncryptionProfile().fieldLevelEncryptionProfileConfig();
            for (boolean entityQuantity : new boolean[]{false, true}) {
                FieldLevelEncryptionProfileConfig invalid = original.toBuilder().encryptionEntities(entityQuantity
                        ? original.encryptionEntities().toBuilder().quantity(2).build()
                        : original.encryptionEntities().toBuilder().items(original.encryptionEntities().items().getFirst()
                                .toBuilder().fieldPatterns(p -> p.quantity(2).items("one")).build()).build()).build();
                CloudFrontException create = assertThrows(CloudFrontException.class,
                        () -> client.createFieldLevelEncryptionProfile(r -> r.fieldLevelEncryptionProfileConfig(invalid)));
                assertEquals("InconsistentQuantities", create.awsErrorDetails().errorCode());
                assertEquals(400, create.statusCode());
                CloudFrontException update = assertThrows(CloudFrontException.class,
                        () -> client.updateFieldLevelEncryptionProfile(r -> r.id(fixture.profile).ifMatch(before.eTag())
                                .fieldLevelEncryptionProfileConfig(invalid)));
                assertEquals("InconsistentQuantities", update.awsErrorDetails().errorCode());
                assertEquals(before.fieldLevelEncryptionProfile(), client.getFieldLevelEncryptionProfile(r -> r.id(fixture.profile))
                        .fieldLevelEncryptionProfile());
                assertEquals(before.eTag(), client.getFieldLevelEncryptionProfileConfig(r -> r.id(fixture.profile)).eTag());
            }
            FieldLevelEncryptionProfileConfig missingKey = original.toBuilder().encryptionEntities(e -> e.quantity(1)
                    .items(original.encryptionEntities().items().getFirst().toBuilder().publicKeyId("K0000000000000").build()))
                    .build();
            assertEquals(404, assertThrows(CloudFrontException.class,
                    () -> client.updateFieldLevelEncryptionProfile(r -> r.id(fixture.profile).ifMatch(before.eTag())
                            .fieldLevelEncryptionProfileConfig(missingKey))).statusCode());
            FieldLevelEncryptionProfileConfig overlap = fixture.profileConfig(fixture.reference, "Provider", "AB*", "ABC*");
            assertEquals(400, assertThrows(CloudFrontException.class,
                    () -> client.updateFieldLevelEncryptionProfile(r -> r.id(fixture.profile).ifMatch(before.eTag())
                            .fieldLevelEncryptionProfileConfig(overlap))).statusCode());
            assertEquals(original, client.getFieldLevelEncryptionProfileConfig(r -> r.id(fixture.profile))
                    .fieldLevelEncryptionProfileConfig());
            assertEquals(before.eTag(), client.getFieldLevelEncryptionProfile(r -> r.id(fixture.profile)).eTag());
        }
    }
}
