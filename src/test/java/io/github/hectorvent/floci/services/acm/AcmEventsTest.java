package io.github.hectorvent.floci.services.acm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.CertificateType;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.acm.model.ValidationMethod;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import jakarta.enterprise.inject.Instance;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The EventBridge events ACM sends, as the ACM User Guide's "Amazon EventBridge support for ACM" describes
 * them, and the time-driven behaviour behind them: "ACM Certificate Available" on issuance, renewal, import
 * and reimport; managed renewal of a private certificate 60 days before it expires; the daily
 * "ACM Certificate Approaching Expiration" and the "ACM Certificate Expired" events. Red without the change:
 * Floci sent no ACM event, so a rule such as {"source":["aws.acm"],"detail-type":["ACM Certificate
 * Available"],"detail":{"Action":["RENEWAL"]}} never fired.
 */
class AcmEventsTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "123456789012";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CA_ARN =
            "arn:aws:acm-pca:us-east-1:123456789012:certificate-authority/11111111-2222-3333-4444-555555555555";
    private static final String SHORT_CA_ARN =
            "arn:aws:acm-pca:us-east-1:123456789012:certificate-authority/66666666-7777-8888-9999-000000000000";

    private static CertificateGenerator generator;
    private final EventBridgeService eventBridge = mock(EventBridgeService.class);
    private final AcmEventPublisher publisher = new AcmEventPublisher(eventBridge, MAPPER, Runnable::run);
    private AcmService acm;
    private AcmPrivateCertificateAuthorities pca;
    private FlociCertificateAuthority localCa;

    @BeforeAll
    static void provider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        generator = new CertificateGenerator();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void services(@TempDir Path dir) {
        RegionResolver regionResolver = new RegionResolver(REGION, ACCOUNT);
        localCa = FlociCertificateAuthority.loadOrCreate(dir.resolve("tls"));
        acm = new AcmService(new InMemoryStorage<>(), generator, localCa, regionResolver, 0);
        // An ACTIVE AWS Private CA authority, as ACM looks it up; the local CA's key signs for it.
        pca = mock(AcmPrivateCertificateAuthorities.class);
        when(pca.find(CA_ARN, REGION)).thenReturn(Optional.of(
                new AcmPrivateCertificateAuthorities.PrivateCertificateAuthority(true, localCa.issuer(),
                        localCa.caPem())));
        Instance<AcmPrivateCertificateAuthorities> authorities = mock(Instance.class);
        when(authorities.isResolvable()).thenReturn(true);
        when(authorities.get()).thenReturn(pca);
        acm.privateAuthorities = authorities;
        Instance<AcmEventPublisher> events = mock(Instance.class);
        when(events.isResolvable()).thenReturn(true);
        when(events.get()).thenReturn(publisher);
        acm.events = events;
    }

    @Test
    void aPrivateCertificateIssuedFromAnActiveCaIsAnnouncedAsAvailable() throws Exception {
        Certificate cert = privateCertificate();

        JsonNode detail = onlyEvent(AcmEventPublisher.AVAILABLE, cert.getArn());
        assertEquals("ISSUANCE", detail.path("Action").asText());
        assertEquals("PRIVATE", detail.path("CertificateType").asText());
        assertEquals("service.example.com", detail.path("CommonName").asText());
        assertEquals("service.example.com", detail.path("FirstSubjectAlternativeName").asText());
        assertEquals("AWS_MANAGED", detail.path("CertificateKeyPairOrigin").asText());
        assertEquals(395, detail.path("ValidityInDays").asLong());
        assertEquals(395, detail.path("DaysToExpiry").asLong());
        assertTrue(detail.path("CertificateExpirationDate").asText().endsWith("Z"));
        assertFalse(detail.path("InUse").asBoolean(true));
        assertFalse(detail.path("Exported").asBoolean(true));
        assertFalse(detail.has("DomainValidationMethod"), "a private certificate is not domain-validated");
    }

    @Test
    void validityInDaysCountsTheNearestWholeDay() throws Exception {
        // A certificate's end is computed before its start is stamped, and both are kept to the second.
        Certificate cert = new Certificate();
        cert.setArn("arn:aws:acm:us-east-1:123456789012:certificate/validity");
        cert.setDomainName("validity.example.com");
        cert.setType(CertificateType.PRIVATE);
        Instant start = Instant.parse("2026-01-01T00:00:01Z");
        cert.setNotBefore(start);
        cert.setNotAfter(start.plus(395, ChronoUnit.DAYS).minusSeconds(1));

        publisher.approachingExpiration(cert, 1);

        JsonNode detail = onlyEvent(AcmEventPublisher.APPROACHING_EXPIRATION, cert.getArn());
        assertEquals(395, detail.path("ValidityInDays").asLong());
    }

    @Test
    void aRenewalIsAnnouncedWithActionRenewalAndRecordedInTheRenewalSummary() throws Exception {
        Certificate cert = privateCertificate();
        acm.exportCertificate(cert.getArn(), Base64.getEncoder().encodeToString("passphrase".getBytes()), REGION);

        Certificate renewed = acm.renewCertificate(cert.getArn(), REGION);

        List<JsonNode> available = events(AcmEventPublisher.AVAILABLE, cert.getArn());
        assertEquals(List.of("ISSUANCE", "RENEWAL"), available.stream().map(d -> d.path("Action").asText()).toList());
        assertTrue(available.get(1).path("Exported").asBoolean(), "the renewed certificate keeps its exported state");
        assertEquals("SUCCESS", renewed.getRenewalStatus());
        assertEquals("SUCCESS", acm.describeCertificate(cert.getArn(), REGION).getRenewalStatus());
    }

    @Test
    void anImportAndAReimportAreAnnouncedAsAvailable() throws Exception {
        CertificateGenerator.GeneratedCertificate first =
                localCa.issueServerCertificate("imported.example.com", List.of(), KeyAlgorithm.RSA_2048, null);
        Certificate imported = acm.importCertificate(first.certificatePem(), first.privateKeyPem(), localCa.caPem(),
                null, Map.of(), REGION);
        CertificateGenerator.GeneratedCertificate second =
                localCa.issueServerCertificate("imported.example.com", List.of(), KeyAlgorithm.RSA_2048, null);
        acm.importCertificate(second.certificatePem(), second.privateKeyPem(), localCa.caPem(), imported.getArn(),
                null, REGION);

        List<JsonNode> available = events(AcmEventPublisher.AVAILABLE, imported.getArn());
        assertEquals(List.of("IMPORT", "REIMPORT"), available.stream().map(d -> d.path("Action").asText()).toList());
        assertEquals("IMPORTED", available.get(0).path("CertificateType").asText());
        assertEquals("CUSTOMER_PROVIDED", available.get(0).path("CertificateKeyPairOrigin").asText());
    }

    @Test
    void aPrivateCertificateIsRenewedSixtyDaysBeforeItExpires() throws Exception {
        Certificate cert = privateCertificate();
        AcmExpiryScheduler scheduler = scheduler();

        scheduler.tick(cert.getNotAfter().minus(61, ChronoUnit.DAYS));
        assertEquals(1, events(AcmEventPublisher.AVAILABLE, cert.getArn()).size(), "61 days out: not yet");

        scheduler.tick(cert.getNotAfter().minus(60, ChronoUnit.DAYS));
        List<JsonNode> available = events(AcmEventPublisher.AVAILABLE, cert.getArn());
        assertEquals("RENEWAL", available.getLast().path("Action").asText());
        Certificate renewed = acm.describeCertificate(cert.getArn(), REGION);
        assertFalse(renewed.getSerial().equals(cert.getSerial()), "a renewal issues a new certificate");
        assertFalse(renewed.getNotAfter().isBefore(cert.getNotAfter()));
        assertTrue(events(AcmEventPublisher.APPROACHING_EXPIRATION, cert.getArn()).isEmpty(),
                "a renewed certificate is not approaching expiration");
    }

    @Test
    void managedRenewalIsTriedOnceADayNotOnEverySweep() throws Exception {
        Certificate cert = privateCertificate();
        AcmExpiryScheduler scheduler = scheduler();
        Instant due = cert.getNotAfter().minus(60, ChronoUnit.DAYS);
        Instant lastSecondOfThatDay = due.truncatedTo(ChronoUnit.DAYS).plus(1, ChronoUnit.DAYS).minusSeconds(1);

        scheduler.tick(due);
        scheduler.tick(lastSecondOfThatDay.isAfter(due) ? lastSecondOfThatDay : due);

        assertEquals(1, renewals(cert.getArn()), "one renewal for two sweeps on the same day");
    }

    @Test
    void aRenewalTheCaCannotExtendIsNotRepeated() throws Exception {
        // A private CA whose own certificate ends in 30 days: the certificates it issues end with it.
        KeyPairGenerator keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        KeyPair caKeys = keys.generateKeyPair();
        X500Name caName = new X500Name("CN=Short-lived CA");
        X509Certificate caCertificate = generator.signCertificate(caName, caKeys.getPublic(), caName,
                caKeys.getPrivate(), List.of(), true, null, Instant.now(), Instant.now().plus(30, ChronoUnit.DAYS));
        when(pca.find(SHORT_CA_ARN, REGION)).thenReturn(Optional.of(
                new AcmPrivateCertificateAuthorities.PrivateCertificateAuthority(true,
                        new CertificateGenerator.Issuer(caCertificate, caKeys.getPrivate()),
                        generator.toPem(caCertificate))));
        Certificate cert = acm.requestCertificate("short.example.com", List.of(), ValidationMethod.DNS, null,
                KeyAlgorithm.RSA_2048, SHORT_CA_ARN, null, Map.of(), REGION);
        AcmExpiryScheduler scheduler = scheduler();
        Instant now = Instant.now();

        scheduler.tick(now);
        scheduler.tick(now.plus(1, ChronoUnit.DAYS));

        assertEquals(1, renewals(cert.getArn()), "the second day's sweep does not reissue it again");
        assertEquals(2, events(AcmEventPublisher.APPROACHING_EXPIRATION, cert.getArn()).size(),
                "it still gets its daily notice");
    }

    @Test
    void anImportedCertificateGetsOneApproachingExpirationNoticeADay() throws Exception {
        CertificateGenerator.GeneratedCertificate material =
                localCa.issueServerCertificate("expiring.example.com", List.of(), KeyAlgorithm.RSA_2048, null);
        Certificate imported = acm.importCertificate(material.certificatePem(), material.privateKeyPem(),
                localCa.caPem(), null, Map.of(), REGION);
        AcmExpiryScheduler scheduler = scheduler();
        Instant tenDaysOut = imported.getNotAfter().minus(10, ChronoUnit.DAYS);

        scheduler.tick(imported.getNotAfter().minus(46, ChronoUnit.DAYS));
        scheduler.tick(tenDaysOut);
        scheduler.tick(tenDaysOut);
        scheduler.tick(tenDaysOut.plus(1, ChronoUnit.DAYS));

        List<JsonNode> notices = events(AcmEventPublisher.APPROACHING_EXPIRATION, imported.getArn());
        assertEquals(List.of(10L, 9L), notices.stream().map(d -> d.path("DaysToExpiry").asLong()).toList());
        assertEquals("expiring.example.com", notices.get(0).path("CommonName").asText());

        scheduler.tick(imported.getNotAfter().plus(1, ChronoUnit.HOURS));
        assertTrue(events(AcmEventPublisher.EXPIRED, imported.getArn()).isEmpty(),
                "ACM sends no Expired event for an imported certificate");
    }

    @Test
    void aCertificateAcmIssuedIsAnnouncedOnceWhenItExpires() throws Exception {
        Certificate cert = acm.requestCertificate("public.example.com", List.of(), ValidationMethod.DNS, null,
                KeyAlgorithm.RSA_2048, null, null, Map.of(), REGION);
        AcmExpiryScheduler scheduler = scheduler();

        scheduler.tick(cert.getNotAfter().minus(31, ChronoUnit.DAYS));
        assertTrue(events(AcmEventPublisher.APPROACHING_EXPIRATION, cert.getArn()).isEmpty(),
                "a public certificate's notices start 30 days out");
        scheduler.tick(cert.getNotAfter().minus(30, ChronoUnit.DAYS));
        assertEquals(1, events(AcmEventPublisher.APPROACHING_EXPIRATION, cert.getArn()).size());

        scheduler.tick(cert.getNotAfter().plus(1, ChronoUnit.SECONDS));
        scheduler.tick(cert.getNotAfter().plus(1, ChronoUnit.DAYS));
        List<JsonNode> expired = events(AcmEventPublisher.EXPIRED, cert.getArn());
        assertEquals(1, expired.size());
        assertEquals("AMAZON_ISSUED", expired.get(0).path("CertificateType").asText());
        assertEquals("DNS", expired.get(0).path("DomainValidationMethod").asText());
    }

    @Test
    void putAccountConfigurationMovesTheStartOfTheNotices() throws Exception {
        Certificate cert = acm.requestCertificate("public.example.com", List.of(), ValidationMethod.DNS, null,
                KeyAlgorithm.RSA_2048, null, null, Map.of(), REGION);
        acm.putAccountConfiguration(60, "token");
        scheduler().tick(cert.getNotAfter().minus(50, ChronoUnit.DAYS));
        assertEquals(1, events(AcmEventPublisher.APPROACHING_EXPIRATION, cert.getArn()).size());
    }

    private AcmExpiryScheduler scheduler() {
        return new AcmExpiryScheduler(acm, publisher, 60, false, null, Clock.systemUTC());
    }

    private Certificate privateCertificate() {
        return acm.requestCertificate("service.example.com", List.of(), ValidationMethod.DNS, null,
                KeyAlgorithm.RSA_2048, CA_ARN, null, Map.of(), REGION);
    }

    /** The details of every event of {@code detailType} sent for {@code arn}, in order. */
    private List<JsonNode> events(String detailType, String arn) throws Exception {
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resources = ArgumentCaptor.forClass(String.class);
        try {
            verify(eventBridge, atLeast(1)).publishServiceEvent(eq(AcmEventPublisher.SOURCE), types.capture(),
                    details.capture(), resources.capture(), eq(REGION));
        } catch (AssertionError none) {
            return List.of();
        }
        List<JsonNode> found = new ArrayList<>();
        for (int i = 0; i < types.getAllValues().size(); i++) {
            if (types.getAllValues().get(i).equals(detailType) && arn.equals(resources.getAllValues().get(i))) {
                found.add(MAPPER.readTree(details.getAllValues().get(i)));
            }
        }
        return found;
    }

    private long renewals(String arn) throws Exception {
        return events(AcmEventPublisher.AVAILABLE, arn).stream()
                .filter(detail -> "RENEWAL".equals(detail.path("Action").asText())).count();
    }

    private JsonNode onlyEvent(String detailType, String arn) throws Exception {
        List<JsonNode> found = events(detailType, arn);
        assertEquals(1, found.size(), () -> "events " + detailType + " for " + arn + ": " + found);
        return found.get(0);
    }
}
