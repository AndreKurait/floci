package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;

/** The supported configuration fields, detached from caller and stored JSON nodes. */
final class SsmSourceSyncConfiguration {
    private SsmSourceSyncConfiguration() {}

    static ObjectNode read(JsonNode request, boolean update) {
        if (request.hasNonNull("S3Destination")) {
            throw invalid("S3Destination cannot be combined with SyncFromSource.");
        }
        JsonNode source = request.get("SyncSource");
        if (source == null || source.isNull()) {
            throw invalid("SyncSource is required for SyncFromSource.");
        }
        if (!"SingleAccountMultiRegions".equals(source.path("SourceType").asText())
                || source.hasNonNull("AwsOrganizationsSource")) {
            throw invalid("Only SingleAccountMultiRegions sources are supported; account options cannot change.");
        }
        ObjectNode result = JsonNodeFactory.instance.objectNode()
                .put("SourceType", source.get("SourceType").asText());
        result.set("SourceRegions", source.get("SourceRegions").deepCopy());
        if (update || source.hasNonNull("IncludeFutureRegions")) {
            result.put("IncludeFutureRegions", source.path("IncludeFutureRegions").asBoolean(false));
        }
        if (source.hasNonNull("EnableAllOpsDataSources")) {
            result.put("EnableAllOpsDataSources", source.get("EnableAllOpsDataSources").asBoolean());
        }
        return result;
    }

    static AwsException invalid(String message) {
        return new AwsException("ResourceDataSyncInvalidConfigurationException", message, 400);
    }
}
