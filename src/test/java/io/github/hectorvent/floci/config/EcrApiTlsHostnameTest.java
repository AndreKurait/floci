package io.github.hectorvent.floci.config;

import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EcrApiTlsHostnameTest {
    @TempDir
    Path directory;

    @AfterEach
    void clearConfiguration() {
        for (String key : List.of("floci.tls.enabled", "floci.tls.self-signed",
                "floci.storage.persistent-path", "floci.dns.spoof-aws-endpoints")) {
            System.clearProperty(key);
        }
    }

    @Test
    void nativeSdkVerifierAcceptsRegionalAndFipsApiEndpoints() throws Exception {
        X509Certificate certificate = certificate(true);
        DefaultHostnameVerifier verifier = new DefaultHostnameVerifier();
        for (String host : List.of("api.ecr.eu-west-1.amazonaws.com",
                "api.ecr.us-west-2.amazonaws.com", "api.ecr-fips.us-gov-west-1.amazonaws.com",
                "api.ecr.cn-north-1.amazonaws.com.cn")) {
            assertDoesNotThrow(() -> verifier.verify(host, certificate), host);
        }
    }

    @Test
    void apiCoverageDoesNotAcceptAnotherPartitionSuffix() throws Exception {
        X509Certificate certificate = certificate(true);
        DefaultHostnameVerifier verifier = new DefaultHostnameVerifier();
        assertThrows(SSLException.class,
                () -> verifier.verify("api.ecr.cn-north-1.amazonaws.com", certificate));
        assertThrows(SSLException.class,
                () -> verifier.verify("api.ecr.eu-west-1.amazonaws.com.example.org", certificate));
    }

    @Test
    void awsApiNamesRequireAwsDnsSpoofing() throws Exception {
        X509Certificate certificate = certificate(false);
        assertThrows(SSLException.class,
                () -> new DefaultHostnameVerifier().verify("api.ecr.eu-west-1.amazonaws.com", certificate));
    }

    private X509Certificate certificate(boolean spoof) throws Exception {
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", directory.toString());
        System.setProperty("floci.dns.spoof-aws-endpoints", Boolean.toString(spoof));
        new TlsConfigSource();
        try (InputStream input = Files.newInputStream(directory.resolve("tls/floci-server.crt"))) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }
}
