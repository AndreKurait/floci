package io.github.hectorvent.floci.services.acmpca;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acmpca.AcmPcaClient;
import software.amazon.awssdk.services.acmpca.model.AcmPcaException;
import software.amazon.awssdk.services.acmpca.model.CertificateAuthority;
import software.amazon.awssdk.services.acmpca.model.CreateCertificateAuthorityRequest;
import software.amazon.awssdk.services.acmpca.model.CreateCertificateAuthorityResponse;
import software.amazon.awssdk.services.acmpca.model.GetCertificateResponse;
import software.amazon.awssdk.services.acmpca.model.IssueCertificateRequest;
import software.amazon.awssdk.services.acmpca.model.ListCertificateAuthoritiesResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@Timeout(120)
class AcmPcaIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "cn-north-1", "us-gov-west-1"})
    void rootAndLeafCertificatesVerifyAcrossTheLifecycle(String region) throws Exception {
        try (Owned owned = new Owned("lifecycle-" + region, region)) {
            Instant started = Instant.now();
            String ca = owned.create(owned.client, UUID.randomUUID().toString());
            String partition = region.startsWith("cn-") ? "aws-cn"
                    : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
            assertTrue(ca.startsWith("arn:" + partition + ":acm-pca:" + region + ":" + owned.account + ":"));
            String csr = owned.client.getCertificateAuthorityCsr(request -> request.certificateAuthorityArn(ca)).csr();
            PKCS10CertificationRequest request = readCsr(csr);
            assertTrue(request.isSignatureValid(new JcaContentVerifierProviderBuilder()
                    .build(request.getSubjectPublicKeyInfo())));
            assertEquals("CN=" + owned.marker, request.getSubject().toString());
            X509Certificate root = owned.activate(ca);
            assertEquals(root.getPublicKey(), readCertificate(owned.client.getCertificateAuthorityCertificate(
                    input -> input.certificateAuthorityArn(ca)).certificate()).getPublicKey());
            assertNull(owned.client.getCertificateAuthorityCertificate(
                    input -> input.certificateAuthorityArn(ca)).certificateChain());
            root.verify(root.getPublicKey());
            root.checkValidity();
            assertTrue(Duration.between(started.minusSeconds(3600), root.getNotBefore().toInstant()).abs().toSeconds() < 120);
            assertTrue(Duration.between(started.plusSeconds(365L * 86400), root.getNotAfter().toInstant()).abs()
                    .toSeconds() < 120);
            assertTrue(root.getBasicConstraints() >= 0);
            assertTrue(root.getCriticalExtensionOIDs().containsAll(Set.of("2.5.29.19", "2.5.29.15")));
            assertTrue(root.getKeyUsage()[0] && root.getKeyUsage()[5] && root.getKeyUsage()[6]);
            assertNull(root.getExtendedKeyUsage());
            assertNotNull(root.getExtensionValue("2.5.29.14"));
            assertNull(root.getExtensionValue("2.5.29.35"));
            CertificateAuthority active = owned.describe(ca);
            assertEquals("ACTIVE", active.statusAsString());
            assertEquals(root.getNotBefore().toInstant(), active.notBefore());
            assertEquals(root.getNotAfter().toInstant(), active.notAfter());

            KeyPair leafKey = keys(2048);
            String leafCsr = csr(leafKey, "CN=worker.internal", true);
            String leafArn = owned.issue(owned.client, ca, leafCsr, null, UUID.randomUUID().toString(), 2);
            GetCertificateResponse issued = owned.client.getCertificate(input -> input
                    .certificateAuthorityArn(ca).certificateArn(leafArn));
            X509Certificate leaf = readCertificate(issued.certificate());
            assertEquals(owned.client.getCertificate(input -> input
                    .certificateAuthorityArn(ca).certificateArn(leafArn)).certificate(), issued.certificate());
            leaf.verify(root.getPublicKey());
            leaf.checkValidity();
            assertTrue(Duration.between(started.minusSeconds(3600), leaf.getNotBefore().toInstant()).abs().toSeconds() < 120);
            assertTrue(Duration.between(started.plusSeconds(2L * 86400), leaf.getNotAfter().toInstant()).abs()
                    .toSeconds() < 120);
            assertFalse(leaf.getNotAfter().after(root.getNotAfter()));
            assertEquals(leafKey.getPublic(), leaf.getPublicKey());
            assertEquals(root.getSubjectX500Principal(), leaf.getIssuerX500Principal());
            assertEquals(-1, leaf.getBasicConstraints());
            assertFalse(leaf.getCriticalExtensionOIDs().contains("2.5.29.19"));
            assertTrue(leaf.getCriticalExtensionOIDs().contains("2.5.29.15"));
            assertNotNull(leaf.getExtensionValue("2.5.29.14"));
            assertNotNull(leaf.getExtensionValue("2.5.29.35"));
            assertEquals(Set.of("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2"), Set.copyOf(leaf.getExtendedKeyUsage()));
            assertEquals(List.of(List.of(2, "worker.internal")), new ArrayList<>(leaf.getSubjectAlternativeNames()));
            assertTrue(leaf.getKeyUsage()[0] && leaf.getKeyUsage()[2]);
            assertEquals(root, readCertificate(issued.certificateChain()));
            PKIXParameters validation = new PKIXParameters(Set.of(new TrustAnchor(root, null)));
            validation.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(
                    CertificateFactory.getInstance("X.509").generateCertPath(List.of(leaf)), validation);
            Signature proof = Signature.getInstance("SHA256withRSA");
            proof.initSign(leafKey.getPrivate());
            proof.update(new byte[]{7, 3, 1});
            byte[] signature = proof.sign();
            proof.initVerify(leaf.getPublicKey());
            proof.update(new byte[]{7, 3, 1});
            assertTrue(proof.verify(signature));

            error("InvalidStateException", () -> owned.client.deleteCertificateAuthority(
                    input -> input.certificateAuthorityArn(ca)));
            assertEquals(active, owned.describe(ca));
            owned.client.updateCertificateAuthority(input -> input.certificateAuthorityArn(ca).status("DISABLED"));
            CertificateAuthority disabled = owned.describe(ca);
            error("InvalidStateException", () -> owned.issue(owned.client, ca, leafCsr, null, null, 1));
            assertEquals(disabled, owned.describe(ca));
            owned.client.updateCertificateAuthority(input -> input.certificateAuthorityArn(ca).status("ACTIVE"));
            assertEquals(issued.certificate(), owned.client.getCertificate(input -> input
                    .certificateAuthorityArn(ca).certificateArn(leafArn)).certificate());
            owned.client.updateCertificateAuthority(input -> input.certificateAuthorityArn(ca).status("DISABLED"));
            owned.client.deleteCertificateAuthority(input -> input.certificateAuthorityArn(ca).permanentDeletionTimeInDays(7));
            CertificateAuthority deleted = owned.describe(ca);
            assertEquals("DELETED", deleted.statusAsString());
            assertEquals(Duration.ofDays(7), Duration.between(deleted.lastStateChangeAt(), deleted.restorableUntil()));
            error("InvalidStateException", () -> owned.client.getCertificate(
                    input -> input.certificateAuthorityArn(ca).certificateArn(leafArn)));
            owned.client.restoreCertificateAuthority(input -> input.certificateAuthorityArn(ca));
            assertEquals("DISABLED", owned.describe(ca).statusAsString());
            assertNull(owned.describe(ca).restorableUntil());
            assertEquals(issued.certificate(), owned.client.getCertificate(input -> input
                    .certificateAuthorityArn(ca).certificateArn(leafArn)).certificate());
        }
    }

    @Test
    void baseTemplatesIgnoreApiCertificateOverrides() throws Exception {
        try (Owned owned = new Owned("ignored-passthrough", "us-east-1")) {
            String ca = owned.create(owned.client, null);
            String rootCsr = owned.client.getCertificateAuthorityCsr(input -> input.certificateAuthorityArn(ca)).csr();
            String rootPem = issueIgnoringApiPassthrough(owned, ca, rootCsr, owned.rootTemplate(), 100);
            X509Certificate root = readCertificate(rootPem);
            assertNull(root.getSubjectAlternativeNames());
            assertNull(root.getExtendedKeyUsage());
            assertTrue(root.getBasicConstraints() >= 0);
            assertTrue(root.getKeyUsage()[0] && root.getKeyUsage()[5] && root.getKeyUsage()[6]);
            owned.client.importCertificateAuthorityCertificate(input -> input
                    .certificateAuthorityArn(ca).certificate(SdkBytes.fromUtf8String(rootPem)));
            String leafCsr = csr(keys(2048), "CN=worker.internal", true);
            for (boolean explicit : new boolean[]{false, true}) {
                String template = explicit ? owned.rootTemplate().replace("RootCACertificate", "EndEntityCertificate") : null;
                X509Certificate leaf = readCertificate(issueIgnoringApiPassthrough(owned, ca, leafCsr, template, 2));
                assertEquals(List.of(List.of(2, "worker.internal")), new ArrayList<>(leaf.getSubjectAlternativeNames()));
                assertEquals(Set.of("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2"), Set.copyOf(leaf.getExtendedKeyUsage()));
                assertEquals(-1, leaf.getBasicConstraints());
                assertTrue(leaf.getKeyUsage()[0] && leaf.getKeyUsage()[2]);
                PKIXParameters validation = new PKIXParameters(Set.of(new TrustAnchor(root, null)));
                validation.setRevocationEnabled(false);
                CertPathValidator.getInstance("PKIX").validate(
                        CertificateFactory.getInstance("X.509").generateCertPath(List.of(leaf)), validation);
            }
            CertificateAuthority before = owned.describe(ca);
            for (String variant : List.of("APIPassthrough", "APICSRPassthrough")) {
                String unsupported = owned.rootTemplate().replace("RootCACertificate", "EndEntityCertificate_" + variant);
                error("InvalidArgsException", () -> owned.issue(owned.client, ca, leafCsr, unsupported, null, 1));
                assertEquals(before, owned.describe(ca));
            }
        }
    }

    private static String issueIgnoringApiPassthrough(Owned owned, String ca, String csr, String template, long days)
            throws Exception {
        CertificateAuthority before = owned.describe(ca);
        long start = Instant.now().minusSeconds(3600).getEpochSecond();
        long end = Instant.now().plusSeconds(days * 86400).getEpochSecond();
        IssueCertificateRequest request = IssueCertificateRequest.builder().certificateAuthorityArn(ca)
                .csr(SdkBytes.fromUtf8String(csr)).signingAlgorithm("SHA256WITHRSA").templateArn(template)
                .validityNotBefore(validity -> validity.type("ABSOLUTE").value(start))
                .validity(validity -> validity.type("ABSOLUTE").value(end)).build();
        String originalArn = owned.client.issueCertificate(request).certificateArn();
        String overriddenArn = owned.client.issueCertificate(request.toBuilder()
                .apiPassthrough(api -> api.subject(subject -> subject.commonName("ignored.invalid")
                                .organization("ignored organization"))
                        .extensions(extensions -> extensions.subjectAlternativeNames(name -> name.dnsName("ignored.invalid"))
                                .keyUsage(usage -> usage.digitalSignature(false).keyEncipherment(false)
                                        .keyCertSign(false).crlSign(false).dataEncipherment(true))
                                .extendedKeyUsage(usage -> usage.extendedKeyUsageType("CODE_SIGNING")))).build())
                .certificateArn();
        GetCertificateResponse original = owned.client.getCertificate(input -> input
                .certificateAuthorityArn(ca).certificateArn(originalArn));
        GetCertificateResponse overridden = owned.client.getCertificate(input -> input
                .certificateAuthorityArn(ca).certificateArn(overriddenArn));
        X509Certificate expected = readCertificate(original.certificate());
        X509Certificate actual = readCertificate(overridden.certificate());
        assertEquals(readCsr(csr).getSubject(), new X509CertificateHolder(actual.getEncoded()).getSubject());
        assertEquals(expected.getSubjectX500Principal(), actual.getSubjectX500Principal());
        assertEquals(expected.getIssuerX500Principal(), actual.getIssuerX500Principal());
        assertEquals(expected.getPublicKey(), actual.getPublicKey());
        assertEquals(expected.getNotBefore(), actual.getNotBefore());
        assertEquals(expected.getNotAfter(), actual.getNotAfter());
        assertEquals(expected.getCriticalExtensionOIDs(), actual.getCriticalExtensionOIDs());
        assertEquals(expected.getNonCriticalExtensionOIDs(), actual.getNonCriticalExtensionOIDs());
        Set<String> extensions = new HashSet<>(expected.getCriticalExtensionOIDs());
        extensions.addAll(expected.getNonCriticalExtensionOIDs());
        for (String oid : extensions) {
            assertArrayEquals(expected.getExtensionValue(oid), actual.getExtensionValue(oid), oid);
        }
        assertEquals(original.certificateChain(), overridden.certificateChain());
        actual.verify(original.certificateChain() == null
                ? actual.getPublicKey() : readCertificate(original.certificateChain()).getPublicKey());
        assertEquals(before, owned.describe(ca));
        return overridden.certificate();
    }

    @Test
    void lostCreateResponseStillRecoversAndDeletesTheOwnedAuthority() {
        try (Owned owned = new Owned("lost-create-response", "us-east-1")) {
            AtomicReference<String> created = new AtomicReference<>();
            AcmPcaClient lossy = owned.client(owned.account, owned.region, new ExecutionInterceptor() {
                @Override
                public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
                    if (context.response() instanceof CreateCertificateAuthorityResponse response
                            && created.compareAndSet(null, response.certificateAuthorityArn())) {
                        throw new IllegalStateException("deliberate loss after the CA was created");
                    }
                }
            });
            assertThrows(RuntimeException.class, () -> owned.create(lossy, UUID.randomUUID().toString()));
            String ca = created.get();
            assertNotNull(ca);
            assertEquals("PENDING_CERTIFICATE", owned.describe(ca).statusAsString());
            owned.cleanupAssertions.add(() -> {
                assertEquals("DELETED", owned.describe(ca).statusAsString());
                error("InvalidStateException", () -> owned.client.getCertificateAuthorityCsr(
                        input -> input.certificateAuthorityArn(ca)));
            });
        }
    }

    @Test
    void unexpectedNegativeCreateSuccessStillGetsOwnedCleanup() {
        try (Owned owned = new Owned("unexpected-create-success", "us-east-1")) {
            AtomicReference<String> created = new AtomicReference<>();
            assertThrows(AssertionError.class, () -> error("InvalidArgsException", () -> created.set(
                    owned.createAttempt(owned.client, owned.createRequest(UUID.randomUUID().toString()).build()))));
            String ca = created.get();
            assertNotNull(ca);
            assertEquals("PENDING_CERTIFICATE", owned.describe(ca).statusAsString());
            owned.cleanupAssertions.add(() -> {
                assertEquals("DELETED", owned.describe(ca).statusAsString());
                error("InvalidStateException", () -> owned.client.getCertificateAuthorityCsr(
                        input -> input.certificateAuthorityArn(ca)));
            });
        }
    }

    @Test
    void failedImportsAndWrongRootCsrsPreserveBothAuthorities() throws Exception {
        try (Owned owned = new Owned("failed-import", "us-east-1")) {
            String first = owned.create(owned.client, null);
            String second = owned.create(owned.client, null);
            CertificateAuthority firstBefore = owned.describe(first);
            CertificateAuthority secondBefore = owned.describe(second);
            String secondCsr = owned.client.getCertificateAuthorityCsr(input -> input.certificateAuthorityArn(second)).csr();
            String certificateArn = owned.issue(owned.client, second, secondCsr, owned.rootTemplate(), null, 100);
            String certificate = owned.client.getCertificate(input -> input
                    .certificateAuthorityArn(second).certificateArn(certificateArn)).certificate();
            error("CertificateMismatchException", () -> owned.client.importCertificateAuthorityCertificate(input -> input
                    .certificateAuthorityArn(first).certificate(SdkBytes.fromUtf8String(certificate))));
            error("InvalidArgsException", () -> owned.issue(owned.client, first, secondCsr, owned.rootTemplate(), null, 100));
            error("MalformedCertificateException", () -> owned.client.importCertificateAuthorityCertificate(input -> input
                    .certificateAuthorityArn(first).certificate(SdkBytes.fromUtf8String("not a certificate"))));
            byte[] damaged = readCertificate(certificate).getEncoded();
            damaged[damaged.length - 1] ^= 1;
            String damagedPem = pem(new X509CertificateHolder(damaged));
            error("MalformedCertificateException", () -> owned.client.importCertificateAuthorityCertificate(input -> input
                    .certificateAuthorityArn(second).certificate(SdkBytes.fromUtf8String(damagedPem))));
            assertEquals(firstBefore, owned.describe(first));
            assertEquals(secondBefore, owned.describe(second));
            assertEquals(certificate, owned.client.getCertificate(input -> input
                    .certificateAuthorityArn(second).certificateArn(certificateArn)).certificate());
            owned.client.importCertificateAuthorityCertificate(input -> input
                    .certificateAuthorityArn(second).certificate(SdkBytes.fromUtf8String(certificate)));
            assertEquals("ACTIVE", owned.describe(second).statusAsString());
            assertEquals(firstBefore, owned.describe(first));
        }
    }

    @Test
    void invalidCsrsAndOverlongValidityPreserveTheActiveIssuer() throws Exception {
        try (Owned owned = new Owned("failed-issue", "us-east-1")) {
            String ca = owned.create(owned.client, null);
            owned.activate(ca);
            CertificateAuthority before = owned.describe(ca);
            String csr = csr(keys(2048), "CN=valid.internal", false);
            String existing = owned.issue(owned.client, ca, csr, null, null, 1);
            String certificate = owned.client.getCertificate(input -> input
                    .certificateAuthorityArn(ca).certificateArn(existing)).certificate();
            error("MalformedCSRException", () -> owned.issue(owned.client, ca, "not a csr", null, null, 1));
            byte[] damaged = readCsr(csr).getEncoded();
            damaged[damaged.length - 1] ^= 1;
            String badSignature = pem(new PKCS10CertificationRequest(damaged));
            error("MalformedCSRException", () -> owned.issue(owned.client, ca, badSignature, null, null, 1));
            String weak = csr(keys(1024), "CN=weak.internal", false);
            error("InvalidArgsException", () -> owned.issue(owned.client, ca, weak, null, null, 1));
            String unnamed = csr(keys(2048), "", false);
            error("MalformedCSRException", () -> owned.issue(owned.client, ca, unnamed, null, null, 1));
            error("InvalidArgsException", () -> owned.issue(owned.client, ca, csr, null, null, 500));
            error("InvalidArgsException", () -> owned.issue(owned.client, ca, csr, null, null, -1));
            error("InvalidArgsException", () -> owned.issue(owned.client, ca, csr,
                    owned.rootTemplate().replace("RootCACertificate", "SubordinateCACertificate_PathLen0"), null, 1));
            assertEquals(before, owned.describe(ca));
            assertEquals(certificate, owned.client.getCertificate(input -> input
                    .certificateAuthorityArn(ca).certificateArn(existing)).certificate());
        }
    }

    @Test
    void idempotencyPaginationAndForeignLookupsStayWithinTheirOwner() throws Exception {
        try (Owned owned = new Owned("ownership", "us-east-1")) {
            String token = UUID.randomUUID().toString();
            String ca = owned.create(owned.client, token);
            assertEquals(ca, owned.create(owned.client, token));
            owned.activate(ca);
            String csr = csr(keys(2048), "CN=owned.internal", false);
            String leaf = owned.issue(owned.client, ca, csr, null, token, 1);
            assertEquals(leaf, owned.issue(owned.client, ca, csr, null, token, 1));
            String second = owned.create(owned.client, null);
            AcmPcaClient foreign = owned.client(Long.toString(Long.parseLong(owned.account) + 1), "us-east-1");
            String foreignCa = owned.create(foreign, token);
            assertNotEquals(ca, foreignCa);
            CertificateAuthority before = owned.describe(ca);
            AcmPcaClient wrongRegion = owned.client(owned.account, "us-west-2");
            AcmPcaClient wrongPartition = owned.client(owned.account, "cn-north-1");
            for (AcmPcaClient other : List.of(foreign, wrongRegion, wrongPartition)) {
                error("ResourceNotFoundException", () -> other.describeCertificateAuthority(
                        input -> input.certificateAuthorityArn(ca)));
                error("ResourceNotFoundException", () -> other.getCertificateAuthorityCsr(
                        input -> input.certificateAuthorityArn(ca)));
                error("ResourceNotFoundException", () -> other.deleteCertificateAuthority(
                        input -> input.certificateAuthorityArn(ca)));
                error("ResourceNotFoundException", () -> other.updateCertificateAuthority(
                        input -> input.certificateAuthorityArn(ca).status("DISABLED")));
                error("ResourceNotFoundException", () -> other.getCertificate(
                        input -> input.certificateAuthorityArn(ca).certificateArn(leaf)));
            }
            error("ResourceNotFoundException", () -> owned.client.getCertificate(
                    input -> input.certificateAuthorityArn(second).certificateArn(leaf)));
            assertEquals(before, owned.describe(ca));
            List<CertificateAuthority> firstPage = owned.client.listCertificateAuthorities(
                    input -> input.maxResults(1)).certificateAuthorities();
            String next = owned.client.listCertificateAuthorities(input -> input.maxResults(1)).nextToken();
            assertEquals(1, firstPage.size());
            assertNotNull(next);
            List<CertificateAuthority> secondPage = owned.client.listCertificateAuthorities(
                    input -> input.maxResults(1).nextToken(next)).certificateAuthorities();
            assertEquals(Set.of(ca, second), Set.of(firstPage.getFirst().arn(), secondPage.getFirst().arn()));
            assertNull(owned.client.listCertificateAuthorities(input -> input.maxResults(1).nextToken(next)).nextToken());
            error("InvalidNextTokenException", () -> foreign.listCertificateAuthorities(input -> input.nextToken(next)));
            error("InvalidNextTokenException", () -> wrongRegion.listCertificateAuthorities(input -> input.nextToken(next)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"subordinate", "elliptic", "short-lived", "crl", "ocsp", "tags", "key-storage", "csr-extensions"})
    void unsupportedCreateFeaturesDoNotCreateAnAuthority(String feature) {
        try (Owned owned = new Owned("unsupported-" + feature, "us-east-1")) {
            CreateCertificateAuthorityRequest.Builder request = owned.createRequest(null);
            switch (feature) {
                case "subordinate" -> request.certificateAuthorityType("SUBORDINATE");
                case "elliptic" -> request.certificateAuthorityConfiguration(config -> config.keyAlgorithm("EC_prime256v1")
                        .signingAlgorithm("SHA256WITHECDSA").subject(subject -> subject.commonName(owned.marker)));
                case "short-lived" -> request.usageMode("SHORT_LIVED_CERTIFICATE");
                case "crl" -> request.revocationConfiguration(config -> config.crlConfiguration(crl -> crl.enabled(true)));
                case "ocsp" -> request.revocationConfiguration(config -> config.ocspConfiguration(ocsp -> ocsp.enabled(true)));
                case "tags" -> request.tags(tag -> tag.key("scope").value("test"));
                case "key-storage" -> request.keyStorageSecurityStandard("FIPS_140_2_LEVEL_3_OR_HIGHER");
                case "csr-extensions" -> request.certificateAuthorityConfiguration(config -> config.keyAlgorithm("RSA_2048")
                        .signingAlgorithm("SHA256WITHRSA").subject(subject -> subject.commonName(owned.marker))
                        .csrExtensions(extensions -> extensions.keyUsage(usage -> usage.keyCertSign(true))));
                default -> fail(feature);
            }
            error("InvalidArgsException", () -> owned.createAttempt(owned.client, request.build()));
            assertTrue(owned.client.listCertificateAuthorities().certificateAuthorities().isEmpty());
        }
    }

    private static void error(String code, Runnable action) {
        AcmPcaException failure = assertThrows(AcmPcaException.class, action::run);
        assertEquals(400, failure.statusCode());
        assertEquals(code, failure.awsErrorDetails().errorCode());
    }

    private static KeyPair keys(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String csr(KeyPair keys, String subject, boolean san) throws Exception {
        JcaPKCS10CertificationRequestBuilder builder = new JcaPKCS10CertificationRequestBuilder(
                new X500Name(subject), keys.getPublic());
        if (san) {
            ExtensionsGenerator extensions = new ExtensionsGenerator();
            extensions.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.dNSName, "worker.internal")));
            builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate());
        }
        return pem(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
    }

    private static PKCS10CertificationRequest readCsr(String text) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(text))) {
            return assertInstanceOf(PKCS10CertificationRequest.class, parser.readObject());
        }
    }

    private static X509Certificate readCertificate(String text) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(text))) {
            return new JcaX509CertificateConverter().getCertificate(
                    assertInstanceOf(X509CertificateHolder.class, parser.readObject()));
        }
    }

    private static String pem(Object object) throws Exception {
        try (StringWriter text = new StringWriter(); JcaPEMWriter writer = new JcaPEMWriter(text)) {
            writer.writeObject(object);
            writer.flush();
            return text.toString();
        }
    }

    private static final class Owned implements AutoCloseable {
        private final String marker = "pca-" + UUID.randomUUID();
        private final String account = Long.toString(810_000_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()));
        private final String caseName;
        private final String region;
        private final WireReceipt wire = new WireReceipt();
        private final List<AcmPcaClient> clients = new ArrayList<>();
        private final Map<String, AcmPcaClient> authorities = new LinkedHashMap<>();
        private final Map<AcmPcaClient, String> ownerPrefixes = new LinkedHashMap<>();
        private final Map<AcmPcaClient, List<String>> attemptedCreateTokens = new LinkedHashMap<>();
        private final List<Runnable> cleanupAssertions = new ArrayList<>();
        private final AcmPcaClient client;

        private Owned(String caseName, String region) {
            this.caseName = caseName;
            this.region = region;
            client = client(account, region);
        }

        private AcmPcaClient client(String account, String region) {
            return client(account, region, new ExecutionInterceptor() {});
        }

        private AcmPcaClient client(String account, String region, ExecutionInterceptor interceptor) {
            AcmPcaClient client = AwsTestClients.create(AcmPcaClient.builder().overrideConfiguration(config ->
                    config.apiCallTimeout(Duration.ofSeconds(20)).apiCallAttemptTimeout(Duration.ofSeconds(15))
                            .retryPolicy(RetryPolicy.none()).addExecutionInterceptor(wire)
                            .addExecutionInterceptor(interceptor)), account, Region.of(region));
            clients.add(client);
            String partition = region.startsWith("cn-") ? "aws-cn"
                    : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
            ownerPrefixes.put(client, "arn:" + partition + ":acm-pca:" + region + ":" + account
                    + ":certificate-authority/");
            return client;
        }

        private CreateCertificateAuthorityRequest.Builder createRequest(String token) {
            return CreateCertificateAuthorityRequest.builder().certificateAuthorityType("ROOT").idempotencyToken(token)
                    .certificateAuthorityConfiguration(config -> config.keyAlgorithm("RSA_2048")
                            .signingAlgorithm("SHA256WITHRSA").subject(subject -> subject.commonName(marker)));
        }

        private String create(AcmPcaClient client, String token) {
            return createAttempt(client, createRequest(token).build());
        }

        private String createAttempt(AcmPcaClient client, CreateCertificateAuthorityRequest request) {
            attemptedCreateTokens.computeIfAbsent(client, ignored -> new ArrayList<>()).add(request.idempotencyToken());
            String arn = client.createCertificateAuthority(request).certificateAuthorityArn();
            authorities.putIfAbsent(arn, client);
            return arn;
        }

        private void verifyOwner(AcmPcaClient owner, CertificateAuthority authority) {
            assertTrue(authority.arn().startsWith(ownerPrefixes.get(owner)));
            assertEquals(marker, authority.certificateAuthorityConfiguration().subject().commonName());
        }

        private void recoverCreatedAuthorities(AcmPcaClient owner) {
            String token = null;
            Set<String> tokens = new HashSet<>();
            for (int page = 0; page < 10; page++) {
                String next = token;
                ListCertificateAuthoritiesResponse response = owner.listCertificateAuthorities(input -> input.maxResults(100).nextToken(next));
                for (CertificateAuthority candidate : response.certificateAuthorities()) {
                    if (candidate.certificateAuthorityConfiguration() != null
                            && candidate.certificateAuthorityConfiguration().subject() != null
                            && marker.equals(candidate.certificateAuthorityConfiguration().subject().commonName())) {
                        verifyOwner(owner, candidate);
                        CertificateAuthority current = owner.describeCertificateAuthority(
                                input -> input.certificateAuthorityArn(candidate.arn())).certificateAuthority();
                        assertEquals(candidate.arn(), current.arn());
                        verifyOwner(owner, current);
                        authorities.putIfAbsent(current.arn(), owner);
                    }
                }
                token = response.nextToken();
                if (token == null) {
                    return;
                }
                assertTrue(tokens.add(token), "CA cleanup pagination token repeated");
            }
            fail("CA cleanup discovery exceeded ten pages");
        }

        private String rootTemplate() {
            String partition = region.startsWith("cn-") ? "aws-cn"
                    : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
            return "arn:" + partition + ":acm-pca:::template/RootCACertificate/V1";
        }

        private String issue(AcmPcaClient client, String ca, String csr, String template, String token, long days) {
            return client.issueCertificate(input -> input.certificateAuthorityArn(ca).csr(SdkBytes.fromUtf8String(csr))
                    .signingAlgorithm("SHA256WITHRSA").templateArn(template).idempotencyToken(token)
                    .validity(validity -> validity.type("DAYS").value(days))).certificateArn();
        }

        private X509Certificate activate(String ca) throws Exception {
            String csr = client.getCertificateAuthorityCsr(input -> input.certificateAuthorityArn(ca)).csr();
            String arn = issue(client, ca, csr, rootTemplate(), null, 365);
            String root = client.getCertificate(input -> input.certificateAuthorityArn(ca).certificateArn(arn)).certificate();
            client.importCertificateAuthorityCertificate(input -> input
                    .certificateAuthorityArn(ca).certificate(SdkBytes.fromUtf8String(root)));
            return readCertificate(root);
        }

        private CertificateAuthority describe(String ca) {
            return client.describeCertificateAuthority(input -> input.certificateAuthorityArn(ca)).certificateAuthority();
        }

        @Override
        public void close() {
            Throwable failure = null;
            for (AcmPcaClient owner : attemptedCreateTokens.keySet()) {
                try {
                    recoverCreatedAuthorities(owner);
                } catch (Throwable discoveryFailure) {
                    if (failure == null) {
                        failure = discoveryFailure;
                    } else {
                        failure.addSuppressed(discoveryFailure);
                    }
                }
            }
            for (Map.Entry<String, AcmPcaClient> entry : authorities.entrySet()) {
                try {
                    AcmPcaClient owner = entry.getValue();
                    String arn = entry.getKey();
                    CertificateAuthority current = owner.describeCertificateAuthority(
                            input -> input.certificateAuthorityArn(arn)).certificateAuthority();
                    assertEquals(arn, current.arn());
                    verifyOwner(owner, current);
                    if ("ACTIVE".equals(current.statusAsString())) {
                        owner.updateCertificateAuthority(input -> input.certificateAuthorityArn(arn).status("DISABLED"));
                    }
                    if (!"DELETED".equals(current.statusAsString())) {
                        owner.deleteCertificateAuthority(input -> input.certificateAuthorityArn(arn)
                                .permanentDeletionTimeInDays(7));
                    }
                    CertificateAuthority deleted = owner.describeCertificateAuthority(
                            input -> input.certificateAuthorityArn(arn)).certificateAuthority();
                    assertEquals(arn, deleted.arn());
                    verifyOwner(owner, deleted);
                    assertEquals("DELETED", deleted.statusAsString());
                    assertNotNull(deleted.restorableUntil());
                    error("InvalidStateException", () -> owner.getCertificateAuthorityCsr(
                            input -> input.certificateAuthorityArn(arn)));
                } catch (Throwable cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            for (Runnable assertion : cleanupAssertions) {
                try {
                    assertion.run();
                } catch (Throwable cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            for (AcmPcaClient ownedClient : clients) {
                try {
                    ownedClient.close();
                } catch (Throwable closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            wire.save(caseName, marker, failure == null);
            if (failure != null) {
                throw new AssertionError("Owned CA disable/delete verification failed", failure);
            }
        }
    }

    private static final class WireReceipt implements ExecutionInterceptor {
        private static final ExecutionAttribute<Integer> ID = new ExecutionAttribute<>("acm-pca-wire-id");
        private final List<Map<String, Object>> calls = new ArrayList<>();

        @Override
        public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
            assertEquals("localhost", context.httpRequest().getUri().getHost());
            assertEquals("http", context.httpRequest().getUri().getScheme());
            assertTrue(calls.size() < 10000);
            attributes.putAttribute(ID, calls.size());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", calls.size());
            row.put("operation", context.request().getClass().getSimpleName().replaceFirst("Request$", ""));
            row.put("method", context.httpRequest().method().name());
            row.put("uri", context.httpRequest().getUri().toString());
            row.put("requestModel", model(context.request()));
            Map<String, List<String>> headers = new LinkedHashMap<>(context.httpRequest().headers());
            headers.keySet().removeIf(name -> name.equalsIgnoreCase("authorization")
                    || name.equalsIgnoreCase("x-amz-security-token"));
            row.put("requestHeaders", headers);
            row.put("requestBodyAvailable", context.requestBody().isPresent());
            try (InputStream stream = context.requestBody().isPresent()
                    ? context.requestBody().orElseThrow().contentStreamProvider().newStream()
                    : InputStream.nullInputStream()) {
                row.put("requestBase64", encode(stream));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            calls.add(row);
        }

        @Override
        public Optional<InputStream> modifyHttpResponseContent(Context.ModifyHttpResponse context,
                                                               ExecutionAttributes attributes) {
            Map<String, Object> row = calls.get(attributes.getAttribute(ID));
            row.put("status", context.httpResponse().statusCode());
            row.put("responseHeaders", context.httpResponse().headers());
            try (InputStream stream = context.responseBody().orElseGet(InputStream::nullInputStream)) {
                String encoded = encode(stream);
                row.put("responseBase64", encoded);
                return Optional.of(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        @Override
        public void afterExecution(Context.AfterExecution context, ExecutionAttributes attributes) {
            calls.get(attributes.getAttribute(ID)).put("responseModel", model(context.response()));
        }

        @Override
        public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attributes) {
            Integer id = attributes.getAttribute(ID);
            if (id == null) {
                return;
            }
            Map<String, Object> row = calls.get(id);
            row.put("exceptionType", context.exception().getClass().getName());
            if (context.exception() instanceof AwsServiceException failure) {
                row.put("errorCode", failure.awsErrorDetails().errorCode());
                row.put("errorMessage", failure.awsErrorDetails().errorMessage());
                row.put("errorStatus", failure.statusCode());
            }
        }

        private static Object model(Object value) {
            if (value instanceof SdkPojo pojo) {
                Map<String, Object> fields = new LinkedHashMap<>();
                pojo.sdkFields().forEach(field -> {
                    Object member = field.getValueOrDefault(pojo);
                    if (member != null) {
                        fields.put(field.memberName(), model(member));
                    }
                });
                return fields;
            }
            if (value instanceof List<?> list) {
                return list.stream().map(WireReceipt::model).toList();
            }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> fields = new LinkedHashMap<>();
                map.forEach((key, member) -> fields.put(key.toString(), model(member)));
                return fields;
            }
            if (value instanceof SdkBytes bytes) {
                return Base64.getEncoder().encodeToString(bytes.asByteArray());
            }
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                return value;
            }
            return value.toString();
        }

        private static String encode(InputStream stream) throws IOException {
            byte[] bytes = stream.readNBytes(1_048_577);
            assertTrue(bytes.length <= 1_048_576);
            return Base64.getEncoder().encodeToString(bytes);
        }

        private void save(String caseName, String marker, boolean cleanupComplete) {
            try {
                Path directory = Path.of(System.getProperty("audit.acm.pca.directory",
                        "target/acm-pca-wire"));
                Files.createDirectories(directory);
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(marker + ".json").toFile(),
                        Map.of("case", caseName, "marker", marker, "cleanupComplete", cleanupComplete, "calls", calls));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
            assertFalse(calls.isEmpty());
            assertTrue(calls.stream().allMatch(row -> row.containsKey("responseBase64")),
                    "every attempted transmission has a complete captured response");
        }
    }
}
