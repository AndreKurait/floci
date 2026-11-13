package io.github.hectorvent.floci.services.neptune;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.StripedLocks;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.neptune.container.NeptuneContainerHandle;
import io.github.hectorvent.floci.services.neptune.container.NeptuneContainerManager;
import io.github.hectorvent.floci.services.neptune.model.NeptuneCluster;
import io.github.hectorvent.floci.services.neptune.model.NeptuneClusterSettings;
import io.github.hectorvent.floci.services.neptune.model.NeptuneClusterSnapshot;
import io.github.hectorvent.floci.services.neptune.model.NeptuneDbType;
import io.github.hectorvent.floci.services.neptune.model.NeptuneInstance;
import io.github.hectorvent.floci.services.neptune.model.NeptuneInstanceSettings;
import io.github.hectorvent.floci.services.neptune.proxy.NeptuneProxyManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@ApplicationScoped
public class NeptuneService {

    private static final Logger LOG = Logger.getLogger(NeptuneService.class);
    private static final String ENGINE_VERSION_DEFAULT = "1.3.2.1";

    private final StorageBackend<String, NeptuneCluster> clusters;
    private final StorageBackend<String, NeptuneInstance> instances;
    private final StorageBackend<String, NeptuneClusterSnapshot> snapshots;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final NeptuneContainerManager containerManager;
    private final NeptuneProxyManager proxyManager;
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();
    private final Set<String> provisioningClusterNames = ConcurrentHashMap.newKeySet();
    private final StripedLocks clusterMutationLocks = new StripedLocks(256);

    @Inject
    public NeptuneService(EmulatorConfig config,
                          RegionResolver regionResolver,
                          NeptuneContainerManager containerManager,
                          NeptuneProxyManager proxyManager,
                          StorageFactory storageFactory) {
        this.config = config;
        this.regionResolver = regionResolver;
        this.containerManager = containerManager;
        this.proxyManager = proxyManager;
        this.clusters = storageFactory.create("neptune", "neptune-clusters.json",
                new TypeReference<Map<String, NeptuneCluster>>() {});
        this.instances = storageFactory.create("neptune", "neptune-instances.json",
                new TypeReference<Map<String, NeptuneInstance>>() {});
        this.snapshots = storageFactory.create("neptune", "neptune-cluster-snapshots.json",
                new TypeReference<Map<String, NeptuneClusterSnapshot>>() {});
    }

    // ── Clusters ──────────────────────────────────────────────────────────────

    public NeptuneCluster createDbCluster(String id, String engineVersion, boolean iamEnabled) {
        return createDbCluster(id, engineVersion, iamEnabled, NeptuneClusterSettings.defaults(), Map.of());
    }

    public NeptuneCluster createDbCluster(String id, String engineVersion, boolean iamEnabled,
                                          NeptuneClusterSettings settings, Map<String, String> tags) {
        settings.validate();
        String identity = resourceIdentity(currentAccount(), currentRegion(), id);
        if (!provisioningClusterNames.add(identity)) {
            throw new AwsException("DBClusterAlreadyExistsFault",
                    "Neptune cluster " + id + " already exists.", 400);
        }
        try {
            synchronized (clusterMutationLock(id)) {
                return provisionDbCluster(id, engineVersion, iamEnabled, settings, tags, null);
            }
        } finally {
            provisioningClusterNames.remove(identity);
        }
    }

    private static AwsException clusterAlreadyExists(String id) {
        return new AwsException("DBClusterAlreadyExistsFault",
                "Neptune cluster " + id + " already exists.", 400);
    }

