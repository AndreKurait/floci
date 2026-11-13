package io.github.hectorvent.floci.services.kinesis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.services.kinesis.model.KinesisConsumer;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static io.github.hectorvent.floci.services.kinesis.KinesisResourcePolicyServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

class KinesisRegistrationOwnershipServiceTest {
    @ParameterizedTest
    @ValueSource(strings = {"foreign-selector", "foreign-stored-arn", "conflicting-stored-account"})
    void refusedRegistrationCannotMoveLegacyParentOrPublishConsumer(String scenario, @TempDir Path directory)
            throws Exception {
        String other = "222222222222";
        String requested = "arn:aws:kinesis:" + REGION + ":" + ACCOUNT + ":stream/legacy";
        if (scenario.equals("foreign-selector")) {
            requested = requested.replace(ACCOUNT, other);
        }
        String storedArn = scenario.equals("foreign-stored-arn") ? requested.replace(ACCOUNT, other) : requested;
        KinesisStream legacy = new KinesisStream("legacy", storedArn);
        legacy.setAccountId(other);
        legacy.setTags(Map.of("owner", "retained"));
        legacy.setResourcePolicies(Map.of(storedArn, POLICY));
        Path file = directory.resolve("streams.json");
        PersistentStorage<String, KinesisStream> raw = new PersistentStorage<>(file, new TypeReference<>() {});
        raw.load();
        String key = REGION + "::legacy";
        raw.put(key, legacy);
        raw.flush();
        byte[] before = Files.readAllBytes(file);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String serialized = mapper.writeValueAsString(legacy);
        AccountAwareStorageBackend<KinesisStream> streams = new AccountAwareStorageBackend<>(raw, null, ACCOUNT);
        InMemoryStorage<String, KinesisConsumer> consumers = new InMemoryStorage<>();
        KinesisService service = new KinesisService(streams, consumers, new RegionResolver(REGION, ACCOUNT));
        String target = requested;
        AwsException refusal = assertThrows(AwsException.class,
                () -> service.registerStreamConsumer(target, "child", REGION));
        assertEquals("ResourceNotFoundException", refusal.getErrorCode());
        assertEquals(400, refusal.getHttpStatus());
        assertTrue(consumers.keys().isEmpty());
        assertEquals(Set.of(key), raw.keys());
        assertSame(legacy, raw.get(key).orElseThrow());
        assertTrue(streams.getForAccount(ACCOUNT, key).isEmpty());
        assertEquals(serialized, mapper.writeValueAsString(legacy));
        assertArrayEquals(before, Files.readAllBytes(file));
        raw.flush();
        assertArrayEquals(before, Files.readAllBytes(file));
    }
}
