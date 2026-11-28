package io.floci.aws.services.acmpca;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acmpca.AcmPcaClient;
import software.amazon.awssdk.services.acmpca.model.AcmPcaException;
import software.amazon.awssdk.services.acmpca.model.CertificateAuthority;
import software.amazon.awssdk.services.acmpca.model.GetCertificateAuthorityCertificateResponse;
import software.amazon.awssdk.services.acmpca.model.GetCertificateResponse;
import software.amazon.awssdk.services.acmpca.model.ListTagsResponse;
import software.amazon.awssdk.services.acmpca.model.Tag;

import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Subordinate authorities, their certificate chains, and authority tags, through the AWS SDK. */
@QuarkusTest
@Timeout(120)
class AcmPcaSubordinateIntegrationTest {

    private static final String TEMPLATES = "arn:aws:acm-pca:::template/";

    @Test
    void aSubordinateSignedByItsRootIssuesCertificatesThatChainUpToTheRoot() throws Exception {
        try (AcmPcaClient client = client()) {
            String root = create(client, "ROOT", "Example Root CA");
            X509Certificate rootCertificate = activateRoot(client, root);
            String issuing = client.createCertificateAuthority(request -> request
                    .certificateAuthorityType("SUBORDINATE").usageMode("GENERAL_PURPOSE")
                    .keyStorageSecurityStandard("FIPS_140_2_LEVEL_3_OR_HIGHER")
                    .certificateAuthorityConfiguration(config -> config.keyAlgorithm("RSA_2048")
                            .signingAlgorithm("SHA256WITHRSA").subject(subject -> subject
                                    .country("US").organization("Example Org").commonName("Example Issuing CA")))
                    .tags(Tag.builder().key("purpose").value("issuing").build())).certificateAuthorityArn();
            CertificateAuthority pending = describe(client, issuing);
            assertEquals("SUBORDINATE", pending.typeAsString());
            assertEquals("PENDING_CERTIFICATE", pending.statusAsString());
            assertEquals("FIPS_140_2_LEVEL_3_OR_HIGHER", pending.keyStorageSecurityStandardAsString());
            assertEquals("ROOT", describe(client, root).typeAsString());

            String csr = client.getCertificateAuthorityCsr(request -> request.certificateAuthorityArn(issuing)).csr();
            GetCertificateResponse signed = issue(client, root, csr, "SubordinateCACertificate_PathLen0/V1", 180);
            X509Certificate subordinate = read(signed.certificate());
            assertEquals(0, subordinate.getBasicConstraints());
            assertTrue(subordinate.getCriticalExtensionOIDs().containsAll(Set.of("2.5.29.19", "2.5.29.15")));
            assertTrue(subordinate.getKeyUsage()[5] && subordinate.getKeyUsage()[6]);
            assertNull(subordinate.getExtendedKeyUsage());
            assertEquals(rootCertificate.getSubjectX500Principal(), subordinate.getIssuerX500Principal());
            subordinate.verify(rootCertificate.getPublicKey());
            assertEquals(List.of(rootCertificate), readAll(signed.certificateChain()));

            error("InvalidArgsException", () -> client.importCertificateAuthorityCertificate(request -> request
                    .certificateAuthorityArn(issuing).certificate(SdkBytes.fromUtf8String(signed.certificate()))));
            String unrelated = create(client, "ROOT", "Unrelated Root CA");
            String unrelatedPem = pem(activateRoot(client, unrelated));
            error("InvalidRequestException", () -> client.importCertificateAuthorityCertificate(request -> request
                    .certificateAuthorityArn(issuing).certificate(SdkBytes.fromUtf8String(signed.certificate()))
                    .certificateChain(SdkBytes.fromUtf8String(unrelatedPem))));
            assertEquals("PENDING_CERTIFICATE", describe(client, issuing).statusAsString());

            error("InvalidRequestException", () -> client.importCertificateAuthorityCertificate(request -> request
                    .certificateAuthorityArn(issuing).certificate(SdkBytes.fromUtf8String(signed.certificate()))
                    .certificateChain(SdkBytes.fromUtf8String(signed.certificate() + signed.certificateChain()))));
            assertEquals(pending, describe(client, issuing));

            client.importCertificateAuthorityCertificate(request -> request.certificateAuthorityArn(issuing)
                    .certificate(SdkBytes.fromUtf8String(signed.certificate()))
                    .certificateChain(SdkBytes.fromUtf8String(signed.certificateChain())));
            CertificateAuthority active = describe(client, issuing);
            assertEquals("ACTIVE", active.statusAsString());
            assertEquals(subordinate.getNotAfter().toInstant(), active.notAfter());
            GetCertificateAuthorityCertificateResponse installed = client.getCertificateAuthorityCertificate(
                    request -> request.certificateAuthorityArn(issuing));
            assertEquals(subordinate, read(installed.certificate()));
            assertEquals(List.of(rootCertificate), readAll(installed.certificateChain()));

            KeyPair key = keys();
            GetCertificateResponse leaf = issue(client, issuing, csr(key, "CN=service.example.com"), null, 30);
            X509Certificate leafCertificate = read(leaf.certificate());
            assertEquals(key.getPublic(), leafCertificate.getPublicKey());
            assertEquals(-1, leafCertificate.getBasicConstraints());
            List<X509Certificate> chain = readAll(leaf.certificateChain());
            assertEquals(List.of(subordinate, rootCertificate), chain);
            validate(leafCertificate, chain);
            String outlives = csr(keys(), "CN=outlives.example.com");
            error("InvalidArgsException", () -> issue(client, issuing, outlives, null, 365));
        }
    }

