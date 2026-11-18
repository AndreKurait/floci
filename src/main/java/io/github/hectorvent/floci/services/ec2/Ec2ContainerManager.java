package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsEndpoints;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.SweepWorker;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLiveness;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.core.common.docker.UserDataPipeline;
import io.github.hectorvent.floci.services.ec2.Ec2InstanceTypeCatalog.CatalogInstanceType;
import io.github.hectorvent.floci.services.ec2.Ec2VolumeBlockDeviceManager.PreparedMount;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceNetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Manages Docker container lifecycle for EC2 instances.
 * Handles launch, stop, start, terminate, and reboot operations.
 * SSH key injection and UserData execution are performed asynchronously after launch.
 * Link-local IMDS routing is prepared before the image starts. SSH initialization and UserData
 * may still be completing in the launch worker when an instance reaches running.
 */
@ApplicationScoped
public class Ec2ContainerManager {

    private static final Logger LOG = Logger.getLogger(Ec2ContainerManager.class);
    // Guest-created /tmp mounts hide files copied through Docker's archive API.
    private static final String USER_DATA_SCRIPT_PATH = UserDataPipeline.USER_DATA_SCRIPT_PATH;
    private static final List<String> ALLOWED_SSHD_PATHS = List.of("/usr/sbin/sshd", "/usr/local/sbin/sshd", "/sbin/sshd");
    /** Exit code the sshd install probe uses for "sshd is present but scp is not". See startSshd. */
    static final int SSH_CLIENT_MISSING_EXIT_CODE = 2;
    private static final int LAUNCH_CORE_THREADS = 4;
    private static final int LAUNCH_MAX_THREADS = 8;
    private static final int LAUNCH_QUEUE_CAPACITY = 64;
    // ponytail: a fixed cap on concurrent post-start IMDS setups; a start beyond it skips the setup.
    private static final int METADATA_SETUP_CONCURRENCY = 16;
    /** Real host public keys, one line per type, come to well under a kilobyte together. */
    static final int SSH_HOST_KEYS_MAX_BYTES = 16 * 1024;
    private static final int SSH_HOST_KEYS_STDERR_BYTES = 1024;
    private static final long USER_DATA_EXECUTION_TIMEOUT_MINUTES = 30;
    /** How often failed teardowns are checked for a backoff that has passed. */
    private static final long TERMINATION_RETRY_SWEEP_SECONDS = 1;
    /** The teardown backoff doubles from one second up to this. */
    private static final int TERMINATION_RETRY_MAX_DELAY_SECONDS = 30;
    private static final Pattern ENVIRONMENT_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern PROCESS_LIMIT = Pattern.compile("([a-z][a-z0-9]*)=(-1|[0-9]+)(?::(-1|[0-9]+))?");
    private static final Set<String> RESERVED_GUEST_ENVIRONMENT = Set.of(
            "AWS_EC2_INSTANCE_ID", "AWS_ACCOUNT_ID", "AWS_REGION", "AWS_DEFAULT_REGION", "AWS_ENDPOINT_URL",
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_SECURITY_TOKEN",
            "AWS_PROFILE", "AWS_DEFAULT_PROFILE", "AWS_SHARED_CREDENTIALS_FILE", "AWS_CONFIG_FILE",
            "AWS_WEB_IDENTITY_TOKEN_FILE", "AWS_ROLE_ARN", "AWS_ROLE_SESSION_NAME",
            "AWS_CONTAINER_AUTHORIZATION_TOKEN", "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE",
            "AWS_IGNORE_CONFIGURED_ENDPOINT_URLS");
    // Wait for capacity so accepted launches are not dropped, but never run launch work on callers.
    static final RejectedExecutionHandler BLOCKING_BACKPRESSURE = (runnable, executor) -> {
        if (executor.isShutdown()) {
            throw new RejectedExecutionException("EC2 container manager is stopped");
        }
        try {
            executor.getQueue().put(runnable);
            if (executor.isShutdown() && executor.getQueue().remove(runnable)) {
                throw new RejectedExecutionException("EC2 container manager is stopped");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("Interrupted while waiting for EC2 launch capacity", e);
        }
    };

    /**
     * Label identifying the Floci deployment that created an EC2 instance container, by its
     * resource namespace and API port. {@link #reconcileOrphanedContainers} lists on the existing
     * {@code io.floci.service=ec2} identity label (see
     * {@link ContainerStorageHelper#resourceIdentityLabels}) and then keeps
     * only containers carrying <em>this</em> deployment's owner: several emulators can share one
     * Docker daemon, and an unscoped sweep would reap a sibling's live instances.
     * {@code floci_namespace} is the documented scoping mechanism for that, but it is absent
     * unless a resource namespace is configured, so it cannot scope the default configuration.
     * Containers created before this label existed carry no owner and are therefore never swept.
     */
    static final String LABEL_OWNER = ContainerStorageHelper.OWNER_LABEL;
    private static final ContainerStorageHelper.LabelAliases ALIASES = ContainerStorageHelper.CONTAINER_LABEL_ALIASES;

    /**
     * Identity of the Floci deployment that owns a container, for scoping the startup sweep.
     * The API port alone collides when two independently namespaced Flocis share a Docker daemon
     * on the same internal port, and each would then reap the other's live containers. Composing
     * the documented resource namespace in front of it separates exactly those deployments; an
     * unnamespaced single Floci keeps the bare port it already stamped.
     */
    private String ownerIdentity() {
        return ContainerStorageHelper.ownerIdentity(config);
    }
    static final String LABEL_SERVICE = ContainerStorageHelper.SERVICE_LABEL;
    static final String SERVICE_VALUE = "ec2";
    static final String LABEL_RESOURCE_ID = ContainerStorageHelper.RESOURCE_ID_LABEL;
    static final String LABEL_REGION = ContainerStorageHelper.REGION_LABEL;

    private static final int CONTAINER_BRIDGE_IP_ATTEMPTS = 30;
    private static final long CONTAINER_BRIDGE_IP_POLL_MILLIS = 500;

    private final Ec2ContainerPause pause;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final ContainerDetector containerDetector;
    private final DockerHostResolver dockerHostResolver;
    private final DockerClient dockerClient;
    private final PortAllocator portAllocator;
    private final EmulatorConfig config;
    private final Ec2NativePeerDependencies nativePeerDependencies;
    private final Ec2MetadataServer metadataServer;
    private final Ec2PortForwardManager portForwardManager;
    private final RegionResolver regionResolver;
    private final ContainerNetworkReachability containerNetworkReachability;
    private final VpcNetworkManager vpcNetworkManager;
    private final ContainerReachableEndpoint reachableEndpoint;
    private final Ec2InstanceTypeCatalog instanceTypeCatalog;
    private final Ec2MetadataNamespace metadataNamespaces;
    private final Ec2SystemdV1Runtime systemdV1;
    private final Ec2GuestReboots guestReboots;
    private final Ec2Ipv6VpcRouter ipv6Router;
    private SecurityGroupFirewallManager firewallManager;
    private final ExecutorService executor;
    private final Semaphore metadataSetupSlots = new Semaphore(METADATA_SETUP_CONCURRENCY);
    private final Map<String, CompletableFuture<Void>> lifecycleOperations = new ConcurrentHashMap<>();
    /** RebootInstances calls queued or running per container: EC2 keeps a rebooting instance running. */
    private final Map<String, Integer> apiReboots = new ConcurrentHashMap<>();
    private final Map<String, String> networkHelpers = new ConcurrentHashMap<>();
    private final Map<String, PendingTermination> pendingTerminations = new ConcurrentHashMap<>();
    private final Map<String, FailedLaunchCleanup> failedLaunchCleanups = new ConcurrentHashMap<>();
    private final AtomicBoolean terminationRetriesStarted = new AtomicBoolean();
    private final SweepWorker terminationRetries;
    /** Stamps the instant an instance reached terminated, which pruning later measures from. */
    private final Clock clock;
    /** Measures the backoff between teardown retries, which is elapsed time and not a date. */
    private final LongSupplier nanoTime;
    /** Instances whose service-network lookup failed and is retried on the next address read. */
    private final Set<String> serviceNetworkRetries = ConcurrentHashMap.newKeySet();
    private final Set<String> serviceNetworkRetriesRunning = ConcurrentHashMap.newKeySet();
    /** Runs IO retries and lifecycle submissions that must not block a launch worker. */
    private final ExecutorService serviceNetworkRetryExecutor;
    private final Duration userDataExecutionTimeout;
    private final Set<ResultCallback<Frame>> activeUserDataCallbacks = ConcurrentHashMap.newKeySet();

    private volatile boolean dockerUnavailableLogged;

    public void refreshSecurityGroups(String accountId, String region, Map<String, SecurityGroup> groups,
                                      Map<String, List<String>> prefixLists) {
        if (firewallManager != null && firewallManager.enabled()) {
            firewallManager.refreshPolicies(accountId, region, groups, prefixLists);
        }
    }

    public void updateSecurityGroups(String eniId, Set<String> groupIds,
                                     Map<String, SecurityGroup> groups,
                                     Map<String, List<String>> prefixLists) {
        if (firewallManager != null && firewallManager.enabled()) {
            firewallManager.updateGroups(eniId, groupIds, groups, prefixLists);
        }
    }

    /** A workload's own Floci-owned namespace, or null for a legacy container. */
    private ProtectedNamespace protectedNamespace(Instance instance) {
        OwnedNamespace namespace = ownedNamespace(instance, instance.getDockerContainerId());
        if (namespace == null) {
            return null;
        }
        String privateAddress = namespacePrivateAddress(instance, namespace.helperId(), namespace.inspection());
        String transportAddress = namespace.securityGroupsEnforced()
                ? Ec2PublicAddressRouting.primaryAddress(namespace.inspection()) : privateAddress;
        return new ProtectedNamespace(namespace.helperId(), transportAddress, privateAddress,
                namespace.securityGroupsEnforced());
    }

    private OwnedNamespace ownedNamespace(Instance instance, String containerId) {
        if (containerId == null) {
            return null;
        }
        InspectContainerResponse worker = dockerClient.inspectContainerCmd(containerId).exec();
        if (systemdV1.rememberGuest(instance, worker)) {
            guestReboots.watch(instance);
        }
        String helperId = namespaceOwnerId(worker);
        if (helperId == null) {
            return null;
        }
        InspectContainerResponse helper = dockerClient.inspectContainerCmd(helperId).exec();
        Map<String, String> labels = helper.getConfig().getLabels();
        boolean securityGroupsEnforced = ALIASES.matches(labels, ContainerStorageHelper.SECURITY_GROUP_HELPER_LABEL, "true");
        if (!ALIASES.consistent(helperId, labels)
                || !(securityGroupsEnforced || "true".equals(labels.get(Ec2MetadataNamespace.HELPER_LABEL)))
                || !"ec2".equals(labels.get(LABEL_SERVICE))
                || !instance.getInstanceId().equals(labels.get(LABEL_RESOURCE_ID))
                || !ALIASES.matches(labels, LABEL_OWNER, ownerIdentity())) {
            throw new IllegalStateException("EC2 workload network namespace is not Floci protected");
        }
        return new OwnedNamespace(helperId, helper, securityGroupsEnforced);
    }

    private record OwnedNamespace(String helperId, InspectContainerResponse inspection,
                                  boolean securityGroupsEnforced) {}

    private record ProtectedNamespace(String helperId, String transportAddress, String privateAddress,
                                      boolean securityGroupsEnforced) {}

    /**
     * The container whose network namespace this one joined through a {@code container:} network
     * mode, as a security-group protected guest joins its firewall helper's; null when it has its own.
     */
    private static String namespaceOwnerId(InspectContainerResponse container) {
        String mode = container.getHostConfig() == null ? null : container.getHostConfig().getNetworkMode();
        return mode != null && mode.startsWith("container:") ? mode.substring("container:".length()) : null;
    }

    public void restoreSecurityGroups(Instance instance, String region, List<SecurityGroup> groups,
                                      Map<String, List<String>> prefixLists) {
        if (firewallManager == null || !firewallManager.enabled()) {
            return;
        }
        ProtectedNamespace namespace = protectedNamespace(instance);
        if (namespace == null || !namespace.securityGroupsEnforced()) {
            throw new IllegalStateException("EC2 workload has no protected network namespace");
        }
        String helperId = namespace.helperId();
        String address = namespace.transportAddress();
        InstanceNetworkInterface eni = instance.getNetworkInterfaces().getFirst();
        String logical = instance.getLogicalPrivateIpAddress() != null
                ? instance.getLogicalPrivateIpAddress() : eni.getPrivateIpAddress();
        firewallManager.register(new SecurityGroupNftCompiler.Endpoint(regionResolver.getAccountId(), region,
                instance.getVpcId(), eni.getNetworkInterfaceId(), logical, address,
                instance.getSecurityGroups().stream().map(GroupIdentifier::getGroupId)
                        .collect(Collectors.toSet()), groups, nativePeerDependencies.connections(), true,
                address.equals(namespace.privateAddress()) ? Set.of() : Set.of(namespace.privateAddress()),
                List.of(), nativePeerDependencies.dependencies()),
                helperId, prefixLists);
    }

    @Inject
    public Ec2ContainerManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ContainerLogStreamer logStreamer,
                               ContainerDetector containerDetector,
                               DockerHostResolver dockerHostResolver,
                               DockerClient dockerClient,
                               PortAllocator portAllocator,
                               EmulatorConfig config,
                               Ec2MetadataServer metadataServer,
                               Ec2PortForwardManager portForwardManager,
                               RegionResolver regionResolver,
                               ContainerNetworkReachability containerNetworkReachability,
                               VpcNetworkManager vpcNetworkManager,
                               ContainerReachableEndpoint reachableEndpoint,
                               SecurityGroupFirewallManager firewallManager,
                               Ec2InstanceTypeCatalog instanceTypeCatalog,
                               Clock clock) {
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, dockerHostResolver, dockerClient,
                portAllocator, config, metadataServer, portForwardManager, regionResolver,
                containerNetworkReachability, vpcNetworkManager, reachableEndpoint, firewallManager,
                instanceTypeCatalog, Ec2ContainerPause.THREAD_SLEEP, createTerminationRetryScheduler(),
                System::nanoTime, clock);
    }

    public Ec2ContainerManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ContainerLogStreamer logStreamer,
                               ContainerDetector containerDetector,
                               DockerHostResolver dockerHostResolver,
                               DockerClient dockerClient,
                               PortAllocator portAllocator,
                               EmulatorConfig config,
                               Ec2MetadataServer metadataServer,
                               Ec2PortForwardManager portForwardManager,
                               RegionResolver regionResolver,
                               ContainerNetworkReachability containerNetworkReachability,
                               VpcNetworkManager vpcNetworkManager,
                               ContainerReachableEndpoint reachableEndpoint,
                               SecurityGroupFirewallManager firewallManager,
                               Ec2InstanceTypeCatalog instanceTypeCatalog) {
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, dockerHostResolver,
                dockerClient, portAllocator, config, metadataServer, portForwardManager, regionResolver,
                containerNetworkReachability, vpcNetworkManager, reachableEndpoint, instanceTypeCatalog);
        this.firewallManager = firewallManager;
    }

    /**
     * The injected wiring with every time source supplied, for tests that own the timeline: the
     * pauses, the timer that retries a failed teardown, the monotonic reading its backoff is measured
     * on, and the clock that stamps a termination.
     */
    Ec2ContainerManager(ContainerBuilder containerBuilder,
                        ContainerLifecycleManager lifecycleManager,
                        ContainerLogStreamer logStreamer,
                        ContainerDetector containerDetector,
                        DockerHostResolver dockerHostResolver,
                        DockerClient dockerClient,
                        PortAllocator portAllocator,
                        EmulatorConfig config,
                        Ec2MetadataServer metadataServer,
                        Ec2PortForwardManager portForwardManager,
                        RegionResolver regionResolver,
                        ContainerNetworkReachability containerNetworkReachability,
                        VpcNetworkManager vpcNetworkManager,
                        ContainerReachableEndpoint reachableEndpoint,
                        SecurityGroupFirewallManager firewallManager,
                        Ec2InstanceTypeCatalog instanceTypeCatalog,
                        Ec2ContainerPause pause,
                        ScheduledExecutorService terminationRetryScheduler,
                        LongSupplier nanoTime,
                        Clock clock) {
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, dockerHostResolver, dockerClient,
                portAllocator, config, metadataServer, portForwardManager, regionResolver,
                containerNetworkReachability, vpcNetworkManager, reachableEndpoint, instanceTypeCatalog,
                createLaunchExecutor(), createServiceNetworkRetryExecutor(),
                Duration.ofMinutes(USER_DATA_EXECUTION_TIMEOUT_MINUTES), pause, terminationRetryScheduler,
                nanoTime, clock);
        this.firewallManager = firewallManager;
    }

    /** The injected wiring with its pauses on {@code pause}, for tests that own the timeline. */
    Ec2ContainerManager(ContainerBuilder containerBuilder,
                        ContainerLifecycleManager lifecycleManager,
                        ContainerLogStreamer logStreamer,
                        ContainerDetector containerDetector,
                        DockerHostResolver dockerHostResolver,
                        DockerClient dockerClient,
                        PortAllocator portAllocator,
                        EmulatorConfig config,
                        Ec2MetadataServer metadataServer,
                        Ec2PortForwardManager portForwardManager,
                        RegionResolver regionResolver,
                        ContainerNetworkReachability containerNetworkReachability,
                        VpcNetworkManager vpcNetworkManager,
                        ContainerReachableEndpoint reachableEndpoint,
                        SecurityGroupFirewallManager firewallManager,
                        Ec2InstanceTypeCatalog instanceTypeCatalog,
                        Ec2ContainerPause pause) {
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, dockerHostResolver, dockerClient,
                portAllocator, config, metadataServer, portForwardManager, regionResolver,
                containerNetworkReachability, vpcNetworkManager, reachableEndpoint, instanceTypeCatalog,
                createLaunchExecutor(), createServiceNetworkRetryExecutor(),
                Duration.ofMinutes(USER_DATA_EXECUTION_TIMEOUT_MINUTES), pause);
        this.firewallManager = firewallManager;
    }

    public Ec2ContainerManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ContainerLogStreamer logStreamer,
                               ContainerDetector containerDetector,
                               DockerHostResolver dockerHostResolver,
                               DockerClient dockerClient,
                               PortAllocator portAllocator,
                               EmulatorConfig config,
                               Ec2MetadataServer metadataServer,
                               Ec2PortForwardManager portForwardManager,
                               RegionResolver regionResolver,
                               ContainerNetworkReachability containerNetworkReachability,
                               VpcNetworkManager vpcNetworkManager,
                               ContainerReachableEndpoint reachableEndpoint,
                               SecurityGroupFirewallManager firewallManager) {
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, dockerHostResolver,
                dockerClient, portAllocator, config, metadataServer, portForwardManager, regionResolver,
                containerNetworkReachability, vpcNetworkManager, reachableEndpoint, firewallManager,
                new Ec2InstanceTypeCatalog());
    }

    public Ec2ContainerManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ContainerLogStreamer logStreamer,
                               ContainerDetector containerDetector,
                               DockerHostResolver dockerHostResolver,
                               DockerClient dockerClient,
                               PortAllocator portAllocator,
                               EmulatorConfig config,
                               Ec2MetadataServer metadataServer,
                               Ec2PortForwardManager portForwardManager,
                               RegionResolver regionResolver,
                               ContainerNetworkReachability containerNetworkReachability,
                               VpcNetworkManager vpcNetworkManager,
                               ContainerReachableEndpoint reachableEndpoint,
                               Ec2InstanceTypeCatalog instanceTypeCatalog) {
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, dockerHostResolver, dockerClient,
                portAllocator, config, metadataServer, portForwardManager, regionResolver,
                containerNetworkReachability, vpcNetworkManager, reachableEndpoint, instanceTypeCatalog,
                createLaunchExecutor(), createServiceNetworkRetryExecutor(),
                Duration.ofMinutes(USER_DATA_EXECUTION_TIMEOUT_MINUTES));
    }

    public Ec2ContainerManager(ContainerBuilder containerBuilder,
                               ContainerLifecycleManager lifecycleManager,
                               ContainerLogStreamer logStreamer,
                               ContainerDetector containerDetector,
                               DockerHostResolver dockerHostResolver,
                               DockerClient dockerClient,
                               PortAllocator portAllocator,
                               EmulatorConfig config,
                               Ec2MetadataServer metadataServer,
                               Ec2PortForwardManager portForwardManager,
                               RegionResolver regionResolver,
                               ContainerNetworkReachability containerNetworkReachability,
                               VpcNetworkManager vpcNetworkManager,
                               ContainerReachableEndpoint reachableEndpoint) {
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, dockerHostResolver, dockerClient,
                portAllocator, config, metadataServer, portForwardManager, regionResolver,
                containerNetworkReachability, vpcNetworkManager, reachableEndpoint, new Ec2InstanceTypeCatalog());
    }

    Ec2ContainerManager(ContainerBuilder containerBuilder,
                        ContainerLifecycleManager lifecycleManager,
                        ContainerLogStreamer logStreamer,
                        ContainerDetector containerDetector,
                        DockerHostResolver dockerHostResolver,
                        DockerClient dockerClient,
                        PortAllocator portAllocator,
                        EmulatorConfig config,
                        Ec2MetadataServer metadataServer,
                        Ec2PortForwardManager portForwardManager,
                        RegionResolver regionResolver,
                        ContainerNetworkReachability containerNetworkReachability,
                        VpcNetworkManager vpcNetworkManager,
                        ContainerReachableEndpoint reachableEndpoint,
                        Ec2InstanceTypeCatalog instanceTypeCatalog,
                        ExecutorService executor,
                        ExecutorService serviceNetworkRetryExecutor,
                        Duration userDataExecutionTimeout) {
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, dockerHostResolver, dockerClient,
                portAllocator, config, metadataServer, portForwardManager, regionResolver,
                containerNetworkReachability, vpcNetworkManager, reachableEndpoint, instanceTypeCatalog,
                executor, serviceNetworkRetryExecutor, userDataExecutionTimeout, Ec2ContainerPause.THREAD_SLEEP);
    }

    Ec2ContainerManager(ContainerBuilder containerBuilder,
                        ContainerLifecycleManager lifecycleManager,
                        ContainerLogStreamer logStreamer,
                        ContainerDetector containerDetector,
                        DockerHostResolver dockerHostResolver,
                        DockerClient dockerClient,
                        PortAllocator portAllocator,
                        EmulatorConfig config,
                        Ec2MetadataServer metadataServer,
                        Ec2PortForwardManager portForwardManager,
                        RegionResolver regionResolver,
                        ContainerNetworkReachability containerNetworkReachability,
                        VpcNetworkManager vpcNetworkManager,
                        ContainerReachableEndpoint reachableEndpoint,
                        Ec2InstanceTypeCatalog instanceTypeCatalog,
                        ExecutorService executor,
                        ExecutorService serviceNetworkRetryExecutor,
                        Duration userDataExecutionTimeout,
                        Ec2ContainerPause pause) {
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, dockerHostResolver, dockerClient,
                portAllocator, config, metadataServer, portForwardManager, regionResolver,
                containerNetworkReachability, vpcNetworkManager, reachableEndpoint, instanceTypeCatalog,
                executor, serviceNetworkRetryExecutor, userDataExecutionTimeout, pause,
                createTerminationRetryScheduler(), System::nanoTime, Clock.systemUTC());
    }

    Ec2ContainerManager(ContainerBuilder containerBuilder,
                        ContainerLifecycleManager lifecycleManager,
                        ContainerLogStreamer logStreamer,
                        ContainerDetector containerDetector,
                        DockerHostResolver dockerHostResolver,
                        DockerClient dockerClient,
                        PortAllocator portAllocator,
                        EmulatorConfig config,
                        Ec2MetadataServer metadataServer,
                        Ec2PortForwardManager portForwardManager,
                        RegionResolver regionResolver,
                        ContainerNetworkReachability containerNetworkReachability,
                        VpcNetworkManager vpcNetworkManager,
                        ContainerReachableEndpoint reachableEndpoint,
                        Ec2InstanceTypeCatalog instanceTypeCatalog,
                        ExecutorService executor,
                        ExecutorService serviceNetworkRetryExecutor,
                        Duration userDataExecutionTimeout,
                        Ec2ContainerPause pause,
                        ScheduledExecutorService terminationRetryScheduler,
                        LongSupplier nanoTime,
                        Clock clock) {
        this.pause = pause;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.terminationRetries = new SweepWorker("ec2-termination-retry", TERMINATION_RETRY_SWEEP_SECONDS, false,
                this::retryPendingTerminations, terminationRetryScheduler, clock);
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.containerDetector = containerDetector;
        this.dockerHostResolver = dockerHostResolver;
        this.dockerClient = dockerClient;
        this.portAllocator = portAllocator;
        this.config = config;
        this.nativePeerDependencies = new Ec2NativePeerDependencies(config);
        this.regionResolver = regionResolver;
        this.metadataServer = metadataServer;
        this.portForwardManager = portForwardManager;
        this.containerNetworkReachability = containerNetworkReachability;
        this.vpcNetworkManager = vpcNetworkManager;
        this.reachableEndpoint = reachableEndpoint;
        this.instanceTypeCatalog = instanceTypeCatalog != null ? instanceTypeCatalog : new Ec2InstanceTypeCatalog();
        this.metadataNamespaces = new Ec2MetadataNamespace(dockerClient, containerBuilder, lifecycleManager, config);
        this.systemdV1 = new Ec2SystemdV1Runtime(dockerClient, containerBuilder, lifecycleManager, config);
        this.ipv6Router = new Ec2Ipv6VpcRouter(dockerClient, containerBuilder, lifecycleManager, config, vpcNetworkManager);
        this.executor = executor;
        this.serviceNetworkRetryExecutor = serviceNetworkRetryExecutor;
        this.userDataExecutionTimeout = userDataExecutionTimeout;
        this.guestReboots = new Ec2GuestReboots(dockerClient, config,
                (container, operation) -> submitLifecycleOperation(
                        container, operation, serviceNetworkRetryExecutor), this::restartNativeGuest);
    }

    Ec2ContainerManager(ContainerBuilder containerBuilder,
                        ContainerLifecycleManager lifecycleManager,
                        ContainerLogStreamer logStreamer,
                        ContainerDetector containerDetector,
                        DockerHostResolver dockerHostResolver,
                        DockerClient dockerClient,
                        PortAllocator portAllocator,
                        EmulatorConfig config,
                        Ec2MetadataServer metadataServer,
                        Ec2PortForwardManager portForwardManager,
                        RegionResolver regionResolver,
                        ContainerNetworkReachability containerNetworkReachability,
                        VpcNetworkManager vpcNetworkManager,
                        ContainerReachableEndpoint reachableEndpoint,
                        ExecutorService executor,
                        Duration userDataExecutionTimeout) {
        this(containerBuilder, lifecycleManager, logStreamer, containerDetector, dockerHostResolver, dockerClient,
                portAllocator, config, metadataServer, portForwardManager, regionResolver,
                containerNetworkReachability, vpcNetworkManager, reachableEndpoint, new Ec2InstanceTypeCatalog(),
                executor, createServiceNetworkRetryExecutor(), userDataExecutionTimeout);
    }

    /**
     * Its own virtual threads, never {@link #createLaunchExecutor}'s: that pool blocks a full queue's
     * submitters, and a launch worker waiting on it for a retry could stall them all.
     */
    static ExecutorService createServiceNetworkRetryExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ec2-service-network-retry-", 0).factory());
    }

    /** The daemon timer thread that re-submits teardowns whose backoff has passed. */
    static ScheduledExecutorService createTerminationRetryScheduler() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ec2-termination-retry");
            thread.setDaemon(true);
            return thread;
        });
    }

    static ExecutorService createLaunchExecutor() {
        return new ThreadPoolExecutor(
                LAUNCH_CORE_THREADS,
                LAUNCH_MAX_THREADS,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(LAUNCH_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "ec2-container-launcher");
                    thread.setDaemon(true);
                    return thread;
                },
                BLOCKING_BACKPRESSURE);
    }

    @PreDestroy
    void stop() {
        guestReboots.close();
        closeActiveUserDataCallbacks();
        executor.shutdownNow();
        terminationRetries.stop();
        serviceNetworkRetryExecutor.shutdownNow();
        closeActiveUserDataCallbacks();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                LOG.warn("EC2 launch workers did not finish before the shutdown drain deadline");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("EC2 launch worker drain interrupted");
        }
        try {
            ipv6Router.retire();
        } catch (RuntimeException failure) {
            LOG.warnv(failure, "IPv6 VPC router was not removed at shutdown");
        }
    }

    void onStop(@Observes @Priority(1100) ShutdownEvent ignored) {
        // Helpers can exist before the primary container is recorded. Finish launch cleanup
        // before EmulatorLifecycle snapshots the instances for container teardown.
        stop();
    }

    /**
     * Launches a Docker container for the given EC2 instance.
     * The instance starts in pending state; an async thread transitions it to running
     * and handles SSH key injection and UserData execution. With no Docker daemon
     * reachable the instance goes straight to running as metadata only.
     *
     * @param instance    the EC2 instance model (mutated in-place as state transitions occur)
     * @param dockerImage Docker image URI resolved from the instance's AMI ID
     * @param publicKey   SSH public key content to inject (may be null)
     * @param region      AWS region (for CloudWatch log group naming)
     */
    public void launch(Instance instance, String dockerImage, String publicKey, String region) {
        launch(instance, ResolvedAmiImage.minimal(dockerImage), publicKey, region, Set.of());
    }

    public void launch(Instance instance, ResolvedAmiImage image, String publicKey, String region) {
        launch(instance, image, publicKey, region, Set.of());
    }

    /**
     * @param appPorts TCP ports opened by the instance's security groups to publish on the host
     *                 via socat sidecars once the container is running (empty for none)
     */
    public void launch(Instance instance, ResolvedAmiImage image, String publicKey, String region, Set<Integer> appPorts) {
        launch(instance, image, publicKey, region, appPorts, List.of(), Map.of());
    }

    public void launch(Instance instance, ResolvedAmiImage image, String publicKey, String region,
                       Set<Integer> appPorts, List<SecurityGroup> groups,
                       Map<String, List<String>> prefixLists) {
        launch(instance, image, publicKey, region, appPorts, groups, prefixLists, null);
    }

    public void launch(Instance instance, ResolvedAmiImage image, String publicKey, String region,
                       Set<Integer> appPorts, List<SecurityGroup> groups,
                       Map<String, List<String>> prefixLists, Runnable postLaunch) {
        launch(instance, image, publicKey, region, appPorts, groups, prefixLists, List::of, postLaunch);
    }

    public void launch(Instance instance, ResolvedAmiImage image, String publicKey, String region,
                       Set<Integer> appPorts, List<SecurityGroup> groups,
                       Map<String, List<String>> prefixLists, Supplier<List<PreparedMount>> prepareMounts,
                       Runnable postLaunch) {
        instance.setState(InstanceState.pending());

        // An instance record is metadata: id, addresses, tags and lifecycle state are all
        // served without a container runtime. Only the guest itself (SSH, UserData, SSM
        // commands) needs Docker, so when no daemon is reachable the instance still runs
        // instead of dying: Floci in Docker without a mounted socket, or a stopped daemon
        // on the host, would otherwise terminate every instance the moment it launched.
        if (!isDockerAvailable()) {
            if (!image.volumeMounts().isEmpty() || firewallManager != null && firewallManager.enabled()) {
                failLaunch(instance);
            } else {
                markContainerlessRunning(instance);
            }
            return;
        }

        // Captured before anything can overwrite it: on a launch that never attaches to the VPC
        // network, exposeReachablePrivateAddress replaces the reported private IP with the bridge
        // one, and the address actually leased would otherwise be unrecoverable on the paths below.
        String leasedPrivateIp = instance.getPrivateIpAddress();
        String accountId = instance.getOwnerId() != null
                ? instance.getOwnerId() : regionResolver.getAccountId();

        try {
            executor.execute(() -> RequestScopes.runAs(accountId, region, () -> {
                try {
                    String instanceId = instance.getInstanceId();
                // IMDS endpoint that this container should use
                String flociHost = dockerHostResolver.resolve();
                int imdsPort = config.services().ec2().imdsPort();
                StartedContainer started = createAndStartContainer(instance, image, region, flociHost, imdsPort,
                        leasedPrivateIp, groups, prefixLists, prepareMounts);
                if (started == null) {
                    return;
                }
                int sshHostPort = started.sshHostPort();
                String containerId = started.containerId();
                if (isLaunchCancelled(instance)) {
                    failLaunch(instance, leasedPrivateIp);
                    return;
                }

                // Poll until Docker confirms the container is running
                boolean running = false;
                for (int i = 0; i < 30 && !running; i++) {
                    if (isLaunchCancelled(instance)) {
                        failLaunch(instance, leasedPrivateIp);
                        return;
                    }
                    running = lifecycleManager.isContainerRunning(containerId);
                    if (!running) {
                        pause.pause(500);
                    }
                }

                if (!running) {
                    LOG.warnv("EC2 instance {0} container {1} did not reach running state", instanceId, containerId);
                    failLaunch(instance, leasedPrivateIp);
                    return;
                }

                if (isLaunchCancelled(instance)) {
                    failLaunch(instance, leasedPrivateIp);
                    return;
                }

                refreshServiceNetworkIp(instance);

                // The helper's public routes were published before guest startup. Only a changed
                // address needs to re-enter shared routing before the instance can report running.
                boolean publicAddressDue = instance.getPublicIpAddress() != null
                        || AutomaticPublicAddress.isDue(instance, false);
                if (publicAddressDue
                        && !Objects.equals(started.publishedPublicAddress(), reachablePublicAddress(instance))) {
                    exposeReachablePublicAddress(instance);
                }

                // Published before the state flips, so a poller that sees running never sees it without the address.
                if (!markRunning(instance)) {
                    failLaunch(instance, leasedPrivateIp);
                    return;
                }

                if (postLaunch != null) {
                    postLaunch.run();
                }

                LOG.infov("EC2 instance {0} running in container {1} (SSH host port {2})",
                        instanceId, containerId, String.valueOf(sshHostPort));

                // Publish security-group TCP ingress ports on the host via socat sidecars.
                if (appPorts != null && !appPorts.isEmpty()) {
                    portForwardManager.reconcile(instance, appPorts);
                }

                // The guest's init consumes metadata, provisions SSH and runs user data.
                // Running the compatibility bootstrap as well would execute scripts twice.
                if (image.cloudInit() && (image.systemd() || image.imageRuntime())) {
                    return;
                }

                // Inject SSH public key, if one was provided
                if (publicKey != null && !publicKey.isBlank()) {
                    injectSshKey(containerId, publicKey);
                }
                // sshd runs on every instance regardless of whether a key pair was supplied,
                // matching real AWS AMIs (which start it as part of normal boot, independent of
                // key-pair association) - starting it only when a key was present meant
                // run-instances without --key-name produced a "connection refused" instead of AWS's
                // actual behavior: a running daemon with simply nothing to authenticate against.
                // A systemd guest is the exception: its init starts sshd from the image's own unit,
                // as it does on EC2. A second sshd launched here raced that unit for port 22, and
                // whichever lost left either the unit failed or a daemon outside systemd that a
                // later `systemctl restart ssh` could not replace.
                if (!image.systemd()) {
                    startSshd(containerId, instanceId);
                }

                // Execute UserData
                String userData = instance.getUserData();
                if (userData != null && !userData.isBlank()) {
                    executeUserData(containerId, instanceId, userData, region);
                }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failLaunch(instance, leasedPrivateIp);
                } catch (Exception e) {
                    LOG.warnv(e, "Failed to launch EC2 instance {0}: {1}", instance.getInstanceId(), e.getMessage());
                    // The daemon can disappear between the probe above and any of the calls in
                    // this block. Losing Docker is not the instance's fault, so degrade to a
                    // metadata-only instance; a genuine container failure still fails the launch.
                    if ((firewallManager != null && firewallManager.enabled()) || isDockerAvailable()) {
                        failLaunch(instance, leasedPrivateIp);
                    } else {
                        markContainerlessRunning(instance);
                    }
                }
            }));
        } catch (RejectedExecutionException e) {
            LOG.warnv("Could not schedule EC2 instance {0} launch because the launch executor is saturated or stopping",
                    instance.getInstanceId());
            failLaunch(instance, leasedPrivateIp);
        }
    }

    private StartedContainer createAndStartContainer(Instance instance, ResolvedAmiImage image, String region,
                                                     String flociHost, int imdsPort, String leasedPrivateIp,
                                                     List<SecurityGroup> groups,
                                                     Map<String, List<String>> prefixLists,
                                                     Supplier<List<PreparedMount>> prepareMounts) {
        String instanceId = instance.getInstanceId();
        String containerName = ContainerStorageHelper.resourceName(config, "ec2", null, instanceId);
        GuestOptions options = guestOptions(config.services().ec2());
        validateMountOptions(image, options);
        List<PreparedMount> mounts = List.copyOf(prepareMounts.get());
        if (mounts.size() != image.volumeMounts().size()) {
            throw new IllegalStateException("EC2 image data-volume mounts were not prepared");
        }
        boolean customResolver = options.mounts().stream().anyMatch(mount ->
                "/etc".equals(mount.getTarget()) || "/etc/resolv.conf".equals(mount.getTarget()));
        // With guest-tls-endpoint the guest reaches Floci over https, so its requests carry aws:SecureTransport
        // true, as an instance's requests to AWS do; baseUrlWithTls stays http while Floci's TLS is off.
        String serviceEndpoint = config.services().ec2().guestTlsEndpoint()
                ? reachableEndpoint.baseUrlWithTls(!customResolver)
                : (customResolver ? reachableEndpoint.baseUrl(false) : reachableEndpoint.baseUrl());

        return portAllocator.allocateAndStart(
                config.services().ec2().sshPortRangeStart(),
                config.services().ec2().sshPortRangeEnd(), sshHostPort -> {
            if (isLaunchCancelled(instance)) {
                portAllocator.release(sshHostPort);
                return null;
            }
            SecurityGroupFirewallManager.Namespace namespace = null;
            String helperId = null;
            String eniId = null;
            String containerId = null;
            boolean recorded = false;
            boolean nativeReleaseStarted = false;
            try {
                boolean ipv6 = guestIpv6(instance) != null;
                if (firewallManager != null && firewallManager.enabled()) {
                    if (instance.getNetworkInterfaces() == null || instance.getNetworkInterfaces().isEmpty()) {
                        throw new IllegalStateException("EC2 instance has no network interface to protect");
                    }
                    InstanceNetworkInterface eni = instance.getNetworkInterfaces().getFirst();
                    eniId = eni.getNetworkInterfaceId();
                    instance.setLogicalPrivateIpAddress(eni.getPrivateIpAddress());
                    namespace = firewallManager.createNamespace("ec2", instanceId,
                            regionResolver.getAccountId(), region, Optional.empty(), Map.of(22, sshHostPort),
                            Map.of(), metadataNamespaces.sshHostBindAddress(), null, ipv6);
                    helperId = namespace.helperId();
                } else {
                    helperId = metadataNamespaces.create(instanceId, regionResolver.getAccountId(), region, sshHostPort,
                            ipv6);
                }
                networkHelpers.put(instanceId, helperId);
                Optional<String> vpcNetwork = vpcNetworkManager.attach(region, instance.getVpcId(),
                        instance.getSubnetId(), helperId, leasedPrivateIp, ipv6);
                String vpcAddress = vpcNetwork.map(network -> leasedPrivateIp).orElse(null);
                if (namespace != null && vpcNetworkManager.enabled() && vpcAddress == null) {
                    throw new IllegalStateException("Protected EC2 namespace could not attach to its VPC");
                }
                String containerIp = waitForContainerBridgeIp(helperId, instanceId, instance);
                if (containerIp == null || containerIp.isBlank()) {
                    throw new IllegalStateException("EC2 network helper has no usable metadata address");
                }
                if (vpcAddress != null) {
                    containerIp = vpcAddress;
                } else if (namespace == null) {
                    vpcNetworkManager.releasePrivateIp(region, instance.getSubnetId(), leasedPrivateIp);
                }
                if (namespace == null) {
                    exposeReachablePrivateAddress(instance, containerIp,
                            config.services().ec2().awsFaithfulPrivateIp());
                } else {
                    firewallManager.register(new SecurityGroupNftCompiler.Endpoint(regionResolver.getAccountId(),
                            region, instance.getVpcId(), eniId, instance.getLogicalPrivateIpAddress(),
                            namespace.transportAddress(),
                            instance.getSecurityGroups().stream().map(GroupIdentifier::getGroupId)
                                    .collect(Collectors.toSet()), groups, nativePeerDependencies.connections(), true,
                            vpcAddress == null ? Set.of() : Set.of(vpcAddress),
                            List.of(), nativePeerDependencies.dependencies()),
                            helperId, prefixLists);
                }
                if (vpcAddress != null) {
                    configureGuestIpv6(helperId, instance, region, vpcNetwork.get(), vpcAddress);
                }
                instance.setContainerBridgeIp(containerIp);
                metadataServer.registerContainer(containerIp, instanceId, instance);
                refreshImdsSourceRegistration(instance, helperId, containerIp);
                refreshMetadataAddresses(instance, helperId);
                configureLinkLocalMetadataEndpoint(helperId, instance, flociHost, imdsPort);
                preparePublicAddressRouting(helperId, instance);
                String publishedPublicAddress = null;
                if (instance.getPublicIpAddress() != null || AutomaticPublicAddress.isDue(instance, false)) {
                    publishedPublicAddress = exposeReachablePublicAddress(instance);
                }
                if (isLaunchCancelled(instance)) {
                    throw new IllegalStateException("EC2 launch was canceled before guest startup");
                }
                if (image.systemdV1()) {
                    systemdV1.prepare(instance, regionResolver.getAccountId(), region);
                }
                ContainerSpec spec = buildContainerSpec(containerName, image, region, serviceEndpoint,
                        instanceId, helperId, namespace != null, instance.getIamInstanceProfileArn() != null,
                        instance.getInstanceType(), options, mounts);
                containerId = image.dockerPlatform() == null
                        ? lifecycleManager.create(spec)
                        : lifecycleManager.create(spec, image.dockerPlatform());
                if (!recordCreatedContainer(instance, containerId, sshHostPort)) {
                    lifecycleManager.removeIfExists(containerId);
                    if (image.systemdV1()) {
                        systemdV1.remove(instance);
                    }
                    if (eniId != null) {
                        firewallManager.unregister(eniId);
                    }
                    lifecycleManager.removeIfExistsStrict(helperId);
                    networkHelpers.remove(instanceId, helperId);
                    metadataServer.unregisterInstance(instance);
                    portAllocator.release(sshHostPort);
                    return null;
                }
                recorded = true;
                lifecycleManager.startCreated(containerId, spec);
                if (image.systemdV1()) {
                    nativeReleaseStarted = true;
                    releaseNativeGuest(instance, containerId);
                }
                return new StartedContainer(containerId, sshHostPort, publishedPublicAddress);
            } catch (Exception e) {
                if (nativeReleaseStarted) {
                    synchronized (instance) {
                        if (!Objects.equals(containerId, instance.getDockerContainerId())
                                || sshHostPort != instance.getSshHostPort()) {
                            return null;
                        }
                    }
                    LOG.warnv(e, "Native EC2 launch failed for {0}; retaining strict workload cleanup",
                            instanceId);
                    try {
                        failLaunch(instance, leasedPrivateIp);
                    } catch (RuntimeException cleanupFailure) {
                        LOG.warnv(cleanupFailure, "Native EC2 launch cleanup retained for {0}", instanceId);
                    }
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }
                boolean ownsCleanup = !recorded || clearRecordedContainer(instance, containerId, sshHostPort);
                if (!ownsCleanup) {
                    return null;
                }
                if (containerId != null) {
                    lifecycleManager.removeIfExists(containerId);
                }
                if (image.systemdV1()) {
                    systemdV1.remove(instance);
                }
                if (helperId != null) {
                    if (eniId != null) {
                        firewallManager.unregister(eniId);
                    }
                    lifecycleManager.removeIfExistsStrict(helperId);
                    networkHelpers.remove(instanceId, helperId);
                }
                metadataServer.unregisterInstance(instance);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw e instanceof RuntimeException runtime ? runtime
                        : new IllegalStateException("Interrupted while preparing EC2 metadata", e);
            }
        });
    }

    private ContainerSpec buildContainerSpec(String containerName, ResolvedAmiImage image, String region,
                                             String serviceEndpoint, String instanceId,
                                             String helperId, boolean enforceSecurityGroups,
                                             boolean hasInstanceProfile, String instanceType, GuestOptions options,
                                             List<PreparedMount> mounts) {
        List<String> environment = localAwsEnvironment(region, serviceEndpoint, hasInstanceProfile);
        if (enforceSecurityGroups && config.network().vpcRouting().enabled()
                && config.network().vpcRouting().publicAwsApi()) {
            environment.removeIf(value -> value.startsWith("AWS_ENDPOINT_URL="));
        }
        // Image runtime keeps the image's startup contract; minimal and systemd retain their commands.
        ContainerBuilder.Builder specBuilder = containerBuilder.newContainer(image.dockerImage())
                .withName(containerName)
                .withEmbeddedDns()
                .withDockerNetwork(Optional.empty())
                .withEnv(environment)
                .withEnv("AWS_EC2_INSTANCE_ID", instanceId)
                .withEnv(options.environment())
                .withHostDockerInternalOnLinux()
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "ec2", instanceId, regionResolver.getAccountId(), region))
                // Which Floci owns this container, so the startup reconciler cannot reap a
                // sibling emulator's live instances off a shared daemon. See LABEL_OWNER.
                .withLabels(Map.of(LABEL_OWNER, ownerIdentity()))
                .withNetworkMode("container:" + helperId)
                .withPrivileged(config.services().ec2().privileged() && !enforceSecurityGroups
                        && mounts.isEmpty() && !image.systemdV1());
        List<String> capDrop = config.services().ec2().capDrop().orElse(List.of());
        List<String> capAdd = config.services().ec2().capAdd().orElse(List.of());
        requireDisjointCapabilities(capAdd, capDrop);
        capDrop.forEach(specBuilder::withCapDrop);
        capAdd.forEach(specBuilder::withCapAdd);
        options.ulimits().forEach((name, limit) -> specBuilder.withUlimit(name, limit.soft(), limit.hard()));
        options.mounts().forEach(specBuilder::withMount);
        if (!mounts.isEmpty()) {
            specBuilder.withCapAdd("SYS_RESOURCE");
            for (PreparedMount mount : mounts) {
                specBuilder.withNamedVolume(mount.dockerVolume(), mount.target())
                        .withDevice(mount.loopDevice(), mount.device(), "rw");
                if (!mount.device().equals(mount.loopDevice())) {
                    specBuilder.withDevice(mount.loopDevice(), mount.loopDevice(), "rw");
                }
            }
        }
        if (!image.imageRuntime()) {
            specBuilder.withCmd(image.systemd() ? List.of("/sbin/init") : List.of("tail", "-f", "/dev/null"));
        }
        if (config.services().ec2().instanceResourceLimits() && instanceType != null && !instanceType.isBlank()) {
            Optional<CatalogInstanceType> catalogType = instanceTypeCatalog.find(instanceType);
            if (catalogType.isPresent()) {
                CatalogInstanceType type = catalogType.get();
                specBuilder.withMemoryMb(type.memoryMib);
                specBuilder.withCpuUnits(type.vcpu * 1024);
            } else {
                LOG.debugv("EC2 instance {0} has unknown instance type {1}; launching without resource limits",
                        instanceId, instanceType);
            }
        }
        if (enforceSecurityGroups) {
            specBuilder.withLabels(Map.of(ContainerStorageHelper.SECURITY_GROUP_WORKLOAD_LABEL, "true"));
        }
        if (image.systemdV1()) {
            // SIGTERM reexecutes native init; its container shutdown signal halts services.
            specBuilder.withStopSignal("SIGRTMIN+3")
                    .withCgroupnsMode("private").withCapAdd("SYS_ADMIN").withCapAdd("SYS_BOOT")
                    .withApparmorProfile(config.services().ec2().nativeInitApparmorProfile().orElse("unconfined"))
                    .withEnv("container", "docker")
                    .withEntrypoint(List.of("/bin/sh", "-c", Ec2SystemdV1Runtime.GATE))
                    .withCmd(List.of("ec2-init", Ec2SystemdV1Runtime.hardwareUuid(
                            regionResolver.getAccountId(), region, instanceId)))
                    .withTmpfs("/run", "rw,nosuid,nodev,exec")
                    .withMount(new Mount().withType(MountType.TMPFS).withTarget("/run/lock"))
                    .withMount(new Mount().withType(MountType.TMPFS).withTarget("/sys/fs/cgroup"))
                    .withMount(new Mount().withType(MountType.TMPFS).withTarget("/sys/hypervisor"))
                    .withLabels(Map.of(Ec2SystemdV1Runtime.RUNTIME_LABEL, ResolvedAmiImage.SYSTEMD_V1_RUNTIME));
        } else if (image.systemd()) {
            specBuilder
                    .withCgroupnsMode("host")
                    .withMount(new Mount().withType(MountType.TMPFS).withTarget("/run"))
                    .withMount(new Mount().withType(MountType.TMPFS).withTarget("/run/lock"))
                    .withBind("/sys/fs/cgroup", "/sys/fs/cgroup");
        }
        return specBuilder.build();
    }

    /**
     * Docker applies a named add over a drop, so a capability configured in both lists would
     * silently be granted. Refuse that configuration instead of guessing which one was meant.
     */
    static void requireDisjointCapabilities(List<String> capAdd, List<String> capDrop) {
        Set<String> dropped = capDrop.stream().map(Ec2ContainerManager::capabilityName)
                .collect(Collectors.toSet());
        List<String> both = capAdd.stream().map(Ec2ContainerManager::capabilityName)
                .filter(dropped::contains).distinct().sorted().toList();
        if (!both.isEmpty()) {
            throw new IllegalStateException("EC2 guest capabilities both added and dropped: " + both);
        }
    }

    private static String capabilityName(String capability) {
        String name = capability.trim().toUpperCase(Locale.ROOT);
        return name.startsWith("CAP_") ? name.substring(4) : name;
    }

    private void validateMountOptions(ResolvedAmiImage image, GuestOptions options) {
        if (image.systemd()) {
            for (Mount mount : options.mounts()) {
                if (Set.of("/run", "/run/lock", "/sys/fs/cgroup").contains(mount.getTarget())
                        || (image.systemdV1() && Set.of("/sys/hypervisor", "/run/floci-init-ready",
                        "/run/floci-init-binary-ready", "/run/floci-time-ready").contains(mount.getTarget()))) {
                    throw new IllegalArgumentException("Read-only bind target is reserved for systemd: "
                            + mount.getTarget());
                }
            }
        }
        if (image.systemdV1() && config.services().ec2().capDrop().orElse(List.of()).stream().anyMatch(capability ->
                "SYS_ADMIN".equalsIgnoreCase(capability) || "CAP_SYS_ADMIN".equalsIgnoreCase(capability)
                        || "SYS_BOOT".equalsIgnoreCase(capability) || "CAP_SYS_BOOT".equalsIgnoreCase(capability)
                        || "ALL".equalsIgnoreCase(capability))) {
            throw new IllegalStateException(
                    "The systemd-v1 runtime requires SYS_ADMIN and SYS_BOOT in its private guest namespace");
        }
        if (image.volumeMounts().isEmpty()) {
            return;
        }
        if (config.services().ec2().capDrop().orElse(List.of()).stream().anyMatch(capability ->
                "SYS_RESOURCE".equalsIgnoreCase(capability) || "CAP_SYS_RESOURCE".equalsIgnoreCase(capability)
                        || "ALL".equalsIgnoreCase(capability))) {
            throw new IllegalStateException("EC2 data-volume mounts require SYS_RESOURCE");
        }
        for (Ec2ImageCatalog.VolumeMount mapping : image.volumeMounts()) {
            Path target = Path.of(mapping.target());
            for (Mount mount : options.mounts()) {
                Path existing = Path.of(mount.getTarget()).normalize();
                if (target.startsWith(existing) || existing.startsWith(target)) {
                    throw new IllegalStateException("EC2 data-volume target overlaps a configured mount");
                }
            }
        }
    }

    private record GuestOptions(List<String> environment, Map<String, ContainerSpec.ResourceLimit> ulimits,
                                List<Mount> mounts) {}

    private static GuestOptions guestOptions(EmulatorConfig.Ec2ServiceConfig ec2) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String entry : ec2.extraEnv().orElse(List.of())) {
            int split = entry == null ? -1 : entry.indexOf('=');
            if (split <= 0 || !ENVIRONMENT_KEY.matcher(entry.substring(0, split)).matches()
                    || entry.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("EC2 extra-env requires explicit KEY=VALUE entries");
            }
            String key = entry.substring(0, split);
            if (RESERVED_GUEST_ENVIRONMENT.contains(key) || key.startsWith("AWS_ENDPOINT_URL_")
                    || key.startsWith("AWS_EC2_METADATA_") || key.startsWith("AWS_CONTAINER_CREDENTIALS_")) {
                throw new IllegalArgumentException("EC2 extra-env cannot override a reserved guest setting: " + key);
            }
            if (environment.putIfAbsent(key, entry.substring(split + 1)) != null) {
                throw new IllegalArgumentException("EC2 extra-env contains a duplicate key: " + key);
            }
        }
        Map<String, ContainerSpec.ResourceLimit> limits = new LinkedHashMap<>();
        for (String entry : ec2.ulimits().orElse(List.of())) {
            Matcher matcher = PROCESS_LIMIT.matcher(entry == null ? "" : entry);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("EC2 ulimits requires name=soft[:hard] entries");
            }
            long soft;
            long hard;
            try {
                soft = Long.parseLong(matcher.group(2));
                hard = matcher.group(3) == null ? soft : Long.parseLong(matcher.group(3));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("EC2 process limit exceeds the supported integer range", e);
            }
            if (limits.putIfAbsent(matcher.group(1), new ContainerSpec.ResourceLimit(soft, hard)) != null) {
                throw new IllegalArgumentException("EC2 ulimits contains a duplicate name: " + matcher.group(1));
            }
        }
        Map<String, Mount> mounts = new LinkedHashMap<>();
        for (EmulatorConfig.Ec2ServiceConfig.GuestReadOnlyBind bind : ec2.readOnlyBinds()) {
            String source = guestMountPath(bind.source());
            String target = guestMountPath(bind.target());
            if ("/".equals(target)) {
                throw new IllegalArgumentException("EC2 read-only bind cannot replace the guest root");
            }
            Mount mount = new Mount().withType(MountType.BIND).withSource(source).withTarget(target)
                    .withReadOnly(true);
            if (mounts.putIfAbsent(target, mount) != null) {
                throw new IllegalArgumentException("EC2 read-only binds contain a duplicate target: " + target);
            }
        }
        return new GuestOptions(environment.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue()).toList(), Map.copyOf(limits),
                List.copyOf(mounts.values()));
    }

    private static String guestMountPath(String value) {
        if (value == null || !value.startsWith("/") || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("EC2 read-only binds require normalized absolute paths");
        }
        // These paths belong to a Linux daemon even when Floci runs on Windows.
        if (!"/".equals(value)) {
            for (String part : value.substring(1).split("/", -1)) {
                if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                    throw new IllegalArgumentException("EC2 read-only binds require normalized absolute paths");
                }
            }
        }
        return value;
    }

    private void failLaunch(Instance instance) {
        failLaunch(instance, instance.getPrivateIpAddress());
    }

    /**
     * Ends a launch that never reached RUNNING.
     *
     * <p>The private address was leased before the container existed, and {@link #terminate}, the
     * only other place it is given back, is not on this path: an instance that fails here is
     * already terminated, so nothing terminates it again and the lease would be held for the life
     * of the process. Detaching first is what makes the release safe; Docker keeps the address
     * reserved while the endpoint stands, and would refuse the next launch handed the same one.
     */
    private void failLaunch(Instance instance, String leasedPrivateIp) {
        FailedLaunchCleanup cleanup;
        synchronized (instance) {
            instance.setPublicIpAddress(null);
            instance.setPublicDnsName(null);
            cleanup = failedLaunchCleanups.get(instance.getInstanceId());
            if (cleanup == null) {
                boolean alreadyCleaned = isLaunchCancelledState(instance)
                        && instance.getDockerContainerId() == null
                        && instance.getSshHostPort() <= 0
                        && (instance.getContainerBridgeIp() == null || instance.getContainerBridgeIp().isBlank());
                if (instance.getTerminatedAt() <= 0) {
                    // Stamped before the state so nobody sees a terminated instance without it.
                    instance.setTerminatedAt(clock.millis());
                }
                instance.setState(InstanceState.terminated());
                if (alreadyCleaned) {
                    return;
                }
                String eniId = instance.getNetworkInterfaces() == null || instance.getNetworkInterfaces().isEmpty()
                        ? null : instance.getNetworkInterfaces().getFirst().getNetworkInterfaceId();
                String accountId = instance.getOwnerId() != null ? instance.getOwnerId() : regionResolver.getAccountId();
                cleanup = new FailedLaunchCleanup(instance, accountId, new AtomicBoolean(),
                        instance.getDockerContainerId(), instance.getSshHostPort(),
                        instance.getContainerBridgeIp(), eniId, instance.getRegion(), instance.getVpcId(),
                        instance.getSubnetId(), leasedPrivateIp);
                failedLaunchCleanups.put(instance.getInstanceId(), cleanup);
                instance.setDockerContainerId(null);
                instance.setSshHostPort(0);
                instance.setContainerBridgeIp(null);
                instance.setServiceNetworkIp(null);
                serviceNetworkRetries.remove(instance.getInstanceId());
            }
        }
        try {
            completeFailedLaunchCleanup(cleanup);
        } catch (RuntimeException failure) {
            startTerminationRetries();
            throw failure;
        }
    }

    private void completeFailedLaunchCleanup(FailedLaunchCleanup cleanup) {
        synchronized (cleanup) {
            String instanceId = cleanup.instance().getInstanceId();
            if (failedLaunchCleanups.get(instanceId) != cleanup) {
                return;
            }
            finishFailedLaunch(cleanup.instance(), cleanup);
            failedLaunchCleanups.remove(instanceId, cleanup);
        }
    }

    private record FailedLaunchCleanup(Instance instance, String accountId, AtomicBoolean retrying,
                                       String containerId, int sshHostPort, String containerIp,
                                       String eniId, String region, String vpcId, String subnetId,
                                       String leasedPrivateIp) {
    }

    private void finishFailedLaunch(Instance instance, FailedLaunchCleanup cleanup) {
        String containerId = cleanup.containerId();
        int sshHostPort = cleanup.sshHostPort();
        String containerIp = cleanup.containerIp();
        try {
            portForwardManager.unpublishAll(instance);
        } catch (Exception e) {
            LOG.warnv("Error removing EC2 port forwards during failed launch of {0}: {1}",
                    instance.getInstanceId(), e.getMessage());
        }
        if (containerId != null) {
            lifecycleManager.removeIfExistsStrict(containerId);
        }
        // Retain the cleanup record and its leases until both the guest and init helper are gone.
        systemdV1.remove(instance);
        if (firewallManager != null && firewallManager.enabled()
                && cleanup.eniId() != null) {
            firewallManager.unregister(cleanup.eniId());
        }
        removeNetworkHelper(instance);
        try {
            vpcNetworkManager.detach(cleanup.region(), cleanup.vpcId(), containerId);
            vpcNetworkManager.releasePrivateIp(cleanup.region(), cleanup.subnetId(), cleanup.leasedPrivateIp());
        } catch (Exception e) {
            LOG.warnv("Error releasing the VPC address of failed EC2 launch {0}: {1}",
                    instance.getInstanceId(), e.getMessage());
        }
        if (sshHostPort > 0) {
            portAllocator.release(sshHostPort);
        }
        metadataServer.unregisterInstance(instance);
        if (containerIp != null && !containerIp.isBlank()) {
            try {
                metadataServer.unregisterContainer(containerIp, instance);
            } catch (Exception e) {
                LOG.warnv("Error unregistering failed EC2 instance {0}: {1}", instance.getInstanceId(), e.getMessage());
            }
        }
    }

    /**
     * Cancels a pending asynchronous launch. Returns {@code false} only when the instance reached
     * running state while the caller was waiting, in which case it must not be torn down as a timeout.
     *
     * <p>The terminal state is established atomically before cleanup. The launch worker checks that
     * state between phases, and {@link #recordCreatedContainer(Instance, String, int)} rejects and
     * removes a container whose blocking Docker create call returns after cancellation. A timed-out
     * CloudFormation waiter therefore cannot leave a late worker able to transition the instance to
     * running.</p>
     */
    boolean cancelLaunch(Instance instance) {
        synchronized (instance) {
            String state = instance.getState() != null ? instance.getState().getName() : null;
            if ("running".equals(state)) {
                return false;
            }
            instance.setState(InstanceState.terminated());
        }
        failLaunch(instance);
        return true;
    }

    private static boolean isLaunchCancelled(Instance instance) {
        synchronized (instance) {
            return isLaunchCancelledState(instance);
        }
    }

    private static boolean markRunning(Instance instance) {
        synchronized (instance) {
            if (isLaunchCancelledState(instance)) {
                return false;
            }
            instance.setState(InstanceState.running());
            return true;
        }
    }

    private static boolean recordCreatedContainer(Instance instance, String containerId, int sshHostPort) {
        synchronized (instance) {
            if (isLaunchCancelledState(instance)) {
                return false;
            }
            instance.setSshHostPort(sshHostPort);
            instance.setDockerContainerId(containerId);
            return true;
        }
    }

    private static boolean clearRecordedContainer(Instance instance, String containerId, int sshHostPort) {
        synchronized (instance) {
            if (!Objects.equals(containerId, instance.getDockerContainerId())
                    || sshHostPort != instance.getSshHostPort()) {
                return false;
            }
            instance.setDockerContainerId(null);
            instance.setSshHostPort(0);
            return true;
        }
    }

    private static boolean isLaunchCancelledState(Instance instance) {
        String state = instance.getState() != null ? instance.getState().getName() : null;
        return "shutting-down".equals(state) || "terminated".equals(state);
    }

    private record StartedContainer(String containerId, int sshHostPort, String publishedPublicAddress) {
    }

    private void removeNetworkHelper(Instance instance) {
        String helperId = networkHelpers.get(instance.getInstanceId());
        if (helperId != null) {
            lifecycleManager.removeIfExistsStrict(helperId);
            networkHelpers.remove(instance.getInstanceId(), helperId);
        }
    }

    /**
     * Reports whether a Docker daemon is reachable, logging the transition in each
     * direction once rather than on every launch.
     */
    public boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            if (dockerUnavailableLogged) {
                dockerUnavailableLogged = false;
                LOG.info("Docker daemon is reachable again; new EC2 instances get a backing container.");
            }
            return true;
        } catch (Exception e) {
            if (!dockerUnavailableLogged) {
                dockerUnavailableLogged = true;
                LOG.warnv("No Docker daemon is reachable from Floci ({0}). EC2 instances are emulated as "
                        + "metadata only: they reach running and honour stop, start and terminate, but have "
                        + "no backing container, so SSH, UserData and SSM command execution stay unavailable "
                        + "until a daemon is reachable.", e.getMessage());
            }
            return false;
        }
    }

    /**
     * Brings an instance to running with no container behind it. Stop, start, terminate and
     * reboot already handle a null container id, so the rest of the lifecycle keeps working.
     */
    private void markContainerlessRunning(Instance instance) {
        if (markRunning(instance)) {
            LOG.infov("EC2 instance {0} is running without a backing container (no Docker daemon reachable)",
                    instance.getInstanceId());
        }
    }

    /**
     * Synchronous stop for emulator shutdown: tears down the port-forward sidecars and
     * stops the container with a short timeout so N instances cannot exhaust the SIGTERM
     * grace window. Unlike {@link #stop}, runs on the caller's thread (the async executor
     * would be abandoned mid-flight during shutdown) and leaves state handling to the caller.
     */
    public void stopForShutdown(Instance instance) {
        guestReboots.forget(instance);
        String containerId = instance.getDockerContainerId();
        if (containerId == null) {
            return;
        }
        portForwardManager.unpublishAll(instance);
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(5).exec();
        } catch (NotFoundException e) {
            // already gone
        } catch (Exception e) {
            LOG.warnv("Error stopping EC2 container {0} on shutdown: {1}", containerId, e.getMessage());
        }
    }

    private CompletableFuture<Void> submitLifecycleOperation(String containerId, Runnable operation) {
        return submitLifecycleOperation(containerId, operation, executor);
    }

    private CompletableFuture<Void> submitLifecycleOperation(String containerId, Runnable operation,
                                                             ExecutorService workers) {
        CompletableFuture<Void> previous;
        CompletableFuture<Void> completed = new CompletableFuture<>();
        synchronized (lifecycleOperations) {
            previous = lifecycleOperations.get(containerId);
            lifecycleOperations.put(containerId, completed);
        }
        Runnable submit = () -> {
            try {
                workers.submit(() -> {
                    try {
                        operation.run();
                    } finally {
                        lifecycleOperations.remove(containerId, completed);
                        completed.complete(null);
                    }
                });
            } catch (RuntimeException failure) {
                failLifecycleSubmission(containerId, completed, failure);
            }
        };
        if (previous == null) {
            // Queue backpressure must not hold the shared lifecycle map lock: native recovery
            // uses its own executor so a guest reboot can unblock a saturated launch pool.
            submit.run();
        } else {
            previous.whenComplete((ignored, priorFailure) -> {
                try {
                    serviceNetworkRetryExecutor.execute(submit);
                } catch (RuntimeException failure) {
                    failLifecycleSubmission(containerId, completed, failure);
                }
            });
        }
        return completed;
    }

    private void failLifecycleSubmission(String containerId, CompletableFuture<Void> completed,
                                         RuntimeException failure) {
        lifecycleOperations.remove(containerId, completed);
        completed.completeExceptionally(failure);
        LOG.warnv(failure, "Could not schedule lifecycle operation for EC2 container {0}", containerId);
    }

    /**
     * Puts an interface AttachNetworkInterface attached into the instance's network namespace, as a
     * hot attach adds a device to a running instance: the interface's MAC and primary private
     * IPv4 on its own VPC's network (EC2 User Guide, "Attach a network interface to an instance").
     * The interface stays record-only, as before, wherever this backend cannot give it its own
     * device with its own security groups; the reason is returned and logged.
     *
     * @return empty once plugged, otherwise why the interface is record-only
     */
    public Optional<String> plugNetworkInterface(Instance instance, InstanceNetworkInterface eni) {
        return plugNetworkInterface(instance, eni, List.of(), Map.of());
    }

    /**
     * As above, with the interface's own security groups and their prefix lists, which a protected
     * namespace enforces on the attached device (isolated address spaces with
     * {@code hot-attach-across-address-spaces}).
     */
    public Optional<String> plugNetworkInterface(Instance instance, InstanceNetworkInterface eni,
                                                 List<SecurityGroup> groups, Map<String, List<String>> prefixLists) {
        String reason = hotAttachBlocker(instance, eni);
        if (reason == null && isolatedHotAttach() && (groups == null || groups.isEmpty() || groups.stream().anyMatch(Objects::isNull))) {
            reason = "the interface's security groups are unknown";
        }
        if (reason == null) {
            try {
                if (isolatedHotAttach()) {
                    reason = plugProtected(instance, eni, groups, prefixLists);
                } else {
                    String helper = networkContainerOf(instance);
                    if (vpcNetworkManager.attachInterface(instance.getRegion(), eni.getVpcId(), eni.getSubnetId(),
                            helper, eni.getPrivateIpAddress(), eni.getMacAddress()).isEmpty()) {
                        reason = "its address is not on a VPC network Floci created";
                    }
                }
            } catch (RuntimeException failure) {
                reason = failure.getMessage();
            }
        }
        if (reason != null) {
            LOG.infov("Interface {0} attached to EC2 instance {1} is record-only: {2}",
                    eni.getNetworkInterfaceId(), instance.getInstanceId(), reason);
        }
        return Optional.ofNullable(reason);
    }

    /** Removes the device {@link #plugNetworkInterface} added, when there is one. */
    public void unplugNetworkInterface(Instance instance, InstanceNetworkInterface eni) {
        if (hotAttachBlocker(instance, eni) != null) {
            return;
        }
        try {
            if (isolatedHotAttach()) {
                String helper = networkContainerOf(instance);
                String primary = primaryInterfaceId(instance);
                if (primary != null) {
                    firewallManager.detachDevice(primary, eni.getNetworkInterfaceId());
                }
                vpcNetworkManager.detachInterfaceAcrossAddressSpaces(instance.getRegion(), eni.getVpcId(), helper,
                        eni.getPrivateIpAddress(), eni.getMacAddress());
            } else {
                vpcNetworkManager.detachInterface(instance.getRegion(), eni.getVpcId(), networkContainerOf(instance));
            }
        } catch (RuntimeException failure) {
            LOG.warnv("Could not unplug interface {0} from EC2 instance {1}: {2}",
                    eni.getNetworkInterfaceId(), instance.getInstanceId(), failure.getMessage());
        }
    }

    /**
     * Isolated address spaces: the device joins the instance's protected helper on the attached interface's
     * own native VPC network, and its own security groups are enforced on that device only.
     */
    private String plugProtected(Instance instance, InstanceNetworkInterface eni, List<SecurityGroup> groups,
                                 Map<String, List<String>> prefixLists) {
        String helper = networkContainerOf(instance);
        String primary = primaryInterfaceId(instance);
        if (helper == null || helper.equals(instance.getDockerContainerId()) || primary == null) {
            return "the instance has no protected namespace";
        }
        Optional<String> device = vpcNetworkManager.attachInterfaceAcrossAddressSpaces(instance.getRegion(),
                eni.getVpcId(), eni.getSubnetId(), helper, instanceAccount(instance), eni.getPrivateIpAddress(),
                eni.getMacAddress());
        if (device.isEmpty()) {
            return "its address is not on a native VPC network Floci created";
        }
        try {
            firewallManager.attachDevice(primary, attachedIdentity(instance, eni, groups), device.get(), prefixLists);
        } catch (RuntimeException failure) {
            try {
                vpcNetworkManager.detachInterfaceAcrossAddressSpaces(instance.getRegion(), eni.getVpcId(), helper,
                        eni.getPrivateIpAddress(), eni.getMacAddress());
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        return null;
    }

    /**
     * After an emulator restart, gives a hot-attached interface of a protected namespace its device and
     * policy again. Where this backend no longer hot-attaches across address spaces (the setting was turned
     * off), a device left from before is removed, so no traffic reaches the namespace outside a policy.
     */
    public void restoreAttachedInterface(Instance instance, InstanceNetworkInterface eni, List<SecurityGroup> groups,
                                         Map<String, List<String>> prefixLists) {
        if (vpcNetworkManager == null || !vpcNetworkManager.isolatesAddressSpaces() || eni.getDeviceIndex() < 1
                || instance.getDockerContainerId() == null) {
            return;
        }
        try {
            String helper = networkContainerOf(instance);
            String primary = primaryInterfaceId(instance);
            boolean usable = isolatedHotAttach() && hotAttachBlocker(instance, eni) == null && primary != null
                    && groups != null && !groups.isEmpty() && groups.stream().noneMatch(Objects::isNull);
            if (!usable) {
                vpcNetworkManager.detachInterfaceAcrossAddressSpaces(instance.getRegion(), eni.getVpcId(), helper,
                        eni.getPrivateIpAddress(), eni.getMacAddress());
                return;
            }
            Optional<String> device = vpcNetworkManager.restoreInterfaceAcrossAddressSpaces(instance.getRegion(),
                    eni.getVpcId(), eni.getSubnetId(), helper, eni.getPrivateIpAddress(), eni.getMacAddress());
            if (device.isEmpty()) {
                plugNetworkInterface(instance, eni, groups, prefixLists);
                return;
            }
            firewallManager.attachDevice(primary, attachedIdentity(instance, eni, groups), device.get(), prefixLists);
        } catch (RuntimeException failure) {
            LOG.warnv("Could not restore interface {0} attached to EC2 instance {1}: {2}",
                    eni.getNetworkInterfaceId(), instance.getInstanceId(), failure.getMessage());
            try {
                vpcNetworkManager.detachInterfaceAcrossAddressSpaces(instance.getRegion(), eni.getVpcId(),
                        networkContainerOf(instance), eni.getPrivateIpAddress(), eni.getMacAddress());
            } catch (RuntimeException cleanup) {
                LOG.warnv("Could not remove the device of interface {0}: {1}", eni.getNetworkInterfaceId(),
                        cleanup.getMessage());
            }
        }
    }

    private String instanceAccount(Instance instance) {
        return instance.getNetworkInterfaces().stream().filter(other -> other.getDeviceIndex() == 0)
                .map(InstanceNetworkInterface::getOwnerId).filter(Objects::nonNull).findFirst()
                .orElse(regionResolver.getAccountId());
    }

    /** The attached interface as its VPC's peers and its own policy see it: its own account, address and groups. */
    private SecurityGroupNftCompiler.Endpoint attachedIdentity(Instance instance, InstanceNetworkInterface eni,
                                                               List<SecurityGroup> groups) {
        String account = eni.getOwnerId() != null ? eni.getOwnerId() : instanceAccount(instance);
        return new SecurityGroupNftCompiler.Endpoint(account, instance.getRegion(), eni.getVpcId(),
                eni.getNetworkInterfaceId(), eni.getPrivateIpAddress(), eni.getPrivateIpAddress(),
                groups.stream().map(SecurityGroup::getGroupId).collect(Collectors.toSet()), List.copyOf(groups),
                List.of(), false, Set.of(eni.getPrivateIpAddress()), List.of(), Map.of());
    }

    private static String primaryInterfaceId(Instance instance) {
        return instance.getNetworkInterfaces().stream().filter(other -> other.getDeviceIndex() == 0)
                .map(InstanceNetworkInterface::getNetworkInterfaceId).filter(Objects::nonNull).findFirst()
                .orElse(null);
    }

    /** Isolated address spaces with hot attach across them turned on. */
    private boolean isolatedHotAttach() {
        if (vpcNetworkManager == null || !vpcNetworkManager.isolatesAddressSpaces() || config == null) {
            return false;
        }
        EmulatorConfig.VpcNetworksConfig networks = config.services().ec2().vpcNetworks();
        return networks != null && networks.hotAttachAcrossAddressSpaces();
    }

    /** Null when this backend can give the attached interface its own device; otherwise why not. */
    String hotAttachBlocker(Instance instance, InstanceNetworkInterface eni) {
        if (vpcNetworkManager == null || !vpcNetworkManager.enabled()) {
            return "VPC networks are disabled";
        }
        if (instance.getDockerContainerId() == null) {
            return "the instance has no container";
        }
        if (vpcNetworkManager.isolatesAddressSpaces()) {
            if (!isolatedHotAttach()) {
                return "isolated VPC address spaces keep one native address space per namespace"
                        + " (FLOCI_SERVICES_EC2_VPC_NETWORKS_HOT_ATTACH_ACROSS_ADDRESS_SPACES is off)";
            }
            if (firewallManager == null || !firewallManager.enabled()) {
                return "isolated VPC address spaces need security-group enforcement";
            }
        } else if (firewallManager != null && firewallManager.enabled()) {
            return "security-group enforcement filters the namespace with the primary interface's groups only";
        }
        if (eni.getVpcId() == null || eni.getMacAddress() == null || eni.getPrivateIpAddress() == null) {
            return "the interface has no VPC, MAC or private address";
        }
        if (eni.getVpcId().equals(instance.getVpcId()) || instance.getNetworkInterfaces().stream().anyMatch(other ->
                other != eni && !Objects.equals(other.getNetworkInterfaceId(), eni.getNetworkInterfaceId())
                        && eni.getVpcId().equals(other.getVpcId()))) {
            return "the instance already holds an interface in VPC " + eni.getVpcId()
                    + ", and Docker gives a namespace one endpoint per network";
        }
        return null;
    }

    /** The container that owns the instance's network namespace: its helper, or the guest itself. */
    private String networkContainerOf(Instance instance) {
        String helper = networkHelpers.get(instance.getInstanceId());
        if (helper != null) {
            return helper;
        }
        String owner = namespaceOwnerId(dockerClient.inspectContainerCmd(instance.getDockerContainerId()).exec());
        return owner != null ? owner : instance.getDockerContainerId();
    }

    /** Serves the instance's current record from IMDS after its network interfaces change. */
    public void refreshInstanceMetadata(Instance instance) {
        if (metadataServer != null) {
            metadataServer.refreshInstance(instance);
        }
    }

    /**
     * Gracefully stops a running container (30 second timeout then SIGKILL).
     * Updates instance state through stopping → stopped.
     */
    public void stop(Instance instance) {
        guestReboots.forget(instance);
        String containerId = instance.getDockerContainerId();
        if (containerId == null) {
            instance.setState(InstanceState.stopped());
            return;
        }
        InstanceState stopping = InstanceState.stopping();
        instance.setState(stopping);
        submitLifecycleOperation(containerId, () -> {
            // Sidecars forward to the container's current IP, which Docker reassigns on the
            // next start; tear them down so no forward is left pointing at a stale address.
            portForwardManager.unpublishAll(instance);
            try {
                dockerClient.stopContainerCmd(containerId).withTimeout(30).exec();
            } catch (NotFoundException ignored) {
                // An already removed container needs no further stop.
            } catch (Exception e) {
                LOG.warnv("Error stopping EC2 container {0}: {1}", containerId, e.getMessage());
            }
            refreshPublicRouting(instance, null);
            instance.compareAndSetState(stopping, InstanceState.stopped());
        });
    }

    /**
     * Starts a previously stopped container.
     * Updates instance state through pending → running.
     */
    public void start(Instance instance) {
        start(instance, null);
    }

    /**
     * Starts a previously stopped container, executing postStart once the container is running.
     */
    public void start(Instance instance, Runnable postStart) {
        start(instance, null, postStart);
    }

    public void start(Instance instance, Runnable beforeStart, Runnable postStart) {
        String containerId = instance.getDockerContainerId();
        if (containerId == null) {
            if (beforeStart != null) {
                beforeStart.run();
            }
            instance.setState(InstanceState.running());
            if (postStart != null) {
                postStart.run();
            }
            return;
        }
        String accountId = instance.getOwnerId() != null ? instance.getOwnerId() : regionResolver.getAccountId();
        InstanceState pending = InstanceState.pending();
        instance.setState(pending);
        submitLifecycleOperation(containerId, () -> RequestScopes.runAs(
                accountId, instance.getRegion(), () -> {
            boolean setUpMetadataEndpoint = false;
            try {
                if (beforeStart != null) {
                    beforeStart.run();
                }
                ProtectedNamespace namespace = protectedNamespace(instance);
                if (namespace != null) {
                    if (firewallManager != null && firewallManager.enabled() && !namespace.securityGroupsEnforced()) {
                        throw new IllegalStateException("Recreate the EC2 instance to enable security-group enforcement");
                    }
                    metadataServer.registerContainer(namespace.privateAddress(), instance.getInstanceId(), instance);
                    refreshImdsSourceRegistration(instance, namespace.helperId(), namespace.privateAddress());
                    refreshMetadataAddresses(instance, namespace.helperId());
                    configureLinkLocalMetadataEndpoint(namespace.helperId(), instance, dockerHostResolver.resolve(),
                            config.services().ec2().imdsPort());
                    preparePublicAddressRouting(namespace.helperId(), instance);
                    if (instance.getPublicIpAddress() != null || AutomaticPublicAddress.isDue(instance, true)) {
                        exposeReachablePublicAddress(instance);
                    }
                }
                dockerClient.startContainerCmd(containerId).exec();
                releaseNativeGuest(instance, containerId);
                boolean running = false;
                for (int i = 0; i < 20 && !running; i++) {
                    running = lifecycleManager.isContainerRunning(containerId);
                    if (!running) {
                        pause.pause(500);
                    }
                }
                setUpMetadataEndpoint = refreshStartedContainerAddresses(instance, containerId, namespace);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                instance.compareAndSetState(pending, InstanceState.stopped());
                return;
            } catch (Exception e) {
                LOG.warnv("Error starting EC2 container {0}: {1}", containerId, e.getMessage());
                instance.compareAndSetState(pending, InstanceState.stopped());
                return;
            }
            InstanceState running = InstanceState.running();
            if (!instance.compareAndSetState(pending, running)) {
                return;
            }
            if (postStart != null) {
                try {
                    postStart.run();
                } catch (Exception e) {
                    LOG.warnv("Error in post-start hook for instance {0}: {1}",
                            instance.getInstanceId(), e.getMessage());
                }
            }
            if (setUpMetadataEndpoint) {
                setUpMetadataEndpointWhileRunning(instance, containerId, running);
            }
        }));
    }

    /**
     * Re-reads the addresses a started or restarted container answers on and publishes them to
     * the instance and its IMDS registration. Returns whether the workload's own link-local
     * metadata endpoint still has to be set up, which is the case only without a helper namespace.
     */
    private boolean refreshStartedContainerAddresses(Instance instance, String containerId,
                                                     ProtectedNamespace namespace) throws InterruptedException {
        String instanceId = instance.getInstanceId();
        // A protected workload shares the helper's namespace, so it has no Docker network
        // attachment of its own to rediscover: the helper keeps its private address
        // across the workload's stop/start, exactly as launch used it.
        String containerIp = namespace == null
                ? waitForContainerBridgeIp(containerId, instanceId)
                : namespace.privateAddress();
        if (containerIp == null || containerIp.isBlank()) {
            return false;
        }
        instance.setContainerBridgeIp(containerIp);
        refreshServiceNetworkIp(instance);
        if (namespace == null) {
            exposeReachablePrivateAddress(instance, containerIp,
                    config.services().ec2().awsFaithfulPrivateIp());
        }
        // Docker hands out a new bridge IP on restart, so the previously reported
        // public address can now point at another container entirely. An address the
        // stop left in place is an Elastic IP's; a released automatic one is replaced
        // only when the shared rule allows it.
        if (instance.getPublicIpAddress() != null || AutomaticPublicAddress.isDue(instance, true)) {
            exposeReachablePublicAddress(instance);
        }
        if (namespace == null) {
            metadataServer.registerContainer(containerIp, instanceId, instance);
            refreshImdsSourceRegistration(instance, containerId, containerIp);
            refreshMetadataAddresses(instance, containerId);
        }
        return namespace == null;
    }

    /**
     * Best effort with its own bounded commands, as at launch, and off the container's lifecycle
     * queue: a slow setup holds neither the instance pending nor a stop queued behind this start.
     * It goes ahead only while {@code running} is still the instance's state, so a stop, terminate
     * or later start makes it stand down; an exec against a container already stopped just fails.
     *
     * <p>Not on {@link #executor}: the start runs on one of its workers, and its backpressure
     * blocks a submitter while the queue is full, so with every worker submitting nothing would
     * drain it. A setup that finds no free slot is skipped and logged rather than waited for.
     */
    private void setUpMetadataEndpointWhileRunning(Instance instance, String containerId, InstanceState running) {
        String instanceId = instance.getInstanceId();
        if (!metadataSetupSlots.tryAcquire()) {
            LOG.warnv("Skipped link-local IMDS setup for EC2 instance {0}: {1} setups already running",
                    instanceId, METADATA_SETUP_CONCURRENCY);
            return;
        }
        BooleanSupplier stillRunning = () -> instance.getState() == running;
        try {
            Thread.ofVirtual().name("ec2-imds-setup-" + instanceId).start(() -> {
                try {
                    configureLinkLocalMetadataEndpoint(containerId, instance, dockerHostResolver.resolve(),
                            config.services().ec2().imdsPort(), stillRunning);
                } catch (RuntimeException e) {
                    LOG.warnv("Could not configure link-local IMDS endpoint for EC2 instance {0}: {1}",
                            instanceId, e.getMessage());
                } finally {
                    metadataSetupSlots.release();
                }
            });
        } catch (RuntimeException e) {
            metadataSetupSlots.release();
            LOG.warnv("Could not schedule link-local IMDS setup for EC2 instance {0}: {1}", instanceId, e.getMessage());
        }
    }

    boolean isContainerRunning(String containerId) {
        if (containerId == null || containerId.isBlank() || guestReboots.recoveryFailed(containerId)) {
            return false;
        }
        if (apiReboots.containsKey(containerId)) {
            return true;
        }
        ContainerLiveness liveness = lifecycleManager.probeContainer(containerId);
        if (liveness == ContainerLiveness.RUNNING || guestReboots.isRebooting(containerId)) {
            return true;
        }
        if (liveness == ContainerLiveness.UNKNOWN) {
            throw new IllegalStateException("Cannot determine EC2 container liveness: " + containerId);
        }
        return false;
    }

    /**
     * Removes EC2 instance containers this Floci left behind on a previous run.
     *
     * <p>{@link #terminate} removes the container asynchronously after flipping the record to
     * {@code shutting-down}, and {@code launch} persists the container id only once Docker has
     * created it. A process killed across either edge — SIGKILL, OOM, {@code docker kill} — leaves
     * a container on the daemon that no surviving record refers to, so nothing on the next run
     * would ever collect it. {@code Ec2Service.stopManagedContainers} only covers the graceful
     * ShutdownEvent path, and {@code restoreMetadataRegistration} deliberately skips records in
     * {@code terminated}/{@code shutting-down}, so neither reaches these.
     *
     * <p><strong>Stopped instances are not orphans.</strong> Floci stops every running container
     * on shutdown and keeps the id so StartInstances can revive it; those records come back as
     * {@code stopped} and {@code stillDeclared} keeps them. Only containers with no surviving
     * record, or whose record is already terminated/shutting-down, are removed.
     *
     * @param stillDeclared answers whether (region, instanceId) is still a live instance record
     * @return the number of containers removed
     */
    public int reconcileOrphanedContainers(BiPredicate<String, String> stillDeclared) {
        if (!config.services().ec2().reconcileContainersOnStartup()) {
            return 0;
        }
        String owner = ownerIdentity();
        int removed = 0;
        try {
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(LABEL_SERVICE, SERVICE_VALUE))
                    .exec();
            for (Container container : containers) {
                Map<String, String> labels = container.getLabels() == null ? Map.of() : container.getLabels();
                if (!ALIASES.consistent(container.getId(), labels) || !ALIASES.matches(labels, LABEL_OWNER, owner)) {
                    continue;
                }
                String instanceId = labels.get(LABEL_RESOURCE_ID);
                String region = labels.get(LABEL_REGION);
                if (instanceId != null && !instanceId.isBlank()
                        && region != null && !region.isBlank()
                        && stillDeclared.test(region, instanceId)) {
                    continue;
                }
                lifecycleManager.removeIfExists(container.getId());
                removed++;
                LOG.infov("Reconciled orphaned EC2 container {0} (instance {1}) left by a previous run",
                        container.getId(), String.valueOf(instanceId));
            }
            systemdV1.reconcileOrphans(stillDeclared);
        } catch (Exception e) {
            LOG.warnv("Could not reconcile orphaned EC2 containers: {0}", e.getMessage());
        }
        if (removed > 0) {
            LOG.infov("Removed {0} orphaned EC2 container(s)", String.valueOf(removed));
        }
        return removed;
    }

    private void refreshMetadataAddresses(Instance instance, String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            if (inspect.getNetworkSettings() == null || inspect.getNetworkSettings().getNetworks() == null) {
                return;
            }
            Set<String> addresses = new HashSet<>();
            for (ContainerNetwork network : inspect.getNetworkSettings().getNetworks().values()) {
                if (network != null && network.getIpAddress() != null && !network.getIpAddress().isBlank()) {
                    addresses.add(network.getIpAddress());
                }
            }
            // An incomplete Docker response must not discard the last known registrations.
            if (!addresses.isEmpty()) {
                metadataServer.reconcileContainerAddresses(addresses, instance);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Could not refresh IMDS addresses for EC2 instance {0}, keeping existing registrations: {1}",
                    instance.getInstanceId(), e.getMessage());
        }
    }

    boolean restoreMetadataRegistration(Instance instance) {
        if (instance == null || instance.getDockerContainerId() == null) {
            return false;
        }
        String containerId = instance.getDockerContainerId();
        boolean running = lifecycleManager.isContainerRunning(containerId);
        if (!running) {
            String state = instance.getState() == null ? "" : instance.getState().getName();
            if (!("pending".equals(state) || "running".equals(state))) {
                return false;
            }
            try {
                InspectContainerResponse guest = dockerClient.inspectContainerCmd(containerId).exec();
                if (!Ec2GuestReboots.requestedReboot(guest) || !systemdV1.rememberGuest(instance, guest)) {
                    return false;
                }
                guestReboots.watch(instance);
            } catch (DockerException | IllegalStateException failure) {
                LOG.warnv(failure, "Could not restore native reboot observation for EC2 instance {0}",
                        instance.getInstanceId());
                return false;
            }
        }

        ProtectedNamespace namespace;
        try {
            namespace = protectedNamespace(instance);
        } catch (IllegalStateException failure) {
            guestReboots.failed(instance);
            LOG.warnv(failure, "Could not verify restored EC2 instance {0} ownership", instance.getInstanceId());
            return false;
        } catch (DockerException e) {
            LOG.warnv("Could not inspect the network namespace of EC2 instance {0}, keeping known IMDS addresses: {1}",
                    instance.getInstanceId(), e.getMessage());
            Set<String> known = new HashSet<>();
            if (instance.getContainerBridgeIp() != null && !instance.getContainerBridgeIp().isBlank()) {
                known.add(instance.getContainerBridgeIp());
            }
            if (instance.getImdsSourceIp() != null && !instance.getImdsSourceIp().isBlank()) {
                known.add(instance.getImdsSourceIp());
            }
            known.forEach(address -> metadataServer.registerContainer(address, instance.getInstanceId(), instance));
            return !known.isEmpty() && (!running || restoreNativeBoot(instance, containerId));
        }
        String networkContainer = namespace == null ? containerId : namespace.helperId();
        String containerIp = namespace == null ? getContainerBridgeIp(networkContainer) : namespace.privateAddress();
        if (containerIp == null || containerIp.isBlank()) {
            containerIp = instance.getContainerBridgeIp();
        }
        if (containerIp == null || containerIp.isBlank()) {
            LOG.warnv("Could not restore IMDS registration for EC2 instance {0}: no container IP",
                    instance.getInstanceId());
            return false;
        }

        String previousContainerIp = instance.getContainerBridgeIp();
        if (previousContainerIp != null && !previousContainerIp.isBlank() && !previousContainerIp.equals(containerIp)) {
            metadataServer.unregisterContainer(previousContainerIp, instance);
        }
        instance.setContainerBridgeIp(containerIp);
        refreshServiceNetworkIp(instance);
        if (firewallManager == null || !firewallManager.enabled()) {
            exposeReachablePrivateAddress(instance, containerIp, config.services().ec2().awsFaithfulPrivateIp());
        }
        metadataServer.registerContainer(containerIp, instance.getInstanceId(), instance);
        refreshImdsSourceRegistration(instance, networkContainer, containerIp);
        refreshMetadataAddresses(instance, networkContainer);
        if (namespace != null) {
            preparePublicAddressRouting(networkContainer, instance);
        }
        if (instance.getPublicIpAddress() != null || AutomaticPublicAddress.isDue(instance, true)) {
            exposeReachablePublicAddress(instance);
        }
        return !running || restoreNativeBoot(instance, containerId);
    }

    /** Restore IMDS before releasing native init, retaining ordinary guests' cached-address fallback. */
    private boolean restoreNativeBoot(Instance instance, String containerId) {
        try {
            releaseNativeGuest(instance, containerId);
            return true;
        } catch (DockerException failure) {
            if (!systemdV1.hasGuest(instance)) {
                return true;
            }
            LOG.warnv(failure, "Could not restore native boot for EC2 instance {0}", instance.getInstanceId());
            return false;
        } catch (RuntimeException failure) {
            LOG.warnv(failure, "Could not restore native boot for EC2 instance {0}", instance.getInstanceId());
            return false;
        }
    }

    /**
     * Keeps the IMDS registration of a VPC-attached instance's bridge address current.
     *
     * <p>An instance on a VPC network has two addresses, and only one of them is the one IMDS
     * sees. {@code Ec2MetadataServer} resolves an instance from the source address of the
     * request, and the container's default route is the bridge, so its metadata requests arrive
     * from the bridge address. The address Floci reports, and the one
     * {@link #getContainerBridgeIp} prefers, is the VPC address, so registering only that one
     * leaves IMDS with no entry for the address the requests actually come from.
     *
     * <p>{@code launch} gets this right. {@link #start} and {@link #restoreMetadataRegistration}
     * did not, and both are exactly where it goes wrong: Docker hands out a fresh bridge address
     * when a stopped container starts again, and an emulator restart rebuilds the registration
     * map empty. Either way the instance came back with its bridge address unregistered and
     * {@code imdsSourceIp} still naming the address of a previous run, which then also survived
     * as a stale entry pointing at this instance.
     *
     * @param reportedIp the address the instance reports, already registered by the caller; when
     *                   the bridge address is the same one, there is no second address to track
     */
    private void refreshImdsSourceRegistration(Instance instance, String containerId, String reportedIp) {
        String bridgeIp;
        try {
            bridgeIp = bridgeNetworkIp(containerId);
        } catch (RuntimeException e) {
            // An inspect that failed says nothing about where the container is attached, and the
            // unregister below is only correct for a container that definitely has no separate
            // bridge address. Reading "I could not find out" as "there is none" would tear down a
            // healthy instance's IMDS source registration over a transient Docker hiccup and put
            // nothing in its place, leaving its metadata requests unresolvable until some later
            // start or restore happened to succeed. Leave the registration exactly as it is; the
            // next start or restore refreshes it once inspect works again.
            LOG.warnv("Could not inspect container {0} for its bridge IP, leaving the IMDS source "
                    + "registration of EC2 instance {1} unchanged: {2}",
                    containerId, instance.getInstanceId(), e.getMessage());
            return;
        }
        // Nothing to track separately when the reported address is the bridge address: that is a
        // plain bridge-only instance, and the caller has already registered it.
        String current = bridgeIp != null && !bridgeIp.isBlank() && !bridgeIp.equals(reportedIp) ? bridgeIp : null;
        String previous = instance.getImdsSourceIp();

        if (previous != null && !previous.isBlank() && !previous.equals(current)) {
            metadataServer.unregisterContainer(previous, instance);
            instance.setImdsSourceIp(null);
        }
        if (current != null) {
            instance.setImdsSourceIp(current);
            metadataServer.registerContainer(current, instance.getInstanceId(), instance);
        }
    }

    /**
     * The container's address on Docker's default bridge, which is where its default route, and
     * so the source address of its IMDS requests, lives. Distinct from
     * {@link #getContainerBridgeIp}, which despite the name prefers the VPC network's address.
     *
     * <p>Returning null has to mean one thing only, "this container has no bridge address",
     * because callers act on that answer. A failed inspect is a different answer, "I could not
     * find out", so it propagates instead of being folded into the same null.
     *
     * @return the address, or null when the container is not on the default bridge at all
     * @throws RuntimeException if the container could not be inspected, leaving its bridge
     *                          attachment unknown rather than known to be absent
     */
    private String bridgeNetworkIp(String containerId) {
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        if (inspect.getNetworkSettings() == null || inspect.getNetworkSettings().getNetworks() == null) {
            return null;
        }
        ContainerNetwork bridge = inspect.getNetworkSettings().getNetworks().get("bridge");
        return bridge == null || bridge.getIpAddress() == null || bridge.getIpAddress().isBlank()
                ? null : bridge.getIpAddress();
    }

    /**
     * The address Floci is willing to hand out as this instance's public address — chosen so
     * that whatever dials it reaches the guest on the service's own port.
     *
     * <p>Where container IPs are routable that is the container's IP: port 22 is really port
     * 22 there, and so is every other port the guest listens on, with no published mapping to
     * translate. Where they are not, it falls back to {@code 127.0.0.1}, which is where the
     * published high host ports live — reachable, though not on the ports AWS clients assume.
     *
     * A prepared VPC guest uses its primary Docker network for public traffic;
     * namespace-local translation delivers it to the private listener.
     *
     * @return the address, or null if the instance has no container IP yet
     */
    public String reachablePublicAddress(Instance instance) {
        if (instance == null) {
            return null;
        }
        if (config.services().ec2().publicIpFromServiceNetwork()) {
            retryServiceNetworkIp(instance);
            String serviceNetworkIp = instance.getServiceNetworkIp();
            if (serviceNetworkIp != null && !serviceNetworkIp.isBlank()) {
                return serviceNetworkIp;
            }
        }
        String containerIp = instance.getContainerPublicIp() != null
                ? instance.getContainerPublicIp() : instance.getContainerBridgeIp();
        if (containerIp == null || containerIp.isBlank()) {
            return null;
        }
        return containerNetworkReachability.isContainerIpRoutable(containerIp) ? containerIp : "127.0.0.1";
    }

    private void preparePublicAddressRouting(String namespaceId, Instance instance) {
        InspectContainerResponse namespace = dockerClient.inspectContainerCmd(namespaceId).exec();
        String primary = Ec2PublicAddressRouting.primaryAddress(namespace);
        String privateAddress = namespacePrivateAddress(instance, namespaceId, namespace);
        if (!primary.equals(privateAddress)) {
            ContainerExec.Result result = execInContainerForResult(namespaceId,
                    Ec2PublicAddressRouting.command(privateAddress, primary), 15);
            if (result.exitCode() != 0) {
                throw new IllegalStateException("Cannot prepare EC2 public-address routing: " + result.summary());
            }
        }
        instance.setContainerPublicIp(primary);
    }

    /**
     * Records the instance's address on Floci's own Docker network, when
     * {@code publicIpFromServiceNetwork} is on and Floci runs as a container, so
     * {@link #reachablePublicAddress} reports it without asking Docker. Called where the container's
     * addresses are (re)learned: launch, start and restore.
     */
    void refreshServiceNetworkIp(Instance instance) {
        String instanceId = instance.getInstanceId();
        // The container is the lookup's generation: a result is published only while the instance
        // still runs that container, so a lookup that outlives a replacement or a teardown cannot
        // overwrite what came after it. Docker is asked outside the instance lock.
        String containerId;
        synchronized (instance) {
            containerId = instance.getDockerContainerId();
        }
        String address;
        try {
            address = resolveServiceNetworkIp(instanceId, containerId);
        } catch (RuntimeException e) {
            synchronized (instance) {
                if (containerId != null && containerId.equals(instance.getDockerContainerId())) {
                    // A Docker hiccup is not an answer: keep what was recorded and let the next
                    // address read retry the lookup in the background.
                    serviceNetworkRetries.add(instanceId);
                }
            }
            LOG.debugv("Could not read the service-network address of {0}, will retry: {1}",
                    instanceId, e.getMessage());
            return;
        }
        synchronized (instance) {
            if (!Objects.equals(containerId, instance.getDockerContainerId())) {
                return;
            }
            instance.setServiceNetworkIp(address);
            serviceNetworkRetries.remove(instanceId);
        }
    }

    /**
     * Retries a failed {@link #refreshServiceNetworkIp} in the background, at most one attempt per
     * instance at a time, so the address read that notices it never waits on Docker.
     */
    private void retryServiceNetworkIp(Instance instance) {
        String instanceId = instance.getInstanceId();
        if (!serviceNetworkRetries.contains(instanceId) || !serviceNetworkRetriesRunning.add(instanceId)) {
            return;
        }
        try {
            serviceNetworkRetryExecutor.execute(() -> {
                try {
                    refreshServiceNetworkIp(instance);
                } finally {
                    serviceNetworkRetriesRunning.remove(instanceId);
                }
            });
        } catch (RuntimeException e) {
            // Only a shutting-down executor refuses; the next address read tries again.
            serviceNetworkRetriesRunning.remove(instanceId);
            LOG.warnv("Could not schedule the service-network lookup retry for {0}: {1}",
                    instanceId, e.getMessage());
        }
    }

    private String resolveServiceNetworkIp(String instanceId, String containerId) {
        if (!config.services().ec2().publicIpFromServiceNetwork() || !containerDetector.isRunningInContainer()
                || containerId == null) {
            return null;
        }
        Optional<String> network = containerBuilder.resolveDockerNetwork(Optional.empty());
        if (network.isEmpty()) {
            return null;
        }
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        // A security-group protected guest joins its firewall helper's network namespace and has
        // no attachment of its own: the helper's address on the network is the guest's.
        String helperId = namespaceOwnerId(inspect);
        if (helperId != null) {
            inspect = dockerClient.inspectContainerCmd(helperId).exec();
        }
        if (inspect.getNetworkSettings() == null || inspect.getNetworkSettings().getNetworks() == null) {
            return null;
        }
        ContainerNetwork attached = inspect.getNetworkSettings().getNetworks().get(network.get());
        return attached == null || attached.getIpAddress() == null || attached.getIpAddress().isBlank()
                ? null : attached.getIpAddress();
    }

    /**
     * Publishes {@link #reachablePublicAddress} as the instance's public IP and DNS name. The DNS name has AWS's
     * form ({@link #publicDnsName}); Floci's DNS answers it with the same address.
     *
     * @return the published address, or null when none is reachable
     */
    String exposeReachablePublicAddress(Instance instance) {
        String publicAddress = reachablePublicAddress(instance);
        if (publicAddress == null) {
            return null;
        }
        instance.setPublicIpAddress(publicAddress);
        instance.setPublicDnsName(publicDnsName(publicAddress, instance.getRegion()));
        refreshPublicRouting(instance, publicAddress);
        return publicAddress;
    }

    /**
     * The public DNS name for {@code publicIp}: AWS's {@code ec2-a-b-c-d...} name, which Floci's DNS resolves back to
     * the address, or {@code localhost} for the loopback address a host-local Floci publishes; null without an address.
     */
    static String publicDnsName(String publicIp, String region) {
        if (publicIp == null) {
            return null;
        }
        return "127.0.0.1".equals(publicIp) ? "localhost" : AwsEndpoints.ec2PublicDns(publicIp, region);
    }

    void refreshPublicRouting(Instance instance) {
        refreshPublicRouting(instance, instance.getPublicIpAddress());
    }

    void refreshPublicRouting(Instance instance, String address) {
        if (firewallManager != null && firewallManager.enabled()
                && instance.getNetworkInterfaces() != null && !instance.getNetworkInterfaces().isEmpty()) {
            firewallManager.publishPublicAddress(instance.getNetworkInterfaces().getFirst().getNetworkInterfaceId(),
                    address);
        }
    }

    static void exposeReachablePrivateAddress(Instance instance, String privateIp) {
        exposeReachablePrivateAddress(instance, privateIp, false);
    }

    /**
     * Overwrite the instance's reported private address with the container's
     * reachable bridge IP — unless {@code awsFaithful} is true (#1983), in which
     * case the CFN/subnet-allocated private IP set at launch is left untouched.
     * The container bridge IP is tracked separately (setContainerBridgeIp) and
     * used for routing/IMDS regardless of this flag.
     */
    static void exposeReachablePrivateAddress(Instance instance, String privateIp, boolean awsFaithful) {
        if (instance == null || privateIp == null || privateIp.isBlank()) {
            return;
        }
        if (awsFaithful) {
            return;
        }

        String privateDnsName = AwsRegions.ec2PrivateIpDnsName(privateIp, instance.getRegion());
        instance.setPrivateIpAddress(privateIp);
        instance.setPrivateDnsName(privateDnsName);
        if (instance.getNetworkInterfaces() != null) {
            instance.getNetworkInterfaces().stream()
                    .filter(networkInterface -> networkInterface.getDeviceIndex() == 0)
                    .forEach(networkInterface -> {
                        networkInterface.setPrivateIpAddress(privateIp);
                        networkInterface.setPrivateDnsName(privateDnsName);
                    });
        }
    }

    private String namespacePrivateAddress(Instance instance, String namespaceId,
                                            InspectContainerResponse namespace) {
        if (vpcNetworkManager != null && vpcNetworkManager.isolatesAddressSpaces()) {
            InstanceNetworkInterface ipv6 = guestIpv6(instance);
            return vpcNetworkManager.requireNativePrivateAddress(instance.getRegion(), instance.getVpcId(),
                    instance.getSubnetId(), namespaceId, instance.getContainerBridgeIp(),
                    ipv6 == null ? null : ipv6.getIpv6Addresses().get(0));
        }
        return Ec2PublicAddressRouting.privateAddress(namespace, instance.getContainerBridgeIp());
    }

    /**
     * Terminates an instance: forcefully removes the container.
     * Updates state through shutting-down → terminated.
     * Sets terminatedAt for TTL pruning.
     */
    public void terminate(Instance instance) {
        terminate(instance, null);
    }

    /**
     * Terminates an instance and runs {@code afterTeardown} once the container is actually gone.
     *
     * <p>The teardown scheduled here is asynchronous, so anything that depends on the container
     * no longer holding a Docker resource cannot run at the call site. Reclaiming a deregistered
     * AMI's captured layer is the case that needs this: attempted while the container still
     * exists, the daemon refuses to delete the image, and with no later attempt the layer stays
     * on disk for the lifetime of the emulator.
     *
     * <p>The callback runs on the teardown executor, and only once the container is established
     * to be gone: removed, already absent, or never launched. Everything after removal can fail
     * without making the callback wrong, so it runs from a finally block, but running it when
     * removal itself failed would attempt work Docker is bound to refuse. Failed teardown stays
     * owned here and is retried with backoff. The callback runs at most once; anything it throws
     * is logged and swallowed rather than killing the executor task.
     */
    public void terminate(Instance instance, Runnable afterTeardown) {
        guestReboots.forget(instance);
        PendingTermination pending = pendingTermination(instance, afterTeardown);
        if (pending != null) {
            submitTermination(pending);
        }
    }

    private PendingTermination pendingTermination(Instance instance, Runnable afterTeardown) {
        return pendingTerminations.computeIfAbsent(instance.getInstanceId(), ignored -> {
            if (instance.getState() != null && "terminated".equals(instance.getState().getName())) {
                return null;
            }
            return new PendingTermination(instance, terminationOperation(instance, afterTeardown));
        });
    }

    private void retryPendingTerminations(Instant ignored) {
        long now = nanoTime.getAsLong();
        pendingTerminations.values().stream()
                .filter(pending -> pending.retryArmed && now - pending.retryAtNanos >= 0)
                .forEach(this::submitTermination);
        failedLaunchCleanups.values().forEach(this::submitFailedLaunchCleanup);
    }

    private void startTerminationRetries() {
        if (!executor.isShutdown() && terminationRetriesStarted.compareAndSet(false, true)) {
            terminationRetries.start();
        }
    }

    private void submitFailedLaunchCleanup(FailedLaunchCleanup cleanup) {
        if (serviceNetworkRetryExecutor.isShutdown() || !cleanup.retrying().compareAndSet(false, true)) {
            return;
        }
        try {
            serviceNetworkRetryExecutor.execute(() -> {
                try {
                    RequestScopes.runAs(cleanup.accountId(), cleanup.region(),
                            () -> completeFailedLaunchCleanup(cleanup));
                } catch (RuntimeException failure) {
                    LOG.debugv(failure, "Failed EC2 launch {0} still needs cleanup", cleanup.instance().getInstanceId());
                } finally {
                    cleanup.retrying().set(false);
                }
            });
        } catch (RuntimeException failure) {
            cleanup.retrying().set(false);
            LOG.debugv(failure, "Could not schedule failed EC2 launch cleanup for {0}",
                    cleanup.instance().getInstanceId());
        }
    }

    private void submitTermination(PendingTermination pending) {
        String instanceId = pending.instance.getInstanceId();
        if (executor.isShutdown() || pendingTerminations.get(instanceId) != pending
                || !pending.executing.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    pending.operation.run();
                } catch (RuntimeException failure) {
                    LOG.warnv(failure, "EC2 instance {0} teardown failed; retaining it for retry", instanceId);
                } finally {
                    try {
                        if ("terminated".equals(pending.instance.getState().getName())) {
                            pendingTerminations.remove(instanceId, pending);
                        } else {
                            pending.retryAtNanos = nanoTime.getAsLong()
                                    + TimeUnit.SECONDS.toNanos(pending.retryDelaySeconds);
                            pending.retryArmed = true;
                            pending.retryDelaySeconds = Math.min(
                                    TERMINATION_RETRY_MAX_DELAY_SECONDS, pending.retryDelaySeconds * 2);
                            startTerminationRetries();
                        }
                    } finally {
                        pending.executing.set(false);
                    }
                }
            });
        } catch (RejectedExecutionException failure) {
            pending.executing.set(false);
            throw failure;
        }
    }

    private static final class PendingTermination {
        private final Instance instance;
        private final Runnable operation;
        private final AtomicBoolean executing = new AtomicBoolean();
        /** Whether an attempt has failed, so that {@link #retryAtNanos} holds a deadline. */
        private volatile boolean retryArmed;
        private volatile long retryAtNanos;
        private int retryDelaySeconds = 1;

        private PendingTermination(Instance instance, Runnable operation) {
            this.instance = instance;
            this.operation = operation;
        }
    }

    /** Complete ephemeral teardown before shutdown destroys the lifecycle executor. */
    public void terminateForShutdown(Instance instance) {
        // A cancelled launch can be terminal while its captured workload cleanup remains uncertain.
        FailedLaunchCleanup failedLaunch = failedLaunchCleanups.get(instance.getInstanceId());
        if (failedLaunch != null) {
            RequestScopes.runAs(failedLaunch.accountId(), failedLaunch.region(),
                    () -> completeFailedLaunchCleanup(failedLaunch));
        }
        PendingTermination pending = pendingTermination(instance, null);
        if (pending == null) {
            return;
        }
        pending.operation.run();
        if (instance.getState() == null || !"terminated".equals(instance.getState().getName())) {
            throw new IllegalStateException("EC2 instance teardown did not complete: " + instance.getInstanceId());
        }
        pendingTerminations.remove(instance.getInstanceId(), pending);
    }

    private Runnable terminationOperation(Instance instance, Runnable afterTeardown) {
        String containerId;
        String containerIp;
        String imdsSourceIp;
        String firewallEniId;
        int sshHostPort;
        AtomicBoolean notified = new AtomicBoolean();
        synchronized (instance) {
            containerId = instance.getDockerContainerId();
            containerIp = instance.getContainerBridgeIp();
            imdsSourceIp = instance.getImdsSourceIp();
            sshHostPort = instance.getSshHostPort();
            firewallEniId = instance.getNetworkInterfaces() == null || instance.getNetworkInterfaces().isEmpty()
                    ? null : instance.getNetworkInterfaces().getFirst().getNetworkInterfaceId();
            instance.setState(InstanceState.shuttingDown());
        }
        // Capture ownership now: a retained ENI may be registered again before Docker cleanup runs.
        Runnable firewallCleanup = firewallManager != null && firewallManager.enabled() && firewallEniId != null
                ? firewallManager.captureUnregister(firewallEniId) : null;
        List<Runnable> teardownSteps = List.of(
                () -> systemdV1.remove(instance),
                () -> {
                    // Retire the endpoint before removing its namespace: another termination may reconcile peers.
                    if (firewallCleanup != null) {
                        firewallCleanup.run();
                    }
                },
                () -> removeNetworkHelper(instance));
        List<Runnable> ownerCleanupSteps = List.of(
                () -> {
                    if (sshHostPort > 0) {
                        portAllocator.release(sshHostPort);
                    }
                },
                () -> metadataServer.unregisterContainer(containerIp, instance),
                () -> metadataServer.unregisterContainer(imdsSourceIp, instance),
                () -> metadataServer.unregisterInstance(instance),
                // Give the address back only after its original workload and helper are gone.
                () -> vpcNetworkManager.releasePrivateIp(instance.getRegion(), instance.getSubnetId(),
                        instance.getPrivateIpAddress()),
                () -> {
                    long terminatedAt = clock.millis();
                    instance.setState(InstanceState.terminated());
                    instance.setTerminatedAt(terminatedAt);
                });
        return new Runnable() {
            private boolean unpublished;
            private boolean workloadGone;
            private int nextTeardown;
            private int nextOwnerCleanup;

            @Override
            public void run() {
                try {
                    advance();
                } finally {
                    boolean teardownComplete;
                    synchronized (this) {
                        teardownComplete = workloadGone && nextTeardown == teardownSteps.size();
                    }
                    // Callbacks may invoke shutdown from another thread; never hold the operation monitor here.
                    if (afterTeardown != null && teardownComplete && notified.compareAndSet(false, true)) {
                        try {
                            afterTeardown.run();
                        } catch (Exception e) {
                            LOG.warnv("Post-teardown hook failed for instance {0}: {1}",
                                    instance.getInstanceId(), e.getMessage());
                        }
                    } else if (afterTeardown != null && !teardownComplete) {
                        LOG.warnv("Skipping post-teardown hook for instance {0}: its container is "
                                + "not established to be gone, so anything waiting on the container "
                                + "being gone would fail", instance.getInstanceId());
                    }
                }
            }

            // Async retries and synchronous shutdown share this captured operation. Only a normally completed
            // step advances: a later failure cannot release an already completed lease again after its reuse.
            private synchronized void advance() {
                if (!unpublished) {
                    portForwardManager.unpublishAll(instance);
                    unpublished = true;
                }
                if (!workloadGone) {
                    if (containerId != null) {
                        try {
                            try {
                                OwnedNamespace namespace = ownedNamespace(instance, containerId);
                                if (namespace != null) {
                                    networkHelpers.put(instance.getInstanceId(), namespace.helperId());
                                }
                            } catch (NotFoundException ignored) {
                                // A missing helper does not establish that the workload is also gone.
                            }
                            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
                        } catch (NotFoundException ignored) {
                            // The exact captured workload is already gone.
                        } catch (Exception e) {
                            LOG.warnv("Error removing EC2 container {0}: {1}", containerId, e.getMessage());
                            return;
                        }
                    }
                    workloadGone = true;
                    if (containerId != null) {
                        try {
                            // iptables/veth teardown lags behind container removal; pause once before lease reuse.
                            pause.pause(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                while (nextTeardown < teardownSteps.size()) {
                    teardownSteps.get(nextTeardown).run();
                    nextTeardown++;
                }
                while (nextOwnerCleanup < ownerCleanupSteps.size()) {
                    ownerCleanupSteps.get(nextOwnerCleanup).run();
                    nextOwnerCleanup++;
                }
            }
        };
    }

    /**
     * Reboots an instance via docker restart.
     */
    public void reboot(Instance instance) {
        reboot(instance, null);
    }

    /**
     * Reboots as {@link #reboot(Instance)} does, then re-reads the container's addresses and runs
     * {@code postReboot}, so the caller publishes them only after Docker restarted the container.
     *
     * <p>The instance stays running throughout, as on AWS: Auto Scaling must not replace it in the
     * moment between Docker's stop and start, and a reboot that fails after Docker stopped the
     * container starts it again.
     */
    public void reboot(Instance instance, Runnable postReboot) {
        String containerId = instance.getDockerContainerId();
        if (containerId == null) {
            return;
        }
        InstanceState expected = instance.getState();
        // As for start: the lifecycle worker has no request context, and native VPC address
        // spaces are keyed by account, so the default account would find no subnet at all.
        String accountId = instance.getOwnerId() != null ? instance.getOwnerId() : regionResolver.getAccountId();
        apiReboots.merge(containerId, 1, Integer::sum);
        submitLifecycleOperation(containerId, () -> RequestScopes.runAs(accountId, instance.getRegion(), () -> {
            if (instance.getState() != expected || expected == null || !"running".equals(expected.getName())
                    || !containerId.equals(instance.getDockerContainerId())) {
                return;
            }
            try {
                dockerClient.restartContainerCmd(containerId).exec();
                guestReboots.forget(instance);
                releaseNativeGuest(instance, containerId);
                refreshStartedContainerAddresses(instance, containerId, protectedNamespace(instance));
                if (postReboot != null) {
                    postReboot.run();
                }
                LOG.infov("Rebooted EC2 container {0}", containerId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.debugv(e, "Interrupted address refresh after rebooting EC2 container {0}", containerId);
            } catch (Exception e) {
                LOG.warnv("Error rebooting EC2 container {0}: {1}", containerId, e.getMessage());
                startAfterFailedReboot(instance, containerId, expected);
            }
        })).whenComplete((ignored, failure) ->
                apiReboots.computeIfPresent(containerId, (id, count) -> count > 1 ? count - 1 : null));
    }

    /** A failed reboot must not leave the guest stopped, which Auto Scaling would take for a dead instance. */
    private void startAfterFailedReboot(Instance instance, String containerId, InstanceState expected) {
        if (instance.getState() != expected || lifecycleManager.isContainerRunning(containerId)) {
            return;
        }
        try {
            dockerClient.startContainerCmd(containerId).exec();
            releaseNativeGuest(instance, containerId);
            LOG.infov("Started EC2 container {0} again after its reboot failed", containerId);
        } catch (RuntimeException failure) {
            LOG.warnv("Could not start EC2 container {0} again after its reboot failed: {1}",
                    containerId, failure.getMessage());
        }
    }

    public boolean isContainerRunning(Instance instance) {
        String containerId = instance.getDockerContainerId();
        return isContainerRunning(containerId);
    }

    private void releaseNativeGuest(Instance instance, String containerId) {
        try {
            if (systemdV1.release(instance, containerId)) {
                guestReboots.released(instance);
            }
        } catch (RuntimeException failure) {
            if (systemdV1.hasGuest(instance)) {
                guestReboots.failed(instance);
            }
            throw failure;
        }
    }

    private void restartNativeGuest(Instance instance) {
        String containerId = instance.getDockerContainerId();
        dockerClient.startContainerCmd(containerId).exec();
        releaseNativeGuest(instance, containerId);
        LOG.infov("Resumed native EC2 reboot in container {0}", containerId);
    }

    /** Signals that an instance's file system could not be captured as a Docker image. */
    public static class CaptureFailedException extends RuntimeException {
        public CaptureFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Captures an instance's file system as a new Docker image, so that an AMI created from it
     * carries what was provisioned rather than pointing back at the base image.
     *
     * <p>Returns the image reference, or null when the instance has no container to capture.
     * A commit that is attempted and fails throws instead of returning null: an AMI with no
     * captured file system launches its ancestor, so reporting the failure as "no capture" would
     * hand back an available AMI whose contents are silently not what was asked for.
     *
     * @param tag repository:tag to commit to, unique per AMI
     * @throws CaptureFailedException if the commit was attempted and did not succeed
     */
    public String commitInstance(Instance instance, String tag) {
        String containerId = instance.getDockerContainerId();
        if (containerId == null) {
            return null;
        }
        try {
            // Committing a running container is what AWS does for CreateImage without
            // NoReboot; docker quiesces nothing either way, so the semantics match closely
            // enough. The container is left running -- CreateImage does not terminate its
            // source instance.
            String imageId = dockerClient.commitCmd(containerId)
                    .withRepository(tag.contains(":") ? tag.substring(0, tag.indexOf(':')) : tag)
                    .withTag(tag.contains(":") ? tag.substring(tag.indexOf(':') + 1) : "latest")
                    .exec();
            LOG.infov("Captured EC2 instance {0} as Docker image {1} ({2})",
                    instance.getInstanceId(), tag, imageId);
            return tag;
        } catch (Exception e) {
            LOG.warnv("Could not capture EC2 instance {0} as an image: {1}",
                    instance.getInstanceId(), e.getMessage());
            throw new CaptureFailedException("could not commit container " + containerId
                    + " of instance " + instance.getInstanceId() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Removes an image previously produced by {@link #commitInstance}. Called when the AMI that
     * owns it is deregistered, so captures do not accumulate on disk indefinitely.
     *
     * @return true when the layer is known to be gone, either removed now or already absent;
     *         false when the daemon refused, in which case the caller must keep the reference
     *         so the layer can still be found and removed later
     */
    public boolean removeCommittedImage(String tag) {
        if (tag == null) {
            return true;
        }
        try {
            dockerClient.removeImageCmd(tag).withForce(true).exec();
            LOG.infov("Removed captured Docker image {0}", tag);
            return true;
        } catch (NotFoundException e) {
            // Already gone: deregistering twice, or the daemon was pruned. Not an error.
            LOG.debugv("Captured Docker image {0} was already absent", tag);
            return true;
        } catch (Exception e) {
            LOG.warnv("Could not remove captured Docker image {0}: {1}", tag, e.getMessage());
            return false;
        }
    }

    private void injectSshKey(String containerId, String publicKey) {
        try {
            // Ensure .ssh directory exists with correct permissions
            execInContainer(containerId, new String[]{"sh", "-c",
                    "mkdir -p /root/.ssh && chmod 700 /root/.ssh"}, 10);

            // Copy authorized_keys via docker cp
            String keyContent = publicKey.trim() + "\n";
            RetryingTarCopier.copyBytes(dockerClient, containerId, "/root/.ssh",
                    "authorized_keys", keyContent.getBytes(StandardCharsets.UTF_8), 0600);

            execInContainer(containerId, new String[]{"chmod", "600", "/root/.ssh/authorized_keys"}, 5);
            LOG.infov("Injected SSH public key into container {0}", containerId);
        } catch (Exception e) {
            LOG.warnv("Could not inject SSH key into container {0}: {1}", containerId, e.getMessage());
        }
    }

    private void startSshd(String containerId, String instanceId) {
        try {
            // Exit 1 means sshd is absent and there is nothing to start; exit 2 means sshd is
            // there but the client package did not land. Only the first is fatal - see
            // sshdInstallProbeCommand() for why the probe distinguishes them.
            ContainerExec.Result install = execInContainerForResult(containerId, sshdInstallProbeCommand(), 120);
            if (install.exitCode() == SSH_CLIENT_MISSING_EXIT_CODE) {
                LOG.warnv("sshd is available on EC2 instance {0} but the OpenSSH client package is not:"
                        + " scp is missing, so provisioners that upload files over scp will fail: {1}",
                        instanceId, install.summary());
            } else if (install.exitCode() != 0) {
                LOG.warnv("Could not install openssh-server for EC2 instance {0}: {1}",
                        instanceId, install.summary());
                return;
            }
            // Generate host keys
            ContainerExec.Result keygen = execInContainerForResult(containerId, new String[]{"ssh-keygen", "-A"}, 10);
            if (keygen.exitCode() != 0) {
                LOG.warnv("Could not generate SSH host keys for EC2 instance {0}: {1}",
                        instanceId, keygen.summary());
                return;
            }
            // Modern OpenSSH refuses to start without its privilege-separation directory, and /run
            // is a fresh tmpfs in most container images, so /run/sshd genuinely isn't there yet.
            ContainerExec.Result mkdir = execInContainerForResult(containerId,
                    new String[]{"mkdir", "-p", "/run/sshd"}, 5);
            if (mkdir.exitCode() != 0) {
                LOG.warnv("Could not create /run/sshd for EC2 instance {0}: {1}",
                        instanceId, mkdir.summary());
                return;
            }
            // Start sshd without -D so it daemonizes itself and survives this exec session. Since sshd
            // requires execution with an absolute path, several paths are tried until it starts
            for (String sshdPath : ALLOWED_SSHD_PATHS) {
                ContainerExec.Result start = execInContainerForResult(containerId, new String[]{sshdPath}, 5);
                if (start.exitCode() != 0) {
                    LOG.warnv("Could not start sshd using path {0} for EC2 instance {1}: {2}", sshdPath, instanceId, start.summary());
                    continue;
                }
                LOG.infov("Started sshd in EC2 instance {0}", instanceId);
                return;
            }
        } catch (Exception e) {
            LOG.warnv("Could not start sshd in EC2 instance {0}: {1}", instanceId, e.getMessage());
        }
    }

    private void executeUserData(String containerId, String instanceId, String userData, String region) {
        String logGroup = "/aws/ec2/" + instanceId;
        String logStream = logStreamer != null ? logStreamer.generateLogStreamName("user-data") : null;

        Consumer<String> lineConsumer = line -> {
            if (logStreamer != null && logGroup != null && logStream != null && region != null) {
                logStreamer.streamToCloudWatchLogs(logGroup, logStream, region, line);
            }
        };

        UserDataPipeline.executeUserData(
                dockerClient, containerId, "EC2 instance " + instanceId, userData,
                userDataExecutionTimeout, lineConsumer, activeUserDataCallbacks);
    }

    private void closeActiveUserDataCallbacks() {
        activeUserDataCallbacks.forEach(callback -> closeUserDataCallback(callback, "active UserData"));
    }

    private void closeUserDataCallback(ResultCallback<Frame> callback, String context) {
        try {
            callback.close();
        } catch (IOException e) {
            LOG.warnv("Could not close Docker UserData callback for {0}: {1}", context, e.getMessage());
        }
    }

    static List<String> userDataShellScripts(String userData) {
        return UserDataPipeline.extractShellScripts(userData);
    }

    static String decodeUserDataPayload(String userData) {
        return UserDataPipeline.decodeUserDataPayload(userData);
    }

    static String[] userDataExecutionCommand() {
        return new String[]{USER_DATA_SCRIPT_PATH};
    }

    /**
     * The install-and-verify probe {@link #startSshd} execs in the guest. Extracted so tests can
     * assert against the string production actually issues rather than against a copy of it.
     *
     * <p>Installs openssh-server if absent. The trailing "command -v" checks make the script's own
     * exit code the source of truth for whether sshd is actually available afterward - without
     * them, a shell if/elif chain with no matching branch (no dnf/yum/apt-get/apk found) or whose
     * install command itself failed (e.g. no network yet, apt lock held) still exits 0 by bash
     * convention, so every later step in startSshd would silently no-op against a daemon that was
     * never installed while still logging success.
     *
     * <p>The client package is installed alongside the server because provisioning tools need scp
     * <em>on the instance</em>: Packer's default file transfer for a shell provisioner uploads the
     * script with scp, and real AMIs carry it. Installing only openssh-server leaves sftp-server
     * present but /usr/bin/scp absent, so the upload fails with "SCP failed to start. This usually
     * means that SCP is not properly installed on the remote system." The guard tests for scp too:
     * keying it on sshd alone would skip the install entirely on an image that already has the
     * server but no client. Package names differ - openssh-clients on rpm distributions,
     * openssh-client on Debian; apk's openssh already contains both.
     *
     * <p>The two failures are not equally fatal, so the script separates them: exit 1 means no sshd
     * and there is nothing to start, while exit 2 means sshd is there but the client package did
     * not land. A guest that can serve SSH but cannot scp is still worth starting - it just cannot
     * run a Packer shell provisioner - so that case warns and continues rather than leaving the
     * instance unreachable. Checking only sshd at the end would report that state as outright
     * success, which is the silent failure this probe exists to prevent.
     */
    static String[] sshdInstallProbeCommand() {
        return new String[]{"sh", "-c",
            // dnf and yum are Python programs: an image's PYTHONHOME or LD_LIBRARY_PATH made them
            // fail to start, so sshd was never installed.
            Ec2MetadataProxy.CLEAR_APPLICATION_PATHS + ";" +
            "if ! command -v sshd >/dev/null 2>&1 || ! command -v scp >/dev/null 2>&1; then" +
                    "  if command -v dnf >/dev/null 2>&1; then dnf install -y openssh-server openssh-clients >/dev/null 2>&1;" +
                    // yum is probed after dnf, and the order is load-bearing: Amazon Linux
                    // 2023 and modern Fedora/RHEL ship both, and there dnf is the supported
                    // front end while yum is only a compatibility shim over it. Amazon Linux
                    // 2 -- reached by explicitly requesting ami-amazonlinux2, not the default
                    // image -- ships only yum, so without this branch the chain falls through
                    // and sshd is never installed.
                    "  elif command -v yum >/dev/null 2>&1; then yum install -y openssh-server openssh-clients >/dev/null 2>&1;" +
                    "  elif command -v apt-get >/dev/null 2>&1; then DEBIAN_FRONTEND=noninteractive apt-get install -y openssh-server openssh-client >/dev/null 2>&1;" +
                    "  elif command -v apk >/dev/null 2>&1; then apk add --no-cache openssh >/dev/null 2>&1;" +
                    "  fi;" +
                    "fi;" +
                    "command -v sshd >/dev/null 2>&1 || exit 1;" +
                    "command -v scp >/dev/null 2>&1 || exit 2"};
    }

    static String[] metadataProxyInstallCommand() {
        return Ec2MetadataProxy.ec2InstallCommand();
    }

    static String[] metadataProxyStartCommand(String flociHost, int imdsPort) {
        return Ec2MetadataProxy.ec2RoutingCommand(flociHost, imdsPort);
    }


    static List<String> localAwsEnvironment(String region, String serviceEndpoint) {
        return localAwsEnvironment(region, serviceEndpoint, false);
    }

    static List<String> localAwsEnvironment(String region, String serviceEndpoint, boolean hasInstanceProfile) {
        List<String> environment = new ArrayList<>(List.of(
                "AWS_EC2_METADATA_SERVICE_ENDPOINT=http://169.254.169.254",
                "AWS_ENDPOINT_URL=" + serviceEndpoint,
                "AWS_DEFAULT_REGION=" + region,
                "AWS_REGION=" + region));
        if (!hasInstanceProfile) {
            environment.addAll(List.of("AWS_ACCESS_KEY_ID=test", "AWS_SECRET_ACCESS_KEY=test",
                    "AWS_SESSION_TOKEN=test-session-token"));
        }
        return environment;
    }

    static String summarizeUserDataOutput(UserDataPipeline.BoundedOutput output) {
        return UserDataPipeline.summarizeUserDataOutput(output);
    }

    static class BoundedOutput extends UserDataPipeline.BoundedOutput {
        BoundedOutput(int capacity) {
            super(capacity);
        }
    }

    /** The primary interface when guest IPv6 is enabled and it holds an IPv6 address, else null. */
    private InstanceNetworkInterface guestIpv6(Instance instance) {
        if (!config.services().ec2().guestIpv6() || instance.getNetworkInterfaces() == null) {
            return null;
        }
        for (InstanceNetworkInterface eni : instance.getNetworkInterfaces()) {
            if (eni.getDeviceIndex() == 0 && !eni.getIpv6Addresses().isEmpty() && !eni.getVpcIpv6CidrBlocks().isEmpty()) {
                return eni;
            }
        }
        return null;
    }

    /**
     * Puts the primary interface's IPv6 address on the namespace's VPC interface (the one holding its private IPv4),
     * routes the VPC's IPv6 block on it with a default route to the VPC router's link-local fe80:ec2::1, and leaves
     * every other interface without IPv6, so IPv6 leaves the guest only through its VPC.
     */
    private void configureGuestIpv6(String helperId, Instance instance, String region, String vpcNetwork,
                                    String vpcAddress) {
        InstanceNetworkInterface eni = guestIpv6(instance);
        if (eni == null) {
            return;
        }
        ipv6Router.join(region, instance.getSubnetId(), vpcNetwork, eni.getVpcIpv6CidrBlocks().get(0));
        ContainerExec.Result result = execInContainerForResult(helperId,
                guestIpv6Command(vpcAddress, eni.getIpv6Addresses().get(0), eni.getVpcIpv6CidrBlocks().get(0)), 30);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Cannot configure guest IPv6 for " + instance.getInstanceId() + ": "
                    + result.summary());
        }
        LOG.infov("Configured IPv6 {0} for EC2 instance {1}", eni.getIpv6Addresses().get(0), instance.getInstanceId());
    }

    static String[] guestIpv6Command(String vpcAddress, String ipv6Address, String vpcIpv6Block) {
        String script = String.join("\n",
                "set -eu",
                "vpc=$(ip -o -4 addr show | awk -v a=\"$1\" '{split($4, p, \"/\"); sub(/@.*/, \"\", $2); if (p[1] == a) print $2}' | head -n1)",
                "[ -n \"$vpc\" ] || { echo \"no interface holds $1\" >&2; exit 3; }",
                "for dev in $(ls /sys/class/net); do",
                "  [ \"$dev\" = lo ] && continue",
                "  [ \"$dev\" = \"$vpc\" ] && continue",
                "  ip link set dev \"$dev\" addrgenmode none 2>/dev/null || true",
                "  ip -6 addr flush dev \"$dev\" scope global 2>/dev/null || true",
                "  ip -6 addr flush dev \"$dev\" scope link 2>/dev/null || true",
                "done",
                "ip -6 addr replace \"$2/64\" dev \"$vpc\" nodad",
                "ip -6 route replace \"$3\" dev \"$vpc\"",
                "ip -6 route replace default via fe80:ec2::1 dev \"$vpc\"",
                // Docker's hosts file also names ::1 localhost; getaddrinfo returns ::1 first once the namespace has
                // IPv6, so a client of an IPv4-only loopback listener fails. EC2 Linux AMIs keep localhost on
                // 127.0.0.1 (::1 is localhost6), so the guest does too. The guest shares this namespace's hosts file.
                "hosts=$(awk '" + LOCALHOST_IPV4_ONLY + "' /etc/hosts) && printf '%s\\n' \"$hosts\" > /etc/hosts"
                        + " || echo 'cannot keep localhost on 127.0.0.1 in /etc/hosts' >&2");
        return new String[] {"sh", "-c", script, "guest-ipv6", vpcAddress, ipv6Address, vpcIpv6Block};
    }

    /** An awk program that drops the name localhost from ::1 lines of a hosts file, and a ::1 line left nameless. */
    private static final String LOCALHOST_IPV4_ONLY = "$1 == \"::1\" { out = $1;"
            + " for (i = 2; i <= NF; i++) if ($i != \"localhost\") out = out \"\\t\" $i;"
            + " if (out != $1) print out; next } { print }";

    private void configureLinkLocalMetadataEndpoint(String containerId, Instance instance, String flociHost, int imdsPort) {
        configureLinkLocalMetadataEndpoint(containerId, instance, flociHost, imdsPort, () -> true);
    }

    private void configureLinkLocalMetadataEndpoint(String containerId, Instance instance, String flociHost, int imdsPort,
                                                    BooleanSupplier stillWanted) {
        if (!stillWanted.getAsBoolean()) {
            return;
        }
        ContainerExec.Result install = execInContainerForResult(containerId, metadataProxyInstallCommand(), 180);
        if (install.exitCode() != 0) {
            throw new IllegalStateException("Cannot prepare EC2 metadata dependencies: " + install.summary());
        }

        if (!stillWanted.getAsBoolean()) {
            return;
        }
        boolean enabled = instance.getMetadataOptions() == null
                || !"disabled".equals(instance.getMetadataOptions().getHttpEndpoint());
        ContainerExec.Result start = execInContainerForResult(containerId,
                Ec2MetadataProxy.ec2RoutingCommand(flociHost, imdsPort, enabled), 30);
        if (start.exitCode() != 0) {
            throw new IllegalStateException("Cannot prepare EC2 metadata routing: " + start.summary());
        }

        LOG.infov("Configured link-local IMDS endpoint for EC2 instance {0}", instance.getInstanceId());
    }

    private void execInContainer(String containerId, String[] cmd, int timeoutSeconds) {
        execInContainerForResult(containerId, cmd, timeoutSeconds);
    }

    private ContainerExec.Result execInContainerForResult(String containerId, String[] cmd, int timeoutSeconds) {
        BoundedOutput output = new BoundedOutput(UserDataPipeline.MAX_EXEC_OUTPUT_BYTES);
        ContainerExec.Result result;
        try {
            result = ContainerExec.run(dockerClient, containerId, cmd, timeoutSeconds, output, output);
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw e;
            }
            return new ContainerExec.Result(-1, "", Objects.requireNonNullElse(e.getMessage(), e.toString()), false);
        }
        if (result.timedOut()) {
            return result;
        }
        return new ContainerExec.Result(result.exitCode(), summarizeUserDataOutput(output), "", false);
    }

    private String getContainerBridgeIp(String containerId) {
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            if (inspect.getNetworkSettings() != null) {
                Map<String, ContainerNetwork> networks = inspect.getNetworkSettings().getNetworks();
                if (networks != null) {
                    Optional<String> preferredIp = preferredMetadataSourceIp(networks);
                    if (preferredIp.isPresent()) {
                        return preferredIp.get();
                    }
                }
                String ip = inspect.getNetworkSettings().getIpAddress();
                if (ip != null && !ip.isBlank()) {
                    return ip;
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not inspect container {0} for bridge IP: {1}", containerId, e.getMessage());
        }
        return null;
    }

    private String waitForContainerBridgeIp(String containerId, String instanceId) throws InterruptedException {
        return waitForContainerBridgeIp(containerId, instanceId, null);
    }

    private String waitForContainerBridgeIp(String containerId, String instanceId, Instance instance)
            throws InterruptedException {
        for (int i = 0; i < CONTAINER_BRIDGE_IP_ATTEMPTS; i++) {
            if (instance != null && isLaunchCancelled(instance)) {
                return null;
            }
            String containerIp = getContainerBridgeIp(containerId);
            if (containerIp != null && !containerIp.isBlank()) {
                return containerIp;
            }
            pause.pause(CONTAINER_BRIDGE_IP_POLL_MILLIS);
        }
        LOG.warnv("Timed out waiting for EC2 instance {0} container {1} bridge IP", instanceId, containerId);
        return null;
    }

    static Optional<String> preferredMetadataSourceIp(Map<String, ContainerNetwork> networks) {
        return Ec2MetadataProxy.preferredMetadataSourceIp(networks);
    }

    /**
     * The instance's SSH host public keys, read from its running container. AWS prints these to
     * the console log at first boot (cloud-init's "SSH HOST KEY KEYS" block), which is how a
     * client verifies the host it connects to.
     */
    public String readSshHostKeys(String containerId) {
        String[] cmd = {"sh", "-c", "for f in /etc/ssh/ssh_host_*_key.pub; do [ -f \"$f\" ] && cat \"$f\"; done"};
        // The files are the guest's to write, so capture no more than real host keys can take.
        UserDataPipeline.BoundedOutput stdout = new UserDataPipeline.BoundedOutput(SSH_HOST_KEYS_MAX_BYTES);
        UserDataPipeline.BoundedOutput stderr = new UserDataPipeline.BoundedOutput(SSH_HOST_KEYS_STDERR_BYTES);
        ContainerExec.Result result = ContainerExec.run(dockerClient, containerId, cmd, 15, stdout, stderr);
        if (result.exitCode() != 0) {
            return "";
        }
        if (stdout.truncated()) {
            LOG.warnv("Ignoring SSH host keys of container {0}: {1} bytes exceed the {2}-byte limit",
                    containerId, stdout.totalBytes(), SSH_HOST_KEYS_MAX_BYTES);
            return "";
        }
        return new String(stdout.toByteArray(), StandardCharsets.UTF_8);
    }

}
