package io.floci.aws.services.acmpca;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.acmpca.model.CertificateAuthority;
import io.floci.aws.services.acmpca.model.CertificateAuthority.IssueToken;
import io.floci.aws.services.acmpca.model.CertificateAuthority.IssuedCertificate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.floci.aws.services.acmpca.PrivateCaRequests.blob;
import static io.floci.aws.services.acmpca.PrivateCaRequests.fields;
import static io.floci.aws.services.acmpca.PrivateCaRequests.invalid;
import static io.floci.aws.services.acmpca.PrivateCaRequests.optionalText;
import static io.floci.aws.services.acmpca.PrivateCaRequests.text;

@ApplicationScoped
public class AcmPcaService implements Resettable {

    private final StorageBackend<String, CertificateAuthority> store;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Inject
    public AcmPcaService(StorageFactory factory, RegionResolver regionResolver, ObjectMapper mapper) {
        this(factory.create("acm-pca", "acm-pca-authorities.json",
                new TypeReference<Map<String, CertificateAuthority>>() {}), regionResolver, mapper, Clock.systemUTC());
    }

    AcmPcaService(StorageBackend<String, CertificateAuthority> store, RegionResolver regionResolver,
                  ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
        this.clock = clock;
    }

    public synchronized ObjectNode create(JsonNode request, String region) {
        Map<String, String> subject = PrivateCaRequests.configuration(request);
        String token = PrivateCaRequests.token(request);
        Instant now = now();
        if (token != null) {
            for (CertificateAuthority authority : authorities(region)) {
                if (token.equals(authority.creationToken()) && authority.createdAt().plusSeconds(300).isAfter(now)) {
                    return mapper.createObjectNode().put("CertificateAuthorityArn", authority.arn());
                }
            }
        }
        PrivateCaCertificates.KeyMaterial material = PrivateCaCertificates.create(subject);
        String arn = regionResolver.buildArn("acm-pca", region, "certificate-authority/" + UUID.randomUUID());
        CertificateAuthority authority = new CertificateAuthority(arn, regionResolver.getAccountId(), region,
                subject, material.privateKeyPem(), material.csrPem(), null, "PENDING_CERTIFICATE",
                now, now, null, token, Map.of(), Map.of());
        save(authority);
        return mapper.createObjectNode().put("CertificateAuthorityArn", arn);
    }

    public synchronized ObjectNode describe(JsonNode request, String region) {
        return mapper.createObjectNode().set("CertificateAuthority", description(authority(request, region)));
    }

    public synchronized ObjectNode csr(JsonNode request, String region) {
        CertificateAuthority authority = authority(request, region);
        requireUsable(authority);
        return mapper.createObjectNode().put("Csr", authority.csrPem());
    }

