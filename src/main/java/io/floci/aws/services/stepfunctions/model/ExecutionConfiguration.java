package io.floci.aws.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** The resolved configuration captured before a Standard execution starts. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExecutionConfiguration(String stateMachineArn, String name, String definition, String roleArn,
                                     double updateDate, String revisionId, JsonNode loggingConfiguration,
                                     JsonNode tracingConfiguration, JsonNode encryptionConfiguration) {
    public static ExecutionConfiguration capture(StateMachine source, String baseArn) {
        return capture(source, baseArn,
                source.getUpdateDate() != null ? source.getUpdateDate() : source.getCreationDate(),
                source.getUpdateDate() != null ? source.getRevisionId() : null);
    }

    public static ExecutionConfiguration captureVersion(
            StateMachine source, String baseArn, StateMachineVersion version) {
        boolean recorded = version.getConfigurationUpdateDate() != null;
        return capture(source, baseArn,
                recorded ? version.getConfigurationUpdateDate() : version.getCreationDate(),
                recorded ? version.getConfigurationRevisionId() : source.getRevisionId());
    }

    private static ExecutionConfiguration capture(StateMachine source, String baseArn, double date, String revision) {
        return new ExecutionConfiguration(baseArn, source.getName(), source.getDefinition(), source.getRoleArn(),
                date, revision, copy(source.getLoggingConfiguration()), copy(source.getTracingConfiguration()),
                copy(source.getEncryptionConfiguration()));
    }

    public ExecutionConfiguration copy() {
        return new ExecutionConfiguration(stateMachineArn, name, definition, roleArn, updateDate, revisionId,
                copy(loggingConfiguration), copy(tracingConfiguration), copy(encryptionConfiguration));
    }

    private static JsonNode copy(JsonNode node) {
        return node == null ? null : node.deepCopy();
    }
}
