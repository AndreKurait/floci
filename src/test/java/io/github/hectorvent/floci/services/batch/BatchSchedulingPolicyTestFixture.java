package io.github.hectorvent.floci.services.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.batch.model.StoredSchedulingPolicy;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;

import java.util.List;
import java.util.Map;

final class BatchSchedulingPolicyTestFixture {
    static final String ACCOUNT = "123456789012";
    static final String REGION = "us-west-2";
    static final String FILE = "batch-scheduling-policies.json";
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final BatchSchedulingPolicyReader READER = new BatchSchedulingPolicyReader(MAPPER);
    final StorageBackend<String, StoredSchedulingPolicy> storage;
    final BatchSchedulingPolicyService service;
    final String account;
    final String region;
    final String partition;

    BatchSchedulingPolicyTestFixture() {
        this(new InMemoryStorage<>());
    }

    BatchSchedulingPolicyTestFixture(StorageBackend<String, StoredSchedulingPolicy> storage) {
        this(storage, ACCOUNT, REGION);
    }

    BatchSchedulingPolicyTestFixture(StorageBackend<String, StoredSchedulingPolicy> storage, String account, String region) {
        this.storage = storage;
        this.account = account;
        this.region = region;
        RegionResolver regions = new RegionResolver(region, account);
        partition = regions.getPartition();
        service = new BatchSchedulingPolicyService(new InMemoryStorageFactory(account).withStore(FILE, storage),
                regions, MAPPER);
    }

    String arn(String name) {
        return BatchSchedulingPolicyTarget.arn(partition, region, account, name);
    }

    String key(String name) {
        return account + "/" + partition + "/" + region + "/" + name;
    }

    StoredSchedulingPolicy stored(String name) {
        return storage.get(key(name)).orElseThrow();
    }

    StoredSchedulingPolicy create(String name) {
        service.create(json(Map.of("name", name, "tags", Map.of("team", "blue"),
                "fairsharePolicy", Map.of("shareDecaySeconds", 30, "computeReservation", 20,
                        "shareDistribution", List.of(Map.of("shareIdentifier", "alpha", "weightFactor", 2))))));
        return stored(name);
    }

    static JsonNode json(Object value) {
        return MAPPER.valueToTree(value);
    }

    JsonNode select(String name) {
        return json(Map.of("arn", arn(name)));
    }

    JsonNode update(String name, int decay) {
        return json(Map.of("arn", arn(name), "fairsharePolicy", Map.of("shareDecaySeconds", decay)));
    }
}
