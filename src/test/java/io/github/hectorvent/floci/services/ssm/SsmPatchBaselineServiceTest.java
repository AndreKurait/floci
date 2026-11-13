package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class SsmPatchBaselineServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "830011110005";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void factoryLifecyclePreservesConfigurationBindingsTagsTokensAndDefaults(String mode) {
        ObjectNode create = object().put("Name", "persisted-baseline").put("OperatingSystem", "AMAZON_LINUX_2")
                .put("ClientToken", "persisted-token");
        create.putArray("ApprovedPatches").add("kernel*");
        create.putArray("Tags").add(object().put("Key", "purpose").put("Value", "restart"));
        StorageFactory first = factory(mode);
        String id;
        try {
            SsmPatchBaselineService service = new SsmPatchBaselineService(first, predefined(), regions());
            id = service.create(create, REGION).path("BaselineId").asText();
            service.registerDefault(select(id), REGION);
            service.registerGroup(select(id).put("PatchGroup", "restart-group"), REGION);
            first.loadAll();
            assertEquals(id, service.getDefault(object().put("OperatingSystem", "AMAZON_LINUX_2"), REGION)
                    .path("BaselineId").asText());
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = factory(mode);
        try {
            SsmPatchBaselineService service = new SsmPatchBaselineService(second, predefined(), regions());
            if (mode.equals("memory")) {
                assertThrows(AwsException.class, () -> service.get(select(id), REGION));
                return;
            }
            ObjectNode restored = service.get(select(id), REGION);
            assertEquals("persisted-baseline", restored.path("Name").asText());
            assertEquals("kernel*", restored.path("ApprovedPatches").get(0).asText());
            assertEquals("restart-group", restored.path("PatchGroups").get(0).asText());
            assertEquals(id, service.create(create, REGION).path("BaselineId").asText());
            assertEquals(id, service.getForGroup(object().put("PatchGroup", "restart-group")
                    .put("OperatingSystem", "AMAZON_LINUX_2"), REGION).path("BaselineId").asText());
            ObjectNode tags = service.resourceTags("ListTagsForResource", object()
                    .put("ResourceId", id).put("ResourceType", "PatchBaseline"), REGION);
            assertEquals("restart", tags.path("TagList").get(0).path("Value").asText());
            service.deregisterGroup(select(id).put("PatchGroup", "restart-group"), REGION);
            service.delete(select(id), REGION);
            assertNotEquals(id, service.getDefault(object().put("OperatingSystem", "AMAZON_LINUX_2"), REGION)
                    .path("BaselineId").asText());
        } finally {
            second.shutdownAll();
        }
        StorageFactory third = factory(mode);
        try {
            SsmPatchBaselineService service = new SsmPatchBaselineService(third, predefined(), regions());
            assertThrows(AwsException.class, () -> service.get(select(id), REGION));
            assertNotEquals(id, service.create(create, REGION).path("BaselineId").asText());
            service.clear();
            assertTrue(service.describeGroups(object(), REGION).path("Mappings").isEmpty());
        } finally {
            third.shutdownAll();
        }
    }

    @Test
    void rejectedWritesDoNotPublishPartialConfigurationOrIdempotencyState() {
        AccountAwareStorageBackend<ObjectNode> store = spy(AccountAwareStorageBackend.inMemory(ACCOUNT));
        SsmPatchBaselineService service = new SsmPatchBaselineService(store, predefined(), regions(), Clock.systemUTC());
        String id = service.create(object().put("Name", "original-baseline"), REGION).path("BaselineId").asText();
        ObjectNode before = service.get(select(id), REGION);
        Set<String> keys = Set.copyOf(store.keys());
        doThrow(new IllegalStateException("storage unavailable")).when(store).putAllAtomically(anyMap());
        assertThrows(IllegalStateException.class,
                () -> service.update(select(id).put("Description", "uncommitted"), REGION));
        assertThrows(IllegalStateException.class,
                () -> service.registerGroup(select(id).put("PatchGroup", "uncommitted"), REGION));
        assertThrows(IllegalStateException.class,
                () -> service.create(object().put("Name", "uncommitted").put("ClientToken", "retry-token"), REGION));
        assertEquals(before, service.get(select(id), REGION));
        assertEquals(keys, store.keys());
    }

    @Test
    void returnedConfigurationCannotMutateTheStoredBaseline() {
        SsmPatchBaselineService service = new SsmPatchBaselineService(AccountAwareStorageBackend.inMemory(ACCOUNT),
                predefined(), regions(), Clock.systemUTC());
        ObjectNode request = object().put("Name", "original-baseline");
        request.putArray("ApprovedPatches").add("original");
        String id = service.create(request, REGION).path("BaselineId").asText();
        request.withArray("ApprovedPatches").add("caller-change");
        ObjectNode read = service.get(select(id), REGION);
        read.put("Name", "caller-change");
        read.withArray("ApprovedPatches").add("caller-change");
        ObjectNode stored = service.get(select(id), REGION);
        assertEquals("original-baseline", stored.path("Name").asText());
        assertEquals(1, stored.path("ApprovedPatches").size());
    }

    private StorageFactory factory(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60_000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(60_000L);
        return new StorageFactory(config, access);
    }

    private static SsmService predefined() {
        SsmService ssm = mock(SsmService.class);
        when(ssm.getDefaultPatchBaseline(anyString())).thenReturn("pb-00000000000000000");
        when(ssm.describePatchBaselines(anyMap())).thenReturn(List.of());
        return ssm;
    }

    private static RegionResolver regions() {
        return new RegionResolver(REGION, ACCOUNT);
    }

    private static ObjectNode select(String id) {
        return object().put("BaselineId", id);
    }

    private static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }
}