    @Test
    void aPathLengthOneSubordinateSignsAnotherTierAndItsChainHoldsEveryIssuer() throws Exception {
        try (AcmPcaClient client = client()) {
            String root = create(client, "ROOT", "Example Root CA");
            X509Certificate rootCertificate = activateRoot(client, root);
            String intermediate = create(client, "SUBORDINATE", "Example Intermediate CA");
            X509Certificate intermediateCertificate = activateSubordinate(client, root, intermediate,
                    "SubordinateCACertificate_PathLen1/V1", 180);
            assertEquals(1, intermediateCertificate.getBasicConstraints());
            String issuing = create(client, "SUBORDINATE", "Example Issuing CA");
            X509Certificate issuingCertificate = activateSubordinate(client, intermediate, issuing,
                    "SubordinateCACertificate_PathLen0/V1", 90);
            assertEquals(List.of(intermediateCertificate, rootCertificate), readAll(client
                    .getCertificateAuthorityCertificate(request -> request.certificateAuthorityArn(issuing))
                    .certificateChain()));

            GetCertificateResponse leaf = issue(client, issuing, csr(keys(), "CN=node.example.com"),
                    "EndEntityCertificate/V1", 10);
            List<X509Certificate> chain = readAll(leaf.certificateChain());
            assertEquals(List.of(issuingCertificate, intermediateCertificate, rootCertificate), chain);
            validate(read(leaf.certificate()), chain);
            String other = csr(keys(), "CN=other.example.com");
            error("InvalidArgsException", () -> issue(client, issuing, other, "SubordinateCACertificate_PathLen4/V1", 10));
            error("InvalidArgsException", () -> issue(client, issuing, other, "SubordinateCACertificate_PathLen0/V1", 10));
            error("InvalidArgsException", () -> issue(client, intermediate, other, "SubordinateCACertificate_PathLen1/V1", 10));
        }
    }

    @Test
    void tagsAreSetAtCreationAddedListedInPagesAndRemoved() {
        try (AcmPcaClient client = client()) {
            String ca = client.createCertificateAuthority(request -> request.certificateAuthorityType("ROOT")
                    .certificateAuthorityConfiguration(config -> config.keyAlgorithm("RSA_2048")
                            .signingAlgorithm("SHA256WITHRSA").subject(subject -> subject.commonName("Tagged CA")))
                    .tags(tag("env", "test"), tag("owner", "pki"))).certificateAuthorityArn();
            assertEquals(Map.of("env", "test", "owner", "pki"), tags(client, ca));

            client.tagCertificateAuthority(request -> request.certificateAuthorityArn(ca)
                    .tags(tag("owner", "platform"), tag("tier", "issuing")));
            assertEquals(Map.of("env", "test", "owner", "platform", "tier", "issuing"), tags(client, ca));

            ListTagsResponse first = client.listTags(request -> request.certificateAuthorityArn(ca).maxResults(2));
            assertEquals(List.of("env", "owner"), first.tags().stream().map(Tag::key).toList());
            assertNotNull(first.nextToken());
            ListTagsResponse second = client.listTags(request -> request.certificateAuthorityArn(ca)
                    .maxResults(2).nextToken(first.nextToken()));
            assertEquals(List.of("tier"), second.tags().stream().map(Tag::key).toList());
            assertNull(second.nextToken());

            client.untagCertificateAuthority(request -> request.certificateAuthorityArn(ca)
                    .tags(tag("env", "other"), Tag.builder().key("tier").build()));
            assertEquals(Map.of("env", "test", "owner", "platform"), tags(client, ca));

            error("InvalidTagException", () -> client.tagCertificateAuthority(request -> request
                    .certificateAuthorityArn(ca).tags(tag("aws:reserved", "x"))));
            Tag[] many = IntStream.range(0, 49).mapToObj(index -> tag("key-" + index, "v")).toArray(Tag[]::new);
            error("TooManyTagsException", () -> client.tagCertificateAuthority(request -> request
                    .certificateAuthorityArn(ca).tags(many)));
            assertEquals(Map.of("env", "test", "owner", "platform"), tags(client, ca));
        }
    }

