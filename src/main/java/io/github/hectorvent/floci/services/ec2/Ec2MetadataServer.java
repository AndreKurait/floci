package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import io.github.hectorvent.floci.services.iam.model.InstanceProfile;
import io.github.hectorvent.floci.services.iam.model.SessionCredential;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * IMDS-compatible HTTP server bound to port 9169 on the Floci host.
 * EC2 containers are launched with AWS_EC2_METADATA_SERVICE_ENDPOINT pointing here.
 *
 * Implements IMDSv2 (token-based) and IMDSv1 (no token) — containers using the
 * standard AWS SDK credential chain will hit /latest/meta-data/iam/security-credentials/
 * to obtain temporary credentials backed by the instance's IAM instance profile.
 */
@ApplicationScoped
public class Ec2MetadataServer {

    private static final Logger LOG = Logger.getLogger(Ec2MetadataServer.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);
    /** IMDSv2 tokens live from one second up to six hours. */
    private static final int MAX_TOKEN_TTL_SECONDS = 21_600;
    private static final String IDENTITY_ROLE = "ec2-instance";
    private static final String INSTANCE_TAGS_PREFIX = "/meta-data/tags/instance/";
    private static final Set<String> METADATA_VERSIONS = Set.of(
            "1.0", "2007-01-19", "2007-03-01", "2007-08-29", "2007-10-10", "2007-12-15",
            "2008-02-01", "2008-09-01", "2009-04-04", "2011-01-01", "2011-05-01", "2012-01-12",
            "2014-02-25", "2014-11-05", "2015-10-20", "2016-04-19", "2016-06-30", "2016-09-02",
            "2018-03-28", "2018-08-17", "2018-09-24", "2019-10-01", "2020-10-27", "2021-01-03",
            "2021-03-23", "2021-07-15", "2022-09-24", "2024-04-11", "2025-10-04", "2026-04-15");

    private final Vertx vertx;
    private final EmulatorConfig config;
    private final Ec2InstanceCredentials credentials;
    private final Clock clock;
    private final Map<String, String> metadataCategories = new ConcurrentHashMap<>();

    /** IMDSv2: token value → Instance */
    private final Map<String, SessionToken> tokens = new ConcurrentHashMap<>();
    /** IMDSv1 fallback: container bridge IP → Instance */
    private final Map<String, Instance> containerIpToInstance = new ConcurrentHashMap<>();
    /** The task that registered an ECS container address, so releasing another task leaves it alone. */
    private final Map<String, String> containerIpOwners = new ConcurrentHashMap<>();
    /**
     * ECS task containers about to start, by container id. Docker assigns the address at start, so a
     * request from an address IMDS does not know yet is matched against these before it is refused.
     */
    private final Map<String, PendingContainer> pendingContainers = new ConcurrentHashMap<>();
    /** Cluster nodes whose instance-profile identity their registrar synthesizes rather than IAM holding it. */
    private final Set<Instance> synthesizedProfiles = ConcurrentHashMap.newKeySet();

    private volatile HttpServer httpServer;

    @Inject
    public Ec2MetadataServer(Vertx vertx, EmulatorConfig config, IamService iamService) {
        this(vertx, config, iamService, Clock.systemUTC());
    }

    Ec2MetadataServer(Vertx vertx, EmulatorConfig config, IamService iamService, Clock clock) {
        this.vertx = vertx;
        this.config = config;
        this.credentials = new Ec2InstanceCredentials(iamService);
        this.clock = clock;
    }

    /** Registers the network namespace before an EC2 guest starts. */
    public void registerContainer(String containerIp, String instanceId, Instance instance) {
        if (containerIp != null && !containerIp.isBlank()) {
            credentials.register(instance);
            containerIpToInstance.put(containerIp, instance);
            LOG.debugv("IMDS: registered container {0} → instance {1}", containerIp, instanceId);
        }
    }

