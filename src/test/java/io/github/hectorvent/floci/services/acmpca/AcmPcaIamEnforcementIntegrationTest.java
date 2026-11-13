package io.github.hectorvent.floci.services.acmpca;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.acmpca.AcmPcaClient;
import software.amazon.awssdk.services.acmpca.model.AcmPcaException;
import software.amazon.awssdk.services.acmpca.model.CertificateAuthority;
import software.amazon.awssdk.services.acmpca.model.ListCertificateAuthoritiesResponse;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
@Timeout(120)
class AcmPcaIamEnforcementIntegrationTest {

    private static final String ACCOUNT = "811234567892";
    @TestHTTPResource("/")
    URI endpoint;
    private final PartitionCleanup cleanup = new PartitionCleanup();
    @RegisterExtension
    final AfterEachCallback fixtureCleanup = cleanup::afterEach;

    @Test
    void caScopedGrantsAndExplicitDeniesApplyBeforeStateChanges() {
        String marker = "pca-iam-" + UUID.randomUUID();
        Set<String> authorities = new LinkedHashSet<>();
        try (AcmPcaClient root = client(ACCOUNT)) {
            Throwable primaryFailure = null;
            try {
                String first = create(root, marker);
                authorities.add(first);
                String second = create(root, marker);
                authorities.add(second);
                CertificateAuthority original = describe(root, first);
                String scopedKey = user("""
                        {"Version":"2012-10-17","Statement":[
                        {"Effect":"Allow","Action":"acm-pca:*","Resource":"%s"}]}
                        """.formatted(first));
                String deniedKey = user(null);
                String explicitDenyKey = user("""
                        {"Version":"2012-10-17","Statement":[
                        {"Effect":"Allow","Action":"acm-pca:*","Resource":"*"},
                        {"Effect":"Deny","Action":"acm-pca:DeleteCertificateAuthority","Resource":"%s"}]}
                        """.formatted(first));
                try (AcmPcaClient scoped = client(scopedKey);
                     AcmPcaClient denied = client(deniedKey);
                     AcmPcaClient explicitDeny = client(explicitDenyKey)) {
                    assertEquals(original, describe(scoped, first));
                    assertTrue(scoped.getCertificateAuthorityCsr(request -> request.certificateAuthorityArn(first))
                            .csr().contains("BEGIN CERTIFICATE REQUEST"));
                    forbidden(() -> describe(scoped, second));
                    forbidden(() -> create(scoped, marker));
                    forbidden(() -> denied.getCertificateAuthorityCsr(request -> request.certificateAuthorityArn(first)));
                    forbidden(() -> explicitDeny.deleteCertificateAuthority(request -> request
                            .certificateAuthorityArn(first).permanentDeletionTimeInDays(7)));
                    assertEquals(original, describe(root, first));
                    assertEquals(2, root.listCertificateAuthorities().certificateAuthorities().stream()
                            .filter(value -> marker.equals(value.certificateAuthorityConfiguration().subject().commonName()))
                            .count());
                    explicitDeny.deleteCertificateAuthority(request -> request.certificateAuthorityArn(second)
                            .permanentDeletionTimeInDays(7));
                    assertEquals("DELETED", describe(root, second).statusAsString());
                    assertEquals(original, describe(root, first));
                    String issued = templateConditions(root, first);
                    assertEquals(root.getCertificate(request -> request.certificateAuthorityArn(first)
                                    .certificateArn(issued)).certificate(),
                            scoped.getCertificate(request -> request.certificateAuthorityArn(first)
                                    .certificateArn(issued)).certificate());
                }
            } catch (Throwable problem) {
                primaryFailure = problem;
                throw problem;
            } finally {
                Throwable failure = null;
                try {
                    recoverCreatedAuthorities(root, marker, authorities);
                } catch (Throwable discoveryFailure) {
                    failure = discoveryFailure;
                }
                for (String arn : authorities) {
                    try {
                        CertificateAuthority value = describe(root, arn);
                        assertTrue(value.arn().startsWith("arn:aws:acm-pca:us-east-1:" + ACCOUNT
                                + ":certificate-authority/"));
                        assertEquals(marker, value.certificateAuthorityConfiguration().subject().commonName());
                        if ("ACTIVE".equals(value.statusAsString())) {
                            root.updateCertificateAuthority(request -> request.certificateAuthorityArn(arn).status("DISABLED"));
                        }
                        if (!"DELETED".equals(value.statusAsString())) {
                            root.deleteCertificateAuthority(request -> request.certificateAuthorityArn(arn)
                                    .permanentDeletionTimeInDays(7));
                        }
                        assertEquals("DELETED", describe(root, arn).statusAsString());
                        assertNotNull(describe(root, arn).restorableUntil());
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

    private static void recoverCreatedAuthorities(AcmPcaClient root, String marker, Set<String> authorities) {
        String token = null;
        Set<String> tokens = new HashSet<>();
        for (int page = 0; page < 10; page++) {
            String next = token;
            ListCertificateAuthoritiesResponse response = root.listCertificateAuthorities(request -> request.maxResults(100).nextToken(next));
            for (CertificateAuthority candidate : response.certificateAuthorities()) {
                if (candidate.certificateAuthorityConfiguration() != null
                        && candidate.certificateAuthorityConfiguration().subject() != null
                        && marker.equals(candidate.certificateAuthorityConfiguration().subject().commonName())) {
                    assertTrue(candidate.arn().startsWith("arn:aws:acm-pca:us-east-1:" + ACCOUNT
                            + ":certificate-authority/"));
                    CertificateAuthority current = describe(root, candidate.arn());
                    assertEquals(candidate.arn(), current.arn());
                    assertEquals(marker, current.certificateAuthorityConfiguration().subject().commonName());
                    authorities.add(current.arn());
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

    private String templateConditions(AcmPcaClient root, String arn) {
        String rootTemplate = "arn:aws:acm-pca:::template/RootCACertificate/V1";
        String leafTemplate = "arn:aws:acm-pca:::template/EndEntityCertificate/V1";
        String csr = root.getCertificateAuthorityCsr(request -> request.certificateAuthorityArn(arn)).csr();
        String rootKey = user("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":"acm-pca:IssueCertificate","Resource":"%s",
                "Condition":{"ArnEquals":{"acm-pca:TemplateArn":"%s"}}}]}
                """.formatted(arn, rootTemplate));
        String leafKey = user("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":"acm-pca:IssueCertificate","Resource":"%s"},
                {"Effect":"Deny","Action":"acm-pca:IssueCertificate","Resource":"%s",
                "Condition":{"ArnEquals":{"acm-pca:TemplateArn":"%s"}}}]}
                """.formatted(arn, arn, rootTemplate));
        String defaultKey = user("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                "Action":"acm-pca:IssueCertificate","Resource":"%s",
                "Condition":{"ArnEquals":{"acm-pca:TemplateArn":"%s"}}}]}
                """.formatted(arn, leafTemplate));
        try (AcmPcaClient roots = client(rootKey); AcmPcaClient leaves = client(leafKey);
             AcmPcaClient defaults = client(defaultKey)) {
            CertificateAuthority pending = describe(root, arn);
            forbidden(() -> issue(roots, arn, csr, null, 1));
            forbidden(() -> issue(leaves, arn, csr, rootTemplate, 2));
            assertEquals(pending, describe(root, arn));
            String certificateArn = issue(roots, arn, csr, rootTemplate, 2);
            String certificate = root.getCertificate(request -> request.certificateAuthorityArn(arn)
                    .certificateArn(certificateArn)).certificate();
            root.importCertificateAuthorityCertificate(request -> request.certificateAuthorityArn(arn)
                    .certificate(SdkBytes.fromUtf8String(certificate)));
            String leaf = issue(defaults, arn, csr, null, 1);
            assertNotEquals(certificateArn, leaf);
            assertTrue(root.getCertificate(request -> request.certificateAuthorityArn(arn).certificateArn(leaf))
                    .certificate().contains("BEGIN CERTIFICATE"));
            forbidden(() -> issue(leaves, arn, csr, rootTemplate, 2));
            assertEquals(certificate, root.getCertificate(request -> request.certificateAuthorityArn(arn)
                    .certificateArn(certificateArn)).certificate());
            return leaf;
        }
    }

    private static String issue(AcmPcaClient client, String arn, String csr, String template, long days) {
        return client.issueCertificate(request -> request.certificateAuthorityArn(arn).csr(SdkBytes.fromUtf8String(csr))
                .signingAlgorithm("SHA256WITHRSA").templateArn(template)
                .validity(validity -> validity.type("DAYS").value(days))).certificateArn();
    }

    private String user(String policy) {
        String name = "pca-iam-" + UUID.randomUUID();
        String key = EnforcementFixtures.createUser(cleanup, endpoint.getPort(), auth(ACCOUNT, "iam"), name).accessKeyId();
        if (policy != null) {
            EnforcementFixtures.putUserPolicy(endpoint.getPort(), auth(ACCOUNT, "iam"), name, "pca", policy);
        }
        return key;
    }

    private AcmPcaClient client(String key) {
        return AwsTestClients.create(AcmPcaClient.builder().overrideConfiguration(configuration -> configuration
                .retryPolicy(RetryPolicy.none()).apiCallTimeout(Duration.ofSeconds(20))
                .apiCallAttemptTimeout(Duration.ofSeconds(15))), key, Region.US_EAST_1, endpoint);
    }

    private static String create(AcmPcaClient client, String marker) {
        return client.createCertificateAuthority(request -> request.certificateAuthorityType("ROOT")
                .idempotencyToken(UUID.randomUUID().toString())
                .certificateAuthorityConfiguration(configuration -> configuration.keyAlgorithm("RSA_2048")
                        .signingAlgorithm("SHA256WITHRSA").subject(subject -> subject.commonName(marker))))
                .certificateAuthorityArn();
    }

    private static CertificateAuthority describe(AcmPcaClient client, String arn) {
        return client.describeCertificateAuthority(request -> request.certificateAuthorityArn(arn)).certificateAuthority();
    }

    private static void forbidden(Runnable operation) {
        AcmPcaException denied = assertThrows(AcmPcaException.class, operation::run);
        assertEquals(400, denied.statusCode());
        assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
    }
}
