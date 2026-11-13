package io.github.hectorvent.floci.services.ecr;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ecr.model.ImageMetadata;
import io.github.hectorvent.floci.services.ecr.model.Repository;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.github.hectorvent.floci.services.ecr.EcrLifecyclePreviewSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EcrLifecyclePreviewOwnershipTest {

    @Test
    @SuppressWarnings("unchecked")
    void actualCallerPartitionCannotReadOrMigrateDefaultLegacyOrForeignInnerRows() throws Exception {
        String other = "111111111111";
        InMemoryStorage<String, Repository> rows = new InMemoryStorage<>();
        InMemoryStorage<String, ImageMetadata> images = new InMemoryStorage<>();
        try (EcrLifecyclePreviewSupport owner = new EcrLifecyclePreviewSupport(rows, images)) {
            Repository legacy = new Repository(owner.create());
            legacy.setRepositoryGeneration(null);
            rows.put(KEY, legacy);
            rows.delete(ACCOUNT + "/" + KEY);
            RequestContext request = new RequestContext();
            request.setAccountId(other);
            Instance<RequestContext> context = mock(Instance.class);
            when(context.get()).thenReturn(request);
            AccountAwareStorageBackend<Repository> repositories = new AccountAwareStorageBackend<>(rows, context, ACCOUNT);
            AccountAwareStorageBackend<ImageMetadata> metadata = new AccountAwareStorageBackend<>(images, context, ACCOUNT);
            EcrService caller = new EcrService(repositories, metadata, new InMemoryStorage<>(), owner.manager,
                    mock(EmulatorConfig.class), new RegionResolver(REGION, ACCOUNT));
            Set<String> before = Set.copyOf(rows.keys());
            assertTrue(caller.lifecyclePreviewTags(NAME, null, REGION).isEmpty());
            assertEquals("RepositoryNotFoundException", assertThrows(AwsException.class,
                    () -> caller.getLifecyclePolicyPreview(NAME, null, List.of(), null, null, null, REGION)).getErrorCode());
            assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                    () -> caller.startLifecyclePolicyPreview(NAME, ACCOUNT, countPolicy(), REGION)).getErrorCode());
            assertEquals(before, rows.keys());
            assertSame(legacy, rows.get(KEY).orElseThrow());
            String logical = REGION + "::" + other + "::" + NAME;
            rows.put(other + "/" + logical, new Repository(legacy));
            assertTrue(caller.lifecyclePreviewTags(NAME, null, REGION).isEmpty());
            Repository valid = new Repository(legacy);
            valid.setRegistryId(other);
            valid.setRepositoryArn(AwsArnUtils.Arn.of("ecr", REGION, other, "repository/" + NAME).toString());
            valid.setRepositoryGeneration(UUID.randomUUID().toString());
            valid.setTags(Map.of("team", "other-account"));
            rows.put(other + "/" + logical, valid);
            assertEquals(Map.of("team", "other-account"), caller.lifecyclePreviewTags(NAME, null, REGION));
            caller.startLifecyclePolicyPreview(NAME, null, countPolicy(), REGION);
            assertNotNull(rows.get(other + "/" + logical).orElseThrow().getLifecyclePolicyPreview());
            assertNull(legacy.getLifecyclePolicyPreview());
            assertNull(legacy.getRepositoryGeneration());
            assertSame(legacy, rows.get(KEY).orElseThrow());
            assertTrue(caller.lifecyclePreviewTags(NAME, null, "us-west-2").isEmpty());
        }
    }
}
