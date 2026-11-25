package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegionFacts;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.ec2.model.VpcEndpoint;
import io.floci.aws.services.ec2.model.VpcEndpointServiceConfiguration;
import io.floci.aws.services.ec2.model.VpcEndpointServicePermission;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.AvailabilityZone;
import io.floci.aws.services.elbv2.model.LoadBalancer;
import io.floci.aws.services.iam.IamService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * VPC endpoint services, the provider side of AWS PrivateLink: the endpoint service configurations
 * an account offers through its Network or Gateway Load Balancers, the principals allowed to connect
 * to each, and the connection requests that consumers' endpoints make to them.
 *
 * <p>A connection is the consumer's own {@link VpcEndpoint}, read across accounts: its state is the
 * connection's state, so the consumer's DescribeVpcEndpoints and the provider's
 * DescribeVpcEndpointConnections cannot disagree. Only the control plane is modelled; traffic sent
 * to an endpoint does not reach the service's load balancer.
 */
@ApplicationScoped
public class Ec2PrivateLinkService {

    public static final String PENDING_ACCEPTANCE = "pendingAcceptance";
    public static final String AVAILABLE = "available";
    public static final String REJECTED = "rejected";
    private static final String INTERFACE = "Interface";
    private static final String GATEWAY_LOAD_BALANCER = "GatewayLoadBalancer";
    private static final Set<String> IP_ADDRESS_TYPES = Set.of("ipv4", "ipv6");
    private static final Set<String> CONFIGURATION_FILTERS = Set.of(
            "service-name", "service-id", "service-state", "supported-ip-address-types", "tag-key");
    private static final Set<String> PERMISSION_FILTERS = Set.of("principal", "principal-type");
    private static final Set<String> CONNECTION_FILTERS = Set.of("ip-address-type", "service-id",
            "vpc-endpoint-owner", "vpc-endpoint-region", "vpc-endpoint-state", "vpc-endpoint-id");

    private final AccountAwareStorageBackend<VpcEndpointServiceConfiguration> services;
    private final Ec2Service ec2Service;
    private final RegionResolver regionResolver;
    private final IamService iamService;
    private final Instance<ElbV2Service> elbV2Services;
    private final Instance<RequestContext> requestContexts;
    private final Object lock = new Object();

    @Inject
    public Ec2PrivateLinkService(StorageFactory storageFactory, Ec2Service ec2Service, RegionResolver regionResolver,
                                 IamService iamService, Instance<ElbV2Service> elbV2Services,
                                 Instance<RequestContext> requestContexts) {
        this.services = storageFactory.create("ec2", "ec2-vpc-endpoint-services.json",
                new TypeReference<Map<String, VpcEndpointServiceConfiguration>>() {});
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.iamService = iamService;
        this.elbV2Services = elbV2Services;
        this.requestContexts = requestContexts;
    }

    /** What CreateVpcEndpointServiceConfiguration asks for; empty lists where a member is absent. */
    public record ServiceSpec(Boolean acceptanceRequired, List<String> networkLoadBalancerArns,
                              List<String> gatewayLoadBalancerArns, String privateDnsName,
                              List<String> supportedIpAddressTypes, List<String> supportedRegions,
                              List<Tag> tags, String clientToken) {
    }

    /** What ModifyVpcEndpointServiceConfiguration asks for; empty lists where a member is absent. */
    public record ServiceChange(Boolean acceptanceRequired,
                                List<String> addNetworkLoadBalancerArns, List<String> removeNetworkLoadBalancerArns,
                                List<String> addGatewayLoadBalancerArns, List<String> removeGatewayLoadBalancerArns,
                                String privateDnsName, boolean removePrivateDnsName,
                                List<String> addSupportedIpAddressTypes, List<String> removeSupportedIpAddressTypes,
                                List<String> addSupportedRegions, List<String> removeSupportedRegions) {
    }

    /** One {@code UnsuccessfulItem} of a bulk request. */
    public record Unsuccessful(String resourceId, String code, String message) {
    }

    /** A consumer's endpoint connected to one of the caller's services, and the account that owns it. */
    public record Connection(VpcEndpointServiceConfiguration service, String ownerId, VpcEndpoint endpoint) {
    }

