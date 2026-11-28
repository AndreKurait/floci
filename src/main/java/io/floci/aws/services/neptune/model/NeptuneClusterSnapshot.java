package io.floci.aws.services.neptune.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Metadata and the immutable GraphSON archive share one storage commit. The archive is internal
 * and never rendered by the Query API. Metadata-only records require explicit mock mode.
 */
@RegisterForReflection
public record NeptuneClusterSnapshot(String dbClusterSnapshotIdentifier,
                                     String dbClusterSnapshotArn,
                                     String dbClusterIdentifier,
                                     String dbClusterArn,
                                     String engineVersion,
                                     boolean iamDatabaseAuthenticationEnabled,
                                     Instant clusterCreateTime,
                                     Instant snapshotCreateTime,
                                     NeptuneClusterSettings settings,
                                     Map<String, String> tags,
                                     boolean metadataOnly,
                                     String archiveBase64) {
    public NeptuneClusterSnapshot {
        tags = Map.copyOf(tags);
        settings = new NeptuneClusterSettings(copy(settings.availabilityZones()), settings.port(),
                settings.backupRetentionPeriod(), settings.preferredBackupWindow(), settings.preferredMaintenanceWindow(),
                settings.dbSubnetGroupName(), settings.dbClusterParameterGroupName(), copy(settings.vpcSecurityGroupIds()),
                settings.storageEncrypted(), settings.kmsKeyId(), settings.storageType(), settings.deletionProtection(),
                settings.copyTagsToSnapshot(), copy(settings.enableLogTypes()), copy(settings.disableLogTypes()),
                settings.replicationSourceIdentifier(), settings.serverlessV2MinCapacity(), settings.serverlessV2MaxCapacity());
    }

    private static List<String> copy(List<String> values) {
        return values == null ? null : List.copyOf(values);
    }

    public NeptuneClusterSnapshot withTags(Map<String, String> updated) {
        return new NeptuneClusterSnapshot(dbClusterSnapshotIdentifier, dbClusterSnapshotArn,
                dbClusterIdentifier, dbClusterArn, engineVersion, iamDatabaseAuthenticationEnabled,
                clusterCreateTime, snapshotCreateTime, settings, updated, metadataOnly, archiveBase64);
    }
}
