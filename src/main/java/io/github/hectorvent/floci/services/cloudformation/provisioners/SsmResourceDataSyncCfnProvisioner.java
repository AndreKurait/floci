package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ssm.SsmResourceDataSyncService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/** Owns inventory sync configuration and its asynchronous S3 destination admission. */
@ApplicationScoped
public class SsmResourceDataSyncCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::SSM::ResourceDataSync";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> DESTINATION_FIELDS = Map.of(
            "BucketName", "BucketName", "BucketRegion", "Region", "BucketPrefix", "Prefix",
            "SyncFormat", "SyncFormat", "KMSKeyArn", "AWSKMSKeyARN");
    private static final Set<String> PROPERTIES = Set.of(
            "SyncName", "SyncType", "SyncSource", "S3Destination",
            "BucketName", "BucketRegion", "BucketPrefix", "SyncFormat", "KMSKeyArn");
    private static final CreateOnlyInputs INPUTS =
            new CreateOnlyInputs("__FlociSsmDataSyncInputs", "__FlociSsmDataSyncPriorInputs");

    private final SsmResourceDataSyncService syncs;
    private final LongConsumer pause;

    @Inject
    public SsmResourceDataSyncCfnProvisioner(SsmResourceDataSyncService syncs) {
        this(syncs, SsmResourceDataSyncCfnProvisioner::pause);
    }

    SsmResourceDataSyncCfnProvisioner(SsmResourceDataSyncService syncs, LongConsumer pause) {
        this.syncs = syncs;
        this.pause = pause;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(resource);
        }
        CfnPropertyTypes.Reader reader = new CfnPropertyTypes.Reader(ctx.engine());
        JsonNode props = reader.read("", properties == null || properties.isNull()
                ? JSON.createObjectNode() : properties, TYPE);
        reader.finish();
        ObjectNode request = request(props);
        String name = request.path("SyncName").asText();
        Map<String, String> before = new HashMap<>(resource.getAttributes());
        if (ctx.reusesPriorEntity(name) && !CfnResourceDispatcher.isStub(name, before)) {
            if (!props.equals(previousInputs(before))) {
                throw CfnExceptions.customNamedReplacement(name);
            }
            if (find(name, ctx.region()) == null) {
                throw new AwsException("ResourceDataSyncNotFoundException",
                        "The resource data sync " + name + " no longer exists.", 400);
            }
        } else {
            syncs.create(request, ctx.region());
            resource.setPhysicalId(name);
            CfnRollback.guarded(resource, ctx, true, () -> delete(TYPE, name, ctx.region()),
                    () -> { }, () -> stabilize(name, ctx.region()));
            INPUTS.record(resource, ctx, before, props.toString());
        }
        resource.setPhysicalId(name);
        ReplacementCleanup.record(resource, ctx, before);
    }

    private static ObjectNode request(JsonNode props) {
        requireKnownProperties(props, PROPERTIES);
        if (props.hasNonNull("SyncSource") || "SyncFromSource".equals(props.path("SyncType").asText())) {
            throw new AwsException("InvalidRequest", "Explorer SyncFromSource resource data syncs are not supported.", 400);
        }
        boolean legacy = DESTINATION_FIELDS.keySet().stream().anyMatch(props::hasNonNull);
        if (legacy && props.hasNonNull("S3Destination")) {
            throw new AwsException("InvalidRequest", "Two configurations for S3Destination are found.", 400);
        }
        ObjectNode request = JSON.createObjectNode();
        for (String field : List.of("SyncName", "SyncType")) {
            if (props.hasNonNull(field)) {
                request.set(field, props.get(field));
            }
        }
        JsonNode destination = legacy ? props : props.get("S3Destination");
        if (destination != null && !destination.isNull()) {
            if (!legacy) {
                requireKnownProperties(destination, DESTINATION_FIELDS.keySet());
            }
            ObjectNode mapped = request.putObject("S3Destination");
            DESTINATION_FIELDS.forEach((field, apiField) -> {
                if (destination.hasNonNull(field)) {
                    mapped.set(apiField, destination.get(field));
                }
            });
        }
        return request;
    }

    private static void requireKnownProperties(JsonNode properties, Set<String> allowed) {
        properties.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw new AwsException("InvalidRequest", TYPE + " does not support property " + name + ".", 400);
            }
        });
    }

    private static JsonNode previousInputs(Map<String, String> attributes) {
        String inputs = INPUTS.read(attributes);
        if (inputs == null) {
            return null;
        }
        try {
            return JSON.readTree(inputs);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Cannot read the resource data sync's original inputs.", error);
        }
    }

    private JsonNode find(String name, String region) {
        ObjectNode result = syncs.list(JSON.createObjectNode().put("SyncType", "SyncToDestination")
                .put("MaxResults", 50), region);
        for (JsonNode item : result.path("ResourceDataSyncItems")) {
            if (name.equals(item.path("SyncName").asText())) {
                return item;
            }
        }
        return null;
    }

    private void stabilize(String name, String region) {
        // AWS's provider starts with a two-second callback, then permits six ten-second retries.
        pause.accept(2);
        for (int attempt = 0; attempt < 6; attempt++) {
            JsonNode sync = find(name, region);
            if (sync != null) {
                String status = sync.path("LastStatus").asText();
                if ("Successful".equals(status) || "InProgress".equals(status)) {
                    return;
                }
                if ("Failed".equals(status)) {
                    throw notStabilized(name, sync.path("LastSyncStatusMessage").asText("The sync failed."));
                }
            }
            pause.accept(10);
        }
        throw notStabilized(name, "The sync did not become visible with a supported status.");
    }

    private static AwsException notStabilized(String name, String reason) {
        return new AwsException("NotStabilized", TYPE + " " + name + " did not stabilize: " + reason, 400);
    }

    private static void pause(long seconds) {
        try {
            TimeUnit.SECONDS.sleep(seconds);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for resource data sync admission.", error);
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("SSM resource data sync", physicalId, () -> syncs.delete(
                JSON.createObjectNode().put("SyncName", physicalId).put("SyncType", "SyncToDestination"), region),
                "ResourceDataSyncNotFoundException");
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        INPUTS.clearPrior(resource);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        INPUTS.clearPrior(resource);
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String replacement = resource.getPhysicalId();
        boolean hasPrior = INPUTS.hasPrior(resource);
        try {
            ReplacementCleanup.rollback(resource, this::delete);
        } finally {
            if (hasPrior && !replacement.equals(resource.getPhysicalId())) {
                INPUTS.restorePrior(resource);
            }
        }
        return true;
    }
}