    // ─── Configurations ───────────────────────────────────────────────────────

    public VpcEndpointServiceConfiguration create(String region, ServiceSpec spec) {
        String account = regionResolver.getAccountId();
        synchronized (lock) {
            if (spec.clientToken() != null) {
                Optional<VpcEndpointServiceConfiguration> earlier = owned(account, region).stream()
                        .filter(service -> spec.clientToken().equals(service.getClientToken())).findFirst();
                if (earlier.isPresent()) {
                    return earlier.get();
                }
            }
            List<String> networkArns = distinct(spec.networkLoadBalancerArns());
            List<String> gatewayArns = distinct(spec.gatewayLoadBalancerArns());
            if (networkArns.isEmpty() == gatewayArns.isEmpty()) {
                throw new AwsException("InvalidParameterCombination",
                        "Specify either NetworkLoadBalancerArn or GatewayLoadBalancerArn, but not both.", 400);
            }
            for (String arn : networkArns) {
                requireLoadBalancer(region, arn, true);
            }
            for (String arn : gatewayArns) {
                requireLoadBalancer(region, arn, false);
            }
            List<String> ipTypes = spec.supportedIpAddressTypes().isEmpty()
                    ? List.of("ipv4") : distinct(spec.supportedIpAddressTypes());
            validateIpAddressTypes(ipTypes);
            List<String> regions = new ArrayList<>(List.of(region));
            regions.addAll(spec.supportedRegions());

            VpcEndpointServiceConfiguration service = new VpcEndpointServiceConfiguration();
            service.setServiceId(newServiceId());
            service.setServiceName(serviceName(region, service.getServiceId()));
            service.setServiceType(networkArns.isEmpty() ? GATEWAY_LOAD_BALANCER : INTERFACE);
            service.setRegion(region);
            service.setOwnerId(account);
            service.setAcceptanceRequired(Boolean.TRUE.equals(spec.acceptanceRequired()));
            service.setNetworkLoadBalancerArns(networkArns);
            service.setGatewayLoadBalancerArns(gatewayArns);
            service.setAvailabilityZones(availabilityZones(region, networkArns, gatewayArns));
            service.setSupportedIpAddressTypes(new ArrayList<>(ipTypes));
            service.setSupportedRegions(distinct(regions));
            setPrivateDnsName(service, spec.privateDnsName());
            service.setClientToken(spec.clientToken());
            if (!spec.tags().isEmpty()) {
                ec2Service.createTags(region, List.of(service.getServiceId()), spec.tags());
            }
            services.putForAccount(account, key(region, service.getServiceId()), service);
            return service;
        }
    }

    public List<VpcEndpointServiceConfiguration> describe(String region, List<String> serviceIds,
                                                          List<Map.Entry<String, List<String>>> filters) {
        Ec2Service.requireSupportedFilters(filters.stream().map(Map.Entry::getKey).toList(), CONFIGURATION_FILTERS,
                "InvalidFilter");
        List<VpcEndpointServiceConfiguration> owned = owned(regionResolver.getAccountId(), region);
        for (String serviceId : serviceIds) {
            if (owned.stream().noneMatch(service -> service.getServiceId().equals(serviceId))) {
                throw serviceNotFound(serviceId);
            }
        }
        return owned.stream()
                .filter(service -> serviceIds.isEmpty() || serviceIds.contains(service.getServiceId()))
                .filter(service -> filters.stream().allMatch(filter -> matches(service, filter)))
                .toList();
    }

    /** One of the caller's services in {@code region}, or {@code InvalidVpcEndpointServiceId.NotFound}. */
    public VpcEndpointServiceConfiguration describeOne(String region, String serviceId) {
        return requireOwned(region, serviceId);
    }

