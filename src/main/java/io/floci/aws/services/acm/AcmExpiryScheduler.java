package io.floci.aws.services.acm;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.services.acm.model.Certificate;
import io.floci.aws.services.acm.model.CertificateStatus;
import io.floci.aws.services.acm.model.CertificateType;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ACM's time-driven behaviour, which nothing else in Floci moves along: managed renewal of private
 * certificates and the expiry events.
 *
 * <ul>
 *   <li>A private certificate requested from a private CA is renewed when it is 60 days from expiring, as ACM
 *   renews them ("When a managed certificate is 60 days away from expiration, ACM automatically attempts to
 *   renew it"); the renewal sends "ACM Certificate Available" with Action RENEWAL. It is tried once a day, and
 *   not again for a certificate a renewal could not give a later expiry (its CA's own certificate ends first).</li>
 *   <li>Every other issued certificate that is within its notice period (45 days for private and imported
 *   certificates, 30 for public ones, or PutAccountConfiguration's DaysBeforeExpiry) gets one
 *   "ACM Certificate Approaching Expiration" event a day.</li>
 *   <li>A certificate ACM issued gets one "ACM Certificate Expired" event when it expires (none for an imported
 *   one, as on AWS).</li>
 * </ul>
 *
 * A single background thread sweeps every account's certificates on a fixed interval.
 */
@ApplicationScoped
public class AcmExpiryScheduler {

    private static final Logger LOG = Logger.getLogger(AcmExpiryScheduler.class);
    /** ACM renews a managed private certificate when it is this many days from expiring. */
    static final long MANAGED_RENEWAL_DAYS = 60;

    private final AcmService service;
    private final AcmEventPublisher events;
    private final long sweepSeconds;
    private final boolean enabled;
    private final ScheduledExecutorService executor;
    private final Clock clock;
    /** The last day each certificate (by ARN and expiry) got its Approaching Expiration notice. */
    private final Map<String, LocalDate> noticed = new HashMap<>();
    /** The certificates (by ARN and expiry) whose Expired event has been sent. */
    private final Set<String> expired = new HashSet<>();
    /** The day managed renewal of each certificate (by ARN) was last tried: a renewal is tried once a day. */
    private final Map<String, LocalDate> renewalTried = new HashMap<>();
    /**
     * The certificates (by ARN and expiry) a renewal reissued without a later expiry, as when the private CA's
     * own certificate ends first: they are not renewed again, so a sweep does not announce a renewal each day.
     */
    private final Set<String> unextendable = new HashSet<>();

    @Inject
    public AcmExpiryScheduler(AcmService service, AcmEventPublisher events, EmulatorConfig config) {
        this(service, events, config.services().acm().expirySweepSeconds(),
                config.services().acm().enabled() && config.services().acm().expirySweepEnabled(),
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "acm-expiry-scheduler");
                    t.setDaemon(true);
                    return t;
                }), Clock.systemUTC());
    }

    /** Package-private so tests drive {@link #tick} on a clock they control, with no thread. */
    AcmExpiryScheduler(AcmService service, AcmEventPublisher events, long sweepSeconds, boolean enabled,
                       ScheduledExecutorService executor, Clock clock) {
        this.service = service;
        this.events = events;
        this.sweepSeconds = sweepSeconds;
        this.enabled = enabled;
        this.executor = executor;
        this.clock = clock;
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            LOG.debug("ACM expiry sweep disabled by configuration");
            return;
        }
        executor.scheduleAtFixedRate(this::tickSafely, sweepSeconds, sweepSeconds, TimeUnit.SECONDS);
        LOG.infov("ACM expiry sweep started (every {0}s)", sweepSeconds);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    void tickSafely() {
        try {
            tick(clock.instant());
        } catch (Throwable t) {
            LOG.warnv("ACM expiry sweep failed: {0}", t.getMessage());
        }
    }

    synchronized void tick(Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        noticed.values().removeIf(day -> !day.equals(today));
        renewalTried.values().removeIf(day -> !day.equals(today));
        for (AcmService.StoredCertificate entry : service.listAllCertificates()) {
            Certificate cert = entry.certificate();
            if (cert.getNotAfter() == null || cert.getArn() == null) {
                continue;
            }
            String identity = cert.getArn() + "@" + cert.getNotAfter();
            if (!now.isBefore(cert.getNotAfter())) {
                if (cert.getType() != CertificateType.IMPORTED && issued(cert) && expired.add(identity)) {
                    events.expired(cert, RequestScopes.callAs(entry.accountId(), entry.region(),
                            () -> !service.certificateInUseBy(cert).isEmpty()));
                }
                continue;
            }
            if (cert.getStatus() != CertificateStatus.ISSUED) {
                continue;
            }
            long days = AcmEventPublisher.daysToExpiry(cert, now);
            if (renewable(cert) && days <= MANAGED_RENEWAL_DAYS && !unextendable.contains(identity)
                    && renewalTried.putIfAbsent(cert.getArn(), today) == null && renew(entry, cert, identity)) {
                continue;
            }
            if (days <= service.expiryNoticeDays(cert.getType()) && !today.equals(noticed.get(identity))) {
                noticed.put(identity, today);
                events.approachingExpiration(cert, days);
            }
        }
    }

    /** Whether ACM renews {@code cert} on its own: a private certificate it requested from a private CA. */
    static boolean renewable(Certificate cert) {
        return cert.getType() == CertificateType.PRIVATE && cert.getCertAuthorityArn() != null
                && !cert.getCertAuthorityArn().isEmpty();
    }

    private static boolean issued(Certificate cert) {
        return cert.getStatus() == CertificateStatus.ISSUED || cert.getStatus() == CertificateStatus.EXPIRED;
    }

    /** True when the renewal gave {@code cert} a later expiry; a CA that cannot sign leaves it as it is. */
    private boolean renew(AcmService.StoredCertificate entry, Certificate cert, String identity) {
        try {
            Certificate renewed = RequestScopes.callAs(entry.accountId(), entry.region(),
                    () -> service.renewCertificate(cert.getArn(), entry.region()));
            if (renewed.getNotAfter() != null && renewed.getNotAfter().isAfter(cert.getNotAfter())) {
                return true;
            }
            if (renewed.getSerial() != null && !renewed.getSerial().equals(cert.getSerial())) {
                unextendable.add(identity);
            }
            return false;
        } catch (RuntimeException e) {
            LOG.warnv("ACM managed renewal of {0} failed: {1}", cert.getArn(), e.getMessage());
            return false;
        }
    }
}