    /**
     * Registers an ECS task container's address on behalf of its task. A later address of the same
     * value (Docker reuses a stopped container's) replaces the owner.
     */
    public void registerContainer(String containerIp, String instanceId, Instance instance, String owner) {
        if (containerIp != null && !containerIp.isBlank()) {
            // Owner first: a release of the previous owner racing this call then finds nothing of its own.
            containerIpOwners.put(containerIp, owner);
            registerContainer(containerIp, instanceId, instance);
        }
    }

    /** Drops a pending container once its address is registered. */
    public void forgetPendingContainer(String containerId) {
        pendingContainers.remove(containerId);
    }

    /**
     * Registers an ECS task container before Docker starts it, so its process is served from its
     * first request; {@code address} resolves the container's address once it runs.
     */
    public void registerPendingContainer(String containerId, String owner, Supplier<String> address,
                                         Instance instance) {
        credentials.register(instance);
        pendingContainers.put(containerId, new PendingContainer(owner, address, instance));
    }

    /** Unregisters a task's address only while that task still owns it, and forgets its pending containers. */
    public void unregisterContainer(String containerIp, Instance instance, String owner) {
        // The owner entry goes only with the mapping it guards: the caller asks every instance in turn.
        if (containerIp != null && instance != null && owner.equals(containerIpOwners.get(containerIp))
                && containerIpToInstance.remove(containerIp, instance)) {
            containerIpOwners.remove(containerIp, owner);
        }
        pendingContainers.values().removeIf(pending -> pending.owner().equals(owner));
    }

