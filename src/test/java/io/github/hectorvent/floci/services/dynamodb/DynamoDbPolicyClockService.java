package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.dynamodb.model.ExportDescription;
import io.github.hectorvent.floci.services.dynamodb.model.ImportTableDescription;
import io.github.hectorvent.floci.services.dynamodb.model.PointInTimeChange;
import io.github.hectorvent.floci.services.dynamodb.model.TableBackup;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.PolicyWorkloadTimeline;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.Map;

/** Real native service with its existing constructor clock selected only in this test profile. */
@Alternative
@Singleton
public class DynamoDbPolicyClockService extends DynamoDbService {
    @Inject
    public DynamoDbPolicyClockService(StorageFactory factory, RegionResolver regions,
                                     DynamoDbStreamService streams, KinesisStreamingForwarder kinesis,
                                     S3Service s3, ObjectMapper mapper, EmulatorConfig config,
                                     PolicyWorkloadTimeline timeline) {
        super(factory.create("dynamodb", "dynamodb-tables.json",
                        new TypeReference<Map<String, TableDefinition>>() {}),
                itemStore(factory, config),
                factory.create("dynamodb", "dynamodb-exports.json",
                        new TypeReference<Map<String, ExportDescription>>() {}),
                factory.create("dynamodb", "dynamodb-imports.json",
                        new TypeReference<Map<String, ImportTableDescription>>() {}),
                factory.create("dynamodb", "dynamodb-backups.json",
                        new TypeReference<Map<String, TableBackup>>() {}),
                factory.create("dynamodb", "dynamodb-point-in-time.json",
                        new TypeReference<Map<String, PointInTimeChange>>() {}),
                regions, streams, kinesis, s3, mapper,
                config.services().dynamodb().vectorIndexAllocationSeconds(),
                config.services().dynamodb().vectorIndexBackfillSeconds(),
                config.services().dynamodb().latestRestorableLagSeconds(), timeline.clock());
    }
}
