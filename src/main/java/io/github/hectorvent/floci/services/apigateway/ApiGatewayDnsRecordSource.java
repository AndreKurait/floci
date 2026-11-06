package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.core.common.dns.DnsAnswer;
import io.github.hectorvent.floci.core.common.dns.DnsRecordSource;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/** Resolves an API Gateway alias only from its existing native domain. */
@ApplicationScoped
public class ApiGatewayDnsRecordSource implements DnsRecordSource {
    private final ApiGatewayService service;
    private final EmbeddedDnsServer dns;

    public ApiGatewayDnsRecordSource(ApiGatewayService service, EmbeddedDnsServer dns) {
        this.service = service;
        this.dns = dns;
    }

    @Override
    public Optional<DnsAnswer> resolveIpv4(String name) {
        return Optional.empty();
    }

    @Override
    public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type) {
        return service.findDomainDnsTarget(name).map(domain -> {
            if (type != 1 || !domain.hostedZoneId().equals(hostedZoneId)) {
                return DnsAnswer.refused();
            }
            return dns.getServerIp()
                    .map(address -> DnsAnswer.records(List.of(address), 60))
                    .orElseGet(DnsAnswer::noData);
        });
    }
}
