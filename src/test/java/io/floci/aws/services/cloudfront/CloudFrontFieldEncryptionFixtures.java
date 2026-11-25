package io.floci.aws.services.cloudfront;

import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;
import software.amazon.awssdk.services.cloudfront.model.ContentTypeProfile;
import software.amazon.awssdk.services.cloudfront.model.ContentTypeProfileConfig;
import software.amazon.awssdk.services.cloudfront.model.CreateFieldLevelEncryptionConfigResponse;
import software.amazon.awssdk.services.cloudfront.model.CreateFieldLevelEncryptionProfileResponse;
import software.amazon.awssdk.services.cloudfront.model.EncryptionEntity;
import software.amazon.awssdk.services.cloudfront.model.FieldLevelEncryptionConfig;
import software.amazon.awssdk.services.cloudfront.model.FieldLevelEncryptionProfileConfig;
import software.amazon.awssdk.services.cloudfront.model.GetFieldLevelEncryptionResponse;
import software.amazon.awssdk.services.cloudfront.model.QueryArgProfile;

import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Real owned key and profile prerequisites for SDK metadata assertions. */
final class CloudFrontFieldEncryptionFixtures implements AutoCloseable {
    final CloudFrontClient client;
    final String reference = "fle-" + UUID.randomUUID();
    final List<String> configs = new ArrayList<>();
    String key;
    String profile;

    CloudFrontFieldEncryptionFixtures(CloudFrontClient client) throws Exception {
        this.client = client;
        String publicKey = publicPem();
        key = client.createPublicKey(r -> r.publicKeyConfig(c -> c.callerReference(reference)
                .name(reference).encodedKey(publicKey).comment("owned key"))).publicKey().id();
        FieldLevelEncryptionProfileConfig profileConfig = profileConfig(reference, "AuditProvider", "private*");
        CreateFieldLevelEncryptionProfileResponse created = client.createFieldLevelEncryptionProfile(r ->
                r.fieldLevelEncryptionProfileConfig(profileConfig));
        profile = created.fieldLevelEncryptionProfile().id();
        assertEquals(profileConfig, created.fieldLevelEncryptionProfile().fieldLevelEncryptionProfileConfig());
        assertEquals(key, client.getPublicKey(r -> r.id(key)).publicKey().id());
        assertEquals(profile, client.getFieldLevelEncryptionProfile(r -> r.id(profile))
                .fieldLevelEncryptionProfile().id());
    }

    FieldLevelEncryptionProfileConfig profileConfig(String callerReference, String provider, String... patterns) {
        return FieldLevelEncryptionProfileConfig.builder().callerReference(callerReference).name(callerReference)
                .comment("owned profile").encryptionEntities(e -> e.quantity(1)
                        .items(EncryptionEntity.builder().publicKeyId(key).providerId(provider)
                                .fieldPatterns(f -> f.quantity(patterns.length).items(patterns)).build())).build();
    }

    static String publicPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(generator.generateKeyPair()
                        .getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n";
    }

    FieldLevelEncryptionConfig config(String callerReference, String query, boolean content) {
        ContentTypeProfileConfig contentConfig = content ? ContentTypeProfileConfig.builder()
                .forwardWhenContentTypeIsUnknown(false).contentTypeProfiles(p -> p.quantity(1)
                        .items(ContentTypeProfile.builder().contentType("application/x-www-form-urlencoded")
                                .format("URLEncoded").profileId(profile).build())).build() : null;
        return FieldLevelEncryptionConfig.builder().callerReference(callerReference).comment("metadata <&> é")
                .queryArgProfileConfig(q -> q.forwardWhenQueryArgProfileIsUnknown(false)
                        .queryArgProfiles(p -> p.quantity(1)
                                .items(QueryArgProfile.builder().queryArg(query).profileId(profile).build())))
                .contentTypeProfileConfig(contentConfig).build();
    }

    CreateFieldLevelEncryptionConfigResponse create(FieldLevelEncryptionConfig config) {
        CreateFieldLevelEncryptionConfigResponse response = client.createFieldLevelEncryptionConfig(r ->
                r.fieldLevelEncryptionConfig(config));
        configs.add(response.fieldLevelEncryption().id());
        return response;
    }

    void delete(String id) {
        GetFieldLevelEncryptionResponse before = client.getFieldLevelEncryption(r -> r.id(id));
        client.deleteFieldLevelEncryptionConfig(r -> r.id(id).ifMatch(before.eTag()));
        assertEquals(404, assertThrows(CloudFrontException.class,
                () -> client.getFieldLevelEncryption(r -> r.id(id))).statusCode());
        configs.remove(id);
    }

    @Override
    public void close() {
        for (String id : List.copyOf(configs)) {
            delete(id);
        }
        if (profile != null) {
            client.deleteFieldLevelEncryptionProfile(r -> r.id(profile)
                    .ifMatch(client.getFieldLevelEncryptionProfile(g -> g.id(profile)).eTag()));
            assertEquals(404, assertThrows(CloudFrontException.class,
                    () -> client.getFieldLevelEncryptionProfile(r -> r.id(profile))).statusCode());
        }
        if (key != null) {
            client.deletePublicKey(r -> r.id(key).ifMatch(client.getPublicKey(g -> g.id(key)).eTag()));
            assertEquals(404, assertThrows(CloudFrontException.class,
                    () -> client.getPublicKey(r -> r.id(key))).statusCode());
        }
    }
}
