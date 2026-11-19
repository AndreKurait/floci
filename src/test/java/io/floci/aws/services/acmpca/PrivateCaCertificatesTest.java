package io.floci.aws.services.acmpca;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Pem;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
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

    @ParameterizedTest
    @CsvSource({"false,true,32", "true,false,32", "true,true,128", "true,true,-1"})
    void subordinateImportRejectsAnchorWithoutCaSigningProfile(boolean ca, boolean critical, int keyUsage)
            throws Exception {
        PrivateCaCertificates.KeyMaterial parent = PrivateCaCertificates.create(Map.of("CommonName", "Anchor"));
        PrivateCaCertificates.KeyMaterial child = PrivateCaCertificates.create(Map.of("CommonName", "Child"));
        Instant now = Instant.now();
        PKCS10CertificationRequest request = PrivateCaCertificates.csr(parent.csrPem());
        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(request.getSubject(), BigInteger.ONE,
                Date.from(now.minusSeconds(60)), Date.from(now.plusSeconds(7200)),
                request.getSubject(), request.getSubjectPublicKeyInfo());
        builder.addExtension(Extension.basicConstraints, critical, new BasicConstraints(ca));
        if (keyUsage >= 0) {
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsage));
        }
        X509Certificate anchor = new JcaX509CertificateConverter().getCertificate(builder.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(Pem.parsePrivateKey(parent.privateKeyPem()))));
        X509Certificate subordinate = PrivateCaCertificates.issue(PrivateCaCertificates.csr(child.csrPem()),
                parent.privateKeyPem(), anchor, 0, now.minusSeconds(60), now.plusSeconds(3600));

        AwsException failure = assertThrows(AwsException.class, () -> PrivateCaCertificates.importedSubordinate(
                PrivateCaCertificates.pem(subordinate), List.of(anchor), child.csrPem(), now));

        assertEquals("InvalidRequestException", failure.getErrorCode());
    }

    private static X509Certificate root(PrivateCaCertificates.KeyMaterial material, Instant now, int seconds) {
        return PrivateCaCertificates.issue(PrivateCaCertificates.csr(material.csrPem()),
                material.privateKeyPem(), null, null, now.minusSeconds(60), now.plusSeconds(seconds));
    }
}
