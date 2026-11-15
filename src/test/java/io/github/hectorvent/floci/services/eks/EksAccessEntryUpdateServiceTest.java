package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.eks.model.AccessConfig;
import io.github.hectorvent.floci.services.eks.model.AccessEntry;
import io.github.hectorvent.floci.services.eks.model.AccessScope;
import io.github.hectorvent.floci.services.eks.model.AssociateAccessPolicyRequest;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreateAccessEntryRequest;
import io.github.hectorvent.floci.services.eks.model.UpdateAccessEntryRequest;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.IamRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EksAccessEntryUpdateServiceTest {
    static final String ACCOUNT = "123456789012";
    static final String PRINCIPAL = "arn:aws:iam::123456789012:role/path/worker";
    static final Instant NOW = Instant.parse("2026-11-12T20:00:00Z");

    @Test
    void patchesPreserveIdentityTagsPoliciesAndCreateToken() {
        InMemoryStorage<String, EksAccessEntryService.StoredEntry> store = new InMemoryStorage<>();
        Fixture fixture = fixture(store, "aws", "us-east-1");
        AccessEntry created = create(fixture, "STANDARD");
        String policy = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSViewPolicy";
        fixture.service.associate(fixture.cluster, PRINCIPAL,
                new AssociateAccessPolicyRequest(policy, new AccessScope("namespace", List.of("team"))));
        EksAccessEntryService.StoredEntry before = store.scan(key -> true).getFirst();
        EksAccessEntryService service = new EksAccessEntryService(store, fixture.iam,
                Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC));
        AccessEntry groups = service.update(ACCOUNT, fixture.cluster, PRINCIPAL,
                new UpdateAccessEntryRequest(null, List.of("readers", "writers"), "patch-token"));
        assertEquals(created.username(), groups.username());
        assertEquals(List.of("readers", "writers"), groups.kubernetesGroups());
        AccessEntry username = service.update(ACCOUNT, fixture.cluster, PRINCIPAL,
                new UpdateAccessEntryRequest("team:{{SessionNameRaw}}", null, null));
        assertEquals(groups.kubernetesGroups(), username.kubernetesGroups());
        AccessEntry both = service.update(ACCOUNT, fixture.cluster, PRINCIPAL,
                new UpdateAccessEntryRequest("release-user", List.of("release"), null));
        assertEquals("release-user", both.username());
        assertEquals(List.of("release"), both.kubernetesGroups());
        assertIdentity(created, both);
        assertEquals(NOW.plusSeconds(10).toEpochMilli() / 1000.0, both.modifiedAt());
        EksAccessEntryService.StoredEntry after = store.scan(key -> true).getFirst();
        assertEquals(before.principalId(), after.principalId());
        assertEquals(before.clientRequestToken(), after.clientRequestToken());
        assertEquals(before.accessPolicies(), after.accessPolicies());
        assertEquals("original", created.username());
        assertEquals(List.of("initial"), created.kubernetesGroups());
    }

    @Test
    void omittedAndEmptyGroupsHaveDifferentMeanings() throws Exception {
        Fixture fixture = fixture(new InMemoryStorage<>(), "aws", "us-east-1");
        AccessEntry created = create(fixture, "STANDARD");
        ObjectMapper mapper = new ObjectMapper();
        AccessEntry omitted = fixture.service.update(ACCOUNT, fixture.cluster, PRINCIPAL,
                UpdateAccessEntryRequest.fromJson(mapper.readTree("{}")));
        assertEquals(created, omitted);
        AccessEntry cleared = fixture.service.update(ACCOUNT, fixture.cluster, PRINCIPAL,
                UpdateAccessEntryRequest.fromJson(mapper.readTree("{\"kubernetesGroups\":[]}")));
        assertEquals(List.of(), cleared.kubernetesGroups());
        assertEquals(created.username(), cleared.username());
        AccessEntry ignored = fixture.service.update(ACCOUNT, fixture.cluster, PRINCIPAL, UpdateAccessEntryRequest.fromJson(
                mapper.readTree("{\"username\":null,\"kubernetesGroups\":null,\"type\":\"EC2_LINUX\",\"principalArn\":\"other\"}")));
        assertEquals(cleared, ignored);
        assertEquals("STANDARD", ignored.type());
        assertEquals(PRINCIPAL, ignored.principalArn());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "system:admin", "eks:admin", "aws:admin", "amazon:admin", "iam:admin",
            "{{SessionName}}", "prefix{{SessionNameRaw}}", "system:\nadmin"})
    void invalidUsernameRefusesEntirePatch(String username) {
        InMemoryStorage<String, EksAccessEntryService.StoredEntry> store = new InMemoryStorage<>();
        Fixture fixture = fixture(store, "aws", "us-east-1");
        create(fixture, "STANDARD");
        EksAccessEntryService.StoredEntry before = store.scan(key -> true).getFirst();
        AwsException error = assertThrows(AwsException.class, () -> fixture.service.update(ACCOUNT, fixture.cluster, PRINCIPAL,
                new UpdateAccessEntryRequest(username, List.of("changed"), null)));
        assertEquals("InvalidParameterException", error.getErrorCode());
        assertEquals(before, store.scan(key -> true).getFirst());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "{\"username\":1}", "{\"username\":{}}",
            "{\"kubernetesGroups\":\"group\"}", "{\"kubernetesGroups\":[null]}",
            "{\"kubernetesGroups\":[1]}", "{\"clientRequestToken\":false}"})
    void malformedWireTypesCannotBeCoercedIntoAWrite(String json) throws Exception {
        InMemoryStorage<String, EksAccessEntryService.StoredEntry> store = new InMemoryStorage<>();
        Fixture fixture = fixture(store, "aws", "us-east-1");
        create(fixture, "STANDARD");
        EksAccessEntryService.StoredEntry before = store.scan(key -> true).getFirst();
        assertThrows(AwsException.class, () -> fixture.service.update(ACCOUNT, fixture.cluster, PRINCIPAL,
                UpdateAccessEntryRequest.fromJson(new ObjectMapper().readTree(json))));
        assertEquals(before, store.scan(key -> true).getFirst());
    }

    @Test
    void invalidGroupsAndNodeOverridesCannotPartiallyChangeUsername() {
        Fixture fixture = fixture(new InMemoryStorage<>(), "aws", "us-east-1");
        AccessEntry created = create(fixture, "STANDARD");
        assertThrows(AwsException.class, () -> fixture.service.update(ACCOUNT, fixture.cluster, PRINCIPAL,
                new UpdateAccessEntryRequest("new-user", List.of(""), null)));
        assertEquals(created, fixture.service.describe(fixture.cluster, PRINCIPAL));
        fixture.service.delete(fixture.cluster, PRINCIPAL);
        AccessEntry node = create(fixture, "EC2_LINUX");
        for (UpdateAccessEntryRequest request : List.of(new UpdateAccessEntryRequest("node-user", null, null),
                new UpdateAccessEntryRequest(null, List.of(), null))) {
            assertThrows(AwsException.class, () -> fixture.service.update(ACCOUNT, fixture.cluster, PRINCIPAL, request));
            assertEquals(node, fixture.service.describe(fixture.cluster, PRINCIPAL));
        }
    }

    @Test
    void missingEntryAndInactiveOrConfigMapClusterRefuse() {
        Fixture fixture = fixture(new InMemoryStorage<>(), "aws", "us-east-1");
        UpdateAccessEntryRequest request = new UpdateAccessEntryRequest("new-user", null, null);
        assertEquals(404, assertThrows(AwsException.class,
                () -> fixture.service.update(ACCOUNT, fixture.cluster, PRINCIPAL, request)).getHttpStatus());
        create(fixture, "STANDARD");
        fixture.cluster.setStatus(ClusterStatus.DELETING);
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> fixture.service.update(ACCOUNT, fixture.cluster, PRINCIPAL, request)).getErrorCode());
        fixture.cluster.setStatus(ClusterStatus.ACTIVE);
        fixture.cluster.setAccessConfig(new AccessConfig("CONFIG_MAP", false));
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> fixture.service.update(ACCOUNT, fixture.cluster, PRINCIPAL, request)).getErrorCode());
    }

    @ParameterizedTest
    @CsvSource({"aws,us-east-1", "aws-cn,cn-north-1", "aws-us-gov,us-gov-west-1", "aws-iso,us-iso-east-1",
            "aws-iso-b,us-isob-east-1", "aws-iso-e,eu-isoe-west-1", "aws-iso-f,us-isof-south-1", "aws-eusc,eusc-de-east-1"})
    void keepsExactIdentityAcrossPartitions(String partition, String region) {
        Fixture fixture = fixture(new InMemoryStorage<>(), partition, region);
        AccessEntry created = create(fixture, "STANDARD");
        AccessEntry updated = fixture.service.update(ACCOUNT, fixture.cluster, fixture.principal,
                new UpdateAccessEntryRequest("updated", null, null));
        assertIdentity(created, updated);
        assertTrue(updated.accessEntryArn().startsWith("arn:" + partition + ":eks:" + region + ":" + ACCOUNT + ":"));
    }

    @Test
    void accountRegionAndRecreatedClusterDoNotShareEntries() {
        InMemoryStorage<String, EksAccessEntryService.StoredEntry> raw = new InMemoryStorage<>();
        AccountAwareStorageBackend<EksAccessEntryService.StoredEntry> first =
                new AccountAwareStorageBackend<>(raw, null, ACCOUNT);
        Fixture fixture = fixture(first, "aws", "us-east-1");
        AccessEntry original = create(fixture, "STANDARD");
        Fixture other = fixture(new AccountAwareStorageBackend<>(raw, null, "999999999999"), "aws", "us-east-1");
        UpdateAccessEntryRequest patch = new UpdateAccessEntryRequest("changed", null, null);
        assertThrows(AwsException.class, () -> other.service.update("999999999999", other.cluster, PRINCIPAL, patch));
        Cluster cluster = fixture.cluster;
        cluster.setArn(cluster.getArn().replace("us-east-1", "us-west-2"));
        assertThrows(AwsException.class, () -> fixture.service.update(ACCOUNT, cluster, PRINCIPAL, patch));
        cluster.setArn(cluster.getArn().replace("us-west-2", "us-east-1"));
        cluster.setCreatedAt(NOW.plusSeconds(1));
        assertThrows(AwsException.class, () -> fixture.service.update(ACCOUNT, cluster, PRINCIPAL, patch));
        cluster.setCreatedAt(NOW);
        assertEquals(original, fixture.service.describe(cluster, PRINCIPAL));
        assertEquals(1, raw.keys().size());
    }

    @Test
    void prewriteFailureLeavesPublishedSnapshotUntouched() {
        InMemoryStorage<String, EksAccessEntryService.StoredEntry> store = new InMemoryStorage<>() {
            @Override
            public void putAllAtomically(Map<String, EksAccessEntryService.StoredEntry> values) {
                throw new IllegalStateException("owned prewrite refusal");
            }
        };
        Fixture fixture = fixture(store, "aws", "us-east-1");
        AccessEntry before = create(fixture, "STANDARD");
        assertThrows(IllegalStateException.class, () -> fixture.service.update(ACCOUNT, fixture.cluster, PRINCIPAL,
                new UpdateAccessEntryRequest("changed", List.of("changed"), null)));
        assertSame(before, fixture.service.describe(fixture.cluster, PRINCIPAL));
    }

    @Test
    void overlappingPartialUpdatesSerializeWithoutLosingEitherField() throws Exception {
        CountDownLatch pending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InMemoryStorage<String, EksAccessEntryService.StoredEntry> store = new InMemoryStorage<>() {
            private boolean first = true;
            @Override
            public void putAllAtomically(Map<String, EksAccessEntryService.StoredEntry> values) {
                if (first) {
                    first = false;
                    pending.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("write was not released");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                }
                super.putAllAtomically(values);
            }
        };
        Fixture fixture = fixture(store, "aws", "us-east-1");
        AccessEntry original = create(fixture, "STANDARD");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AccessEntry> username = executor.submit(() -> fixture.service.update(ACCOUNT, fixture.cluster, PRINCIPAL,
                    new UpdateAccessEntryRequest("concurrent-user", null, null)));
            try {
                assertTrue(pending.await(5, TimeUnit.SECONDS));
                assertSame(original, store.scan(key -> true).getFirst().entry());
                Future<AccessEntry> groups = executor.submit(() -> fixture.service.update(ACCOUNT, fixture.cluster, PRINCIPAL,
                        new UpdateAccessEntryRequest(null, List.of("concurrent-group"), null)));
                release.countDown();
                username.get(5, TimeUnit.SECONDS);
                groups.get(5, TimeUnit.SECONDS);
                AccessEntry finalEntry = fixture.service.describe(fixture.cluster, PRINCIPAL);
                assertEquals("concurrent-user", finalEntry.username());
                assertEquals(List.of("concurrent-group"), finalEntry.kubernetesGroups());
            } finally {
                release.countDown();
            }
        }
    }

    static AccessEntry create(Fixture fixture, String type) {
        return fixture.service.create(fixture.cluster, new CreateAccessEntryRequest(fixture.principal, type,
                "STANDARD".equals(type) ? "original" : null,
                "STANDARD".equals(type) ? List.of("initial") : null, Map.of("team", "platform"), "create-token"));
    }

    static Fixture fixture(StorageBackend<String, EksAccessEntryService.StoredEntry> store,
                           String partition, String region) {
        String principal = PRINCIPAL.replace("arn:aws:", "arn:" + partition + ":");
        Cluster cluster = new Cluster();
        cluster.setName("update-entries");
        cluster.setArn("arn:" + partition + ":eks:" + region + ":" + ACCOUNT + ":cluster/update-entries");
        cluster.setAccountId(ACCOUNT);
        cluster.setCreatedAt(NOW);
        cluster.setStatus(ClusterStatus.ACTIVE);
        cluster.setAccessConfig(new AccessConfig("API", false));
        IamService iam = mock(IamService.class);
        when(iam.findRole(ACCOUNT, "worker")).thenReturn(Optional.of(
                new IamRole("stable-principal", "worker", "/path/", principal, "{}")));
        return new Fixture(cluster, principal, iam,
                new EksAccessEntryService(store, iam, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    static void assertIdentity(AccessEntry before, AccessEntry after) {
        assertEquals(before.accessEntryArn(), after.accessEntryArn());
        assertEquals(before.clusterName(), after.clusterName());
        assertEquals(before.principalArn(), after.principalArn());
        assertEquals(before.type(), after.type());
        assertEquals(before.tags(), after.tags());
        assertEquals(before.createdAt(), after.createdAt());
    }

    record Fixture(Cluster cluster, String principal, IamService iam, EksAccessEntryService service) {}
}