    private record PendingContainer(String owner, Supplier<String> address, Instance instance) {
        String addressOrNull() {
            try {
                return address.get();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** The instance an address belongs to, resolving a pending ECS container on a miss. */
    /** ECS task containers registered before start and not yet matched or released. */
    public int pendingContainerCount() {
        return pendingContainers.size();
    }

    Instance instanceForAddress(String remoteIp) {
        Instance inst = containerIpToInstance.get(remoteIp);
        if (inst != null || pendingContainers.isEmpty()) {
            return inst;
        }
        for (Map.Entry<String, PendingContainer> entry : pendingContainers.entrySet()) {
            PendingContainer pending = entry.getValue();
            if (remoteIp.equals(pending.addressOrNull()) && pendingContainers.remove(entry.getKey(), pending)) {
                containerIpToInstance.put(remoteIp, pending.instance());
                containerIpOwners.put(remoteIp, pending.owner());
                return pending.instance();
            }
        }
        return null;
    }

    /** Called by Ec2ContainerManager when a container is terminated. */
    public void unregisterContainer(String containerIp, Instance instance) {
        if (containerIp != null && instance != null) {
            containerIpToInstance.remove(containerIp, instance);
        }
    }

    /** Reconcile every Docker attachment without retaining stale addresses after restart. */
    public void reconcileContainerAddresses(Set<String> addresses, Instance instance) {
        for (String address : addresses) {
            registerContainer(address, instance.getInstanceId(), instance);
        }
        containerIpToInstance.entrySet().removeIf(entry ->
                entry.getValue() == instance && !addresses.contains(entry.getKey()));
    }

    /**
     * Registers a cluster node that another service synthesizes instead of EC2 launching it. Its
     * instance-profile ARN and ID are the registrar's own, so {@code iam/info} reports them while IAM
     * holds no such profile.
     */
    public void reconcileClusterNodeAddresses(Set<String> addresses, Instance instance) {
        synthesizedProfiles.add(instance);
        reconcileContainerAddresses(addresses, instance);
    }

    public void unregisterInstance(Instance instance) {
        if (instance != null) {
            synthesizedProfiles.remove(instance);
            credentials.unregister(instance);
            tokens.values().removeIf(token -> token.instance() == instance);
            containerIpToInstance.entrySet().removeIf(entry -> entry.getValue() == instance);
            containerIpOwners.keySet().retainAll(containerIpToInstance.keySet());
            pendingContainers.values().removeIf(pending -> pending.instance() == instance);
        }
    }

    Optional<Instance> registeredContainer(String containerIp) {
        return Optional.ofNullable(containerIpToInstance.get(containerIp));
    }

    public synchronized CompletableFuture<Void> start() {
        if (httpServer != null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        int port = config.services().ec2().imdsPort();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        // Apply instance options before any route, including issuing tokens. Methods IMDS does
        // not serve retain the router's answers when the instance permits metadata access.
        router.route().handler(this::enforceMetadataOptions);

        // IMDSv2 token endpoint
        router.put("/latest/api/token").handler(this::handleToken);
        router.put("/:metadataVersion/api/token").handler(ctx -> ctx.response().setStatusCode(403).end());

        // Metadata endpoints
        metadataGet(router, "meta-data/instance-id", "1.0").handler(ctx -> handleText(ctx, inst -> inst.getInstanceId()));
        metadataGet(router, "meta-data/ami-id", "1.0").handler(ctx -> handleText(ctx, inst -> inst.getImageId()));
        metadataGet(router, "meta-data/ami-launch-index", "1.0").handler(ctx ->
                handleText(ctx, inst -> Integer.toString(inst.getAmiLaunchIndex())));
        metadataGet(router, "meta-data/instance-type", "2007-08-29")
                .handler(ctx -> handleText(ctx, inst -> inst.getInstanceType()));
        metadataGet(router, "meta-data/local-ipv4", "1.0").handler(ctx -> handleText(ctx, inst -> inst.getPrivateIpAddress()));
        metadataGet(router, "meta-data/public-ipv4", "2007-01-19")
                .handler(ctx -> handleText(ctx, inst -> inst.getPublicIpAddress()));
        metadataGet(router, "meta-data/public-hostname", "2007-01-19")
                .handler(ctx -> handleText(ctx, inst -> inst.getPublicDnsName()));
        metadataGet(router, "meta-data/local-hostname", "2007-01-19")
                .handler(ctx -> handleText(ctx, inst -> inst.getPrivateDnsName()));
        metadataGet(router, "meta-data/hostname", "1.0").handler(ctx -> handleText(ctx, inst -> inst.getPrivateDnsName()));
        metadataGet(router, "meta-data/mac", "2011-01-01").handler(ctx -> handleMac(ctx));
        metadataGet(router, "meta-data/security-groups", "1.0").handler(ctx -> handleSecurityGroups(ctx));
        metadataGet(router, "meta-data/placement/availability-zone", "2008-02-01").handler(ctx -> handleText(ctx, inst ->
                inst.getPlacement() != null
                        ? inst.getPlacement().getAvailabilityZone()
                        : config.defaultAvailabilityZone()));
        metadataGet(router, "meta-data/placement/region", "2019-10-01")
                .handler(ctx -> handleText(ctx, inst -> inst.getRegion()));
        versionedGet(router, "meta-data/services", "2014-02-25").handler(this::handleServicesListing);
        versionedGet(router, "meta-data/services/", "2014-02-25").handler(this::handleServicesListing);
        metadataGet(router, "meta-data/services/partition", "2015-10-20")
                .handler(ctx -> handleText(ctx, inst -> AwsRegions.partitionFor(inst.getRegion())));
        metadataGet(router, "meta-data/services/domain", "2014-02-25")
                .handler(ctx -> handleText(ctx, inst -> AwsRegions.dnsSuffixFor(inst.getRegion())));
        metadataGet(router, "meta-data/iam/info", "2012-01-12").handler(ctx -> handleIamInfo(ctx));
        versionedGet(router, "meta-data/iam/security-credentials", "2012-01-12")
                .handler(ctx -> handleCredentialsList(ctx));
        metadataGet(router, "meta-data/iam/security-credentials/", "2012-01-12").handler(ctx -> handleCredentialsList(ctx));
        metadataGet(router, "meta-data/iam/security-credentials/:role", "2012-01-12").handler(ctx -> handleCredentials(ctx));
        versionedGet(router, "meta-data/identity-credentials", "2018-03-28").handler(ctx -> handleListing(ctx, "ec2/"));
        versionedGet(router, "meta-data/identity-credentials/", "2018-03-28").handler(ctx -> handleListing(ctx, "ec2/"));
        versionedGet(router, "meta-data/identity-credentials/ec2", "2018-03-28")
                .handler(ctx -> handleListing(ctx, "info\nsecurity-credentials/"));
        versionedGet(router, "meta-data/identity-credentials/ec2/", "2018-03-28")
                .handler(ctx -> handleListing(ctx, "info\nsecurity-credentials/"));
        metadataGet(router, "meta-data/identity-credentials/ec2/info", "2018-03-28").handler(ctx -> handleIdentityInfo(ctx));
        versionedGet(router, "meta-data/identity-credentials/ec2/security-credentials", "2018-03-28")
                .handler(ctx -> handleListing(ctx, IDENTITY_ROLE));
        versionedGet(router, "meta-data/identity-credentials/ec2/security-credentials/", "2018-03-28")
                .handler(ctx -> handleListing(ctx, IDENTITY_ROLE));
        metadataGet(router, "meta-data/identity-credentials/ec2/security-credentials/" + IDENTITY_ROLE, "2018-03-28")
                .handler(ctx -> handleIdentityCredentials(ctx));
        versionedGet(router, "meta-data/tags/instance", "2021-03-23").handler(ctx -> handleInstanceTagKeys(ctx));
        metadataGet(router, "meta-data/tags/instance/", "2021-03-23").handler(ctx -> handleInstanceTagKeys(ctx));
        metadataGet(router, "meta-data/tags/instance/*", "2021-03-23").handler(ctx -> handleInstanceTagValue(ctx));
        metadataGet(router, "user-data", "1.0").handler(ctx -> handleUserData(ctx));
        metadataGet(router, "dynamic/instance-identity/document", "2009-04-04").handler(ctx -> handleIdentityDocument(ctx));
        metadataGet(router, "meta-data/public-keys/0/openssh-key", "1.0").handler(ctx ->
                handleText(ctx, inst -> inst.getKeyPairPublicKey()));
        Set<String> directories = new TreeSet<>();
        for (String category : metadataCategories.keySet()) {
            for (int separator = category.lastIndexOf('/'); separator > 0;
                    separator = category.lastIndexOf('/', separator - 1)) {
                directories.add(category.substring(0, separator + 1));
            }
        }
        for (String directory : directories) {
            router.get("/:metadataVersion" + directory).handler(this::handleDirectory);
            router.get("/:metadataVersion" + directory.substring(0, directory.length() - 1))
                    .handler(this::handleDirectory);
        }

        httpServer = vertx.createHttpServer();
        httpServer.requestHandler(router).listen(port, result -> {
            if (result.succeeded()) {
                LOG.infof("EC2 IMDS server listening on port %d", port);
                future.complete(null);
            } else {
                LOG.warnf("EC2 IMDS server failed to start on port %d: %s", port, result.cause().getMessage());
                future.completeExceptionally(result.cause());
            }
        });
        return future;
    }

    private Route metadataGet(Router router, String path, String firstVersion) {
        if (!path.contains(":") && !path.contains("*")) {
            metadataCategories.put("/" + path, firstVersion);
        }
        return versionedGet(router, path, firstVersion);
    }

    private static Route versionedGet(Router router, String path, String firstVersion) {
        return router.get("/:metadataVersion/" + path).handler(ctx -> {
            if (supportsVersion(ctx, firstVersion)) {
                ctx.next();
            } else {
                ctx.response().setStatusCode(404).end();
            }
        });
    }

    private static boolean supportsVersion(RoutingContext ctx, String firstVersion) {
        String version = ctx.pathParam("metadataVersion");
        return "latest".equals(version)
                || (METADATA_VERSIONS.contains(version) && version.compareTo(firstVersion) >= 0);
    }

    private void handleServicesListing(RoutingContext ctx) {
        handleListing(ctx, supportsVersion(ctx, "2015-10-20") ? "domain\npartition" : "domain");
    }

    public synchronized void stop() {
        credentials.clear();
        tokens.clear();
        containerIpToInstance.clear();
        containerIpOwners.clear();
        pendingContainers.clear();
        synthesizedProfiles.clear();
        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        }
    }

    private void handleDirectory(RoutingContext ctx) {
        if (!supportsVersion(ctx, "1.0")) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        Instance instance = resolveInstance(ctx);
        if (instance == null) {
            return;
        }
        String pathWithVersion = ctx.normalizedPath();
        String category = pathWithVersion.substring(pathWithVersion.indexOf('/', 1));
        String directory = category.endsWith("/") ? category : category + "/";
        Set<String> entries = new TreeSet<>();
        for (Map.Entry<String, String> entry : metadataCategories.entrySet()) {
            String path = entry.getKey();
            if (!path.startsWith(directory) || path.equals(directory)
                    || !supportsVersion(ctx, entry.getValue())
                    || !metadataAvailable(instance, path)) {
                continue;
            }
            String child = path.substring(directory.length());
            int separator = child.indexOf('/');
            entries.add(separator < 0 ? child : child.substring(0, separator + 1));
        }
        if (directory.equals("/meta-data/public-keys/") && !entries.isEmpty()) {
            entries = Set.of("0=" + instance.getKeyName());
        }
        if (entries.isEmpty()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        ctx.response().putHeader("content-type", "text/plain").end(String.join("\n", entries) + "\n");
    }

    private boolean metadataAvailable(Instance instance, String category) {
        return switch (category) {
            case "/meta-data/ami-id" -> instance.getImageId() != null;
            case "/meta-data/instance-type" -> instance.getInstanceType() != null;
            case "/meta-data/local-ipv4" -> instance.getPrivateIpAddress() != null;
            case "/meta-data/public-ipv4" -> instance.getPublicIpAddress() != null;
            case "/meta-data/public-hostname" -> instance.getPublicDnsName() != null;
            case "/meta-data/hostname", "/meta-data/local-hostname" -> instance.getPrivateDnsName() != null;
            case "/meta-data/public-keys/0/openssh-key" ->
                    instance.getKeyName() != null && instance.getKeyPairPublicKey() != null;
            case "/meta-data/iam/info" -> credentials.profile(instance).isPresent()
                    || synthesizedProfiles.contains(instance) && instance.getIamInstanceProfileArn() != null
                    && !credentials.holdsProfile(instance);
            case "/meta-data/iam/security-credentials/" -> credentials.role(instance).isPresent();
            case "/meta-data/tags/instance/" ->
                    "enabled".equals(instance.effectiveMetadataOptions().getInstanceMetadataTags());
            default -> true;
        };
    }

    // ── Token (IMDSv2) ────────────────────────────────────────────────────────

    private void handleToken(RoutingContext ctx) {
        String ttlHeader = ctx.request().getHeader("x-aws-ec2-metadata-token-ttl-seconds");
        Integer ttlSeconds = parseTokenTtl(ttlHeader);
        if (ttlSeconds == null) {
            ctx.response().setStatusCode(400).end(
                    "x-aws-ec2-metadata-token-ttl-seconds must be an integer from 1 to " + MAX_TOKEN_TTL_SECONDS);
            return;
        }

        Instance inst = resolveInstanceByIp(ctx);
        String token = UUID.randomUUID().toString().replace("-", "");
        if (inst != null) {
            Instant now = clock.instant();
            tokens.values().removeIf(existing -> existing.isExpiredAt(now));
            tokens.put(token, new SessionToken(inst, now.plusSeconds(ttlSeconds)));
        }
        else {
            LOG.debugv("IMDS: token requested from {0}, which is not a registered EC2 container; "
                    + "metadata requests with this token will fail", ctx.request().remoteAddress().host());
        }

        ctx.response()
                .setStatusCode(200)
                .putHeader("x-aws-ec2-metadata-token-ttl-seconds", ttlHeader)
                .end(token);
    }

    /** Returns the TTL in seconds, or null when the header is missing or outside 1..21600. */
    private static Integer parseTokenTtl(String ttlHeader) {
        if (ttlHeader == null) {
            return null;
        }
        try {
            int ttl = Integer.parseInt(ttlHeader.trim());
            return ttl >= 1 && ttl <= MAX_TOKEN_TTL_SECONDS ? ttl : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record SessionToken(Instance instance, Instant expiresAt) {
        boolean isExpiredAt(Instant now) {
            return !now.isBefore(expiresAt);
        }
    }

    // ── Metadata helpers ──────────────────────────────────────────────────────

    @FunctionalInterface
    interface InstanceField {
        String get(Instance instance);
    }

    private void handleText(RoutingContext ctx, InstanceField field) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String value = field.get(inst);
        if (value == null) {
            ctx.response().setStatusCode(404).end("not-available");
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(value);
    }

    private void handleMac(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String mac = inst.getNetworkInterfaces().isEmpty()
                ? "02:42:ac:11:00:02"
                : inst.getNetworkInterfaces().get(0).getMacAddress();
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(mac != null ? mac : "02:42:ac:11:00:02");
    }

    private void handleSecurityGroups(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (GroupIdentifier sg : inst.getSecurityGroups()) {
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append(sg.getGroupName() != null ? sg.getGroupName() : sg.getGroupId());
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(sb.toString());
    }

    private void handleIamInfo(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        Optional<InstanceProfile> resolved = credentials.profile(inst);
        String profileArn;
        String profileId;
        if (resolved.isPresent()) {
            profileArn = resolved.get().getArn();
            profileId = resolved.get().getInstanceProfileId();
        } else if (synthesizedProfiles.contains(inst) && inst.getIamInstanceProfileArn() != null
                && !credentials.holdsProfile(inst)) {
            profileArn = inst.getIamInstanceProfileArn();
            profileId = inst.getIamInstanceProfileId();
        } else {
            ctx.response().setStatusCode(404).end("{}");
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(new JsonObject().put("Code", "Success").put("LastUpdated", now())
                        .put("InstanceProfileArn", profileArn)
                        .put("InstanceProfileId", profileId).encode());
    }

    private void handleCredentialsList(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        Optional<IamRole> role = credentials.role(inst);
        if (role.isEmpty()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        ctx.response().putHeader("content-type", "text/plain").end(role.get().getRoleName());
    }

    private void handleCredentials(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        Optional<SessionCredential> result = credentials.get(inst, ctx.pathParam("role"), Instant.now());
        if (result.isEmpty()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        SessionCredential session = result.get();
        ctx.response().putHeader("content-type", "application/json").end(credentialsJson(session));
    }

    private void handleListing(RoutingContext ctx, String entries) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        ctx.response().setStatusCode(200).putHeader("content-type", "text/plain").end(entries);
    }

    private void handleIdentityInfo(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        ctx.response().putHeader("content-type", "application/json").end(new JsonObject()
                .put("Code", "Success")
                .put("LastUpdated", ISO.format(clock.instant()))
                .put("AccountId", ownerAccount(inst)).encode());
    }

    private void handleIdentityCredentials(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        Optional<SessionCredential> result = credentials.identity(inst, ownerAccount(inst), clock.instant());
        if (result.isEmpty()) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        ctx.response().putHeader("content-type", "application/json").end(credentialsJson(result.get()));
    }

    /** The account that launched the instance; instances persisted before it was recorded belong to the default one. */
    private String ownerAccount(Instance inst) {
        return inst.getOwnerId() != null ? inst.getOwnerId() : config.defaultAccountId();
    }

    private static String credentialsJson(SessionCredential session) {
        return new JsonObject()
                .put("Code", "Success")
                .put("LastUpdated", ISO.format(session.getExpiration().minusSeconds(3600)))
                .put("Type", "AWS-HMAC")
                .put("AccessKeyId", session.getAccessKeyId())
                .put("SecretAccessKey", session.getSecretAccessKey())
                .put("Token", session.getSessionToken())
                .put("Expiration", ISO.format(session.getExpiration())).encode();
    }

    private void handleInstanceTagKeys(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(instanceTagKeys(inst));
    }

    private void handleInstanceTagValue(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }

        String path = ctx.request().path();
        String prefix = "/" + ctx.pathParam("metadataVersion") + INSTANCE_TAGS_PREFIX;
        String tagKey = path.length() <= prefix.length()
                ? ""
                : URLDecoder.decode(path.substring(prefix.length()), StandardCharsets.UTF_8);
        Optional<String> value = instanceTagValue(inst, tagKey);
        if (value.isEmpty()) {
            ctx.response().setStatusCode(404).end("not-found");
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "text/plain")
                .end(value.get());
    }

    private void handleUserData(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String encoded = inst.getEncodedUserData();
        byte[] userData;
        if (encoded != null) {
            try {
                userData = encoded.isBlank() ? null : Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException e) {
                LOG.warnv("Ignoring malformed saved user-data encoding for instance {0}", inst.getInstanceId());
                userData = inst.getUserData() == null ? null : inst.getUserData().getBytes(StandardCharsets.UTF_8);
            }
        } else {
            userData = inst.getUserData() == null ? null : inst.getUserData().getBytes(StandardCharsets.UTF_8);
        }
        if (userData == null || userData.length == 0) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "application/octet-stream")
                .end(Buffer.buffer(userData));
    }

    private void handleIdentityDocument(RoutingContext ctx) {
        Instance inst = resolveInstance(ctx);
        if (inst == null) {
            return;
        }
        String body = instanceIdentityDocument(inst, ownerAccount(inst), config.defaultAvailabilityZone());
        ctx.response().setStatusCode(200)
                .putHeader("content-type", "application/json")
                .end(body);
    }

    /**
     * {@code defaultAvailabilityZone} answers for an instance with no placement, the same configured
     * zone {@code /latest/meta-data/placement/availability-zone} reports for it.
     */
    static String instanceIdentityDocument(Instance inst, String accountId, String defaultAvailabilityZone) {
        String az = inst.getPlacement() != null ? inst.getPlacement().getAvailabilityZone() : defaultAvailabilityZone;
        String architecture = inst.getArchitecture() == null || inst.getArchitecture().isBlank()
                ? "x86_64"
                : inst.getArchitecture();
        return new JsonObject()
                .put("accountId", accountId)
                .put("architecture", architecture)
                .put("availabilityZone", az)
                .put("imageId", inst.getImageId())
                .put("instanceId", inst.getInstanceId())
                .put("instanceType", inst.getInstanceType())
                .put("privateIp", nvl(inst.getPrivateIpAddress()))
                .put("region", inst.getRegion())
                .put("version", "2017-09-30").encodePrettily();
    }

    // ── Instance resolution ───────────────────────────────────────────────────

    private Instance resolveInstanceByIp(RoutingContext ctx) {
        return containerIpToInstance.get(ctx.request().remoteAddress().host());
    }

    /**
     * Disabled endpoints serve neither metadata nor tokens. An enabled endpoint whose HttpTokens
     * is required refuses tokenless GET and HEAD requests before looking up any data.
     */
    private void enforceMetadataOptions(RoutingContext ctx) {
        String remoteIp = ctx.request().remoteAddress().host();
        if (pendingContainers.isEmpty() || containerIpToInstance.containsKey(remoteIp)) {
            enforceResolvedMetadataOptions(ctx);
            return;
        }
        // Matching a pending ECS container inspects it through Docker: off the event loop.
        vertx.<Instance>executeBlocking(() -> instanceForAddress(remoteIp), false)
                .onComplete(ignored -> enforceResolvedMetadataOptions(ctx));
    }

    private void enforceResolvedMetadataOptions(RoutingContext ctx) {
        HttpMethod method = ctx.request().method();
        String token = ctx.request().getHeader("x-aws-ec2-metadata-token");
        Instance inst = resolveInstanceByIp(ctx);
        if (inst == null && token != null) {
            SessionToken session = tokens.get(token);
            if (session != null && !session.isExpiredAt(clock.instant())) {
                inst = session.instance();
            }
        }
        if (inst != null && "disabled".equals(inst.effectiveMetadataOptions().getHttpEndpoint())) {
            ctx.response().setStatusCode(404).end();
            return;
        }
        if ((HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method)) && (token == null || token.isBlank())) {
            if (inst != null && "required".equals(inst.effectiveMetadataOptions().getHttpTokens())) {
                ctx.response().setStatusCode(401).end();
                return;
            }
        }
        ctx.next();
    }

    private Instance resolveInstance(RoutingContext ctx) {
        String remoteIp = ctx.request().remoteAddress().host();
        Instance inst = containerIpToInstance.get(remoteIp);

        // IMDSv2: a presented token must be valid; an invalid or expired one gets 401 so the
        // caller fetches a new token. A token is not valid on another instance, so a caller whose
        // IP maps to a different instance also gets 401; an unregistered IP defers to the token.
        // Requests without a token fall back to IMDSv1; one whose instance requires tokens has
        // already been refused before routing.
        String token = ctx.request().getHeader("x-aws-ec2-metadata-token");
        if (token != null && !token.isBlank()) {
            SessionToken session = tokens.get(token);
            if (session != null && !session.isExpiredAt(clock.instant())) {
                if (inst != null && !Objects.equals(inst.getInstanceId(), session.instance().getInstanceId())) {
                    ctx.response().setStatusCode(401).end();
                    return null;
                }
                return session.instance();
            }
            if (session != null) {
                tokens.remove(token, session);
            }
            if (inst != null) {
                ctx.response().setStatusCode(401).end();
                return null;
            }
        }

        if (inst == null) {
            String message = unregisteredContainerMessage(remoteIp);
            LOG.warnv("IMDS: {0}", message);
            ctx.response().setStatusCode(404)
                    .putHeader("content-type", "text/plain")
                    .end(message);
        }
        return inst;
    }

    /**
     * Explains why IMDS has nothing to serve for a request coming from {@code remoteIp}.
     *
     * <p>IMDS only knows about containers that {@link Ec2ContainerManager} launched through EC2
     * {@code RunInstances}. Registering a container's SSM agent as a managed instance
     * ({@code UpdateInstanceInformation}) does not create an EC2 instance record, so a container
     * that was only registered with SSM ends up here.
     */
    static String unregisteredContainerMessage(String remoteIp) {
        return "Instance not found: no EC2 instance is registered for source IP " + remoteIp + ". "
                + "IMDS only serves containers launched through EC2 RunInstances; "
                + "registering a container as an SSM managed instance does not register it with IMDS. "
                + "Launch the container with RunInstances first, then register its SSM agent.";
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String now() {
        return ISO.format(Instant.now());
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    static String instanceTagKeys(Instance instance) {
        StringBuilder tags = new StringBuilder();
        if (instance == null || instance.getTags() == null) {
            return "";
        }
        for (Tag tag : instance.getTags()) {
            if (tag.getKey() == null || tag.getKey().isBlank()) {
                continue;
            }
            if (!tags.isEmpty()) {
                tags.append("\n");
            }
            tags.append(tag.getKey());
        }
        return tags.toString();
    }

    static Optional<String> instanceTagValue(Instance instance, String key) {
        if (instance == null || instance.getTags() == null || key == null) {
            return Optional.empty();
        }
        for (Tag tag : instance.getTags()) {
            if (key.equals(tag.getKey())) {
                return Optional.of(nvl(tag.getValue()));
            }
        }
        return Optional.empty();
    }
}
