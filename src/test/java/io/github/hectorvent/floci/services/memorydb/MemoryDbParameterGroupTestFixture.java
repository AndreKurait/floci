package io.github.hectorvent.floci.services.memorydb;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.memorydb.model.MemoryDbParameterGroup;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class MemoryDbParameterGroupTestFixture {
    static final String ACCOUNT = "111111111111";
    static final String REGION = "us-east-1";
    static final String FAMILY = "memorydb_redis6";
    static final String FILE = "memorydb-parameter-groups.json";
    final ObjectMapper mapper = new ObjectMapper();
    final MemoryDbParameterCatalog catalog = new MemoryDbParameterCatalog(mapper);
    final RegionResolver regions = mock(RegionResolver.class);
    final StorageBackend<String, MemoryDbParameterGroup> raw;
    final MemoryDbParameterGroupService service;

    MemoryDbParameterGroupTestFixture() {
        this(new InMemoryStorage<>());
    }

    MemoryDbParameterGroupTestFixture(StorageBackend<String, MemoryDbParameterGroup> raw) {
        this.raw = raw;
        when(regions.getAccountId()).thenReturn(ACCOUNT);
        when(regions.partitionForRegion(anyString())).thenAnswer(call -> AwsRegions.partitionFor(call.getArgument(0)));
        service = new MemoryDbParameterGroupService(
                new InMemoryStorageFactory(ACCOUNT).withStore(FILE, raw), catalog, regions, mapper);
    }

    MemoryDbParameterGroup create(String name) {
        return service.create(name, FAMILY, "retained description", Map.of("team", "blue"), REGION);
    }

    MemoryDbParameterGroup current(String name) {
        return service.describe(name, null, null, REGION).values().getFirst();
    }

    static List<MemoryDbParameterGroupService.ParameterValue> update(String name, String value) {
        return List.of(new MemoryDbParameterGroupService.ParameterValue(name, value));
    }

    static void error(String code, Executable action) {
        AwsException failure = assertThrows(AwsException.class, action);
        assertEquals(code, failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
    }
}
