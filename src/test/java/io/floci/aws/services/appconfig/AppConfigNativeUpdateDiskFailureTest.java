package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigNativeUpdateDiskFailureTest {
    private static final String ACCOUNT = "741686000094";
    private static final String REGION = "us-east-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void refusedSnapshotReplacementLeavesTheLiveRecordUnchanged(boolean environment, @TempDir Path directory)
            throws IOException {
        Path snapshot = directory.resolve("owned-appconfig-records.json");
        TypeReference<Map<String, Object>> type = new TypeReference<>() {};
        PersistentStorage<String, Object> disk = new PersistentStorage<>(snapshot, type);
        disk.load();
        String file = environment ? "appconfig-environments.json" : "appconfig-strategies.json";
        InMemoryStorageFactory factory = new InMemoryStorageFactory(ACCOUNT).withStore(file, disk);
        AppConfigService service = new AppConfigService(factory, new RegionResolver(REGION, ACCOUNT));
        String app = service.createApplication(Map.of("Name", "owned-app")).getId();
        String id = environment
                ? service.createEnvironment(app, Map.of("Name", "owned-env", "Description", "original")).getId()
                : service.createDeploymentStrategy(Map.of("Name", "owned-strategy", "Description", "original",
                        "GrowthFactor", 20, "DeploymentDurationInMinutes", 10, "FinalBakeTimeInMinutes", 2)).getId();
        Object original = environment ? service.getEnvironment(app, id) : service.getDeploymentStrategy(id);
        JsonNode before = snapshot(original);
        String key = ACCOUNT + "/" + REGION + "/" + id;
        assertSame(original, disk.get(key).orElseThrow());
        byte[] committed = Files.readAllBytes(snapshot);
        Files.delete(snapshot);
        Files.createDirectory(snapshot);
        Files.writeString(snapshot.resolve("block-replacement"), "owned test obstacle");
        try {
            assertThrows(UncheckedIOException.class, () -> patch(service, environment, app, id, "must-not-publish"));
            assertSame(original, disk.get(key).orElseThrow());
            assertEquals(before, snapshot(environment
                    ? service.getEnvironment(app, id) : service.getDeploymentStrategy(id)));
        } finally {
            Files.delete(snapshot.resolve("block-replacement"));
            Files.delete(snapshot);
            Files.write(snapshot, committed);
        }
        PersistentStorage<String, Object> reloaded = new PersistentStorage<>(snapshot, type);
        reloaded.load();
        assertEquals(before, snapshot(reloaded.get(key).orElseThrow()));
        assertArrayEquals(committed, Files.readAllBytes(snapshot));
        patch(service, environment, app, id, "accepted-after-recovery");
        PersistentStorage<String, Object> recovered = new PersistentStorage<>(snapshot, type);
        recovered.load();
        assertEquals("accepted-after-recovery", snapshot(recovered.get(key).orElseThrow())
                .path("Description").asText());
        assertEquals(before, snapshot(original));
    }

    /** Compare JSON number values consistently after typed records reload as generic maps. */
    private static JsonNode snapshot(Object value) throws IOException {
        return MAPPER.readTree(MAPPER.writeValueAsBytes(value));
    }

    private static void patch(AppConfigService service, boolean environment, String app, String id, String description) {
        if (environment) {
            service.updateEnvironment(app, id, Map.of("Description", description));
        } else {
            service.updateDeploymentStrategy(id, Map.of("Description", description));
        }
    }
}
