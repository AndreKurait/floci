package io.floci.aws.services.elbv2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.dns.DnsAnswer;
import io.floci.aws.core.common.dns.DnsClientVpcSource.ClientVpc;
import io.floci.aws.core.common.dns.DnsRecordSource;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.services.elbv2.model.Listener;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** Resolves an ALB alias to the emulator address serving its native listeners. */
@ApplicationScoped
public class ElbV2DnsRecordSource implements DnsRecordSource {
    private final ElbV2Service service;
    private final EmbeddedDnsServer dns;
    private final EmulatorConfig config;
    private final ElbV2VpcNetworkManager networks;

    public ElbV2DnsRecordSource(ElbV2Service service) {
        this(service, null, null);
    }

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
        Optional<DnsAnswer> frontend = resolve(name, type, origin);
        return frontend.isPresent() ? frontend : awsFormAnswer(name, type, this::serverAddress);
    }

    @Override
    public Optional<DnsAnswer> resolveAuthoritative(String name, int type) {
        return resolveAuthoritative(name, type, (ClientVpc) null);
    }

    @Override
    public Optional<DnsAnswer> resolveAuthoritative(String name, int type, String listenerAddress) {
        return resolveAuthoritative(name, type, listenerAddress, null);
    }

    @Override
    public Optional<DnsAnswer> resolveAuthoritative(String name, int type, String listenerAddress, ClientVpc origin) {
        Supplier<Optional<String>> address = () -> Optional.ofNullable(listenerAddress).filter(value -> !value.isBlank());
        if (networks != null && networks.frontendsEnabled()) {
            List<LoadBalancer> matches = service.findByDnsName(name);
            if (matches.size() > 1) {
                return Optional.of(DnsAnswer.refused());
            }
            if (matches.size() == 1 && "application".equals(matches.getFirst().getType())) {
                return Optional.of(frontendAnswer(matches.getFirst(), type, origin, address));
            }
        }
        return awsFormAnswer(name, type, address);
    }

    private DnsAnswer frontendAnswer(LoadBalancer target, int type, ClientVpc origin) {
        return frontendAnswer(target, type, origin, this::serverAddress);
    }

    private DnsAnswer frontendAnswer(LoadBalancer target, int type, ClientVpc origin,
                                     Supplier<Optional<String>> listenerAddress) {
        if (networks.usesSharedFrontend(target.getLoadBalancerArn())) {
            return type == 1 ? sharedAnswer(target, origin, listenerAddress) : DnsAnswer.noData();
        }
        List<String> addresses = type == 1 ? networks.frontendAddresses(target, origin) : List.of();
        return addresses.isEmpty() ? DnsAnswer.noData() : DnsAnswer.records(addresses, 60);
    }

    /**
     * A load balancer's own AWS-form DNS name ({@code floci.services.elbv2.dns-name-strategy=aws}), or the
     * name with {@code dualstack.} in front, answers with the address the {@code floci} form resolves to:
     * Floci's own. It is answered ahead of AWS endpoint spoofing, which would otherwise send it to the
     * public AWS API address, and ahead of upstream DNS, which does not know it. The {@code floci} form
     * stays with the emulator suffix, so it is not answered here. A load balancer frontend's own addresses
     * ({@link #resolve}) take precedence.
     */
    private Optional<DnsAnswer> awsFormAnswer(String name, int type, Supplier<Optional<String>> listenerAddress) {
        if (!service.awsDnsNames()) {
            return Optional.empty();
        }
        List<LoadBalancer> matches = service.findByDnsName(name);
        if (matches.isEmpty()) {
            // AWS answers NXDOMAIN for a name in its load balancer zones that no load balancer has. Answering here
            // also stops a resolver that forwards these zones to Floci from getting the name forwarded back.
            return inElbZone(name) ? Optional.of(DnsAnswer.nxDomain()) : Optional.empty();
        }
        if (matches.size() != 1) {
            return Optional.of(DnsAnswer.refused());
        }
        if (type != 1) {
            return Optional.of(DnsAnswer.noData());
        }
        return Optional.of(listenerAddress.get()
                .map(address -> DnsAnswer.records(List.of(address), DnsAnswer.DEFAULT_TTL_SECONDS))
                .orElseGet(DnsAnswer::noData));
    }

    @Override
    public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type) {
        return resolveAliasTargetInVpc(name, hostedZoneId, type, null);
    }

    @Override
    public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type, String listenerAddress) {
        return resolveAliasTarget(name, hostedZoneId, type, listenerAddress, null);
    }

    @Override
    public Optional<DnsAnswer> resolveAliasTargetInVpc(String name, String hostedZoneId, int type, ClientVpc origin) {
        return resolveOwnedAlias(name, hostedZoneId, type,
                () -> dns == null ? Optional.empty() : dns.getServerIp(), origin, false);
    }

    @Override
    public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type,
                                                 String listenerAddress, ClientVpc origin) {
        return resolveOwnedAlias(name, hostedZoneId, type,
                () -> Optional.ofNullable(listenerAddress).filter(value -> !value.isBlank()), origin, false);
    }

    @Override
    public Optional<DnsAnswer> resolveAliasTarget(String name, String hostedZoneId, int type, ClientVpc origin) {
        return resolveAliasTargetInVpc(name, hostedZoneId, type, origin);
    }

    @Override
    public Optional<DnsAnswer> resolveDeclaredPublicAliasTarget(String name, String hostedZoneId, int type,
                                                                ClientVpc origin) {
        return resolveOwnedAlias(name, hostedZoneId, type, this::serverAddress, origin, true);
    }

    @Override
    public Optional<DnsAnswer> resolveDeclaredPublicAliasTarget(String name, String hostedZoneId, int type,
                                                                String listenerAddress, ClientVpc origin) {
        return resolveOwnedAlias(name, hostedZoneId, type,
                () -> Optional.ofNullable(listenerAddress).filter(value -> !value.isBlank()), origin, true);
    }

    private Optional<DnsAnswer> resolveOwnedAlias(String name, String hostedZoneId, int type,
                                                  Supplier<Optional<String>> listenerAddress, ClientVpc origin, boolean declaredPublicPeer) {
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
                    return Optional.of(sharedAnswer(target, origin, listenerAddress));
                }
            }
            return Optional.of(frontendAnswer(target, type, origin, listenerAddress));
        }
        return Optional.of(sharedAnswer(target, origin, listenerAddress));
    }

    private Optional<String> serverAddress() {
        return dns == null ? Optional.empty() : dns.getServerIp();
    }

    private DnsAnswer sharedAnswer(LoadBalancer target, ClientVpc origin) {
        return sharedAnswer(target, origin, this::serverAddress);
    }

    private DnsAnswer sharedAnswer(LoadBalancer target, ClientVpc origin,
                                   Supplier<Optional<String>> listenerAddress) {
        boolean publicHttps = config != null && "internet-facing".equals(target.getScheme())
                && config.network().vpcRouting().enabled()
                && config.network().vpcRouting().publicAwsApi()
                && config.tls().enabled() && config.tls().awsHttpsPort() == 443;
        Optional<String> selectedAddress = listenerAddress.get();
        if (publicHttps && origin != null && dns != null && config.network().vpcRouting().publicAwsApiNativeHttps()) {
            selectedAddress = dns.publicApiAddress(origin);
        } else if (publicHttps && origin != null && dns != null
                && config.network().vpcRouting().publicAwsApiUpstreamHost().isPresent()
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

    /**
     * Whether {@code name} is in one of the zones AWS-form load balancer names live in: {@code <region>.elb.<suffix>}
     * (Application and Classic Load Balancers) or {@code elb.<region>.<suffix>} (Network and Gateway Load Balancers),
     * for a known region and its partition's DNS suffix, with at least one label in front.
     */
    static boolean inElbZone(String name) {
        if (name == null) {
            return false;
        }
        String host = name.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        for (int at = host.indexOf(".elb."); at > 0; at = host.indexOf(".elb.", at + 1)) {
            String before = host.substring(0, at);
            String after = host.substring(at + ".elb.".length());
            int dot = before.lastIndexOf('.');
            if (dot > 0) {
                String region = before.substring(dot + 1);
                if (RegionResolver.isKnownRegion(region) && after.equals(AwsRegions.dnsSuffixFor(region))) {
                    return true;
                }
            }
            int next = after.indexOf('.');
            if (next > 0) {
                String region = after.substring(0, next);
                if (RegionResolver.isKnownRegion(region)
                        && after.substring(next + 1).equals(AwsRegions.dnsSuffixFor(region))) {
                    return true;
                }
            }
        }
        return false;
    }
}
