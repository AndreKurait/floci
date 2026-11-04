package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.core.common.dns.DnsAnswer;
import io.github.hectorvent.floci.core.common.dns.DnsRecordSource;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/** Native Classic DNS names resolve to the owned listener before an HTTP authority is available. */
@ApplicationScoped
public class ElbClassicDnsRecordSource implements DnsRecordSource {
    private final ElbClassicService service;
    private final ElbClassicFrontendManager frontends;

    public ElbClassicDnsRecordSource(ElbClassicService service, ElbClassicFrontendManager frontends) {
        this.service = service;
        this.frontends = frontends;
    }

    @Override
    public Optional<DnsAnswer> resolveIpv4(String name) {
        if (!frontends.enabled()) {
            return Optional.empty();
        }
        List<ClassicLoadBalancer> matches = service.findByDnsName(name);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() != 1) {
            return Optional.of(DnsAnswer.refused());
        }
        List<String> addresses = frontends.addresses(matches.getFirst());
        return Optional.of(addresses.isEmpty() ? DnsAnswer.noData() : DnsAnswer.records(addresses, 60));
    }

    @Override
    public Optional<DnsAnswer> resolveAuthoritative(String name, int type) {
        return resolve(name, type);
    }
}
