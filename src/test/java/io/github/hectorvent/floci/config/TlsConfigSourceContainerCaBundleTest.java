package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TlsConfigSource} writes the container CA bundle at boot, in both certificate modes, and
 * explicitly configured extra trust must load before boot can complete.
 */
class TlsConfigSourceContainerCaBundleTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearSystemProperties() {
        System.clearProperty("floci.tls.enabled");
        System.clearProperty("floci.tls.self-signed");
        System.clearProperty("floci.tls.cert-path");
        System.clearProperty("floci.tls.key-path");
        System.clearProperty("floci.tls.extra-ca-certs");
        System.clearProperty("floci.storage.persistent-path");
    }

    @Test
    void selfSignedBootWritesTheBundleEndingWithTheLocalCa() throws Exception {
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        new TlsConfigSource();

        Path bundle = tempDir.resolve("tls/floci-ca-bundle.pem");
        assertTrue(Files.exists(bundle), "bundle written at boot");
        String pem = Files.readString(bundle).strip();
        String caPem = Files.readString(tempDir.resolve("tls/floci-root-ca.crt")).strip();
        assertTrue(pem.endsWith(caPem), "the local CA is the last entry");
        assertTrue(pem.indexOf("-----BEGIN CERTIFICATE-----") < pem.lastIndexOf("-----BEGIN CERTIFICATE-----"),
                "public roots precede the CA");
        assertTrue(Files.isRegularFile(tempDir.resolve("tls/floci-ca-bundle.p12")), "Java trust store written at boot");
    }

    @Test
    void aJavaTrustStoreThatCannotBeWrittenLeavesTheBundleAndTheBoot() throws Exception {
        // A directory where the trust store goes, with something in it, so it cannot be replaced.
        Files.writeString(Files.createDirectories(tempDir.resolve("tls/floci-ca-bundle.p12")).resolve("x"), "x");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        new TlsConfigSource();

        assertTrue(Files.isRegularFile(tempDir.resolve("tls/floci-ca-bundle.pem")), "bundle written at boot");
        assertTrue(Files.isDirectory(tempDir.resolve("tls/floci-ca-bundle.p12")), "the boot left the obstacle alone");
    }

    @Test
    void userCertificateBootWritesTheBundleEndingWithThatCertificate() throws Exception {
        Path userDir = Files.createDirectories(tempDir.resolve("user"));
        CertificateGenerator.GeneratedCertificate generated = new CertificateGenerator()
                .generateSelfSignedCertificate("localhost", List.of("localhost"), KeyAlgorithm.RSA_2048);
        Path userCert = Files.writeString(userDir.resolve("user.crt"), generated.certificatePem());
        Path userKey = Files.writeString(userDir.resolve("user.key"), generated.privateKeyPem());
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.cert-path", userCert.toString());
        System.setProperty("floci.tls.key-path", userKey.toString());
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        new TlsConfigSource();

        String pem = Files.readString(tempDir.resolve("tls/floci-ca-bundle.pem")).strip();
        assertTrue(pem.endsWith(generated.certificatePem().strip()));
        assertFalse(Files.exists(tempDir.resolve("tls/floci-root-ca.crt")), "custom-cert mode creates no CA");
        assertEquals(tempDir.resolve("tls"), TlsConfigSource.resolvedTlsDir(), "CDI consumers look where the boot wrote");
    }

    @Test
    void tlsOffWritesNoBundle() {
        System.setProperty("floci.tls.enabled", "false");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());

        new TlsConfigSource();

        assertFalse(Files.exists(tempDir.resolve("tls")));
    }

    @Test
    void aBundleThatCannotBeWrittenDoesNotStopTheBoot() throws Exception {
        Path userDir = Files.createDirectories(tempDir.resolve("user"));
        CertificateGenerator.GeneratedCertificate generated = new CertificateGenerator()
                .generateSelfSignedCertificate("localhost", List.of("localhost"), KeyAlgorithm.RSA_2048);
        Path userCert = Files.writeString(userDir.resolve("user.crt"), generated.certificatePem());
        Path userKey = Files.writeString(userDir.resolve("user.key"), generated.privateKeyPem());
        Path persistent = Files.createDirectories(tempDir.resolve("data"));
        Files.writeString(persistent.resolve("tls"), "a file where the tls directory should be");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.cert-path", userCert.toString());
        System.setProperty("floci.tls.key-path", userKey.toString());
        System.setProperty("floci.storage.persistent-path", persistent.toString());

        TlsConfigSource source = new TlsConfigSource();

        assertEquals(userCert.toString(), source.getValue("quarkus.tls.key-store.pem.0.cert"));
        assertTrue(Files.isRegularFile(persistent.resolve("tls")), "the boot left the obstacle alone");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void extraCertificatesReachPemAndJavaTrustWithoutLeakingKeys(boolean customServer) throws Exception {
        FlociCertificateAuthority peer = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("peer"));
        FlociCertificateAuthority otherPeer = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("other-peer"));
        CertificateGenerator.GeneratedCertificate peerLeaf = peer.issueServerCertificate(
                "peer.test", List.of("peer.test"), KeyAlgorithm.RSA_2048, null);
        Path extra = Files.writeString(tempDir.resolve("extra.pem"),
                peer.caPem() + peerLeaf.privateKeyPem() + otherPeer.caPem());
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.extra-ca-certs", extra.toString());
        System.setProperty("floci.storage.persistent-path", tempDir.resolve("floci").toString());
        if (customServer) {
            CertificateGenerator.GeneratedCertificate server = new CertificateGenerator()
                    .generateSelfSignedCertificate("localhost", List.of("localhost"), KeyAlgorithm.RSA_2048);
            System.setProperty("floci.tls.cert-path",
                    Files.writeString(tempDir.resolve("server.crt"), server.certificatePem()).toString());
            System.setProperty("floci.tls.key-path",
                    Files.writeString(tempDir.resolve("server.key"), server.privateKeyPem()).toString());
        }

        new TlsConfigSource();

        Path tls = tempDir.resolve("floci/tls");
        String pem = Files.readString(tls.resolve(ContainerCaBundle.FILE_NAME));
        assertTrue(pem.contains(peer.caPem().strip()));
        assertTrue(pem.contains(otherPeer.caPem().strip()));
        assertFalse(pem.contains("PRIVATE KEY"));
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = Files.newInputStream(tls.resolve(ContainerCaBundle.JAVA_TRUST_STORE_FILE_NAME))) {
            store.load(input, null);
        }
        assertTrue(store.size() > 50, "the extra trust must retain public roots");
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        X509TrustManager trust = (X509TrustManager) factory.getTrustManagers()[0];
        trust.checkServerTrusted(new X509Certificate[]{Pem.parseCertificate(peerLeaf.certificatePem())}, "RSA");
        FlociCertificateAuthority foreign = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("foreign"));
        CertificateGenerator.GeneratedCertificate foreignLeaf = foreign.issueServerCertificate(
                "foreign.test", List.of("foreign.test"), KeyAlgorithm.RSA_2048, null);
        assertThrows(CertificateException.class, () -> trust.checkServerTrusted(
                new X509Certificate[]{Pem.parseCertificate(foreignLeaf.certificatePem())}, "RSA"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "empty", "invalid"})
    void invalidExplicitExtraTrustStopsBoot(String kind) throws Exception {
        Path extra = tempDir.resolve("extra.pem");
        if (!kind.equals("missing")) {
            Files.writeString(extra, kind.equals("empty") ? "" : "-----BEGIN CERTIFICATE-----\ninvalid\n");
        }
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.extra-ca-certs", extra.toString());
        System.setProperty("floci.storage.persistent-path", tempDir.resolve("floci").toString());

        assertThrows(IllegalStateException.class, TlsConfigSource::new);
    }

    @ParameterizedTest
    @ValueSource(strings = {"floci-ca-bundle.pem", "floci-ca-bundle.p12"})
    void explicitExtraTrustMustReachBothOutputs(String output) throws Exception {
        FlociCertificateAuthority peer = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("peer"));
        Path obstacle = tempDir.resolve("floci/tls").resolve(output);
        Files.writeString(Files.createDirectories(obstacle).resolve("retained"), "retained");
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.extra-ca-certs", peer.certificatePath().toString());
        System.setProperty("floci.storage.persistent-path", tempDir.resolve("floci").toString());

        assertThrows(IllegalStateException.class, TlsConfigSource::new);
        assertEquals("retained", Files.readString(obstacle.resolve("retained")));
    }

    @Test
    void changingExtraCertificatesReplacesTheirTrustAtTheNextBoot() throws Exception {
        FlociCertificateAuthority first = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("first"));
        FlociCertificateAuthority second = FlociCertificateAuthority.loadOrCreate(tempDir.resolve("second"));
        Path extra = Files.writeString(tempDir.resolve("extra.pem"), first.caPem());
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.extra-ca-certs", extra.toString());
        System.setProperty("floci.storage.persistent-path", tempDir.resolve("floci").toString());
        new TlsConfigSource();
        Path bundle = tempDir.resolve("floci/tls").resolve(ContainerCaBundle.FILE_NAME);
        assertTrue(Files.readString(bundle).contains(first.caPem().strip()));

        Files.writeString(extra, second.caPem());
        new TlsConfigSource();

        assertFalse(Files.readString(bundle).contains(first.caPem().strip()));
        assertTrue(Files.readString(bundle).contains(second.caPem().strip()));
    }
}
