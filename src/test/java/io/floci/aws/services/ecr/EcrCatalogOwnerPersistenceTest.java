package io.floci.aws.services.ecr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.services.ecr.model.Repository;
import io.floci.aws.services.ecr.registry.EcrRegistryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class EcrCatalogOwnerPersistenceTest {
    private static final String OWNER = "111111111111";
    private static final String CALLER = "222222222222";
    private static final String REGION = "us-east-1";
    private static final String NAME = "catalog-owner";
    private static final String KEY = REGION + "::" + OWNER + "::" + NAME;
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistedForeignAliasCannotReplaceOwnerMetadataAcrossRestarts(boolean legacyGeneration) {
        Path file = directory.resolve("repositories.json");
        PersistentStorage<String, Repository> disk = disk(file);
        Repository owner = repository(legacyGeneration ? null : "owner-generation");
        owner.setEncryptionType("KMS");
        owner.setKmsKey("owner-key");
        owner.setImageTagMutability("IMMUTABLE");
        owner.setScanOnPush(true);
        owner.setRepositoryPolicyText("{\"Statement\":[]}");
        owner.setLifecyclePolicyText("{\"rules\":[]}");
        owner.setTags(Map.of("managed-by", "cloudformation"));
        disk.put(OWNER + "/" + KEY, owner);
        Repository alias = repository("stale-alias-generation");
        alias.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        disk.put(CALLER + "/" + KEY, alias);

        for (int restart = 0; restart < 2; restart++) {
            disk = disk(file);
            AccountAwareStorageBackend<Repository> store = new AccountAwareStorageBackend<>(disk, null, CALLER);
            service(store).reconcileFromCatalog(List.of(OWNER + "/" + REGION + "/" + NAME));
            Repository actual = store.getForAccount(OWNER, KEY).orElseThrow();
            Repository metadata = new Repository(actual);
            metadata.setRepositoryUri(owner.getRepositoryUri());
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            assertEquals(mapper.valueToTree(owner), mapper.valueToTree(metadata));
            assertAll(
                    () -> assertEquals(owner.getRepositoryGeneration(), actual.getRepositoryGeneration()),
                    () -> assertEquals(owner.getCreatedAt(), actual.getCreatedAt()),
                    () -> assertEquals(owner.getRepositoryPolicyText(), actual.getRepositoryPolicyText()),
                    () -> assertEquals(owner.getLifecyclePolicyText(), actual.getLifecyclePolicyText()),
                    () -> assertEquals(owner.getTags(), actual.getTags()),
                    () -> assertEquals("https://registry.example/" + NAME, actual.getRepositoryUri()));
            assertEquals("stale-alias-generation", store.getForAccount(CALLER, KEY)
                    .orElseThrow().getRepositoryGeneration());
        }
    }

    @Test
    void foreignAliasCannotSeedPolicyOrLifetimeForMissingOwner() {
        AccountAwareStorageBackend<Repository> store = AccountAwareStorageBackend.inMemory(CALLER);
        Repository alias = repository("foreign-generation");
        alias.setRepositoryPolicyText("foreign-policy");
        alias.setTags(Map.of("untrusted", "alias"));
        store.put(KEY, alias);
        service(store).reconcileFromCatalog(List.of(OWNER + "/" + REGION + "/" + NAME));
        Repository created = store.getForAccount(OWNER, KEY).orElseThrow();
        assertNotEquals(alias.getRepositoryGeneration(), created.getRepositoryGeneration());
        assertNull(created.getRepositoryPolicyText());
        assertTrue(created.getTags().isEmpty());
    }

    @Test
    void unscopedDefaultOwnerMetadataSurvivesCatalogRecovery() {
        Path file = directory.resolve("legacy.json");
        PersistentStorage<String, Repository> disk = disk(file);
        Repository legacy = repository(null);
        legacy.setRepositoryPolicyText("legacy-policy");
        legacy.setTags(Map.of("legacy", "owner"));
        disk.put(KEY, legacy);
        AccountAwareStorageBackend<Repository> store = new AccountAwareStorageBackend<>(disk(file), null, OWNER);
        service(store).reconcileFromCatalog(List.of(OWNER + "/" + REGION + "/" + NAME));
        Repository recovered = store.getForAccount(OWNER, KEY).orElseThrow();
        assertEquals(legacy.getRepositoryPolicyText(), recovered.getRepositoryPolicyText());
        assertEquals(legacy.getTags(), recovered.getTags());
        assertEquals(legacy.getCreatedAt(), recovered.getCreatedAt());
        assertNull(recovered.getRepositoryGeneration());
    }

    private PersistentStorage<String, Repository> disk(Path file) {
        PersistentStorage<String, Repository> storage = new PersistentStorage<>(file, new TypeReference<>() {});
        storage.load();
        return storage;
    }

    private Repository repository(String generation) {
        Repository repository = new Repository();
        repository.setRegistryId(OWNER);
        repository.setRepositoryName(NAME);
        repository.setRepositoryArn("arn:aws:ecr:" + REGION + ":" + OWNER + ":repository/" + NAME);
        repository.setRepositoryGeneration(generation);
        repository.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));
        return repository;
    }

    private EcrService service(AccountAwareStorageBackend<Repository> store) {
        EcrRegistryManager manager = mock(EcrRegistryManager.class);
        when(manager.getRepositoryUri(anyString(), anyString(), anyString()))
                .thenReturn("https://registry.example/" + NAME);
        return new EcrService(store, new InMemoryStorage<>(), new InMemoryStorage<>(), manager,
                mock(EmulatorConfig.class), new RegionResolver(REGION, store.accountId()));
    }
}
