package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.EpochSeconds;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.core.common.StripedLocks;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codedeploy.model.Application;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.github.hectorvent.floci.services.codedeploy.model.DeploymentConfig;
import io.github.hectorvent.floci.services.codedeploy.model.DeploymentGroup;
import io.github.hectorvent.floci.services.codedeploy.model.OnPremisesInstance;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.CreateTaskSetRequest;
import io.github.hectorvent.floci.services.ecs.model.TaskSet;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.lambda.LambdaArnUtils;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.ssm.SsmCommandService;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

@ApplicationScoped
public class CodeDeployService {

    private static final Logger LOG = Logger.getLogger(CodeDeployService.class);

    // Lambda/ECS validation hooks must call PutLifecycleEventHookExecutionStatus within one hour.
    private static final Duration DEFAULT_HOOK_CALLBACK_TIMEOUT = Duration.ofHours(1);
    private static final Duration STOP_POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration SSM_POLL_INTERVAL = Duration.ofMillis(500);

    private final LambdaService lambdaService;
    private final EcsService ecsService;
    private final ElbV2Service elbV2Service;
    private final SsmCommandService ssmCommandService;
    private final Ec2Service ec2Service;
    private final CodeDeployAgentDeployment agentDeployment;
    private final ServiceRoleCallAuthorizer roleAuthorizer;
    private final CodeDeployLaunchHooks launchHooks;
    private final ObjectMapper mapper;
    private final ObjectMapper yamlMapper;
    private final RegionResolver regionResolver;
    private final StorageFactory storageFactory;
    private final Duration hookCallbackTimeout;
    private final Clock clock;
    private final CodeDeployTimer timer;

    public CodeDeployService(LambdaService lambdaService, EcsService ecsService,
                             ElbV2Service elbV2Service, SsmCommandService ssmCommandService,
                             Ec2Service ec2Service, ObjectMapper mapper, RegionResolver regionResolver,
                              StorageFactory storageFactory, CodeDeployAgentDeployment agentDeployment,
                              ServiceRoleCallAuthorizer roleAuthorizer) {
        this(lambdaService, ecsService, elbV2Service, ssmCommandService, ec2Service, mapper,
                regionResolver, storageFactory, agentDeployment, roleAuthorizer, null);
    }

    @Inject
    public CodeDeployService(LambdaService lambdaService, EcsService ecsService,
                             ElbV2Service elbV2Service, SsmCommandService ssmCommandService,
                             Ec2Service ec2Service, ObjectMapper mapper, RegionResolver regionResolver,
                             StorageFactory storageFactory, CodeDeployAgentDeployment agentDeployment,
                             ServiceRoleCallAuthorizer roleAuthorizer, CodeDeployLaunchHooks launchHooks) {
        this(lambdaService, ecsService, elbV2Service, ssmCommandService, ec2Service, mapper,
                regionResolver, storageFactory, DEFAULT_HOOK_CALLBACK_TIMEOUT, Clock.systemUTC(),
                agentDeployment, roleAuthorizer, launchHooks);
    }

    CodeDeployService(LambdaService lambdaService, EcsService ecsService,
                      ElbV2Service elbV2Service, SsmCommandService ssmCommandService,
                      Ec2Service ec2Service, ObjectMapper mapper, RegionResolver regionResolver,
                       StorageFactory storageFactory, Duration hookCallbackTimeout, Clock clock,
                       CodeDeployAgentDeployment agentDeployment, ServiceRoleCallAuthorizer roleAuthorizer) {
        this(lambdaService, ecsService, elbV2Service, ssmCommandService, ec2Service, mapper,
                regionResolver, storageFactory, hookCallbackTimeout, clock, agentDeployment, roleAuthorizer, null);
    }

    CodeDeployService(LambdaService lambdaService, EcsService ecsService,
                      ElbV2Service elbV2Service, SsmCommandService ssmCommandService,
                      Ec2Service ec2Service, ObjectMapper mapper, RegionResolver regionResolver,
                      StorageFactory storageFactory, Duration hookCallbackTimeout, Clock clock,
                      CodeDeployAgentDeployment agentDeployment, ServiceRoleCallAuthorizer roleAuthorizer,
                      CodeDeployLaunchHooks launchHooks) {
        this(lambdaService, ecsService, elbV2Service, ssmCommandService, ec2Service, mapper,
                regionResolver, storageFactory, hookCallbackTimeout, clock, agentDeployment, roleAuthorizer,
                launchHooks, CodeDeployTimer.SYSTEM);
    }

    CodeDeployService(LambdaService lambdaService, EcsService ecsService,
                      ElbV2Service elbV2Service, SsmCommandService ssmCommandService,
                      Ec2Service ec2Service, ObjectMapper mapper, RegionResolver regionResolver,
                      StorageFactory storageFactory, Duration hookCallbackTimeout, Clock clock,
                      CodeDeployAgentDeployment agentDeployment, ServiceRoleCallAuthorizer roleAuthorizer,
                      CodeDeployLaunchHooks launchHooks, CodeDeployTimer timer) {
        this.lambdaService = lambdaService;
        this.ecsService = ecsService;
        this.elbV2Service = elbV2Service;
        this.ssmCommandService = ssmCommandService;
        this.ec2Service = ec2Service;
        this.agentDeployment = agentDeployment;
        this.roleAuthorizer = roleAuthorizer;
        this.launchHooks = launchHooks;
        this.mapper = mapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.regionResolver = regionResolver;
        this.storageFactory = storageFactory;
        this.hookCallbackTimeout = hookCallbackTimeout;
        this.clock = clock;
        this.timer = timer;
    }

    // ---- Durable (persisted) ----
    // key: region -> name -> application
    private Map<String, Map<String, Application>> applications = new ConcurrentHashMap<>();
    // key: region -> appName -> groupName -> group
    private Map<String, Map<String, Map<String, DeploymentGroup>>> deploymentGroups = new ConcurrentHashMap<>();
    private AccountAwareStorageBackend<Map<String, Map<String, DeploymentGroup>>> deploymentGroupsStore;
    // key: region -> configName -> config (pre-seeded with built-ins)
    private Map<String, Map<String, DeploymentConfig>> deploymentConfigs = new ConcurrentHashMap<>();
    // key: resourceArn -> tags
    private Map<String, Map<String, String>> tags = new ConcurrentHashMap<>();
    // key: region -> instanceName -> OnPremisesInstance
    private Map<String, Map<String, OnPremisesInstance>> onPremisesInstances = new ConcurrentHashMap<>();
    private Map<String, SuccessfulAgentRevision> successfulAgentRevisions = new ConcurrentHashMap<>();

    // ---- Transient runtime state (in memory only) ----
    // key: account/region -> deploymentId -> Deployment
    private final ConcurrentHashMap<RuntimeScope, ConcurrentHashMap<String, Deployment>> deployments =
            new ConcurrentHashMap<>();
    // key: account/region -> deploymentId -> targetId -> DeploymentTarget wrapper
    private final ConcurrentHashMap<RuntimeScope,
            ConcurrentHashMap<String, ConcurrentHashMap<String, Map<String, Object>>>> deploymentTargets =
            new ConcurrentHashMap<>();
    // key: lifecycleEventHookExecutionId -> CompletableFuture<status>
    private final ConcurrentHashMap<String, CompletableFuture<String>> hookFutures = new ConcurrentHashMap<>();
    // key: deploymentId -> stop flag
    private final ConcurrentHashMap<String, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();
    private final StripedLocks agentGroupLocks = new StripedLocks(256);
    private final ConcurrentHashMap<AgentGroupScope, String> activeAgentDeployments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AgentDeploymentContext> agentDeploymentContexts = new ConcurrentHashMap<>();

    private record RuntimeScope(String account, String region) {}
    private record AgentGroupScope(String account, String region, String groupId) {
        String historyKey() {
            return account + ":" + region + ":" + groupId;
        }
    }

    @RegisterForReflection
    public record SuccessfulAgentRevision(String deploymentId, Map<String, Object> revision,
                                           String fileExistsBehavior, String configName) {}

    private record AgentDeploymentContext(AgentGroupScope scope, SuccessfulAgentRevision revision,
                                          AgentRollbackPolicy policy, boolean rollback, AtomicBoolean stop,
                                          AtomicBoolean rollbackRequested, boolean automatic) {}

    private record AgentRollbackPolicy(boolean enabled, List<String> events) {
        static AgentRollbackPolicy from(Map<String, Object> configuration) {
            if (configuration == null) {
                return new AgentRollbackPolicy(false, List.of());
            }
            Object enabled = configuration.getOrDefault("enabled", false);
            Object events = configuration.getOrDefault("events", List.of());
            if (!(enabled instanceof Boolean) || !(events instanceof List<?> values)
                    || values.stream().anyMatch(value -> !(value instanceof String)
                            || !Set.of("DEPLOYMENT_FAILURE", "DEPLOYMENT_STOP_ON_ALARM",
                                    "DEPLOYMENT_STOP_ON_REQUEST").contains(value))) {
                throw new AwsException("InvalidAutoRollbackConfigException",
                        "Rollback configuration requires a boolean enabled and valid event names", 400);
            }
            List<String> names = values.stream().map(String.class::cast).toList();
            if (Boolean.TRUE.equals(enabled) && names.stream().anyMatch(value -> !"DEPLOYMENT_FAILURE".equals(value))) {
                throw new AwsException("UnsupportedOperationException",
                        "S3 agent deployments support failure rollback; use StopDeployment for an explicit rollback. "
                                + "Automatic alarm and request-stop triggers are not implemented", 400);
            }
            return new AgentRollbackPolicy((Boolean) enabled, names);
        }

        Map<String, Object> configuration() {
            return Map.of("enabled", enabled, "events", events);
        }
    }

    @PostConstruct
    void initializeStorage() {
        if (storageFactory == null) {
            return; // keeps non-CDI unit tests working
        }
        this.applications = storageBacked("codedeploy-applications.json",
                new TypeReference<Map<String, Map<String, Application>>>() {});
        this.deploymentGroupsStore = storageFactory.create("codedeploy", "codedeploy-deployment-groups.json",
                new TypeReference<Map<String, Map<String, Map<String, DeploymentGroup>>>>() {});
        this.deploymentGroups = new StorageBackedMap<>(deploymentGroupsStore);
        this.deploymentConfigs = storageBacked("codedeploy-deployment-configs.json",
                new TypeReference<Map<String, Map<String, DeploymentConfig>>>() {});
        this.tags = storageBacked("codedeploy-tags.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        this.onPremisesInstances = storageBacked("codedeploy-on-premises-instances.json",
                new TypeReference<Map<String, Map<String, OnPremisesInstance>>>() {});
        this.successfulAgentRevisions = storageBacked("codedeploy-agent-successes.json",
                new TypeReference<Map<String, SuccessfulAgentRevision>>() {});
        normalizeRegionMaps(applications);
        normalizeDeploymentGroups();
        normalizeRegionMaps(deploymentConfigs);
        normalizeRegionMaps(tags);
        normalizeRegionMaps(onPremisesInstances);
        reconcileBuiltInConfigs();
    }

    private <V> Map<String, V> storageBacked(String fileName, TypeReference<Map<String, V>> typeReference) {
        return new StorageBackedMap<>(storageFactory.create("codedeploy", fileName, typeReference));
    }

    /** After load, re-wrap persisted inner maps as {@link ConcurrentHashMap} (Jackson reads them as
     *  plain maps) so per-key mutation stays thread-safe. For two-level region maps. */
    private <V> void normalizeRegionMaps(Map<String, Map<String, V>> resources) {
        for (Map.Entry<String, Map<String, V>> entry : new ArrayList<>(resources.entrySet())) {
            if (!(entry.getValue() instanceof ConcurrentHashMap)) {
                resources.put(entry.getKey(), new ConcurrentHashMap<>(entry.getValue()));
            }
        }
    }

    /** Three-level normalization for deploymentGroups (region -> app -> group). */
    private void normalizeDeploymentGroups() {
        for (AccountAwareStorageBackend.AccountEntry<Map<String, Map<String, DeploymentGroup>>> regionEntry
                : deploymentGroupsStore.scanAllAccountEntries(region -> true)) {
            Map<String, Map<String, DeploymentGroup>> appMap = new ConcurrentHashMap<>();
            for (Map.Entry<String, Map<String, DeploymentGroup>> appEntry : regionEntry.value().entrySet()) {
                appMap.put(appEntry.getKey(), new ConcurrentHashMap<>(appEntry.getValue()));
            }
            deploymentGroupsStore.putForAccount(regionEntry.accountId(), regionEntry.key(), appMap);
        }
    }

    /** Top up any built-in deployment configs missing from already-loaded regions (forward-compat
     *  if the built-in list grows between Floci versions). Built-ins themselves are persisted. */
    private void reconcileBuiltInConfigs() {
        for (String region : new ArrayList<>(deploymentConfigs.keySet())) {
            Map<String, DeploymentConfig> store = deploymentConfigs.get(region);
            if (store == null) {
                continue;
            }
            double now = EpochSeconds.fromInstant(clock.instant());
            boolean changed = false;
            for (String name : BUILT_IN_CONFIG_NAMES) {
                if (!store.containsKey(name)) {
                    store.put(name, buildBuiltInConfig(name, now));
                    changed = true;
                }
            }
            if (changed) {
                deploymentConfigs.put(region, store);
            }
        }
    }

    /** {@link StorageBackedMap} only flushes on a top-level put, so an in-place mutation of an
     *  inner map must be written back by re-putting the region entry. */
    private <V> void persistRegion(Map<String, Map<String, V>> resources, String region) {
        Map<String, V> regionResources = resources.get(region);
        if (regionResources != null) {
            resources.put(region, regionResources);
        }
    }