    @Test
    void anUnknownKeyStorageStandardCreatesNothing() {
        try (AcmPcaClient client = client()) {
            error("InvalidArgsException", () -> client.createCertificateAuthority(request -> request
                    .certificateAuthorityType("SUBORDINATE").keyStorageSecurityStandard("FIPS_140_2_LEVEL_1")
                    .certificateAuthorityConfiguration(config -> config.keyAlgorithm("RSA_2048")
                            .signingAlgorithm("SHA256WITHRSA").subject(subject -> subject.commonName("Refused CA")))));
            assertTrue(client.listCertificateAuthorities().certificateAuthorities().isEmpty());
        }
    }

    private static AcmPcaClient client() {
        String account = Long.toString(820_000_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()));
        return AwsTestClients.create(AcmPcaClient.builder(), account, Region.US_EAST_1);
    }

    private static String create(AcmPcaClient client, String type, String commonName) {
        return client.createCertificateAuthority(request -> request.certificateAuthorityType(type)
                .certificateAuthorityConfiguration(config -> config.keyAlgorithm("RSA_2048")
                        .signingAlgorithm("SHA256WITHRSA").subject(subject -> subject.commonName(commonName))))
                .certificateAuthorityArn();
    }

    private static X509Certificate activateRoot(AcmPcaClient client, String ca) throws Exception {
        String csr = client.getCertificateAuthorityCsr(request -> request.certificateAuthorityArn(ca)).csr();
        String certificate = issue(client, ca, csr, "RootCACertificate/V1", 365).certificate();
        client.importCertificateAuthorityCertificate(request -> request.certificateAuthorityArn(ca)
                .certificate(SdkBytes.fromUtf8String(certificate)));
        return read(certificate);
    }

    private static X509Certificate activateSubordinate(AcmPcaClient client, String issuer, String ca, String template,
                                                       long days) throws Exception {
        String csr = client.getCertificateAuthorityCsr(request -> request.certificateAuthorityArn(ca)).csr();
        GetCertificateResponse signed = issue(client, issuer, csr, template, days);
        client.importCertificateAuthorityCertificate(request -> request.certificateAuthorityArn(ca)
                .certificate(SdkBytes.fromUtf8String(signed.certificate()))
                .certificateChain(SdkBytes.fromUtf8String(signed.certificateChain())));
        assertEquals("ACTIVE", describe(client, ca).statusAsString());
        return read(signed.certificate());
    }

    private static GetCertificateResponse issue(AcmPcaClient client, String ca, String csr, String template, long days) {
        String arn = client.issueCertificate(request -> request.certificateAuthorityArn(ca)
                .csr(SdkBytes.fromUtf8String(csr)).signingAlgorithm("SHA256WITHRSA")
                .templateArn(template == null ? null : TEMPLATES + template)
                .validity(validity -> validity.type("DAYS").value(days))).certificateArn();
        return client.getCertificate(request -> request.certificateAuthorityArn(ca).certificateArn(arn));
    }

    private static CertificateAuthority describe(AcmPcaClient client, String ca) {
        return client.describeCertificateAuthority(request -> request.certificateAuthorityArn(ca)).certificateAuthority();
    }

    private static Map<String, String> tags(AcmPcaClient client, String ca) {
        return client.listTags(request -> request.certificateAuthorityArn(ca)).tags().stream()
                .collect(Collectors.toMap(Tag::key, Tag::value));
    }

    private static Tag tag(String key, String value) {
        return Tag.builder().key(key).value(value).build();
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

    private static void error(String code, Runnable action) {
        AcmPcaException failure = assertThrows(AcmPcaException.class, action::run);
        assertEquals(400, failure.statusCode());
        assertEquals(code, failure.awsErrorDetails().errorCode());
    }

    private static KeyPair keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String csr(KeyPair keys, String subject) throws Exception {
        return pem(new JcaPKCS10CertificationRequestBuilder(new X500Name(subject), keys.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
    }

    private static X509Certificate read(String text) throws Exception {
        List<X509Certificate> certificates = readAll(text);
        assertEquals(1, certificates.size());
        return certificates.get(0);
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

    private static String pem(Object object) throws Exception {
        try (StringWriter text = new StringWriter(); JcaPEMWriter writer = new JcaPEMWriter(text)) {
            writer.writeObject(object);
            writer.flush();
            return text.toString();
        }
    }
}
