package io.github.hectorvent.floci.services.appsync;

import io.github.hectorvent.floci.services.acm.AcmCertificateReferenceProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** ACM discovers this consumer lazily, after the AppSync service has initialized. */
@ApplicationScoped
public class AppSyncAcmCertificateReferences implements AcmCertificateReferenceProvider {
    private final AppSyncService service;

    @Inject
    public AppSyncAcmCertificateReferences(AppSyncService service) {
        this.service = service;
    }

    @Override
    public List<CertificateReference> acmCertificateReferences() {
        return service.acmCertificateReferences();
    }

    /** A custom domain is not served with the ACM certificate locally, so there is nothing to refresh. */
    @Override
    public void certificateReimported(String certificateArn, String region) {
    }
}
