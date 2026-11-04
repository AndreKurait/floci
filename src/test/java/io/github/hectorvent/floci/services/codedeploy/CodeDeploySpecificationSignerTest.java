package io.github.hectorvent.floci.services.codedeploy;

import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeDeploySpecificationSignerTest {

    @TempDir
    Path directory;

    @Test
    void theOrdinaryLocalAuthoritySignsAnAttachedSpecification() throws Exception {
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(directory);
        verify(new CodeDeploySpecificationSigner(authority), authority.certificate());
    }

    @Test
    void anEllipticCurveIssuerProducesAVerifiableSignature() throws Exception {
        CertificateGenerator.GeneratedCertificate generated = new CertificateGenerator()
                .generateSelfSignedCertificate("floci.test", List.of(), KeyAlgorithm.EC_prime256v1);
        CertificateGenerator.Issuer issuer = new CertificateGenerator.Issuer(
                Pem.parseCertificate(generated.certificatePem()), Pem.parsePrivateKey(generated.privateKeyPem()));
        verify(new CodeDeploySpecificationSigner(issuer), issuer.certificate());
    }

    @Test
    void aProviderSpecificEllipticCurveKeyProducesAVerifiableSignature() throws Exception {
        CertificateGenerator.GeneratedCertificate generated = new CertificateGenerator()
                .generateSelfSignedCertificate("floci.test", List.of(), KeyAlgorithm.EC_prime256v1);
        CertificateGenerator.Issuer issuer = new CertificateGenerator.Issuer(
                Pem.parseCertificate(generated.certificatePem()), new JcaPEMKeyConverter()
                        .setProvider(new BouncyCastleProvider()).getPrivateKey(PrivateKeyInfo.getInstance(
                                Pem.parsePrivateKey(generated.privateKeyPem()).getEncoded())));
        assertEquals("ECDSA", issuer.key().getAlgorithm());
        verify(new CodeDeploySpecificationSigner(issuer), issuer.certificate());
    }

    private static void verify(CodeDeploySpecificationSigner specificationSigner,
                               X509Certificate certificate) throws Exception {
        String original = "{\"DeploymentId\":\"d-original\",\"Revision\":{\"Key\":\"release.zip\"}}";
        String envelope = specificationSigner.sign(original);
        CMSSignedData signed;
        try (PemReader reader = new PemReader(new StringReader(envelope))) {
            signed = new CMSSignedData(reader.readPemObject().getContent());
        }
        assertEquals(original, new String((byte[]) signed.getSignedContent().getContent(), StandardCharsets.UTF_8));
        assertEquals(1, signed.getSignerInfos().size());
        SignerInformation signer = signed.getSignerInfos().getSigners().iterator().next();
        assertTrue(signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(certificate)));
        assertEquals(1, signed.getCertificates().getMatches(signer.getSID()).size());
    }
}
