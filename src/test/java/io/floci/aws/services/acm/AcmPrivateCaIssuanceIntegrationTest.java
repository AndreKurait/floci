package io.floci.aws.services.acm;

import io.floci.aws.core.common.Pem;
import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acm.AcmClient;
import software.amazon.awssdk.services.acm.model.CertificateDetail;
import software.amazon.awssdk.services.acm.model.CertificateStatus;
import software.amazon.awssdk.services.acm.model.CertificateType;
import software.amazon.awssdk.services.acm.model.ExportCertificateResponse;
import software.amazon.awssdk.services.acm.model.FailureReason;
import software.amazon.awssdk.services.acm.model.GetCertificateResponse;
import software.amazon.awssdk.services.acm.model.KeyAlgorithm;
import software.amazon.awssdk.services.acm.model.RequestInProgressException;
import software.amazon.awssdk.services.acmpca.AcmPcaClient;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code RequestCertificate} with the {@code CertificateAuthorityArn} of an authority AWS Private CA holds:
 * an ACTIVE authority signs the PRIVATE certificate and its chain is returned with it; one that is not ACTIVE
 * fails the certificate with {@code PCA_INVALID_STATE}.
 */
@QuarkusTest
@Timeout(120)
class AcmPrivateCaIssuanceIntegrationTest {

    private static final String TEMPLATES = "arn:aws:acm-pca:::template/";
    private static final String PASSPHRASE = "private-ca-passphrase";

    @Test
    void anActiveSubordinateSignsThePrivateCertificateAndItsChainReachesTheRoot() throws Exception {
        String account = account();
        try (AcmPcaClient pca = AwsTestClients.create(AcmPcaClient.builder(), account, Region.US_EAST_1);
             AcmClient acm = AwsTestClients.create(AcmClient.builder(), account, Region.US_EAST_1)) {
            String root = create(pca, "ROOT", "Example Root CA");
            X509Certificate rootCertificate = activate(pca, root, root, "RootCACertificate/V1", 3650);
            String issuing = create(pca, "SUBORDINATE", "Example Issuing CA");
            X509Certificate issuingCertificate = activate(pca, root, issuing, "SubordinateCACertificate_PathLen0/V1", 1825);

            String arn = acm.requestCertificate(request -> request.domainName("api.example.com")
                    .subjectAlternativeNames("api.example.com", "www.example.com")
                    .keyAlgorithm(KeyAlgorithm.RSA_2048).certificateAuthorityArn(issuing)).certificateArn();
            CertificateDetail detail = acm.describeCertificate(request -> request.certificateArn(arn)).certificate();
            assertEquals(CertificateStatus.ISSUED, detail.status());
            assertEquals(CertificateType.PRIVATE, detail.type());
            assertEquals(issuing, detail.certificateAuthorityArn());
            assertEquals("RSA-2048", detail.keyAlgorithmAsString());

            GetCertificateResponse issued = acm.getCertificate(request -> request.certificateArn(arn));
            X509Certificate leaf = Pem.parseCertificate(issued.certificate());
            assertEquals(leaf.getNotBefore().toInstant(), detail.notBefore().truncatedTo(ChronoUnit.SECONDS));
            assertEquals(leaf.getNotAfter().toInstant(), detail.notAfter().truncatedTo(ChronoUnit.SECONDS));
            assertTrue(Duration.between(Instant.now().plus(395, ChronoUnit.DAYS), leaf.getNotAfter().toInstant())
                    .abs().toMinutes() < 10);
            assertEquals(issuingCertificate.getSubjectX500Principal(), leaf.getIssuerX500Principal());
            leaf.verify(issuingCertificate.getPublicKey());
            List<X509Certificate> chain = readAll(issued.certificateChain());
            assertEquals(List.of(issuingCertificate, rootCertificate), chain);
            validate(leaf, chain);

            ExportCertificateResponse exported = acm.exportCertificate(request -> request.certificateArn(arn)
                    .passphrase(SdkBytes.fromString(PASSPHRASE, StandardCharsets.UTF_8)));
            assertEquals(issued.certificate(), exported.certificate());
            assertEquals(issued.certificateChain(), exported.certificateChain());
            assertTrue(exported.privateKey().startsWith("-----BEGIN ENCRYPTED PRIVATE KEY-----"));
            assertTrue(Pem.isPair(decrypt(exported.privateKey()), leaf.getPublicKey()));

            acm.renewCertificate(request -> request.certificateArn(arn));
            GetCertificateResponse renewed = acm.getCertificate(request -> request.certificateArn(arn));
            assertNotEquals(issued.certificate(), renewed.certificate());
            assertEquals(issued.certificateChain(), renewed.certificateChain());
            Pem.parseCertificate(renewed.certificate()).verify(issuingCertificate.getPublicKey());
        }
    }

    @Test
    void aPrivateCertificateNeverOutlivesItsIssuingAuthority() throws Exception {
        String account = account();
        try (AcmPcaClient pca = AwsTestClients.create(AcmPcaClient.builder(), account, Region.US_EAST_1);
             AcmClient acm = AwsTestClients.create(AcmClient.builder(), account, Region.US_EAST_1)) {
            String root = create(pca, "ROOT", "Short Root CA");
            X509Certificate rootCertificate = activate(pca, root, root, "RootCACertificate/V1", 30);
            String arn = acm.requestCertificate(request -> request.domainName("short.example.com")
                    .certificateAuthorityArn(root)).certificateArn();
            X509Certificate leaf = Pem.parseCertificate(acm.getCertificate(request -> request.certificateArn(arn))
                    .certificate());
            assertEquals(rootCertificate.getNotAfter(), leaf.getNotAfter());
            leaf.verify(rootCertificate.getPublicKey());
        }
    }

