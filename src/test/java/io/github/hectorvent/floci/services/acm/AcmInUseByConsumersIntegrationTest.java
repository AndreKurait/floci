package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.github.hectorvent.floci.services.acm.model.ValidationMethod;
import io.github.hectorvent.floci.services.apigateway.ApiGatewayService;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.model.DomainName;
import io.github.hectorvent.floci.services.opensearch.OpenSearchService;
import io.github.hectorvent.floci.services.opensearch.model.Domain;
import io.github.hectorvent.floci.services.opensearch.model.DomainEndpointOptions;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DescribeCertificate's {@code InUseBy} lists the resources that use a certificate, as the ACM User Guide
 * describes for the services integrated with ACM: here the custom domains of API Gateway (REST and HTTP
 * APIs), the custom endpoint of an OpenSearch Service domain and an AppSync custom domain, each named by
 * its own ARN. A certificate in use cannot be deleted ({@code ResourceInUseException}) until the resource
 * lets it go. Red without the change: these consumers were not reported, so {@code InUseBy} was empty.
 */
@QuarkusTest
class AcmInUseByConsumersIntegrationTest {

    private static final String REGION = "us-east-1";

    @Inject
    AcmService acmService;

    @Inject
    ApiGatewayService apiGatewayService;

    @Inject
    OpenSearchService openSearchService;

    @Inject
    AppSyncService appSyncService;

    @Test
    void aRestApiCustomDomainIsInItsCertificatesInUseBy() {
        String certificateArn = certificate();
        String domainName = name() + ".rest.example.com";
        apiGatewayService.createDomainName(REGION, Map.of("domainName", domainName,
                "regionalCertificateArn", certificateArn));

        assertInUseUntilReleased(certificateArn, ApiGatewayService.domainNameArn(REGION, domainName),
                () -> apiGatewayService.deleteDomainName(REGION, domainName));
    }

    @Test
    void anEdgeOptimizedCustomDomainIsInItsCertificatesInUseBy() {
        String certificateArn = certificate();
        String domainName = name() + ".edge.example.com";
        apiGatewayService.createDomainName(REGION, Map.of("domainName", domainName,
                "certificateArn", certificateArn));

        assertInUseUntilReleased(certificateArn, ApiGatewayService.domainNameArn(REGION, domainName),
                () -> apiGatewayService.deleteDomainName(REGION, domainName));
    }

    @Test
    void anHttpApiCustomDomainIsInItsCertificatesInUseBy() {
        String certificateArn = certificate();
        String domainName = name() + ".http.example.com";
        apiGatewayService.createV2DomainName(REGION, Map.of("domainName", domainName,
                "domainNameConfigurations", List.of(Map.of("certificateArn", certificateArn,
                        "endpointType", "REGIONAL"))));

        assertInUseUntilReleased(certificateArn, ApiGatewayService.domainNameArn(REGION, domainName),
                () -> apiGatewayService.deleteDomainName(REGION, domainName));
    }

    @Test
    void anOpenSearchCustomEndpointIsInItsCertificatesInUseBy() {
        String certificateArn = certificate();
        String domainName = "inuse-" + name().substring(0, 8);
        DomainEndpointOptions endpoint = new DomainEndpointOptions();
        endpoint.setEnforceHttps(true);
        endpoint.setCustomEndpointEnabled(true);
        endpoint.setCustomEndpoint(domainName + ".search.example.com");
        endpoint.setCustomEndpointCertificateArn(certificateArn);
        Domain domain = openSearchService.createDomain(domainName, "OpenSearch_2.11", null, null, null, null,
                new OpenSearchService.DomainOptions(null, null, null, null, endpoint), REGION);

        assertInUseUntilReleased(certificateArn, domain.getArn(),
                () -> openSearchService.deleteDomain(domainName, REGION));
    }

    @Test
    void anAppSyncCustomDomainIsInItsCertificatesInUseBy() {
        String certificateArn = certificate();
        String domainName = name() + ".graphql.example.com";
        DomainName domain = appSyncService.createDomainName(Map.of("domainName", domainName,
                "certificateArn", certificateArn));

        assertInUseUntilReleased(certificateArn, domain.getDomainNameArn(),
                () -> appSyncService.deleteDomainName(domainName));
    }

    private void assertInUseUntilReleased(String certificateArn, String resourceArn, Runnable release) {
        assertEquals(List.of(resourceArn), acmService.certificateInUseBy(certificateArn, REGION));
        AwsException refused = assertThrows(AwsException.class,
                () -> acmService.deleteCertificate(certificateArn, REGION));
        assertEquals("ResourceInUseException", refused.getErrorCode());

        release.run();

        assertEquals(List.of(), acmService.certificateInUseBy(certificateArn, REGION));
        acmService.deleteCertificate(certificateArn, REGION);
    }

    private String certificate() {
        return acmService.requestCertificate(name() + ".example.com", List.of(), ValidationMethod.DNS, null,
                KeyAlgorithm.RSA_2048, null, null, Map.of(), REGION).getArn();
    }

    private static String name() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
