package io.github.hectorvent.floci.services.acm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.CertificateType;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The events ACM sends to the default EventBridge bus of a certificate's account and Region, with the
 * detail of the ACM User Guide's "Amazon EventBridge support for ACM": "ACM Certificate Available" on
 * issuance, renewal, import and reimport, "ACM Certificate Approaching Expiration" and "ACM Certificate
 * Expired". An event is written as its change is made and sent from a thread of its own, in the order
 * the changes were made, so a rule's target that calls ACM never waits on the call that made the change.
 *
 * <p>Fields Floci has no model for are left out: ACME (AcmeAccountId, AcmeEndpointArn) and
 * CertificateKeyPairOrigin other than AWS_MANAGED for an ACM-issued certificate and CUSTOMER_PROVIDED for
 * an imported one.
 */
@ApplicationScoped
public class AcmEventPublisher {

    static final String SOURCE = "aws.acm";
    static final String AVAILABLE = "ACM Certificate Available";
    static final String APPROACHING_EXPIRATION = "ACM Certificate Approaching Expiration";
    static final String EXPIRED = "ACM Certificate Expired";
    // The guide's examples write their times to the second: "2019-12-22T18:43:48Z".
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /** The Action of an "ACM Certificate Available" event. */
    enum Action { ISSUANCE, RENEWAL, IMPORT, REIMPORT }

    private final EventBridgeService eventBridgeService;
    private final ObjectMapper objectMapper;
    private final Executor deliveries;

    @Inject
    public AcmEventPublisher(EventBridgeService eventBridgeService, ObjectMapper objectMapper) {
        this(eventBridgeService, objectMapper, Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("acm-events").factory()));
    }

    AcmEventPublisher(EventBridgeService eventBridgeService, ObjectMapper objectMapper, Executor deliveries) {
        this.eventBridgeService = eventBridgeService;
        this.objectMapper = objectMapper;
        this.deliveries = deliveries;
    }

    @PreDestroy
    void shutdown() {
        if (deliveries instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
    }

    /** {@code cert} has become available for use through {@code action}, as of {@code now}. */
    void available(Certificate cert, Action action, Instant now) {
        ObjectNode detail = objectMapper.createObjectNode().put("Action", action.name())
                .put("CertificateType", cert.getType().name());
        identity(detail, cert);
        if (cert.getType() == CertificateType.AMAZON_ISSUED && cert.getValidationMethod() != null) {
            detail.put("DomainValidationMethod", cert.getValidationMethod().name());
        }
        if (cert.getCreatedAt() != null || cert.getImportedAt() != null) {
            detail.put("CertificateCreatedDate",
                    TIME.format(cert.getCreatedAt() != null ? cert.getCreatedAt() : cert.getImportedAt()));
        }
        if (cert.getNotAfter() != null) {
            detail.put("CertificateExpirationDate", TIME.format(cert.getNotAfter()))
                    .put("DaysToExpiry", daysToExpiry(cert, now));
        }
        detail.put("InUse", !cert.getInUseBy().isEmpty()).put("Exported", cert.isExported());
        publish(AVAILABLE, detail, cert.getArn());
    }

    /** {@code cert} expires in {@code daysToExpiry} days (the daily notice). */
    void approachingExpiration(Certificate cert, long daysToExpiry) {
        ObjectNode detail = objectMapper.createObjectNode().put("DaysToExpiry", daysToExpiry);
        identity(detail, cert);
        publish(APPROACHING_EXPIRATION, detail, cert.getArn());
    }

    /** {@code cert}, issued by ACM, has expired. */
    void expired(Certificate cert) {
        ObjectNode detail = objectMapper.createObjectNode().put("CertificateType", cert.getType().name());
        identity(detail, cert);
        if (cert.getType() == CertificateType.AMAZON_ISSUED && cert.getValidationMethod() != null) {
            detail.put("DomainValidationMethod", cert.getValidationMethod().name());
        }
        if (cert.getCreatedAt() != null) {
            detail.put("CertificateCreatedDate", TIME.format(cert.getCreatedAt()));
        }
        if (cert.getNotAfter() != null) {
            detail.put("CertificateExpirationDate", TIME.format(cert.getNotAfter()));
        }
        detail.put("InUse", !cert.getInUseBy().isEmpty()).put("Exported", cert.isExported());
        publish(EXPIRED, detail, cert.getArn());
    }

    /** Whole days until {@code cert} expires, rounded up as ACM counts them (0 once it has expired). */
    static long daysToExpiry(Certificate cert, Instant now) {
        long seconds = Duration.between(now, cert.getNotAfter()).getSeconds();
        return seconds <= 0 ? 0 : (seconds + 86_399) / 86_400;
    }

    private static void identity(ObjectNode detail, Certificate cert) {
        detail.put("CommonName", cert.getDomainName());
        if (!cert.getSubjectAlternativeNames().isEmpty()) {
            detail.put("FirstSubjectAlternativeName", cert.getSubjectAlternativeNames().getFirst());
        }
        if (cert.getNotBefore() != null && cert.getNotAfter() != null) {
            // The nearest whole day: the end is computed before the start is stamped, both to the second.
            long seconds = Duration.between(cert.getNotBefore(), cert.getNotAfter()).getSeconds();
            detail.put("ValidityInDays", (seconds + 43_200) / 86_400);
        }
        detail.put("CertificateKeyPairOrigin",
                cert.getType() == CertificateType.IMPORTED ? "CUSTOMER_PROVIDED" : "AWS_MANAGED");
    }

    private void publish(String detailType, ObjectNode detail, String certificateArn) {
        String event = detail.toString();
        AwsArnUtils.Arn arn = AwsArnUtils.parse(certificateArn);
        deliveries.execute(() -> RequestScopes.runAs(arn.accountId(), arn.region(), () ->
                eventBridgeService.publishServiceEvent(SOURCE, detailType, event, certificateArn, arn.region())));
    }
}
