package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.cloudfront.model.FieldLevelEncryptionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.FieldLevelEncryptionProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FieldLevelEncryptionConfigCodecTest {
    @Test
    void namespaceQualifiedNestedStringsRoundTripWithoutLosingEscapingOrWhitespace() {
        FieldLevelEncryptionConfig config = FieldLevelEncryptionConfigCodec.parse("""
                <cf:FieldLevelEncryptionConfig xmlns:cf="urn:test">
                  <cf:CallerReference> ref &amp; &lt; é </cf:CallerReference>
                  <cf:Comment> comment "quoted" </cf:Comment>
                  <cf:QueryArgProfileConfig>
                    <cf:ForwardWhenQueryArgProfileIsUnknown>false</cf:ForwardWhenQueryArgProfileIsUnknown>
                    <cf:QueryArgProfiles><cf:Quantity>1</cf:Quantity><cf:Items>
                      <cf:QueryArgProfile><cf:ProfileId>profile-id</cf:ProfileId>
                        <cf:QueryArg> value &amp; é </cf:QueryArg></cf:QueryArgProfile>
                    </cf:Items></cf:QueryArgProfiles>
                  </cf:QueryArgProfileConfig>
                </cf:FieldLevelEncryptionConfig>
                """);
        XmlBuilder xml = new XmlBuilder().start("FieldLevelEncryptionConfig");
        FieldLevelEncryptionConfigCodec.serialize(xml, config);
        FieldLevelEncryptionConfig copy = FieldLevelEncryptionConfigCodec.parse(
                xml.end("FieldLevelEncryptionConfig").build());
        assertEquals(" ref & < é ", copy.getCallerReference());
        assertEquals(" comment \"quoted\" ", copy.getComment());
        assertEquals(config.getQueryArgProfileConfig(), copy.getQueryArgProfileConfig());
        assertNull(copy.getContentTypeProfileConfig());
        assertEquals("profile-id", FieldLevelEncryptionConfigCodec.profileIds(copy).iterator().next());
    }

    @Test
    void optionalListAndBlockAbsenceDoNotInventDefaults() {
        FieldLevelEncryptionConfig config = FieldLevelEncryptionConfigCodec.parse("""
                <FieldLevelEncryptionConfig><CallerReference>ref</CallerReference>
                  <QueryArgProfileConfig>
                    <ForwardWhenQueryArgProfileIsUnknown>true</ForwardWhenQueryArgProfileIsUnknown>
                  </QueryArgProfileConfig>
                </FieldLevelEncryptionConfig>
                """);
        assertEquals(Map.of("ForwardWhenQueryArgProfileIsUnknown", true), config.getQueryArgProfileConfig());
        assertNull(config.getContentTypeProfileConfig());
        assertNull(config.getComment());
        assertTrue(FieldLevelEncryptionConfigCodec.profileIds(config).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"-1,InconsistentQuantities", "2,InconsistentQuantities", "abc,InvalidArgument"})
    void invalidDeclaredQuantityIsRejected(String quantity, String code) {
        String body = """
                <FieldLevelEncryptionConfig><CallerReference>ref</CallerReference>
                  <QueryArgProfileConfig>
                    <ForwardWhenQueryArgProfileIsUnknown>true</ForwardWhenQueryArgProfileIsUnknown>
                    <QueryArgProfiles><Quantity>%s</Quantity></QueryArgProfiles>
                  </QueryArgProfileConfig>
                </FieldLevelEncryptionConfig>
                """.formatted(quantity);
        AwsException error = assertThrows(AwsException.class, () -> FieldLevelEncryptionConfigCodec.parse(body));
        assertEquals(code, error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
    }

    @Test
    void profileEntitiesRetainNestedStringsCountsAndCaseSensitivePatterns() {
        FieldLevelEncryptionProfile profile = FieldLevelEncryptionConfigCodec.parseProfile("""
                <FieldLevelEncryptionProfileConfig><Name>profile</Name><CallerReference>ref</CallerReference>
                  <Comment> &amp; &lt; é </Comment><EncryptionEntities><Quantity>1</Quantity><Items><EncryptionEntity>
                    <PublicKeyId>key</PublicKeyId><ProviderId>Provider</ProviderId>
                    <FieldPatterns><Quantity>2</Quantity><Items><FieldPattern>Billing*</FieldPattern>
                      <FieldPattern>billing*</FieldPattern></Items></FieldPatterns>
                  </EncryptionEntity></Items></EncryptionEntities>
                </FieldLevelEncryptionProfileConfig>
                """);
        XmlBuilder xml = new XmlBuilder().start("FieldLevelEncryptionProfileConfig");
        FieldLevelEncryptionConfigCodec.serializeProfile(xml, profile);
        FieldLevelEncryptionProfile copy = FieldLevelEncryptionConfigCodec.parseProfile(
                xml.end("FieldLevelEncryptionProfileConfig").build());
        assertEquals(" & < é ", copy.getComment());
        assertEquals(profile.getEncryptionEntities(), copy.getEncryptionEntities());
        assertEquals("key", FieldLevelEncryptionConfigCodec.publicKeyIds(copy).iterator().next());
    }

    @Test
    void emptyProfileCollectionDoesNotInventItems() {
        FieldLevelEncryptionProfile profile = FieldLevelEncryptionConfigCodec.parseProfile("""
                <FieldLevelEncryptionProfileConfig><Name>profile</Name><CallerReference>ref</CallerReference>
                  <EncryptionEntities><Quantity>0</Quantity></EncryptionEntities>
                </FieldLevelEncryptionProfileConfig>
                """);
        assertEquals(Map.of("Quantity", 0), profile.getEncryptionEntities());
        assertTrue(FieldLevelEncryptionConfigCodec.publicKeyIds(profile).isEmpty());
        assertNull(profile.getComment());
        assertThrows(AwsException.class, () -> FieldLevelEncryptionConfigCodec.parseProfile(
                "<FieldLevelEncryptionProfileConfig><Name>p</Name><CallerReference>r</CallerReference>"
                        + "</FieldLevelEncryptionProfileConfig>"));
    }

    @Test
    void malformedTrailingXmlIsRejectedBeforeStorage() {
        assertThrows(AwsException.class, () -> FieldLevelEncryptionConfigCodec.parse(
                "<FieldLevelEncryptionConfig><CallerReference>ref</CallerReference></FieldLevelEncryptionConfig><"));
    }
}
