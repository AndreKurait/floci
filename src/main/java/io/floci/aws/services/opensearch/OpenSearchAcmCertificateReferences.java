package io.floci.aws.services.opensearch;

import io.floci.aws.services.acm.AcmCertificateReferenceProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** ACM discovers this consumer lazily, after the OpenSearch service has initialized. */
@ApplicationScoped
public class OpenSearchAcmCertificateReferences implements AcmCertificateReferenceProvider {
    private final OpenSearchService service;

    @Inject
    public OpenSearchAcmCertificateReferences(OpenSearchService service) {
        this.service = service;
    }

    @Override
    public List<CertificateReference> acmCertificateReferences() {
        return service.acmCertificateReferences();
    }

    /** A custom endpoint is not served with the ACM certificate locally, so there is nothing to refresh. */
    @Override
    public void certificateReimported(String certificateArn, String region) {
    }
}
