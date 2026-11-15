package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.ReplicaAutoScalingRequest.IndexUpdate;
import io.github.hectorvent.floci.services.dynamodb.ReplicaAutoScalingRequest.ReplicaUpdate;
import io.github.hectorvent.floci.services.dynamodb.model.GlobalSecondaryIndex;
import io.github.hectorvent.floci.services.dynamodb.model.ReplicaAutoScalingState;
import io.github.hectorvent.floci.services.dynamodb.model.ReplicaAutoScalingState.Policy;
import io.github.hectorvent.floci.services.dynamodb.model.ReplicaAutoScalingState.Replica;
import io.github.hectorvent.floci.services.dynamodb.model.ReplicaAutoScalingState.Settings;
import io.github.hectorvent.floci.services.dynamodb.model.ReplicaAutoScalingState.TargetTracking;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.hectorvent.floci.services.dynamodb.ReplicaAutoScalingRequest.validation;

/** Stored settings for the native backend's existing logical replica model. */
@ApplicationScoped
public class DynamoDbReplicaAutoScalingService {
    record Description(String tableName, String tableStatus, List<ReplicaDescription> replicas) {}

    record ReplicaDescription(String region, String status, Settings read, Settings write,
                              List<IndexDescription> indexes) {}

    record IndexDescription(String name, String status, Settings read, Settings write) {}

    private final DynamoDbService tables;

    @Inject
    public DynamoDbReplicaAutoScalingService(DynamoDbService tables) {
        this.tables = tables;
    }

    Description describe(String name, String region) {
        synchronized (tables.tableControlLock(name, region)) {
            TableDefinition table = resolve(name, region, false);
            return describe(table, state(table), region);
        }
    }

    Description update(ReplicaAutoScalingRequest request, String region) {
        synchronized (tables.tableControlLock(request.tableName(), region)) {
            TableDefinition table = resolve(request.tableName(), region, true);
            ReplicaAutoScalingState previous = state(table);
            Set<String> regions = regions(table, region);
            Settings write = merge(previous.write(), request.write());
            Map<String, Settings> indexWrites = new LinkedHashMap<>(previous.indexWrites());
            applyIndexes(table, indexWrites, request.indexWrites());
            Map<String, Replica> replicas = new LinkedHashMap<>(previous.replicas());
            Set<String> updatedRegions = new HashSet<>();
            for (ReplicaUpdate update : request.replicas()) {
                if (!updatedRegions.add(update.region())) {
                    throw validation("ReplicaUpdates must not repeat a region");
                }
                if (!regions.contains(update.region())) {
                    throw new AwsException("ResourceNotFoundException", "Replica not found: " + update.region(), 400);
                }
                Replica oldReplica = replicas.getOrDefault(update.region(), new Replica(null, Map.of()));
                Map<String, Settings> indexReads = new LinkedHashMap<>(oldReplica.indexReads());
                applyIndexes(table, indexReads, update.indexReads());
                replicas.put(update.region(), new Replica(merge(oldReplica.read(), update.read()), indexReads));
            }
            ReplicaAutoScalingState changed = new ReplicaAutoScalingState(
                    table.getTableId(), write, indexWrites, replicas);
            if (!changed.equals(previous)) {
                tables.persistReplicaAutoScalingState(table, changed);
            }
            return describe(table, changed, region);
        }
    }

    private TableDefinition resolve(String name, String region, boolean update) {
        TableDefinition table = tables.describeTable(name, region);
        if (AwsArnUtils.looksLikeArn(name) && !name.equals(table.getTableArn())) {
            throw new AwsException("ResourceNotFoundException", "Requested table not found: " + name, 400);
        }
        if (!"ACTIVE".equals(table.getTableStatus())) {
            throw new AwsException(update ? "ResourceInUseException" : "ResourceNotFoundException",
                    "Table is not ACTIVE: " + table.getTableName(), 400);
        }
        return table;
    }

    private ReplicaAutoScalingState state(TableDefinition table) {
        return tables.replicaAutoScalingState(table)
                .orElseGet(() -> new ReplicaAutoScalingState(table.getTableId(), null, Map.of(), Map.of()));
    }

    private static void applyIndexes(TableDefinition table, Map<String, Settings> settings, List<IndexUpdate> updates) {
        Set<String> updatedNames = new HashSet<>();
        for (IndexUpdate update : updates) {
            if (!updatedNames.add(update.name())) {
                throw validation("Auto scaling index updates must not repeat an index");
            }
            if (table.findGsi(update.name()).isEmpty()) {
                throw new AwsException("ResourceNotFoundException", "Index not found: " + update.name(), 400);
            }
            Settings changed = merge(settings.get(update.name()), update.settings());
            if (changed != null) {
                settings.put(update.name(), changed);
            }
        }
    }

    private static Settings merge(Settings previous, Settings update) {
        if (update == null) {
            return previous;
        }
        Settings original = previous == null ? new Settings(null, null, null, null, null) : previous;
        Settings result = new Settings(
                supplied(update.minimumUnits(), original.minimumUnits()),
                supplied(update.maximumUnits(), original.maximumUnits()),
                supplied(update.disabled(), original.disabled()),
                supplied(update.roleArn(), original.roleArn()),
                merge(original.policy(), update.policy()));
        if (result.minimumUnits() != null && result.maximumUnits() != null
                && result.minimumUnits() > result.maximumUnits()) {
            throw validation("MinimumUnits must be less than or equal to MaximumUnits");
        }
        return result.equals(new Settings(null, null, null, null, null)) ? previous : result;
    }

    private static Policy merge(Policy previous, Policy update) {
        if (update == null) {
            return previous;
        }
        if (previous == null) {
            return update;
        }
        TargetTracking oldTarget = previous.targetTracking();
        TargetTracking target = update.targetTracking();
        return new Policy(supplied(update.name(), previous.name()), new TargetTracking(
                supplied(target.disableScaleIn(), oldTarget.disableScaleIn()),
                supplied(target.scaleInCooldown(), oldTarget.scaleInCooldown()),
                supplied(target.scaleOutCooldown(), oldTarget.scaleOutCooldown()),
                supplied(target.targetValue(), oldTarget.targetValue())));
    }

    private static <T> T supplied(T update, T previous) {
        return update != null ? update : previous;
    }

    private static Set<String> regions(TableDefinition table, String region) {
        Set<String> result = new LinkedHashSet<>();
        result.add(table.getGlobalTableHomeRegion() != null ? table.getGlobalTableHomeRegion() : region);
        result.addAll(table.getReplicaRegions());
        return result;
    }

    private static Description describe(TableDefinition table, ReplicaAutoScalingState state, String region) {
        List<ReplicaDescription> replicas = new ArrayList<>();
        for (String replicaRegion : regions(table, region)) {
            Replica replica = state.replicas().getOrDefault(replicaRegion, new Replica(null, Map.of()));
            List<IndexDescription> indexes = new ArrayList<>();
            for (GlobalSecondaryIndex index : table.getGlobalSecondaryIndexes()) {
                indexes.add(new IndexDescription(index.getIndexName(), "ACTIVE",
                        replica.indexReads().get(index.getIndexName()), state.indexWrites().get(index.getIndexName())));
            }
            replicas.add(new ReplicaDescription(replicaRegion, "ACTIVE", replica.read(), state.write(),
                    List.copyOf(indexes)));
        }
        return new Description(table.getTableName(), table.getTableStatus(), List.copyOf(replicas));
    }
}
