package io.github.hectorvent.floci.services.kinesis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static io.github.hectorvent.floci.services.kinesis.KinesisResourcePolicyServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class KinesisPolicyReviewPersistenceServiceTest {
    @ParameterizedTest
    @MethodSource("io.github.hectorvent.floci.services.kinesis.KinesisPolicyReviewCases#rejectedPolicies")
    void invalidPolicyCannotMigrateOrChangeOwnerProvenLegacySnapshot(String rejected, @TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("streams.json");
        PersistentStorage<String, KinesisStream> raw = new PersistentStorage<>(file, new TypeReference<>() {});
        raw.load();
        RegionResolver regions = new RegionResolver(REGION, ACCOUNT);
        String arn = regions.buildArn("kinesis", REGION, "stream/legacy");
        KinesisStream legacy = new KinesisStream("legacy", arn);
        legacy.setAccountId(ACCOUNT);
        legacy.setResourcePolicies(Map.of(arn, POLICY));
        legacy.setTags(Map.of("owner", "kept"));
        String key = REGION + "::legacy";
        raw.put(key, legacy);
        raw.flush();
        byte[] committed = Files.readAllBytes(file);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String before = mapper.writeValueAsString(legacy);
        AccountAwareStorageBackend<KinesisStream> scoped = new AccountAwareStorageBackend<>(raw, null, "000000000000");
        KinesisService service = new KinesisService(scoped, new InMemoryStorage<>(), regions);
        AwsException error = assertThrows(AwsException.class, () -> service.putResourcePolicy(arn, rejected, REGION));
        assertEquals("InvalidArgumentException", error.getErrorCode());
        assertSame(legacy, raw.get(key).orElseThrow());
        assertEquals(Set.of(key), raw.keys());
        assertEquals(before, mapper.writeValueAsString(legacy));
        assertArrayEquals(committed, Files.readAllBytes(file));
        raw.flush();
        assertArrayEquals(committed, Files.readAllBytes(file));
        PersistentStorage<String, KinesisStream> reloaded = new PersistentStorage<>(file, new TypeReference<>() {});
        reloaded.load();
        assertEquals(Set.of(key), reloaded.keys());
        assertEquals(before, mapper.writeValueAsString(reloaded.get(key).orElseThrow()));
    }
}
