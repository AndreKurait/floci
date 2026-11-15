package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
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
    private final ElbV2VpcNetworkManager networks;

    public ElbV2DnsRecordSource(ElbV2Service service, EmbeddedDnsServer dns) {
        this(service, dns, null);
    }

    public ElbV2DnsRecordSource(ElbV2Service service, EmbeddedDnsServer dns, EmulatorConfig config) {
        this(service, dns, config, null);
    }

    @Inject
    public ElbV2DnsRecordSource(ElbV2Service service, EmbeddedDnsServer dns, EmulatorConfig config,
                              ElbV2VpcNetworkManager networks) {
        this.service = service;
        this.dns = dns;
        this.config = config;
        this.networks = networks;
    }

    @Override
    public Optional<DnsAnswer> resolveIpv4(String name) {
        return resolve(name, 1, null);
    }

    @Override
    public Optional<DnsAnswer> resolve(String name, int type, ClientVpc origin) {
        if (networks == null || !networks.frontendsEnabled()) {
            return Optional.empty();
        }
        List<LoadBalancer> matches = service.findByDnsName(name);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() != 1) {
            return Optional.of(DnsAnswer.refused());
        }
        LoadBalancer target = matches.getFirst();
        return "application".equals(target.getType())
                ? Optional.of(frontendAnswer(target, type, origin)) : Optional.empty();
    }

    @Override
    public Optional<DnsAnswer> resolveAuthoritative(String name, int type, ClientVpc origin) {
        return resolve(name, type, origin);
    }

    private DnsAnswer frontendAnswer(LoadBalancer target, int type, ClientVpc origin) {
        if (networks.usesSharedFrontend(target.getLoadBalancerArn())) {
            return type == 1 ? sharedAnswer(target, origin) : DnsAnswer.noData();
        }
        List<String> addresses = type == 1 ? networks.frontendAddresses(target, origin) : List.of();
        return addresses.isEmpty() ? DnsAnswer.noData() : DnsAnswer.records(addresses, 60);
    }

    @Override
    public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type) {
        return resolveAliasTarget(name, hostedZoneId, type, null);
    }

    @Override
    public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type, ClientVpc origin) {
        return resolveAliasTarget(name, hostedZoneId, type, origin, false);
    }

    @Override
    public Optional<DnsAnswer> resolveDeclaredPublicAliasTarget(String name, String hostedZoneId, int type,
                                                                ClientVpc origin) {
        return resolveAliasTarget(name, hostedZoneId, type, origin, true);
    }

    private Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type, ClientVpc origin,
                                                   boolean declaredPublicPeer) {
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
        if (networks != null && networks.frontendsEnabled()) {
            if (declaredPublicPeer) {
                ClientVpc owner = new ClientVpc(AwsArnUtils.parse(target.getLoadBalancerArn()).accountId(),
                        target.getRegion(), target.getVpcId());
                if (!networks.frontendAddresses(target, owner).isEmpty()) {
                    return Optional.of(sharedAnswer(target, origin));
                }
            }
            return Optional.of(frontendAnswer(target, type, origin));
        }
        return Optional.of(sharedAnswer(target, origin));
    }

    private DnsAnswer sharedAnswer(LoadBalancer target, ClientVpc origin) {
        boolean publicHttps = config != null && "internet-facing".equals(target.getScheme())
                && config.network().vpcRouting().enabled()
                && config.network().vpcRouting().publicAwsApi()
                && config.tls().enabled() && config.tls().awsHttpsPort() == 443;
        Optional<String> selectedAddress = dns.getServerIp();
        if (publicHttps && config.network().vpcRouting().publicAwsApiNativeHttps()) {
            selectedAddress = dns.publicApiAddress(origin);
        } else if (publicHttps && config.network().vpcRouting().publicAwsApiUpstreamHost().isPresent()
                && servesNativeHttpPorts(target)) {
            selectedAddress = dns.publicAlbHttpsAddress(origin);
        }
        return selectedAddress
                .map(address -> DnsAnswer.records(List.of(address), 60))
                .orElseGet(DnsAnswer::noData);
    }

    private boolean servesNativeHttpPorts(LoadBalancer loadBalancer) {
        List<Listener> listeners = service.findListenersForLoadBalancer(loadBalancer.getLoadBalancerArn());
        if (listeners.isEmpty()) {
            return false;
        }
        for (Listener listener : listeners) {
            boolean http = "HTTP".equals(listener.getProtocol()) && Integer.valueOf(80).equals(listener.getPort());
            boolean https = "HTTPS".equals(listener.getProtocol()) && Integer.valueOf(443).equals(listener.getPort());
            if (!http && !https) {
                return false;
            }
        }
        return true;
    }
}
