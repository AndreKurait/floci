package io.github.hectorvent.floci.services.acmpca;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acmpca.AcmPcaClient;
import software.amazon.awssdk.services.acmpca.model.AcmPcaException;
import software.amazon.awssdk.services.acmpca.model.Tag;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Floci's reserved {@code floci:override-id} create tag names the id in a new authority's ARN. */
@QuarkusTest
@Timeout(120)
class AcmPcaOverrideIdIntegrationTest {

    private static final String OVERRIDE = "floci:override-id";

    @Test
    void theOverrideTagSetsTheAuthorityIdAndIsNotStored() {
        String account = Long.toString(850_000_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()));
        try (AcmPcaClient client = AwsTestClients.create(AcmPcaClient.builder(), account, Region.US_EAST_1)) {
            String id = UUID.randomUUID().toString();
            String arn = create(client, tag(OVERRIDE, id), tag("team", "pki"));
            assertEquals("arn:aws:acm-pca:us-east-1:" + account + ":certificate-authority/" + id, arn);
            assertEquals(arn, client.describeCertificateAuthority(request -> request.certificateAuthorityArn(arn))
                    .certificateAuthority().arn());
            assertEquals(List.of(tag("team", "pki")),
                    client.listTags(request -> request.certificateAuthorityArn(arn)).tags());

            error("InvalidArgsException", () -> create(client, tag(OVERRIDE, id)));
            error("InvalidTagException", () -> create(client, tag(OVERRIDE, UUID.randomUUID().toString()
                    .toUpperCase(Locale.ROOT))));
            error("InvalidTagException", () -> create(client, tag(OVERRIDE, "issuing-ca")));
            error("InvalidTagException", () -> client.tagCertificateAuthority(request -> request
                    .certificateAuthorityArn(arn).tags(tag(OVERRIDE, UUID.randomUUID().toString()))));
            assertEquals(1, client.listCertificateAuthorities().certificateAuthorities().size());
        }
    }

    private static String create(AcmPcaClient client, Tag... tags) {
        return client.createCertificateAuthority(request -> request.certificateAuthorityType("SUBORDINATE")
                .certificateAuthorityConfiguration(config -> config.keyAlgorithm("RSA_2048")
                        .signingAlgorithm("SHA256WITHRSA").subject(subject -> subject.commonName("Pinned CA")))
                .tags(tags)).certificateAuthorityArn();
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
    }

    private static void error(String code, Runnable action) {
        AcmPcaException failure = assertThrows(AcmPcaException.class, action::run);
        assertEquals(400, failure.statusCode());
        assertEquals(code, failure.awsErrorDetails().errorCode());
    }
}
