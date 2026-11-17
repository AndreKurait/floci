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
        Map<String, IssueToken> issueTokens, String type, String certificateChainPem,
        String keyStorageSecurityStandard, Map<String, String> tags) {

    public CertificateAuthority {
        subject = Map.copyOf(subject);
        certificates = Map.copyOf(certificates);
        issueTokens = Map.copyOf(issueTokens);
        // Authorities stored before subordinate and tag support are untagged roots.
        type = type == null ? "ROOT" : type;
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    public CertificateAuthority transition(String nextStatus, String certificate, Instant now, Instant restoreUntil) {
        return new CertificateAuthority(arn, ownerAccount, region, subject, privateKeyPem, csrPem,
                certificate, nextStatus, createdAt, now, restoreUntil, creationToken, certificates, issueTokens,
                type, certificateChainPem, keyStorageSecurityStandard, tags);
    }

    /** Activates the authority with its imported certificate and, for a subordinate, the chain above it. */
    public CertificateAuthority imported(String certificate, String chain, Instant now) {
        return new CertificateAuthority(arn, ownerAccount, region, subject, privateKeyPem, csrPem,
                certificate, "ACTIVE", createdAt, now, null, creationToken, certificates, issueTokens,
                type, chain, keyStorageSecurityStandard, tags);
    }

    public CertificateAuthority tagged(Map<String, String> nextTags) {
        return new CertificateAuthority(arn, ownerAccount, region, subject, privateKeyPem, csrPem,
                certificatePem, status, createdAt, lastStateChangeAt, restorableUntil, creationToken,
                certificates, issueTokens, type, certificateChainPem, keyStorageSecurityStandard, nextTags);
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
                creationToken, issued, tokens, type, certificateChainPem, keyStorageSecurityStandard, tags);
    }

    @RegisterForReflection
    public record IssuedCertificate(String certificatePem, String chainPem) {}

    @RegisterForReflection
    public record IssueToken(String certificateArn, Instant createdAt) {}
}
