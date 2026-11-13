package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import io.github.hectorvent.floci.services.stepfunctions.model.Activity;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Provisions activities through the same store and worker callbacks as the Step Functions API. */
@ApplicationScoped
public class StepFunctionsActivityCfnProvisioner implements CfnResourceProvisioner {
    private static final String TYPE = "AWS::StepFunctions::Activity";
    private static final String TAG_SNAPSHOT = "__FlociStepFunctionsActivityTags";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StepFunctionsService stepFunctions;

    public StepFunctionsActivityCfnProvisioner(StepFunctionsService stepFunctions) {
        this.stepFunctions = stepFunctions;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new AwsException("ValidationError", TYPE + " requires Name with 1 to 80 characters", 400);
        }
        if (props != null && props.hasNonNull("EncryptionConfiguration")) {
            throw new AwsException("ValidationError",
                    TYPE + " EncryptionConfiguration is not supported", 400);
        }
        resource.getAttributes().remove(TAG_SNAPSHOT);
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(resource);
        }
        Map<String, String> attributesBefore = Map.copyOf(resource.getAttributes());
        Map<String, String> tags = ctx.resolveTags(props, "Tags");
        Activity previous = ctx.isUpdate()
                && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), attributesBefore)
                ? stepFunctions.describeActivity(ctx.priorPhysicalId()) : null;
        Activity activity;
        if (previous != null && previous.getName().equals(name)) {
            ObjectNode snapshot = MAPPER.createObjectNode();
            snapshot.put("arn", previous.getActivityArn());
            snapshot.set("tags", MAPPER.valueToTree(previous.getTags()));
            resource.getAttributes().put(TAG_SNAPSHOT, snapshot.toString());
            stepFunctions.replaceActivityTags(previous.getActivityArn(), tags);
            activity = previous;
        } else {
            try {
                activity = stepFunctions.createActivity(name, ctx.region(), tags);
            } catch (RuntimeException failure) {
                if (previous != null) {
                    resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
                }
                throw failure;
            }
        }
        resource.setPhysicalId(activity.getActivityArn());
        resource.getAttributes().put("Arn", activity.getActivityArn());
        resource.getAttributes().put("Name", activity.getName());
        ReplacementCleanup.record(resource, ctx, attributesBefore);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("Step Functions activity", physicalId,
                () -> stepFunctions.deleteActivity(physicalId), "ActivityDoesNotExist");
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
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(TAG_SNAPSHOT);
        ReplacementCleanup.clear(resource);
    }

    @Override
    public void mergeFailedUpdateResourceTracking(StackResource previous, StackResource attempted) {
        ReplacementCleanup.mergeDisplaced(previous, attempted);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        String raw = resource.getAttributes().get(TAG_SNAPSHOT);
        if (raw == null) {
            return true;
        }
        try {
            JsonNode snapshot = MAPPER.readTree(raw);
            Map<String, String> tags = new HashMap<>();
            snapshot.path("tags").fields()
                    .forEachRemaining(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
            stepFunctions.replaceActivityTags(snapshot.path("arn").asText(), tags);
            resource.getAttributes().remove(TAG_SNAPSHOT);
            return true;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not read activity update snapshot for "
                    + resource.getLogicalId(), failure);
        }
    }
}