    public void modify(String region, String serviceId, ServiceChange change) {
        requireParameter(serviceId, "ServiceId");
        synchronized (lock) {
            VpcEndpointServiceConfiguration service = requireOwned(region, serviceId);
            if (change.privateDnsName() != null && change.removePrivateDnsName()) {
                throw new AwsException("InvalidParameterCombination",
                        "PrivateDnsName and RemovePrivateDnsName cannot be used together.", 400);
            }
            List<String> networkArns = applyChanges(service.getNetworkLoadBalancerArns(),
                    change.addNetworkLoadBalancerArns(), change.removeNetworkLoadBalancerArns());
            List<String> gatewayArns = applyChanges(service.getGatewayLoadBalancerArns(),
                    change.addGatewayLoadBalancerArns(), change.removeGatewayLoadBalancerArns());
            boolean loadBalancersChange = !networkArns.equals(service.getNetworkLoadBalancerArns())
                    || !gatewayArns.equals(service.getGatewayLoadBalancerArns());
            if (loadBalancersChange) {
                if (hasActiveConnections(service)) {
                    throw new AwsException("ExistingVpcEndpointConnections",
                            "The load balancers of service " + serviceId
                                    + " cannot change while endpoints are connected to it.", 400);
                }
                boolean network = INTERFACE.equals(service.getServiceType());
                if ((network ? networkArns : gatewayArns).isEmpty() || !(network ? gatewayArns : networkArns).isEmpty()) {
                    throw new AwsException("InvalidParameterCombination", "Service " + serviceId
                            + " needs at least one load balancer, and only of the type it was created with.", 400);
                }
                for (String arn : change.addNetworkLoadBalancerArns()) {
                    requireLoadBalancer(region, arn, true);
                }
                for (String arn : change.addGatewayLoadBalancerArns()) {
                    requireLoadBalancer(region, arn, false);
                }
            }
            List<String> ipTypes = applyChanges(service.getSupportedIpAddressTypes(),
                    change.addSupportedIpAddressTypes(), change.removeSupportedIpAddressTypes());
            validateIpAddressTypes(ipTypes);
            if (ipTypes.isEmpty()) {
                throw new AwsException("InvalidParameterValue",
                        "Service " + serviceId + " must support at least one IP address type.", 400);
            }
            if (change.removeSupportedRegions().contains(region)) {
                throw new AwsException("InvalidParameterValue",
                        "The Region that hosts service " + serviceId + " cannot be removed from its supported Regions.",
                        400);
            }
            List<String> regions = applyChanges(service.getSupportedRegions(),
                    change.addSupportedRegions(), change.removeSupportedRegions());

            if (loadBalancersChange) {
                service.setNetworkLoadBalancerArns(networkArns);
                service.setGatewayLoadBalancerArns(gatewayArns);
                service.setAvailabilityZones(availabilityZones(region, networkArns, gatewayArns));
            }
            if (change.acceptanceRequired() != null) {
                service.setAcceptanceRequired(change.acceptanceRequired());
            }
            if (change.removePrivateDnsName()) {
                setPrivateDnsName(service, null);
            } else if (change.privateDnsName() != null && !change.privateDnsName().equals(service.getPrivateDnsName())) {
                setPrivateDnsName(service, change.privateDnsName());
            }
            service.setSupportedIpAddressTypes(ipTypes);
            service.setSupportedRegions(regions);
            services.putForAccount(service.getOwnerId(), key(region, serviceId), service);
        }
    }

    /**
     * Brings one of the caller's services to {@code desired}, which names every member: CloudFormation's
     * update of {@code AWS::EC2::VPCEndpointService}. The members it leaves out return to their defaults.
     */
    public void update(String region, String serviceId, ServiceSpec desired) {
        VpcEndpointServiceConfiguration current = requireOwned(region, serviceId);
        List<String> ipTypes = desired.supportedIpAddressTypes().isEmpty()
                ? List.of("ipv4") : desired.supportedIpAddressTypes();
        List<String> regions = new ArrayList<>(List.of(region));
        regions.addAll(desired.supportedRegions());
        modify(region, serviceId, new ServiceChange(Boolean.TRUE.equals(desired.acceptanceRequired()),
                missing(desired.networkLoadBalancerArns(), current.getNetworkLoadBalancerArns()),
                missing(current.getNetworkLoadBalancerArns(), desired.networkLoadBalancerArns()),
                missing(desired.gatewayLoadBalancerArns(), current.getGatewayLoadBalancerArns()),
                missing(current.getGatewayLoadBalancerArns(), desired.gatewayLoadBalancerArns()),
                desired.privateDnsName(), desired.privateDnsName() == null && current.getPrivateDnsName() != null,
                missing(ipTypes, current.getSupportedIpAddressTypes()),
                missing(current.getSupportedIpAddressTypes(), ipTypes),
                missing(regions, current.getSupportedRegions()), missing(current.getSupportedRegions(), regions)));
    }