    private static final class AppSpecInfo {
        String functionName;
        String aliasName;
        String currentVersion;
        String targetVersion;
        String beforeAllowTraffic;
        String afterAllowTraffic;
    }

    private static final class ServerAppSpecInfo {
        String os;
        Map<String, List<Map<String, Object>>> hooks; // hookName → [{location, timeout, runas}]
    }

    private static final class EcsAppSpecInfo {
        String taskDefinition;
        String containerName;
        int containerPort;
        String beforeInstall;
        String afterInstall;
        String beforeAllowTraffic;
        String afterAllowTraffic;
    }

    private static final class TrafficRoutingInfo {
        final String type;
        final int percentage;
        final int intervalSeconds;

        TrafficRoutingInfo(String type, int percentage, int intervalSeconds) {
            this.type = type;
            this.percentage = percentage;
            this.intervalSeconds = intervalSeconds;
        }
    }

    private static final List<String> BUILT_IN_CONFIG_NAMES = List.of(
            "CodeDeployDefault.OneAtATime",
            "CodeDeployDefault.HalfAtATime",
            "CodeDeployDefault.AllAtOnce",
            "CodeDeployDefault.LambdaAllAtOnce",
            "CodeDeployDefault.LambdaCanary10Percent5Minutes",
            "CodeDeployDefault.LambdaCanary10Percent10Minutes",
            "CodeDeployDefault.LambdaCanary10Percent15Minutes",
            "CodeDeployDefault.LambdaCanary10Percent30Minutes",
            "CodeDeployDefault.LambdaLinear10PercentEvery1Minute",
            "CodeDeployDefault.LambdaLinear10PercentEvery2Minutes",
            "CodeDeployDefault.LambdaLinear10PercentEvery3Minutes",
            "CodeDeployDefault.LambdaLinear10PercentEvery10Minutes",
            "CodeDeployDefault.ECSAllAtOnce",
            "CodeDeployDefault.ECSCanary10Percent5Minutes",
            "CodeDeployDefault.ECSCanary10Percent15Minutes",
            "CodeDeployDefault.ECSLinear10PercentEvery1Minutes",
            "CodeDeployDefault.ECSLinear10PercentEvery3Minutes"
    );

