package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.RunInstancesReplayJournal;
import io.github.hectorvent.floci.services.ec2.model.RunInstancesReplayRecord;
import io.github.hectorvent.floci.services.ec2.model.RunInstancesReplayRecord.Allocation;
import io.github.hectorvent.floci.services.ec2.model.RunInstancesReplayRecord.Result;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Request outcomes and allocation ownership using the configured EC2 storage durability. */
final class Ec2RunInstancesReplay {
    private static final Logger LOG = Logger.getLogger(Ec2RunInstancesReplay.class);
    private final AccountAwareStorageBackend<RunInstancesReplayJournal> records;
    private final ConcurrentHashMap<String, Lock> locks = new ConcurrentHashMap<>();

    Ec2RunInstancesReplay(StorageFactory factory) {
        this(factory.create("ec2", "ec2-run-instances-replays.json",
                new TypeReference<Map<String, RunInstancesReplayJournal>>() {}));
    }

    Ec2RunInstancesReplay(AccountAwareStorageBackend<RunInstancesReplayJournal> records) {
        this.records = records;
    }

    Result execute(String account, String region, String selectedZone, boolean regional, String token,
                   Map<String, List<String>> parameters, Function<Attempt, Result> launch,
                   Consumer<List<Allocation>> compensate) {
        return execute(account, region, () -> new Scope(selectedZone, regional), token, parameters, launch, compensate, false);
    }

    record Scope(String zone, boolean regional) {}

    Result execute(String account, String region, Supplier<Scope> scope, String token,
                   Map<String, List<String>> parameters, Function<Attempt, Result> launch,
                   Consumer<List<Allocation>> compensate) {
        return execute(account, region, scope, token, parameters, launch, compensate, true);
    }

    private Result execute(String account, String region, Supplier<Scope> scope, String token,
                           Map<String, List<String>> parameters, Function<Attempt, Result> launch,
                           Consumer<List<Allocation>> compensate, boolean matchBeforeScope) {
        Objects.requireNonNull(account);
        Objects.requireNonNull(region);
        Objects.requireNonNull(token);
        String key = component(account) + component(region) + component(token);
        Lock lock = locks.compute(key, (ignored, existing) -> {
            Lock selected = existing == null ? new Lock() : existing;
            selected.users++;
            return selected;
        });
        try {
            synchronized (lock) {
                RunInstancesReplayJournal journal = records.getForAccount(account, key).orElse(null);
                if (matchBeforeScope && journal != null) {
                    for (RunInstancesReplayRecord record : journal.zones().values()) {
                        if (record.result() != null && record.parameters().equals(parameters)) {
                            return record.result();
                        }
                    }
                }
                Scope selected = scope.get();
                return executeLocked(account, key, selected.zone(), selected.regional(), parameters, launch, compensate);
            }
        } finally {
            locks.compute(key, (ignored, existing) -> --existing.users == 0 ? null : existing);
        }
    }

