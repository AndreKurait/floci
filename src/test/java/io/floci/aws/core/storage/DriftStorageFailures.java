package io.floci.aws.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.services.cloudformation.model.StackDriftRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test-only access to the real WAL force boundary. */
public final class DriftStorageFailures {
    private DriftStorageFailures() {
    }

    public static WalStorage<String, StackDriftRecord> wal(Path snapshot, Path log, AtomicBoolean fail) {
        return new WalStorage<>(snapshot, log, new TypeReference<Map<String, StackDriftRecord>>() {},
                3_600_000, channel -> {
                    if (fail.get()) {
                        throw new IOException("owned drift durability fault");
                    }
                    channel.force(true);
                });
    }
}