    private NeptuneCluster provisionDbCluster(String id, String engineVersion, boolean iamEnabled,
                                             NeptuneClusterSettings settings, Map<String, String> tags,
                                             NeptuneClusterSnapshot restore) {
        if (getStoredCluster(id).isPresent()) {
            throw new AwsException("DBClusterAlreadyExistsFault",
                    "Neptune cluster " + id + " already exists.", 400);
        }

        // Open the try immediately after reserving the port so config reads below can't leak it.
        int proxyPort = allocateProxyPort(settings.port());
        NeptuneContainerHandle handle = null;
        boolean provisioned = false;
        try {
            String configuredDbType = config.services().neptune().dbType();
            NeptuneDbType dbType = NeptuneDbType.fromConfig(configuredDbType).orElseGet(() -> {
                LOG.warnv("Unsupported Neptune db-type ''{0}'', falling back to {1}. Supported: gremlin, neo4j.",
                        configuredDbType, NeptuneDbType.GREMLIN);
                return NeptuneDbType.GREMLIN;
            });
            String image = switch (dbType) {
                case GREMLIN -> config.services().neptune().defaultImage();
                case NEO4J -> config.services().neptune().defaultNeo4jImage();
            };

            LOG.infov("Creating Neptune cluster {0} on proxy port {1}, dbType={2}, image={3}",
                    id, String.valueOf(proxyPort), dbType, image);

            // A cluster record is metadata: its identifier, ARN, endpoint host and proxy port
            // are derived from configuration and need no Docker, so the cluster is created and
            // reaches 'available' even when no daemon is reachable. Only connecting to the
            String region = currentRegion();
            String accountId = currentAccount();
            String identity = resourceIdentity(accountId, region, id);
            // graph database needs the container.
            handle = config.services().neptune().mock() ? null
                    : containerManager.tryStart(id, image, dbType, accountId, region);
            if (restore != null && !restore.metadataOnly()) {
                if (handle == null || dbType != NeptuneDbType.GREMLIN) {
                    throw new AwsException("InvalidRestoreFault",
                            "A real Neptune snapshot requires a running Gremlin backend.", 400);
                }
                try {
                    containerManager.restoreGraph(handle, restore.archiveBase64());
                } catch (RuntimeException exception) {
                    throw new AwsException("InvalidRestoreFault",
                            "The Neptune snapshot archive could not be restored: " + exception.getMessage(), 400);
                }
            }
            String endpointHost = resolveEndpointHost();

            NeptuneCluster cluster = new NeptuneCluster();
            cluster.setDbClusterIdentifier(id);
            cluster.setStatus("available");
            cluster.setEngineVersion(engineVersion != null ? engineVersion : ENGINE_VERSION_DEFAULT);
            cluster.setEndpoint(endpointHost);
            cluster.setReaderEndpoint(endpointHost);
            cluster.setPort(proxyPort);
            cluster.setIamDatabaseAuthenticationEnabled(iamEnabled);
            cluster.setDbClusterArn(regionResolver.buildArn("neptune", region, "cluster:" + id));
            cluster.setDbClusterResourceId("cluster-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 24).toUpperCase());
            cluster.setCreatedAt(Instant.now());
            cluster.setDbClusterMembers(new ArrayList<>());
            cluster.setProxyPort(proxyPort);
            settings.applyTo(cluster);
            if (tags != null && !tags.isEmpty()) {
                cluster.setTags(tags);
            }

            if (handle != null) {
                cluster.setContainerId(handle.getContainerId());
                cluster.setContainerHost(handle.getHost());
                cluster.setContainerPort(handle.getPort());

                proxyManager.startProxy(identity, proxyPort, handle.getHost(), handle.getPort());
            } else {
                LOG.warnv("Neptune cluster {0} created without a backing graph database container: "
                        + "no Docker daemon is reachable. Metadata operations work; connections to "
                        + "the cluster do not until a daemon appears.", id);
            }

            if (restore == null) {
                putCluster(cluster);
            } else {
                putRestoredCluster(cluster);
            }
            provisioned = true;
            LOG.infov("Neptune cluster {0} created ({1}), endpoint={2}:{3}",
                    id, dbType, endpointHost, String.valueOf(proxyPort));
            return cluster;
        } catch (RuntimeException e) {
            LOG.warnv("Neptune cluster {0} provisioning failed, rolling back: {1}", id, e.getMessage());
            throw e;
        } finally {
            // Roll back on ANY non-success exit — including a JVM Error, which a
            // catch (RuntimeException) would miss — so a failed create never leaks the
            // reserved port or leaves a container behind. Idempotent and a no-op on success.
            if (!provisioned) {
                rollbackDbCluster(resourceIdentity(currentAccount(), currentRegion(), id), handle, proxyPort);
            }
        }
    }

    private void rollbackDbCluster(String identity, NeptuneContainerHandle handle, int proxyPort) {
        try {
            try {
                // The proxy only starts after the container is ready, so a null handle means it
                // never started — nothing to stop.
                if (handle != null) {
                    proxyManager.stopProxy(identity);
                }
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping proxy for Neptune cluster {0}: {1}", identity, e.getMessage());
            }
            try {
                // Stop by id, not handle: a readiness timeout in containerManager.start() throws
                // after the container was created and registered but before the handle is returned,
                // so cleaning up by handle here would miss (and orphan) it. stopByClusterId is
                // idempotent, so it's safe when the container never started.
                containerManager.stopByClusterId(identity);
            } catch (RuntimeException e) {
                LOG.warnv("Error stopping container for Neptune cluster {0}: {1}", identity, e.getMessage());
            }
        } finally {
            // Always release the port — even if a cleanup step throws a non-RuntimeException
            // (e.g. an Error) — since leaking the port is the exact failure this rollback prevents.
            releaseProxyPort(proxyPort);
        }
    }

    public NeptuneCluster getDbCluster(String id) {
        return getStoredCluster(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "Neptune cluster " + id + " not found.", 404));
    }

    public boolean hasCluster(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return getStoredCluster(id).isPresent();
    }

    public boolean hasInstance(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return getStoredInstance(id).isPresent();
    }

    public boolean hasResourceWithArn(String arn) {
        if (!AwsArnUtils.looksLikeArn(arn)) {
            return false;
        }
        return scanSnapshots().stream().anyMatch(snapshot -> arn.equalsIgnoreCase(snapshot.dbClusterSnapshotArn()))
                || scanClusters().stream()
                        .anyMatch(c -> arn.equalsIgnoreCase(c.getDbClusterArn()))
                || scanInstances().stream()
                        .anyMatch(i -> arn.equalsIgnoreCase(i.getDbInstanceArn()));
    }