    private Map<String, Application> applicationsFor(String region) {
        return applications.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, Map<String, DeploymentGroup>> deploymentGroupsFor(String region) {
        return deploymentGroups.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private Map<String, DeploymentConfig> deploymentConfigsFor(String region) {
        return deploymentConfigs.computeIfAbsent(region, r -> {
            ConcurrentHashMap<String, DeploymentConfig> store = new ConcurrentHashMap<>();
            double now = EpochSeconds.fromInstant(clock.instant());
            for (String name : BUILT_IN_CONFIG_NAMES) {
                store.put(name, buildBuiltInConfig(name, now));
            }
            return store;
        });
    }

    private DeploymentConfig buildBuiltInConfig(String name, double now) {
        DeploymentConfig cfg = new DeploymentConfig();
        cfg.setDeploymentConfigId("d-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase());
        cfg.setDeploymentConfigName(name);
        cfg.setCreateTime(now);

        if (name.startsWith("CodeDeployDefault.Lambda")) {
            cfg.setComputePlatform("Lambda");
            if (name.equals("CodeDeployDefault.LambdaAllAtOnce")) {
                cfg.setTrafficRoutingConfig(Map.of("type", "AllAtOnce"));
            } else if (name.contains("Canary")) {
                int pct = 10;
                int minutes = extractMinutes(name);
                cfg.setTrafficRoutingConfig(Map.of("type", "TimeBasedCanary",
                        "timeBasedCanary", Map.of("canaryPercentage", pct, "canaryInterval", minutes)));
            } else if (name.contains("Linear")) {
                int pct = 10;
                int minutes = extractMinutes(name);
                cfg.setTrafficRoutingConfig(Map.of("type", "TimeBasedLinear",
                        "timeBasedLinear", Map.of("linearPercentage", pct, "linearInterval", minutes)));
            }
        } else if (name.startsWith("CodeDeployDefault.ECS")) {
            cfg.setComputePlatform("ECS");
            if (name.equals("CodeDeployDefault.ECSAllAtOnce")) {
                cfg.setTrafficRoutingConfig(Map.of("type", "AllAtOnce"));
            } else if (name.contains("Canary")) {
                int pct = 10;
                int minutes = extractMinutes(name);
                cfg.setTrafficRoutingConfig(Map.of("type", "TimeBasedCanary",
                        "timeBasedCanary", Map.of("canaryPercentage", pct, "canaryInterval", minutes)));
            } else if (name.contains("Linear")) {
                int pct = 10;
                int minutes = extractMinutes(name);
                cfg.setTrafficRoutingConfig(Map.of("type", "TimeBasedLinear",
                        "timeBasedLinear", Map.of("linearPercentage", pct, "linearInterval", minutes)));
            }
        } else {
            cfg.setComputePlatform("Server");
            if (name.equals("CodeDeployDefault.AllAtOnce")) {
                cfg.setMinimumHealthyHosts(Map.of("type", "FLEET_PERCENT", "value", 0));
            } else if (name.equals("CodeDeployDefault.HalfAtATime")) {
                cfg.setMinimumHealthyHosts(Map.of("type", "FLEET_PERCENT", "value", 50));
            } else {
                cfg.setMinimumHealthyHosts(Map.of("type", "HOST_COUNT", "value", 1));
            }
        }
        return cfg;
    }

    private int extractMinutes(String name) {
        // e.g. "…Every1Minute" -> 1, "…5Minutes" -> 5
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)Minute").matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    // ---- Applications ----

    public Application createApplication(String region, String name, String computePlatform,
                                          List<Map<String, String>> tags) {
        Map<String, Application> store = applicationsFor(region);
        if (store.containsKey(name)) {
            throw new AwsException("ApplicationAlreadyExistsException",
                    "Application already exists: " + name, 400);
        }
        Application app = new Application();
        app.setApplicationId(UUID.randomUUID().toString());
        app.setApplicationName(name);
        app.setCreateTime(EpochSeconds.fromInstant(clock.instant()));
        app.setLinkedToGitHub(false);
        app.setComputePlatform(computePlatform != null ? computePlatform : "Server");
        store.put(name, app);
        persistRegion(applications, region);

        if (tags != null && !tags.isEmpty()) {
            String arn = applicationArn(region, name);
            applyTags(arn, tags);
        }
        return app;
    }

    public Application getApplication(String region, String name) {
        if (name == null || name.isEmpty()) {
            throw new AwsException("ApplicationNameRequiredException",
                    "The minimum number of required characters for applicationName is 1.", 400);
        }
        if (name.isBlank()) {
            throw new AwsException("InvalidApplicationNameException",
                    "The application name was specified in an invalid format.", 400);
        }
        Application app = applicationsFor(region).get(name);
        if (app == null) {
            throw new AwsException("ApplicationDoesNotExistException",
                    "Application does not exist: " + name, 400);
        }
        return app;
    }

    public void updateApplication(String region, String currentName, String newName) {
        Map<String, Application> store = applicationsFor(region);
        Application app = store.get(currentName);
        if (app == null) {
            throw new AwsException("ApplicationDoesNotExistException",
                    "Application does not exist: " + currentName, 400);
        }
        if (newName != null && !newName.equals(currentName)) {
            if (store.containsKey(newName)) {
                throw new AwsException("ApplicationAlreadyExistsException",
                        "Application already exists: " + newName, 400);
            }
            store.remove(currentName);
            app.setApplicationName(newName);
            store.put(newName, app);
            persistRegion(applications, region);
        }
    }

    public void deleteApplication(String region, String name) {
        if (applicationsFor(region).remove(name) == null) {
            throw new AwsException("ApplicationDoesNotExistException",
                    "Application does not exist: " + name, 400);
        }
        persistRegion(applications, region);
        Map<String, DeploymentGroup> removedGroups = deploymentGroupsFor(region).remove(name);
        if (removedGroups != null) {
            removedGroups.keySet().forEach(group -> tags.remove(deploymentGroupArn(region, name, group)));
            removedGroups.values().forEach(group -> forgetAgentHistory(region, group.getDeploymentGroupId()));
            if (launchHooks != null) {
                removedGroups.values().forEach(group -> launchHooks.delete(region, group));
            }
            persistRegion(deploymentGroups, region);
        }
        tags.remove(applicationArn(region, name));
    }

    public List<String> listApplications(String region) {
        return new ArrayList<>(applicationsFor(region).keySet());
    }

    public PaginatedResult<String> listApplications(String region, String nextToken) {
        return Pagination.paginate(listApplications(region), name -> name, 100, nextToken,
                "codedeploy:ListApplications:" + regionResolver.getAccountId() + ":" + region,
                token -> new AwsException("InvalidNextTokenException", "Invalid nextToken.", 400));
    }

    public List<Application> batchGetApplications(String region, List<String> names) {
        Map<String, Application> store = applicationsFor(region);
        return names.stream()
                .map(n -> {
                    Application a = store.get(n);
                    if (a == null) {
                        throw new AwsException("ApplicationDoesNotExistException",
                                "Application does not exist: " + n, 400);
                    }
                    return a;
                })
                .collect(Collectors.toList());
    }

    // ---- Deployment Groups ----

    public DeploymentGroup createDeploymentGroup(String region, String appName, String groupName,
                                                  String deploymentConfigName, String serviceRoleArn,
                                                  Map<String, Object> fields) {
        Application application = getApplication(region, appName);
        Map<String, Map<String, DeploymentGroup>> appGroups = deploymentGroupsFor(region);
        Map<String, DeploymentGroup> groupStore = appGroups.computeIfAbsent(appName, a -> new ConcurrentHashMap<>());
        synchronized (groupStore) {
            if (groupStore.containsKey(groupName)) {
                throw new AwsException("DeploymentGroupAlreadyExistsException",
                        "Deployment group already exists: " + groupName, 400);
            }
            DeploymentGroup group = new DeploymentGroup();
            group.setApplicationName(appName);
            group.setDeploymentGroupId(UUID.randomUUID().toString());
            group.setDeploymentGroupName(groupName);
            group.setDeploymentConfigName(deploymentConfigName != null
                    ? deploymentConfigName : "CodeDeployDefault.OneAtATime");
            group.setServiceRoleArn(serviceRoleArn);
            group.setComputePlatform(application.getComputePlatform());
            applyGroupFields(group, fields);
            if (launchHooks != null) {
                launchHooks.configure(region, null, group);
            }
            groupStore.put(groupName, group);
            persistRegion(deploymentGroups, region);
            return group;
        }
    }

    public DeploymentGroup getDeploymentGroup(String region, String appName, String groupName) {
        getApplication(region, appName);
        Map<String, Map<String, DeploymentGroup>> appGroups = deploymentGroupsFor(region);
        Map<String, DeploymentGroup> groupStore = appGroups.get(appName);
        DeploymentGroup group = groupStore != null ? groupStore.get(groupName) : null;
        if (group == null) {
            throw new AwsException("DeploymentGroupDoesNotExistException",
                    "Deployment group does not exist: " + groupName, 400);
        }
        return group;
    }

    public DeploymentGroup updateDeploymentGroup(String region, String appName,
                                                  String currentGroupName, String newGroupName,
                                                  String deploymentConfigName, String serviceRoleArn,
                                                  Map<String, Object> fields) {
        return updateDeploymentGroupWithHooks(region, appName, currentGroupName, newGroupName,
                deploymentConfigName, serviceRoleArn, fields).group();
    }

    record UpdatedDeploymentGroup(DeploymentGroup group, List<Map<String, Object>> hooksNotCleanedUp) {}

    UpdatedDeploymentGroup updateDeploymentGroupWithHooks(String region, String appName,
                                                           String currentGroupName, String newGroupName,
                                                           String deploymentConfigName, String serviceRoleArn,
                                                           Map<String, Object> fields) {
        DeploymentGroup previous = getDeploymentGroup(region, appName, currentGroupName);
        Map<String, DeploymentGroup> groupStore = deploymentGroupsFor(region)
                .computeIfAbsent(appName, a -> new ConcurrentHashMap<>());
        AgentGroupScope scope = new AgentGroupScope(regionResolver.getAccountId(), region, previous.getDeploymentGroupId());
        synchronized (agentGroupLocks.lockFor(scope)) {
            synchronized (groupStore) {
                if (groupStore.get(currentGroupName) != previous) {
                    throw new AwsException("DeploymentGroupDoesNotExistException",
                            "The deployment group changed during update", 400);
                }
                String name = newGroupName == null ? currentGroupName : newGroupName;
                if (!name.equals(currentGroupName) && groupStore.containsKey(name)) {
                    throw new AwsException("DeploymentGroupAlreadyExistsException",
                            "Deployment group already exists: " + name, 400);
                }
                DeploymentGroup group = mapper.convertValue(previous, DeploymentGroup.class);
                group.setDeploymentGroupName(name);
                if (deploymentConfigName != null) { group.setDeploymentConfigName(deploymentConfigName); }
                if (serviceRoleArn != null) { group.setServiceRoleArn(serviceRoleArn); }
                applyGroupFields(group, fields);
                List<Map<String, Object>> remaining = launchHooks == null
                        ? List.of() : launchHooks.configure(region, previous, group);
                groupStore.remove(currentGroupName);
                groupStore.put(name, group);
                persistRegion(deploymentGroups, region);
                return new UpdatedDeploymentGroup(group, remaining);
            }
        }
    }

    public void deleteDeploymentGroup(String region, String appName, String groupName) {
        deleteDeploymentGroupWithHooks(region, appName, groupName);
    }

    List<Map<String, Object>> deleteDeploymentGroupWithHooks(String region, String appName, String groupName) {
        DeploymentGroup group = getDeploymentGroup(region, appName, groupName);
        Map<String, Map<String, DeploymentGroup>> appGroups = deploymentGroupsFor(region);
        Map<String, DeploymentGroup> groupStore = appGroups.get(appName);
        AgentGroupScope scope = new AgentGroupScope(regionResolver.getAccountId(), region, group.getDeploymentGroupId());
        synchronized (agentGroupLocks.lockFor(scope)) {
            if (groupStore == null) {
                throw new AwsException("DeploymentGroupDoesNotExistException",
                        "Deployment group does not exist: " + groupName, 400);
            }
            synchronized (groupStore) {
                if (!groupStore.remove(groupName, group)) {
                    throw new AwsException("DeploymentGroupDoesNotExistException",
                            "Deployment group changed during deletion: " + groupName, 400);
                }
                successfulAgentRevisions.remove(scope.historyKey());
                persistRegion(deploymentGroups, region);
                tags.remove(deploymentGroupArn(region, appName, groupName));
                return launchHooks == null ? List.of() : launchHooks.delete(region, group);
            }
        }
    }

    private void forgetAgentHistory(String region, String groupId) {
        AgentGroupScope scope = new AgentGroupScope(regionResolver.getAccountId(), region, groupId);
        synchronized (agentGroupLocks.lockFor(scope)) {
            successfulAgentRevisions.remove(scope.historyKey());
        }
    }

    record AutoScalingGroupReference(String account, String region, String application, String groupId) {}
    record AutoScalingLaunchView(DeploymentGroup group, SuccessfulAgentRevision revision, String foregroundDeployment) {}
    record AutomaticDeployment(String deploymentId, String sourceDeploymentId) {}
    private record AutomaticTarget(String instanceId, boolean followup) {}

    List<AutoScalingGroupReference> autoScalingDeploymentGroups() {
        if (deploymentGroupsStore == null) {
            return List.of();
        }
        List<AutoScalingGroupReference> references = new ArrayList<>();
        for (AccountAwareStorageBackend.AccountEntry<Map<String, Map<String, DeploymentGroup>>> entry
                : deploymentGroupsStore.scanAllAccountEntries(region -> true)) {
            entry.value().forEach((application, groups) -> groups.values().stream()
                    .filter(group -> !CodeDeployLaunchHooks.groupNames(group).isEmpty())
                    .forEach(group -> references.add(new AutoScalingGroupReference(
                            entry.accountId(), entry.key(), application, group.getDeploymentGroupId()))));
        }
        return List.copyOf(references);
    }

    AutoScalingLaunchView autoScalingLaunchView(AutoScalingGroupReference reference) {
        AgentGroupScope scope = autoScalingScope(reference);
        synchronized (agentGroupLocks.lockFor(scope)) {
            DeploymentGroup group = findAgentGroup(scope, reference.application());
            return group == null ? null : new AutoScalingLaunchView(
                    mapper.convertValue(group, DeploymentGroup.class),
                    successfulAgentRevisions.get(scope.historyKey()), activeAgentDeployments.get(scope));
        }
    }

    AutomaticDeployment startAutomaticDeployment(AutoScalingGroupReference reference, String scalingGroup,
                                                  String instanceId, boolean followup) {
        AgentGroupScope scope = autoScalingScope(reference);
        synchronized (agentGroupLocks.lockFor(scope)) {
            DeploymentGroup group = findAgentGroup(scope, reference.application());
            if (group == null || !CodeDeployLaunchHooks.groupNames(group).contains(scalingGroup)) {
                return null;
            }
            SuccessfulAgentRevision revision = successfulAgentRevisions.get(scope.historyKey());
            if (revision == null || (followup && activeAgentDeployments.containsKey(scope))) {
                return null;
            }
            String id = createServerDeployment(reference.region(), reference.application(), group.getDeploymentGroupName(),
                    group, revision.configName(), revision.revision(),
                    (followup ? "Update outdated Auto Scaling instance " : "Auto Scaling launch of ") + instanceId,
                    revision.fileExistsBehavior(), Map.of("enabled", false, "events", List.of()), null,
                    new AutomaticTarget(instanceId, followup));
            return new AutomaticDeployment(id, revision.deploymentId());
        }
    }

    private AgentGroupScope autoScalingScope(AutoScalingGroupReference reference) {
        if (!reference.account().equals(regionResolver.getAccountId())) {
            throw new IllegalStateException("Auto Scaling deployment must run in its owning account");
        }
        return new AgentGroupScope(reference.account(), reference.region(), reference.groupId());
    }

    private DeploymentGroup findAgentGroup(AgentGroupScope scope, String application) {
        return deploymentGroupsFor(scope.region()).getOrDefault(application, Map.of()).values().stream()
                .filter(group -> scope.groupId().equals(group.getDeploymentGroupId())).findFirst().orElse(null);
    }

    public List<String> listDeploymentGroups(String region, String appName) {
        getApplication(region, appName);
        Map<String, Map<String, DeploymentGroup>> appGroups = deploymentGroupsFor(region);
        Map<String, DeploymentGroup> groupStore = appGroups.get(appName);
        return groupStore != null ? new ArrayList<>(groupStore.keySet()) : List.of();
    }

    public List<DeploymentGroup> batchGetDeploymentGroups(String region, String appName, List<String> names) {
        getApplication(region, appName);
        Map<String, Map<String, DeploymentGroup>> appGroups = deploymentGroupsFor(region);
        Map<String, DeploymentGroup> groupStore = appGroups.get(appName);
        if (groupStore == null) {
            return List.of();
        }
        return names.stream()
                .map(groupStore::get)
                .filter(g -> g != null)
                .collect(Collectors.toList());
    }

    // ---- Deployment Configs ----

    public DeploymentConfig createDeploymentConfig(String region, String name,
                                                    Map<String, Object> minimumHealthyHosts,
                                                    String computePlatform,
                                                    Map<String, Object> trafficRoutingConfig,
                                                    Map<String, Object> zonalConfig) {
        Map<String, DeploymentConfig> store = deploymentConfigsFor(region);
        if (store.containsKey(name)) {
            throw new AwsException("DeploymentConfigAlreadyExistsException",
                    "Deployment configuration already exists: " + name, 400);
        }
        if (name.startsWith("CodeDeployDefault.")) {
            throw new AwsException("InvalidDeploymentConfigNameException",
                    "Cannot create a deployment configuration starting with 'CodeDeployDefault.'", 400);
        }
        DeploymentConfig cfg = new DeploymentConfig();
        cfg.setDeploymentConfigId(UUID.randomUUID().toString());
        cfg.setDeploymentConfigName(name);
        cfg.setMinimumHealthyHosts(minimumHealthyHosts);
        cfg.setCreateTime(EpochSeconds.fromInstant(clock.instant()));
        cfg.setComputePlatform(computePlatform != null ? computePlatform : "Server");
        cfg.setTrafficRoutingConfig(trafficRoutingConfig);
        cfg.setZonalConfig(zonalConfig);
        store.put(name, cfg);
        persistRegion(deploymentConfigs, region);
        return cfg;
    }

    public DeploymentConfig getDeploymentConfig(String region, String name) {
        DeploymentConfig cfg = deploymentConfigsFor(region).get(name);
        if (cfg == null) {
            throw new AwsException("DeploymentConfigDoesNotExistException",
                    "Deployment configuration does not exist: " + name, 400);
        }
        return cfg;
    }

    public void deleteDeploymentConfig(String region, String name) {
        if (name.startsWith("CodeDeployDefault.")) {
            throw new AwsException("InvalidDeploymentConfigNameException",
                    "Cannot delete a built-in deployment configuration.", 400);
        }
        if (deploymentConfigsFor(region).remove(name) == null) {
            throw new AwsException("DeploymentConfigDoesNotExistException",
                    "Deployment configuration does not exist: " + name, 400);
        }
        persistRegion(deploymentConfigs, region);
    }

    public List<String> listDeploymentConfigs(String region) {
        return new ArrayList<>(deploymentConfigsFor(region).keySet());
    }

    // ---- Deployments ----

    private Map<String, Deployment> deploymentsFor(String region) {
        return deployments.computeIfAbsent(new RuntimeScope(regionResolver.getAccountId(), region),
                ignored -> new ConcurrentHashMap<>());
    }

    private Map<String, ConcurrentHashMap<String, Map<String, Object>>> deploymentTargetsFor(String region) {
        return deploymentTargets.computeIfAbsent(new RuntimeScope(regionResolver.getAccountId(), region),
                ignored -> new ConcurrentHashMap<>());
    }

    private Map<String, OnPremisesInstance> onPremisesFor(String region) {
        return onPremisesInstances.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    public String createDeployment(String region, String appName, String groupName,
                                   String configName, Map<String, Object> revision, String description) {
        return createDeployment(region, appName, groupName, configName, revision, description, "DISALLOW");
    }

    public String createDeployment(String region, String appName, String groupName,
                                   String configName, Map<String, Object> revision, String description,
                                   String fileExistsBehavior) {
        return createDeployment(region, appName, groupName, configName, revision, description,
                fileExistsBehavior, null);
    }

    public String createDeployment(String region, String appName, String groupName,
                                    String configName, Map<String, Object> revision, String description,
                                    String fileExistsBehavior, Map<String, Object> autoRollbackConfiguration) {
        Application app = getApplication(region, appName);
        DeploymentGroup group = getDeploymentGroup(region, appName, groupName);
        // Compute platform is authoritative on the Application; the deployment group may also carry it
        String computePlatform = group.getComputePlatform();
        if (computePlatform == null) {
            computePlatform = app.getComputePlatform();
        }
        if (!"DISALLOW".equals(fileExistsBehavior)
                && (!"Server".equals(computePlatform) || revision == null
                    || !"S3".equals(revision.get("revisionType")))) {
            throw new AwsException("InvalidFileExistsBehaviorException",
                    "File behavior overrides require an S3 Server revision", 400);
        }
        if (autoRollbackConfiguration != null && (!"Server".equals(computePlatform) || revision == null
                || !"S3".equals(revision.get("revisionType")))) {
            throw new AwsException("UnsupportedOperationException",
                    "Rollback overrides currently require an S3 Server revision", 400);
        }

        if ("ECS".equals(computePlatform)) {
            return createEcsDeployment(region, appName, groupName, group, configName, revision, description);
        }

        if ("Server".equals(computePlatform)) {
            return createServerDeployment(region, appName, groupName, group, configName, revision, description,
                    fileExistsBehavior, autoRollbackConfiguration, null);
        }
        AppSpecInfo appSpec = parseAppSpec(revision);
        String effectiveConfig = configName != null ? configName : group.getDeploymentConfigName();

        String deploymentId = generateDeploymentId();
        double now = EpochSeconds.fromInstant(clock.instant());

        Deployment deployment = new Deployment();
        deployment.setDeploymentId(deploymentId);
        deployment.setApplicationName(appName);
        deployment.setDeploymentGroupName(groupName);
        deployment.setDeploymentConfigName(effectiveConfig);
        deployment.setStatus("Queued");
        deployment.setRevision(revision);
        deployment.setCreateTime(now);
        deployment.setDescription(description);
        deployment.setCreator("user");
        deployment.setComputePlatform("Lambda");
        deploymentsFor(region).put(deploymentId, deployment);

        // Build initial target map
        String targetId = appSpec.functionName + ":" + appSpec.aliasName;
        String targetArn = AwsArnUtils.Arn.of("lambda", region, regionResolver.getAccountId(), "function:" + appSpec.functionName + ":" + appSpec.aliasName).toString();
        Map<String, Object> lambdaTargetMap = new ConcurrentHashMap<>();
        lambdaTargetMap.put("deploymentId", deploymentId);
        lambdaTargetMap.put("targetId", targetId);
        lambdaTargetMap.put("targetArn", targetArn);
        lambdaTargetMap.put("status", "Pending");
        lambdaTargetMap.put("lastUpdatedAt", now);
        lambdaTargetMap.put("lifecycleEvents", new CopyOnWriteArrayList<>());

        Map<String, Object> targetMap = new ConcurrentHashMap<>();
        targetMap.put("deploymentTargetType", "LambdaFunction");
        targetMap.put("lambdaTarget", lambdaTargetMap);
        ConcurrentHashMap<String, Map<String, Object>> targets = new ConcurrentHashMap<>();
        targets.put(targetId, targetMap);
        deploymentTargetsFor(region).put(deploymentId, targets);

        AtomicBoolean stopFlag = new AtomicBoolean(false);
        stopFlags.put(deploymentId, stopFlag);

        String accountId = regionResolver.getAccountId();
        String serviceRoleArn = group.getServiceRoleArn();
        Thread.ofVirtual().name("codedeploy-" + deploymentId).start(
                () -> RequestScopes.runAs(accountId, region,
                        () -> runStateMachine(region, deployment, appSpec, lambdaTargetMap, stopFlag,
                                effectiveConfig, serviceRoleArn)));

        return deploymentId;
    }

    public Deployment getDeployment(String region, String deploymentId) {
        Deployment d = deploymentsFor(region).get(deploymentId);
        if (d == null) {
            throw new AwsException("DeploymentDoesNotExistException",
                    "Deployment does not exist: " + deploymentId, 400);
        }
        return d;
    }

    public List<String> listDeployments(String region, String appName, String groupName, List<String> statuses) {
        return deploymentsFor(region).values().stream()
                .filter(d -> appName == null || appName.equals(d.getApplicationName()))
                .filter(d -> groupName == null || groupName.equals(d.getDeploymentGroupName()))
                .filter(d -> statuses == null || statuses.isEmpty() || statuses.contains(d.getStatus()))
                .map(Deployment::getDeploymentId)
                .collect(Collectors.toList());
    }

    public Map<String, String> stopDeployment(String region, String deploymentId) {
        return stopDeployment(region, deploymentId, false);
    }

    public Map<String, String> stopDeployment(String region, String deploymentId, boolean autoRollbackEnabled) {
        Deployment d = getDeployment(region, deploymentId);
        AgentDeploymentContext agent = agentDeploymentContexts.get(deploymentId);
        if (agent != null) {
            synchronized (agentGroupLocks.lockFor(agent.scope())) {
                if (agentDeploymentContexts.get(deploymentId) == agent) {
                    agent.rollbackRequested().set(autoRollbackEnabled);
                    agent.stop().set(true);
                    return Map.of("status", "Pending", "statusMessage", "Stop request submitted.");
                }
            }
        }
        String status = d.getStatus();
        if ("Succeeded".equals(status) || "Failed".equals(status) || "Stopped".equals(status)) {
            return Map.of("status", "Succeeded", "statusMessage", "Deployment is already in a terminal state.");
        }
        if (autoRollbackEnabled) {
            throw new AwsException("UnsupportedActionForDeploymentTypeException",
                    "Explicit stop rollback currently requires an S3 Server deployment", 400);
        }
        AtomicBoolean flag = stopFlags.get(deploymentId);
        if (flag != null) {
            flag.set(true);
        }
        return Map.of("status", "Pending", "statusMessage", "Stop request submitted.");
    }

    public List<Deployment> batchGetDeployments(String region, List<String> ids) {
        Map<String, Deployment> store = deploymentsFor(region);
        return ids.stream()
                .map(id -> {
                    Deployment d = store.get(id);
                    if (d == null) {
                        throw new AwsException("DeploymentDoesNotExistException",
                                "Deployment does not exist: " + id, 400);
                    }
                    return d;
                })
                .collect(Collectors.toList());
    }

    public List<String> listDeploymentTargets(String region, String deploymentId) {
        getDeployment(region, deploymentId);
        Map<String, Map<String, Object>> targets = deploymentTargetsFor(region).get(deploymentId);
        if (targets == null) {
            return List.of();
        }
        return new ArrayList<>(targets.keySet());
    }

    public Map<String, Object> getDeploymentTarget(String region, String deploymentId, String targetId) {
        if (deploymentId == null || deploymentId.isBlank()) {
            throw new AwsException("DeploymentIdRequiredException", "A deployment ID is required", 400);
        }
        if (targetId == null || targetId.isBlank()) {
            throw new AwsException("DeploymentTargetIdRequiredException", "A deployment target ID is required", 400);
        }
        List<Map<String, Object>> targets = batchGetDeploymentTargets(region, deploymentId, List.of(targetId));
        if (targets.isEmpty()) {
            throw new AwsException("DeploymentTargetDoesNotExistException",
                    "The target does not belong to this deployment", 400);
        }
        return targets.getFirst();
    }

    public List<Map<String, Object>> batchGetDeploymentTargets(String region, String deploymentId, List<String> targetIds) {
        getDeployment(region, deploymentId);
        Map<String, Map<String, Object>> targets = deploymentTargetsFor(region).get(deploymentId);
        if (targets == null) {
            return List.of();
        }
        if (targetIds.isEmpty()) {
            return new ArrayList<>(targets.values());
        }
        return targetIds.stream()
                .map(targets::get)
                .filter(t -> t != null)
                .collect(Collectors.toList());
    }

    public String putLifecycleEventHookExecutionStatus(String deploymentId, String executionId, String status) {
        CompletableFuture<String> future = hookFutures.get(executionId);
        if (future != null && !future.isDone()) {
            future.complete(status);
        }
        return executionId;
    }

    // ---- Deployment state machine ----

    // ---- On-Premises Instances ----

    public OnPremisesInstance registerOnPremisesInstance(String region, String instanceName,
                                                          String iamSessionArn, String iamUserArn) {
        Map<String, OnPremisesInstance> store = onPremisesFor(region);
        OnPremisesInstance inst = new OnPremisesInstance();
        inst.setInstanceName(instanceName);
        inst.setInstanceArn(AwsArnUtils.Arn.of("codedeploy", region, regionResolver.getAccountId(),
                "instance/" + instanceName).toString());
        inst.setIamSessionArn(iamSessionArn);
        inst.setIamUserArn(iamUserArn);
        inst.setRegisterTime(EpochSeconds.fromInstant(clock.instant()));
        inst.setRegistrationStatus("Registered");
        store.put(instanceName, inst);
        persistRegion(onPremisesInstances, region);
        return inst;
    }

    public void deregisterOnPremisesInstance(String region, String instanceName) {
        OnPremisesInstance inst = requireOnPremisesInstance(region, instanceName);
        inst.setDeregisterTime(EpochSeconds.fromInstant(clock.instant()));
        inst.setRegistrationStatus("Deregistered");
        persistRegion(onPremisesInstances, region);
    }

    public OnPremisesInstance getOnPremisesInstance(String region, String instanceName) {
        return requireOnPremisesInstance(region, instanceName);
    }

    public List<OnPremisesInstance> batchGetOnPremisesInstances(String region, List<String> names) {
        return names.stream().map(n -> requireOnPremisesInstance(region, n)).collect(Collectors.toList());
    }

    public List<String> listOnPremisesInstances(String region, String registrationStatus,
                                                 List<Map<String, String>> tagFilters) {
        return onPremisesFor(region).values().stream()
                .filter(i -> registrationStatus == null || registrationStatus.equals(i.getRegistrationStatus()))
                .filter(i -> tagFilters == null || tagFilters.isEmpty() || matchesTagFilters(i.getTags(), tagFilters))
                .map(OnPremisesInstance::getInstanceName)
                .collect(Collectors.toList());
    }

    public void addTagsToOnPremisesInstances(String region, List<String> instanceNames, List<Map<String, String>> newTags) {
        for (String name : instanceNames) {
            OnPremisesInstance inst = requireOnPremisesInstance(region, name);
            for (Map<String, String> t : newTags) {
                inst.getTags().removeIf(e -> e.get("Key").equals(t.get("Key")));
                inst.getTags().add(t);
            }
        }
        persistRegion(onPremisesInstances, region);
    }

    public void removeTagsFromOnPremisesInstances(String region, List<String> instanceNames, List<Map<String, String>> tagsToRemove) {
        for (String name : instanceNames) {
            OnPremisesInstance inst = requireOnPremisesInstance(region, name);
            for (Map<String, String> t : tagsToRemove) {
                inst.getTags().removeIf(e -> e.get("Key").equals(t.get("Key")));
            }
        }
        persistRegion(onPremisesInstances, region);
    }

    private OnPremisesInstance requireOnPremisesInstance(String region, String instanceName) {
        OnPremisesInstance inst = onPremisesFor(region).get(instanceName);
        if (inst == null) {
            throw new AwsException("InstanceNameRequiredException",
                    "On-premises instance not found: " + instanceName, 400);
        }
        return inst;
    }

    // ---- Server Platform Deployment ----

    private String createServerDeployment(String region, String appName, String groupName,
                                          DeploymentGroup group, String configName,
                                          Map<String, Object> revision, String description,
                                          String fileExistsBehavior, Map<String, Object> rollbackOverride,
                                          String rollbackTrigger) {
        return createServerDeployment(region, appName, groupName, group, configName, revision, description,
                fileExistsBehavior, rollbackOverride, rollbackTrigger, null);
    }

    private String createServerDeployment(String region, String appName, String groupName,
                                          DeploymentGroup group, String configName,
                                          Map<String, Object> revision, String description,
                                          String fileExistsBehavior, Map<String, Object> rollbackOverride,
                                          String rollbackTrigger, AutomaticTarget automatic) {
        String effectiveConfig = configName != null ? configName : group.getDeploymentConfigName();
        boolean s3Revision = revision != null && "S3".equals(revision.get("revisionType"));
        AgentRollbackPolicy policy = s3Revision ? AgentRollbackPolicy.from(rollbackOverride == null
                ? group.getAutoRollbackConfiguration() : rollbackOverride) : null;
        CodeDeployAgentDeployment.Prepared prepared = !s3Revision ? null : automatic == null
                ? agentDeployment.prepare(region, group, effectiveConfig, revision, fileExistsBehavior)
                : agentDeployment.prepareAutomatic(region, group, effectiveConfig, revision,
                        fileExistsBehavior, automatic.instanceId());
        ServerAppSpecInfo appSpec = s3Revision ? null : parseServerAppSpec(revision);

        String deploymentId = generateDeploymentId();
        double now = EpochSeconds.fromInstant(clock.instant());

        Deployment deployment = new Deployment();
        deployment.setDeploymentId(deploymentId);
        deployment.setApplicationName(appName);
        deployment.setDeploymentGroupName(groupName);
        deployment.setDeploymentConfigName(prepared == null ? effectiveConfig : prepared.config());
        deployment.setStatus("Queued");
        deployment.setRevision(s3Revision ? captureAgentRevision(revision) : revision);
        deployment.setCreateTime(now);
        deployment.setDescription(description);
        deployment.setCreator(automatic != null ? automatic.followup() ? "CodeDeploy" : "autoScaling"
                : rollbackTrigger == null ? "user" : "codeDeployRollback");
        deployment.setComputePlatform("Server");
        if (s3Revision) {
            deployment.setFileExistsBehavior(fileExistsBehavior);
            deployment.setAutoRollbackConfiguration(policy.configuration());
            if (rollbackTrigger != null) {
                deployment.setRollbackInfo(Map.of("rollbackTriggeringDeploymentId", rollbackTrigger,
                        "rollbackDeploymentId", deploymentId,
                        "rollbackMessage", "Redeploying the last successful revision"));
            }
        }
        AtomicBoolean stopFlag = new AtomicBoolean(false);
        AgentDeploymentContext agent = prepared == null ? null : admitAgentDeployment(
                prepared, deployment, policy, rollbackTrigger != null, stopFlag,
                automatic != null, automatic == null || automatic.followup());
        try {
            // Resolve target instances
            List<String> instanceIds = prepared == null ? resolveServerTargets(region, group) : prepared.instanceIds();
            if (instanceIds.isEmpty()) {
                deploymentsFor(region).put(deploymentId, deployment);
                CodeDeployAgentDeployment.Outcome outcome = new CodeDeployAgentDeployment.Outcome(
                        "Failed", "NoInstancesReachable", "No instances found for deployment group");
                if (agent == null) {
                    outcome.publish(deployment);
                } else {
                    completeAgentDeployment(deployment, agent, outcome);
                }
                return deploymentId;
            }

            ConcurrentHashMap<String, Map<String, Object>> allTargets = new ConcurrentHashMap<>();
            List<Map<String, Object>> instanceTargetMaps = new ArrayList<>();
            for (String instanceId : instanceIds) {
                Map<String, Object> instanceTargetMap = new ConcurrentHashMap<>();
                instanceTargetMap.put("deploymentId", deploymentId);
                instanceTargetMap.put("targetId", instanceId);
                instanceTargetMap.put("targetArn", prepared == null ? instanceId : prepared.hosts().get(instanceId));
                instanceTargetMap.put("status", "Pending");
                instanceTargetMap.put("lastUpdatedAt", now);
                instanceTargetMap.put("lifecycleEvents", new CopyOnWriteArrayList<>());

                Map<String, Object> targetWrapper = new ConcurrentHashMap<>();
                targetWrapper.put("deploymentTargetType", "InstanceTarget");
                targetWrapper.put("instanceTarget", instanceTargetMap);
                allTargets.put(instanceId, targetWrapper);
                instanceTargetMaps.add(instanceTargetMap);
            }
            CodeDeployAgentCommands.Session session = prepared == null ? null : agentDeployment.open(prepared, deployment);
            deploymentsFor(region).put(deploymentId, deployment);
            deploymentTargetsFor(region).put(deploymentId, allTargets);

            stopFlags.put(deploymentId, stopFlag);

            Thread.ofVirtual().name("codedeploy-server-" + deploymentId).start(
                    () -> {
                        try {
                            if (prepared != null) {
                                agentDeployment.run(prepared, session, deployment, instanceTargetMaps, stopFlag,
                                        outcome -> RequestScopes.runAs(prepared.account(), prepared.region(),
                                                () -> completeAgentDeployment(deployment, agent, outcome)));
                            } else {
                                runServerStateMachine(region, deployment, appSpec, instanceIds, instanceTargetMaps, stopFlag);
                            }
                        } finally {
                            stopFlags.remove(deploymentId);
                        }
                    });
            return deploymentId;
        } catch (RuntimeException | Error failure) {
            if (agent != null) {
                synchronized (agentGroupLocks.lockFor(agent.scope())) {
                    agentDeploymentContexts.remove(deploymentId, agent);
                    activeAgentDeployments.remove(agent.scope(), deploymentId);
                }
            }
            throw failure;
        }
    }

    private Map<String, Object> captureAgentRevision(Map<String, Object> revision) {
        JsonNode source = mapper.valueToTree(revision).path("s3Location");
        Map<String, Object> location = new LinkedHashMap<>();
        for (String key : List.of("bucket", "key", "bundleType", "version", "eTag")) {
            if (source.hasNonNull(key)) {
                location.put(key, source.path(key).asText());
            }
        }
        return Map.of("revisionType", "S3", "s3Location", Map.copyOf(location));
    }

    private AgentDeploymentContext admitAgentDeployment(CodeDeployAgentDeployment.Prepared prepared,
                                                          Deployment deployment, AgentRollbackPolicy policy,
                                                          boolean rollback, AtomicBoolean stop,
                                                          boolean automatic, boolean reserveGroup) {
        AgentGroupScope scope = new AgentGroupScope(prepared.account(), prepared.region(), prepared.groupId());
        synchronized (agentGroupLocks.lockFor(scope)) {
            if (reserveGroup && activeAgentDeployments.containsKey(scope)) {
                throw new AwsException("DeploymentLimitExceededException",
                        "An S3 agent deployment is already running in this deployment group", 400);
            }
            SuccessfulAgentRevision revision = new SuccessfulAgentRevision(deployment.getDeploymentId(),
                    deployment.getRevision(), prepared.fileExistsBehavior(), prepared.config());
            AgentDeploymentContext context = new AgentDeploymentContext(scope, revision, policy, rollback, stop,
                    new AtomicBoolean(), automatic);
            if (reserveGroup) {
                activeAgentDeployments.put(scope, deployment.getDeploymentId());
            }
            agentDeploymentContexts.put(deployment.getDeploymentId(), context);
            return context;
        }
    }

    /** Called only after all old host and traffic workers have settled. */
    private void completeAgentDeployment(Deployment deployment, AgentDeploymentContext context,
                                          CodeDeployAgentDeployment.Outcome completed) {
        AgentGroupScope scope = context.scope();
        synchronized (agentGroupLocks.lockFor(scope)) {
            if (agentDeploymentContexts.get(deployment.getDeploymentId()) != context) {
                return;
            }
            CodeDeployAgentDeployment.Outcome outcome = context.stop().get()
                    ? new CodeDeployAgentDeployment.Outcome("Stopped", null, null) : completed;
            DeploymentGroup currentGroup = findAgentGroup(scope, deployment.getApplicationName());
            boolean rollback = !context.automatic() && (("Stopped".equals(outcome.status()) && context.rollbackRequested().get())
                    || (!context.rollback() && "Failed".equals(outcome.status()) && context.policy().enabled()
                        && context.policy().events().contains("DEPLOYMENT_FAILURE")));
            try {
                if (!context.automatic() && "Succeeded".equals(outcome.status()) && currentGroup != null) {
                    successfulAgentRevisions.put(scope.historyKey(), context.revision());
                }
                // Keep the group monitor across the handoff; no user deployment can interleave.
                activeAgentDeployments.remove(scope, deployment.getDeploymentId());
                if (rollback) {
                    startAgentRollback(deployment, context, currentGroup);
                }
            } catch (RuntimeException failure) {
                LOG.warnv("Deployment {0} rollback bookkeeping failed: {1}",
                        deployment.getDeploymentId(), failure.getMessage());
                deployment.setRollbackInfo(Map.of("rollbackMessage", "Rollback could not be started: "
                        + failure.getMessage()));
            } finally {
                activeAgentDeployments.remove(scope, deployment.getDeploymentId());
                agentDeploymentContexts.remove(deployment.getDeploymentId(), context);
                outcome.publish(deployment);
            }
        }
    }

    private void startAgentRollback(Deployment deployment, AgentDeploymentContext context, DeploymentGroup group) {
        SuccessfulAgentRevision previous = successfulAgentRevisions.get(context.scope().historyKey());
        if (group == null || previous == null) {
            deployment.setRollbackInfo(Map.of("rollbackMessage", group == null
                    ? "The original deployment group no longer exists"
                    : "No successful S3 agent deployment is available for rollback"));
            return;
        }
        String rollback = createServerDeployment(context.scope().region(), deployment.getApplicationName(),
                group.getDeploymentGroupName(), group, previous.configName(), previous.revision(),
                "Rollback of " + deployment.getDeploymentId(), previous.fileExistsBehavior(),
                Map.of("enabled", false, "events", List.of()), deployment.getDeploymentId());
        deployment.setRollbackInfo(Map.of("rollbackDeploymentId", rollback,
                "rollbackTriggeringDeploymentId", deployment.getDeploymentId(),
                "rollbackMessage", "Redeploying successful deployment " + previous.deploymentId()));
    }

    private void runServerStateMachine(String region, Deployment deployment,
                                       ServerAppSpecInfo appSpec, List<String> instanceIds,
                                       List<Map<String, Object>> instanceTargetMaps,
                                       AtomicBoolean stopFlag) {
        String deploymentId = deployment.getDeploymentId();
        try {
            deployment.setStatus("InProgress");
            deployment.setStartTime(EpochSeconds.fromInstant(clock.instant()));
            instanceTargetMaps.forEach(m -> updateTargetStatus(m, "InProgress"));

            boolean anyFailed = false;
            for (int i = 0; i < instanceIds.size(); i++) {
                if (stopFlag.get()) {
                    instanceTargetMaps.forEach(m -> updateTargetStatus(m, "Skipped"));
                    deployment.setStatus("Stopped");
                    deployment.setCompleteTime(EpochSeconds.fromInstant(clock.instant()));
                    return;
                }
                String instanceId = instanceIds.get(i);
                Map<String, Object> targetMap = instanceTargetMaps.get(i);
                boolean ok = runInstanceDeployment(region, deployment, appSpec, instanceId, targetMap, stopFlag);
                if (!ok) {
                    anyFailed = true;
                }
            }

            if (anyFailed) {
                deployment.setStatus("Failed");
                deployment.setErrorInformation(Map.of("code", "HEALTH_CONSTRAINTS",
                        "message", "The overall deployment failed because too many individual instances failed "
                                + "deployment, too few healthy instances are available for deployment, or some "
                                + "instances in your deployment group are experiencing problems."));
            } else {
                deployment.setStatus("Succeeded");
            }
            deployment.setCompleteTime(EpochSeconds.fromInstant(clock.instant()));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            instanceTargetMaps.forEach(m -> updateTargetStatus(m, "Skipped"));
            deployment.setStatus("Stopped");
            deployment.setCompleteTime(EpochSeconds.fromInstant(clock.instant()));
        } catch (Exception e) {
            LOG.warnv("Server deployment {0} failed: {1}", deploymentId, e.getMessage());
            instanceTargetMaps.forEach(m -> updateTargetStatus(m, "Failed"));
            deployment.setStatus("Failed");
            deployment.setCompleteTime(EpochSeconds.fromInstant(clock.instant()));
            deployment.setErrorInformation(Map.of("code", "DeploymentFailed",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"));
        } finally {
            stopFlags.remove(deploymentId);
        }
    }

    private boolean runInstanceDeployment(String region, Deployment deployment,
                                           ServerAppSpecInfo appSpec, String instanceId,
                                           Map<String, Object> targetMap,
                                           AtomicBoolean stopFlag) throws InterruptedException {
        List<String> lifecycleOrder = List.of(
                "ApplicationStop", "DownloadBundle", "BeforeInstall",
                "Install", "AfterInstall", "ApplicationStart", "ValidateService");

        for (String eventName : lifecycleOrder) {
            if (stopFlag.get()) {
                updateTargetStatus(targetMap, "Skipped");
                return false;
            }

            List<Map<String, Object>> hookSteps = appSpec.hooks != null
                    ? appSpec.hooks.get(eventName) : null;

            Map<String, Object> event = addLifecycleEvent(targetMap, eventName);

            // DownloadBundle and Install are infrastructure steps — always succeed
            if ("DownloadBundle".equals(eventName) || "Install".equals(eventName)) {
                finishLifecycleEvent(event, "Succeeded");
                continue;
            }

            if (hookSteps == null || hookSteps.isEmpty()) {
                finishLifecycleEvent(event, "Skipped");
                continue;
            }

            boolean stepOk = executeHookStepsOnInstance(region, instanceId, hookSteps, event);
            if (!stepOk) {
                updateTargetStatus(targetMap, "Failed");
                return false;
            }
        }

        updateTargetStatus(targetMap, "Succeeded");
        return true;
    }

    private boolean executeHookStepsOnInstance(String region, String instanceId,
                                               List<Map<String, Object>> hookSteps,
                                               Map<String, Object> event) throws InterruptedException {
        for (Map<String, Object> step : hookSteps) {
            String location = (String) step.get("location");
            int timeout = toInt(step.get("timeout"), 3600);
            String runas = (String) step.getOrDefault("runas", "root");

            if (location == null) {
                continue;
            }

            // Check if instance is registered with SSM
            boolean hasSsm = ssmCommandService.isInstanceRegistered(instanceId, region);
            if (!hasSsm) {
                LOG.warnv("Instance {0} is not registered with SSM; cannot run hook script {1}",
                        instanceId, location);
                finishLifecycleEvent(event, "Failed", "UnknownError",
                        "CodeDeploy agent was not able to receive the lifecycle event. Check the CodeDeploy agent "
                                + "logs on your host and make sure the agent is running and can connect to the CodeDeploy server.");
                return false;
            }

            try {
                String script = "sh " + location;
                if (!"root".equals(runas)) {
                    script = "sudo -u " + runas + " sh " + location;
                }
                String commandId = ssmCommandService.sendCommandToInstance(
                        instanceId, "AWS-RunShellScript", Map.of("commands", List.of(script)),
                        timeout, region);

                long timeoutNanos = TimeUnit.SECONDS.toNanos(timeout);
                long started = timer.nanoTime();
                String invocationStatus = "InProgress";
                while (timer.nanoTime() - started < timeoutNanos && "InProgress".equals(invocationStatus)) {
                    timer.sleepNanos(SSM_POLL_INTERVAL.toNanos());
                    invocationStatus = ssmCommandService.getCommandInvocationStatus(commandId, instanceId, region);
                }

                if ("InProgress".equals(invocationStatus)) {
                    LOG.warnv("Hook script {0} on instance {1} did not finish within {2}s",
                            location, instanceId, timeout);
                    finishLifecycleEvent(event, "Failed", "ScriptTimedOut",
                            "Script at specified location: " + location + " failed to complete in " + timeout + " seconds");
                    return false;
                }
                if (!"Success".equals(invocationStatus)) {
                    finishLifecycleEvent(event, "Failed", "ScriptFailed",
                            "Script at specified location: " + location + " failed with SSM command status "
                                    + invocationStatus);
                    return false;
                }
            } catch (Exception e) {
                LOG.warnv(e, "SSM execution failed for {0} on {1}: {2}", location, instanceId, e.getMessage());
                finishLifecycleEvent(event, "Failed", "UnknownError",
                        "Failed to execute script " + location + ": " + e.getMessage());
                return false;
            }
        }
        finishLifecycleEvent(event, "Succeeded");
        return true;
    }

    @SuppressWarnings("unchecked")
    private ServerAppSpecInfo parseServerAppSpec(Map<String, Object> revision) {
        if (revision == null) {
            throw new AwsException("InvalidRevisionException", "Revision is required", 400);
        }

        String content = null;
        Object appSpecContent = revision.get("appSpecContent");
        if (appSpecContent instanceof Map<?, ?> asc) {
            content = (String) ((Map<String, Object>) asc).get("content");
        }

        ServerAppSpecInfo info = new ServerAppSpecInfo();
        info.os = "linux";
        info.hooks = new java.util.LinkedHashMap<>();

        if (content == null || content.isBlank()) {
            return info;
        }

        try {
            JsonNode root = yamlMapper.readTree(content);
            if (root.has("os")) {
                info.os = root.get("os").asText("linux");
            }
            JsonNode hooksNode = root.get("hooks");
            if (hooksNode != null && hooksNode.isObject()) {
                hooksNode.fields().forEachRemaining(entry -> {
                    String hookName = entry.getKey();
                    JsonNode steps = entry.getValue();
                    List<Map<String, Object>> stepList = new ArrayList<>();
                    if (steps.isArray()) {
                        steps.forEach(s -> {
                            Map<String, Object> step = new java.util.LinkedHashMap<>();
                            if (s.has("location")) { step.put("location", s.get("location").asText()); }
                            if (s.hasNonNull("timeout")) {
                                JsonNode value = s.get("timeout");
                                int timeout = value.asInt(0);
                                if ((!value.isNumber() && !value.isTextual()) || timeout <= 0) {
                                    throw new IllegalArgumentException("Invalid script timeout");
                                }
                                step.put("timeout", timeout);
                            }
                            if (s.has("runas")) { step.put("runas", s.get("runas").asText("root")); }
                            stepList.add(step);
                        });
                    }
                    info.hooks.put(hookName, stepList);
                });
            }
        } catch (Exception e) {
            throw new AwsException("InvalidRevisionException",
                    "Failed to parse Server AppSpec: " + e.getMessage(), 400);
        }
        return info;
    }

    private List<String> resolveServerTargets(String region, DeploymentGroup group) {
        List<String> instanceIds = new ArrayList<>();

        // EC2 instances by tag filters
        List<Map<String, String>> ec2TagFilters = group.getEc2TagFilters();
        if (ec2TagFilters != null && !ec2TagFilters.isEmpty()) {
            Map<String, List<String>> filters = new java.util.LinkedHashMap<>();
            for (Map<String, String> filter : ec2TagFilters) {
                String key = filter.get("Key");
                String value = filter.get("Value");
                if (key != null && value != null) {
                    filters.computeIfAbsent("tag:" + key, k -> new ArrayList<>()).add(value);
                }
            }
            try {
                ec2Service.describeInstances(region, null, filters).stream()
                        .flatMap(r -> r.getInstances().stream())
                        .map(inst -> inst.getInstanceId())
                        .filter(id -> id != null)
                        .forEach(instanceIds::add);
            } catch (Exception e) {
                LOG.debugv("EC2 tag filter lookup failed: {0}", e.getMessage());
            }
        }

        // On-premises instances by tag filters
        List<Map<String, String>> onPremFilters = group.getOnPremisesInstanceTagFilters();
        if (onPremFilters != null && !onPremFilters.isEmpty()) {
            onPremisesFor(region).values().stream()
                    .filter(inst -> "Registered".equals(inst.getRegistrationStatus()))
                    .filter(inst -> matchesTagFilters(inst.getTags(), onPremFilters))
                    .map(OnPremisesInstance::getInstanceName)
                    .forEach(instanceIds::add);
        }

        // If no filters specified, include all registered on-premises instances
        if ((ec2TagFilters == null || ec2TagFilters.isEmpty())
                && (onPremFilters == null || onPremFilters.isEmpty())) {
            onPremisesFor(region).values().stream()
                    .filter(inst -> "Registered".equals(inst.getRegistrationStatus()))
                    .map(OnPremisesInstance::getInstanceName)
                    .forEach(instanceIds::add);
        }

        return instanceIds;
    }

    private boolean matchesTagFilters(List<Map<String, String>> instanceTags,
                                       List<Map<String, String>> filters) {
        for (Map<String, String> filter : filters) {
            String key = filter.get("Key");
            String value = filter.get("Value");
            if (key == null) { continue; }
            boolean found = instanceTags.stream()
                    .anyMatch(t -> key.equals(t.get("Key"))
                            && (value == null || value.equals(t.get("Value"))));
            if (!found) { return false; }
        }
        return true;
    }

    private AppSpecInfo parseAppSpec(Map<String, Object> revision) {
        if (revision == null) {
            throw new AwsException("InvalidRevisionException", "Revision is required", 400);
        }
        Object appSpecContent = revision.get("appSpecContent");
        if (!(appSpecContent instanceof Map)) {
            throw new AwsException("InvalidRevisionException", "Missing appSpecContent in revision", 400);
        }
        String content = (String) ((Map<String, Object>) appSpecContent).get("content");
        if (content == null || content.isBlank()) {
            throw new AwsException("InvalidRevisionException", "Missing content in appSpecContent", 400);
        }

        AppSpecInfo info = new AppSpecInfo();
        try {
            JsonNode root = mapper.readTree(content);
            JsonNode resources = root.get("Resources");
            if (resources != null && resources.isArray() && !resources.isEmpty()) {
                JsonNode firstResource = resources.get(0);
                if (firstResource.isObject()) {
                    JsonNode resourceNode = firstResource.fields().next().getValue();
                    JsonNode props = resourceNode.path("Properties");
                    info.functionName = props.path("Name").asText(null);
                    info.aliasName = props.path("Alias").asText(null);
                    info.currentVersion = props.path("CurrentVersion").asText(null);
                    info.targetVersion = props.path("TargetVersion").asText(null);
                }
            }
            JsonNode hooks = root.get("Hooks");
            if (hooks != null && hooks.isArray()) {
                for (JsonNode hook : hooks) {
                    if (hook.has("BeforeAllowTraffic")) {
                        info.beforeAllowTraffic = hook.get("BeforeAllowTraffic").asText(null);
                    }
                    if (hook.has("AfterAllowTraffic")) {
                        info.afterAllowTraffic = hook.get("AfterAllowTraffic").asText(null);
                    }
                }
            }
        } catch (Exception e) {
            throw new AwsException("InvalidRevisionException", "Failed to parse AppSpec content: " + e.getMessage(), 400);
        }

        if (info.functionName == null || info.aliasName == null || info.targetVersion == null) {
            throw new AwsException("InvalidRevisionException",
                    "AppSpec must specify Name, Alias, and TargetVersion", 400);
        }
        return info;
    }

    @SuppressWarnings("unchecked")
    private EcsAppSpecInfo parseEcsAppSpec(Map<String, Object> revision) {
        if (revision == null) {
            throw new AwsException("InvalidRevisionException", "Revision is required", 400);
        }
        Object appSpecContent = revision.get("appSpecContent");
        if (!(appSpecContent instanceof Map)) {
            throw new AwsException("InvalidRevisionException", "Missing appSpecContent in revision", 400);
        }
        String content = (String) ((Map<String, Object>) appSpecContent).get("content");
        if (content == null || content.isBlank()) {
            throw new AwsException("InvalidRevisionException", "Missing content in appSpecContent", 400);
        }

        EcsAppSpecInfo info = new EcsAppSpecInfo();
        try {
            JsonNode root = mapper.readTree(content);
            JsonNode resources = root.get("Resources");
            if (resources != null && resources.isArray() && !resources.isEmpty()) {
                JsonNode firstResource = resources.get(0);
                if (firstResource.isObject()) {
                    JsonNode resourceNode = firstResource.fields().next().getValue();
                    JsonNode props = resourceNode.path("Properties");
                    info.taskDefinition = props.path("TaskDefinition").asText(null);
                    JsonNode lbInfo = props.path("LoadBalancerInfo");
                    if (!lbInfo.isMissingNode()) {
                        info.containerName = lbInfo.path("ContainerName").asText(null);
                        info.containerPort = lbInfo.path("ContainerPort").asInt(80);
                    }
                }
            }
            JsonNode hooks = root.get("Hooks");
            if (hooks != null && hooks.isArray()) {
                for (JsonNode hook : hooks) {
                    if (hook.has("BeforeInstall")) {
                        info.beforeInstall = hook.get("BeforeInstall").asText(null);
                    }
                    if (hook.has("AfterInstall")) {
                        info.afterInstall = hook.get("AfterInstall").asText(null);
                    }
                    if (hook.has("BeforeAllowTraffic")) {
                        info.beforeAllowTraffic = hook.get("BeforeAllowTraffic").asText(null);
                    }
                    if (hook.has("AfterAllowTraffic")) {
                        info.afterAllowTraffic = hook.get("AfterAllowTraffic").asText(null);
                    }
                }
            }
        } catch (Exception e) {
            throw new AwsException("InvalidRevisionException", "Failed to parse ECS AppSpec: " + e.getMessage(), 400);
        }

        if (info.taskDefinition == null) {
            throw new AwsException("InvalidRevisionException", "ECS AppSpec must specify TaskDefinition", 400);
        }
        return info;
    }

    @SuppressWarnings("unchecked")
    private String createEcsDeployment(String region, String appName, String groupName,
                                       DeploymentGroup group, String configName,
                                       Map<String, Object> revision, String description) {
        EcsAppSpecInfo appSpec = parseEcsAppSpec(revision);
        String effectiveConfig = configName != null ? configName : group.getDeploymentConfigName();

        String deploymentId = generateDeploymentId();
        double now = EpochSeconds.fromInstant(clock.instant());

        Deployment deployment = new Deployment();
        deployment.setDeploymentId(deploymentId);
        deployment.setApplicationName(appName);
        deployment.setDeploymentGroupName(groupName);
        deployment.setDeploymentConfigName(effectiveConfig);
        deployment.setStatus("Queued");
        deployment.setRevision(revision);
        deployment.setCreateTime(now);
        deployment.setDescription(description);
        deployment.setCreator("user");
        deployment.setComputePlatform("ECS");
        deploymentsFor(region).put(deploymentId, deployment);

        // Determine ECS cluster/service from deployment group
        String clusterName = "default";
        String serviceName = null;
        List<Map<String, Object>> ecsSvcs = group.getEcsServices();
        if (ecsSvcs != null && !ecsSvcs.isEmpty()) {
            Map<String, Object> svc = ecsSvcs.get(0);
            clusterName = (String) svc.getOrDefault("clusterName", "default");
            serviceName = (String) svc.get("serviceName");
        }
        if (serviceName == null) {
            throw new AwsException("InvalidDeploymentConfigException",
                    "ECS deployment group must specify ecsServices", 400);
        }

        // Determine blue/green TG ARNs from loadBalancerInfo
        String blueTgArn = null;
        String greenTgArn = null;
        List<String> listenerArns = new ArrayList<>();
        Map<String, Object> lbInfo = group.getLoadBalancerInfo();
        if (lbInfo != null) {
            List<Map<String, Object>> pairList = (List<Map<String, Object>>) lbInfo.get("targetGroupPairInfoList");
            if (pairList != null && !pairList.isEmpty()) {
                Map<String, Object> pair = pairList.get(0);
                List<Map<String, Object>> tgList = (List<Map<String, Object>>) pair.get("targetGroups");
                if (tgList != null && tgList.size() >= 2) {
                    String blueName = (String) tgList.get(0).get("name");
                    String greenName = (String) tgList.get(1).get("name");
                    TargetGroup blueTg = elbV2Service.getTargetGroupByName(region, blueName);
                    TargetGroup greenTg = elbV2Service.getTargetGroupByName(region, greenName);
                    if (blueTg != null) { blueTgArn = blueTg.getTargetGroupArn(); }
                    if (greenTg != null) { greenTgArn = greenTg.getTargetGroupArn(); }
                }
                Map<String, Object> prodRoute = (Map<String, Object>) pair.get("prodTrafficRoute");
                if (prodRoute != null) {
                    List<String> arns = (List<String>) prodRoute.get("listenerArns");
                    if (arns != null) { listenerArns.addAll(arns); }
                }
            }
        }

        String targetId = clusterName + ":" + serviceName;
        String targetArn = AwsArnUtils.Arn.of("ecs", region, regionResolver.getAccountId(), "service/" + clusterName + "/" + serviceName).toString();

        Map<String, Object> ecsTargetMap = new ConcurrentHashMap<>();
        ecsTargetMap.put("deploymentId", deploymentId);
        ecsTargetMap.put("targetId", targetId);
        ecsTargetMap.put("targetArn", targetArn);
        ecsTargetMap.put("status", "Pending");
        ecsTargetMap.put("lastUpdatedAt", now);
        ecsTargetMap.put("lifecycleEvents", new CopyOnWriteArrayList<>());
        ecsTargetMap.put("taskSetsInfo", new CopyOnWriteArrayList<>());

        Map<String, Object> targetMap = new ConcurrentHashMap<>();
        targetMap.put("deploymentTargetType", "ECSTarget");
        targetMap.put("ecsTarget", ecsTargetMap);
        ConcurrentHashMap<String, Map<String, Object>> ecsTargets = new ConcurrentHashMap<>();
        ecsTargets.put(targetId, targetMap);
        deploymentTargetsFor(region).put(deploymentId, ecsTargets);

        AtomicBoolean stopFlag = new AtomicBoolean(false);
        stopFlags.put(deploymentId, stopFlag);

        final String finalCluster = clusterName;
        final String finalService = serviceName;
        final String finalBlueTgArn = blueTgArn;
        final String finalGreenTgArn = greenTgArn;
        final List<String> finalListenerArns = listenerArns;
        Thread.ofVirtual().name("codedeploy-ecs-" + deploymentId).start(
                () -> runEcsStateMachine(region, deployment, appSpec, ecsTargetMap, stopFlag,
                        effectiveConfig, finalCluster, finalService,
                        finalBlueTgArn, finalGreenTgArn, finalListenerArns));

        return deploymentId;
    }

    private void runEcsStateMachine(String region, Deployment deployment, EcsAppSpecInfo appSpec,
                                    Map<String, Object> ecsTargetMap, AtomicBoolean stopFlag,
                                    String configName, String clusterName, String serviceName,
                                    String blueTgArn, String greenTgArn,
                                    List<String> listenerArns) {
        String deploymentId = deployment.getDeploymentId();
        TaskSet greenTaskSet = null;
        TaskSet blueTaskSet = null;
        try {
            deployment.setStatus("InProgress");
            deployment.setStartTime(EpochSeconds.fromInstant(clock.instant()));
            updateEcsTargetStatus(ecsTargetMap, "InProgress");

            if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }

            // Find existing primary (blue) task set
            List<TaskSet> existing = ecsService.describeTaskSets(clusterName, serviceName, null, region);
            blueTaskSet = existing.stream()
                    .filter(ts -> "PRIMARY".equals(ts.getStatus()))
                    .findFirst()
                    .orElse(existing.isEmpty() ? null : existing.get(0));

            if (appSpec.beforeInstall != null) {
                boolean ok = invokeHook(region, deployment, appSpec.beforeInstall,
                        "BeforeInstall", ecsTargetMap, stopFlag);
                if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }
                if (!ok) {
                    finishEcsFailed(deployment, ecsTargetMap, "HOOK_EXECUTION_FAILURE",
                            "Hook function reported failure: BeforeInstall");
                    return;
                }
            }

            if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }

            // Install: create green task set
            Map<String, Object> installEvent = addLifecycleEvent(ecsTargetMap, "Install");
            try {
                CreateTaskSetRequest createTaskSet = new CreateTaskSetRequest();
                createTaskSet.setCluster(clusterName);
                createTaskSet.setService(serviceName);
                createTaskSet.setTaskDefinition(appSpec.taskDefinition);
                createTaskSet.setScaleValue(100.0);
                // ECS reports the deployment id as the task set's externalId, and CODE_DEPLOY as
                // its startedBy, which is how a client tells a CodeDeploy set from an external one.
                createTaskSet.setExternalId(deploymentId);
                createTaskSet.setStartedBy("CODE_DEPLOY");
                greenTaskSet = ecsService.createTaskSet(createTaskSet, region);
                appendTaskSetInfo(ecsTargetMap, greenTaskSet, greenTgArn, 0.0);
                finishLifecycleEvent(installEvent, "Succeeded");
            } catch (Exception e) {
                finishLifecycleEvent(installEvent, "Failed");
                finishEcsFailed(deployment, ecsTargetMap, "InstallFailed", e.getMessage());
                return;
            }

            if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }

            if (appSpec.afterInstall != null) {
                boolean ok = invokeHook(region, deployment, appSpec.afterInstall,
                        "AfterInstall", ecsTargetMap, stopFlag);
                if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }
                if (!ok) {
                    finishEcsFailed(deployment, ecsTargetMap, "HOOK_EXECUTION_FAILURE",
                            "Hook function reported failure: AfterInstall");
                    return;
                }
            }

            if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }

            if (appSpec.beforeAllowTraffic != null) {
                boolean ok = invokeHook(region, deployment, appSpec.beforeAllowTraffic,
                        "BeforeAllowTraffic", ecsTargetMap, stopFlag);
                if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }
                if (!ok) {
                    finishEcsFailed(deployment, ecsTargetMap, "HOOK_EXECUTION_FAILURE",
                            "Hook function reported failure: BeforeAllowTraffic");
                    return;
                }
            }

            if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }

