package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudfront.model.FieldLevelEncryptionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.FieldLevelEncryptionProfile;
import io.github.hectorvent.floci.services.cloudfront.model.PublicKey;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CloudFrontFieldEncryptionPersistenceTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void nestedReplacementMetadataAndDeletionSurviveFreshStorageFactories(String mode) throws Exception {
        String id;
        String profileId;
        String keyId;
        String legacyProfileId;
        String etag;
        Instant modified;
        FieldLevelEncryptionConfig expected;
        FieldLevelEncryptionProfile expectedProfile;
        EmulatorConfig config = config();
        StorageFactory first = factory(config, mode);
        try {
            CloudFrontService service = service(first, config);
            PublicKey key = new PublicKey();
            key.setName("owned-public-key");
            key.setCallerReference("owned-public-key");
            key.setEncodedKey(CloudFrontFieldEncryptionFixtures.publicPem());
            keyId = service.createPublicKey(key).getId();
            FieldLevelEncryptionProfile profile = service.createFieldLevelEncryptionProfile(
                    FieldLevelEncryptionConfigCodec.parseProfile(profileXml(keyId, "OldProvider", "old*")));
            profileId = profile.getId();
            expectedProfile = service.updateFieldLevelEncryptionProfile(profileId, profile.getEtag(),
                    FieldLevelEncryptionConfigCodec.parseProfile(profileXml(keyId, "ReplacementProvider", "replacement*")));
            String writtenProfileId = profileId;
            assertEquals(404, assertThrows(AwsException.class, () -> service.updateFieldLevelEncryptionProfile(
                    writtenProfileId, expectedProfile.getEtag(), FieldLevelEncryptionConfigCodec.parseProfile(
                            profileXml("missing-key", "RejectedProvider", "rejected*")))).getHttpStatus());
            assertEquals(expectedProfile.getEncryptionEntities(), service.getFieldLevelEncryptionProfile(profileId)
                    .getEncryptionEntities());
            assertEquals(expectedProfile.getEtag(), service.getFieldLevelEncryptionProfile(profileId).getEtag());
            FieldLevelEncryptionProfile legacy = new FieldLevelEncryptionProfile();
            legacy.setName("legacy-profile");
            legacy.setCallerReference("legacy-profile");
            legacyProfileId = service.createFieldLevelEncryptionProfile(legacy).getId();
            FieldLevelEncryptionConfig created = service.createFieldLevelEncryptionConfig(
                    FieldLevelEncryptionConfigCodec.parse(xml(profileId, "old")));
            id = created.getId();
            expected = service.updateFieldLevelEncryptionConfig(id, created.getEtag(),
                    FieldLevelEncryptionConfigCodec.parse(xml(profileId, "replacement")));
            etag = expected.getEtag();
            modified = expected.getLastModifiedTime();
            assertEquals(profileId, service.getFieldLevelEncryptionProfile(profileId).getId());
            assertEquals(keyId, service.getPublicKey(keyId).getId());
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = factory(config, mode);
        try {
            CloudFrontService service = service(second, config);
            FieldLevelEncryptionConfig restored = service.getFieldLevelEncryptionConfig(id);
            assertEquals(expected.getQueryArgProfileConfig(), restored.getQueryArgProfileConfig());
            assertEquals(expected.getContentTypeProfileConfig(), restored.getContentTypeProfileConfig());
            assertEquals(expected.getComment(), restored.getComment());
            assertEquals(etag, restored.getEtag());
            assertEquals(modified, restored.getLastModifiedTime());
            XmlBuilder serialized = new XmlBuilder().start("FieldLevelEncryptionConfig");
            FieldLevelEncryptionConfigCodec.serialize(serialized, restored);
            FieldLevelEncryptionConfig decoded = FieldLevelEncryptionConfigCodec.parse(
                    serialized.end("FieldLevelEncryptionConfig").build());
            assertEquals(restored.getQueryArgProfileConfig(), decoded.getQueryArgProfileConfig());
            assertEquals(restored.getContentTypeProfileConfig(), decoded.getContentTypeProfileConfig());
            FieldLevelEncryptionProfile restoredProfile = service.getFieldLevelEncryptionProfile(profileId);
            assertEquals(expectedProfile.getEncryptionEntities(), restoredProfile.getEncryptionEntities());
            assertEquals(expectedProfile.getEtag(), restoredProfile.getEtag());
            assertEquals(expectedProfile.getLastModifiedTime(), restoredProfile.getLastModifiedTime());
            XmlBuilder profileXml = new XmlBuilder().start("FieldLevelEncryptionProfileConfig");
            FieldLevelEncryptionConfigCodec.serializeProfile(profileXml, restoredProfile);
            assertEquals(restoredProfile.getEncryptionEntities(), FieldLevelEncryptionConfigCodec.parseProfile(
                    profileXml.end("FieldLevelEncryptionProfileConfig").build()).getEncryptionEntities());
            FieldLevelEncryptionProfile restoredLegacy = service.getFieldLevelEncryptionProfile(legacyProfileId);
            assertNull(restoredLegacy.getEncryptionEntities());
            XmlBuilder legacyXml = new XmlBuilder().start("FieldLevelEncryptionProfileConfig");
            FieldLevelEncryptionConfigCodec.serializeProfile(legacyXml, restoredLegacy);
            assertFalse(legacyXml.end("FieldLevelEncryptionProfileConfig").build().contains("EncryptionEntities"));
            service.deleteFieldLevelEncryptionProfile(legacyProfileId, restoredLegacy.getEtag());
            assertEquals(keyId, service.getPublicKey(keyId).getId());
            service.deleteFieldLevelEncryptionConfig(id, etag);
            service.deleteFieldLevelEncryptionProfile(profileId,
                    service.getFieldLevelEncryptionProfile(profileId).getEtag());
            service.deletePublicKey(keyId, service.getPublicKey(keyId).getEtag());
        } finally {
            second.shutdownAll();
        }
        StorageFactory third = factory(config, mode);
        try {
            CloudFrontService service = service(third, config);
            assertEquals(404, assertThrows(AwsException.class,
                    () -> service.getFieldLevelEncryptionConfig(id)).getHttpStatus());
            assertEquals(404, assertThrows(AwsException.class,
                    () -> service.getFieldLevelEncryptionProfile(profileId)).getHttpStatus());
            assertEquals(404, assertThrows(AwsException.class, () -> service.getPublicKey(keyId)).getHttpStatus());
            assertEquals(404, assertThrows(AwsException.class,
                    () -> service.getFieldLevelEncryptionProfile(legacyProfileId)).getHttpStatus());
        } finally {
            third.shutdownAll();
        }
    }

    private static String profileXml(String key, String provider, String pattern) {
        return """
                <FieldLevelEncryptionProfileConfig><Name>persisted-profile</Name><CallerReference>persisted-profile</CallerReference>
                  <EncryptionEntities><Quantity>1</Quantity><Items><EncryptionEntity>
                    <PublicKeyId>%s</PublicKeyId><ProviderId>%s</ProviderId>
                    <FieldPatterns><Quantity>1</Quantity><Items><FieldPattern>%s</FieldPattern></Items></FieldPatterns>
                  </EncryptionEntity></Items></EncryptionEntities>
                </FieldLevelEncryptionProfileConfig>
                """.formatted(key, provider, pattern);
    }

    private static String xml(String profile, String query) {
        return """
                <FieldLevelEncryptionConfig><CallerReference>persisted</CallerReference><Comment> &amp; é </Comment>
                  <QueryArgProfileConfig><ForwardWhenQueryArgProfileIsUnknown>false</ForwardWhenQueryArgProfileIsUnknown>
                    <QueryArgProfiles><Quantity>1</Quantity><Items><QueryArgProfile>
                      <ProfileId>%s</ProfileId><QueryArg>%s</QueryArg>
                    </QueryArgProfile></Items></QueryArgProfiles>
                  </QueryArgProfileConfig>
                  <ContentTypeProfileConfig><ForwardWhenContentTypeIsUnknown>true</ForwardWhenContentTypeIsUnknown>
                    <ContentTypeProfiles><Quantity>1</Quantity><Items><ContentTypeProfile>
                      <ContentType>application/x-www-form-urlencoded</ContentType><Format>URLEncoded</Format>
                      <ProfileId>%s</ProfileId>
                    </ContentTypeProfile></Items></ContentTypeProfiles>
                  </ContentTypeProfileConfig>
                </FieldLevelEncryptionConfig>
                """.formatted(profile, query, profile);
    }

    private EmulatorConfig config() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        when(config.dns().extraSuffixes()).thenReturn(Optional.empty());
        when(config.services().cloudfront().domainSuffix()).thenReturn(Optional.empty());
        return config;
    }

    private StorageFactory factory(EmulatorConfig config, String mode) {
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(60000L);
        return new StorageFactory(config, access);
    }

    private static CloudFrontService service(StorageFactory factory, EmulatorConfig config) {
        return new CloudFrontService(factory, config, new RegionResolver("us-east-1", "000000000000"));
    }
}
