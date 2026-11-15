package io.github.hectorvent.floci.services.acmpca.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Private signing material stays in storage and never appears in API descriptions. */
@RegisterForReflection
public record CertificateAuthority(
        String arn, String ownerAccount, String region, Map<String, String> subject,
        String privateKeyPem, String csrPem, String certificatePem, String status,
        Instant createdAt, Instant lastStateChangeAt, Instant restorableUntil,
        String creationToken, Map<String, IssuedCertificate> certificates,
        Map<String, IssueToken> issueTokens) {

    public CertificateAuthority {
        subject = Map.copyOf(subject);
        certificates = Map.copyOf(certificates);
        issueTokens = Map.copyOf(issueTokens);
    }

    public CertificateAuthority transition(String nextStatus, String certificate, Instant now, Instant restoreUntil) {
        return new CertificateAuthority(arn, ownerAccount, region, subject, privateKeyPem, csrPem,
                certificate, nextStatus, createdAt, now, restoreUntil, creationToken, certificates, issueTokens);
    }

    public CertificateAuthority issued(String certificateArn, IssuedCertificate certificate,
                                       String token, Instant now) {
        Map<String, IssuedCertificate> issued = new LinkedHashMap<>(certificates);
        issued.put(certificateArn, certificate);
        Map<String, IssueToken> tokens = new LinkedHashMap<>();
        issueTokens.forEach((key, value) -> {
            if (value.createdAt().plusSeconds(300).isAfter(now)) {
                tokens.put(key, value);
            }
        });
        if (token != null) {
            tokens.put(token, new IssueToken(certificateArn, now));
        }
        return new CertificateAuthority(arn, ownerAccount, region, subject, privateKeyPem, csrPem,
                certificatePem, status, createdAt, lastStateChangeAt, restorableUntil,
                creationToken, issued, tokens);
    }

    @RegisterForReflection
    public record IssuedCertificate(String certificatePem, String chainPem) {}

    @RegisterForReflection
    public record IssueToken(String certificateArn, Instant createdAt) {}
}
