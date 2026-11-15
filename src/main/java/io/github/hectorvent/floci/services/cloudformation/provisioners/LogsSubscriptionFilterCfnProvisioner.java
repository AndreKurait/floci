package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsSubscriptionFilterService;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsSubscriptionFilterService.MutationOutcome;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsSubscriptionFilterService.MutationResult;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.SubscriptionFilter;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static io.github.hectorvent.floci.services.cloudformation.provisioners.ProvisionContext.report;

/**
 * Ref and physical id are the filter name; its group is private identity metadata.
 * This resource deletes the old filter before creating a replacement, including with Retain.
 * Full prior state remains available until commit or successful rollback.
 */
@ApplicationScoped
public class LogsSubscriptionFilterCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::Logs::SubscriptionFilter";
    private static final String GROUP = "__FlociSubscriptionFilterGroup";
    private static final String NAME_MODE = "__FlociSubscriptionFilterNameMode";
    private static final String STATE = "__FlociSubscriptionFilterState";
    private static final String SNAPSHOT = "__FlociSubscriptionFilterUpdate";
    private static final String REPLACEMENT_REASON = "Requested update requires the replacement of the existing resource; "
            + "deleting existing resource, then creating a new one.";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SUPPORTED = Set.of(
            "LogGroupName", "FilterName", "FilterPattern", "DestinationArn", "Distribution");
    private static final Set<String> UNSUPPORTED = Set.of(
            "RoleArn", "ApplyOnTransformedLogs", "EmitSystemFields", "FieldSelectionCriteria");

    private enum Ownership { OWNED, UNOWNED, UNKNOWN }
    private enum Operation { CREATE, UPDATE, RESTORE, DELETE }
    private record Identity(String group, String name) {}

    private final CloudWatchLogsSubscriptionFilterService filters;

    public LogsSubscriptionFilterCfnProvisioner(CloudWatchLogsSubscriptionFilterService filters) {
        this.filters = filters;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext ctx) {
        JsonNode resolved = ctx.engine().resolveNode(markNoValue(properties));
        SubscriptionFilter definition = definition(resolved);
        String explicitName = string(resolved, "FilterName", false, 1, 512, "[^:*]+");
        if (resource.getAttributes().containsKey(SNAPSHOT)) {
            throw new IllegalStateException("Subscription filter update still needs rollback or commit");
        }
        Identity prior = ctx.priorPhysicalId() == null ? null : identity(resource, ctx.priorPhysicalId());
        String name = explicitName != null ? explicitName
                : prior != null && "generated".equals(resource.getAttributes().get(NAME_MODE)) ? prior.name()
                : ctx.generatePhysicalName(resource.getLogicalId(), 512, false);
        definition.setFilterName(name);
        Identity target = new Identity(definition.getLogGroupName(), name);
        if (prior == null) {
            ObjectNode state = state(target, Ownership.UNOWNED);
            setIdentity(resource, target, explicitName == null ? "generated" : "explicit");
            resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
            mutate(state, Operation.CREATE, definition, ctx.region(), () -> {
                saveCurrent(resource, state);
                if (ownership(state) == Ownership.UNOWNED) {
                    resource.setPhysicalId(null);
                    resource.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
                }
            });
            return;
        }

        ObjectNode priorState = currentState(resource, prior);
        resolveUnknown(priorState, ctx.region(), () -> saveCurrent(resource, priorState));
        ObjectNode snapshot = MAPPER.createObjectNode().put("region", ctx.region())
                .put("replacement", !prior.equals(target));
        snapshot.set("attributes", MAPPER.valueToTree(resource.getAttributes()));
        snapshot.set("prior", priorState);
        if (ownership(priorState) == Ownership.OWNED) {
            filters.findSubscriptionFilter(prior.group(), prior.name(), ctx.region()).ifPresentOrElse(
                    filter -> snapshot.set("definition", MAPPER.valueToTree(filter)),
                    () -> priorState.put("ownership", Ownership.UNOWNED.name()));
        }
        if (!prior.equals(target)) {
            snapshot.set("target", state(target, Ownership.UNOWNED));
        }
        Runnable persist = () -> save(resource, SNAPSHOT, snapshot);
        persist.run();
        boolean replacement = !prior.equals(target);
        if (replacement) {
            report(ctx.progress(), prior.name(), "DELETE_IN_PROGRESS", REPLACEMENT_REASON);
            try {
                mutate(priorState, Operation.DELETE, null, ctx.region(), persist);
            } catch (RuntimeException failure) {
                report(ctx.progress(), prior.name(), "DELETE_FAILED", failure.getMessage());
                throw failure;
            }
            report(ctx.progress(), prior.name(), "DELETE_COMPLETE", null);
            setIdentity(resource, target, explicitName == null ? "generated" : "explicit");
            report(ctx.progress(), target.name(), "CREATE_IN_PROGRESS", null);
        }
        ObjectNode targetState = targetState(snapshot);
        try {
            mutate(targetState, replacement ? Operation.CREATE : Operation.UPDATE, definition, ctx.region(), persist);
        } catch (RuntimeException failure) {
            if (replacement) {
                report(ctx.progress(), target.name(), "CREATE_FAILED", failure.getMessage());
            }
            throw failure;
        }
        setIdentity(resource, target, explicitName == null ? "generated" : "explicit");
        if (replacement) {
            report(ctx.progress(), target.name(), "CREATE_COMPLETE", null);
        }
    }

    private void mutate(ObjectNode state, Operation requested, SubscriptionFilter definition,
                        String region, Runnable persist) {
        resolveUnknown(state, region, persist);
        Ownership before = ownership(state);
        if (requested == Operation.DELETE && before == Ownership.UNOWNED) {
            return;
        }
        Operation operation = requested == Operation.UPDATE && before == Ownership.UNOWNED
                ? Operation.CREATE : requested;
        Identity identity = address(state);
        state.put("ownership", Ownership.UNKNOWN.name());
        persist.run();
        Consumer<MutationResult> outcome = result -> {
            Ownership after;
            if (result.outcome() == MutationOutcome.UNKNOWN) {
                after = Ownership.UNKNOWN;
            } else if (operation == Operation.CREATE || operation == Operation.RESTORE && before == Ownership.UNOWNED) {
                after = result.outcome() == MutationOutcome.APPLIED ? Ownership.OWNED : Ownership.UNOWNED;
            } else {
                after = result.present() == null ? before
                        : result.present() ? Ownership.OWNED : Ownership.UNOWNED;
            }
            state.put("ownership", after.name());
            persist.run();
        };
        switch (operation) {
            case CREATE -> filters.createSubscriptionFilter(definition, region, outcome);
            case UPDATE -> filters.updateSubscriptionFilter(definition, region, outcome);
            case RESTORE -> filters.restoreSubscriptionFilter(definition, region, before == Ownership.UNOWNED, outcome);
            case DELETE -> filters.deleteSubscriptionFilter(identity.group(), identity.name(), region, outcome);
        }
    }

    private void resolveUnknown(ObjectNode state, String region, Runnable persist) {
        if (ownership(state) == Ownership.UNKNOWN) {
            Identity identity = address(state);
            if (!filters.confirmSubscriptionFilterAbsent(identity.group(), identity.name(), region, () -> {
                state.put("ownership", Ownership.UNOWNED.name());
                persist.run();
            })) {
                throw new IllegalStateException("Subscription filter ownership is uncertain for "
                        + identity.group() + "/" + identity.name());
            }
        }
    }

    @Override
    public void delete(StackResource resource, String region) {
        ObjectNode snapshot = read(resource, SNAPSHOT);
        if (snapshot != null) {
            Runnable persist = () -> save(resource, SNAPSHOT, snapshot);
            if (snapshot.path("replacement").asBoolean()) {
                mutate(targetState(snapshot), Operation.DELETE, null, region, persist);
            }
            mutate(priorState(snapshot), Operation.DELETE, null, region, persist);
            resource.getAttributes().remove(SNAPSHOT);
        } else if (resource.getPhysicalId() != null) {
            ObjectNode state = currentState(resource, identity(resource, resource.getPhysicalId()));
            mutate(state, Operation.DELETE, null, region, () -> saveCurrent(resource, state));
        }
        resource.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return rollbackUpdate(resource, event -> {});
    }

    @Override
    public boolean rollbackUpdate(StackResource resource, Consumer<StackEvent> progress) {
        ObjectNode snapshot = read(resource, SNAPSHOT);
        if (snapshot == null) {
            return true;
        }
        String region = snapshot.path("region").asText();
        Runnable persist = () -> save(resource, SNAPSHOT, snapshot);
        ObjectNode prior = priorState(snapshot);
        ObjectNode target = targetState(snapshot);
        boolean replacement = snapshot.path("replacement").asBoolean();
        if (replacement || !snapshot.has("definition")) {
            if (replacement) {
                report(progress, address(target).name(), "DELETE_IN_PROGRESS", "Resource update rolled back");
            }
            mutate(target, Operation.DELETE, null, region, persist);
            if (replacement) {
                report(progress, address(target).name(), "DELETE_COMPLETE", null);
            }
        }
        if (snapshot.has("definition")) {
            try {
                SubscriptionFilter definition = MAPPER.treeToValue(snapshot.get("definition"), SubscriptionFilter.class);
                if (replacement) {
                    report(progress, address(prior).name(), "CREATE_IN_PROGRESS", null);
                }
                mutate(prior, Operation.RESTORE, definition, region, persist);
                if (replacement) {
                    report(progress, address(prior).name(), "CREATE_COMPLETE", null);
                }
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Invalid subscription filter rollback definition", e);
            }
        }
        Map<String, String> attributes = new HashMap<>();
        snapshot.path("attributes").fields().forEachRemaining(e -> attributes.put(e.getKey(), e.getValue().asText()));
        resource.setAttributes(attributes);
        resource.setPhysicalId(address(prior).name());
        saveCurrent(resource, prior);
        return true;
    }

    @Override
    public boolean retainsFailedUpdateState(StackResource resource) {
        return resource.getAttributes().containsKey(SNAPSHOT);
    }

    @Override
    public boolean requiresUpdateRecovery(StackResource resource) {
        return resource.getAttributes().containsKey(SNAPSHOT);
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        ObjectNode snapshot = read(resource, SNAPSHOT);
        return snapshot != null && snapshot.path("replacement").asBoolean();
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        ObjectNode snapshot = read(resource, SNAPSHOT);
        if ("UPDATE_COMPLETE".equals(resource.getStatus()) && snapshot != null) {
            clearUpdate(resource);
        }
        return UpdateCleanupResult.notApplicable();
    }

    @Override
    public UpdateCleanupResult completeDeleteCleanup(StackResource resource) {
        return UpdateCleanupResult.notApplicable();
    }

    @Override
    public void clearDeleteCleanup(StackResource resource) {
        // DeleteStack still needs every owned address and the full failed-update snapshot.
    }

    @Override
    public void clearUpdate(StackResource resource) {
        ObjectNode snapshot = read(resource, SNAPSHOT);
        if (snapshot != null) {
            if (ownership(targetState(snapshot)) != Ownership.OWNED
                    || snapshot.path("replacement").asBoolean()
                    && ownership(priorState(snapshot)) != Ownership.UNOWNED) {
                throw new IllegalStateException("Cannot commit unconfirmed subscription filter mutations");
            }
            saveCurrent(resource, targetState(snapshot));
            resource.getAttributes().remove(SNAPSHOT);
        }
    }

    private static ObjectNode state(Identity identity, Ownership ownership) {
        return MAPPER.createObjectNode().put("group", identity.group()).put("name", identity.name())
                .put("ownership", ownership.name());
    }

    private static ObjectNode currentState(StackResource resource, Identity identity) {
        ObjectNode state = read(resource, STATE);
        if (state == null || !identity.equals(address(state))) {
            throw new IllegalStateException("Missing subscription filter ownership metadata");
        }
        return state;
    }

    private static Identity identity(StackResource resource, String name) {
        String group = resource.getAttributes().get(GROUP);
        if (group == null || name == null) {
            throw new IllegalStateException("Missing subscription filter identity");
        }
        return new Identity(group, name);
    }

    private static Identity address(ObjectNode state) {
        return new Identity(state.path("group").asText(), state.path("name").asText());
    }

    private static Ownership ownership(ObjectNode state) {
        return Ownership.valueOf(state.path("ownership").asText());
    }

    private static ObjectNode priorState(ObjectNode snapshot) {
        return (ObjectNode) snapshot.get("prior");
    }

    private static ObjectNode targetState(ObjectNode snapshot) {
        return snapshot.path("replacement").asBoolean() ? (ObjectNode) snapshot.get("target") : priorState(snapshot);
    }

    private static void saveCurrent(StackResource resource, ObjectNode state) {
        save(resource, STATE, state);
    }

    private static void save(StackResource resource, String attribute, ObjectNode value) {
        resource.getAttributes().put(attribute, value.toString());
    }

    private static void setIdentity(StackResource resource, Identity identity, String mode) {
        resource.setPhysicalId(identity.name());
        resource.getAttributes().put(GROUP, identity.group());
        resource.getAttributes().put(NAME_MODE, mode);
    }

    private static ObjectNode read(StackResource resource, String attribute) {
        String raw = resource.getAttributes().get(attribute);
        if (raw == null) {
            return null;
        }
        try {
            if (MAPPER.readTree(raw) instanceof ObjectNode object) {
                return object;
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid subscription filter lifecycle metadata", e);
        }
        throw new IllegalStateException("Invalid subscription filter lifecycle metadata");
    }

    private static SubscriptionFilter definition(JsonNode properties) {
        if (properties == null || !properties.isObject()) {
            throw invalid("Properties must be an object");
        }
        properties.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isMissingNode() && !SUPPORTED.contains(entry.getKey())) {
                throw invalid((UNSUPPORTED.contains(entry.getKey()) ? "Unsupported native property " : "Unknown property ")
                        + entry.getKey());
            }
        });
        SubscriptionFilter filter = new SubscriptionFilter();
        filter.setLogGroupName(string(properties, "LogGroupName", true, 1, 512, "[.\\-_/#A-Za-z0-9]+"));
        filter.setFilterPattern(string(properties, "FilterPattern", true, 0, 1024, null));
        filter.setDestinationArn(string(properties, "DestinationArn", true, 1, Integer.MAX_VALUE, null));
        filter.setDistribution(string(properties, "Distribution", false, 1, 32, "Random|ByLogStream"));
        return filter;
    }

    private static String string(JsonNode node, String name, boolean required, int min, int max, String pattern) {
        JsonNode value = node == null ? null : node.get(name);
        if ((value == null || value.isMissingNode()) && !required) {
            return null;
        }
        if (value == null || !value.isTextual() || value.textValue().length() < min
                || value.textValue().length() > max || pattern != null && !value.textValue().matches(pattern)) {
            throw invalid(name + " must be a valid string");
        }
        return value.textValue();
    }

    private static JsonNode markNoValue(JsonNode node) {
        if (node != null && node.isObject()) {
            if ("AWS::NoValue".equals(node.path("Ref").asText())) {
                return MissingNode.getInstance();
            }
            ObjectNode marked = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> marked.set(e.getKey(), markNoValue(e.getValue())));
            return marked;
        }
        if (node != null && node.isArray()) {
            ArrayNode marked = MAPPER.createArrayNode();
            node.forEach(value -> marked.add(markNoValue(value)));
            return marked;
        }
        return node;
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", TYPE + " " + message, 400);
    }
}
