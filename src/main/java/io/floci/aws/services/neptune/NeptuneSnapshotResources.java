package io.floci.aws.services.neptune;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.neptune.model.NeptuneCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The resources named by Neptune snapshot requests, which use the shared RDS IAM namespace. */
@ApplicationScoped
public class NeptuneSnapshotResources {
    private static final Set<String> OPERATIONS = Set.of("rds:CreateDBClusterSnapshot",
            "rds:DescribeDBClusterSnapshots", "rds:DeleteDBClusterSnapshot", "rds:RestoreDBClusterFromSnapshot");
    private final NeptuneService service;

    @Inject
    public NeptuneSnapshotResources(NeptuneService service) {
        this.service = service;
    }

    public static boolean supports(String operation) {
        return operation != null && OPERATIONS.contains(operation);
    }

    public Optional<List<String>> resolve(String operation, Map<String, String> parameters,
                                          String partition, String region, String account) {
        if (!supports(operation)) {
            return Optional.empty();
        }
        String cluster = parameters.get("DBClusterIdentifier");
        String snapshot = parameters.get("DBClusterSnapshotIdentifier");
        String sourceSnapshot = parameters.get("SnapshotIdentifier");
        List<NeptuneCluster> clusters = cluster == null || cluster.isBlank()
                ? List.of() : List.copyOf(service.listDbClusters(cluster));
        boolean restoring = "rds:RestoreDBClusterFromSnapshot".equals(operation);
        boolean creating = "rds:CreateDBClusterSnapshot".equals(operation);
        if (!(restoring && "neptune".equalsIgnoreCase(parameters.get("Engine")))
                && clusters.isEmpty() && !service.hasClusterSnapshot(snapshot)
                && !service.hasClusterSnapshot(sourceSnapshot)) {
            return Optional.empty();
        }
        List<String> resources = new ArrayList<>();
        if (creating || restoring || "rds:DescribeDBClusterSnapshots".equals(operation)) {
            // Existing Floci Neptune clusters use a neptune ARN. The control-plane IAM ARN is rds.
            String identifier = clusters.isEmpty() ? cluster : clusters.getFirst().getDbClusterIdentifier();
            add(resources, identifier, "cluster", partition, region, account);
        }
        if (creating || "rds:DeleteDBClusterSnapshot".equals(operation)
                || "rds:DescribeDBClusterSnapshots".equals(operation)) {
            add(resources, snapshot, "cluster-snapshot", partition, region, account);
        }
        if (restoring) {
            add(resources, sourceSnapshot, "cluster-snapshot", partition, region, account);
            add(resources, parameters.get("DBClusterParameterGroupName"), "cluster-pg", partition, region, account);
            add(resources, parameters.get("DBSubnetGroupName"), "subgrp", partition, region, account);
        }
        return Optional.of(resources.isEmpty() ? List.of("*") : List.copyOf(resources));
    }

    private static void add(List<String> resources, String value, String type,
                             String partition, String region, String account) {
        if (value != null && !value.isBlank()) {
            resources.add(AwsArnUtils.looksLikeArn(value) ? value
                    : new AwsArnUtils.Arn(partition, "rds", region, account,
                            type + ":" + value.toLowerCase(Locale.ROOT)).toString());
        }
    }
}