    @Test
    void aHeldAuthorityThatIsNotActiveFailsTheCertificate() throws Exception {
        String account = account();
        try (AcmPcaClient pca = AwsTestClients.create(AcmPcaClient.builder(), account, Region.US_EAST_1);
             AcmClient acm = AwsTestClients.create(AcmClient.builder(), account, Region.US_EAST_1)) {
            String pending = create(pca, "SUBORDINATE", "Pending Issuing CA");
            String disabled = create(pca, "ROOT", "Disabled Root CA");
            activate(pca, disabled, disabled, "RootCACertificate/V1", 365);
            pca.updateCertificateAuthority(request -> request.certificateAuthorityArn(disabled).status("DISABLED"));

            for (String ca : List.of(pending, disabled)) {
                String arn = acm.requestCertificate(request -> request.domainName("failed.example.com")
                        .certificateAuthorityArn(ca)).certificateArn();
                CertificateDetail detail = acm.describeCertificate(request -> request.certificateArn(arn)).certificate();
                assertEquals(CertificateStatus.FAILED, detail.status());
                assertEquals(FailureReason.PCA_INVALID_STATE, detail.failureReason());
                assertEquals(CertificateType.PRIVATE, detail.type());
                assertEquals(ca, detail.certificateAuthorityArn());
                assertThrows(RequestInProgressException.class,
                        () -> acm.getCertificate(request -> request.certificateArn(arn)));
            }
        }
    }

    private static String account() {
        return Long.toString(830_000_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()));
    }

    private static String create(AcmPcaClient pca, String type, String commonName) {
        return pca.createCertificateAuthority(request -> request.certificateAuthorityType(type)
                .certificateAuthorityConfiguration(config -> config.keyAlgorithm("RSA_2048")
                        .signingAlgorithm("SHA256WITHRSA").subject(subject -> subject
                                .country("US").organization("Example Org").commonName(commonName))))
                .certificateAuthorityArn();
    }

    /** Signs {@code ca}'s CSR on {@code issuer} with {@code template} and imports it, with the chain for a subordinate. */
    private static X509Certificate activate(AcmPcaClient pca, String issuer, String ca, String template, long days) {
        String csr = pca.getCertificateAuthorityCsr(request -> request.certificateAuthorityArn(ca)).csr();
        String certificateArn = pca.issueCertificate(request -> request.certificateAuthorityArn(issuer)
                .csr(SdkBytes.fromUtf8String(csr)).signingAlgorithm("SHA256WITHRSA").templateArn(TEMPLATES + template)
                .validity(validity -> validity.type("DAYS").value(days))).certificateArn();
        String certificate = pca.getCertificate(request -> request.certificateAuthorityArn(issuer)
                .certificateArn(certificateArn)).certificate();
        String chain = pca.getCertificate(request -> request.certificateAuthorityArn(issuer)
                .certificateArn(certificateArn)).certificateChain();
        pca.importCertificateAuthorityCertificate(request -> request.certificateAuthorityArn(ca)
                .certificate(SdkBytes.fromUtf8String(certificate))
                .certificateChain(chain == null ? null : SdkBytes.fromUtf8String(chain)));
        return Pem.parseCertificate(certificate);
    }

    private static void validate(X509Certificate leaf, List<X509Certificate> chain) throws Exception {
        List<X509Certificate> path = new ArrayList<>();
        path.add(leaf);
        path.addAll(chain.subList(0, chain.size() - 1));
        PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(chain.get(chain.size() - 1), null)));
        parameters.setRevocationEnabled(false);
        CertPathValidator.getInstance("PKIX").validate(
                CertificateFactory.getInstance("X.509").generateCertPath(path), parameters);
    }

    private static List<X509Certificate> readAll(String text) throws Exception {
        List<X509Certificate> certificates = new ArrayList<>();
        try (PEMParser parser = new PEMParser(new StringReader(text))) {
            for (Object object = parser.readObject(); object != null; object = parser.readObject()) {
                certificates.add(new JcaX509CertificateConverter().getCertificate(
                        assertInstanceOf(X509CertificateHolder.class, object)));
            }
        }
        return certificates;
    }

    private static PrivateKey decrypt(String encryptedPem) throws Exception {
        String base64 = encryptedPem.replace("-----BEGIN ENCRYPTED PRIVATE KEY-----", "")
                .replace("-----END ENCRYPTED PRIVATE KEY-----", "").replaceAll("\\s", "");
        EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(Base64.getDecoder().decode(base64));
        AlgorithmParameters parameters = info.getAlgParameters();
        String pbe = parameters.toString();
        SecretKey secret = SecretKeyFactory.getInstance(pbe).generateSecret(new PBEKeySpec(PASSPHRASE.toCharArray()));
        Cipher cipher = Cipher.getInstance(pbe);
        cipher.init(Cipher.DECRYPT_MODE, secret, parameters);
        return KeyFactory.getInstance("RSA").generatePrivate(info.getKeySpec(cipher));
    }
}
