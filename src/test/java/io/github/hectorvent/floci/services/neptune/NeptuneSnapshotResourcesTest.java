package io.github.hectorvent.floci.services.neptune;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class NeptuneSnapshotResourcesTest {
    private final NeptuneSnapshotResources resources = new NeptuneSnapshotResources(mock(NeptuneService.class));

    @ParameterizedTest
    @CsvSource({"aws,us-east-1", "aws-cn,cn-north-1", "aws-us-gov,us-gov-west-1"})
    void restoreChecksOnlyTheResourcesItsRequestNames(String partition, String region) {
        String prefix = "arn:" + partition + ":rds:" + region + ":111111111111:";
        assertEquals(List.of(prefix + "cluster:target", prefix + "cluster-snapshot:snapshot"),
                resources.resolve("rds:RestoreDBClusterFromSnapshot", Map.of("Engine", "neptune",
                        "DBClusterIdentifier", "Target", "SnapshotIdentifier", prefix + "cluster-snapshot:snapshot"),
                        partition, region, "111111111111").orElseThrow());
        assertEquals(List.of(prefix + "cluster:target", prefix + "cluster-snapshot:snapshot",
                        prefix + "cluster-pg:parameters", prefix + "subgrp:subnets"),
                resources.resolve("rds:RestoreDBClusterFromSnapshot", Map.of("Engine", "neptune",
                        "DBClusterIdentifier", "target", "SnapshotIdentifier", "snapshot",
                        "DBClusterParameterGroupName", "parameters", "DBSubnetGroupName", "subnets"),
                        partition, region, "111111111111").orElseThrow());
    }

    @Test
    void otherOperationsAndOtherFamiliesKeepTheirExistingResolution() {
        for (String operation : List.of("rds:CreateDBCluster", "rds:DeleteDBInstance", "rds:DescribeDBClusterSnapshots")) {
            assertTrue(resources.resolve(operation, Map.of(), "aws", "us-east-1", "111111111111").isEmpty());
        }
        assertTrue(resources.resolve("rds:RestoreDBClusterFromSnapshot", Map.of("Engine", "docdb",
                "DBClusterIdentifier", "target", "SnapshotIdentifier", "snapshot"),
                "aws", "us-east-1", "111111111111").isEmpty());
    }
}
