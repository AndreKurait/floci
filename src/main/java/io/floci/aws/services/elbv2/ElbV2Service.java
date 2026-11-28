package io.floci.aws.services.elbv2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegionFacts;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.core.common.CertificateReferenceResolver;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.common.SsrfProtection;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.resource.ExplorerResource;
import io.floci.aws.core.resource.ResourceProvider;
import io.floci.aws.core.resource.SupportedResourceType;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackedMap;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.acm.AcmCertificateReferenceProvider.CertificateReference;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.EmulatorOriginSource;
import io.floci.aws.services.ec2.SecurityGroupFirewallManager;
import io.floci.aws.services.ec2.SecurityGroupNftCompiler;
import io.floci.aws.services.ec2.VpcDependencyProvider;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.elbv2.model.*;
import io.floci.aws.services.iam.ServerCertificateReferenceProvider;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@ApplicationScoped
public class ElbV2Service implements ResourceProvider, ServerCertificateReferenceProvider, VpcDependencyProvider,
        EmulatorOriginSource {

    private static final Logger LOG = Logger.getLogger(ElbV2Service.class);
    private static final String DEFAULT_SSL_POLICY = "ELBSecurityPolicy-2016-08";
    /**
     * "Certificates per Application Load Balancer (excluding default certificates)", the one
     * certificate quota the reference publishes a number for, and the number
     * {@code DescribeAccountLimits} reports.
     */
    public static final int CERTIFICATES_PER_LOAD_BALANCER = 25;
    /** {@code LoadBalancerTypeEnum}, in the model's order. */
    private static final List<String> LOAD_BALANCER_TYPES = List.of("application", "network", "gateway");
    private static final String NETWORK = "network";

    private final ElbV2VpcNetworkManager vpcNetworks;

    public ElbV2Service() {
        this(null);
    }

    @Inject
    public ElbV2Service(ElbV2VpcNetworkManager vpcNetworks) {
        this.vpcNetworks = vpcNetworks;
    }

    @Inject
    ElbV2DataPlane dataPlane;

    @Inject
    ElbV2HealthChecker healthChecker;

    @Inject
    RegionResolver regionResolver;

    @Inject
    Ec2Service ec2Service;

    @Inject
    StorageFactory storageFactory;

    @Inject
    EmulatorConfig config;

    /**
     * Left null by the unit tests, which wire this service without IAM or ACM. A referrer with no
     * resolver keeps the behaviour it had before the check existed, rather than refusing every
     * certificate because it cannot look one up.
     */
    @Inject
    CertificateReferenceResolver certificateResolver;

    /** Left null by the unit tests, which wire this service without EC2 security-group enforcement. */
    @Inject
    Instance<SecurityGroupFirewallManager> securityGroupFirewall;

    // region → ARN → resource
    private Map<String, Map<String, LoadBalancer>> loadBalancers = new ConcurrentHashMap<>();
    private Map<String, Map<String, TargetGroup>> targetGroups = new ConcurrentHashMap<>();
    private Map<String, Map<String, Listener>> listeners = new ConcurrentHashMap<>();
    private Map<String, Map<String, Rule>> rules = new ConcurrentHashMap<>();

    // The stores behind the maps above, which only see the current account; null without a StorageFactory
    private AccountAwareStorageBackend<Map<String, LoadBalancer>> loadBalancerStore;
    private AccountAwareStorageBackend<Map<String, TargetGroup>> targetGroupStore;
    private AccountAwareStorageBackend<Map<String, Listener>> listenerStore;
    private AccountAwareStorageBackend<Map<String, Rule>> ruleStore;
    private AccountAwareStorageBackend<Map<String, String>> tagStore;

    // indexes
    private final Map<String, List<String>> lbToListeners   = new ConcurrentHashMap<>(); // LB-ARN → listener ARNs
    private final Map<String, List<String>> listenerToRules  = new ConcurrentHashMap<>(); // Listener-ARN → rule ARNs
    private final Map<String, Set<String>>  tgToLbs          = new ConcurrentHashMap<>(); // TG-ARN → LB-ARNs

    // tags: resource-ARN → {key → value}
    private Map<String, Map<String, String>> tags = new ConcurrentHashMap<>();

    /** The accounts of {@code floci.services.elbv2.single-zone-alb-accounts}; see {@link #minimumAlbZones}. */
    private Set<String> singleZoneAlbAccounts = Set.of();

    /**
     * Reads {@code floci.services.elbv2.single-zone-alb-accounts} and refuses, at startup, a value that
     * is not a 12-digit account id, so a typo cannot silently leave the exception off.
     */
    void readSingleZoneAlbAccounts() {
        if (config == null) {
            singleZoneAlbAccounts = Set.of();
            return;
        }
        // Entries are trimmed, and a blank one (an empty value or a trailing comma) lists nothing, so
        // "111111111111, 222222222222" lists both and blanking the setting turns it off.
        List<String> accounts = config.services().elbv2().singleZoneAlbAccounts().orElse(List.of()).stream()
                .filter(account -> account != null && !account.isBlank())
                .map(String::trim)
                .toList();
        for (String account : accounts) {
            if (!account.matches("[0-9]{12}")) {
                throw new IllegalStateException("floci.services.elbv2.single-zone-alb-accounts must list 12-digit"
                        + " account ids, not '" + account + "'");
            }
        }
        singleZoneAlbAccounts = Set.copyOf(accounts);
    }

    /**
     * The fewest Availability Zones an Application Load Balancer of the calling account takes in
     * {@code region}. The regional zone policy ({@link #requiresTwoAlbZones}) decides between one and
     * two; Floci also lowers that to one for the accounts
     * {@code floci.services.elbv2.single-zone-alb-accounts} lists. Nothing is listed by default.
     */
    private int minimumAlbZones(String region) {
        if (!singleZoneAlbAccounts.isEmpty() && singleZoneAlbAccounts.contains(regionResolver.getAccountId())) {
            return 1;
        }
        return requiresTwoAlbZones(region) ? 2 : 1;
    }

    /**
     * The regional ALB zone policy: {@code floci.services.elbv2.alb-zone-rule-by-region} for the
     * region, then {@code floci.services.elbv2.alb-zone-rule}, then the default requirement for two Availability Zones.
     */
    private boolean requiresTwoAlbZones(String region) {
        EmulatorConfig.AlbZoneRule rule = EmulatorConfig.AlbZoneRule.AWS;
        if (config != null) {
            EmulatorConfig.ElbV2ServiceConfig settings = config.services().elbv2();
            if (settings.albZoneRule() != null) {
                rule = settings.albZoneRule();
            }
            Map<String, EmulatorConfig.AlbZoneRule> regions = settings.albZoneRuleByRegion();
            EmulatorConfig.AlbZoneRule regional = regions == null ? null : regions.get(region);
            if (regional != null) {
                rule = regional;
            }
        }
        return switch (rule) {
            case AWS -> true;
            case ALLOW_SINGLE_ZONE -> false;
            case REQUIRE_TWO_ZONES -> true;
        };
    }

    @PostConstruct
    void initializeStorage()
    {
        readSingleZoneAlbAccounts();
        if (storageFactory == null) {
            return;
        }
        this.loadBalancerStore = storageFactory.create("elbv2", "elbv2-load-balancers.json",
                new TypeReference<Map<String, Map<String, LoadBalancer>>>() {});
        this.loadBalancers = new StorageBackedMap<>(loadBalancerStore);
        this.targetGroupStore = storageFactory.create("elbv2", "elbv2-target-groups.json",
                new TypeReference<Map<String, Map<String, TargetGroup>>>() {});
        this.targetGroups = new StorageBackedMap<>(targetGroupStore);
        this.listenerStore = storageFactory.create("elbv2", "elbv2-listeners.json",
                new TypeReference<Map<String, Map<String, Listener>>>() {});
        this.listeners = new StorageBackedMap<>(listenerStore);
        this.ruleStore = storageFactory.create("elbv2", "elbv2-rules.json",
                new TypeReference<Map<String, Map<String, Rule>>>() {});
        this.rules = new StorageBackedMap<>(ruleStore);
        this.tagStore = storageFactory.create("elbv2", "elbv2-tags.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        this.tags = new StorageBackedMap<>(tagStore);
        normalizeStoredRegions(loadBalancers, loadBalancerStore);
        normalizeStoredRegions(targetGroups, targetGroupStore);
        normalizeStoredRegions(listeners, listenerStore);
        normalizeStoredRegions(rules, ruleStore);
        normalizeRegionMaps(tags);
        rebuildIndexes();
    }

    private <V> void normalizeRegionMaps(Map<String, Map<String, V>> resources) {
        for (Map.Entry<String, Map<String, V>> entry : new ArrayList<>(resources.entrySet())) {
            if (!(entry.getValue() instanceof ConcurrentHashMap)) {
                resources.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
            }
        }
    }

    private record StoredRegion<V>(String accountId, String region, Map<String, V> resources) {}

    private <V> List<StoredRegion<V>> storedRegions(Map<String, Map<String, V>> resources,
                                                  AccountAwareStorageBackend<Map<String, V>> store) {
        List<StoredRegion<V>> stored = new ArrayList<>();
        if (store == null) {
            resources.forEach((region, values) -> stored.add(new StoredRegion<>(null, region, values)));
            return stored;
        }
        store.scanAllAccountsWithRawKeys().forEach((rawKey, values) -> {
            int slash = rawKey.indexOf('/');
            if (slash < 0 && store.getForAccount(config.defaultAccountId(), rawKey).isPresent()) {
                return;
            }
            String accountId = slash < 0 ? config.defaultAccountId() : rawKey.substring(0, slash);
            String region = slash < 0 ? rawKey : rawKey.substring(slash + 1);
            stored.add(new StoredRegion<>(accountId, region, values));
        });
        return stored;
    }

    private <V> void normalizeStoredRegions(Map<String, Map<String, V>> resources,
                                           AccountAwareStorageBackend<Map<String, V>> store) {
        if (store == null) {
            normalizeRegionMaps(resources);
            return;
        }
        for (StoredRegion<V> stored : storedRegions(resources, store)) {
            Map<String, V> concurrent = stored.resources() instanceof ConcurrentHashMap
                    ? stored.resources() : new ConcurrentHashMap<>(stored.resources());
            store.putForAccount(stored.accountId(), stored.region(), concurrent);
            store.deleteRaw(stored.region());
        }
    }

    /**
     * Drops one entry from an ARN index. The list has to be looked up rather than defaulted:
     * {@code getOrDefault(key, List.of())} hands back an immutable list whose {@code remove}
     * throws, which the Query path reports as an {@code InternalFailure}.
     */
    private static void removeFromIndex(Map<String, List<String>> index, String key, String value) {
        List<String> entries = index.get(key);
        if (entries != null) {
            entries.remove(value);
        }
    }

    private <V> void persistRegion(Map<String, Map<String, V>> resources, String region) {
        Map<String, V> regionResources = resources.get(region);
        if (regionResources != null) {
            resources.put(region, regionResources);
        }
    }

    /**
     * Rebuilds the in-memory indexes from the storage-backed maps. Pure and in-process on purpose:
     * nothing reachable from {@link #initializeStorage()} may call an injected collaborator, because
     * {@link ElbV2DataPlane} calls back into this service and CDI has not registered the instance in
     * the ApplicationScoped context yet — re-entering bean creation instead (issue #1913). Runtime
     * side effects belong in {@link #restorePersistedRuntime()}.
     */
    private void rebuildIndexes()
    {
        lbToListeners.clear();
        listenerToRules.clear();
        tgToLbs.clear();

        for (Map<String, Listener> regionListeners : allAccounts(listeners, listenerStore)) {
            for (Listener listener : regionListeners.values()) {
                lbToListeners.computeIfAbsent(listener.getLoadBalancerArn(), k -> new CopyOnWriteArrayList<>())
                        .add(listener.getListenerArn());
            }
        }
        for (Map<String, Rule> regionRules : allAccounts(rules, ruleStore)) {
            for (Rule rule : regionRules.values()) {
                listenerToRules.computeIfAbsent(rule.getListenerArn(), k -> new CopyOnWriteArrayList<>())
                        .add(rule.getRuleArn());
            }
        }
        for (Map<String, TargetGroup> regionTargetGroups : allAccounts(targetGroups, targetGroupStore)) {
            for (TargetGroup targetGroup : regionTargetGroups.values()) {
                tgToLbs.computeIfAbsent(targetGroup.getTargetGroupArn(), k -> ConcurrentHashMap.newKeySet())
                        .addAll(targetGroup.getLoadBalancerArns());
            }
        }
    }

    /** Every account's region maps; the indexes are keyed by ARN, which already names the account. */
    private static <V> Collection<Map<String, V>> allAccounts(Map<String, Map<String, V>> resources,
                                                              AccountAwareStorageBackend<Map<String, V>> store) {
        return store == null ? resources.values() : store.scanAllAccounts();
    }

    /**
     * Brings the data plane and health checks back up for state restored from disk. Invoked from
     * {@code EmulatorLifecycle} once the bean is fully constructed, alongside the other services that
     * restore persisted runtime after {@code storageFactory.loadAll()}. Startup has no request, so each
     * account that stored anything is restored as itself rather than as the default account.
     */
    public void restorePersistedRuntime()
    {
        refreshCanonicalHostedZones();
        if (vpcNetworks != null) {
            for (StoredRegion<LoadBalancer> stored : storedRegions(loadBalancers, loadBalancerStore)) {
                Runnable persist = () -> persistStoredLoadBalancers(stored);
                for (LoadBalancer balancer : stored.resources().values()) {
                    try {
                        if (vpcNetworks.enabled() && "application".equals(balancer.getType())) {
                            balancer.setState("provisioning");
                            persist.run();
                        }
                        vpcNetworks.reconcile(balancer, persist);
                        if (vpcNetworks.enabled() && "application".equals(balancer.getType())) {
                            balancer.setState("active");
                            persist.run();
                        }
                    } catch (RuntimeException failure) {
                        if (vpcNetworks.enabled() && "application".equals(balancer.getType())) {
                            balancer.setState("failed");
                            persist.run();
                        }
                        LOG.warnv(failure, "ELBv2 backend network could not be restored for {0}",
                                balancer.getLoadBalancerArn());
                    }
                }
            }
        }
        if (healthChecker != null) {
            for (StoredRegion<TargetGroup> stored : storedRegions(targetGroups, targetGroupStore)) {
                RequestScopes.runAs(stored.accountId(), stored.region(), () -> {
                    for (TargetGroup targetGroup : stored.resources().values()) {
                        healthChecker.startMonitoring(targetGroup);
                        if (!targetGroup.getTargets().isEmpty()) {
                            healthChecker.addTargets(targetGroup.getTargetGroupArn(), targetGroup.getTargets(), targetGroup);
                        }
                    }
                });
            }
        }
        if (dataPlane != null) {
            for (StoredRegion<Listener> stored : storedRegions(listeners, listenerStore)) {
                String region = stored.region();
                for (Listener listener : stored.resources().values()) {
                    try {
                        RequestScopes.runAs(stored.accountId(), region,
                                () -> restoreListener(region, listener.getListenerArn()));
                    } catch (RuntimeException failure) {
                        LOG.warnv("ELBv2 saved listener {0} could not be restored ({1}: {2}); its configuration is retained",
                                listener.getListenerArn(), failure.getClass().getSimpleName(), failure.getMessage());
                    }
                }
            }
        }
    }

    // ── Load Balancers ────────────────────────────────────────────────────────

    /**
     * The subnets a CreateLoadBalancer or SetSubnets request names: {@code Subnets}, or else the subnet
     * of each {@code SubnetMappings} entry. The two are alternatives, so a request that names subnets
     * both ways is refused with the error AWS gives; an empty one does not count.
     */
    public static List<String> requestedSubnets(List<String> subnets, List<String> mappingSubnetIds) {
        if (!subnets.isEmpty() && !mappingSubnetIds.isEmpty()) {
            throw new AwsException("ValidationError",
                    "You can specify either subnets or subnet mappings, not both", 400);
        }
        return mappingSubnetIds.isEmpty() ? subnets : mappingSubnetIds;
    }

    /**
     * Resolving the subnets and publishing the balancer happen under EC2's subnet lock, so a
     * DeleteSubnet cannot pass its dependency check in between and leave the balancer on a subnet
     * that is gone. A {@code Type} outside the model's enum is refused, as AWS refuses it, rather
     * than read as an application load balancer, which is not the one IAM checks the request for.
     */
    public LoadBalancer createLoadBalancer(String region, String name, String scheme,
                                           String type, String ipAddressType,
                                           List<String> subnets, List<String> securityGroups,
                                           Map<String, String> initialTags) {
        if (type != null && !LOAD_BALANCER_TYPES.contains(type)) {
            throw SmithyValidationErrors.validationError(SmithyValidationErrors.notInEnum(type, "type", LOAD_BALANCER_TYPES));
        }
        LoadBalancer balancer = ec2Service.supplyWithSubnetReferencesHeld(region, () -> createLoadBalancerWithSubnetsHeld(
                region, name, scheme, type, ipAddressType, subnets, securityGroups, initialTags));
        if (vpcNetworks != null) {
            synchronized (balancer) {
                if (loadBalancers.getOrDefault(region, Map.of()).get(balancer.getLoadBalancerArn()) != balancer) {
                    throw new AwsException("LoadBalancerNotFound", "The load balancer was deleted.", 400);
                }
                if ("active".equals(balancer.getState())) {
                    return balancer;
                }
                try {
                    vpcNetworks.reconcile(balancer, () -> persistRegion(loadBalancers, region));
                    balancer.setState("active");
                    persistRegion(loadBalancers, region);
                } catch (RuntimeException failure) {
                    balancer.setState("failed");
                    persistRegion(loadBalancers, region);
                    try {
                        deleteLoadBalancer(region, balancer.getLoadBalancerArn());
                    } catch (RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
            }
        }
        return balancer;
    }

    private LoadBalancer createLoadBalancerWithSubnetsHeld(String region, String name, String scheme,
                                                           String type, String ipAddressType,
                                                           List<String> subnets, List<String> securityGroups,
                                                           Map<String, String> initialTags) {
        validateName(name, "load balancer");
        String lbType = type != null ? type : "application";
        String lbScheme = scheme != null ? scheme : "internet-facing";
        String ipType = ipAddressType != null ? ipAddressType : "ipv4";
        Map<String, LoadBalancer> regionLbs = loadBalancers.computeIfAbsent(region, k -> new ConcurrentHashMap<>());
        LoadBalancer existing = regionLbs.values().stream()
                .filter(lb -> lb.getLoadBalancerName().equals(name)).findFirst().orElse(null);
        if (existing != null) {
            // CreateLoadBalancer is idempotent for the same settings. Returning the stored
            // resource also preserves its listeners, attributes, creation time and DNS name.
            if (lbType.equals(existing.getType()) && lbScheme.equals(existing.getScheme())
                    && ipType.equals(existing.getIpAddressType())
                    && new HashSet<>(subnets != null ? subnets : List.of()).equals(
                            existing.getAvailabilityZones().stream().map(AvailabilityZone::getSubnetId)
                                    .collect(Collectors.toSet()))
                    && new HashSet<>(securityGroups != null ? securityGroups : List.of()).equals(
                            new HashSet<>(existing.getSecurityGroups()))
                    && initialTags.equals(tags.getOrDefault(existing.getLoadBalancerArn(), Map.of()))) {
                return existing;
            }
            throw new AwsException("DuplicateLoadBalancerName",
                    "A load balancer with name '" + name + "' already exists.", 400);
        }

        String typePrefix = lbTypePrefix(lbType);
        String id = randomHex16();
        String arn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "loadbalancer/" + typePrefix + "/" + name + "/" + id).toString();
        String dnsName = loadBalancerDnsName(name, id, lbType, lbScheme, region);
        requireSubnets(region, lbType, subnets);
        String vpcId = resolveSubnetVpcId(region, lbType, subnets);

        LoadBalancer lb = new LoadBalancer();
        lb.setLoadBalancerArn(arn);
        lb.setDnsName(dnsName);
        lb.setCanonicalHostedZoneId(canonicalHostedZoneId(lbType, region));
        lb.setCreatedTime(Instant.now());
        lb.setLoadBalancerName(name);
        lb.setScheme(lbScheme);
        lb.setVpcId(vpcId);
        lb.setState(vpcNetworks != null && vpcNetworks.enabled() && "application".equals(lbType)
                ? "provisioning" : "active");
        lb.setType(lbType);
        lb.setIpAddressType(ipType);
        lb.setRegion(region);
        lb.setAvailabilityZones(resolveAvailabilityZones(region, subnets));
        if (securityGroups != null) lb.setSecurityGroups(new ArrayList<>(securityGroups));

        regionLbs.put(arn, lb);
        loadBalancers.put(region, regionLbs);
        lbToListeners.put(arn, new ArrayList<>());
        if (!initialTags.isEmpty()) {
            tags.put(arn, new LinkedHashMap<>(initialTags));
        }
        emulatorOriginsChanged(lb);
        return lb;
    }

    /**
     * Every load balancer type takes {@code Subnets} or {@code SubnetMappings}, and an Application Load
     * Balancer takes at least two by default, so a CreateLoadBalancer with fewer is refused before anything is
     * created, with the message AWS gives an Application or a Network Load Balancer. A Gateway Load
     * Balancer, which takes subnets the way a Network Load Balancer does, gets the Network one. AWS
     * counts before it looks a subnet up, so one subnet that does not exist gets this error too, not
     * SubnetNotFound. On create this runs after the duplicate-name check, because a CloudFormation stack
     * update finds the balancer it keeps through DuplicateLoadBalancerName, whatever subnets its
     * template names. SetSubnets takes the same subnets, so {@link #setSubnetsFromRequest} runs this on
     * a SetSubnets that names none, and {@link #setSubnets} on a list that names some, once it has the
     * balancer's type; whether the subnets span two Availability Zones is checked after they are looked
     * up. No public report shows what AWS answers a SetSubnets with one subnet, so it gets the create
     * message and order. For an account granted single-zone ALBs ({@link #minimumAlbZones}) one subnet
     * is enough, and none still gets the Application Load Balancer message. Where the regional zone
     * policy ({@link #requiresTwoAlbZones}) accepts one zone, one subnet is enough for every account
     * and none gets the Network Load Balancer message.
     */
    private void requireSubnets(String region, String lbType, List<String> subnets) {
        int count = subnets == null ? 0 : subnets.size();
        if ("application".equals(lbType) && requiresTwoAlbZones(region) && count < minimumAlbZones(region)) {
            throw new AwsException("ValidationError",
                    "At least two subnets in two different Availability Zones must be specified", 400);
        }
        if (count == 0) {
            throw new AwsException("ValidationError", "At least one subnet must be specified", 400);
        }
    }



    /**
     * A subnet is in use while a load balancer is configured with it. Account-scoped through the
     * request-scoped storage, and a target group does not count: it pins nothing in AWS either.
     */
    @Override
    public boolean hasSubnetDependency(String region, String subnetId) {
        return loadBalancers.getOrDefault(region, Map.of()).values().stream()
                .anyMatch(lb -> lb.getAvailabilityZones().stream()
                        .anyMatch(zone -> subnetId.equals(zone.getSubnetId())));
    }

    @Override
    public boolean hasVpcDependency(String region, String vpcId) {
        return loadBalancers.getOrDefault(region, Map.of()).values().stream()
                .anyMatch(lb -> vpcId.equals(lb.getVpcId()));
    }

    private String loadBalancerDnsSuffix() {
        if (config == null) {
            return EmbeddedDnsServer.DEFAULT_SUFFIX;
        }
        return config.hostname().orElse(EmbeddedDnsServer.DEFAULT_SUFFIX);
    }

    /**
     * The DNS name a new load balancer gets, in the form {@code floci.services.elbv2.dns-name-strategy}
     * selects. The AWS form is the documented one: an Application Load Balancer's name ends in a
     * decimal number and the region comes before {@code elb}, with {@code internal-} in front for an
     * internal scheme; a Network or Gateway Load Balancer's name ends in the id its ARN carries and
     * {@code elb} comes before the region.
     */
    private String loadBalancerDnsName(String name, String id, String type, String scheme, String region) {
        if (!awsDnsNames()) {
            return name + "-" + id + ".elb." + loadBalancerDnsSuffix();
        }
        String suffix = AwsRegions.dnsSuffixFor(region);
        if ("application".equals(type)) {
            String prefix = "internal".equals(scheme) ? "internal-" : "";
            long number = ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L);
            return prefix + name + "-" + number + "." + region + ".elb." + suffix;
        }
        return name + "-" + id + ".elb." + region + "." + suffix;
    }

    boolean awsDnsNames() {
        if (config == null || config.services() == null || config.services().elbv2() == null) {
            return false;
        }
        return config.services().elbv2().dnsNameStrategy() == EmulatorConfig.ElbV2ServiceConfig.DnsNameStrategy.AWS;
    }

    /** DNS selects the resource owner independently of a management request's account. */
    List<LoadBalancer> findByDnsName(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String normalized = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
        if (normalized.regionMatches(true, 0, "dualstack.", 0, "dualstack.".length())) {
            normalized = normalized.substring("dualstack.".length());
        }
        String target = normalized;
        Collection<Map<String, LoadBalancer>> regions =
                loadBalancerStore != null ? loadBalancerStore.scanAllAccounts() : loadBalancers.values();
        return regions.stream().flatMap(region -> region.values().stream())
                .filter(lb -> target.equalsIgnoreCase(lb.getDnsName()))
                .toList();
    }

    /** DNS inspects the selected load balancer's listeners across management accounts. */
    List<Listener> findListenersForLoadBalancer(String loadBalancerArn) {
        Collection<Map<String, Listener>> regions =
                listenerStore != null ? listenerStore.scanAllAccounts() : listeners.values();
        return regions.stream().flatMap(region -> region.values().stream())
                .filter(listener -> loadBalancerArn.equals(listener.getLoadBalancerArn()))
                .toList();
    }

    public List<LoadBalancer> describeLoadBalancers(String region, List<String> arns, List<String> names,
                                                     String marker, Integer pageSize) {
        Map<String, LoadBalancer> regionLbs = loadBalancers.getOrDefault(region, Map.of());
        List<LoadBalancer> result = new ArrayList<>(regionLbs.values());

        if (arns != null && !arns.isEmpty()) {
            Set<String> arnSet = new HashSet<>(arns);
            result = result.stream().filter(lb -> arnSet.contains(lb.getLoadBalancerArn())).collect(Collectors.toList());
            if (result.isEmpty() && !arns.isEmpty()) {
                throw new AwsException("LoadBalancerNotFound",
                        "One or more load balancers not found.", 400);
            }
        }
        if (names != null && !names.isEmpty()) {
            Set<String> nameSet = new HashSet<>(names);
            result = result.stream().filter(lb -> nameSet.contains(lb.getLoadBalancerName())).collect(Collectors.toList());
            if (result.isEmpty() && !names.isEmpty()) {
                throw new AwsException("LoadBalancerNotFound",
                        "One or more load balancers not found.", 400);
            }
        }
        return result;
    }

    public void deleteLoadBalancer(String region, String arn) {
        Map<String, LoadBalancer> regionLbs = loadBalancers.get(region);
        if (regionLbs == null) {
            return; // AWS silently ignores non-existent LBs on delete
        }
        LoadBalancer lb = regionLbs.get(arn);
        if (lb == null) {
            return;
        }
        List<String> listenerArns;
        synchronized (lb) {
            if (regionLbs.get(arn) != lb) {
                return;
            }
            if (vpcNetworks != null) {
                vpcNetworks.release(lb, () -> persistRegion(loadBalancers, region));
            }
            if (!regionLbs.remove(arn, lb)) {
                return;
            }
            listenerArns = listeners.getOrDefault(region, Map.of()).values().stream()
                    .filter(listener -> arn.equals(listener.getLoadBalancerArn()))
                    .map(Listener::getListenerArn).toList();
            lbToListeners.remove(arn);
        }
        for (String listenerArn : listenerArns) {
            deleteListener(region, listenerArn);
        }
        // remove from TG index
        tgToLbs.values().forEach(lbSet -> lbSet.remove(arn));
        tags.remove(arn);
        loadBalancers.put(region, regionLbs);
        emulatorOriginsChanged(lb);
    }

    /**
     * Every network load balancer with security groups in a VPC, in every account. Floci sends its
     * health checks and forwarded connections from its own address, so a target's rule that admits one
     * of these groups admits that address. An application load balancer is not one: it reaches its
     * targets from its own VPC interfaces (ElbV2VpcNetworkManager).
     */
    @Override
    public List<SecurityGroupNftCompiler.EmulatorOrigin> emulatorOrigins() {
        if (config != null && config.services().elbv2().mock()) {
            // A mock ELBv2 sends no traffic, so it admits nothing.
            return List.of();
        }
        Collection<Map<String, LoadBalancer>> regions = loadBalancerStore != null
                ? loadBalancerStore.scanAllAccounts() : loadBalancers.values();
        List<SecurityGroupNftCompiler.EmulatorOrigin> origins = new ArrayList<>();
        for (Map<String, LoadBalancer> regionLbs : regions) {
            for (LoadBalancer lb : regionLbs.values()) {
                AwsArnUtils.Arn arn;
                try {
                    arn = AwsArnUtils.looksLikeArn(lb.getLoadBalancerArn())
                            ? AwsArnUtils.parse(lb.getLoadBalancerArn()) : null;
                } catch (IllegalArgumentException e) {
                    // One unreadable record must not hide every other load balancer's groups.
                    continue;
                }
                String accountId = arn != null ? arn.accountId() : null;
                String region = lb.getRegion() != null ? lb.getRegion() : arn != null ? arn.region() : null;
                List<String> groups = lb.getSecurityGroups();
                if (NETWORK.equals(lb.getType()) && accountId != null && region != null && lb.getVpcId() != null
                        && groups != null && !groups.isEmpty()) {
                    origins.add(new SecurityGroupNftCompiler.EmulatorOrigin(accountId, region,
                            lb.getVpcId(), groups.stream().filter(Objects::nonNull).collect(Collectors.toSet())));
                }
            }
        }
        return origins;
    }

    private void emulatorOriginsChanged(LoadBalancer lb) {
        if (NETWORK.equals(lb.getType()) && lb.getVpcId() != null && securityGroupFirewall != null && securityGroupFirewall.isResolvable()) {
            securityGroupFirewall.get().emulatorOriginsChanged();
        }
    }

    public Map<String, String> describeLoadBalancerAttributes(String region, String arn) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        return ElbV2DefaultAttributes.merge(ElbV2DefaultAttributes.forLoadBalancer(lb), lb.getAttributes());
    }

    public void modifyLoadBalancerAttributes(String region, String arn, Map<String, String> newAttrs) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        requireValidAttributeValue(newAttrs, ElbV2ForwardedHeaders.XFF_MODE_ATTRIBUTE, ElbV2ForwardedHeaders.XFF_MODES);
        requireValidAttributeValue(newAttrs, ElbV2ForwardedHeaders.XFF_CLIENT_PORT_ATTRIBUTE, Set.of("true", "false"));
        lb.getAttributes().putAll(newAttrs);
        persistRegion(loadBalancers, region);
    }

    /** Restores only attributes changed by a failed CloudFormation update; null means previously absent. */
    public void restoreLoadBalancerAttributes(String region, String arn, Map<String, String> previous) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        previous.forEach((key, value) -> {
            if (value == null) {
                lb.getAttributes().remove(key);
            } else {
                lb.getAttributes().put(key, value);
            }
        });
        persistRegion(loadBalancers, region);
    }

    private static void requireValidAttributeValue(Map<String, String> attributes, String key, Set<String> allowed) {
        String value = attributes.get(key);
        if (value != null && !allowed.contains(value)) {
            throw new AwsException("InvalidConfigurationRequest",
                    "Load balancer attribute '" + key + "' cannot have the value '" + value + "'", 400);
        }
    }

    LoadBalancer getLoadBalancer(String region, String arn) {
        return loadBalancers.getOrDefault(region, Map.of()).get(arn);
    }

    /**
     * The security groups of the load balancer that {@code arn} (a load balancer or listener ARN)
     * belongs to, in any account; empty when it has none or is unknown. Floci checks a protected
     * target's ingress against these before its NLB traffic dials it.
     */
    Set<String> originSecurityGroups(String arn) {
        if (arn == null || !AwsArnUtils.isArn(arn)) {
            return Set.of();
        }
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            return Set.of();
        }
        String resource = parsed.resource();
        if (resource.startsWith("listener/")) {
            String[] parts = resource.split("/");
            if (parts.length < 5) {
                return Set.of();
            }
            arn = arn.substring(0, arn.indexOf(resource)) + "loadbalancer/" + parts[1] + "/" + parts[2] + "/" + parts[3];
        }
        Map<String, LoadBalancer> regionLbs = loadBalancerStore == null ? loadBalancers.get(parsed.region())
                : loadBalancerStore.getForAccount(parsed.accountId(), parsed.region()).orElse(null);
        LoadBalancer lb = regionLbs == null ? null : regionLbs.get(arn);
        return lb == null || lb.getSecurityGroups() == null ? Set.of()
                : lb.getSecurityGroups().stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }

    Listener getListener(String region, String arn) {
        return listeners.getOrDefault(region, Map.of()).get(arn);
    }

    TargetGroup getTargetGroupForListener(String region, String listenerArn, String targetGroupArn) {
        return dataPlaneResource(targetGroups, targetGroupStore, region, listenerArn, targetGroupArn);
    }

    LoadBalancer getLoadBalancerForListener(String region, String listenerArn, String loadBalancerArn) {
        return dataPlaneResource(loadBalancers, loadBalancerStore, region, listenerArn, loadBalancerArn);
    }

    Listener getDataPlaneListener(String region, String listenerArn) {
        return dataPlaneResource(listeners, listenerStore, region, listenerArn, listenerArn);
    }

    private <V> V dataPlaneResource(Map<String, Map<String, V>> resources,
                                   AccountAwareStorageBackend<Map<String, V>> store,
                                   String region, String listenerArn, String resourceArn) {
        if (!AwsArnUtils.isArn(listenerArn) || !AwsArnUtils.isArn(resourceArn)) {
            return null;
        }
        AwsArnUtils.Arn owner = AwsArnUtils.parse(listenerArn);
        AwsArnUtils.Arn resource = AwsArnUtils.parse(resourceArn);
        if (!"elasticloadbalancing".equals(owner.service()) || !owner.service().equals(resource.service())
                || !region.equals(owner.region()) || !region.equals(resource.region())
                || !owner.partition().equals(resource.partition())
                || !owner.partition().equals(AwsRegions.partitionFor(region))
                || owner.accountId().isBlank() || !owner.accountId().equals(resource.accountId())) {
            return null;
        }
        Map<String, V> regionResources = store == null ? resources.get(region)
                : store.getForAccount(owner.accountId(), region).orElse(null);
        return regionResources == null ? null : regionResources.get(resourceArn);
    }

    /** Capacity reservation status for a load balancer. All fields are {@code null} when no
     *  capacity is reserved, which is always the case in Floci (capacity cannot be reserved). */
    public record CapacityReservation(Integer decreaseRequestsRemaining,
                                      Integer minimumCapacityUnits,
                                      Instant lastModifiedTime) {}

    public CapacityReservation describeCapacityReservation(String region, String arn) {
        requireLoadBalancer(region, arn);
        // Floci does not reserve load balancer capacity, so there is never a reservation to report.
        return new CapacityReservation(null, null, null);
    }

    public record ProvisionedCapacity(int minimumLBCapacityUnits, String status, int decreasesRemaining) {}

    /** Legacy capacity view for an application load balancer without a reservation. */
    public ProvisionedCapacity describeProvisionedCapacity(String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("ValidationError", "A valid LoadBalancerArn must be specified.", 400);
        }
        if (!"elasticloadbalancing".equals(parsed.service())
                || !parsed.accountId().matches("[0-9]{12}")
                || !parsed.resource().matches("loadbalancer/app/[A-Za-z0-9-]{1,32}/[0-9a-f]{16}")
                || !region.equals(parsed.region())
                // Match createLoadBalancer's ARN derivation, including persisted custom-region ARNs.
                || !AwsRegions.partitionFor(region).equals(parsed.partition())) {
            throw new AwsException("ValidationError",
                    "LoadBalancerArn must identify an application load balancer in the request region.", 400);
        }
        if (!regionResolver.getAccountId().equals(parsed.accountId())) {
            throw new AwsException("LoadBalancerNotFound", "One or more load balancers not found.", 400);
        }
        LoadBalancer lb = requireLoadBalancer(region, arn);
        if (!"application".equals(lb.getType())) {
            throw new AwsException("ValidationError", "LoadBalancerArn must identify an application load balancer.", 400);
        }
        // Capacity is never reserved in Floci, so none of the four daily decreases has been consumed.
        return new ProvisionedCapacity(0, "disabled", 4);
    }

    public void setSecurityGroups(String region, String arn, List<String> sgIds) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        synchronized (lb) {
            if (loadBalancers.getOrDefault(region, Map.of()).get(arn) != lb) {
                throw new AwsException("LoadBalancerNotFound", "The load balancer was deleted.", 400);
            }
            List<String> previous = lb.getSecurityGroups();
            lb.setSecurityGroups(new ArrayList<>(sgIds));
            try {
                if (vpcNetworks != null) {
                    vpcNetworks.reconcile(lb, () -> persistRegion(loadBalancers, region));
                }
                persistRegion(loadBalancers, region);
            } catch (RuntimeException failure) {
                lb.setSecurityGroups(previous);
                try {
                    if (vpcNetworks != null) {
                        vpcNetworks.reconcile(lb, () -> persistRegion(loadBalancers, region));
                    }
                    persistRegion(loadBalancers, region);
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    retainFailedBackend(lb, region, failure);
                }
                throw failure;
            }
        }
        emulatorOriginsChanged(lb);
    }

    /**
     * The SetSubnets action, for the Query handler. A request with neither {@code Subnets} nor
     * {@code SubnetMappings} is refused and the balancer stays on its subnets, with the error
     * CreateLoadBalancer gives a balancer of its type with neither; AWS gives a Network Load Balancer
     * that error for SetSubnets too. The balancer is looked up first, as the message depends on its
     * type. Only an empty list is refused here; a request that names subnets is checked by
     * {@link #setSubnets}.
     */
    void setSubnetsFromRequest(String region, String arn, List<String> subnets) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        if (subnets.isEmpty()) {
            requireSubnets(region, lb.getType(), subnets);
        }
        setSubnets(region, arn, subnets);
    }

    /**
     * Under EC2's subnet lock for the same reason as {@link #createLoadBalancer}. Unlike the SetSubnets
     * action, this takes an empty list: CloudFormation moves balancers through it, and its rollback
     * moves a balancer an earlier Floci version saved without subnets back onto none.
     */
    public void setSubnets(String region, String arn, List<String> subnets) {
        setSubnets(region, arn, subnets, false);
    }

    /** Restores CloudFormation's saved subnet snapshot, including legacy duplicate-zone assignments. */
    public void restoreSubnetSnapshot(String region, String arn, List<String> subnets) {
        setSubnets(region, arn, subnets, true);
    }

    private void setSubnets(String region, String arn, List<String> subnets, boolean restoreSnapshot) {
        LoadBalancer balancer = requireLoadBalancer(region, arn);
        synchronized (balancer) {
            List<AvailabilityZone> previous = List.copyOf(balancer.getAvailabilityZones());
            String previousVpc = balancer.getVpcId();
            setSubnetsWithBalancerHeld(region, arn, subnets, restoreSnapshot);
            try {
                if (vpcNetworks != null) {
                    vpcNetworks.reconcile(balancer, () -> persistRegion(loadBalancers, region));
                }
            } catch (RuntimeException failure) {
                balancer.setAvailabilityZones(new ArrayList<>(previous));
                balancer.setVpcId(previousVpc);
                try {
                    persistRegion(loadBalancers, region);
                    vpcNetworks.reconcile(balancer, () -> persistRegion(loadBalancers, region));
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                    retainFailedBackend(balancer, region, failure);
                }
                throw failure;
            }
        }
    }

    private void retainFailedBackend(LoadBalancer balancer, String region, RuntimeException failure) {
        balancer.setState("failed");
        try {
            persistRegion(loadBalancers, region);
        } catch (RuntimeException persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
        }
    }

    private void setSubnetsWithBalancerHeld(String region, String arn, List<String> subnets, boolean restoreSnapshot) {
        ec2Service.runWithSubnetReferencesHeld(region, () -> {
            LoadBalancer lb = requireLoadBalancer(region, arn);
            // Counted as on create, before any subnet is looked up; an empty list is taken, as above.
            if (subnets != null && !subnets.isEmpty()) {
                requireSubnets(region, lb.getType(), subnets);
            }
            String vpcId = resolveSubnetVpcId(region, lb.getType(), subnets, restoreSnapshot);
            if (vpcId != null && lb.getVpcId() != null && !lb.getVpcId().equals(vpcId)) {
                throw new AwsException("InvalidConfigurationRequest",
                        "All subnets must belong to the load balancer VPC.", 400);
            }
            lb.setAvailabilityZones(resolveAvailabilityZones(region, subnets));
            if (vpcId != null) {
                lb.setVpcId(vpcId);
            }
            persistRegion(loadBalancers, region);
            emulatorOriginsChanged(lb);
        });
    }

    public void setIpAddressType(String region, String arn, String ipAddressType) {
        LoadBalancer lb = requireLoadBalancer(region, arn);
        lb.setIpAddressType(ipAddressType);
        persistRegion(loadBalancers, region);
    }

    // ── Target Groups ─────────────────────────────────────────────────────────

    public TargetGroup createTargetGroup(String region, String name, String protocol, String protocolVersion,
                                          Integer port, String vpcId, String targetType,
                                          String healthCheckProtocol, String healthCheckPort,
                                          Boolean healthCheckEnabled, String healthCheckPath,
                                          Integer healthCheckInterval, Integer healthCheckTimeout,
                                          Integer healthyThreshold, Integer unhealthyThreshold,
                                          String matcher, String ipAddressType,
                                          Map<String, String> initialTags) {
        validateHealthCheckTimeout(healthCheckTimeout);
        validateHealthCheckInterval(healthCheckInterval);
        validatePort(port, "port");
        validateName(name, "target group");
        if (port == null && !"lambda".equals(targetType)) {
            throw new AwsException("ValidationError", "A port must be specified", 400);
        }
        validateHealthCheckPort(healthCheckPort);
        String groupProtocol = protocol != null ? protocol : "HTTP";
        String groupTargetType = targetType != null ? targetType : "instance";
        validateHealthCheckProtocol(healthCheckProtocol, groupProtocol, groupTargetType);
        String checkProtocol = healthCheckProtocol != null ? healthCheckProtocol
                : defaultHealthCheckProtocol(groupProtocol, groupTargetType);
        validateLayer7Members(checkProtocol, healthCheckPath, matcher);
        boolean layer7Check = isHttpProtocol(checkProtocol);
        validateHealthCheckEnabled(groupTargetType, healthCheckEnabled);
        Map<String, TargetGroup> regionTgs = targetGroups.computeIfAbsent(region, k -> new ConcurrentHashMap<>());
        boolean duplicate = regionTgs.values().stream()
                .anyMatch(tg -> tg.getTargetGroupName().equals(name));
        if (duplicate) {
            throw new AwsException("DuplicateTargetGroupName",
                    "A target group with name '" + name + "' already exists.", 400);
        }

        String id = randomHex16();
        String arn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "targetgroup/" + name + "/" + id).toString();

        TargetGroup tg = new TargetGroup();
        tg.setTargetGroupArn(arn);
        tg.setTargetGroupName(name);
        tg.setProtocol(groupProtocol);
        tg.setProtocolVersion(protocolVersion != null ? protocolVersion : "HTTP1");
        tg.setPort(port);
        tg.setVpcId(vpcId);
        tg.setTargetType(groupTargetType);
        tg.setIpAddressType(ipAddressType != null ? ipAddressType : "ipv4");
        tg.setRegion(region);

        // health check defaults
        tg.setHealthCheckEnabled(healthCheckEnabled != null ? healthCheckEnabled : !"lambda".equals(groupTargetType));
        tg.setHealthCheckProtocol(checkProtocol);
        tg.setHealthCheckPort(healthCheckPort != null ? healthCheckPort : "traffic-port");
        tg.setHealthCheckPath(layer7Check ? (healthCheckPath != null ? healthCheckPath : "/") : null);
        tg.setHealthCheckIntervalSeconds(healthCheckInterval != null ? healthCheckInterval : 30);
        tg.setHealthCheckTimeoutSeconds(healthCheckTimeout != null ? healthCheckTimeout : 5);
        tg.setHealthyThresholdCount(healthyThreshold != null ? healthyThreshold : 5);
        tg.setUnhealthyThresholdCount(unhealthyThreshold != null ? unhealthyThreshold : 2);
        tg.setMatcher(layer7Check ? (matcher != null ? matcher : "200") : null);

        regionTgs.put(arn, tg);
        targetGroups.put(region, regionTgs);
        tgToLbs.put(arn, ConcurrentHashMap.newKeySet());
        if (!initialTags.isEmpty()) {
            tags.put(arn, new LinkedHashMap<>(initialTags));
        }
        healthChecker.startMonitoring(tg);
        return tg;
    }

    public List<TargetGroup> describeTargetGroups(String region, String lbArn, List<String> tgArns,
                                                    List<String> names) {
        Map<String, TargetGroup> regionTgs = targetGroups.getOrDefault(region, Map.of());
        List<TargetGroup> result = new ArrayList<>(regionTgs.values());

        if (lbArn != null && !lbArn.isEmpty()) {
            result = result.stream()
                    .filter(tg -> tgToLbs.getOrDefault(tg.getTargetGroupArn(), Set.of()).contains(lbArn))
                    .collect(Collectors.toList());
        }
        if (tgArns != null && !tgArns.isEmpty()) {
            Set<String> arnSet = new HashSet<>(tgArns);
            result = result.stream().filter(tg -> arnSet.contains(tg.getTargetGroupArn())).collect(Collectors.toList());
            if (result.size() != arnSet.size()) {
                throw new AwsException("TargetGroupNotFound", "One or more target groups not found.", 400);
            }
        }
        if (names != null && !names.isEmpty()) {
            Set<String> nameSet = new HashSet<>(names);
            result = result.stream().filter(tg -> nameSet.contains(tg.getTargetGroupName())).collect(Collectors.toList());
            if (result.size() != nameSet.size()) {
                throw new AwsException("TargetGroupNotFound", "One or more target groups not found.", 400);
            }
        }
        return result;
    }

    public void deleteTargetGroup(String region, String arn) {
        TargetGroup tg = targetGroups.getOrDefault(region, Map.of()).get(arn);
        if (tg == null) {
            return;
        }
        Set<String> lbRefs = tgToLbs.getOrDefault(arn, Set.of());
        if (!lbRefs.isEmpty()) {
            throw new AwsException("ResourceInUse",
                    "Target group '" + tg.getTargetGroupName() + "' is currently in use by a listener or rule.", 400);
        }
        healthChecker.stopMonitoring(arn);
        targetGroups.getOrDefault(region, Map.of()).remove(arn);
        persistRegion(targetGroups, region);
        tgToLbs.remove(arn);
        tags.remove(arn);
    }

    public void modifyTargetGroup(String region, String arn, String healthCheckProtocol,
                                   String healthCheckPort, Boolean healthCheckEnabled,
                                   String healthCheckPath, Integer healthCheckInterval,
                                   Integer healthCheckTimeout, Integer healthyThreshold,
                                   Integer unhealthyThreshold, String matcher) {
        validateHealthCheckTimeout(healthCheckTimeout);
        validateHealthCheckInterval(healthCheckInterval);
        TargetGroup tg = requireTargetGroup(region, arn);
        validateHealthCheckPort(healthCheckPort);
        validateHealthCheckEnabled(tg.getTargetType(), healthCheckEnabled);
        validateHealthCheckProtocol(healthCheckProtocol, tg.getProtocol(), tg.getTargetType());
        String checkProtocol = healthCheckProtocol != null ? healthCheckProtocol : tg.getHealthCheckProtocol();
        validateLayer7Members(checkProtocol, healthCheckPath, matcher);
        if (isHttpProtocol(healthCheckProtocol) && !isHttpProtocol(tg.getHealthCheckProtocol())
                && (healthCheckPath == null || matcher == null)) {
            throw new AwsException("ValidationError", "Path and return code are required for layer 7 health checks.",
                    400);
        }
        Integer previousInterval = tg.getHealthCheckIntervalSeconds();
        if (healthCheckProtocol != null) tg.setHealthCheckProtocol(healthCheckProtocol);
        if (!isHttpProtocol(checkProtocol)) {
            tg.setHealthCheckPath(null);
            tg.setMatcher(null);
        }
        if (healthCheckPort != null)     tg.setHealthCheckPort(healthCheckPort);
        if (healthCheckEnabled != null)  tg.setHealthCheckEnabled(healthCheckEnabled);
        if (healthCheckPath != null)     tg.setHealthCheckPath(healthCheckPath);
        if (healthCheckInterval != null) tg.setHealthCheckIntervalSeconds(healthCheckInterval);
        if (healthCheckTimeout != null)  tg.setHealthCheckTimeoutSeconds(healthCheckTimeout);
        if (healthyThreshold != null)    tg.setHealthyThresholdCount(healthyThreshold);
        if (unhealthyThreshold != null)  tg.setUnhealthyThresholdCount(unhealthyThreshold);
        if (matcher != null)             tg.setMatcher(matcher);
        persistRegion(targetGroups, region);
        if (!Objects.equals(previousInterval, tg.getHealthCheckIntervalSeconds())) {
            healthChecker.startMonitoring(tg);
        }
    }

    /**
     * Puts back the health check {@code prior} holds, as a CloudFormation rollback does. A TCP check has no path or
     * matcher, so the ones a target group saved before TCP checks dropped them still holds are not put back.
     */
    public void restoreHealthCheck(String region, String arn, TargetGroup prior) {
        boolean layer7Check = isHttpProtocol(prior.getHealthCheckProtocol());
        modifyTargetGroup(region, arn, prior.getHealthCheckProtocol(), prior.getHealthCheckPort(),
                prior.getHealthCheckEnabled(), layer7Check ? prior.getHealthCheckPath() : null,
                prior.getHealthCheckIntervalSeconds(), prior.getHealthCheckTimeoutSeconds(),
                prior.getHealthyThresholdCount(), prior.getUnhealthyThresholdCount(),
                layer7Check ? prior.getMatcher() : null);
    }

    /**
     * The health check protocol a target group gets when the request names none: "For Application Load Balancers,
     * the default is HTTP. For Network Load Balancers and Gateway Load Balancers, the default is TCP." An HTTP or
     * HTTPS target group is an Application Load Balancer's, and one whose target is an Application Load Balancer
     * supports only HTTP and HTTPS checks.
     */
    static String defaultHealthCheckProtocol(String protocol, String targetType) {
        return isHttpProtocol(protocol) || "alb".equals(targetType) ? "HTTP" : "TCP";
    }

    /**
     * A health check uses HTTP, HTTPS or TCP: "The GENEVE, TLS, UDP, TCP_UDP, QUIC, and TCP_QUIC protocols are not
     * supported for health checks", "the TCP protocol is not supported for health checks if the protocol of the
     * target group is HTTP or HTTPS", and for a target type of {@code alb} "the supported health check protocols are
     * HTTP and HTTPS". A protocol outside them is refused naming the ones the target group takes, as AWS refuses a
     * TCP check of an HTTP target group: "Health check protocol 'TCP' must be one of 'HTTP, HTTPS'".
     */
    private static void validateHealthCheckProtocol(String healthCheckProtocol, String protocol, String targetType) {
        if (healthCheckProtocol == null) {
            return;
        }
        List<String> supported = isHttpProtocol(protocol) || "alb".equals(targetType)
                ? List.of("HTTP", "HTTPS") : List.of("HTTP", "HTTPS", "TCP");
        if (!supported.contains(healthCheckProtocol)) {
            throw new AwsException("ValidationError", "Health check protocol '" + healthCheckProtocol
                    + "' must be one of '" + String.join(", ", supported) + "'", 400);
        }
    }

    /**
     * {@code HealthCheckPath} and {@code Matcher} are "[HTTP/HTTPS health checks]" members, so a TCP check, which
     * only opens a connection, carries neither. {@code protocol} is the protocol of the check the request leaves.
     */
    private static void validateLayer7Members(String protocol, String healthCheckPath, String matcher) {
        if (isHttpProtocol(protocol)) {
            return;
        }
        if (healthCheckPath != null) {
            throw new AwsException("InvalidConfigurationRequest",
                    "Health check paths are not supported for " + protocol + " health checks", 400);
        }
        if (matcher != null) {
            throw new AwsException("InvalidConfigurationRequest",
                    "Health check matchers are not supported for " + protocol + " health checks", 400);
        }
    }

    /** Whether {@code protocol} is HTTP or HTTPS, the protocols whose health checks have a path and a matcher. */
    static boolean isHttpProtocol(String protocol) {
        return "HTTP".equals(protocol) || "HTTPS".equals(protocol);
    }

    private static void validateHealthCheckInterval(Integer interval) {
        if (interval != null && (interval < 5 || interval > 300)) {
            String constraint = interval < 5 ? "greater than or equal to 5" : "less than or equal to 300";
            throw new AwsException("ValidationError", "1 validation error detected: Value '" + interval
                    + "' at 'healthCheckIntervalSeconds' failed to satisfy constraint: Member must have value "
                    + constraint, 400);
        }
    }

    private static void validateHealthCheckEnabled(String targetType, Boolean enabled) {
        if (Boolean.FALSE.equals(enabled) && !"lambda".equals(targetType)) {
            throw new AwsException("InvalidConfigurationRequest",
                    "Health check enabled must be true for target groups with target type '" + targetType + "'", 400);
        }
    }

    private static void validateHealthCheckTimeout(Integer timeout) {
        if (timeout != null && (timeout < 2 || timeout > 120)) {
            String constraint = timeout < 2 ? "greater than or equal to 2" : "less than or equal to 120";
            throw new AwsException("ValidationError", "1 validation error detected: Value '" + timeout
                    + "' at 'healthCheckTimeoutSeconds' failed to satisfy constraint: Member must have value "
                    + constraint, 400);
        }
    }

    public Map<String, String> describeTargetGroupAttributes(String region, String arn) {
        TargetGroup tg = requireTargetGroup(region, arn);
        return ElbV2DefaultAttributes.merge(ElbV2DefaultAttributes.forTargetGroup(tg), tg.getAttributes());
    }

    public void modifyTargetGroupAttributes(String region, String arn, Map<String, String> newAttrs) {
        TargetGroup tg = requireTargetGroup(region, arn);
        tg.getAttributes().putAll(newAttrs);
        persistRegion(targetGroups, region);
    }

    /** Puts back the values a rolled-back update replaced; a key that had no stored value is removed again. */
    public void restoreTargetGroupAttributes(String region, String arn, Map<String, String> previous) {
        TargetGroup tg = requireTargetGroup(region, arn);
        previous.forEach((key, value) -> {
            if (value == null) {
                tg.getAttributes().remove(key);
            } else {
                tg.getAttributes().put(key, value);
            }
        });
        persistRegion(targetGroups, region);
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    public Listener createListener(String region, String lbArn, String protocol, Integer port,
                                    String sslPolicy, List<String> certificates,
                                    List<Action> defaultActions, List<String> alpnPolicy,
                                    Map<String, String> initialTags) {
        validatePort(port, "port");
        requireOneDefaultCertificate(certificates);
        requireGatewayListenerSettings(requireLoadBalancer(region, lbArn), protocol, port);

        Map<String, Listener> regionListeners = listeners.computeIfAbsent(region, k -> new ConcurrentHashMap<>());

        // check duplicate port on same LB
        boolean portExists = regionListeners.values().stream()
                .filter(l -> l.getLoadBalancerArn().equals(lbArn))
                .anyMatch(l -> Objects.equals(l.getPort(), port));
        if (portExists) {
            throw new AwsException("DuplicateListener",
                    "A listener already exists on port " + port + " for this load balancer.", 400);
        }

        // Fail fast, then again under the guard the listener is published inside: this one keeps
        // the error precedence, and only the one holding the lock is atomic with the write.
        requireListenerCertificates(region, protocol, certificates);

        LoadBalancer lb = requireLoadBalancer(region, lbArn);
        String lbType = lb.getType() != null ? lb.getType() : "application";
        String typePrefix = lbTypePrefix(lbType);
        String lbId = arnId(lbArn);
        String listenerId = randomHex16();
        String listenerArn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "listener/" + typePrefix + "/" + lb.getLoadBalancerName() + "/" + lbId + "/" + listenerId).toString();

        Listener listener = new Listener();
        listener.setListenerArn(listenerArn);
        listener.setLoadBalancerArn(lbArn);
        listener.setPort(port);
        listener.setProtocol(protocol != null ? protocol : ("gateway".equals(lbType) ? "GENEVE" : "HTTP"));
        listener.setSslPolicy(sslPolicy == null && servesCertificates(listener.getProtocol())
                ? DEFAULT_SSL_POLICY : sslPolicy);
        listener.setDefaultCertificate(defaultCertificateOf(certificates));
        listener.setDefaultActions(defaultActions != null ? new ArrayList<>(defaultActions) : new ArrayList<>());
        listener.setAlpnPolicy(alpnPolicy != null ? new ArrayList<>(alpnPolicy) : new ArrayList<>());

        Rule defaultRule = buildDefaultRule(region, listenerArn, lb, lbId, listenerId, defaultActions);
        while (true) {
            requireListenerCertificates(region, protocol, certificates);
            requireCompatibleListenerProtocol(listenerArn, port, listener.getProtocol());
            boolean applied = publishListener(listener, region, port, () -> {
                synchronized (lb) {
                    requireCanonicalLoadBalancer(region, lb);
                    requireAvailableListenerPort(regionListeners, lbArn, listenerArn, port);
                    regionListeners.put(listenerArn, listener);
                    listeners.put(region, regionListeners);
                    lbToListeners.computeIfAbsent(lbArn, ignored -> new CopyOnWriteArrayList<>()).add(listenerArn);
                    Map<String, Rule> regionRules = rules.computeIfAbsent(region, ignored -> new ConcurrentHashMap<>());
                    regionRules.put(defaultRule.getRuleArn(), defaultRule);
                    rules.put(region, regionRules);
                    listenerToRules.computeIfAbsent(listenerArn, ignored -> new CopyOnWriteArrayList<>())
                            .add(defaultRule.getRuleArn());
                    for (Action action : defaultRule.getActions()) {
                        linkTgToLb(action, lbArn);
                    }
                    if (!initialTags.isEmpty()) {
                        tags.put(listenerArn, new LinkedHashMap<>(initialTags));
                    }
                    return true;
                }
            }, operation -> supplyCertificatesHeld(certificates, () -> {
                requireListenerCertificates(region, protocol, certificates);
                return operation.get();
            }));
            if (applied) {
                break;
            }
        }
        return listener;
    }

    public List<Listener> describeListeners(String region, String lbArn, List<String> listenerArns) {
        Map<String, Listener> regionListeners = listeners.getOrDefault(region, Map.of());
        List<Listener> result = new ArrayList<>(regionListeners.values());

        if (lbArn != null && !lbArn.isEmpty()) {
            requireLoadBalancer(region, lbArn);
            result = result.stream()
                    .filter(l -> l.getLoadBalancerArn().equals(lbArn))
                    .collect(Collectors.toList());
        }
        if (listenerArns != null && !listenerArns.isEmpty()) {
            Set<String> arnSet = new HashSet<>(listenerArns);
            for (String arn : arnSet) {
                requireListener(region, arn);
            }
            result = result.stream().filter(l -> arnSet.contains(l.getListenerArn())).collect(Collectors.toList());
        }
        return result;
    }

    public Map<String, String> describeListenerAttributes(String region, String arn) {
        Listener listener = requireListener(region, arn);
        return new LinkedHashMap<>(listener.getAttributes());
    }

    public void modifyListenerAttributes(String region, String arn, Map<String, String> newAttrs) {
        Listener listener = requireListener(region, arn);
        validateListenerAttributes(newAttrs);
        listener.getAttributes().putAll(newAttrs);
        persistRegion(listeners, region);
    }

    private void validateListenerAttributes(Map<String, String> newAttrs) {
        if (newAttrs == null) {
            return;
        }
        for (Map.Entry<String, String> entry : newAttrs.entrySet()) {
            if (ElbV2DataPlane.TCP_IDLE_TIMEOUT_ATTRIBUTE.equals(entry.getKey())) {
                String value = entry.getValue();
                long seconds = -1;
                try {
                    seconds = Long.parseLong(value != null ? value.trim() : "");
                } catch (NumberFormatException ignored) {
                    // Handled by range check below
                }
                if (seconds < ElbV2DataPlane.MIN_TCP_IDLE_TIMEOUT_SECONDS
                        || seconds > ElbV2DataPlane.MAX_TCP_IDLE_TIMEOUT_SECONDS) {
                    throw new AwsException("InvalidConfigurationRequest",
                            "The attribute 'tcp.idle_timeout.seconds' must be between 60 and 6000", 400);
                }
            }
        }
    }

    public void deleteListener(String region, String listenerArn) {
        while (true) {
            Map<String, Listener> regionListeners = listeners.get(region);
            Listener listener = regionListeners == null ? null : regionListeners.get(listenerArn);
            if (listener == null) {
                return;
            }
            ListenerState expected = listenerState(listener);
            LoadBalancer lb = loadBalancerOf(region, listener);
            BooleanSupplier removal = () -> {
                if (!isCurrentListener(region, expected)) {
                    return false;
                }
                regionListeners.remove(listenerArn, listener);
                removeFromIndex(lbToListeners, listener.getLoadBalancerArn(), listenerArn);
                Map<String, Rule> regionRules = rules.computeIfAbsent(region, ignored -> new ConcurrentHashMap<>());
                List<String> ruleArns = listenerToRules.remove(listenerArn);
                if (ruleArns != null) {
                    for (String ruleArn : ruleArns) {
                        regionRules.remove(ruleArn);
                        tags.remove(ruleArn);
                    }
                }
                listeners.put(region, regionListeners);
                rules.put(region, regionRules);
                tags.remove(listenerArn);
                unlinkUnreferencedTargetGroups(region, listener.getLoadBalancerArn());
                return true;
            };
            boolean removed = dataPlane.removeListener(listenerArn, expected.port(),
                    () -> underLoadBalancerLock(lb, removal::getAsBoolean));
            if (removed) {
                return;
            }
        }
    }

    public Listener modifyListener(String region, String listenerArn, String protocol, Integer port,
                                    String sslPolicy, List<String> certificates,
                                    List<Action> defaultActions, List<String> alpnPolicy) {
        validatePort(port, "port");
        requireOneDefaultCertificate(certificates);
        while (true) {
            Listener initial = requireListener(region, listenerArn);
            LoadBalancer initialLb = requireLoadBalancer(region, initial.getLoadBalancerArn());
            requireGatewayListenerSettings(initialLb, protocol, port);
            ListenerState initialState = listenerState(initial, initialLb);
            requireAvailableListenerPort(listeners.getOrDefault(region, Map.of()), initial.getLoadBalancerArn(),
                    listenerArn, port != null ? port : initialState.port());
            if (validateExistingListener(region, initialState, initialLb, () -> {
                requireListenerProtocolTransition(initialState.protocol(), protocol, sslPolicy, certificates);
                requireListenerCertificates(region, protocol != null ? protocol : initialState.protocol(),
                        certificates != null ? certificates : initialState.certificates());
            })) {
                break;
            }
        }
        while (true) {
            Listener listener = requireListener(region, listenerArn);
            LoadBalancer lb = requireLoadBalancer(region, listener.getLoadBalancerArn());
            ListenerState expected = listenerState(listener, lb);
            Listener prospective = prospectiveListener(expected, protocol, port,
                    defaultCertificateOf(certificates), null);
            boolean clearSecuritySettings = clearsSecuritySettings(expected.protocol(), protocol);
            requireAvailableListenerPort(listeners.getOrDefault(region, Map.of()), listener.getLoadBalancerArn(),
                    listenerArn, prospective.getPort());
            if (!validateExistingListener(region, expected, lb, () -> {
                requireListenerProtocolTransition(expected.protocol(), protocol, sslPolicy, certificates);
                requireListenerCertificates(region, prospective.getProtocol(), prospective.servedCertificates());
                requireCompatibleListenerProtocol(listenerArn, prospective.getPort(), prospective.getProtocol());
            })) {
                continue;
            }
            BooleanSupplier publication = () -> {
                synchronized (lb) {
                    requireCanonicalLoadBalancer(region, lb);
                    if (!isCurrentListener(region, expected)) {
                        return false;
                    }
                    requireAvailableListenerPort(listeners.getOrDefault(region, Map.of()),
                            listener.getLoadBalancerArn(), listenerArn, prospective.getPort());
                    if (port != null) {
                        listener.setPort(port);
                    }
                    if (protocol != null) {
                        listener.setProtocol(protocol);
                    }
                    if (clearSecuritySettings) {
                        listener.setSslPolicy(null);
                    } else if (sslPolicy != null) {
                        listener.setSslPolicy(sslPolicy);
                    }
                    if (certificates != null || clearSecuritySettings) {
                        // A default the certificate list holds as well is not counted while it is
                        // the default, so replacing it starts counting it and the ceiling has to be
                        // asked about, which is why this operation models TooManyCertificates too.
                        requireCertificateQuota(region, expected, prospective.getDefaultCertificate(),
                                prospective.getCertificateList());
                        listener.setDefaultCertificate(prospective.getDefaultCertificate());
                        listener.setCertificateList(new ArrayList<>(prospective.getCertificateList()));
                    }
                    if (alpnPolicy != null) {
                        listener.setAlpnPolicy(new ArrayList<>(alpnPolicy));
                    }
                    if (defaultActions != null) {
                        listener.setDefaultActions(new ArrayList<>(defaultActions));
                        listenerToRules.getOrDefault(listenerArn, List.of()).stream()
                                .map(arn -> rules.getOrDefault(region, Map.of()).get(arn))
                                .filter(rule -> rule != null && rule.isDefault())
                                .forEach(rule -> rule.setActions(new ArrayList<>(defaultActions)));
                        for (Action action : defaultActions) {
                            linkTgToLb(action, listener.getLoadBalancerArn());
                        }
                        unlinkUnreferencedTargetGroups(region, listener.getLoadBalancerArn());
                    }
                    persistRegion(listeners, region);
                    persistRegion(rules, region);
                    return true;
                }
            };
            boolean restart = port != null || protocol != null || certificates != null;
            boolean applied = restart
                    ? publishExistingListener(prospective, region, expected, lb,
                            () -> requireCertificates(region, prospective.getProtocol(),
                                    certificates != null ? certificates : prospective.servedCertificates()), publication)
                    : supplyCertificatesHeld(prospective.servedCertificates(), () -> {
                        synchronized (lb) {
                            if (!isCurrentListener(region, expected)) {
                                return false;
                            }
                        }
                        requireCertificates(region, prospective.getProtocol(), prospective.servedCertificates());
                        requirePublishedCertificates(region, prospective.getProtocol(), prospective.servedCertificates());
                        return dataPlane.updateListener(expected.port(), publication);
                    });
            if (applied) {
                break;
            }
        }
        if (defaultActions != null) {
            dataPlane.recompileRules(listenerArn, () -> getListenerRules(region, listenerArn));
        }
        return requireListener(region, listenerArn);
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    public Rule createRule(String region, String listenerArn, List<RuleCondition> conditions,
                            int priority, List<Action> actions, Map<String, String> initialTags) {
        requireListener(region, listenerArn);
        if (priority < 1 || priority > 50000) {
            throw new AwsException("ValidationError", "Priority must be between 1 and 50000.", 400);
        }

        Map<String, Rule> regionRules = rules.computeIfAbsent(region, k -> new ConcurrentHashMap<>());
        List<String> existingRuleArns = listenerToRules.getOrDefault(listenerArn, List.of());
        String priorityStr = String.valueOf(priority);
        boolean priorityTaken = existingRuleArns.stream()
                .map(regionRules::get)
                .filter(Objects::nonNull)
                .anyMatch(r -> priorityStr.equals(r.getPriority()));
        if (priorityTaken) {
            throw new AwsException("PriorityInUse",
                    "The specified priority is already in use.", 400);
        }

        Listener listener = requireListener(region, listenerArn);
        LoadBalancer lb = requireLoadBalancer(region, listener.getLoadBalancerArn());
        String lbType = lb.getType() != null ? lb.getType() : "application";
        String typePrefix = lbTypePrefix(lbType);
        String lbId = arnId(listener.getLoadBalancerArn());
        String listenerId = arnId(listenerArn);
        String ruleId = randomHex16();
        String ruleArn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "listener-rule/" + typePrefix + "/" + lb.getLoadBalancerName() + "/" + lbId + "/" + listenerId + "/" + ruleId).toString();

        Rule rule = new Rule();
        rule.setRuleArn(ruleArn);
        rule.setListenerArn(listenerArn);
        rule.setPriority(priorityStr);
        rule.setConditions(conditions != null ? new ArrayList<>(conditions) : new ArrayList<>());
        rule.setActions(actions != null ? new ArrayList<>(actions) : new ArrayList<>());
        rule.setDefault(false);

        synchronized (lb) {
            regionRules.put(ruleArn, rule);
            rules.put(region, regionRules);
            listenerToRules.computeIfAbsent(listenerArn, k -> new CopyOnWriteArrayList<>()).add(ruleArn);

            // update TG → LB index for all target group actions
            for (Action a : rule.getActions()) {
                linkTgToLb(a, listener.getLoadBalancerArn());
            }
        }

        if (!initialTags.isEmpty()) {
            tags.put(ruleArn, new LinkedHashMap<>(initialTags));
        }
        dataPlane.recompileRules(listenerArn, () -> getListenerRules(region, listenerArn));
        return rule;
    }

    public List<Rule> describeRules(String region, String listenerArn, List<String> ruleArns) {
        Map<String, Rule> regionRules = rules.getOrDefault(region, Map.of());

        if (ruleArns != null && !ruleArns.isEmpty()) {
            return ruleArns.stream()
                    .map(arn -> requireRule(region, arn))
                    .collect(Collectors.toList());
        }
        if (listenerArn != null && !listenerArn.isEmpty()) {
            return listenerToRules.getOrDefault(listenerArn, List.of()).stream()
                    .map(regionRules::get)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(r -> prioritySortKey(r.getPriority())))
                    .collect(Collectors.toList());
        }
        return new ArrayList<>(regionRules.values());
    }

    public void deleteRule(String region, String ruleArn) {
        Map<String, Rule> regionRules = rules.getOrDefault(region, Map.of());
        Rule rule = regionRules.get(ruleArn);
        if (rule == null) {
            return;
        }
        if (rule.isDefault()) {
            throw new AwsException("OperationNotPermitted",
                    "The default rule for a listener cannot be deleted.", 400);
        }
        String listenerArn = rule.getListenerArn();
        Listener listener = listeners.getOrDefault(region, Map.of()).get(listenerArn);
        underLoadBalancerLock(loadBalancerOf(region, listener), () -> {
            regionRules.remove(ruleArn);
            rules.put(region, regionRules);
            removeFromIndex(listenerToRules, listenerArn, ruleArn);
            tags.remove(ruleArn);
            if (listener != null) {
                unlinkUnreferencedTargetGroups(region, listener.getLoadBalancerArn());
            }
            return rule;
        });
        dataPlane.recompileRules(listenerArn, () -> getListenerRules(region, listenerArn));
    }

    public Rule modifyRule(String region, String ruleArn, List<RuleCondition> conditions, List<Action> actions) {
        Rule rule = requireRule(region, ruleArn);
        String listenerArn = rule.getListenerArn();
        Listener listener = listeners.getOrDefault(region, Map.of()).get(listenerArn);
        underLoadBalancerLock(loadBalancerOf(region, listener), () -> {
            if (conditions != null) {
                rule.setConditions(new ArrayList<>(conditions));
            }
            if (actions != null) {
                rule.setActions(new ArrayList<>(actions));
                if (listener != null) {
                    // As ModifyListener does for its default actions: a target group the rule now forwards to is in
                    // use by the load balancer, and one nothing routes to any more is not, so it can be deleted.
                    for (Action action : actions) {
                        linkTgToLb(action, listener.getLoadBalancerArn());
                    }
                    unlinkUnreferencedTargetGroups(region, listener.getLoadBalancerArn());
                }
            }
            persistRegion(rules, region);
            return rule;
        });
        dataPlane.recompileRules(listenerArn, () -> getListenerRules(region, listenerArn));
        return rule;
    }

    /** The load balancer {@code listener} belongs to; null when the listener or its load balancer is gone. */
    private LoadBalancer loadBalancerOf(String region, Listener listener) {
        return listener == null ? null : loadBalancers.getOrDefault(region, Map.of()).get(listener.getLoadBalancerArn());
    }

    /**
     * Runs {@code change} holding {@code lb}'s monitor, as every change to the actions of the load balancer's
     * listeners and rules does, so that unlinking the target groups nothing routes to any more sees each rule as
     * the other changes left it; unlocked when there is no load balancer, as for a listener whose one is gone.
     */
    private <T> T underLoadBalancerLock(LoadBalancer lb, Supplier<T> change) {
        if (lb == null) {
            return change.get();
        }
        synchronized (lb) {
            return change.get();
        }
    }

    public void setRulePriorities(String region, Map<String, Integer> arnToPriority) {
        Map<String, Rule> regionRules = rules.getOrDefault(region, Map.of());

        // validate all rules exist and are not default before touching anything
        for (Map.Entry<String, Integer> e : arnToPriority.entrySet()) {
            Rule rule = regionRules.get(e.getKey());
            if (rule == null) {
                throw new AwsException("RuleNotFound", "Rule not found: " + e.getKey(), 400);
            }
            if (rule.isDefault()) {
                throw new AwsException("OperationNotPermitted", "Cannot change priority of the default rule.", 400);
            }
            int p = e.getValue();
            if (p < 1 || p > 50000) {
                throw new AwsException("ValidationError", "Priority must be between 1 and 50000.", 400);
            }
        }

        // priorities are unique per listener, so collisions are only checked among rules of the same listener
        Map<String, Set<Integer>> newPrioritiesByListener = new HashMap<>();
        for (Map.Entry<String, Integer> e : arnToPriority.entrySet()) {
            String listenerArn = regionRules.get(e.getKey()).getListenerArn();
            if (!newPrioritiesByListener.computeIfAbsent(listenerArn, k -> new HashSet<>()).add(e.getValue())) {
                throw new AwsException("PriorityInUse",
                        "Priority " + e.getValue() + " is already in use.", 400);
            }
        }
        // iterate the concurrent region map, not the listener index lists, which CreateRule/DeleteRule mutate
        for (Rule existing : regionRules.values()) {
            if (existing.isDefault() || arnToPriority.containsKey(existing.getRuleArn())) {
                continue;
            }
            Set<Integer> newPriorities = newPrioritiesByListener.get(existing.getListenerArn());
            if (newPriorities == null) {
                continue;
            }
            int existingPriority = Integer.parseInt(existing.getPriority());
            if (newPriorities.contains(existingPriority)) {
                throw new AwsException("PriorityInUse",
                        "Priority " + existingPriority + " is already in use.", 400);
            }
        }

        // commit
        arnToPriority.forEach((arn, priority) -> regionRules.get(arn).setPriority(String.valueOf(priority)));
        rules.put(region, regionRules);

        Set<String> affectedListeners = arnToPriority.keySet().stream()
                .map(arn -> regionRules.get(arn).getListenerArn())
                .collect(Collectors.toSet());
        affectedListeners.forEach(la -> dataPlane.recompileRules(la, () -> getListenerRules(region, la)));
    }

    // ── Targets ───────────────────────────────────────────────────────────────

    public void registerTargets(String region, String tgArn, List<TargetDescription> targets) {
        validateTargetPorts(targets);
        TargetGroup tg = requireTargetGroup(region, tgArn);
        validateLambdaTargetPorts(tg, targets);
        if (!"lambda".equals(tg.getTargetType())) {
            for (TargetDescription t : targets) {
                requireNotMetadataAddress(t.getId());
            }
        }
        List<TargetDescription> existing = tg.getTargets();
        for (TargetDescription t : targets) {
            if (!"lambda".equals(tg.getTargetType()) && t.getPort() == null && tg.getPort() != null) {
                t.setPort(tg.getPort());
            }
            // replace if same id+port already registered
            existing.removeIf(e -> ElbV2HealthChecker.sameTarget(e, t, tg));
            existing.add(t);
        }
        persistRegion(targetGroups, region);
        healthChecker.addTargets(tgArn, targets, tg);
    }

    private static void requireNotMetadataAddress(String targetId) {
        if (!ElbV2TargetResolver.isIpLiteral(targetId)) {
            return;
        }
        try {
            SsrfProtection.rejectMetadataAddresses(InetAddress.getAllByName(targetId), targetId);
        } catch (IOException e) {
            throw new AwsException("InvalidTarget",
                    "The IP address '" + targetId + "' is not a valid target", 400);
        }
    }

    public void deregisterTargets(String region, String tgArn, List<TargetDescription> targets) {
        validateTargetPorts(targets);
        TargetGroup tg = requireTargetGroup(region, tgArn);
        validateLambdaTargetPorts(tg, targets);
        for (TargetDescription t : targets) {
            tg.getTargets().removeIf(e -> ElbV2HealthChecker.sameTarget(e, t, tg));
        }
        persistRegion(targetGroups, region);
        healthChecker.removeTargets(tgArn, targets, tg);
    }

    public List<TargetHealth> describeTargetHealth(String region, String tgArn,
                                                     List<TargetDescription> filterTargets) {
        validateTargetPorts(filterTargets);
        TargetGroup tg = requireTargetGroup(region, tgArn);
        validateLambdaTargetPorts(tg, filterTargets);
        boolean hasFilterTargets = filterTargets != null && !filterTargets.isEmpty();
        List<TargetDescription> candidates = hasFilterTargets ? filterTargets : tg.getTargets();

        boolean isLambdaTg = "lambda".equals(tg.getTargetType());
        return candidates.stream().map(t -> {
            TargetHealth th = new TargetHealth();
            th.setTarget(t);
            if (isLambdaTg) {
                th.setHealthCheckPort("N/A");
                if (Boolean.FALSE.equals(tg.getHealthCheckEnabled())) {
                    th.setState("unavailable");
                    th.setReason("Target.HealthCheckDisabled");
                    th.setDescription("Health checks are disabled for the target group");
                } else {
                    th.setState("healthy");
                }
                return th;
            }
            int port = ElbV2HealthChecker.effectivePort(t, tg);
            th.setHealthCheckPort(String.valueOf(ElbV2HealthChecker.healthCheckPort(tg, port)));
            if (hasFilterTargets && !isRegisteredTarget(tg, t)) {
                th.setState("unused");
                th.setReason("Target.NotRegistered");
                th.setDescription("Target is not registered to the target group");
                return th;
            }
            ElbV2HealthChecker.TargetHealthStatus health = healthChecker.getHealth(tgArn, t.getId(), port);
            th.setState(health.state());
            th.setReason(health.reason());
            th.setDescription(health.description());
            return th;
        }).collect(Collectors.toList());
    }

    private static boolean isRegisteredTarget(TargetGroup targetGroup, TargetDescription candidate) {
        return targetGroup.getTargets().stream()
                .anyMatch(registered -> ElbV2HealthChecker.sameTarget(registered, candidate, targetGroup));
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    /**
     * Gives each resource a new map holding its tags and {@code newTags}. Tag calls run one at a time, so two of them
     * never lose one's tags, and a lookup reads one whole set.
     */
    public synchronized void addTags(List<String> resourceArns, Map<String, String> newTags) {
        for (String arn : resourceArns) {
            Map<String, String> merged = new LinkedHashMap<>(tags.getOrDefault(arn, Map.of()));
            merged.putAll(newTags);
            tags.put(arn, merged);
        }
    }

    /** Gives each resource holding tags a new map without {@code tagKeys}, one tag call at a time. */
    public synchronized void removeTags(List<String> resourceArns, List<String> tagKeys) {
        for (String arn : resourceArns) {
            Map<String, String> resourceTags = tags.get(arn);
            if (resourceTags != null) {
                Map<String, String> remaining = new LinkedHashMap<>(resourceTags);
                tagKeys.forEach(remaining::remove);
                tags.put(arn, remaining);
            }
        }
    }

    /**
     * The tags of the load balancer, target group, listener or rule whose ARN is {@code arn}, in {@code account}, read
     * in place under the ARN they are kept under, for IAM's lookup of stored tags. A resource's tags go with it when it
     * is deleted, so a deleted resource's ARN reads none.
     */
    public Map<String, String> tagsInAccount(String account, String arn) {
        if (tagStore == null) {
            return tags.getOrDefault(arn, Map.of());
        }
        return AccountAwareStorageBackend.readInAccount(tagStore, account, arn).orElse(Map.of());
    }

    public Map<String, Map<String, String>> describeTags(List<String> resourceArns) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (String arn : resourceArns) {
            result.put(arn, tags.getOrDefault(arn, Map.of()));
        }
        return result;
    }

    // ── Listener Certificates ─────────────────────────────────────────────────

    @Override
    public List<Reference> serverCertificateReferences() {
        List<Reference> references = new ArrayList<>();
        for (Map<String, Listener> regionListeners : listeners.values()) {
            for (Listener listener : regionListeners.values()) {
                for (String certificate : listener.servedCertificates()) {
                    references.add(new Reference(certificate, "listener " + listener.getListenerArn()));
                }
            }
        }
        return references;
    }

    /**
     * The certificate list as the add leaves it, which is what the operation answers with: "the
     * certificates in the certificate list", rather than the certificates the call named.
     *
     * <p>The reference's member says one certificate per call, and the Application Load Balancers
     * guide adds three in one call of its own, so a list is taken.
     */
    public List<String> addListenerCertificates(String region, String listenerArn, List<String> certArns) {
        Listener initial = requireListener(region, listenerArn);
        requireCertificates(region, initial.getProtocol(), certArns);
        return changeListenerCertificates(region, listenerArn, certArns, false);
    }

    public void removeListenerCertificates(String region, String listenerArn, List<String> certArns) {
        changeListenerCertificates(region, listenerArn, certArns, true);
    }

    private List<String> changeListenerCertificates(String region, String listenerArn,
                                                    List<String> requested, boolean remove) {
        while (true) {
            Listener listener = requireListener(region, listenerArn);
            LoadBalancer lb = requireLoadBalancer(region, listener.getLoadBalancerArn());
            ListenerState expected = listenerState(listener, lb);
            List<String> certificates = new ArrayList<>(expected.certificateList());
            boolean namesTheDefault = remove && expected.defaultCertificate() != null
                    && requested.contains(expected.defaultCertificate());
            if (remove) {
                if (servesCertificates(expected.protocol()) && namesTheDefault) {
                    throw new AwsException("OperationNotPermitted",
                            "The default listener certificate cannot be removed.", 400);
                }
                certificates.removeAll(requested);
            } else {
                if (!validateExistingListener(region, expected, lb,
                        () -> requireCertificates(region, expected.protocol(), requested))) {
                    continue;
                }
                for (String arn : requested) {
                    if (!certificates.contains(arn)) {
                        certificates.add(arn);
                    }
                }
                requireCertificateQuota(region, expected, certificates);
            }
            Listener prospective = prospectiveListener(expected, null, null, null, certificates);
            if (namesTheDefault) {
                prospective.setDefaultCertificate(null);
            }
            if (publishExistingListener(prospective, region, expected, lb, () -> {
                if (!remove) {
                    requireCertificates(region, expected.protocol(), requested);
                    requireCertificateQuota(region, expected, certificates);
                }
            }, () -> {
                synchronized (lb) {
                    requireCanonicalLoadBalancer(region, lb);
                    if (!isCurrentListener(region, expected)) {
                        return false;
                    }
                    if (!remove) {
                        // The ceiling belongs to the load balancer rather than to the listener, so
                        // it is counted here as well, under the lock every certificate write takes:
                        // two adds on sibling listeners would otherwise each count the other's list
                        // as it was before the other wrote and pass the ceiling together.
                        requireCertificateQuota(region, expected, certificates);
                    }
                    listener.setCertificateList(certificates);
                    if (namesTheDefault) {
                        // Only a listener of a protocol the member does not describe reaches here,
                        // the throw above refusing the removal otherwise, and it keeps the
                        // behaviour this service had before the default was held apart.
                        listener.setDefaultCertificate(null);
                    }
                    persistRegion(listeners, region);
                    return true;
                }
            })) {
                return certificates;
            }
        }
    }

    /**
     * The quota the reference publishes is per load balancer and excludes the default certificates,
     * so a listener's own default is not counted where its certificate list holds it as well, and
     * the listeners of one load balancer share the ceiling. The default the count is taken against
     * is the one the write leaves behind rather than the one the listener holds now, since
     * replacing a default the certificate list also holds starts counting it. The number in hand is
     * the Application Load Balancer one; a TLS listener on a Network Load Balancer is held to the
     * same ceiling, which is Floci's reading rather than a sentence of its own.
     */
    private void requireCertificateQuota(String region, ListenerState expected, List<String> certificateList) {
        requireCertificateQuota(region, expected, expected.defaultCertificate(), certificateList);
    }

    private void requireCertificateQuota(String region, ListenerState expected, String defaultCertificate,
                                         List<String> certificateList) {
        String loadBalancerArn = expected.listener().getLoadBalancerArn();
        String listenerArn = expected.listener().getListenerArn();
        int held = countedCertificates(defaultCertificate, certificateList);
        for (Listener other : listeners.getOrDefault(region, Map.of()).values()) {
            if (loadBalancerArn.equals(other.getLoadBalancerArn())
                    && !listenerArn.equals(other.getListenerArn())) {
                held += countedCertificates(other.getDefaultCertificate(), other.getCertificateList());
            }
        }
        if (held > CERTIFICATES_PER_LOAD_BALANCER) {
            throw new AwsException("TooManyCertificates",
                    "You've reached the limit on the number of certificates per load balancer.", 400);
        }
    }

    private static int countedCertificates(String defaultCertificate, List<String> certificateList) {
        return (int) certificateList.stream().filter(arn -> !arn.equals(defaultCertificate)).count();
    }

    /**
     * The default certificate and then the certificate list, a default the list holds as well
     * named twice, "once with IsDefault set to true and once with IsDefault set to false".
     */
    public List<ListenerCertificate> describeListenerCertificates(String region, String listenerArn) {
        Listener listener = requireListener(region, listenerArn);
        List<ListenerCertificate> certificates = new ArrayList<>();
        if (listener.getDefaultCertificate() != null) {
            certificates.add(new ListenerCertificate(listener.getDefaultCertificate(), true));
        }
        for (String certificate : listener.getCertificateList()) {
            certificates.add(new ListenerCertificate(certificate, false));
        }
        return certificates;
    }

    /** A certificate {@code DescribeListenerCertificates} names, and the part it plays. */
    public record ListenerCertificate(String certificateArn, boolean isDefault) {}

    /**
     * The member both create and modify read as the listener's default certificate, where the
     * reference says "You must provide exactly one certificate." No source gives the code a longer
     * list is answered with, so it is the {@code ValidationError} this service answers a request
     * shape with, which is Floci's reading.
     */
    private static void requireOneDefaultCertificate(List<String> certificates) {
        if (certificates != null && certificates.size() > 1) {
            throw new AwsException("ValidationError",
                    "You must provide exactly one certificate.", 400);
        }
    }

    private static String defaultCertificateOf(List<String> certificates) {
        return certificates == null || certificates.isEmpty() ? null : certificates.getFirst();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private record ListenerState(Listener listener, Integer port, String protocol,
                                 String defaultCertificate, List<String> certificateList,
                                 List<String> certificates) {}

    private static ListenerState listenerState(Listener listener) {
        return new ListenerState(listener, listener.getPort(), listener.getProtocol(),
                listener.getDefaultCertificate(), new ArrayList<>(listener.getCertificateList()),
                listener.servedCertificates());
    }

    private static ListenerState listenerState(Listener listener, LoadBalancer lb) {
        synchronized (lb) {
            return listenerState(listener);
        }
    }

    private boolean isCurrentListener(String region, ListenerState expected) {
        Listener current = listeners.getOrDefault(region, Map.of()).get(expected.listener().getListenerArn());
        return current == expected.listener() && Objects.equals(current.getPort(), expected.port())
                && Objects.equals(current.getProtocol(), expected.protocol())
                && Objects.equals(current.getDefaultCertificate(), expected.defaultCertificate())
                && current.getCertificateList().equals(expected.certificateList());
    }

    private static Listener prospectiveListener(ListenerState expected, String protocol, Integer port,
                                                String defaultCertificate, List<String> certificateList) {
        Listener prospective = new Listener();
        prospective.setListenerArn(expected.listener().getListenerArn());
        prospective.setLoadBalancerArn(expected.listener().getLoadBalancerArn());
        prospective.setPort(port != null ? port : expected.port());
        prospective.setProtocol(protocol != null ? protocol : expected.protocol());
        if (!clearsSecuritySettings(expected.protocol(), protocol)) {
            prospective.setDefaultCertificate(defaultCertificate != null ? defaultCertificate
                    : expected.defaultCertificate());
            prospective.setCertificateList(new ArrayList<>(certificateList != null ? certificateList
                    : expected.certificateList()));
        }
        return prospective;
    }

    private static boolean clearsSecuritySettings(String currentProtocol, String newProtocol) {
        return ("HTTPS".equalsIgnoreCase(currentProtocol) && "HTTP".equalsIgnoreCase(newProtocol))
                || ("TLS".equalsIgnoreCase(currentProtocol) && "TCP".equalsIgnoreCase(newProtocol));
    }

    private static void requireListenerProtocolTransition(String currentProtocol, String newProtocol,
                                                          String sslPolicy, List<String> certificates) {
        boolean requiresSecuritySettings =
                ("HTTP".equalsIgnoreCase(currentProtocol) && "HTTPS".equalsIgnoreCase(newProtocol))
                || ("TCP".equalsIgnoreCase(currentProtocol) && "TLS".equalsIgnoreCase(newProtocol));
        if (!requiresSecuritySettings) {
            return;
        }
        if (certificates == null || certificates.isEmpty()) {
            throw new AwsException("InvalidConfigurationRequest",
                    "A default certificate is required when changing the listener protocol to " + newProtocol + ".", 400);
        }
        if (sslPolicy == null) {
            throw new AwsException("InvalidConfigurationRequest",
                    "An SSL policy is required when changing the listener protocol to " + newProtocol + ".", 400);
        }
    }

    private void requireCanonicalLoadBalancer(String region, LoadBalancer lb) {
        if (loadBalancers.getOrDefault(region, Map.of()).get(lb.getLoadBalancerArn()) != lb) {
            throw new AwsException("LoadBalancerNotFound", "One or more load balancers not found.", 400);
        }
    }

    private static void requireAvailableListenerPort(Map<String, Listener> regionListeners, String lbArn,
                                                     String listenerArn, Integer port) {
        if (regionListeners.values().stream().anyMatch(listener -> lbArn.equals(listener.getLoadBalancerArn())
                && !listenerArn.equals(listener.getListenerArn()) && Objects.equals(listener.getPort(), port))) {
            throw new AwsException("DuplicateListener",
                    "A listener already exists on port " + port + " for this load balancer.", 400);
        }
    }

    private static void requireGatewayListenerSettings(LoadBalancer loadBalancer, String protocol, Integer port) {
        if (port != null && "gateway".equals(loadBalancer.getType())) {
            throw new AwsException("InvalidConfigurationRequest",
                    "You can't specify a port for a Gateway Load Balancer.", 400);
        }
        if (protocol != null && "gateway".equals(loadBalancer.getType())) {
            throw new AwsException("InvalidConfigurationRequest",
                    "You can't specify a protocol for a Gateway Load Balancer.", 400);
        }
    }

    private void requireCompatibleListenerProtocol(String listenerArn, Integer port, String protocol) {
        if (port == null) {
            return;
        }
        try {
            dataPlane.requireCompatibleListenerProtocol(listenerArn, port, protocol);
        } catch (ElbV2DataPlane.IncompatibleListenerProtocolException conflict) {
            throw new ListenerProtocolConflict(conflict);
        }
    }

    private boolean publishListener(Listener prospective, String region, Integer previousPort,
                                    BooleanSupplier publication, Function<Supplier<Boolean>, Boolean> admission) {
        try {
            return dataPlane.publishListener(prospective, region, previousPort, publication,
                    () -> getListenerRules(region, prospective.getListenerArn()), admission);
        } catch (ElbV2DataPlane.IncompatibleListenerProtocolException conflict) {
            throw new ListenerProtocolConflict(conflict);
        }
    }

    private static final class ListenerProtocolConflict extends AwsException {
        private ListenerProtocolConflict(ElbV2DataPlane.IncompatibleListenerProtocolException conflict) {
            super("InvalidConfigurationRequest", conflict.getMessage(), 400);
        }
    }

    private boolean staleListenerPreparation(String region, ListenerState expected, LoadBalancer lb,
                                             AwsException failure) {
        if (!(failure instanceof ListenerProtocolConflict) && !"CertificateNotFound".equals(failure.getErrorCode())) {
            return false;
        }
        synchronized (lb) {
            return !isCurrentListener(region, expected)
                    || loadBalancers.getOrDefault(region, Map.of()).get(lb.getLoadBalancerArn()) != lb;
        }
    }

    private boolean validateExistingListener(String region, ListenerState expected, LoadBalancer lb,
                                             Runnable validation) {
        try {
            validation.run();
            return true;
        } catch (AwsException failure) {
            if (staleListenerPreparation(region, expected, lb, failure)) {
                return false;
            }
            throw failure;
        }
    }

    private boolean publishExistingListener(Listener prospective, String region, ListenerState expected,
                                            LoadBalancer lb, Runnable referenceValidation, BooleanSupplier publication) {
        try {
            return publishListener(prospective, region, expected.port(), publication,
                    operation -> supplyCertificatesHeld(prospective.servedCertificates(), () -> {
                        synchronized (lb) {
                            if (!isCurrentListener(region, expected)
                                    || loadBalancers.getOrDefault(region, Map.of()).get(lb.getLoadBalancerArn()) != lb) {
                                return false;
                            }
                        }
                        referenceValidation.run();
                        requirePublishedCertificates(region, prospective.getProtocol(), prospective.servedCertificates());
                        return operation.get();
                    }));
        } catch (AwsException failure) {
            if (staleListenerPreparation(region, expected, lb, failure)) {
                return false;
            }
            throw failure;
        }
    }

    private void restoreListener(String region, String listenerArn) {
        while (true) {
            Listener listener = listeners.getOrDefault(region, Map.of()).get(listenerArn);
            if (listener == null) {
                return;
            }
            LoadBalancer lb = loadBalancerOf(region, listener);
            if (lb == null) {
                return;
            }
            ListenerState expected = listenerState(listener, lb);
            if (publishExistingListener(prospectiveListener(expected, null, null, null, null), region, expected, lb,
                    () -> { }, () -> {
                synchronized (lb) {
                    return loadBalancers.getOrDefault(region, Map.of()).get(lb.getLoadBalancerArn()) == lb
                            && isCurrentListener(region, expected);
                }
            })) {
                return;
            }
        }
    }

    private void requirePublishedCertificates(String region, String protocol, List<String> certificateArns) {
        if (certificateResolver != null && certificateArns != null && servesCertificates(protocol)) {
            for (String arn : certificateArns) {
                if (arn != null && AwsArnUtils.isArn(arn) && "acm".equals(AwsArnUtils.parse(arn).service())
                        && !certificateResolver.isUsableAcmCertificate(region, arn)) {
                    throw new AwsException("CertificateNotFound", "The specified certificate is not available for use.", 400);
                }
            }
        }
        if ("HTTPS".equalsIgnoreCase(protocol) && (certificateArns == null || certificateArns.isEmpty())) {
            throw new AwsException("InvalidConfigurationRequest",
                    "An HTTPS listener requires a default certificate.", 400);
        }
    }

    /**
     * Refuses a certificate the account does not have. {@code CertificateNotFound} is modeled on
     * {@code CreateListener}, {@code ModifyListener} and {@code AddListenerCertificates} alone,
     * and the reference gives it one sentence, so that sentence is the message rather than one
     * naming the ARN.
     *
     * <p>Only for the listener types the member belongs to. The reference marks {@code
     * Certificates} "[HTTPS and TLS listeners]" and the user guide scopes the certificate list to
     * an HTTPS listener, so a certificate on a listener of any other protocol is a configuration
     * AWS does not describe, and what it answers there is not {@code CertificateNotFound} on any
     * evidence here. Such a request keeps the behaviour it had rather than being refused with a
     * code read off the wrong case.
     *
     * <p>A blank entry is refused like any other. {@code Certificates} is a list of the
     * certificates the listener serves, and an empty value in it names one no better than a value
     * that is no ARN at all, so letting it through would store a certificate the listener cannot
     * serve.
     */
    private void requireCertificates(String region, String protocol,
                                     List<String> certificateArns) {
        if (certificateResolver == null || certificateArns == null || !servesCertificates(protocol)) {
            return;
        }
        for (String certificateArn : certificateArns) {
            if (certificateArn == null || certificateArn.isBlank()
                    || !certificateResolver.exists(region, certificateArn)) {
                throw new AwsException("CertificateNotFound",
                        "The specified certificate does not exist.", 400);
            }
        }
    }

    private void requireListenerCertificates(String region, String protocol, List<String> certificateArns) {
        requireCertificates(region, protocol, certificateArns);
        if ("HTTPS".equalsIgnoreCase(protocol) && (certificateArns == null || certificateArns.isEmpty())) {
            throw new AwsException("InvalidConfigurationRequest",
                    "An HTTPS listener requires a default certificate.", 400);
        }
        requirePublishedCertificates(region, protocol, certificateArns);
    }

    /** The listener protocols a certificate belongs to, per the reference's own bracket. */
    private static boolean servesCertificates(String protocol) {
        return "HTTPS".equalsIgnoreCase(protocol) || "TLS".equalsIgnoreCase(protocol);
    }

    private <T> T supplyCertificatesHeld(List<String> certificateArns, Supplier<T> action) {
        return certificateResolver == null ? action.get() : certificateResolver.supplyWhileHeld(certificateArns, action);
    }

    public List<CertificateReference> acmCertificateReferences() {
        List<CertificateReference> references = new ArrayList<>();
        for (Map<String, Listener> regionListeners : listeners.values()) {
            for (Listener listener : regionListeners.values()) {
                for (String certificateArn : listener.servedCertificates()) {
                    references.add(new CertificateReference(certificateArn, listener.getLoadBalancerArn()));
                }
            }
        }
        return references;
    }

    public void certificateReimported(String certificateArn, String region) {
        for (Listener listener : listeners.getOrDefault(region, Map.of()).values()) {
            if (listener.servedCertificates().contains(certificateArn)) {
                try {
                    restoreListener(region, listener.getListenerArn());
                } catch (RuntimeException failure) {
                    LOG.warnv("ELBv2 certificate refresh for listener {0} failed ({1}: {2}); its configuration is retained",
                            listener.getListenerArn(), failure.getClass().getSimpleName(), failure.getMessage());
                }
            }
        }
    }

    private LoadBalancer requireLoadBalancer(String region, String arn) {
        LoadBalancer lb = loadBalancers.getOrDefault(region, Map.of()).get(arn);
        if (lb == null) {
            throw new AwsException("LoadBalancerNotFound",
                    "One or more load balancers not found.", 400);
        }
        return lb;
    }

    private List<AvailabilityZone> resolveAvailabilityZones(String region, List<String> subnetIds) {
        if (subnetIds == null || subnetIds.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Subnet> subnetsById = resolveSubnetsById(region, subnetIds);
        List<AvailabilityZone> availabilityZones = new ArrayList<>();
        for (String subnetId : subnetIds) {
            Subnet subnet = subnetsById.get(subnetId);
            AvailabilityZone availabilityZone = new AvailabilityZone();
            availabilityZone.setSubnetId(subnet.getSubnetId());
            availabilityZone.setZoneName(subnet.getAvailabilityZone());
            availabilityZones.add(availabilityZone);
        }
        return availabilityZones;
    }

    private String resolveSubnetVpcId(String region, String lbType, List<String> subnetIds) {
        return resolveSubnetVpcId(region, lbType, subnetIds, false);
    }

    private String resolveSubnetVpcId(String region, String lbType, List<String> subnetIds,
                                      boolean restoreSnapshot) {
        if (subnetIds == null || subnetIds.isEmpty()) {
            return null;
        }

        Map<String, Subnet> subnetsById = resolveSubnetsById(region, subnetIds);

        if ("application".equals(lbType)) {
            long distinctAvailabilityZones = subnetIds.stream()
                    .map(subnetsById::get)
                    .map(Subnet::getAvailabilityZone)
                    .distinct()
                    .count();
            if (distinctAvailabilityZones < minimumAlbZones(region)) {
                throw new AwsException("InvalidConfigurationRequest",
                        "Application Load Balancers must be attached to subnets in at least two Availability Zones.", 400);
            }
            if (!restoreSnapshot && distinctAvailabilityZones < subnetIds.size()) {
                throw new AwsException("InvalidConfigurationRequest",
                        "A load balancer cannot be attached to multiple subnets in the same Availability Zone", 400);
            }
        }

        Set<String> vpcIds = subnetIds.stream()
                .map(subnetsById::get)
                .map(Subnet::getVpcId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (vpcIds.size() > 1) {
            throw new AwsException("InvalidConfigurationRequest",
                    "All subnets must belong to the same VPC.", 400);
        }

        return vpcIds.iterator().next();
    }

    private Map<String, Subnet> resolveSubnetsById(String region, List<String> subnetIds) {
        List<Subnet> subnets = ec2Service.describeSubnets(region, subnetIds, Map.of());
        Map<String, Subnet> subnetsById = subnets.stream()
                .collect(Collectors.toMap(Subnet::getSubnetId, subnet -> subnet, (left, right) -> left));

        for (String subnetId : subnetIds) {
            if (!subnetsById.containsKey(subnetId)) {
                throw new AwsException("SubnetNotFound",
                        "The subnet ID '" + subnetId + "' does not exist", 400);
            }
        }
        return subnetsById;
    }
    private TargetGroup requireTargetGroup(String region, String arn) {
        TargetGroup tg = targetGroups.getOrDefault(region, Map.of()).get(arn);
        if (tg == null) {
            throw new AwsException("TargetGroupNotFound",
                    "One or more target groups not found.", 400);
        }
        return tg;
    }

    private Listener requireListener(String region, String arn) {
        Listener l = listeners.getOrDefault(region, Map.of()).get(arn);
        if (l == null) {
            throw new AwsException("ListenerNotFound",
                    "One or more listeners not found.", 400);
        }
        return l;
    }

    private Rule requireRule(String region, String arn) {
        Rule r = rules.getOrDefault(region, Map.of()).get(arn);
        if (r == null) {
            throw new AwsException("RuleNotFound", "One or more rules not found.", 400);
        }
        return r;
    }

    public TargetGroup getTargetGroup(String region, String arn) {
        return targetGroups.getOrDefault(region, Map.of()).get(arn);
    }

    public TargetGroup getTargetGroupByName(String region, String name) {
        return targetGroups.getOrDefault(region, Map.of()).values().stream()
                .filter(tg -> tg.getTargetGroupName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public void shiftListenerForward(String region, String listenerArn,
                                     String blueTgArn, String greenTgArn, int greenWeightPct) {
        Rule defaultRule = listenerToRules.getOrDefault(listenerArn, List.of()).stream()
                .map(arn -> rules.getOrDefault(region, Map.of()).get(arn))
                .filter(r -> r != null && r.isDefault())
                .findFirst()
                .orElse(null);
        if (defaultRule == null) {
            return;
        }
        Action action = new Action();
        action.setType("forward");
        if (greenWeightPct >= 100) {
            action.setTargetGroupArn(greenTgArn);
        } else {
            Action.TargetGroupTuple blueTuple = new Action.TargetGroupTuple();
            blueTuple.setTargetGroupArn(blueTgArn);
            blueTuple.setWeight(100 - greenWeightPct);
            Action.TargetGroupTuple greenTuple = new Action.TargetGroupTuple();
            greenTuple.setTargetGroupArn(greenTgArn);
            greenTuple.setWeight(greenWeightPct);
            action.setTargetGroups(List.of(blueTuple, greenTuple));
        }
        defaultRule.setActions(new ArrayList<>(List.of(action)));
        Listener listener = listeners.getOrDefault(region, Map.of()).get(listenerArn);
        if (listener != null) {
            // The listener answers with the same default action as its default rule, and the
            // target groups it now forwards to are in use; the ones it left are not.
            listener.setDefaultActions(new ArrayList<>(List.of(action)));
            linkTgToLb(action, listener.getLoadBalancerArn());
            unlinkUnreferencedTargetGroups(region, listener.getLoadBalancerArn());
            persistRegion(listeners, region);
        }
        persistRegion(rules, region);
        dataPlane.recompileRules(listenerArn, () -> getListenerRules(region, listenerArn));
    }

    private List<Rule> getListenerRules(String region, String listenerArn) {
        Map<String, Rule> regionRules = rules.getOrDefault(region, Map.of());
        return listenerToRules.getOrDefault(listenerArn, List.of()).stream()
                .map(regionRules::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(r -> {
                    if ("default".equals(r.getPriority())) return Integer.MAX_VALUE;
                    try { return Integer.parseInt(r.getPriority()); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
                }))
                .collect(Collectors.toList());
    }

    private static void validateLambdaTargetPorts(TargetGroup group, List<TargetDescription> targets) {
        if (!"lambda".equals(group.getTargetType()) || targets == null) {
            return;
        }
        for (TargetDescription target : targets) {
            if (target.getPort() != null) {
                throw new AwsException("ValidationError", "Port cannot be specified for Lambda function targets", 400);
            }
        }
    }

    private static void validateTargetPorts(List<TargetDescription> targets) {
        if (targets == null) {
            return;
        }
        List<String> violations = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            addPortViolation(violations, targets.get(i).getPort(), "targets." + (i + 1) + ".member.port");
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationError(violations);
        }
    }

    static void validatePort(Integer port, String member) {
        List<String> violations = new ArrayList<>();
        addPortViolation(violations, port, member);
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationError(violations);
        }
    }

    private static void addPortViolation(List<String> violations, Integer port, String member) {
        if (port == null) {
            return;
        }
        String violation = SmithyValidationErrors.outOfRange(member, port, 1, 65535);
        if (violation != null) {
            violations.add(violation);
        }
    }

    /** {@code HealthCheckPort} is either {@code traffic-port} or a port number from 1 to 65535. */
    private static void validateHealthCheckPort(String healthCheckPort) {
        if (healthCheckPort == null || "traffic-port".equals(healthCheckPort)) {
            return;
        }
        if (ElbV2HealthChecker.parsePort(healthCheckPort) == null) {
            throw new AwsException("ValidationError",
                    "Health check port '" + healthCheckPort + "' must be 'traffic-port' or a port number from 1 to 65535.", 400);
        }
    }

    private static void validateName(String name, String resource) {
        if (name == null || name.isEmpty()) {
            throw new AwsException("ValidationError", "Name is required for " + resource + ".", 400);
        }
        if (name.length() > 32) {
            throw new AwsException("ValidationError",
                    "Name '" + name + "' exceeds 32 characters.", 400);
        }
        if (!name.matches("[a-zA-Z0-9-]+")) {
            throw new AwsException("ValidationError",
                    "Name '" + name + "' contains invalid characters. Use alphanumeric characters and hyphens.", 400);
        }
        if (name.startsWith("-") || name.endsWith("-")) {
            throw new AwsException("ValidationError",
                    "Name '" + name + "' cannot start or end with a hyphen.", 400);
        }
    }

    private static String randomHex16() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * A load balancer stored before the hosted zones were looked up per region and type still
     * carries the one fixed zone every balancer used to report, so restored balancers take the
     * zone of their region and type again, written back so the stored copy agrees.
     */
    private void refreshCanonicalHostedZones() {
        for (StoredRegion<LoadBalancer> stored : storedRegions(loadBalancers, loadBalancerStore)) {
            String region = stored.region();
            for (LoadBalancer lb : stored.resources().values()) {
                String lbType = lb.getType() != null ? lb.getType() : "application";
                lb.setCanonicalHostedZoneId(canonicalHostedZoneId(lbType, region));
            }
            persistStoredLoadBalancers(stored);
        }
    }

    private void persistStoredLoadBalancers(StoredRegion<LoadBalancer> stored) {
        if (loadBalancerStore == null) {
            loadBalancers.put(stored.region(), stored.resources());
        } else {
            loadBalancerStore.putForAccount(stored.accountId(), stored.region(), stored.resources());
        }
    }

    /**
     * Network load balancers have their own hosted zone per region, distinct from the one
     * Application (and Classic) load balancers share; a gateway load balancer has none.
     */
    private static String canonicalHostedZoneId(String type, String region) {
        return switch (type) {
            case "network" -> AwsRegionFacts.nlbHostedZoneId(region).orElse(null);
            case "gateway" -> null;
            default -> AwsRegionFacts.albHostedZoneId(region).orElse(null);
        };
    }

    private static String lbTypePrefix(String type) {
        return switch (type) {
            case "network" -> "net";
            case "gateway" -> "gwy";
            default -> "app";
        };
    }

    // extracts the last path segment of an ARN (the random hex ID)
    private static String arnId(String arn) {
        int last = arn.lastIndexOf('/');
        return last >= 0 ? arn.substring(last + 1) : arn;
    }

    private static int prioritySortKey(String priority) {
        if ("default".equals(priority)) return Integer.MAX_VALUE;
        try { return Integer.parseInt(priority); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }

    private Rule buildDefaultRule(String region, String listenerArn, LoadBalancer lb, String lbId,
                                   String listenerId, List<Action> defaultActions) {
        String lbType = lb.getType() != null ? lb.getType() : "application";
        String typePrefix = lbTypePrefix(lbType);
        String ruleId = randomHex16();
        String ruleArn = AwsArnUtils.Arn.of("elasticloadbalancing", region, regionResolver.getAccountId(), "listener-rule/" + typePrefix + "/" + lb.getLoadBalancerName() + "/" + lbId + "/" + listenerId + "/" + ruleId).toString();

        Rule rule = new Rule();
        rule.setRuleArn(ruleArn);
        rule.setListenerArn(listenerArn);
        rule.setPriority("default");
        rule.setConditions(new ArrayList<>());
        rule.setActions(defaultActions != null ? new ArrayList<>(defaultActions) : new ArrayList<>());
        rule.setDefault(true);
        return rule;
    }

    private void linkTgToLb(Action action, String lbArn) {
        if ("forward".equals(action.getType())) {
            if (action.getTargetGroupArn() != null) {
                tgToLbs.computeIfAbsent(action.getTargetGroupArn(), k -> ConcurrentHashMap.newKeySet()).add(lbArn);
                addLoadBalancerReference(action.getTargetGroupArn(), lbArn);
            }
            for (Action.TargetGroupTuple t : action.getTargetGroups()) {
                if (t.getTargetGroupArn() != null) {
                    tgToLbs.computeIfAbsent(t.getTargetGroupArn(), k -> ConcurrentHashMap.newKeySet()).add(lbArn);
                    addLoadBalancerReference(t.getTargetGroupArn(), lbArn);
                }
            }
        }
    }

    private void addLoadBalancerReference(String targetGroupArn, String loadBalancerArn) {
        for (Map.Entry<String, Map<String, TargetGroup>> entry : targetGroups.entrySet()) {
            TargetGroup targetGroup = entry.getValue().get(targetGroupArn);
            if (targetGroup != null) {
                if (!targetGroup.getLoadBalancerArns().contains(loadBalancerArn)) {
                    targetGroup.getLoadBalancerArns().add(loadBalancerArn);
                    targetGroups.put(entry.getKey(), entry.getValue());
                }
                return;
            }
        }
    }

    /**
     * Drops {@code lbArn} from every target group that is no longer referenced by any of the
     * load balancer's remaining listeners or rules. Called after a listener or rule is removed
     * so a target group can be deleted once nothing routes to it (avoids a stale "in use" guard
     * during teardown).
     */
    private void unlinkUnreferencedTargetGroups(String region, String lbArn) {
        Set<String> referenced = new HashSet<>();
        Map<String, Listener> regionListeners = listeners.getOrDefault(region, Map.of());
        for (Listener listener : regionListeners.values()) {
            if (lbArn.equals(listener.getLoadBalancerArn())) {
                listener.getDefaultActions().forEach(a -> collectActionTargetGroups(a, referenced));
            }
        }
        for (Rule rule : rules.getOrDefault(region, Map.of()).values()) {
            Listener listener = regionListeners.get(rule.getListenerArn());
            if (listener != null && lbArn.equals(listener.getLoadBalancerArn())) {
                rule.getActions().forEach(a -> collectActionTargetGroups(a, referenced));
            }
        }
        tgToLbs.forEach((tgArn, lbSet) -> {
            if (!referenced.contains(tgArn)) {
                lbSet.remove(lbArn);
                removeLoadBalancerReference(tgArn, lbArn);
            }
        });
    }

    private void removeLoadBalancerReference(String targetGroupArn, String loadBalancerArn) {
        for (Map.Entry<String, Map<String, TargetGroup>> entry : targetGroups.entrySet()) {
            TargetGroup targetGroup = entry.getValue().get(targetGroupArn);
            if (targetGroup != null && targetGroup.getLoadBalancerArns().remove(loadBalancerArn)) {
                targetGroups.put(entry.getKey(), entry.getValue());
                return;
            }
        }
    }

    private static void collectActionTargetGroups(Action action, Set<String> out) {
        if (action.getTargetGroupArn() != null) {
            out.add(action.getTargetGroupArn());
        }
        for (Action.TargetGroupTuple t : action.getTargetGroups()) {
            if (t.getTargetGroupArn() != null) {
                out.add(t.getTargetGroupArn());
            }
        }
    }

    // ─── Resource Explorer 2 ───────────────────────────────────────────────────

    @Override
    public List<ExplorerResource> getResources() {
        List<ExplorerResource> resources = new ArrayList<>();
        for (Map<String, LoadBalancer> regionLoadBalancers : loadBalancers.values()) {
            for (LoadBalancer loadBalancer : regionLoadBalancers.values()) {
                addExplorerResource(resources, loadBalancer.getLoadBalancerArn(),
                        "elasticloadbalancing:loadbalancer", loadBalancer.getCreatedTime());
            }
        }
        for (Map<String, TargetGroup> regionTargetGroups : targetGroups.values()) {
            for (TargetGroup targetGroup : regionTargetGroups.values()) {
                addExplorerResource(resources, targetGroup.getTargetGroupArn(),
                        "elasticloadbalancing:targetgroup", null);
            }
        }
        return resources;
    }

    @Override
    public Set<SupportedResourceType> getSupportedResourceTypes() {
        return Set.of(
                new SupportedResourceType("elasticloadbalancing:loadbalancer", "elasticloadbalancing", true),
                new SupportedResourceType("elasticloadbalancing:targetgroup", "elasticloadbalancing", true));
    }

    private void addExplorerResource(List<ExplorerResource> out, String arn, String resourceType,
                                     Instant createdAt) {
        if (arn == null) {
            return;
        }
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
        out.add(new ExplorerResource(arn, resourceType, "elasticloadbalancing",
                parsed.region(), parsed.accountId(),
                createdAt != null ? createdAt : Instant.now(),
                tags.getOrDefault(arn, Map.of())));
    }
}
