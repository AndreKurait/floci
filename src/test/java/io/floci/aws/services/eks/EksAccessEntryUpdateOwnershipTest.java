package io.floci.aws.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.eks.model.AccessEntry;
import io.floci.aws.services.eks.model.Cluster;
import io.floci.aws.services.eks.model.UpdateAccessEntryRequest;
import io.floci.aws.services.iam.ResourceArnBuilder;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.services.eks.EksAccessEntryUpdateServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EksAccessEntryUpdateOwnershipTest {
    @Test
    void wrongAccountRegionAndAccountPrefixedAliasesCannotMigrateLegacyRecords() {
        InMemoryStorage<String, EksAccessEntryService.StoredEntry> rawEntries = new InMemoryStorage<>();
        Fixture fixture = fixture(rawEntries, "aws", "us-east-1");
        AccessEntry original = create(fixture, "STANDARD");
        String key = rawEntries.keys().iterator().next();
        EksAccessEntryService.StoredEntry stored = rawEntries.get(key).orElseThrow();
        InMemoryStorage<String, Cluster> rawClusters = new InMemoryStorage<>();
        rawClusters.put(fixture.cluster().getName(), fixture.cluster());
        rawClusters.put("999999999999/foreign", fixture.cluster());
        Set<String> clusterKeys = rawClusters.keys();
        for (String[] scope : new String[][] {{"999999999999", "us-east-1"}, {ACCOUNT, "us-west-2"}}) {
            EksService eks = service(scope[0], scope[1], rawClusters, rawEntries, fixture);
            try {
                assertTrue(eks.findAccessEntryUpdateTarget(fixture.cluster().getName(), PRINCIPAL).isEmpty());
                assertEquals(404, assertThrows(AwsException.class, () -> eks.updateAccessEntryInRequestRegion(
                        fixture.cluster().getName(), PRINCIPAL, new UpdateAccessEntryRequest("foreign", null, null)))
                        .getHttpStatus());
                assertEquals(clusterKeys, rawClusters.keys());
                assertEquals(Set.of(key), rawEntries.keys());
                assertSame(stored, rawEntries.get(key).orElseThrow());
            } finally {
                eks.shutdown();
            }
        }
        EksService owner = service(ACCOUNT, "us-east-1", rawClusters, rawEntries, fixture);
        try {
            assertTrue(owner.findAccessEntryUpdateTarget("999999999999/foreign", PRINCIPAL).isEmpty());
            assertEquals(clusterKeys, rawClusters.keys());
            // IAM resolves this target before deciding whether the caller is allowed to update it.
            assertEquals(original, owner.findAccessEntryUpdateTarget(fixture.cluster().getName(), PRINCIPAL).orElseThrow());
            assertIamResourceAndTagLookup(owner, original);
            assertEquals(clusterKeys, rawClusters.keys());
            assertEquals(Set.of(key), rawEntries.keys());
            assertSame(stored, rawEntries.get(key).orElseThrow());
            AccessEntry updated = owner.updateAccessEntryInRequestRegion(fixture.cluster().getName(), PRINCIPAL,
                    new UpdateAccessEntryRequest("legacy-owner", null, null));
            assertEquals("legacy-owner", updated.username());
            assertEquals(Set.of(ACCOUNT + "/" + key), rawEntries.keys());
            assertFalse(rawClusters.keys().contains(fixture.cluster().getName()));
            assertTrue(rawClusters.keys().contains(ACCOUNT + "/" + fixture.cluster().getName()));
            assertIdentity(original, updated);
            assertEquals("stable-principal", rawEntries.get(ACCOUNT + "/" + key).orElseThrow().principalId());
        } finally {
            owner.shutdown();
        }
    }

    @Test
    void concurrentParentDeletionWaitsForEntryCommitAndDoesNotResurrectIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryStorage<String, EksAccessEntryService.StoredEntry> rawEntries = new InMemoryStorage<>() {
            @Override
            public void putAllAtomically(Map<String, EksAccessEntryService.StoredEntry> values) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("entry write was not released");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                super.putAllAtomically(values);
            }
        };
        Fixture fixture = fixture(rawEntries, "aws", "us-east-1");
        create(fixture, "STANDARD");
        InMemoryStorage<String, Cluster> rawClusters = new InMemoryStorage<>();
        rawClusters.put(fixture.cluster().getName(), fixture.cluster());
        EksService owner = service(ACCOUNT, "us-east-1", rawClusters, rawEntries, fixture);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AccessEntry> update = executor.submit(() -> owner.updateAccessEntryInRequestRegion(
                    fixture.cluster().getName(), PRINCIPAL, new UpdateAccessEntryRequest("committed", null, null)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Future<Cluster> deletion = executor.submit(() -> owner.deleteClusterInRequestRegion(fixture.cluster().getName()));
                release.countDown();
                assertEquals("committed", update.get(5, TimeUnit.SECONDS).username());
                deletion.get(5, TimeUnit.SECONDS);
                assertTrue(rawClusters.isEmpty());
                assertTrue(rawEntries.isEmpty());
                assertEquals(404, assertThrows(AwsException.class, () -> owner.updateAccessEntryInRequestRegion(
                        fixture.cluster().getName(), PRINCIPAL, new UpdateAccessEntryRequest("resurrect", null, null)))
                        .getHttpStatus());
                assertTrue(rawEntries.isEmpty());
            } finally {
                release.countDown();
            }
        } finally {
            owner.shutdown();
        }
    }

    private static void assertIamResourceAndTagLookup(EksService owner, AccessEntry entry) {
        @SuppressWarnings("unchecked")
        Instance<EksService> instance = mock(Instance.class);
        when(instance.get()).thenReturn(owner);
        ResourceArnBuilder builder = new ResourceArnBuilder(new ObjectMapper(), null, null, null, instance);
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        String path = "/clusters/" + entry.clusterName() + "/access-entries/" + entry.principalArn();
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn(path);
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost" + path));
        when(uriInfo.getBaseUri()).thenReturn(URI.create("http://localhost/"));
        assertEquals(List.of(entry.accessEntryArn()), builder.buildResources(
                "eks", "eks:UpdateAccessEntry", context, "us-east-1", ACCOUNT, "aws"));
        Map<String, List<String>> conditions = new EksAccessEntryUpdateConditions(owner, builder).resolve(context);
        assertEquals(List.of("platform"), conditions.get("aws:ResourceTag/team"));
        assertEquals(List.of(entry.principalArn()), conditions.get("eks:principalArn"));
    }

    private static EksService service(String account, String region, InMemoryStorage<String, Cluster> rawClusters,
                                      InMemoryStorage<String, EksAccessEntryService.StoredEntry> rawEntries, Fixture fixture) {
        AccountAwareStorageBackend<Cluster> clusters = new AccountAwareStorageBackend<>(rawClusters, null, ACCOUNT);
        AccountAwareStorageBackend<EksAccessEntryService.StoredEntry> entries =
                new AccountAwareStorageBackend<>(rawEntries, null, ACCOUNT);
        StorageFactory factory = new StorageFactory(null, null) {
            @Override
            @SuppressWarnings("unchecked")
            public <V> AccountAwareStorageBackend<V> create(String service, String file, TypeReference<Map<String, V>> type) {
                if ("eks-clusters.json".equals(file)) {
                    return (AccountAwareStorageBackend<V>) clusters;
                }
                return AccountAwareStorageBackend.inMemory(account);
            }
        };
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().eks().mock()).thenReturn(true);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.defaultRegion()).thenReturn("us-east-1");
        return new EksService(factory, config, new RegionResolver(region, account), null, null,
                mock(EksOidcService.class), new EksAccessEntryService(entries, fixture.iam()), null);
    }
}
