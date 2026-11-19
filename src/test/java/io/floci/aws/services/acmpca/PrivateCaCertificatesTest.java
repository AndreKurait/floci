package io.floci.aws.services.acmpca;

import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrivateCaCertificatesTest {

    @Test
    void subordinateImportRejectsItsOwnCertificateInTheChain() {
        PrivateCaCertificates.KeyMaterial material = PrivateCaCertificates.create(Map.of("CommonName", "Root"));
        Instant now = Instant.now();
        X509Certificate root = root(material, now, 3600);

        AwsException failure = assertThrows(AwsException.class, () -> PrivateCaCertificates.importedSubordinate(
                PrivateCaCertificates.pem(root), List.of(root), material.csrPem(), now));

        assertEquals("InvalidRequestException", failure.getErrorCode());
    }

    @Test
    void subordinateImportRejectsSelfSignatureEvenWithADifferentAnchorCertificate() {
        PrivateCaCertificates.KeyMaterial material = PrivateCaCertificates.create(Map.of("CommonName", "Root"));
        Instant now = Instant.now();
        X509Certificate target = root(material, now, 3600);
        X509Certificate anchor = root(material, now, 7200);
        assertNotEquals(target, anchor);

        AwsException failure = assertThrows(AwsException.class, () -> PrivateCaCertificates.importedSubordinate(
                PrivateCaCertificates.pem(target), List.of(anchor), material.csrPem(), now));

        assertEquals("InvalidRequestException", failure.getErrorCode());
    }

    @Test
    void rootImportStillAcceptsSelfSignature() {
        PrivateCaCertificates.KeyMaterial material = PrivateCaCertificates.create(Map.of("CommonName", "Root"));
        Instant now = Instant.now();
        X509Certificate root = root(material, now, 3600);

        assertEquals(root, PrivateCaCertificates.importedRoot(
                PrivateCaCertificates.pem(root), material.csrPem(), now));
    }

    @Test
    void subordinateImportAcceptsSameSubjectWithADifferentSigningKey() {
        PrivateCaCertificates.KeyMaterial parent = PrivateCaCertificates.create(Map.of("CommonName", "Rollover"));
        PrivateCaCertificates.KeyMaterial child = PrivateCaCertificates.create(Map.of("CommonName", "Rollover"));
        Instant now = Instant.now();
        X509Certificate root = root(parent, now, 7200);
        X509Certificate subordinate = PrivateCaCertificates.issue(PrivateCaCertificates.csr(child.csrPem()),
                parent.privateKeyPem(), root, 0, now.minusSeconds(60), now.plusSeconds(3600));

        assertEquals(subordinate, PrivateCaCertificates.importedSubordinate(
                PrivateCaCertificates.pem(subordinate), List.of(root), child.csrPem(), now));
    }

    private static X509Certificate root(PrivateCaCertificates.KeyMaterial material, Instant now, int seconds) {
        return PrivateCaCertificates.issue(PrivateCaCertificates.csr(material.csrPem()),
                material.privateKeyPem(), null, null, now.minusSeconds(60), now.plusSeconds(seconds));
    }
}
