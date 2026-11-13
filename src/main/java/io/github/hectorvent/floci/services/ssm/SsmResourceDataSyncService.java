package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.EpochSeconds;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.S3Service.BucketPolicyInfo;
import io.github.hectorvent.floci.services.s3.model.PutObjectOptions;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Stores source configuration metadata and exports destination inventory to S3. */
@ApplicationScoped
public class SsmResourceDataSyncService implements Resettable {
    private static final Logger LOG = Logger.getLogger(SsmResourceDataSyncService.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String DESTINATION = "SyncToDestination";
    private static final String SOURCE = "SyncFromSource";
    private static final String OWNER_ACL = "bucket-owner-full-control";
    private static final int MAX_SYNCS = 5;

    private final AccountAwareStorageBackend<ObjectNode> syncs;
    private final SsmInventoryService inventories;
    private final S3Service s3;
    private final IamPolicyEvaluator policies;
    private final RegionResolver regionResolver;
    private final EmulatorConfig config;
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private volatile ScheduledExecutorService worker;
    private volatile boolean closed;
    private volatile boolean resetting;

    @Inject
    public SsmResourceDataSyncService(StorageFactory storageFactory, SsmInventoryService inventories,
                                     S3Service s3, IamPolicyEvaluator policies, RegionResolver regionResolver,
                                     EmulatorConfig config, ObjectMapper objectMapper) {
        this.syncs = storageFactory.create("ssm", "ssm-resource-data-sync.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.inventories = inventories;
        this.s3 = s3;
        this.policies = policies;
        this.regionResolver = regionResolver;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    void onStart(@Observes @Priority(Interceptor.Priority.APPLICATION + 1000) StartupEvent ignored) {
        if (!config.services().ssm().enabled()) {
            return;
        }
        long interval = config.services().ssm().resourceDataSyncIntervalMs();
        if (interval < 1) {
            throw new IllegalArgumentException("SSM resource-data-sync-interval-ms must be positive.");
        }
        worker = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("ssm-resource-data-sync").factory());
        worker.scheduleWithFixedDelay(this::synchronizeAll, interval, interval, TimeUnit.MILLISECONDS);
    }

    void onStop(@Observes @Priority(Interceptor.Priority.APPLICATION) ShutdownEvent ignored) {
        shutdown();
    }

    public void create(JsonNode request, String region) {
        SsmResourceDataSyncRequests.check("CreateResourceDataSync", request);
        if (SOURCE.equals(type(request))) {
            createSource(request, region);
            return;
        }
        if (request.hasNonNull("SyncSource")) {
            throw invalid("Explorer SyncFromSource resource data syncs are not supported.");
        }
        if (!request.hasNonNull("S3Destination")) {
            throw invalid("S3Destination is required for SyncToDestination.");
        }
        ObjectNode destination = request.get("S3Destination").deepCopy();
        if (destination.hasNonNull("AWSKMSKeyARN")) {
            throw invalid("KMS-encrypted resource data syncs are not supported.");
        }
        if (destination.path("DestinationDataSharing").hasNonNull("DestinationDataSharingType")) {
            throw invalid("Organization-based destination data sharing is not supported.");
        }
        if (!AwsRegions.partitionFor(region).equals(
                AwsRegions.partitionFor(destination.get("Region").asText()))) {
            throw invalid("The destination bucket must belong to the source AWS partition.");
        }
        String name = request.get("SyncName").asText();
        lifecycle.writeLock().lock();
        try {
            if (closed || resetting) {
                throw new AwsException("InternalServerError", "Inventory synchronization is stopping or resetting.", 500);
            }
            if (syncs.get(key(region, name)).isPresent()) {
                throw new AwsException("ResourceDataSyncAlreadyExistsException",
                        "A resource data sync named " + name + " already exists.", 400);
            }
            if (syncs.scan(key -> key.startsWith(region + "::")).stream()
                    .filter(sync -> DESTINATION.equals(sync.path("SyncType").asText()))
                    .count() >= MAX_SYNCS) {
                throw new AwsException("ResourceDataSyncCountExceededException",
                        "An account can have at most " + MAX_SYNCS + " resource data syncs per Region.", 400);
            }
            double now = EpochSeconds.fromInstant(Instant.now());
            ObjectNode sync = JSON.objectNode().put("SyncName", name).put("SyncType", DESTINATION)
                    .put("Region", region).put("Generation", UUID.randomUUID().toString())
                    .put("SyncCreatedTime", now).put("SyncLastModifiedTime", now).put("LastStatus", "InProgress");
            sync.set("S3Destination", destination);
            sync.putObject("Acknowledged");
            syncs.put(key(region, name), sync);
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private void createSource(JsonNode request, String region) {
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
                    request.path("MaxResults").asInt(50), optionalText(request, "NextToken"), scope,
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
            if (SOURCE.equals(type)) {
                requireAdmission();
            }
            if (syncs.get(key).filter(sync -> type.equals(sync.path("SyncType").asText())).isEmpty()) {
                throw notFound();
            }
            if (SOURCE.equals(type)) {
                syncs.deleteAllAtomically(Set.of(key));
            } else {
                syncs.delete(key);
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    void synchronizeAll() {
        if (closed || resetting) {
            return;
        }
        try {
            for (AccountEntry<ObjectNode> entry : syncs.scanAllAccountEntries(key -> true)) {
                if (closed || resetting) {
                    return;
                }
                if (DESTINATION.equals(entry.value().path("SyncType").asText())) {
                    synchronize(entry.accountId(), entry.key());
                }
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Inventory synchronization cycle failed; the next cycle will retry");
        }
    }

    private void synchronize(String account, String key) {
        lifecycle.readLock().lock();
        try {
            if (closed || resetting) {
                return;
            }
            ObjectNode sync = syncs.getForAccount(account, key).map(ObjectNode::deepCopy).orElse(null);
            if (sync == null || !DESTINATION.equals(sync.path("SyncType").asText())) {
                return;
            }
            String region = sync.get("Region").asText();
            ObjectNode acknowledged = sync.withObject("/Acknowledged");
            List<ObjectNode> pending = inventories.inventories(account, region).stream()
                    .filter(inventory -> !inventory.path("Revision").asText()
                            .equals(acknowledged.path(inventory.path("InstanceId").asText()).asText())).toList();
            if (pending.isEmpty() && "Successful".equals(sync.path("LastStatus").asText())) {
                return;
            }
            sync.put("LastStatus", "InProgress").put("LastSyncTime", EpochSeconds.fromInstant(Instant.now()));
            syncs.putForAccount(account, key, sync.deepCopy());
            try {
                JsonNode destination = sync.get("S3Destination");
                RequestScopes.runAs(account, destination.get("Region").asText(), () -> {
                    verifyDestination(sync, account);
                    for (ObjectNode inventory : pending) {
                        if (closed || resetting) {
                            return;
                        }
                        writeInventory(sync, inventory, account);
                        acknowledged.put(inventory.get("InstanceId").asText(), inventory.get("Revision").asText());
                    }
                });
                if (closed || resetting) {
                    return;
                }
                sync.put("LastStatus", "Successful")
                        .put("LastSuccessfulSyncTime", EpochSeconds.fromInstant(Instant.now()))
                        .put("LastSyncStatusMessage", "The sync was successfully completed.");
            } catch (RuntimeException e) {
                String message = e instanceof AwsException aws ? aws.getErrorCode() + ": " + aws.getMessage()
                        : "Inventory delivery failed: " + e.getMessage();
                sync.put("LastStatus", "Failed")
                        .put("LastSyncStatusMessage", message.substring(0, Math.min(message.length(), 2048)));
                LOG.debugv(e, "Inventory sync {0} failed; the next cycle will retry", sync.get("SyncName").asText());
            }
            syncs.putForAccount(account, key, sync.deepCopy());
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    private void verifyDestination(ObjectNode sync, String account) {
        if (!config.services().s3().enabled()) {
            throw invalid("The S3 service is disabled.");
        }
        JsonNode destination = sync.get("S3Destination");
        String bucket = destination.get("BucketName").asText();
        String region = destination.get("Region").asText();
        if (!region.equals(s3.getBucketRegion(bucket))) {
            throw invalid("The destination bucket does not belong to the specified Region.");
        }
        authorize(sync, account, "s3:GetBucketAcl", bucket);
        String owner = s3.getBucketOwnerAccountId(bucket);
        try {
            RequestScopes.callAs(owner, region, () -> s3.getObjectLockConfiguration(bucket));
        } catch (AwsException e) {
            if ("ObjectLockConfigurationNotFoundError".equals(e.getErrorCode())) {
                return;
            }
            throw e;
        }
        throw invalid("Inventory resource data sync does not support buckets with S3 Object Lock.");
    }

    private void writeInventory(ObjectNode sync, ObjectNode inventory, String account) {
        JsonNode destination = sync.get("S3Destination");
        String bucket = destination.get("BucketName").asText();
        String prefix = destination.path("Prefix").asText("");
        if (!prefix.isEmpty() && !prefix.endsWith("/")) {
            prefix += "/";
        }
        String instance = inventory.get("InstanceId").asText();
        for (Map.Entry<String, JsonNode> item : inventory.path("Items").properties()) {
            String objectKey = prefix + item.getKey() + "/accountid=" + account + "/region="
                    + sync.get("Region").asText() + "/resourcetype=ManagedInstanceInventory/" + instance + ".json";
            authorize(sync, account, "s3:PutObject", bucket + "/" + objectKey);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (JsonNode entry : item.getValue().path("Content")) {
                ObjectNode row = entry.deepCopy();
                row.put("resourceId", instance);
                row.set("captureTime", item.getValue().get("CaptureTime"));
                row.set("schemaVersion", item.getValue().get("SchemaVersion"));
                try {
                    body.writeBytes(objectMapper.writeValueAsBytes(row));
                    body.write('\n');
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("Inventory record could not be encoded as JSON.", e);
                }
            }
            s3.putObject(bucket, objectKey, body.toByteArray(), "application/json", Map.of(),
                    new PutObjectOptions().withAcl(OWNER_ACL));
        }
    }

    private void authorize(ObjectNode sync, String account, String action, String resource) {
        if (!config.services().s3().enforceAuth() && !config.services().iam().enforcementEnabled()) {
            return;
        }
        JsonNode destination = sync.get("S3Destination");
        BucketPolicyInfo policy = s3.findBucketPolicyInfo(destination.get("BucketName").asText())
                .orElseThrow(() -> new AwsException("NoSuchBucket", "The destination bucket does not exist.", 404));
        String sourceArn = AwsArnUtils.Arn.of("ssm", sync.get("Region").asText(), account,
                "resource-data-sync/" + sync.get("SyncName").asText()).toString();
        String resourceArn = AwsArnUtils.Arn.global(AwsRegions.partitionFor(destination.get("Region").asText()),
                "s3", "", resource).toString();
        // Managed service requests use SigV4; their network authorization context is redacted.
        Map<String, List<String>> conditions = Map.of(
                "aws:SourceAccount", List.of(account),
                "aws:SourceArn", List.of(sourceArn),
                "aws:ResourceAccount", List.of(policy.ownerAccountId()),
                "s3:ResourceAccount", List.of(policy.ownerAccountId()),
                "aws:PrincipalIsAWSService", List.of("true"),
                "s3:signatureversion", List.of("AWS4-HMAC-SHA256"),
                "s3:x-amz-acl", List.of(OWNER_ACL));
        if (policies.evaluateServicePrincipal(policy.policy() == null ? List.of() : List.of(policy.policy()),
                ServicePrincipals.of("ssm"), action, resourceArn, conditions) != Decision.ALLOW) {
            throw new AwsException("AccessDenied", "The bucket policy denies " + action + " for " + resourceArn, 403);
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

    void shutdown() {
        closed = true;
        ScheduledExecutorService current = worker;
        if (current != null) {
            current.shutdownNow();
        }
        lifecycle.writeLock().lock();
        try {
            worker = null;
        } finally {
            lifecycle.writeLock().unlock();
        }
        if (current != null) {
            try {
                if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The inventory sync worker did not stop before storage shutdown.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping inventory synchronization.", e);
            }
        }
    }

    private void requireAdmission() {
        if (closed || resetting) {
            throw new AwsException("InternalServerError", "Resource data syncs are stopping or resetting.", 500);
        }
    }

    private static AwsException notFound() {
        return new AwsException("ResourceDataSyncNotFoundException",
                "The specified resource data sync does not exist.", 400);
    }

    private static String type(JsonNode request) {
        String value = request.path("SyncType").asText(DESTINATION);
        if (!DESTINATION.equals(value) && !SOURCE.equals(value)) {
            throw invalid("SyncType must be SyncToDestination or SyncFromSource.");
        }
        return value;
    }

    private static String key(String region, String name) {
        return region + "::" + name;
    }

    private static String optionalText(JsonNode request, String name) {
        return request.hasNonNull(name) ? request.get(name).asText() : null;
    }

    private static AwsException invalid(String message) {
        return new AwsException("ResourceDataSyncInvalidConfigurationException", message, 400);
    }
}