    public List<Unsuccessful> delete(String region, List<String> serviceIds) {
        if (serviceIds.isEmpty()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter ServiceId", 400);
        }
        String account = regionResolver.getAccountId();
        List<Unsuccessful> unsuccessful = new ArrayList<>();
        synchronized (lock) {
            for (String serviceId : serviceIds) {
                Optional<VpcEndpointServiceConfiguration> service = services.getForAccount(account, key(region, serviceId));
                if (service.isEmpty()) {
                    unsuccessful.add(new Unsuccessful(serviceId, "InvalidVpcEndpointServiceId.NotFound",
                            "The VPC endpoint service ID '" + serviceId + "' does not exist"));
                } else if (hasActiveConnections(service.get())) {
                    unsuccessful.add(new Unsuccessful(serviceId, "ExistingVpcEndpointConnections",
                            "Service " + serviceId + " has endpoints in the available or pendingAcceptance state."));
                } else {
                    List<Tag> tags = ec2Service.resourceTags(region, serviceId);
                    if (!tags.isEmpty()) {
                        ec2Service.deleteTags(region, List.of(serviceId), tags);
                    }
                    services.deleteForAccount(account, key(region, serviceId));
                }
            }
        }
        return unsuccessful;
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    public List<VpcEndpointServicePermission> describePermissions(String region, String serviceId,
                                                                  List<Map.Entry<String, List<String>>> filters) {
        requireParameter(serviceId, "ServiceId");
        Ec2Service.requireSupportedFilters(filters.stream().map(Map.Entry::getKey).toList(), PERMISSION_FILTERS,
                "InvalidFilter");
        return requireOwned(region, serviceId).getAllowedPrincipals().stream()
                .filter(permission -> filters.stream().allMatch(filter -> anyMatches(filter.getValue(),
                        "principal".equals(filter.getKey()) ? permission.getPrincipal() : permission.getPrincipalType())))
                .toList();
    }

    /** Grants {@code add} and revokes {@code remove}; returns the principals granted, with their types. */
    public List<VpcEndpointServicePermission> modifyPermissions(String region, String serviceId,
                                                                List<String> add, List<String> remove) {
        requireParameter(serviceId, "ServiceId");
        List<VpcEndpointServicePermission> added = distinct(add).stream()
                .map(principal -> new VpcEndpointServicePermission(principal, principalType(principal))).toList();
        synchronized (lock) {
            VpcEndpointServiceConfiguration service = requireOwned(region, serviceId);
            List<VpcEndpointServicePermission> allowed = new ArrayList<>(service.getAllowedPrincipals());
            allowed.removeIf(permission -> remove.contains(permission.getPrincipal()));
            for (VpcEndpointServicePermission permission : added) {
                if (allowed.stream().noneMatch(existing -> existing.getPrincipal().equals(permission.getPrincipal()))) {
                    allowed.add(permission);
                }
            }
            service.setAllowedPrincipals(allowed);
            services.putForAccount(service.getOwnerId(), key(region, serviceId), service);
        }
        return added;
    }

    /** Makes {@code principals} the whole set: CloudFormation's {@code AllowedPrincipals}. */
    public void replacePermissions(String region, String serviceId, List<String> principals) {
        synchronized (lock) {
            List<String> current = requireOwned(region, serviceId).getAllowedPrincipals().stream()
                    .map(VpcEndpointServicePermission::getPrincipal).toList();
            modifyPermissions(region, serviceId, principals, missing(current, principals));
        }
    }

    /**
     * The principal type DescribeVpcEndpointServicePermissions reports for {@code principal}. AWS
     * accounts, IAM roles and IAM users are given by ARN, and every principal by {@code *}; ARNs with
     * a path are not supported.
     */
    static String principalType(String principal) {
        if ("*".equals(principal)) {
            return "All";
        }
        AwsArnUtils.Arn arn = parsedArn(principal);
        if (arn != null && "iam".equals(arn.service()) && arn.accountId().matches("[0-9]{12}")) {
            String resource = arn.resource();
            if ("root".equals(resource)) {
                return "Account";
            }
            for (String kind : List.of("role", "user")) {
                if (resource.startsWith(kind + "/")) {
                    String name = resource.substring(kind.length() + 1);
                    if (name.isEmpty() || name.contains("/")) {
                        throw new AwsException("InvalidParameterValue",
                                "Principal ARNs with path components aren't supported: " + principal, 400);
                    }
                    return "role".equals(kind) ? "Role" : "User";
                }
            }
        }
        throw new AwsException("UnknownPrincipalType.Unsupported",
                "The principal type of '" + principal + "' is not supported.", 400);
    }

    // ─── Connections ──────────────────────────────────────────────────────────

    public List<Connection> describeConnections(String region, List<Map.Entry<String, List<String>>> filters) {
        Ec2Service.requireSupportedFilters(filters.stream().map(Map.Entry::getKey).toList(), CONNECTION_FILTERS,
                "InvalidFilter");
        Map<String, VpcEndpointServiceConfiguration> byName = owned(regionResolver.getAccountId(), region).stream()
                .collect(Collectors.toMap(VpcEndpointServiceConfiguration::getServiceName, Function.identity()));
        if (byName.isEmpty()) {
            return List.of();
        }
        return ec2Service.endpointsOfServices(region, byName.keySet()).stream()
                .map(entry -> new Connection(byName.get(entry.value().getServiceName()), entry.account(), entry.value()))
                .filter(connection -> filters.stream().allMatch(filter -> matches(connection, filter)))
                .sorted(Comparator.comparing(connection -> connection.endpoint().getVpcEndpointId()))
                .toList();
    }

    public List<Unsuccessful> acceptConnections(String region, String serviceId, List<String> endpointIds) {
        return changeConnections(region, serviceId, endpointIds, Set.of(PENDING_ACCEPTANCE, AVAILABLE), AVAILABLE);
    }

    public List<Unsuccessful> rejectConnections(String region, String serviceId, List<String> endpointIds) {
        return changeConnections(region, serviceId, endpointIds,
                Set.of(PENDING_ACCEPTANCE, AVAILABLE, REJECTED), REJECTED);
    }

    private List<Unsuccessful> changeConnections(String region, String serviceId, List<String> endpointIds,
                                                 Set<String> from, String to) {
        requireParameter(serviceId, "ServiceId");
        if (endpointIds.isEmpty()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter VpcEndpointId", 400);
        }
        List<Unsuccessful> unsuccessful = new ArrayList<>();
        synchronized (lock) {
            VpcEndpointServiceConfiguration service = requireOwned(region, serviceId);
            Map<String, String> owners = new HashMap<>();
            ec2Service.endpointsOfServices(region, Set.of(service.getServiceName()))
                    .forEach(entry -> owners.put(entry.value().getVpcEndpointId(), entry.account()));
            for (String endpointId : endpointIds) {
                String owner = owners.get(endpointId);
                Optional<VpcEndpoint> endpoint = owner == null ? Optional.empty()
                        : ec2Service.changeEndpointConnectionState(owner, region, endpointId,
                                service.getServiceName(), from, to);
                if (endpoint.isEmpty()) {
                    unsuccessful.add(new Unsuccessful(endpointId, "InvalidVpcEndpoint.NotFound",
                            "The VPC endpoint '" + endpointId + "' is not connected to service " + serviceId));
                } else if (!to.equals(endpoint.get().getState())) {
                    unsuccessful.add(new Unsuccessful(endpointId, "InvalidState",
                            "The VPC endpoint '" + endpointId + "' is " + endpoint.get().getState() + "."));
                }
            }
        }
        return unsuccessful;
    }

    // ─── Consumers ────────────────────────────────────────────────────────────

    /**
     * The state a new endpoint to {@code serviceName} starts in: {@code pendingAcceptance} when the
     * service requires acceptance of requests from other accounts, {@code available} otherwise. Empty
     * when Floci holds no endpoint service of that name, so an endpoint to an AWS service, or to one
     * outside the emulator, is created as before. A caller the service does not allow cannot discover
     * it, so its name is not valid for that caller.
     */
    public Optional<String> admitEndpoint(String region, String serviceName, String endpointType) {
        Optional<VpcEndpointServiceConfiguration> held = findByName(region, serviceName);
        if (held.isEmpty()) {
            return Optional.empty();
        }
        VpcEndpointServiceConfiguration service = held.get();
        String caller = regionResolver.getAccountId();
        if (!visibleTo(service, caller)) {
            throw invalidServiceName(serviceName);
        }
        if (!service.getServiceType().equalsIgnoreCase(endpointType)) {
            throw new AwsException("InvalidVpcEndpointType", "Service " + serviceName + " takes endpoints of type "
                    + service.getServiceType() + ", not " + endpointType + ".", 400);
        }
        return Optional.of(caller.equals(service.getOwnerId()) || !service.isAcceptanceRequired()
                ? AVAILABLE : PENDING_ACCEPTANCE);
    }

    /** The endpoint services in {@code region} the caller owns or is allowed to connect to, by name. */
    public List<VpcEndpointServiceConfiguration> visibleServices(String region) {
        String caller = regionResolver.getAccountId();
        return allInRegion(region).stream().filter(service -> visibleTo(service, caller))
                .sorted(Comparator.comparing(VpcEndpointServiceConfiguration::getServiceName)).toList();
    }

    /**
     * The endpoint service named {@code serviceName} when the caller may see it, empty when Floci holds
     * none of that name, and {@code InvalidServiceName} when it holds one the caller may not see.
     */
    public Optional<VpcEndpointServiceConfiguration> visibleService(String region, String serviceName) {
        Optional<VpcEndpointServiceConfiguration> held = findByName(region, serviceName);
        if (held.isPresent() && !visibleTo(held.get(), regionResolver.getAccountId())) {
            throw invalidServiceName(serviceName);
        }
        return held;
    }

    public boolean ownedByCaller(VpcEndpointServiceConfiguration service) {
        return regionResolver.getAccountId().equals(service.getOwnerId());
    }

    /** {@code <service id>.<region>.vpce.<DNS suffix>}, the endpoint-specific DNS name of the service. */
    public static String baseEndpointDnsName(VpcEndpointServiceConfiguration service) {
        return service.getServiceId() + "." + service.getRegion() + ".vpce." + AwsRegions.dnsSuffixFor(service.getRegion());
    }

    public static String privateDnsVerificationState(VpcEndpointServiceConfiguration service) {
        return service.getPrivateDnsName() == null ? null : "pendingVerification";
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static String serviceName(String region, String serviceId) {
        String prefix = AwsRegionFacts.vpcEndpointServiceNamePrefix(AwsRegions.partitionFor(region)).orElseThrow();
        return prefix + "." + region + "." + serviceId;
    }

    private String newServiceId() {
        Set<String> taken = services.scanAllAccounts().stream()
                .map(VpcEndpointServiceConfiguration::getServiceId).collect(Collectors.toSet());
        String id;
        do {
            id = "vpce-svc-" + hex(17);
        } while (taken.contains(id));
        return id;
    }

    private static String hex(int length) {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, length);
    }

    private static void setPrivateDnsName(VpcEndpointServiceConfiguration service, String privateDnsName) {
        service.setPrivateDnsName(privateDnsName);
        service.setPrivateDnsVerificationName(privateDnsName == null ? null : "_" + hex(20));
        service.setPrivateDnsVerificationValue(privateDnsName == null ? null : "vpce:" + hex(20));
    }

    /**
     * Checks that {@code arn} names a load balancer of the right type in this account and Region.
     * The Network Load Balancer errors are EC2's documented ones; the Gateway Load Balancer ones have
     * no documented code and use {@code InvalidParameterValue}.
     */
    private void requireLoadBalancer(String region, String arn, boolean network) {
        String malformed = network ? "InvalidNetworkLoadBalancerArn.Malformed" : "InvalidParameterValue";
        String notFound = network ? "InvalidNetworkLoadBalancerArn.NotFound" : "InvalidParameterValue";
        String kind = network ? "Network" : "Gateway";
        AwsArnUtils.Arn parsed = parsedArn(arn);
        if (parsed == null || !"elasticloadbalancing".equals(parsed.service())
                || !parsed.resource().startsWith(network ? "loadbalancer/net/" : "loadbalancer/gwy/")) {
            throw new AwsException(malformed, "The " + kind + " Load Balancer ARN '" + arn + "' is malformed.", 400);
        }
        if (elbV2Services != null && !elbV2Services.isUnsatisfied() && loadBalancer(region, arn).isEmpty()) {
            throw new AwsException(notFound, "The " + kind + " Load Balancer '" + arn + "' does not exist.", 400);
        }
    }

    private Optional<LoadBalancer> loadBalancer(String region, String arn) {
        try {
            return elbV2Services.get().describeLoadBalancers(region, List.of(arn), null, null, null)
                    .stream().findFirst();
        } catch (AwsException e) {
            return Optional.empty();
        }
    }

    /** The zones the service's load balancers are enabled in, or every zone of the Region when none is known. */
    private List<String> availabilityZones(String region, List<String> networkArns, List<String> gatewayArns) {
        Set<String> zones = new TreeSet<>();
        if (elbV2Services != null && !elbV2Services.isUnsatisfied()) {
            for (String arn : distinct(concat(networkArns, gatewayArns))) {
                loadBalancer(region, arn).ifPresent(balancer -> balancer.getAvailabilityZones().stream()
                        .map(AvailabilityZone::getZoneName).filter(Objects::nonNull).forEach(zones::add));
            }
        }
        if (zones.isEmpty()) {
            ec2Service.describeAvailabilityZones(region, List.of(), List.of(), null)
                    .forEach(zone -> zones.add(zone.get("zoneName")));
        }
        return new ArrayList<>(zones);
    }

    private static void validateIpAddressTypes(List<String> ipTypes) {
        for (String ipType : ipTypes) {
            if (!IP_ADDRESS_TYPES.contains(ipType)) {
                throw new AwsException("InvalidParameterValue",
                        "Supported IP address types are ipv4 and ipv6, not '" + ipType + "'.", 400);
            }
        }
    }

    private boolean hasActiveConnections(VpcEndpointServiceConfiguration service) {
        return ec2Service.endpointsOfServices(service.getRegion(), Set.of(service.getServiceName())).stream()
                .anyMatch(entry -> Set.of(PENDING_ACCEPTANCE, AVAILABLE).contains(entry.value().getState()));
    }

    private boolean visibleTo(VpcEndpointServiceConfiguration service, String callerAccount) {
        if (callerAccount.equals(service.getOwnerId())) {
            return true;
        }
        Optional<String> principalArn = callerPrincipalArn();
        String partition = AwsRegions.partitionFor(service.getRegion());
        for (VpcEndpointServicePermission permission : service.getAllowedPrincipals()) {
            boolean allowed = switch (permission.getPrincipalType()) {
                case "All" -> true;
                case "Account" -> {
                    AwsArnUtils.Arn arn = parsedArn(permission.getPrincipal());
                    yield arn != null && partition.equals(arn.partition()) && callerAccount.equals(arn.accountId());
                }
                default -> principalArn.filter(permission.getPrincipal()::equals).isPresent();
            };
            if (allowed) {
                return true;
            }
        }
        return false;
    }

    /** The calling user's ARN, or the role's for a role session; empty for an account's own credentials. */
    private Optional<String> callerPrincipalArn() {
        if (requestContexts == null || requestContexts.isUnsatisfied()) {
            return Optional.empty();
        }
        try {
            String accessKeyId = requestContexts.get().getAccessKeyId();
            return accessKeyId == null ? Optional.empty()
                    : iamService.resolveCallerArns(accessKeyId).map(IamService.CallerArns::principalArn);
        } catch (ContextNotActiveException e) {
            return Optional.empty();
        }
    }

    private Optional<VpcEndpointServiceConfiguration> findByName(String region, String serviceName) {
        if (serviceName == null) {
            return Optional.empty();
        }
        return allInRegion(region).stream().filter(service -> serviceName.equals(service.getServiceName())).findFirst();
    }

    private List<VpcEndpointServiceConfiguration> allInRegion(String region) {
        return services.scanAllAccountEntries(k -> k.startsWith(region + "::")).stream()
                .map(AccountAwareStorageBackend.AccountEntry::value).toList();
    }

    private List<VpcEndpointServiceConfiguration> owned(String account, String region) {
        return services.scanAllAccountEntries(k -> k.startsWith(region + "::")).stream()
                .filter(entry -> account.equals(entry.accountId()))
                .map(AccountAwareStorageBackend.AccountEntry::value)
                .sorted(Comparator.comparing(VpcEndpointServiceConfiguration::getServiceId))
                .toList();
    }

    private VpcEndpointServiceConfiguration requireOwned(String region, String serviceId) {
        return services.getForAccount(regionResolver.getAccountId(), key(region, serviceId))
                .orElseThrow(() -> serviceNotFound(serviceId));
    }

    private boolean matches(VpcEndpointServiceConfiguration service, Map.Entry<String, List<String>> filter) {
        String name = filter.getKey();
        List<String> values = filter.getValue();
        if (name.startsWith("tag:")) {
            String tagKey = name.substring("tag:".length());
            return ec2Service.resourceTags(service.getRegion(), service.getServiceId()).stream()
                    .anyMatch(tag -> tag.getKey().equals(tagKey) && anyMatches(values, tag.getValue()));
        }
        return switch (name) {
            case "service-name" -> anyMatches(values, service.getServiceName());
            case "service-id" -> anyMatches(values, service.getServiceId());
            case "service-state" -> anyMatches(values, service.getServiceState());
            case "supported-ip-address-types" -> service.getSupportedIpAddressTypes().stream()
                    .anyMatch(ipType -> anyMatches(values, ipType));
            case "tag-key" -> ec2Service.resourceTags(service.getRegion(), service.getServiceId()).stream()
                    .anyMatch(tag -> anyMatches(values, tag.getKey()));
            default -> false;
        };
    }

    private static boolean matches(Connection connection, Map.Entry<String, List<String>> filter) {
        List<String> values = filter.getValue();
        VpcEndpoint endpoint = connection.endpoint();
        return switch (filter.getKey()) {
            case "ip-address-type" -> anyMatches(values, endpoint.getIpAddressType());
            case "service-id" -> anyMatches(values, connection.service().getServiceId());
            case "vpc-endpoint-owner" -> anyMatches(values, connection.ownerId());
            case "vpc-endpoint-state" -> anyMatches(values, endpoint.getState());
            case "vpc-endpoint-id" -> anyMatches(values, endpoint.getVpcEndpointId());
            // Endpoints are only ever created in the service's own Region here, so none is cross-region.
            case "vpc-endpoint-region" -> anyMatches(values, endpoint.getRegion());
            default -> false;
        };
    }

    private static boolean anyMatches(List<String> patterns, String value) {
        return value != null && patterns.stream().anyMatch(pattern -> Ec2Service.wildcardMatches(pattern, value));
    }

    private static AwsArnUtils.Arn parsedArn(String value) {
        if (!AwsArnUtils.looksLikeArn(value)) {
            return null;
        }
        try {
            return AwsArnUtils.parse(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void requireParameter(String value, String name) {
        if (value == null) {
            throw new AwsException("MissingParameter", "The request must contain the parameter " + name, 400);
        }
    }

    private static AwsException serviceNotFound(String serviceId) {
        return new AwsException("InvalidVpcEndpointServiceId.NotFound",
                "The VPC endpoint service ID '" + serviceId + "' does not exist", 400);
    }

    private static AwsException invalidServiceName(String serviceName) {
        return new AwsException("InvalidServiceName",
                "The Vpc Endpoint Service '" + serviceName + "' does not exist", 400);
    }

    private static String key(String region, String serviceId) {
        return region + "::" + serviceId;
    }

    private static List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /** {@code current} with {@code remove} taken out and {@code add} appended, each once. */
    private static List<String> applyChanges(List<String> current, List<String> add, List<String> remove) {
        List<String> result = new ArrayList<>(current);
        result.removeAll(remove);
        for (String value : add) {
            if (!result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    /** The values of {@code values} that {@code present} lacks. */
    private static List<String> missing(List<String> values, List<String> present) {
        return distinct(values).stream().filter(value -> !present.contains(value)).toList();
    }
}
