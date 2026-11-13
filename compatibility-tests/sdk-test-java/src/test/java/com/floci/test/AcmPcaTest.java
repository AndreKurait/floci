package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.acmpca.AcmPcaClient;
import software.amazon.awssdk.services.acmpca.model.AcmPcaException;
import software.amazon.awssdk.services.acmpca.model.CertificateAuthority;
import software.amazon.awssdk.services.acmpca.model.GetCertificateResponse;
import software.amazon.awssdk.services.acmpca.model.ListCertificateAuthoritiesResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class AcmPcaTest {

    @Test
    void rootBootstrapSignsImportsAndRetainsDeletionState() throws Exception {
        assumeFalse(TestFixtures.isRealAws(), "Private CA provisioning is a billed operation; this fixture is local only");
        String marker = TestFixtures.uniqueName("pca-root");
        String account = Long.toString(810_000_000_000L + Integer.toUnsignedLong(UUID.randomUUID().hashCode()));
        Set<String> authorities = new LinkedHashSet<>();
        String prefix = TestFixtures.arn("acm-pca", account, "certificate-authority/");
        try (AcmPcaClient client = TestFixtures.acmPcaClient(account)) {
            Throwable primaryFailure = null;
            try {
                String ca = client.createCertificateAuthority(request -> request
                        .certificateAuthorityType("ROOT").idempotencyToken(UUID.randomUUID().toString())
                        .certificateAuthorityConfiguration(configuration -> configuration
                                .keyAlgorithm("RSA_2048").signingAlgorithm("SHA256WITHRSA")
                                .subject(subject -> subject.commonName(marker)))).certificateAuthorityArn();
                authorities.add(ca);
                assertThat(ca).startsWith("arn:" + TestFixtures.partition() + ":acm-pca:");
                assertThat(client.describeCertificateAuthority(request -> request.certificateAuthorityArn(ca))
                        .certificateAuthority().statusAsString()).isEqualTo("PENDING_CERTIFICATE");
                String csr = client.getCertificateAuthorityCsr(request -> request.certificateAuthorityArn(ca)).csr();
                String certificateArn = client.issueCertificate(request -> request.certificateAuthorityArn(ca)
                        .csr(SdkBytes.fromUtf8String(csr)).signingAlgorithm("SHA256WITHRSA")
                        .templateArn("arn:" + TestFixtures.partition() + ":acm-pca:::template/RootCACertificate/V1")
                        .validity(validity -> validity.type("DAYS").value(365L))).certificateArn();
                GetCertificateResponse issued = client.getCertificate(request -> request
                        .certificateAuthorityArn(ca).certificateArn(certificateArn));
                X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(
                                issued.certificate().getBytes(StandardCharsets.US_ASCII)));
                certificate.verify(certificate.getPublicKey());
                certificate.checkValidity();
                assertThat(certificate.getBasicConstraints()).isGreaterThanOrEqualTo(0);
                assertThat(certificate.getKeyUsage()[5]).isTrue();
                assertThat(certificate.getSubjectX500Principal()).isEqualTo(certificate.getIssuerX500Principal());
                client.importCertificateAuthorityCertificate(request -> request.certificateAuthorityArn(ca)
                        .certificate(SdkBytes.fromUtf8String(issued.certificate())));
                assertThat(client.getCertificateAuthorityCertificate(request -> request.certificateAuthorityArn(ca))
                        .certificate()).isEqualTo(issued.certificate());
                assertThat(client.describeCertificateAuthority(request -> request.certificateAuthorityArn(ca))
                        .certificateAuthority().statusAsString()).isEqualTo("ACTIVE");
            } catch (Throwable problem) {
                primaryFailure = problem;
                throw problem;
            } finally {
                Throwable failure = null;
                try {
                    recoverCreatedAuthorities(client, marker, prefix, authorities);
                } catch (Throwable problem) {
                    failure = problem;
                }
                for (String ca : authorities) {
                    try {
                        CertificateAuthority current = client.describeCertificateAuthority(request -> request
                                .certificateAuthorityArn(ca)).certificateAuthority();
                        assertThat(current.arn()).startsWith(prefix);
                        assertThat(current.certificateAuthorityConfiguration().subject().commonName()).isEqualTo(marker);
                        if ("ACTIVE".equals(current.statusAsString())) {
                            client.updateCertificateAuthority(request -> request.certificateAuthorityArn(ca).status("DISABLED"));
                        }
                        if (!"DELETED".equals(current.statusAsString())) {
                            client.deleteCertificateAuthority(request -> request.certificateAuthorityArn(ca)
                                    .permanentDeletionTimeInDays(7));
                        }
                        CertificateAuthority deleted = client.describeCertificateAuthority(request -> request
                                .certificateAuthorityArn(ca)).certificateAuthority();
                        assertThat(deleted.statusAsString()).isEqualTo("DELETED");
                        assertThat(deleted.restorableUntil()).isNotNull();
                        assertThat(assertThrows(AcmPcaException.class,
                                () -> client.getCertificateAuthorityCsr(request -> request.certificateAuthorityArn(ca)))
                                .awsErrorDetails().errorCode()).isEqualTo("InvalidStateException");
                    } catch (Throwable problem) {
                        if (failure == null) {
                            failure = problem;
                        } else {
                            failure.addSuppressed(problem);
                        }
                    }
                }
                if (failure != null) {
                    AssertionError cleanupFailure = new AssertionError("Owned CA deletion-state cleanup failed", failure);
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    private static void recoverCreatedAuthorities(AcmPcaClient client, String marker, String prefix, Set<String> authorities) {
        String token = null;
        Set<String> tokens = new HashSet<>();
        for (int page = 0; page < 10; page++) {
            String next = token;
            ListCertificateAuthoritiesResponse response = client.listCertificateAuthorities(request -> request.maxResults(100).nextToken(next));
            for (CertificateAuthority candidate : response.certificateAuthorities()) {
                if (candidate.certificateAuthorityConfiguration() != null
                        && candidate.certificateAuthorityConfiguration().subject() != null
                        && marker.equals(candidate.certificateAuthorityConfiguration().subject().commonName())) {
                    assertThat(candidate.arn()).startsWith(prefix);
                    CertificateAuthority current = client.describeCertificateAuthority(request -> request
                            .certificateAuthorityArn(candidate.arn())).certificateAuthority();
                    assertThat(current.arn()).isEqualTo(candidate.arn());
                    assertThat(current.certificateAuthorityConfiguration().subject().commonName()).isEqualTo(marker);
                    authorities.add(current.arn());
                }
            }
            token = response.nextToken();
            if (token == null) {
                return;
            }
            assertThat(tokens.add(token)).as("CA cleanup pagination token repeated").isTrue();
        }
        throw new AssertionError("CA cleanup discovery exceeded ten pages");
    }
}