    public synchronized ObjectNode issue(JsonNode request, String region) {
        fields(request, "request", Set.of("CertificateAuthorityArn", "Csr", "SigningAlgorithm",
                "TemplateArn", "Validity", "ValidityNotBefore", "IdempotencyToken", "ApiPassthrough"));
        if (request.hasNonNull("ApiPassthrough") && !request.path("ApiPassthrough").isObject()) {
            throw invalid("ApiPassthrough must be an object");
        }
        // The supported base templates ignore API passthrough certificate fields.
        CertificateAuthority authority = authority(request, region);
        requireUsable(authority);
        if (!"SHA256WITHRSA".equals(text(request, "SigningAlgorithm"))) {
            throw invalid("The CA supports only SHA256WITHRSA");
        }
        String template = optionalText(request, "TemplateArn");
        String rootTemplate = templateArn(region, "RootCACertificate/V1");
        String leafTemplate = templateArn(region, "EndEntityCertificate/V1");
        boolean root = rootTemplate.equals(template);
        if (template != null && !root && !leafTemplate.equals(template)) {
            throw invalid("Only RootCACertificate/V1 and EndEntityCertificate/V1 templates are supported");
        }
        String status = status(authority);
        if (root ? !"PENDING_CERTIFICATE".equals(status) : !"ACTIVE".equals(status)) {
            throw state("The certificate authority is not in a state that permits this certificate template");
        }
        PKCS10CertificationRequest csr = PrivateCaCertificates.csr(blob(request, "Csr", 32_768));
        X509Certificate parent = null;
        if (root) {
            PrivateCaCertificates.requireOwnCsr(csr, authority.csrPem());
        } else {
            parent = PrivateCaCertificates.certificate(authority.certificatePem());
        }
        Instant now = now();
        Instant notBefore = PrivateCaRequests.validity(request, now, true);
        Instant notAfter = PrivateCaRequests.validity(request, now, false);
        if (!notBefore.isBefore(notAfter) || parent != null && notAfter.isAfter(parent.getNotAfter().toInstant())) {
            throw new AwsException("InvalidArgsException", "The requested validity exceeds the CA validity", 400);
        }
        String token = PrivateCaRequests.token(request);
        IssueToken previous = token == null ? null : authority.issueTokens().get(token);
        if (previous != null && previous.createdAt().plusSeconds(300).isAfter(now)) {
            return mapper.createObjectNode().put("CertificateArn", previous.certificateArn());
        }
        X509Certificate certificate = PrivateCaCertificates.issue(
                csr, authority.privateKeyPem(), parent, notBefore, notAfter);
        AwsArnUtils.Arn caArn = AwsArnUtils.parse(authority.arn());
        String arn = regionResolver.buildArn("acm-pca", region,
                caArn.resource() + "/certificate/" + certificate.getSerialNumber().toString(16));
        IssuedCertificate issued = new IssuedCertificate(
                PrivateCaCertificates.pem(certificate), root ? null : authority.certificatePem());
        save(authority.issued(arn, issued, token, now));
        return mapper.createObjectNode().put("CertificateArn", arn);
    }

    public synchronized ObjectNode certificate(JsonNode request, String region) {
        CertificateAuthority authority = authority(request, region);
        requireUsable(authority);
        String arn = text(request, "CertificateArn");
        validateArn(arn, true);
        IssuedCertificate certificate = authority.certificates().get(arn);
        if (certificate == null) {
            throw missing();
        }
        return certificateResponse(certificate.certificatePem(), certificate.chainPem());
    }

