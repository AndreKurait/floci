package io.floci.aws.services.apigateway;

import io.floci.aws.services.acm.AcmCertificateReferenceProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/** ACM discovers this consumer lazily, after the API Gateway service has initialized. */
@ApplicationScoped
public class ApiGatewayAcmCertificateReferences implements AcmCertificateReferenceProvider {
    private final ApiGatewayService service;

    @Inject
    public ApiGatewayAcmCertificateReferences(ApiGatewayService service) {
        this.service = service;
    }

    @Override
    public List<CertificateReference> acmCertificateReferences() {
        return service.acmCertificateReferences();
    }

    /** A custom domain is served with Floci's own TLS certificate for its host, so there is nothing to refresh. */
    @Override
    public void certificateReimported(String certificateArn, String region) {
    }
}
