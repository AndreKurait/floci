package io.floci.aws.services.acmpca;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Pem;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.HybridStorage;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.WalStorage;
import io.floci.aws.services.acmpca.model.CertificateAuthority;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1String;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AcmPcaServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "811234567890";
    private static final String REGION = "us-east-1";
    private static final TypeReference<Map<String, CertificateAuthority>> TYPE = new TypeReference<>() {};
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void restartRetainsKeysIssuedCertificatesTokensAndRestorationState(String mode) {
        AdjustableClock clock = new AdjustableClock();
        String ca;
        String issued;
        CertificateAuthority expected;
        StorageBackend<String, CertificateAuthority> first = backend(mode);
        first.load();
        try {
            AcmPcaService service = service(first, clock);
            ca = create(service, "same-token");
            issued = root(service, first.get(ca).orElseThrow());
            service.importCertificate(importRequest(ca, service.certificate(
                    arn(ca).put("CertificateArn", issued), REGION).path("Certificate").asText()), REGION);
            service.update(arn(ca).put("Status", "DISABLED"), REGION);
            service.delete(arn(ca).put("PermanentDeletionTimeInDays", 7), REGION);
            expected = first.get(ca).orElseThrow();
            first.flush();
        } finally {
            shutdown(first);
        }
        StorageBackend<String, CertificateAuthority> reopened = backend(mode);
        reopened.load();
        try {
            AcmPcaService service = service(reopened, clock);
            assertEquals(expected, reopened.get(ca).orElseThrow());
            assertEquals(ca, create(service, "same-token"));
            service.restore(arn(ca), REGION);
            assertEquals("DISABLED", service.describe(arn(ca), REGION).path("CertificateAuthority").path("Status").asText());
            assertEquals(expected.certificates().get(issued).certificatePem(),
                    service.certificate(arn(ca).put("CertificateArn", issued), REGION).path("Certificate").asText());
            assertEquals(expected.csrPem(), service.csr(arn(ca), REGION).path("Csr").asText());
            assertEquals(expected.privateKeyPem(), reopened.get(ca).orElseThrow().privateKeyPem());
            service.update(arn(ca).put("Status", "ACTIVE"), REGION);
            String leaf = service.issue(issueRequest(ca, expected.csrPem(), false), REGION).path("CertificateArn").asText();
            assertNotEquals(issued, leaf);
            X509Certificate certificate = PrivateCaCertificates.certificate(service.certificate(
                    arn(ca).put("CertificateArn", leaf), REGION).path("Certificate").asText());
            assertEquals(-1, certificate.getBasicConstraints());
            assertDoesNotThrow(() -> certificate.verify(
                    PrivateCaCertificates.certificate(expected.certificatePem()).getPublicKey()));
        } finally {
            shutdown(reopened);
        }
    }

    @Test
    void deletionExpiresOnlyAtItsDeadlineAndTokensExpireAfterFiveMinutes() {
        AdjustableClock clock = new AdjustableClock();
        InMemoryStorage<String, CertificateAuthority> store = new InMemoryStorage<>();
        AcmPcaService service = service(store, clock);
        String ca = create(service, "creation-token");
        assertEquals(ca, create(service, "creation-token"));
        String issued = root(service, store.get(ca).orElseThrow());
        assertEquals(issued, root(service, store.get(ca).orElseThrow()));
        clock.advance(300);
        assertNotEquals(ca, create(service, "creation-token"));
        assertNotEquals(issued, root(service, store.get(ca).orElseThrow()));
        service.delete(arn(ca).put("PermanentDeletionTimeInDays", 7), REGION);
        clock.advance(7 * 86400 - 1);
        assertEquals("DELETED", service.describe(arn(ca), REGION).path("CertificateAuthority").path("Status").asText());
        clock.advance(1);
        error("ResourceNotFoundException", () -> service.describe(arn(ca), REGION));
        assertFalse(store.keys().contains(ca));
        error("ResourceNotFoundException", () -> service.restore(arn(ca), REGION));
        assertEquals(1, store.keys().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-ca", "not-critical", "no-cert-sign", "constrained", "wrong-subject", "expired", "future"})
    void matchingKeyCertificatesWithInvalidCaConstraintsCannotActivate(String shape) throws Exception {
        AdjustableClock clock = new AdjustableClock();
        InMemoryStorage<String, CertificateAuthority> store = new InMemoryStorage<>();
        AcmPcaService service = service(store, clock);
        String ca = create(service, null);
        CertificateAuthority before = store.get(ca).orElseThrow();
        PKCS10CertificationRequest csr = PrivateCaCertificates.csr(before.csrPem());
        X500Name subject = "wrong-subject".equals(shape) ? new X500Name("CN=another-root") : csr.getSubject();
        Instant start = clock.instant().plusSeconds("future".equals(shape) ? 3600 : -86400);
        Instant end = clock.instant().plusSeconds("expired".equals(shape) ? -60 : 86400);
        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(subject, BigInteger.TEN,
                Date.from(start), Date.from(end), subject, csr.getSubjectPublicKeyInfo());
        builder.addExtension(Extension.basicConstraints, !"not-critical".equals(shape),
                new BasicConstraints(!"not-ca".equals(shape)));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage("no-cert-sign".equals(shape)
                ? KeyUsage.digitalSignature : KeyUsage.keyCertSign));
        if ("constrained".equals(shape)) {
            builder.addExtension(new ASN1ObjectIdentifier("1.2.3.4"), true,
                    new DERUTF8String("unsupported constraint"));
        }
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(builder.build(
                new JcaContentSignerBuilder("SHA256withRSA").build(Pem.parsePrivateKey(before.privateKeyPem()))));
        certificate.verify(certificate.getPublicKey());
        String expected = switch (shape) {
            case "constrained" -> "InvalidRequestException";
            case "wrong-subject" -> "CertificateMismatchException";
            default -> "MalformedCertificateException";
        };
        error(expected,
                () -> service.importCertificate(importRequest(ca, PrivateCaCertificates.pem(certificate)), REGION));
        assertEquals(before, store.get(ca).orElseThrow());
        assertEquals(Set.of(ca), store.keys());
    }

    @Test
    void persistentCommitFailureDoesNotPublishActivationOrLoseItsPriorKey() throws Exception {
        AdjustableClock clock = new AdjustableClock();
        StorageBackend<String, CertificateAuthority> store = backend("persistent");
        store.load();
        AcmPcaService service = service(store, clock);
        String ca = create(service, null);
        String issued = root(service, store.get(ca).orElseThrow());
        String pem = service.certificate(arn(ca).put("CertificateArn", issued), REGION).path("Certificate").asText();
        CertificateAuthority before = store.get(ca).orElseThrow();
        Path path = directory.resolve("authorities.json");
        Path retained = directory.resolve("prior.json");
        byte[] original = Files.readAllBytes(path);
        Files.move(path, retained);
        Files.createDirectory(path);
        try {
            AwsException failure = assertThrows(AwsException.class,
                    () -> service.importCertificate(importRequest(ca, pem), REGION));
            assertEquals(500, failure.getHttpStatus());
            assertEquals(before, store.get(ca).orElseThrow());
            assertArrayEquals(original, Files.readAllBytes(retained));
        } finally {
            Files.delete(path);
            Files.move(retained, path);
        }
        StorageBackend<String, CertificateAuthority> reopened = backend("persistent");
        reopened.load();
        assertEquals(before, reopened.get(ca).orElseThrow());
        service.importCertificate(importRequest(ca, pem), REGION);
        assertEquals("ACTIVE", store.get(ca).orElseThrow().status());
    }

    @Test
    void unprefixedLegacyRecordsAreNotMigratedOrRemovedByScopedOperations() {
        AdjustableClock clock = new AdjustableClock();
        InMemoryStorage<String, CertificateAuthority> raw = new InMemoryStorage<>();
        AcmPcaService original = service(raw, clock);
        String ca = create(original, null);
        CertificateAuthority legacy = raw.get(ca).orElseThrow();
        AccountAwareStorageBackend<CertificateAuthority> aware = new AccountAwareStorageBackend<>(raw, null, ACCOUNT);
        AcmPcaService scoped = service(aware, clock);
        error("ResourceNotFoundException", () -> scoped.describe(arn(ca), REGION));
        AcmPcaService foreign = new AcmPcaService(new AccountAwareStorageBackend<>(raw, null, "811234567891"),
                new RegionResolver(REGION, "811234567891"), MAPPER, clock);
        error("ResourceNotFoundException", () -> foreign.delete(arn(ca), REGION));
        assertEquals(Set.of(ca), raw.keys());
        assertEquals(legacy, raw.get(ca).orElseThrow());
        aware.put(ca, legacy);
        scoped.delete(arn(ca).put("PermanentDeletionTimeInDays", 7), REGION);
        assertEquals(legacy, raw.get(ca).orElseThrow());
        assertEquals("DELETED", raw.get(ACCOUNT + "/" + ca).orElseThrow().status());
        clock.advance(7 * 86400);
        error("ResourceNotFoundException", () -> scoped.describe(arn(ca), REGION));
        assertEquals(Set.of(ca), raw.keys());
        assertEquals(legacy, raw.get(ca).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "17", "\"wrong\"", "{}", "{\"Country\":\"USA\"}",
            "{\"CommonName\":false}", "{\"CommonName\":\"\"}", "{\"CustomAttributes\":[]}", "{\"SerialNumber\":\"☃\"}"})
    void invalidSubjectShapesDoNotCreateKeysOrRecords(String subject) throws Exception {
        InMemoryStorage<String, CertificateAuthority> store = new InMemoryStorage<>();
        AcmPcaService service = service(store, new AdjustableClock());
        ObjectNode request = createRequest(null);
        request.withObject("/CertificateAuthorityConfiguration").set("Subject", MAPPER.readTree(subject));
        error("InvalidArgsException", () -> service.create(request, REGION));
        assertTrue(store.keys().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"CommonName,64", "Organization,64", "OrganizationalUnit,64", "State,128", "Locality,128"})
    void subjectLimitsCountUnicodeScalarsAndPreserveTheirCsrValues(String field, int maximum) {
        InMemoryStorage<String, CertificateAuthority> store = new InMemoryStorage<>();
        AcmPcaService service = service(store, new AdjustableClock());
        ObjectNode request = createRequest(null);
        ASN1ObjectIdentifier oid = switch (field) {
            case "CommonName" -> BCStyle.CN;
            case "Organization" -> BCStyle.O;
            case "OrganizationalUnit" -> BCStyle.OU;
            case "State" -> BCStyle.ST;
            case "Locality" -> BCStyle.L;
            default -> throw new IllegalArgumentException(field);
        };
        for (String scalar : List.of(new String(Character.toChars(0x1F642)), "é")) {
            String value = scalar.repeat(maximum);
            request.withObject("/CertificateAuthorityConfiguration/Subject").put(field, value);
            String ca = service.create(request, REGION).path("CertificateAuthorityArn").asText();
            CertificateAuthority authority = store.get(ca).orElseThrow();
            assertEquals(value, authority.subject().get(field));
            PKCS10CertificationRequest csr = PrivateCaCertificates.csr(authority.csrPem());
            assertEquals(value, assertInstanceOf(ASN1String.class,
                    csr.getSubject().getRDNs(oid)[0].getFirst().getValue()).getString());
            List<CertificateAuthority> before = List.copyOf(store.scan(key -> true));
            request.withObject("/CertificateAuthorityConfiguration/Subject").put(field, value + scalar);
            error("InvalidArgsException", () -> service.create(request, REGION));
            assertEquals(before, store.scan(key -> true));
        }
    }

    @Test
    void subjectStringsRemainLiteralInTheirCsr() {
        InMemoryStorage<String, CertificateAuthority> store = new InMemoryStorage<>();
        AcmPcaService service = service(store, new AdjustableClock());
        for (Map.Entry<String, ASN1ObjectIdentifier> field : Map.of("CommonName", BCStyle.CN, "Organization", BCStyle.O,
                "OrganizationalUnit", BCStyle.OU, "State", BCStyle.ST, "Locality", BCStyle.L).entrySet()) {
            ObjectNode request = createRequest(null);
            for (String value : List.of("#not-hex", "\\leading")) {
                request.withObject("/CertificateAuthorityConfiguration/Subject").put(field.getKey(), value);
                String arn = service.create(request, REGION).path("CertificateAuthorityArn").asText();
                PKCS10CertificationRequest csr = PrivateCaCertificates.csr(store.get(arn).orElseThrow().csrPem());
                assertEquals(value, assertInstanceOf(ASN1String.class,
                        csr.getSubject().getRDNs(field.getValue())[0].getFirst().getValue()).getString());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0xD800, 0xDC00})
    void unpairedSurrogatesDoNotCreateKeysOrRecords(int surrogate) {
        InMemoryStorage<String, CertificateAuthority> store = new InMemoryStorage<>();
        AcmPcaService service = service(store, new AdjustableClock());
        ObjectNode request = createRequest(null);
        request.withObject("/CertificateAuthorityConfiguration/Subject")
                .put("CommonName", "name" + (char) surrogate);
        error("InvalidArgsException", () -> service.create(request, REGION));
        assertTrue(store.keys().isEmpty());
    }

    @Test
    void malformedApiPassthroughDoesNotIssueOrChangeTheAuthority() throws Exception {
        InMemoryStorage<String, CertificateAuthority> store = new InMemoryStorage<>();
        AcmPcaService service = service(store, new AdjustableClock());
        String ca = create(service, null);
        CertificateAuthority before = store.get(ca).orElseThrow();
        for (String value : List.of("[]", "false", "17", "\"wrong\"")) {
            ObjectNode request = issueRequest(ca, before.csrPem(), true);
            request.set("ApiPassthrough", MAPPER.readTree(value));
            error("InvalidArgsException", () -> service.issue(request, REGION));
            assertEquals(before, store.get(ca).orElseThrow());
        }
    }

    @Test
    void rejectedUpdatesPreserveCompleteStateAndDescriptionsNeverExposeSigningKeys() {
        InMemoryStorage<String, CertificateAuthority> store = new InMemoryStorage<>();
        AcmPcaService service = service(store, new AdjustableClock());
        String ca = create(service, null);
        CertificateAuthority before = store.get(ca).orElseThrow();
        error("InvalidStateException", () -> service.update(arn(ca).put("Status", "ACTIVE"), REGION));
        error("InvalidArgsException", () -> service.delete(arn(ca).put("PermanentDeletionTimeInDays", 0), REGION));
        error("InvalidArgsException", () -> service.delete(arn(ca).put("PermanentDeletionTimeInDays", 7.1), REGION));
        ObjectNode revocation = arn(ca);
        revocation.putObject("RevocationConfiguration").putObject("OcspConfiguration").put("Enabled", true);
        error("InvalidArgsException", () -> service.update(revocation, REGION));
        assertEquals(before, store.get(ca).orElseThrow());
        String response = service.describe(arn(ca), REGION).toString();
        assertFalse(response.contains("PRIVATE KEY"));
        assertFalse(response.contains("privateKeyPem"));
        assertFalse(response.contains("csrPem"));
    }

    private StorageBackend<String, CertificateAuthority> backend(String mode) {
        Path path = directory.resolve("authorities.json");
        return switch (mode) {
            case "persistent" -> new PersistentStorage<>(path, TYPE);
            case "hybrid" -> new HybridStorage<>(path, TYPE, 3_600_000);
            case "wal" -> new WalStorage<>(path, directory.resolve("authorities.wal"), TYPE, 3_600_000);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private static void shutdown(StorageBackend<String, CertificateAuthority> backend) {
        if (backend instanceof WalStorage<String, CertificateAuthority> wal) {
            wal.shutdown();
        } else if (backend instanceof HybridStorage<String, CertificateAuthority> hybrid) {
            hybrid.shutdown();
        }
    }

    private static AcmPcaService service(StorageBackend<String, CertificateAuthority> backend, Clock clock) {
        return new AcmPcaService(backend, new RegionResolver(REGION, ACCOUNT), MAPPER, clock);
    }

    private static String create(AcmPcaService service, String token) {
        return service.create(createRequest(token), REGION).path("CertificateAuthorityArn").asText();
    }

    private static ObjectNode createRequest(String token) {
        ObjectNode request = MAPPER.createObjectNode().put("CertificateAuthorityType", "ROOT");
        request.putObject("CertificateAuthorityConfiguration").put("KeyAlgorithm", "RSA_2048")
                .put("SigningAlgorithm", "SHA256WITHRSA").putObject("Subject").put("CommonName", "unit-ca");
        if (token != null) {
            request.put("IdempotencyToken", token);
        }
        return request;
    }

    private static String root(AcmPcaService service, CertificateAuthority authority) {
        return service.issue(issueRequest(authority.arn(), authority.csrPem(), true), REGION).path("CertificateArn").asText();
    }

    private static ObjectNode issueRequest(String ca, String csr, boolean root) {
        ObjectNode request = arn(ca).put("Csr", Base64.getEncoder().encodeToString(csr.getBytes(StandardCharsets.US_ASCII)))
                .put("SigningAlgorithm", "SHA256WITHRSA").put("IdempotencyToken", root ? "root-token" : "leaf-token");
        if (root) {
            request.put("TemplateArn", "arn:aws:acm-pca:::template/RootCACertificate/V1");
        }
        request.putObject("Validity").put("Type", "DAYS").put("Value", root ? 365 : 1);
        return request;
    }

    private static ObjectNode importRequest(String ca, String certificate) {
        return arn(ca).put("Certificate",
                Base64.getEncoder().encodeToString(certificate.getBytes(StandardCharsets.US_ASCII)));
    }

    private static ObjectNode arn(String ca) {
        return MAPPER.createObjectNode().put("CertificateAuthorityArn", ca);
    }

    private static void error(String code, Runnable operation) {
        assertEquals(code, assertThrows(AwsException.class, operation::run).getErrorCode());
    }

    private static final class AdjustableClock extends Clock {
        private Instant now = Instant.parse("2026-11-13T12:00:00Z");

        private void advance(long seconds) {
            now = now.plus(seconds, ChronoUnit.SECONDS);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