    public synchronized ObjectNode importCertificate(JsonNode request, String region) {
        fields(request, "request", Set.of("CertificateAuthorityArn", "Certificate", "CertificateChain"));
        CertificateAuthority authority = authority(request, region);
        if (!"PENDING_CERTIFICATE".equals(status(authority))) {
            throw state("Only initial activation of a pending root certificate authority is supported");
        }
        if (request.hasNonNull("CertificateChain")) {
            throw invalid("A root certificate authority does not accept a certificate chain");
        }
        X509Certificate certificate = PrivateCaCertificates.importedRoot(
                blob(request, "Certificate", 32_768), authority.csrPem(), now());
        save(authority.transition("ACTIVE", PrivateCaCertificates.pem(certificate), now(), null));
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode caCertificate(JsonNode request, String region) {
        CertificateAuthority authority = authority(request, region);
        requireUsable(authority);
        if (authority.certificatePem() == null) {
            throw state("The certificate authority does not have an imported certificate");
        }
        return certificateResponse(authority.certificatePem(), null);
    }

    public synchronized ObjectNode update(JsonNode request, String region) {
        fields(request, "request", Set.of("CertificateAuthorityArn", "Status", "RevocationConfiguration"));
        PrivateCaRequests.revocation(request);
        String requested = optionalText(request, "Status");
        if (requested != null && !Set.of("ACTIVE", "DISABLED").contains(requested)) {
            throw invalid("Status must be ACTIVE or DISABLED");
        }
        CertificateAuthority authority = authority(request, region);
        if (!Set.of("ACTIVE", "DISABLED").contains(status(authority))) {
            throw state("Only active or disabled certificate authorities can be updated");
        }
        if (requested != null && !requested.equals(authority.status())) {
            save(authority.transition(requested, authority.certificatePem(), now(), null));
        }
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode delete(JsonNode request, String region) {
        fields(request, "request", Set.of("CertificateAuthorityArn", "PermanentDeletionTimeInDays"));
        long days = request.hasNonNull("PermanentDeletionTimeInDays")
                ? PrivateCaRequests.integer(request, "PermanentDeletionTimeInDays", 7, 30) : 30;
        CertificateAuthority authority = authority(request, region);
        if (!Set.of("PENDING_CERTIFICATE", "DISABLED", "EXPIRED").contains(status(authority))) {
            throw state("Disable an active certificate authority before deleting it");
        }
        Instant now = now();
        save(authority.transition("DELETED", authority.certificatePem(), now, now.plus(days, ChronoUnit.DAYS)));
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode restore(JsonNode request, String region) {
        CertificateAuthority authority = authority(request, region);
        if (!"DELETED".equals(authority.status())) {
            throw state("Only a deleted certificate authority within its restoration period can be restored");
        }
        String status = authority.certificatePem() == null ? "PENDING_CERTIFICATE" : "DISABLED";
        save(authority.transition(status, authority.certificatePem(), now(), null));
        return mapper.createObjectNode();
    }

    public synchronized ObjectNode list(JsonNode request, String region) {
        fields(request, "request", Set.of("MaxResults", "NextToken", "ResourceOwner"));
        String owner = optionalText(request, "ResourceOwner");
        if (owner != null && !"SELF".equals(owner)) {
            throw invalid("Only SELF certificate authority ownership is supported");
        }
        long limit = request.hasNonNull("MaxResults")
                ? PrivateCaRequests.integer(request, "MaxResults", 1, 1000) : 1000;
        String after = cursor(optionalText(request, "NextToken"), region);
        List<CertificateAuthority> authorities = authorities(region).stream()
                .filter(authority -> after == null || authority.arn().compareTo(after) > 0)
                .sorted(Comparator.comparing(CertificateAuthority::arn)).toList();
        ObjectNode result = mapper.createObjectNode();
        ArrayNode values = result.putArray("CertificateAuthorities");
        int count = (int) Math.min(Math.min(limit, 100), authorities.size());
        for (int index = 0; index < count; index++) {
            values.add(description(authorities.get(index)));
        }
        if (count < authorities.size()) {
            String cursor = regionResolver.getAccountId() + "\n" + region + "\n" + authorities.get(count - 1).arn();
            result.put("NextToken", Base64.getEncoder().encodeToString(cursor.getBytes(StandardCharsets.UTF_8)));
        }
        return result;
    }

    @Override
    public synchronized void clear() {
        store.clear();
    }

    private CertificateAuthority authority(JsonNode request, String region) {
        String arn = text(request, "CertificateAuthorityArn");
        AwsArnUtils.Arn parsed = validateArn(arn, false);
        if (!region.equals(parsed.region()) || !regionResolver.getAccountId().equals(parsed.accountId())
                || !AwsRegions.partitionFor(region).equals(parsed.partition())) {
            throw missing();
        }
        // scan never migrates unprefixed legacy data into another account.
        return store.scan(arn::equals).stream().filter(value -> owned(value, region))
                .filter(this::retained).findFirst().orElseThrow(AcmPcaService::missing);
    }

    private List<CertificateAuthority> authorities(String region) {
        return store.scan(key -> true).stream().filter(value -> owned(value, region)).filter(this::retained).toList();
    }

    private boolean owned(CertificateAuthority authority, String region) {
        return authority.ownerAccount().equals(regionResolver.getAccountId()) && authority.region().equals(region);
    }

    private boolean retained(CertificateAuthority authority) {
        if (authority.restorableUntil() != null && !authority.restorableUntil().isAfter(now())) {
            try {
                store.deleteAllAtomically(Set.of(authority.arn()));
            } catch (UncheckedIOException failure) {
                throw new AwsException("InternalFailure", "Unable to remove expired private CA state", 500);
            }
            return false;
        }
        return true;
    }

    private void save(CertificateAuthority authority) {
        try {
            store.putAllAtomically(Map.of(authority.arn(), authority));
        } catch (UncheckedIOException failure) {
            throw new AwsException("InternalFailure", "Unable to store private CA state", 500);
        }
    }

    private String status(CertificateAuthority authority) {
        if (Set.of("ACTIVE", "DISABLED").contains(authority.status()) && authority.certificatePem() != null
                && !PrivateCaCertificates.certificate(authority.certificatePem()).getNotAfter().toInstant().isAfter(now())) {
            return "EXPIRED";
        }
        return authority.status();
    }

    private ObjectNode description(CertificateAuthority authority) {
        ObjectNode result = mapper.createObjectNode().put("Arn", authority.arn()).put("OwnerAccount", authority.ownerAccount())
                .put("Type", "ROOT").put("Status", status(authority)).put("UsageMode", "GENERAL_PURPOSE")
                .put("CreatedAt", authority.createdAt().getEpochSecond())
                .put("LastStateChangeAt", authority.lastStateChangeAt().getEpochSecond());
        ObjectNode config = result.putObject("CertificateAuthorityConfiguration")
                .put("KeyAlgorithm", "RSA_2048").put("SigningAlgorithm", "SHA256WITHRSA");
        config.set("Subject", mapper.valueToTree(authority.subject()));
        ObjectNode revocation = result.putObject("RevocationConfiguration");
        revocation.putObject("CrlConfiguration").put("Enabled", false);
        revocation.putObject("OcspConfiguration").put("Enabled", false);
        if (authority.certificatePem() != null) {
            X509Certificate certificate = PrivateCaCertificates.certificate(authority.certificatePem());
            result.put("NotBefore", certificate.getNotBefore().toInstant().getEpochSecond());
            result.put("NotAfter", certificate.getNotAfter().toInstant().getEpochSecond());
            result.put("Serial", certificate.getSerialNumber().toString(16));
        }
        if (authority.restorableUntil() != null) {
            result.put("RestorableUntil", authority.restorableUntil().getEpochSecond());
        }
        return result;
    }

    private ObjectNode certificateResponse(String certificate, String chain) {
        ObjectNode response = mapper.createObjectNode().put("Certificate", certificate);
        if (chain != null) {
            response.put("CertificateChain", chain);
        }
        return response;
    }

    private String cursor(String token, String region) {
        if (token == null) {
            return null;
        }
        try {
            if (token.length() > 4096) {
                throw new IllegalArgumentException("Oversized token");
            }
            String[] parts = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8).split("\n", -1);
            if (parts.length != 3 || !parts[0].equals(regionResolver.getAccountId()) || !parts[1].equals(region)) {
                throw new IllegalArgumentException("Token belongs to another scope");
            }
            AwsArnUtils.Arn arn = validateArn(parts[2], false);
            if (!arn.accountId().equals(parts[0]) || !arn.region().equals(region)
                    || !arn.partition().equals(AwsRegions.partitionFor(region))) {
                throw new IllegalArgumentException("Token contains an invalid authority");
            }
            return parts[2];
        } catch (IllegalArgumentException | AwsException failure) {
            throw new AwsException("InvalidNextTokenException", "The pagination token is invalid", 400);
        }
    }

    private static AwsArnUtils.Arn validateArn(String value, boolean certificate) {
        try {
            if (value.length() > 200 || !AwsArnUtils.isArnFor(value, "acm-pca")) {
                throw new IllegalArgumentException("Not a Private CA ARN");
            }
            AwsArnUtils.Arn arn = AwsArnUtils.parse(value);
            String suffix = certificate ? "/certificate/[0-9a-fA-F]+" : "";
            if (!arn.accountId().matches("[0-9]{12}") || arn.region().isEmpty()
                    || !arn.partition().equals(AwsRegions.partitionFor(arn.region()))
                    || !arn.resource().matches("certificate-authority/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}"
                    + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" + suffix)) {
                throw new IllegalArgumentException("Invalid Private CA ARN");
            }
            return arn;
        } catch (IllegalArgumentException failure) {
            throw new AwsException("InvalidArnException", "The Private CA ARN is invalid", 400);
        }
    }

    private static String templateArn(String region, String template) {
        return AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "acm-pca", "", "template/" + template).toString();
    }

    private static void requireUsable(CertificateAuthority authority) {
        if ("DELETED".equals(authority.status())) {
            throw state("The certificate authority is deleted");
        }
    }

    private static AwsException missing() {
        return new AwsException("ResourceNotFoundException", "The certificate authority or certificate was not found", 400);
    }

    private static AwsException state(String message) {
        return new AwsException("InvalidStateException", message, 400);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }
}
