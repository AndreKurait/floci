package io.github.hectorvent.floci.services.acmpca;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class AcmPcaJsonHandler {

    private final AcmPcaService service;

    @Inject
    public AcmPcaJsonHandler(AcmPcaService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        return Response.ok(switch (action) {
            case "CreateCertificateAuthority" -> service.create(request, region);
            case "DescribeCertificateAuthority" -> service.describe(request, region);
            case "GetCertificateAuthorityCsr" -> service.csr(request, region);
            case "IssueCertificate" -> service.issue(request, region);
            case "GetCertificate" -> service.certificate(request, region);
            case "ImportCertificateAuthorityCertificate" -> service.importCertificate(request, region);
            case "GetCertificateAuthorityCertificate" -> service.caCertificate(request, region);
            case "UpdateCertificateAuthority" -> service.update(request, region);
            case "DeleteCertificateAuthority" -> service.delete(request, region);
            case "RestoreCertificateAuthority" -> service.restore(request, region);
            case "ListCertificateAuthorities" -> service.list(request, region);
            default -> throw new AwsException("UnsupportedOperation",
                    "Operation " + action + " is not supported", 400);
        }).build();
    }
}
