package io.floci.aws.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Digests;
import io.floci.aws.core.common.EpochSeconds;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Stores source configuration metadata without running OpsData aggregation or an exporter. */
@ApplicationScoped
public class SsmResourceDataSyncService implements Resettable {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String SOURCE = "SyncFromSource";
    private static final String DESTINATION = "SyncToDestination";
    private static final int MAX_SYNCS = 5;

    private final AccountAwareStorageBackend<ObjectNode> syncs;
    private final RegionResolver regionResolver;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private volatile boolean closed;
    private boolean resetting;

    @Inject
    public SsmResourceDataSyncService(StorageFactory storageFactory, RegionResolver regionResolver) {
        syncs = storageFactory.create("ssm", "ssm-resource-data-sync.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
    }

    public void create(JsonNode request, String region) {
        SsmResourceDataSyncRequests.check("CreateResourceDataSync", request);
        if (!SOURCE.equals(type(request))) {
            throw SsmSourceSyncConfiguration.invalid("SyncToDestination delivery is not supported.");
        }
        ObjectNode source = SsmSourceSyncConfiguration.read(request, false);
        String name = request.get("SyncName").asText();
        lifecycle.writeLock().lock();
        try {
            requireAdmission();
            if (syncs.get(key(region, name)).isPresent()) {
                throw new AwsException("ResourceDataSyncAlreadyExistsException",
                        "A resource data sync named " + name + " already exists.", 400);
            }
            if (syncs.scan(key -> key.startsWith(region + "::")).stream()
                    .filter(sync -> SOURCE.equals(sync.path("SyncType").asText())).count() >= MAX_SYNCS) {
                throw new AwsException("ResourceDataSyncCountExceededException",
                        "An account can have at most " + MAX_SYNCS + " source resource data syncs per Region.", 400);
            }
            double now = EpochSeconds.fromInstant(Instant.now());
            ObjectNode sync = JSON.objectNode().put("SyncName", name).put("SyncType", SOURCE)
                    .put("Region", region).put("Generation", UUID.randomUUID().toString())
                    .put("SyncCreatedTime", now).put("SyncLastModifiedTime", now);
            sync.set("SyncSource", source);
            syncs.putAllAtomically(Map.of(key(region, name), sync));
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    public void update(JsonNode request, String region) {
        SsmResourceDataSyncRequests.check("UpdateResourceDataSync", request);
        if (!SOURCE.equals(type(request))) {
            throw SsmSourceSyncConfiguration.invalid("Only SyncFromSource configurations can be updated.");
        }
        ObjectNode source = SsmSourceSyncConfiguration.read(request, true);
        String key = key(region, request.get("SyncName").asText());
        lifecycle.writeLock().lock();
        try {
            requireAdmission();
            ObjectNode stored = syncs.get(key).filter(sync -> SOURCE.equals(sync.path("SyncType").asText()))
                    .orElseThrow(SsmResourceDataSyncService::notFound);
            if (!stored.path("SyncSource").path("SourceType").equals(source.path("SourceType"))
                    || stored.path("SyncSource").hasNonNull("AwsOrganizationsSource")) {
                throw SsmSourceSyncConfiguration.invalid("The resource data sync account options cannot change.");
            }
            ObjectNode replacement = stored.deepCopy();
            replacement.set("SyncSource", source);
            replacement.put("SyncLastModifiedTime", EpochSeconds.fromInstant(Instant.now()));
            syncs.putAllAtomically(Map.of(key, replacement));
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    public ObjectNode list(JsonNode request, String region) {
        SsmResourceDataSyncRequests.check("ListResourceDataSync", request);
        String type = type(request);
        lifecycle.readLock().lock();
        try {
            List<ObjectNode> entries = syncs.scan(key -> key.startsWith(region + "::")).stream()
                    .filter(sync -> type.equals(sync.path("SyncType").asText())).map(ObjectNode::deepCopy).toList();
            String membership = entries.stream().map(sync -> sync.path("Generation").asText())
                    .sorted().toList().toString();
            String scope = "ssm-data-sync:" + regionResolver.getAccountId() + ":" + region + ":" + type
                    + ":" + Digests.sha256Hex(membership);
            PaginatedResult<ObjectNode> page = Pagination.paginate(entries, sync -> sync.get("SyncName").asText(),
                    request.path("MaxResults").asInt(50), request.path("NextToken").asText(null), scope,
                    token -> new AwsException("InvalidNextToken", "The resource data sync token is invalid.", 400));
            ObjectNode result = JSON.objectNode();
            ArrayNode items = result.putArray("ResourceDataSyncItems");
            for (ObjectNode sync : page.items()) {
                sync.remove(List.of("Region", "Generation", "Acknowledged"));
                items.add(sync);
            }
            if (page.nextToken() != null) {
                result.put("NextToken", page.nextToken());
            }
            return result;
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    public void delete(JsonNode request, String region) {
        SsmResourceDataSyncRequests.check("DeleteResourceDataSync", request);
        String type = type(request);
        String key = key(region, request.get("SyncName").asText());
        lifecycle.writeLock().lock();
        try {
            requireAdmission();
            if (!SOURCE.equals(type) || syncs.get(key)
                    .filter(sync -> type.equals(sync.path("SyncType").asText())).isEmpty()) {
                throw notFound();
            }
            syncs.deleteAllAtomically(Set.of(key));
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    @Override
    public void beforeReset() {
        lifecycle.writeLock().lock();
        try {
            resetting = true;
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    @Override
    public void afterReset() {
        lifecycle.writeLock().lock();
        try {
            resetting = false;
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    @Override
    public void clear() {
        lifecycle.writeLock().lock();
        try {
            syncs.clear();
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    void onStop(@Observes @Priority(Interceptor.Priority.APPLICATION) ShutdownEvent ignored) {
        shutdown();
    }

    void shutdown() {
        closed = true;
        lifecycle.writeLock().lock();
        try {
            // Drain an already admitted storage publication before storage shutdown.
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private void requireAdmission() {
        if (closed || resetting) {
            throw new AwsException("InternalServerError", "Resource data syncs are stopping or resetting.", 500);
        }
    }

    private static String type(JsonNode request) {
        String value = request.path("SyncType").asText(DESTINATION);
        if (!SOURCE.equals(value) && !DESTINATION.equals(value)) {
            throw SsmSourceSyncConfiguration.invalid("SyncType must be SyncToDestination or SyncFromSource.");
        }
        return value;
    }

    private static String key(String region, String name) {
        return region + "::" + name;
    }

    private static AwsException notFound() {
        return new AwsException("ResourceDataSyncNotFoundException",
                "The specified resource data sync does not exist.", 400);
    }
}
