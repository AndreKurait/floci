package io.floci.aws.services.dynamodb;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Detached numeric accounting for one completed native item operation. */
public record DynamoDbCapacityUsage(String accountId, String region, String tableName, String tableId,
                                    Instant timestamp, String publicationId, double tableRead, double tableWrite,
                                    Map<String, Double> indexReads, Map<String, Double> indexWrites) {
    public DynamoDbCapacityUsage {
        indexReads = Map.copyOf(indexReads);
        indexWrites = Map.copyOf(indexWrites);
    }

    static DynamoDbCapacityUsage of(String accountId, String region, String tableName, String tableId,
                                    Instant timestamp, double tableRead, double tableWrite,
                                    Map<String, Double> indexReads, Map<String, Double> indexWrites) {
        return new DynamoDbCapacityUsage(accountId, region, tableName, tableId, timestamp,
                UUID.randomUUID().toString(), tableRead, tableWrite, indexReads, indexWrites);
    }

    static double readUnits(long bytes, boolean consistentRead) {
        double units = Math.max(1, (bytes + 4095) / 4096) * 0.5;
        return consistentRead ? units * 2 : units;
    }

    double totalRead() {
        return tableRead + indexReads.values().stream().mapToDouble(Double::doubleValue).sum();
    }
}