            // AllowTraffic: shift ELB traffic blue → green
            if (!listenerArns.isEmpty() && blueTgArn != null && greenTgArn != null) {
                executeEcsAllowTraffic(region, deployment, configName, ecsTargetMap,
                        listenerArns, blueTgArn, greenTgArn, stopFlag);
            } else {
                Map<String, Object> allowEvent = addLifecycleEvent(ecsTargetMap, "AllowTraffic");
                finishLifecycleEvent(allowEvent, "Succeeded");
            }

            if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }

            if (appSpec.afterAllowTraffic != null) {
                boolean ok = invokeHook(region, deployment, appSpec.afterAllowTraffic,
                        "AfterAllowTraffic", ecsTargetMap, stopFlag);
                if (stopFlag.get()) { finishEcsStopped(deployment, ecsTargetMap); return; }
                if (!ok) {
                    finishEcsFailed(deployment, ecsTargetMap, "HOOK_EXECUTION_FAILURE",
                            "Hook function reported failure: AfterAllowTraffic");
                    return;
                }
            }

            // Promote green as primary
            if (greenTaskSet != null) {
                ecsService.updateServicePrimaryTaskSet(clusterName, serviceName,
                        greenTaskSet.getTaskSetArn(), region);
            }

            // Terminate blue task set
            if (blueTaskSet != null) {
                Map<String, Object> terminateEvent = addLifecycleEvent(ecsTargetMap, "TerminateBlueInstances");
                try {
                    ecsService.deleteTaskSet(clusterName, serviceName,
                            blueTaskSet.getTaskSetArn(), true, region);
                    finishLifecycleEvent(terminateEvent, "Succeeded");
                } catch (Exception e) {
                    LOG.debugv("Could not delete blue task set: {0}", e.getMessage());
                    finishLifecycleEvent(terminateEvent, "Succeeded");
                }
            }

            updateEcsTargetStatus(ecsTargetMap, "Succeeded");
            deployment.setStatus("Succeeded");
            deployment.setCompleteTime(EpochSeconds.fromInstant(clock.instant()));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishEcsStopped(deployment, ecsTargetMap);
        } catch (Exception e) {
            LOG.warnv("ECS deployment {0} failed: {1}", deploymentId, e.getMessage());
            finishEcsFailed(deployment, ecsTargetMap, "DeploymentFailed", e.getMessage());
        } finally {
            stopFlags.remove(deploymentId);
        }
    }

    private void executeEcsAllowTraffic(String region, Deployment deployment, String configName,
                                        Map<String, Object> ecsTargetMap, List<String> listenerArns,
                                        String blueTgArn, String greenTgArn,
                                        AtomicBoolean stopFlag) throws InterruptedException {
        TrafficRoutingInfo trc = getTrafficRoutingInfo(region, configName);
        Map<String, Object> event = addLifecycleEvent(ecsTargetMap, "AllowTraffic");
        try {
            switch (trc.type) {
                case "TimeBasedCanary" -> {
                    for (String listenerArn : listenerArns) {
                        elbV2Service.shiftListenerForward(region, listenerArn,
                                blueTgArn, greenTgArn, trc.percentage);
                    }
                    long waitMs = Math.min(trc.intervalSeconds * 1000L, 5000L);
                    if (waitMs > 0 && !stopFlag.get()) {
                        waitUnlessStopped(TimeUnit.MILLISECONDS.toNanos(waitMs), stopFlag);
                    }
                    if (!stopFlag.get()) {
                        for (String listenerArn : listenerArns) {
                            elbV2Service.shiftListenerForward(region, listenerArn,
                                    blueTgArn, greenTgArn, 100);
                        }
                    }
                }
                case "TimeBasedLinear" -> {
                    int steps = (int) Math.ceil(100.0 / trc.percentage);
                    for (int step = 1; step <= steps && !stopFlag.get(); step++) {
                        int pct = Math.min(step * trc.percentage, 100);
                        for (String listenerArn : listenerArns) {
                            elbV2Service.shiftListenerForward(region, listenerArn,
                                    blueTgArn, greenTgArn, pct);
                        }
                        if (pct < 100) {
                            long waitMs = Math.min(trc.intervalSeconds * 1000L, 2000L);
                            if (waitMs > 0) {
                                waitUnlessStopped(TimeUnit.MILLISECONDS.toNanos(waitMs), stopFlag);
                            }
                        }
                    }
                }
                default -> {
                    for (String listenerArn : listenerArns) {
                        elbV2Service.shiftListenerForward(region, listenerArn,
                                blueTgArn, greenTgArn, 100);
                    }
                }
            }
            finishLifecycleEvent(event, "Succeeded");
        } catch (InterruptedException e) {
            finishLifecycleEvent(event, "Failed");
            throw e;
        } catch (Exception e) {
            finishLifecycleEvent(event, "Failed");
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private void appendTaskSetInfo(Map<String, Object> ecsTargetMap, TaskSet ts,
                                   String tgArn, double trafficWeight) {
        Map<String, Object> tsInfo = new ConcurrentHashMap<>();
        tsInfo.put("identifer", ts.getId());
        tsInfo.put("desiredCount", ts.getComputedDesiredCount());
        tsInfo.put("pendingCount", ts.getPendingCount());
        tsInfo.put("runningCount", ts.getRunningCount());
        tsInfo.put("status", ts.getStatus());
        tsInfo.put("trafficWeight", trafficWeight);
        if (tgArn != null) {
            tsInfo.put("targetGroup", Map.of("arn", tgArn));
        }
        List<Map<String, Object>> taskSetsInfo = (List<Map<String, Object>>) ecsTargetMap.get("taskSetsInfo");
        if (taskSetsInfo != null) {
            taskSetsInfo.add(tsInfo);
        }
    }

    private void updateEcsTargetStatus(Map<String, Object> ecsTargetMap, String status) {
        ecsTargetMap.put("status", status);
        ecsTargetMap.put("lastUpdatedAt", EpochSeconds.fromInstant(clock.instant()));
    }

    private void finishEcsStopped(Deployment deployment, Map<String, Object> ecsTargetMap) {
        updateEcsTargetStatus(ecsTargetMap, "Skipped");
        deployment.setStatus("Stopped");
        deployment.setCompleteTime(EpochSeconds.fromInstant(clock.instant()));
    }

    private void finishEcsFailed(Deployment deployment, Map<String, Object> ecsTargetMap,
                                  String errorCode, String message) {
        updateEcsTargetStatus(ecsTargetMap, "Failed");
        deployment.setStatus("Failed");
        deployment.setCompleteTime(EpochSeconds.fromInstant(clock.instant()));
        deployment.setErrorInformation(Map.of("code", errorCode, "message", message != null ? message : ""));
    }

    @SuppressWarnings("unchecked")
    private TrafficRoutingInfo getTrafficRoutingInfo(String region, String configName) {
        if (configName == null) {
            return new TrafficRoutingInfo("AllAtOnce", 100, 0);
        }
        DeploymentConfig cfg = deploymentConfigsFor(region).get(configName);
        if (cfg == null || cfg.getTrafficRoutingConfig() == null) {
            return new TrafficRoutingInfo("AllAtOnce", 100, 0);
        }
        Map<String, Object> trc = (Map<String, Object>) cfg.getTrafficRoutingConfig();
        String type = (String) trc.getOrDefault("type", "AllAtOnce");

        if ("TimeBasedCanary".equals(type)) {
            Map<String, Object> canary = (Map<String, Object>) trc.get("timeBasedCanary");
            if (canary != null) {
                int pct = toInt(canary.get("canaryPercentage"), 10);
                int minutes = toInt(canary.get("canaryInterval"), 5);
                return new TrafficRoutingInfo(type, pct, minutes * 60);
            }
        } else if ("TimeBasedLinear".equals(type)) {
            Map<String, Object> linear = (Map<String, Object>) trc.get("timeBasedLinear");
            if (linear != null) {
                int pct = toInt(linear.get("linearPercentage"), 10);
                int minutes = toInt(linear.get("linearInterval"), 1);
                return new TrafficRoutingInfo(type, pct, minutes * 60);
            }
        }
        return new TrafficRoutingInfo("AllAtOnce", 100, 0);
    }

    private void runStateMachine(String region, Deployment deployment, AppSpecInfo appSpec,
                                  Map<String, Object> lambdaTargetMap, AtomicBoolean stopFlag, String configName,
                                  String serviceRoleArn) {
        String deploymentId = deployment.getDeploymentId();
        try {
            deployment.setStatus("InProgress");
            deployment.setStartTime(EpochSeconds.fromInstant(clock.instant()));
            updateTargetStatus(lambdaTargetMap, "InProgress");

            if (stopFlag.get()) { finishStopped(deployment, lambdaTargetMap); return; }

            requireLambdaPermission(region, serviceRoleArn, appSpec.functionName, appSpec.aliasName, "GetAlias");
            lambdaService.getAlias(region, appSpec.functionName, appSpec.aliasName);

            if (appSpec.beforeAllowTraffic != null) {
                requireLambdaPermission(region, serviceRoleArn, appSpec.beforeAllowTraffic, null, "InvokeFunction");
                boolean ok = invokeHook(region, deployment, appSpec.beforeAllowTraffic,
                        "BeforeAllowTraffic", lambdaTargetMap, stopFlag);
                if (stopFlag.get()) { finishStopped(deployment, lambdaTargetMap); return; }
                if (!ok) { finishFailed(deployment, lambdaTargetMap, "HOOK_EXECUTION_FAILURE",
                        "Hook function reported failure: BeforeAllowTraffic"); return; }
            }

            if (stopFlag.get()) { finishStopped(deployment, lambdaTargetMap); return; }

            executeAllowTraffic(region, deployment, appSpec, configName, lambdaTargetMap, stopFlag, serviceRoleArn);

            if (stopFlag.get()) { finishStopped(deployment, lambdaTargetMap); return; }

            if (appSpec.afterAllowTraffic != null) {
                requireLambdaPermission(region, serviceRoleArn, appSpec.afterAllowTraffic, null, "InvokeFunction");
                boolean ok = invokeHook(region, deployment, appSpec.afterAllowTraffic,
                        "AfterAllowTraffic", lambdaTargetMap, stopFlag);
                if (stopFlag.get()) { finishStopped(deployment, lambdaTargetMap); return; }
                if (!ok) { finishFailed(deployment, lambdaTargetMap, "HOOK_EXECUTION_FAILURE",
                        "Hook function reported failure: AfterAllowTraffic"); return; }
            }

            updateTargetStatus(lambdaTargetMap, "Succeeded");
            deployment.setStatus("Succeeded");
            deployment.setCompleteTime(EpochSeconds.fromInstant(clock.instant()));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finishStopped(deployment, lambdaTargetMap);
        } catch (Exception e) {
            LOG.warnv("Deployment {0} failed: {1}", deploymentId, e.getMessage());
            String code = e instanceof AwsException failure && "IAM_ROLE_PERMISSIONS".equals(failure.getErrorCode())
                    ? failure.getErrorCode() : "DeploymentFailed";
            finishFailed(deployment, lambdaTargetMap, code, e.getMessage());
        } finally {
            stopFlags.remove(deploymentId);
        }
    }

    private void executeAllowTraffic(String region, Deployment deployment, AppSpecInfo appSpec,
                                      String configName, Map<String, Object> lambdaTargetMap,
                                      AtomicBoolean stopFlag, String serviceRoleArn) throws InterruptedException {
        TrafficRoutingInfo trc = getTrafficRoutingInfo(region, configName);
        Map<String, Object> event = addLifecycleEvent(lambdaTargetMap, "AllowTraffic");

        try {
            switch (trc.type) {
                case "TimeBasedCanary" -> {
                    // Step 1: shift canaryPercentage to targetVersion
                    double canaryWeight = trc.percentage / 100.0;
                    Map<String, Double> routing = Map.of(appSpec.targetVersion, canaryWeight);
                    updateLambdaAlias(region, serviceRoleArn, appSpec, appSpec.currentVersion, routing);

                    if (!waitForTrafficInterval(trc.intervalSeconds, stopFlag)) {
                        finishLifecycleEvent(event, "Skipped");
                        return;
                    }

                    // Step 2: flip 100% to targetVersion
                    updateLambdaAlias(region, serviceRoleArn, appSpec, appSpec.targetVersion, Map.of());
                }
                case "TimeBasedLinear" -> {
                    int steps = (int) Math.ceil(100.0 / trc.percentage);
                    for (int step = 1; step <= steps; step++) {
                        if (stopFlag.get()) {
                            finishLifecycleEvent(event, "Skipped");
                            return;
                        }
                        int pct = Math.min(step * trc.percentage, 100);
                        if (pct >= 100) {
                            updateLambdaAlias(region, serviceRoleArn, appSpec, appSpec.targetVersion, Map.of());
                        } else {
                            double weight = pct / 100.0;
                            updateLambdaAlias(region, serviceRoleArn, appSpec, appSpec.currentVersion,
                                    Map.of(appSpec.targetVersion, weight));
                            if (!waitForTrafficInterval(trc.intervalSeconds, stopFlag)) {
                                finishLifecycleEvent(event, "Skipped");
                                return;
                            }
                        }
                    }
                }
                default -> {
                    // AllAtOnce: flip immediately
                    updateLambdaAlias(region, serviceRoleArn, appSpec, appSpec.targetVersion, Map.of());
                }
            }
            finishLifecycleEvent(event, "Succeeded");
        } catch (Exception e) {
            finishLifecycleEvent(event, "Failed");
            throw e;
        }
    }

    private void updateLambdaAlias(String region, String roleArn, AppSpecInfo appSpec, String version,
                                    Map<String, Double> routing) {
        requireLambdaPermission(region, roleArn, appSpec.functionName, appSpec.aliasName, "UpdateAlias");
        lambdaService.updateAlias(region, appSpec.functionName, appSpec.aliasName, version, null, routing);
    }

    private void requireLambdaPermission(String region, String roleArn, String function, String qualifier,
                                           String operation) {
        LambdaArnUtils.resolveWithRegion(region, function, qualifier);
        String account = regionResolver.getAccountId();
        String partition = AwsRegions.partitionFor(region);
        String arn = LambdaArnUtils.functionArn(function, partition, region, account);
        if (qualifier != null && LambdaArnUtils.resolve(function).qualifier() == null) {
            arn += ":" + qualifier;
        }
        if (roleAuthorizer.authorizeWithoutSourceContext("codedeploy", roleArn, "lambda:" + operation,
                arn, account, partition) != Decision.ALLOW) {
            throw new AwsException("IAM_ROLE_PERMISSIONS",
                    "The CodeDeploy service role cannot perform lambda:" + operation + " for " + arn, 403);
        }
    }

    private boolean waitForTrafficInterval(int intervalSeconds, AtomicBoolean stopFlag)
            throws InterruptedException {
        return waitUnlessStopped(TimeUnit.SECONDS.toNanos(intervalSeconds), stopFlag);
    }

    /** Waits {@code duration} nanoseconds, looking at the stop flag every stop poll; false once it is set. */
    private boolean waitUnlessStopped(long duration, AtomicBoolean stopFlag) throws InterruptedException {
        long started = timer.nanoTime();
        while (!stopFlag.get()) {
            long remaining = duration - (timer.nanoTime() - started);
            if (remaining <= 0) {
                return true;
            }
            timer.sleepNanos(Math.min(remaining, STOP_POLL_INTERVAL.toNanos()));
        }
        return false;
    }

    private boolean invokeHook(String region, Deployment deployment, String hookFunctionName,
                                String lifecycleEventName, Map<String, Object> lambdaTargetMap,
                                AtomicBoolean stopFlag) throws InterruptedException {
        String executionId = UUID.randomUUID().toString();
        CompletableFuture<String> future = new CompletableFuture<>();
        hookFutures.put(executionId, future);

        Map<String, Object> event = addLifecycleEvent(lambdaTargetMap, lifecycleEventName);

        try {
            String payload = "{\"DeploymentId\":\"" + deployment.getDeploymentId()
                    + "\",\"LifecycleEventHookExecutionId\":\"" + executionId + "\"}";

            String failureMessage = null;
            try {
                InvokeResult result = lambdaService.invoke(region, hookFunctionName,
                        payload.getBytes(), InvocationType.RequestResponse);
                if (result.getFunctionError() != null && !future.isDone()) {
                    failureMessage = "Lambda function " + hookFunctionName
                            + " returned a function error: " + result.getFunctionError();
                    future.complete("Failed");
                }
            } catch (Exception e) {
                LOG.warnv(e, "Hook Lambda {0} for lifecycle event {1} could not be invoked: {2}",
                        hookFunctionName, lifecycleEventName, e.getMessage());
                failureMessage = "Lambda function " + hookFunctionName + " could not be invoked: " + e.getMessage();
                if (!future.isDone()) {
                    future.complete("Failed");
                }
            }

            String status = null;
            long deadlineNanos = timer.nanoTime() + hookCallbackTimeout.toNanos();
            while (status == null) {
                if (stopFlag.get()) {
                    finishLifecycleEvent(event, "Skipped");
                    return false;
                }
                long remainingNanos = deadlineNanos - timer.nanoTime();
                if (remainingNanos <= 0) {
                    LOG.warnv("Hook {0} for lifecycle event {1} did not call PutLifecycleEventHookExecutionStatus within {2}",
                            hookFunctionName, lifecycleEventName, hookCallbackTimeout);
                    status = "Failed";
                    failureMessage = "Lambda function " + hookFunctionName
                            + " did not call PutLifecycleEventHookExecutionStatus within "
                            + hookCallbackTimeout.toSeconds() + " seconds";
                    break;
                }
                long sliceNanos = Math.min(remainingNanos, STOP_POLL_INTERVAL.toNanos());
                if (!timer.awaitDone(future, sliceNanos)) {
                    continue;
                }
                try {
                    status = future.get();
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e);
                }
            }

            if ("Failed".equals(status)) {
                finishLifecycleEvent(event, "Failed", "UnknownError",
                        failureMessage != null ? failureMessage
                                : "Lambda function " + hookFunctionName + " reported that lifecycle event "
                                        + lifecycleEventName + " failed");
            } else {
                finishLifecycleEvent(event, status);
            }
            return "Succeeded".equals(status);
        } finally {
            hookFutures.remove(executionId);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> addLifecycleEvent(Map<String, Object> lambdaTargetMap, String name) {
        Map<String, Object> event = new ConcurrentHashMap<>();
        event.put("lifecycleEventName", name);
        event.put("startTime", EpochSeconds.fromInstant(clock.instant()));
        event.put("status", "InProgress");
        List<Map<String, Object>> events = (List<Map<String, Object>>) lambdaTargetMap.get("lifecycleEvents");
        if (events != null) {
            events.add(event);
        }
        lambdaTargetMap.put("lastUpdatedAt", EpochSeconds.fromInstant(clock.instant()));
        return event;
    }

    private void finishLifecycleEvent(Map<String, Object> event, String status) {
        event.put("endTime", EpochSeconds.fromInstant(clock.instant()));
        event.put("status", status);
    }

    private void finishLifecycleEvent(Map<String, Object> event, String status,
                                       String diagnosticsErrorCode, String diagnosticsMessage) {
        finishLifecycleEvent(event, status);
        event.put("diagnostics", Map.of("errorCode", diagnosticsErrorCode, "message", diagnosticsMessage));
    }

    private void updateTargetStatus(Map<String, Object> lambdaTargetMap, String status) {
        lambdaTargetMap.put("status", status);
        lambdaTargetMap.put("lastUpdatedAt", EpochSeconds.fromInstant(clock.instant()));
    }

    private void finishStopped(Deployment deployment, Map<String, Object> lambdaTargetMap) {
        updateTargetStatus(lambdaTargetMap, "Skipped");
        deployment.setStatus("Stopped");
        deployment.setCompleteTime(EpochSeconds.fromInstant(clock.instant()));
    }

    private void finishFailed(Deployment deployment, Map<String, Object> lambdaTargetMap,
                               String errorCode, String message) {
        updateTargetStatus(lambdaTargetMap, "Failed");
        deployment.setStatus("Failed");
        deployment.setCompleteTime(EpochSeconds.fromInstant(clock.instant()));
        deployment.setErrorInformation(Map.of("code", errorCode, "message", message != null ? message : ""));
    }

    private String generateDeploymentId() {
        String hex = UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase();
        return "d-" + hex;
    }

    private int toInt(Object val, int def) {
        if (val instanceof Number n) { return n.intValue(); }
        if (val instanceof String s) { try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {} }
        return def;
    }

    // ---- Tags ----

    public void tagResource(String arn, List<Map<String, String>> tagList) {
        Map<String, String> tagMap = tags.computeIfAbsent(arn, k -> new ConcurrentHashMap<>());
        for (Map<String, String> t : tagList) {
            tagMap.put(t.get("Key"), t.get("Value"));
        }
        tags.put(arn, tagMap); // write back the in-place inner-map mutation
    }

    public void untagResource(String arn, List<String> tagKeys) {
        Map<String, String> tagMap = tags.get(arn);
        if (tagMap != null) {
            tagKeys.forEach(tagMap::remove);
            tags.put(arn, tagMap); // write back the in-place inner-map mutation
        }
    }

    public List<Map<String, String>> listTagsForResource(String arn) {
        Map<String, String> tagMap = tags.getOrDefault(arn, Map.of());
        return tagMap.entrySet().stream()
                .map(e -> Map.of("Key", e.getKey(), "Value", e.getValue()))
                .collect(Collectors.toList());
    }

    public String applicationArn(String region, String name) {
        return AwsArnUtils.Arn.of("codedeploy", region, regionResolver.getAccountId(), "application:" + name).toString();
    }

    public String deploymentGroupArn(String region, String appName, String groupName) {
        return AwsArnUtils.Arn.of("codedeploy", region, regionResolver.getAccountId(), "deploymentgroup:" + appName + "/" + groupName).toString();
    }

    @SuppressWarnings("unchecked")
    private void applyGroupFields(DeploymentGroup group, Map<String, Object> fields) {
        if (fields == null) { return; }
        Object ec2TagFilters = fields.get("ec2TagFilters");
        if (ec2TagFilters instanceof List<?> list) {
            group.setEc2TagFilters((List<Map<String, String>>) list);
        } else if (fields.containsKey("ec2TagFilters") && ec2TagFilters == null) {
            group.setEc2TagFilters(null);
        }
        Object onPremTagFilters = fields.get("onPremisesInstanceTagFilters");
        if (onPremTagFilters instanceof List<?> list) {
            group.setOnPremisesInstanceTagFilters((List<Map<String, String>>) list);
        } else if (fields.containsKey("onPremisesInstanceTagFilters") && onPremTagFilters == null) {
            group.setOnPremisesInstanceTagFilters(null);
        }
        Object asg = fields.get("autoScalingGroups");
        if (asg instanceof List<?> list) {
            group.setAutoScalingGroups(list.stream()
                    .map(value -> value instanceof String name
                            ? Map.<String, Object>of("name", name) : (Map<String, Object>) value)
                    .toList());
        } else if (fields.containsKey("autoScalingGroups") && asg == null) {
            group.setAutoScalingGroups(null);
        }
        setMapField(group, fields, "deploymentStyle", DeploymentGroup::setDeploymentStyle);
        setMapField(group, fields, "blueGreenDeploymentConfiguration", DeploymentGroup::setBlueGreenDeploymentConfiguration);
        setMapField(group, fields, "loadBalancerInfo", DeploymentGroup::setLoadBalancerInfo);
        setMapField(group, fields, "ec2TagSet", DeploymentGroup::setEc2TagSet);
        setMapField(group, fields, "onPremisesTagSet", DeploymentGroup::setOnPremisesTagSet);
        setMapField(group, fields, "alarmConfiguration", DeploymentGroup::setAlarmConfiguration);
        setMapField(group, fields, "autoRollbackConfiguration", DeploymentGroup::setAutoRollbackConfiguration);
        Object triggerConfigs = fields.get("triggerConfigurations");
        if (triggerConfigs instanceof List<?> list) {
            group.setTriggerConfigurations((List<Map<String, Object>>) list);
        } else if (fields.containsKey("triggerConfigurations") && triggerConfigs == null) {
            group.setTriggerConfigurations(null);
        }
        Object ecsServices = fields.get("ecsServices");
        if (ecsServices instanceof List<?> list) {
            group.setEcsServices((List<Map<String, Object>>) list);
        } else if (fields.containsKey("ecsServices") && ecsServices == null) {
            group.setEcsServices(null);
        }
        if (fields.containsKey("computePlatform")) {
            group.setComputePlatform((String) fields.get("computePlatform"));
        }
        if (fields.containsKey("outdatedInstancesStrategy")) {
            group.setOutdatedInstancesStrategy((String) fields.get("outdatedInstancesStrategy"));
        }
        if (fields.containsKey("terminationHookEnabled")) {
            group.setTerminationHookEnabled((Boolean) fields.get("terminationHookEnabled"));
        }
    }

    @SuppressWarnings("unchecked")
    private void setMapField(DeploymentGroup group, Map<String, Object> fields, String key,
                             BiConsumer<DeploymentGroup, Map<String, Object>> setter) {
        Object val = fields.get(key);
        if (val instanceof Map<?, ?> m) {
            setter.accept(group, (Map<String, Object>) m);
        } else if (fields.containsKey(key) && val == null) {
            setter.accept(group, null);
        }
    }

    private void applyTags(String arn, List<Map<String, String>> tagList) {
        Map<String, String> tagMap = tags.computeIfAbsent(arn, k -> new ConcurrentHashMap<>());
        for (Map<String, String> t : tagList) {
            String key = t.containsKey("Key") ? t.get("Key") : t.get("key");
            String value = t.containsKey("Value") ? t.get("Value") : t.get("value");
            if (key != null) { tagMap.put(key, value != null ? value : ""); }
        }
        tags.put(arn, tagMap); // write back the in-place inner-map mutation
    }
}