    private Result executeLocked(String account, String key, String selectedZone, boolean regional,
                                 Map<String, List<String>> parameters,
                                 Function<Attempt, Result> launch, Consumer<List<Allocation>> compensate) {
        RunInstancesReplayJournal journal = records.getForAccount(account, key)
                .orElseGet(() -> new RunInstancesReplayJournal(null, Map.of()));
        String zone = regional && journal.regionalZone() != null ? journal.regionalZone() : selectedZone;
        RunInstancesReplayRecord existing = journal.zones().get(zone);
        if (existing != null && existing.result() != null) {
            if (!existing.parameters().equals(parameters)) {
                throw new AwsException("IdempotentParameterMismatch",
                        "Arguments on this idempotent request are inconsistent with arguments used previously.", 400);
            }
            return existing.result();
        }
        if (existing != null) {
            compensate.accept(existing.allocations());
            journal = without(journal, zone);
            publish(account, key, journal);
            // A regional retry may choose a different zone after an unsuccessful attempt.
            return executeLocked(account, key, selectedZone, regional, parameters, launch, compensate);
        }
        Map<String, RunInstancesReplayRecord> zones = new HashMap<>(journal.zones());
        zones.put(zone, new RunInstancesReplayRecord(parameters, List.of(), null));
        journal = new RunInstancesReplayJournal(regional ? zone : journal.regionalZone(), zones);
        Attempt attempt = new Attempt(account, key, zone, journal);
        publish(account, key, journal);
        try {
            Result result = Objects.requireNonNull(launch.apply(attempt));
            RunInstancesReplayRecord pending = attempt.record();
            attempt.replace(new RunInstancesReplayRecord(pending.parameters(), pending.allocations(), result));
            return result;
        } catch (RuntimeException failure) {
            try {
                compensate.accept(attempt.record().allocations());
                publish(account, key, without(attempt.journal, zone));
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static RunInstancesReplayJournal without(RunInstancesReplayJournal journal, String zone) {
        Map<String, RunInstancesReplayRecord> remaining = new HashMap<>(journal.zones());
        remaining.remove(zone);
        return new RunInstancesReplayJournal(Objects.equals(zone, journal.regionalZone())
                ? null : journal.regionalZone(), remaining);
    }

    private void publish(String account, String key, RunInstancesReplayJournal journal) {
        // Unlike put(), this operation reports snapshot/WAL write failures before publishing.
        records.putAllDurablyForAccount(account, Map.of(key, journal));
    }

    record Recovery(String account, String region, List<Allocation> allocations) {}

    void recoverPending(Consumer<Recovery> compensate) {
        for (AccountEntry<RunInstancesReplayJournal> entry : records.scanAllAccountEntries(key -> true)) {
            RunInstancesReplayJournal journal = entry.value();
            int separator = entry.key().indexOf(':');
            int regionStart = separator + 1 + Integer.parseInt(entry.key().substring(0, separator));
            int regionSeparator = entry.key().indexOf(':', regionStart);
            int regionLength = Integer.parseInt(entry.key().substring(regionStart, regionSeparator));
            String region = entry.key().substring(regionSeparator + 1, regionSeparator + 1 + regionLength);
            for (Map.Entry<String, RunInstancesReplayRecord> zone : entry.value().zones().entrySet()) {
                if (zone.getValue().result() == null) {
                    try {
                        compensate.accept(new Recovery(entry.accountId(), region, zone.getValue().allocations()));
                        RunInstancesReplayJournal remaining = without(journal, zone.getKey());
                        publish(entry.accountId(), entry.key(), remaining);
                        journal = remaining;
                    } catch (RuntimeException failure) {
                        LOG.warnv(failure, "Unable to recover pending RunInstances allocation for account {0}, region {1}, zone {2}",
                                entry.accountId(), region, zone.getKey());
                    }
                }
            }
        }
    }

    private static String component(String value) {
        return value.length() + ":" + value;
    }

    final class Attempt {
        private final String account;
        private final String key;
        private final String zone;
        private RunInstancesReplayJournal journal;

        private Attempt(String account, String key, String zone, RunInstancesReplayJournal journal) {
            this.account = account;
            this.key = key;
            this.zone = zone;
            this.journal = journal;
        }

        String selectedZone() {
            return zone;
        }

        /** Record generated resource identifiers before creating or attaching those resources. */
        void remember(Allocation allocation) {
            List<Allocation> allocations = new ArrayList<>(record().allocations());
            allocations.add(allocation);
            replace(new RunInstancesReplayRecord(record().parameters(), allocations, null));
        }

        void rememberAll(List<Allocation> allocations) {
            replace(new RunInstancesReplayRecord(record().parameters(), allocations, null));
        }

        void rememberInterfaceTags(String interfaceId, Map<String, String> before, Map<String, String> applied) {
            List<Allocation> allocations = record().allocations().stream().map(allocation ->
                    interfaceId.equals(allocation.suppliedNetworkInterfaceId())
                            ? new Allocation(allocation.instanceId(), allocation.rootVolumeId(), allocation.subnetId(),
                                    allocation.privateIpAddress(), allocation.suppliedNetworkInterfaceId(),
                                    allocation.generatedNetworkInterfaceId(), before, applied,
                                    allocation.additionalVolumeIds()) : allocation).toList();
            replace(new RunInstancesReplayRecord(record().parameters(), allocations, null));
        }

        private RunInstancesReplayRecord record() {
            return journal.zones().get(zone);
        }

        private void replace(RunInstancesReplayRecord replacement) {
            Map<String, RunInstancesReplayRecord> zones = new HashMap<>(journal.zones());
            zones.put(zone, replacement);
            RunInstancesReplayJournal updated = new RunInstancesReplayJournal(journal.regionalZone(), zones);
            publish(account, key, updated);
            journal = updated;
        }
    }

    private static final class Lock {
        private int users;
    }
}
