package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.dns.DnsAnswer;
import io.github.hectorvent.floci.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.github.hectorvent.floci.core.common.dns.DnsRecordSource;
import io.github.hectorvent.floci.core.common.dns.EmbeddedDnsServer;
import io.github.hectorvent.floci.services.elbv2.model.Listener;
import io.github.hectorvent.floci.services.elbv2.model.LoadBalancer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

/** Resolves an ALB alias to the emulator address serving its native listeners. */
@ApplicationScoped
public class ElbV2DnsRecordSource implements DnsRecordSource {
    private final ElbV2Service service;
    private final EmbeddedDnsServer dns;
    private final EmulatorConfig config;

    public ElbV2DnsRecordSource(ElbV2Service service, EmbeddedDnsServer dns) {
        this(service, dns, null);
    }

    @Inject
    public ElbV2DnsRecordSource(ElbV2Service service, EmbeddedDnsServer dns, EmulatorConfig config) {
        this.service = service;
        this.dns = dns;
        this.config = config;
    }

    @Override
    public Optional<DnsAnswer> resolveIpv4(String name) {
        return Optional.empty();
    }

    @Override
    public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type) {
        return resolveAliasTarget(name, hostedZoneId, type, null);
    }

    @Override
    public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type, ClientVpc origin) {
        List<LoadBalancer> matches = service.findByDnsName(name);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() != 1) {
            return Optional.of(DnsAnswer.refused());
        }
        LoadBalancer target = matches.getFirst();
        if (!"application".equals(target.getType()) || hostedZoneId == null
                || !hostedZoneId.equals(target.getCanonicalHostedZoneId()) || (type != 1 && type != 28)) {
            return Optional.of(DnsAnswer.refused());
        }
        if (type == 28) {
            return Optional.of(DnsAnswer.noData());
        }
        boolean publicHttps = config != null && "internet-facing".equals(target.getScheme())
                && config.network().vpcRouting().enabled()
                && config.network().vpcRouting().publicAwsApi()
                && config.tls().enabled() && config.tls().awsHttpsPort() == 443;
        Optional<String> selectedAddress = dns.getServerIp();
        if (publicHttps && config.network().vpcRouting().publicAwsApiNativeHttps()) {
            selectedAddress = dns.publicApiAddress(origin);
        } else if (publicHttps && config.network().vpcRouting().publicAwsApiUpstreamHost().isPresent()
                && servesOnlyHttps443(target)) {
            selectedAddress = dns.publicAlbHttpsAddress(origin);
        }
        return Optional.of(selectedAddress
                .map(address -> DnsAnswer.records(List.of(address), 60))
                .orElseGet(DnsAnswer::noData));
    }

    private boolean servesOnlyHttps443(LoadBalancer loadBalancer) {
        List<Listener> listeners = service.findListenersForLoadBalancer(loadBalancer.getLoadBalancerArn());
        if (listeners.size() != 1) {
            return false;
        }
        Listener listener = listeners.getFirst();
        return "HTTPS".equals(listener.getProtocol()) && Integer.valueOf(443).equals(listener.getPort());
    }
}
