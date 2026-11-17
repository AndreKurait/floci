package io.github.hectorvent.floci.services.acm;

import java.util.Optional;

/**
 * The private certificate authorities ACM issues {@code PRIVATE} certificates from, as
 * {@code RequestCertificate}'s {@code CertificateAuthorityArn} names them. Served by AWS Private CA
 * and looked up lazily, so ACM never depends on that service directly.
 */
public interface AcmPrivateCertificateAuthorities {

    /**
     * The CA of this ARN in the caller's account and the Region, or empty when there is none there.
     */
    Optional<PrivateCertificateAuthority> find(String certificateAuthorityArn, String region);

    /**
     * A private CA as ACM sees it. {@code issuer} and {@code chainPem} are set only when it is
     * {@code active}: the CA's certificate and key, and the PEM chain a certificate it issues presents
     * above itself, the CA's certificate followed by the CA's own chain.
     */
    record PrivateCertificateAuthority(boolean active, CertificateGenerator.Issuer issuer, String chainPem) {}
}