    public Collection<NeptuneCluster> listDbClusters(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            // The db-cluster-id filter accepts ARNs as well as identifiers. Match the
            // full ARN against each cluster's stored ARN rather than reducing it to
            // the bare identifier, so a cross-account or cross-region ARN does not
            // resolve a same-named local cluster.
            if (AwsArnUtils.looksLikeArn(filterId)) {
                return scanClusters().stream()
                        .filter(c -> filterId.equalsIgnoreCase(c.getDbClusterArn()))
                        .toList();
            }
            return scanClusters().stream().filter(c -> filterId.equalsIgnoreCase(c.getDbClusterIdentifier())).toList();
        }
        return scanClusters();
    }

    public NeptuneCluster modifyDbCluster(String id, String engineVersion, Boolean iamEnabled) {
        return modifyDbCluster(id, engineVersion, iamEnabled, NeptuneClusterSettings.unchanged());
    }

    public NeptuneCluster modifyDbCluster(String id, String engineVersion, Boolean iamEnabled,
                                          NeptuneClusterSettings settings) {
        synchronized (clusterMutationLock(id)) {
            return modifyClusterLocked(id, engineVersion, iamEnabled, settings);
        }
    }

    private NeptuneCluster modifyClusterLocked(String id, String engineVersion, Boolean iamEnabled,
                                                NeptuneClusterSettings settings) {
        settings.validate();
        NeptuneCluster cluster = getDbCluster(id);
        if (engineVersion != null && !engineVersion.isBlank()) {
            cluster.setEngineVersion(engineVersion);
        }
        if (iamEnabled != null) {
            cluster.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        settings.applyTo(cluster);
        putCluster(cluster);
        LOG.infov("Neptune cluster {0} modified", id);
        return cluster;
    }

    public void deleteDbCluster(String id) {
        synchronized (clusterMutationLock(id)) {
            deleteClusterLocked(id);
        }
    }

    private void deleteClusterLocked(String id) {
        NeptuneCluster cluster = getStoredCluster(id).orElseThrow(() ->
                new AwsException("DBClusterNotFoundFault",
                        "Neptune cluster " + id + " not found.", 404));

        if (cluster.isDeletionProtection()) {
            throw new AwsException("InvalidParameterCombination",
                    "Cannot delete protected Cluster, please disable deletion protection and try again.", 400);
        }
        if (cluster.getDbClusterMembers() != null && !cluster.getDbClusterMembers().isEmpty()) {
            throw new AwsException("InvalidDBClusterStateFault",
                    "Cannot delete Neptune cluster " + id + " — it still has DB instances.", 400);
        }

        cluster.setStatus("deleting");
        putCluster(cluster);

        String identity = resourceIdentity(cluster);
        proxyManager.stopProxy(identity);

        if (cluster.getContainerId() != null) {
            containerManager.stop(new NeptuneContainerHandle(
                    cluster.getContainerId(), identity,
                    cluster.getContainerHost(), cluster.getContainerPort()));
        }

        releaseProxyPort(cluster.getProxyPort());
        deleteCluster(cluster);
        LOG.infov("Neptune cluster {0} deleted", id);
    }

    /** Explicit manual snapshots preserve a real GraphSON archive unless mock mode was requested. */
    public synchronized NeptuneClusterSnapshot createDbClusterSnapshot(String snapshotId, String clusterId,
                                                                        Map<String, String> tags) {
        String id = snapshotIdentifier(snapshotId);
        if (findSnapshot(id).isPresent()) {
            throw new AwsException("DBClusterSnapshotAlreadyExistsFault", "Snapshot " + id + " already exists.", 400);
        }
        NeptuneCluster source = snapshotSource(clusterId);
        synchronized (clusterMutationLock(source.getDbClusterIdentifier())) {
            source = snapshotSource(clusterId);
            if (!"available".equals(source.getStatus())) {
                throw new AwsException("InvalidDBClusterStateFault", "The source Neptune cluster is not available.", 400);
            }
            boolean mock = config.services().neptune().mock();
            String archive = null;
            if (!mock) {
                if (source.getContainerId() == null
                        || "neo4j".equalsIgnoreCase(config.services().neptune().dbType())) {
                    throw new AwsException("InvalidDBClusterStateFault",
                            "Neptune snapshots require a running Gremlin backend; Neo4j snapshots are unsupported.", 400);
                }
                try {
                    archive = containerManager.snapshotGraph(new NeptuneContainerHandle(source.getContainerId(),
                            resourceIdentity(source), source.getContainerHost(), source.getContainerPort()));
                } catch (RuntimeException exception) {
                    throw new AwsException("InvalidDBClusterStateFault",
                            "The Neptune graph could not be archived: " + exception.getMessage(), 400);
                }
            }
            Map<String, String> snapshotTags = new LinkedHashMap<>();
            if (source.isCopyTagsToSnapshot()) {
                snapshotTags.putAll(source.getTags());
            }
            if (tags != null) {
                snapshotTags.putAll(tags);
            }
            NeptuneClusterSnapshot snapshot = new NeptuneClusterSnapshot(id,
                    regionResolver.buildArn("rds", currentRegion(), "cluster-snapshot:" + id),
                    source.getDbClusterIdentifier(), source.getDbClusterArn(), source.getEngineVersion(),
                    source.isIamDatabaseAuthenticationEnabled(), source.getCreatedAt(), Instant.now(),
                    snapshotSettings(source, source.isDeletionProtection()), snapshotTags, mock, archive);
            putSnapshot(snapshot);
            return snapshot;
        }
    }

    public NeptuneClusterSnapshot getDbClusterSnapshot(String identifier) {
        return findSnapshot(identifier).orElseThrow(() -> new AwsException("DBClusterSnapshotNotFoundFault",
                "Neptune snapshot " + identifier + " not found.", 404));
    }

    public boolean hasClusterSnapshot(String identifier) {
        return identifier != null && !identifier.isBlank() && findSnapshot(identifier).isPresent();
    }

    public List<NeptuneClusterSnapshot> listDbClusterSnapshots(String identifier, String clusterIdentifier) {
        if (identifier != null && !identifier.isBlank()) {
            return List.of(getDbClusterSnapshot(identifier));
        }
        return scanSnapshots().stream()
                .filter(snapshot -> clusterIdentifier == null || clusterIdentifier.isBlank()
                        || clusterIdentifier.equalsIgnoreCase(snapshot.dbClusterIdentifier())
                        || clusterIdentifier.equalsIgnoreCase(snapshot.dbClusterArn()))
                .sorted(Comparator.comparing(NeptuneClusterSnapshot::dbClusterSnapshotIdentifier))
                .toList();
    }

    public synchronized NeptuneClusterSnapshot deleteDbClusterSnapshot(String identifier) {
        NeptuneClusterSnapshot snapshot = getDbClusterSnapshot(identifier);
        String key = clusterKey(currentRegion(), snapshot.dbClusterSnapshotIdentifier());
        if (snapshots instanceof AccountAwareStorageBackend<NeptuneClusterSnapshot> aware) {
            aware.deleteAllAtomicallyForAccounts(Map.of(currentAccount(), Set.of(key)));
        } else {
            snapshots.deleteAllAtomically(Set.of(key));
        }
        return snapshot;
    }

    public synchronized NeptuneCluster restoreDbClusterFromSnapshot(String targetIdentifier, String snapshotIdentifier,
                                                                     String engineVersion, Boolean iamEnabled,
                                                                     NeptuneClusterSettings overrides,
                                                                     Map<String, String> tags) {
        String id = snapshotIdentifier(targetIdentifier);
        NeptuneClusterSnapshot snapshot = getDbClusterSnapshot(snapshotIdentifier);
        boolean mock = config.services().neptune().mock();
        if (snapshot.metadataOnly() != mock) {
            throw new AwsException("InvalidRestoreFault",
                    "Metadata-only Neptune snapshots can only be restored in explicit mock mode.", 400);
        }
        if (!mock && (snapshot.archiveBase64() == null
                || !"gremlin".equalsIgnoreCase(config.services().neptune().dbType()))) {
            throw new AwsException("InvalidRestoreFault",
                    "A real Neptune snapshot requires its stored archive and a Gremlin backend.", 400);
        }
        NeptuneCluster settingsCarrier = new NeptuneCluster();
        snapshot.settings().applyTo(settingsCarrier);
        settingsCarrier.setPort(snapshot.settings().port());
        overrides.validate();
        overrides.applyTo(settingsCarrier);
        if (overrides.port() != null) {
            settingsCarrier.setPort(overrides.port());
        }
        NeptuneClusterSettings settings = snapshotSettings(settingsCarrier,
                Boolean.TRUE.equals(overrides.deletionProtection()));
        String identity = resourceIdentity(currentAccount(), currentRegion(), id);
        if (!provisioningClusterNames.add(identity)) {
            throw clusterAlreadyExists(id);
        }
        try {
            synchronized (clusterMutationLock(id)) {
                return provisionDbCluster(id, engineVersion != null ? engineVersion : snapshot.engineVersion(),
                        iamEnabled != null ? iamEnabled : snapshot.iamDatabaseAuthenticationEnabled(),
                        settings, tags, snapshot);
            }
        } finally {
            provisioningClusterNames.remove(identity);
        }
    }

    private static NeptuneClusterSettings snapshotSettings(NeptuneCluster source, boolean deletionProtection) {
        return new NeptuneClusterSettings(List.copyOf(source.getAvailabilityZones()), source.getPort(),
                source.getBackupRetentionPeriod(), source.getPreferredBackupWindow(),
                source.getPreferredMaintenanceWindow(), source.getDbSubnetGroupName(),
                source.getDbClusterParameterGroupName(), List.copyOf(source.getVpcSecurityGroupIds()),
                source.isStorageEncrypted(), source.getKmsKeyId(), source.getStorageType(), deletionProtection,
                source.isCopyTagsToSnapshot(), List.copyOf(source.getEnabledCloudwatchLogsExports()), null,
                null, source.getServerlessV2MinCapacity(), source.getServerlessV2MaxCapacity());
    }

    private NeptuneCluster snapshotSource(String identifier) {
        return scanClusters().stream().filter(cluster ->
                        identifier != null && (identifier.equalsIgnoreCase(cluster.getDbClusterIdentifier())
                                || identifier.equalsIgnoreCase(cluster.getDbClusterArn())))
                .findFirst().orElseThrow(() -> new AwsException("DBClusterNotFoundFault",
                        "Neptune cluster " + identifier + " not found.", 404));
    }

    private static String snapshotIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z][A-Za-z0-9-]{0,62}")
                || identifier.endsWith("-") || identifier.contains("--")) {
            throw new AwsException("InvalidParameterValue", "Invalid Neptune cluster or snapshot identifier.", 400);
        }
        return identifier.toLowerCase(Locale.ROOT);
    }

    private Optional<NeptuneClusterSnapshot> findSnapshot(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Optional.empty();
        }
        if (AwsArnUtils.looksLikeArn(identifier)) {
            return scanSnapshots().stream().filter(snapshot ->
                    identifier.equalsIgnoreCase(snapshot.dbClusterSnapshotArn())).findFirst();
        }
        String key = clusterKey(currentRegion(), identifier.toLowerCase(Locale.ROOT));
        if (snapshots instanceof AccountAwareStorageBackend<NeptuneClusterSnapshot> aware) {
            return aware.getForAccount(currentAccount(), key);
        }
        return snapshots.get(key);
    }

    private List<NeptuneClusterSnapshot> scanSnapshots() {
        String region = currentRegion();
        if (snapshots instanceof AccountAwareStorageBackend<NeptuneClusterSnapshot> aware) {
            return aware.scanForAccount(currentAccount(), key -> key.startsWith(region + "/"));
        }
        return snapshots.scan(key -> key.startsWith(region + "/"));
    }

    private void putSnapshot(NeptuneClusterSnapshot snapshot) {
        String key = clusterKey(currentRegion(), snapshot.dbClusterSnapshotIdentifier());
        if (snapshots instanceof AccountAwareStorageBackend<NeptuneClusterSnapshot> aware) {
            aware.putAllAtomicallyForAccount(currentAccount(), Map.of(key, snapshot));
        } else {
            snapshots.putAllAtomically(Map.of(key, snapshot));
        }
    }

    private Object clusterMutationLock(String identifier) {
        return clusterMutationLocks.lockFor(resourceIdentity(currentAccount(), currentRegion(),
                identifier.toLowerCase(Locale.ROOT)));
    }

    public NeptuneCluster addRoleToDbCluster(String id, String roleArn) {
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("InvalidParameterValue", "RoleArn is required.", 400);
        }
        NeptuneCluster cluster = getDbCluster(id);
        if (cluster.getAssociatedRoleArns().contains(roleArn)) {
            throw new AwsException("DBClusterRoleAlreadyExists",
                    "Role ARN " + roleArn + " is already associated with Neptune cluster " + id + ".", 400);
        }
        cluster.getAssociatedRoleArns().add(roleArn);
        putCluster(cluster);
        LOG.infov("Role {0} added to Neptune cluster {1}", roleArn, id);
        return cluster;
    }

    public NeptuneCluster removeRoleFromDbCluster(String id, String roleArn) {
        NeptuneCluster cluster = getDbCluster(id);
        if (roleArn == null || !cluster.getAssociatedRoleArns().remove(roleArn)) {
            throw new AwsException("DBClusterRoleNotFound",
                    "Role ARN " + roleArn + " is not associated with Neptune cluster " + id + ".", 404);
        }
        putCluster(cluster);
        LOG.infov("Role {0} removed from Neptune cluster {1}", roleArn, id);
        return cluster;
    }

    public static String parameterGroupFamily(String engineVersion) {
        String version = engineVersion == null || engineVersion.isBlank() ? ENGINE_VERSION_DEFAULT : engineVersion.trim();
        String[] parts = version.split("\\.");
        int major;
        int minor;
        try {
            major = Integer.parseInt(parts[0]);
            minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        } catch (NumberFormatException e) {
            return parameterGroupFamily(ENGINE_VERSION_DEFAULT);
        }
        if (major == 1 && minor < 2) {
            return "neptune1";
        }
        return "neptune" + major + "." + minor;
    }

    // ── Instances ─────────────────────────────────────────────────────────────

    public NeptuneInstance createDbInstance(String id, String dbClusterIdentifier,
                                            String dbInstanceClass, String engineVersion,
                                            boolean iamEnabled) {
        return createDbInstance(id, dbClusterIdentifier, dbInstanceClass, engineVersion, iamEnabled, Map.of());
    }

    public NeptuneInstance createDbInstance(String id, String dbClusterIdentifier,
                                            String dbInstanceClass, String engineVersion,
                                            boolean iamEnabled, Map<String, String> tags) {
        return createDbInstance(id, dbClusterIdentifier, dbInstanceClass, engineVersion, iamEnabled,
                NeptuneInstanceSettings.defaults(), tags);
    }

    public NeptuneInstance createDbInstance(String id, String dbClusterIdentifier,
                                            String dbInstanceClass, String engineVersion,
                                            boolean iamEnabled, NeptuneInstanceSettings settings,
                                            Map<String, String> tags) {
        settings.validate();
        if (getStoredInstance(id).isPresent()) {
            throw new AwsException("DBInstanceAlreadyExists",
                    "Neptune instance " + id + " already exists.", 400);
        }

        NeptuneCluster cluster = getDbCluster(dbClusterIdentifier);
        String region = currentRegion();

        NeptuneInstance instance = new NeptuneInstance();
        instance.setDbInstanceIdentifier(id);
        instance.setDbClusterIdentifier(dbClusterIdentifier);
        instance.setDbInstanceClass(dbInstanceClass != null ? dbInstanceClass : "db.r5.large");
        instance.setEngineVersion(engineVersion != null ? engineVersion : cluster.getEngineVersion());
        instance.setStatus("available");
        instance.setEndpoint(cluster.getEndpoint());
        instance.setPort(cluster.getPort());
        instance.setIamDatabaseAuthenticationEnabled(iamEnabled);
        instance.setDbInstanceArn(regionResolver.buildArn("neptune", region, "db:" + id));
        instance.setDbiResourceId("db-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 24).toUpperCase());
        instance.setCreatedAt(Instant.now());
        settings.applyTo(instance);
        if (tags != null && !tags.isEmpty()) {
            instance.setTags(tags);
        }

        cluster.getDbClusterMembers().add(id);
        putCluster(cluster);

        putInstance(instance);
        LOG.infov("Neptune instance {0} created in cluster {1}", id, dbClusterIdentifier);
        return instance;
    }

    public Optional<NeptuneCluster> findDbCluster(String id) {
        return id == null ? Optional.empty() : getStoredCluster(id);
    }

    public NeptuneInstance getDbInstance(String id) {
        return getStoredInstance(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "Neptune instance " + id + " not found.", 404));
    }

    public Collection<NeptuneInstance> listDbInstances(String filterId) {
        if (filterId != null && !filterId.isBlank()) {
            // The db-instance-id filter accepts ARNs as well as identifiers; see
            // listDbClusters for why the match is against the stored ARN.
            if (AwsArnUtils.looksLikeArn(filterId)) {
                return scanInstances().stream()
                        .filter(i -> filterId.equalsIgnoreCase(i.getDbInstanceArn()))
                        .toList();
            }
            return scanInstances().stream().filter(i -> filterId.equalsIgnoreCase(i.getDbInstanceIdentifier())).toList();
        }
        return scanInstances();
    }

    public NeptuneInstance modifyDbInstance(String id, String dbInstanceClass, Boolean iamEnabled) {
        return modifyDbInstance(id, dbInstanceClass, iamEnabled, NeptuneInstanceSettings.unchanged());
    }

    public NeptuneInstance modifyDbInstance(String id, String dbInstanceClass, Boolean iamEnabled,
                                            NeptuneInstanceSettings settings) {
        settings.validate();
        NeptuneInstance instance = getDbInstance(id);
        if (dbInstanceClass != null && !dbInstanceClass.isBlank()) {
            instance.setDbInstanceClass(dbInstanceClass);
        }
        if (iamEnabled != null) {
            instance.setIamDatabaseAuthenticationEnabled(iamEnabled);
        }
        settings.applyTo(instance);
        putInstance(instance);
        LOG.infov("Neptune instance {0} modified", id);
        return instance;
    }

    public void deleteDbInstance(String id) {
        NeptuneInstance instance = getStoredInstance(id).orElseThrow(() ->
                new AwsException("DBInstanceNotFound",
                        "Neptune instance " + id + " not found.", 404));

        String clusterId = instance.getDbClusterIdentifier();
        NeptuneCluster cluster = getStoredCluster(clusterId).orElse(null);
        if (cluster != null) {
            cluster.getDbClusterMembers().remove(id);
            putCluster(cluster);
        }

        deleteInstance(instance);
        LOG.infov("Neptune instance {0} deleted", id);
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    private record TagTarget(Map<String, String> tags, Consumer<Map<String, String>> save) {}

    public Map<String, String> listTagsForResource(String resourceName) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(resolveTagTarget(resourceName).tags()));
    }

    public synchronized void addTagsToResource(String resourceName, Map<String, String> tags) {
        updateTags(resourceName, current -> current.putAll(tags));
    }

    public synchronized void removeTagsFromResource(String resourceName, Collection<String> tagKeys) {
        updateTags(resourceName, current -> tagKeys.forEach(current::remove));
    }

    private void updateTags(String resourceName, Consumer<Map<String, String>> change) {
        TagTarget target = resolveTagTarget(resourceName);
        Map<String, String> updated = new LinkedHashMap<>(target.tags());
        change.accept(updated);
        target.save().accept(updated);
    }

    private TagTarget resolveTagTarget(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            throw new AwsException("InvalidParameterValue", "ResourceName is required.", 400);
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceName);
        } catch (IllegalArgumentException malformed) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
        }
        String resource = arn.resource();
        int separator = resource.indexOf(':');
        if (separator < 0) {
            throw new AwsException("InvalidParameterValue", "Invalid resource name: " + resourceName, 400);
        }
        String type = resource.substring(0, separator);
        String id = resource.substring(separator + 1);
        return switch (type) {
            case "cluster-snapshot" -> {
                NeptuneClusterSnapshot snapshot = getDbClusterSnapshot(resourceName);
                yield new TagTarget(snapshot.tags(), updated -> putSnapshot(snapshot.withTags(updated)));
            }
            case "cluster" -> {
                NeptuneCluster cluster = scanClusters().stream()
                        .filter(c -> resourceName.equalsIgnoreCase(c.getDbClusterArn()))
                        .findFirst()
                        .orElseThrow(() -> new AwsException("DBClusterNotFoundFault",
                                "Neptune cluster " + id + " not found.", 404));
                yield new TagTarget(cluster.getTags(), updated -> {
                    cluster.setTags(updated);
                    putCluster(cluster);
                });
            }
            case "db" -> {
                NeptuneInstance instance = scanInstances().stream()
                        .filter(i -> resourceName.equalsIgnoreCase(i.getDbInstanceArn()))
                        .findFirst()
                        .orElseThrow(() -> new AwsException("DBInstanceNotFound",
                                "Neptune instance " + id + " not found.", 404));
                yield new TagTarget(instance.getTags(), updated -> {
                    instance.setTags(updated);
                    putInstance(instance);
                });
            }
            default -> throw new AwsException("InvalidParameterValue",
                    "Tagging for resource type '" + type + "' is not supported by Neptune in Floci: " + resourceName, 400);
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String currentAccount() {
        String account = regionResolver.getAccountId();
        return account != null ? account : regionResolver.getDefaultAccountId();
    }

    private String currentRegion() {
        String region = regionResolver.getRegion();
        return region != null ? region : regionResolver.getDefaultRegion();
    }

    private String clusterKey(String region, String id) {
        return region + "/" + id;
    }

    private String instanceKey(String region, String id) {
        return region + "/" + id;
    }

    private Optional<NeptuneCluster> getStoredCluster(String id) {
        String account = currentAccount();
        String region = currentRegion();
        String key = clusterKey(region, id);
        if (clusters instanceof AccountAwareStorageBackend<NeptuneCluster> aware) {
            return aware.getForAccountMigratingLegacyKeys(account, key, List.of(id),
                    value -> belongsTo(value.getDbClusterArn(), account, region));
        }
        return clusters.get(key);
    }

    private Optional<NeptuneInstance> getStoredInstance(String id) {
        String account = currentAccount();
        String region = currentRegion();
        String key = instanceKey(region, id);
        if (instances instanceof AccountAwareStorageBackend<NeptuneInstance> aware) {
            return aware.getForAccountMigratingLegacyKeys(account, key, List.of(id),
                    value -> belongsTo(value.getDbInstanceArn(), account, region));
        }
        return instances.get(key);
    }

    private Collection<NeptuneCluster> scanClusters() {
        String account = currentAccount();
        String region = currentRegion();
        if (clusters instanceof AccountAwareStorageBackend<NeptuneCluster> aware) {
            migrateLegacyClusters(aware, account, region);
            return aware.scanForAccount(account, key -> key.startsWith(region + "/"));
        }
        return clusters.scan(key -> key.startsWith(region + "/"));
    }

    private Collection<NeptuneInstance> scanInstances() {
        String account = currentAccount();
        String region = currentRegion();
        if (instances instanceof AccountAwareStorageBackend<NeptuneInstance> aware) {
            migrateLegacyInstances(aware, account, region);
            return aware.scanForAccount(account, key -> key.startsWith(region + "/"));
        }
        return instances.scan(key -> key.startsWith(region + "/"));
    }

    private void migrateLegacyClusters(AccountAwareStorageBackend<NeptuneCluster> aware,
                                       String account, String region) {
        if (account.equals(regionResolver.getDefaultAccountId())) {
            for (NeptuneCluster cluster : aware.scanUnscopedLegacy(value -> belongsTo(
                    value.getDbClusterArn(), account, region))) {
                migrateLegacyCluster(aware, account, region, cluster);
            }
        }
        for (String legacyKey : aware.keysForAccount(account)) {
            if (legacyKey.contains("/")) {
                continue;
            }
            aware.getForAccount(account, legacyKey)
                    .filter(value -> belongsTo(value.getDbClusterArn(), account, region))
                    .ifPresent(value -> migrateLegacyCluster(aware, account, region, value));
        }
    }

    private void migrateLegacyInstances(AccountAwareStorageBackend<NeptuneInstance> aware,
                                        String account, String region) {
        if (account.equals(regionResolver.getDefaultAccountId())) {
            for (NeptuneInstance instance : aware.scanUnscopedLegacy(value -> belongsTo(
                    value.getDbInstanceArn(), account, region))) {
                migrateLegacyInstance(aware, account, region, instance);
            }
        }
        for (String legacyKey : aware.keysForAccount(account)) {
            if (legacyKey.contains("/")) {
                continue;
            }
            aware.getForAccount(account, legacyKey)
                    .filter(value -> belongsTo(value.getDbInstanceArn(), account, region))
                    .ifPresent(value -> migrateLegacyInstance(aware, account, region, value));
        }
    }

    private void migrateLegacyCluster(AccountAwareStorageBackend<NeptuneCluster> aware,
                                      String account, String region, NeptuneCluster cluster) {
        String key = clusterKey(region, cluster.getDbClusterIdentifier());
        aware.getForAccountMigratingLegacyKeys(account, key,
                List.of(cluster.getDbClusterIdentifier()),
                value -> belongsTo(value.getDbClusterArn(), account, region))
                .ifPresent(value -> aware.putForAccount(account, key, value));
    }

    private void migrateLegacyInstance(AccountAwareStorageBackend<NeptuneInstance> aware,
                                       String account, String region, NeptuneInstance instance) {
        String key = instanceKey(region, instance.getDbInstanceIdentifier());
        aware.getForAccountMigratingLegacyKeys(account, key,
                List.of(instance.getDbInstanceIdentifier()),
                value -> belongsTo(value.getDbInstanceArn(), account, region))
                .ifPresent(value -> aware.putForAccount(account, key, value));
    }

    private void putCluster(NeptuneCluster cluster) {
        String account = arnAccount(cluster.getDbClusterArn());
        String region = arnRegion(cluster.getDbClusterArn());
        String key = clusterKey(region, cluster.getDbClusterIdentifier());
        if (clusters instanceof AccountAwareStorageBackend<NeptuneCluster> aware) {
            aware.putForAccount(account, key, cluster);
        } else {
            clusters.put(key, cluster);
        }
    }

    private void putRestoredCluster(NeptuneCluster cluster) {
        String account = arnAccount(cluster.getDbClusterArn());
        String key = clusterKey(arnRegion(cluster.getDbClusterArn()), cluster.getDbClusterIdentifier());
        if (clusters instanceof AccountAwareStorageBackend<NeptuneCluster> aware) {
            aware.putAllAtomicallyForAccount(account, Map.of(key, cluster));
        } else {
            clusters.putAllAtomically(Map.of(key, cluster));
        }
    }

    private void deleteCluster(NeptuneCluster cluster) {
        String account = arnAccount(cluster.getDbClusterArn());
        String region = arnRegion(cluster.getDbClusterArn());
        String key = clusterKey(region, cluster.getDbClusterIdentifier());
        if (clusters instanceof AccountAwareStorageBackend<NeptuneCluster> aware) {
            aware.deleteForAccount(account, key);
        } else {
            clusters.delete(key);
        }
    }

    private void putInstance(NeptuneInstance instance) {
        String account = arnAccount(instance.getDbInstanceArn());
        String region = arnRegion(instance.getDbInstanceArn());
        String key = instanceKey(region, instance.getDbInstanceIdentifier());
        if (instances instanceof AccountAwareStorageBackend<NeptuneInstance> aware) {
            aware.putForAccount(account, key, instance);
        } else {
            instances.put(key, instance);
        }
    }

    private void deleteInstance(NeptuneInstance instance) {
        String account = arnAccount(instance.getDbInstanceArn());
        String region = arnRegion(instance.getDbInstanceArn());
        String key = instanceKey(region, instance.getDbInstanceIdentifier());
        if (instances instanceof AccountAwareStorageBackend<NeptuneInstance> aware) {
            aware.deleteForAccount(account, key);
        } else {
            instances.delete(key);
        }
    }

    private static boolean belongsTo(String arn, String account, String region) {
        return account.equals(arnAccount(arn)) && region.equals(arnRegion(arn));
    }

    private static String arnAccount(String arn) {
        try {
            return AwsArnUtils.parse(arn).accountId();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String arnRegion(String arn) {
        try {
            return AwsArnUtils.parse(arn).region();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String resourceIdentity(String account, String region, String id) {
        return account + "-" + region + "-" + id;
    }

    private static String resourceIdentity(NeptuneCluster cluster) {
        return resourceIdentity(arnAccount(cluster.getDbClusterArn()), arnRegion(cluster.getDbClusterArn()),
                cluster.getDbClusterIdentifier());
    }

    private String resolveEndpointHost() {
        return config.hostname().orElse("localhost");
    }

    private int allocateProxyPort(Integer requested) {
        int base = config.services().neptune().proxyBasePort();
        int max = config.services().neptune().proxyMaxPort();
        if (requested != null && requested >= base && requested <= max && usedPorts.add(requested)) {
            return requested;
        }
        if (requested != null) {
            LOG.infov("Requested Neptune port {0} is outside the proxy range {1}-{2} or already in use; "
                    + "allocating the next free proxy port instead",
                    String.valueOf(requested), String.valueOf(base), String.valueOf(max));
        }
        for (int port = base; port <= max; port++) {
            if (usedPorts.add(port)) {
                return port;
            }
        }
        // Wire code the SDK maps to InsufficientStorageClusterCapacityFault (the only
        // capacity fault CreateDBCluster declares); "InsufficientNeptuneCapacity" isn't a
        // real Neptune code, so callers got an unmapped generic NeptuneException.
        throw new AwsException("InsufficientStorageClusterCapacity",
                "No available proxy ports in range " + base + "-" + max, 400);
    }

    private void releaseProxyPort(int port) {
        usedPorts.remove(port);
    }
}
