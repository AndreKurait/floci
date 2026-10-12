package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.ServerCertificate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.Optional;

/**
 * Whether a certificate another service names exists, and the lock that keeps the answer true for
 * the length of a write.
 *
 * <p>A load balancer listener or a CloudFront distribution serves a certificate it does not own:
 * IAM holds server certificates and ACM holds the rest, and the referring service carries only the
 * ARN. Both of those lookups and the lock belong to neither referrer, which is why they live here
 * rather than in one service's package.
 *
 * <p>A service that is not present answers for its own certificates: an absent {@link IamService}
 * or {@link AcmService} leaves every ARN in its namespace existing rather than missing. That keeps
 * a unit test that wires one referrer and no certificate service on the behaviour it had before a
 * check existed, which is the same judgement the referrers make about an absent resolver.
 */
@ApplicationScoped
public class CertificateReferenceResolver {

    private static final Logger LOG = Logger.getLogger(CertificateReferenceResolver.class);

    private final IamService iamService;
    private final AcmService acmService;

    @Inject
    public CertificateReferenceResolver(IamService iamService, AcmService acmService) {
        this.iamService = iamService;
        this.acmService = acmService;
    }

    /**
     * Whether the ARN names a certificate that exists. Anything that is not an ARN of a service
     * that serves certificates does not name one, so it does not exist either.
     */
    public boolean exists(String region, String certificateArn) {
        if (certificateArn == null || certificateArn.isBlank()
                || !AwsArnUtils.isArn(certificateArn)) {
            return false;
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(certificateArn);
        return switch (arn.service()) {
            case "iam" -> iamService == null || iamServerCertificate(arn, certificateArn).isPresent();
            case "acm" -> acmService == null || acmCertificateExists(region, certificateArn);
            default -> false;
        };
    }

    /**
     * Runs a write that records an IAM server certificate while IAM holds the lock
     * {@code DeleteServerCertificate} takes, so the certificate cannot be deleted between the
     * check and the write. A caller re-checks inside the action: a check made before the lock
     * says nothing about the moment of the write, while one made outside it keeps the error
     * precedence AWS has.
     *
     * <p>Only for a write that names an IAM certificate. One naming no certificate, or an ACM
     * certificate, cannot leave a referrer holding a deleted IAM certificate, and taking the lock
     * for every write would make a certificate delete wait on writes that cannot affect it.
     */
    public void runWhileHeld(Collection<String> certificateArns, Runnable action) {
        if (iamService == null || !namesIamCertificate(certificateArns)) {
            action.run();
            return;
        }
        iamService.runWithServerCertificatesHeld(action);
    }

    /** Whether any of these ARNs names a certificate IAM serves, rather than ACM. */
    public boolean namesIamCertificate(Collection<String> certificateArns) {
        if (certificateArns == null) {
            return false;
        }
        for (String certificateArn : certificateArns) {
            if (certificateArn != null && !certificateArn.isBlank()
                    && AwsArnUtils.isArn(certificateArn)
                    && "iam".equals(AwsArnUtils.parse(certificateArn).service())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The certificate the ARN names, by the name it carries. Only the certificate's current ARN
     * counts: a rename keeps the former ones for the reference check that
     * {@code DeleteServerCertificate} makes, and they do not name a certificate a new listener can
     * be given.
     */
    private Optional<ServerCertificate> iamServerCertificate(AwsArnUtils.Arn arn,
                                                             String certificateArn) {
        if (!arn.resource().startsWith("server-certificate/")) {
            return Optional.empty();
        }
        String name = arn.resource().substring(arn.resource().lastIndexOf('/') + 1);
        return iamService.findServerCertificate(name)
                .filter(certificate -> certificateArn.equals(certificate.getArn()));
    }

    private boolean acmCertificateExists(String region, String certificateArn) {
        try {
            return certificateArn.equals(
                    acmService.describeCertificate(certificateArn, region).getArn());
        } catch (AwsException e) {
            LOG.debugv("ACM certificate {0} not usable in {1}: {2}", certificateArn, region,
                    e.getMessage());
            return false;
        }
    }
}
