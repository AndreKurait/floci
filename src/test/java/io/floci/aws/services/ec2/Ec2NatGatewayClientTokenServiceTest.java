package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.PersistentStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.model.Address;
import io.floci.aws.services.ec2.model.NatGateway;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.ec2.model.Tag;
import io.floci.aws.services.ec2.net.VpcNetworkManager;
import io.floci.aws.services.ec2.portforward.Ec2PortForwardManager;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Ec2NatGatewayClientTokenServiceTest {
    private static final String ACCOUNT = "111111111111";
    private static final String OTHER_ACCOUNT = "222222222222";
    private static final String REGION = "us-east-2";
    private static final String OTHER_REGION = "us-west-2";
    private static final String NAT_FILE = "ec2-nat-gateways.json";
    private static final String ADDRESS_FILE = "ec2-addresses.json";
    private static final String TAG_FILE = "ec2-tags.json";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @TempDir
    Path directory;

    @Test
    void concurrentIdenticalPrivateRequestsCommitExactlyOneGateway() throws Exception {
        try (Fixture fixture = new Fixture("memory", directory);
                ExecutorService workers = Executors.newFixedThreadPool(8)) {
            Subnet subnet = fixture.subnet(REGION, REGION + "a", "10.198.1.0/24");
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<NatGateway>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return fixture.create(subnet, "10.198.1.40", "Concurrent Token ", creationTags());
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            NatGateway first = results.getFirst().get(10, TimeUnit.SECONDS);
            JsonNode expected = JSON.valueToTree(first);
            for (Future<NatGateway> result : results) {
                assertEquals(expected, JSON.valueToTree(result.get(10, TimeUnit.SECONDS)));
            }
            assertEquals(1, fixture.factory.atomicAttempts.get());
            assertEquals(1, fixture.factory.atomicCommits.get());
            assertEquals(1, fixture.gateways(REGION).size());
            assertEquals(1, fixture.factory.nats().scanAllAccounts().size());
            assertEquals("Concurrent Token ", first.getClientToken());
            assertEquals(ACCOUNT, first.getCreationAccountId());
            assertEquals(REGION + "a", first.getCreationAvailabilityZone());
            assertEquals(List.of("10.198.1.40"), privateIps(first));
            verify(fixture.factory.nats(), never()).put(anyString(), any());
        }
    }

    @Test
    void tokenCaseWhitespaceAndEmptyStringRemainDistinctLiteralKeys() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Subnet subnet = fixture.subnet(REGION, REGION + "a", "10.198.1.0/24");
            Set<String> ids = new LinkedHashSet<>();
            for (String token : List.of("Token", "token", " Token", "Token ", "", " ")) {
                NatGateway created = fixture.create(subnet, null, token, List.of());
                JsonNode before = fixture.factory.snapshot();
                NatGateway replay = fixture.create(subnet, null, token, List.of());
                assertEquals(token, created.getClientToken());
                assertEquals(JSON.valueToTree(created), JSON.valueToTree(replay));
                assertEquals(before, fixture.factory.snapshot());
                assertTrue(ids.add(created.getNatGatewayId()));
            }
            assertEquals(6, ids.size());
            assertEquals(6, fixture.factory.atomicCommits.get());
            assertEquals(ids, gatewayIds(fixture.gateways(REGION)));
        }
    }

    @Test
    void sixtyFourAsciiCharactersAreAcceptedWithoutTrimming() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Subnet subnet = fixture.subnet(REGION, REGION + "a", "10.198.1.0/24");
            String token = " " + "A".repeat(62) + " ";
            NatGateway first = fixture.create(subnet, null, token, List.of());
            NatGateway trimmed = fixture.create(subnet, null, token.trim(), List.of());
            assertEquals(64, first.getClientToken().length());
            assertEquals(token, first.getClientToken());
            assertNotEquals(first.getNatGatewayId(), trimmed.getNatGatewayId());
            assertEquals(first.getNatGatewayId(), fixture.create(subnet, null, token, List.of()).getNatGatewayId());
            assertEquals(2, fixture.factory.atomicCommits.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"é", "Token😀", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void invalidTokenRefusesBeforeResourceLookupOrAnyStoreMutation(String token) {
        try (Fixture fixture = new Fixture("memory", directory)) {
            JsonNode before = fixture.factory.snapshot();
            AwsException failure = assertThrows(AwsException.class, () -> fixture.service.createNatGateway(
                    REGION, "subnet-absent", null, "private", null, List.of(), List.of(),
                    null, creationTags(), token));
            assertEquals("InvalidParameterValue", failure.getErrorCode());
            assertEquals(400, failure.getHttpStatus());
            assertEquals(before, fixture.factory.snapshot());
            assertEquals(0, fixture.factory.atomicAttempts.get());
        }
    }

    @Test
    void absentTokenRetainsIndependentCreationBehavior() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Subnet subnet = fixture.subnet(REGION, REGION + "a", "10.198.1.0/24");
            NatGateway first = fixture.create(subnet, null, null, List.of());
            NatGateway second = fixture.create(subnet, null, null, List.of());
            assertNull(first.getClientToken());
            assertNull(second.getClientToken());
            assertNotEquals(first.getNatGatewayId(), second.getNatGatewayId());
            assertNotEquals(privateIps(first), privateIps(second));
            assertEquals(2, fixture.gateways(REGION).size());
            assertEquals(0, fixture.factory.atomicAttempts.get());
        }
    }

    @Test
    void sameZoneChangedSubnetOrPrivateIpRefusesWithoutChangingAnyStoredState() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Subnet firstSubnet = fixture.subnet(REGION, REGION + "a", "10.198.1.0/24");
            Subnet sameZone = fixture.subnet(REGION, REGION + "a", "10.198.2.0/24");
            fixture.create(firstSubnet, "10.198.1.40", "Scoped", creationTags());
            JsonNode before = fixture.factory.snapshot();
            mismatch(() -> fixture.create(firstSubnet, "10.198.1.41", "Scoped", creationTags()));
            assertEquals(before, fixture.factory.snapshot());
            mismatch(() -> fixture.create(sameZone, "10.198.2.40", "Scoped", creationTags()));
            assertEquals(before, fixture.factory.snapshot());
            assertEquals(1, fixture.factory.atomicAttempts.get());
        }
    }

    @Test
    void sameTokenInAnotherZoneCreatesAnIndependentGatewayAndReplaysEach() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Subnet firstSubnet = fixture.subnet(REGION, REGION + "a", "10.198.1.0/24");
            Subnet otherZone = fixture.subnet(REGION, REGION + "b", "10.198.2.0/24");
            NatGateway first = fixture.create(firstSubnet, "10.198.1.40", "Scoped", creationTags());
            NatGateway second = fixture.create(otherZone, "10.198.2.40", "Scoped", creationTags());
            assertNotEquals(first.getNatGatewayId(), second.getNatGatewayId());
            assertEquals(REGION + "a", first.getCreationAvailabilityZone());
            assertEquals(REGION + "b", second.getCreationAvailabilityZone());
            JsonNode before = fixture.factory.snapshot();
            assertEquals(JSON.valueToTree(first),
                    JSON.valueToTree(fixture.create(firstSubnet, "10.198.1.40", "Scoped", creationTags())));
            assertEquals(JSON.valueToTree(second),
                    JSON.valueToTree(fixture.create(otherZone, "10.198.2.40", "Scoped", creationTags())));
            assertEquals(before, fixture.factory.snapshot());
            assertEquals(2, fixture.factory.atomicCommits.get());
        }
    }

    @Test
    void accountAndRegionScopeUseTheActualSharedAccountAwareStores() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Subnet east = fixture.subnet(REGION, REGION + "a", "10.198.1.0/24");
            NatGateway first = fixture.create(east, "10.198.1.40", "Scoped", creationTags());
            fixture.account.set(OTHER_ACCOUNT);
            Subnet otherAccountSubnet = fixture.subnet(REGION, REGION + "a", "10.198.1.0/24");
            NatGateway second = fixture.create(otherAccountSubnet, "10.198.1.40", "Scoped", creationTags());
            assertEquals(OTHER_ACCOUNT, fixture.factory.nats().accountId());
            assertEquals(Set.of(second.getNatGatewayId()), gatewayIds(fixture.gateways(REGION)));
            fixture.account.set(ACCOUNT);
            Subnet west = fixture.subnet(OTHER_REGION, OTHER_REGION + "a", "10.198.1.0/24");
            NatGateway third = fixture.create(west, "10.198.1.40", "Scoped", creationTags());
            assertEquals(3, Set.of(first.getNatGatewayId(), second.getNatGatewayId(), third.getNatGatewayId()).size());
            assertEquals(Set.of(first.getNatGatewayId()), gatewayIds(fixture.gateways(REGION)));
            assertEquals(Set.of(third.getNatGatewayId()), gatewayIds(fixture.gateways(OTHER_REGION)));
            assertEquals(3, fixture.factory.nats().scanAllAccounts().size());
            assertEquals(3, fixture.factory.atomicCommits.get());
            assertEquals(ACCOUNT, first.getCreationAccountId());
            assertEquals(OTHER_ACCOUNT, second.getCreationAccountId());
            assertEquals(OTHER_REGION, third.getRegion());
            JsonNode before = fixture.factory.snapshot();
            assertEquals(first.getNatGatewayId(),
                    fixture.create(east, "10.198.1.40", "Scoped", creationTags()).getNatGatewayId());
            fixture.account.set(OTHER_ACCOUNT);
            assertEquals(second.getNatGatewayId(),
                    fixture.create(otherAccountSubnet, "10.198.1.40", "Scoped", creationTags()).getNatGatewayId());
            assertEquals(before, fixture.factory.snapshot());
        }
    }

    @Test
    void mutableResourceTagsDoNotReplaceOriginalCreationParameters() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Subnet subnet = fixture.subnet(REGION, REGION + "a", "10.198.1.0/24");
            NatGateway first = fixture.create(subnet, "10.198.1.40", "Tagged", creationTags());
            fixture.service.createTags(REGION, List.of(first.getNatGatewayId()),
                    List.of(new Tag("creation", "updated")));
            JsonNode before = fixture.factory.snapshot();
            NatGateway replay = fixture.create(subnet, "10.198.1.40", "Tagged", creationTags());
            assertEquals(first.getNatGatewayId(), replay.getNatGatewayId());
            assertEquals(first.getCreateTime(), replay.getCreateTime());
            assertEquals(Map.of("creation", "updated"), tags(replay.getTags()));
            assertEquals(List.of("creation", "original"), replay.getCreationParameters().get("Tags"));
            assertEquals(Map.of("creation", "updated"),
                    tags(fixture.service.resourceTags(REGION, first.getNatGatewayId())));
            mismatch(() -> fixture.create(subnet, "10.198.1.40", "Tagged", List.of(new Tag("creation", "updated"))));
            assertEquals(before, fixture.factory.snapshot());
            assertEquals(1, fixture.factory.atomicAttempts.get());
        }
    }

    @Test
    void callerCollectionsAndReturnedModelsCannotAlterStoredReplayIdentity() {
        try (Fixture fixture = new Fixture("memory", directory)) {
            Subnet subnet = fixture.subnet(REGION, REGION + "a", "10.198.1.0/24");
            List<Tag> supplied = new ArrayList<>(creationTags());
            NatGateway first = fixture.create(subnet, "10.198.1.40", "Detached", supplied);
            JsonNode expected = JSON.valueToTree(first);
            JsonNode before = fixture.factory.snapshot();
            supplied.getFirst().setValue("input-mutated");
            supplied.clear();
            first.getCreationParameters().get("Tags").set(1, "returned-identity-mutated");
            first.getTags().getFirst().setValue("returned-tag-mutated");
            first.getNatGatewayAddresses().getFirst().setPrivateIp("10.198.1.99");
            first.setClientToken("returned-token-mutated");
            assertEquals(expected, JSON.valueToTree(fixture.create(subnet, "10.198.1.40", "Detached", creationTags())));
            assertEquals(before, fixture.factory.snapshot());
        }
    }

    @Test
    void persistentReloadRetainsOriginalRequestIdentityAndCurrentMutableTags() throws Exception {
        Subnet subnet;
        JsonNode expected;
        AccountAwareStorageBackend<NatGateway> oldStore;
        try (Fixture first = new Fixture("persistent", directory)) {
            subnet = first.subnet(REGION, REGION + "a", "10.198.1.0/24");
            NatGateway created = first.create(subnet, "10.198.1.40", "Persistent Token ", creationTags());
            first.service.createTags(REGION, List.of(created.getNatGatewayId()),
                    List.of(new Tag("creation", "updated")));
            expected = JSON.valueToTree(first.gateways(REGION).getFirst());
            oldStore = first.factory.nats();
        }
        JsonNode disk = JSON.readTree(Files.readString(directory.resolve(NAT_FILE)));
        assertEquals(1, disk.size());
        JsonNode stored = disk.elements().next();
        assertEquals("Persistent Token ", stored.path("clientToken").asText());
        assertEquals(ACCOUNT, stored.path("creationAccountId").asText());
        assertEquals(REGION + "a", stored.path("creationAvailabilityZone").asText());
        assertEquals(JSON.valueToTree(List.of("creation", "original")), stored.path("creationParameters").path("Tags"));
        try (Fixture restarted = new Fixture("persistent", directory)) {
            assertNotSame(oldStore, restarted.factory.nats());
            assertEquals(expected, JSON.valueToTree(restarted.gateways(REGION).getFirst()));
            JsonNode before = restarted.factory.snapshot();
            NatGateway replay = restarted.create(subnet, "10.198.1.40", "Persistent Token ", creationTags());
            assertEquals(expected, JSON.valueToTree(replay));
            assertEquals(Map.of("creation", "updated"), tags(replay.getTags()));
            mismatch(() -> restarted.create(subnet, "10.198.1.41", "Persistent Token ", creationTags()));
            mismatch(() -> restarted.create(subnet, "10.198.1.40", "Persistent Token ",
                    List.of(new Tag("creation", "updated"))));
            assertEquals(before, restarted.factory.snapshot());
            assertEquals(0, restarted.factory.atomicAttempts.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent"})
    void failedInitialAtomicNatWriteRestoresAllEipsIpsAndTagsBeforeRetry(String mode) throws Exception {
        try (Fixture fixture = new Fixture(mode, directory, true)) {
            Subnet subnet = fixture.subnet(REGION, REGION + "a", "10.198.1.0/24");
            NatGateway survivor = fixture.create(subnet, "10.198.1.10", "Survivor", creationTags());
            Address primary = fixture.service.allocateAddress(REGION);
            Address secondary = fixture.service.allocateAddress(REGION);
            fixture.service.createTags(REGION, List.of(primary.getAllocationId()), List.of(new Tag("keep", "primary")));
            Set<String> leases = fixture.leases(subnet, List.of("10.198.1.40", "10.198.1.41"));
            JsonNode before = fixture.factory.snapshot();
            IllegalStateException original = new IllegalStateException("initial NAT commit refused");
            fixture.factory.rejectNext.set(original);
            fixture.factory.beforeCommit.set(gateway -> {
                assertEquals(List.of("10.198.1.40", "10.198.1.41"), privateIps(gateway));
                assertEquals(Set.of("10.198.1.40", "10.198.1.41"), leases);
                for (Address address : fixture.service.describeAddresses(REGION,
                        List.of(primary.getAllocationId(), secondary.getAllocationId()), Map.of())) {
                    assertNotNull(address.getAssociationId());
                    assertNotNull(address.getNetworkInterfaceId());
                    assertNotNull(address.getPrivateIpAddress());
                }
                assertEquals(Map.of("creation", "original"),
                        tags(fixture.service.resourceTags(REGION, gateway.getNatGatewayId())));
            });
            RuntimeException caught = assertThrows(RuntimeException.class,
                    () -> fixture.publicGateway(subnet, primary, secondary, "Retry"));
            assertSame(original, caught);
            assertEquals(0, caught.getSuppressed().length);
            String refusedId = fixture.factory.lastAttempt.get().getNatGatewayId();
            assertEquals(before, fixture.factory.snapshot());
            fixture.factory.assertPersistentSnapshot(before);
            assertTrue(leases.isEmpty());
            verify(fixture.network, times(1)).releasePrivateIp(REGION, subnet.getSubnetId(), "10.198.1.40");
            verify(fixture.network, times(1)).releasePrivateIp(REGION, subnet.getSubnetId(), "10.198.1.41");
            assertEquals(Set.of(survivor.getNatGatewayId()), gatewayIds(fixture.gateways(REGION)));
            fixture.factory.beforeCommit.set(gateway -> {});
            NatGateway retried = fixture.publicGateway(subnet, primary, secondary, "Retry");
            assertNotEquals(refusedId, retried.getNatGatewayId());
            assertEquals(List.of("10.198.1.40", "10.198.1.41"), privateIps(retried));
            assertEquals(Set.of("10.198.1.40", "10.198.1.41"), leases);
            assertEquals(2, fixture.gateways(REGION).size());
            assertEquals(2, fixture.factory.atomicCommits.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneFailingIpCleanupStillAttemptsEveryOtherReleaseAndTagCleanup(boolean reusesOriginalFailure) {
        try (Fixture fixture = new Fixture("memory", directory, true)) {
            Subnet subnet = fixture.subnet(REGION, REGION + "a", "10.198.1.0/24");
            Address primary = fixture.service.allocateAddress(REGION);
            Address secondary = fixture.service.allocateAddress(REGION);
            Set<String> leases = fixture.leases(subnet, List.of("10.198.1.40", "10.198.1.41"));
            JsonNode before = fixture.factory.snapshot();
            IllegalStateException original = new IllegalStateException("initial NAT commit refused");
            IllegalStateException cleanup = reusesOriginalFailure
                    ? original : new IllegalStateException("first IP cleanup reported failure");
            fixture.factory.rejectNext.set(original);
            doAnswer(call -> {
                assertTrue(leases.remove("10.198.1.40"));
                throw cleanup;
            }).when(fixture.network).releasePrivateIp(REGION, subnet.getSubnetId(), "10.198.1.40");
            RuntimeException caught = assertThrows(RuntimeException.class,
                    () -> fixture.publicGateway(subnet, primary, secondary, "Cleanup"));
            assertSame(original, caught);
            assertArrayEquals(reusesOriginalFailure ? new Throwable[0] : new Throwable[]{cleanup},
                    caught.getSuppressed());
            verify(fixture.network, times(1)).releasePrivateIp(REGION, subnet.getSubnetId(), "10.198.1.40");
            verify(fixture.network, times(1)).releasePrivateIp(REGION, subnet.getSubnetId(), "10.198.1.41");
            assertTrue(leases.isEmpty());
            assertEquals(before, fixture.factory.snapshot());
            assertEquals(0, fixture.factory.atomicCommits.get());
        }
    }

    @Test
    void completedDeletionAndPersistentReloadAllowSameTokenToCreateANewOwnedGateway() {
        Subnet subnet;
        String retiredId;
        JsonNode survivor;
        try (Fixture fixture = new Fixture("persistent", directory)) {
            subnet = fixture.subnet(REGION, REGION + "a", "10.198.1.0/24");
            NatGateway first = fixture.create(subnet, "10.198.1.40", "Reused", creationTags());
            retiredId = first.getNatGatewayId();
            survivor = JSON.valueToTree(fixture.create(subnet, "10.198.1.41", "Survivor", creationTags()));
            assertEquals("deleted", fixture.service.deleteNatGateway(REGION, retiredId).getState());
            assertEquals(1, fixture.gateways(REGION).size());
            assertTrue(fixture.service.resourceTags(REGION, retiredId).isEmpty());
        }
        try (Fixture restarted = new Fixture("persistent", directory)) {
            NatGateway replacement = restarted.create(subnet, "10.198.1.40", "Reused", creationTags());
            assertNotEquals(retiredId, replacement.getNatGatewayId());
            assertEquals("Reused", replacement.getClientToken());
            assertEquals(ACCOUNT, replacement.getCreationAccountId());
            assertEquals(2, restarted.gateways(REGION).size());
            assertTrue(restarted.gateways(REGION).stream().map(JSON::valueToTree).anyMatch(survivor::equals));
            AwsException missing = assertThrows(AwsException.class,
                    () -> restarted.service.describeNatGateways(REGION, List.of(retiredId), Map.of()));
            assertEquals("NatGatewayNotFound", missing.getErrorCode());
            assertEquals(400, missing.getHttpStatus());
            assertEquals(1, restarted.factory.atomicCommits.get());
            assertEquals(replacement.getNatGatewayId(),
                    restarted.create(subnet, "10.198.1.40", "Reused", creationTags()).getNatGatewayId());
        }
    }

    private static List<Tag> creationTags() {
        return List.of(new Tag("creation", "original"));
    }

    private static Map<String, String> tags(List<Tag> values) {
        Map<String, String> result = new TreeMap<>();
        values.forEach(tag -> result.put(tag.getKey(), tag.getValue()));
        return result;
    }

    private static List<String> privateIps(NatGateway gateway) {
        return gateway.getNatGatewayAddresses().stream().map(address -> address.getPrivateIp()).toList();
    }

    private static Set<String> gatewayIds(List<NatGateway> gateways) {
        Set<String> result = new LinkedHashSet<>();
        gateways.forEach(gateway -> assertTrue(result.add(gateway.getNatGatewayId())));
        return result;
    }

    private static void mismatch(Runnable request) {
        AwsException failure = assertThrows(AwsException.class, request::run);
        assertEquals("IdempotentParameterMismatch", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
    }

    private static final class Fixture implements AutoCloseable {
        private final ThreadLocal<String> account = ThreadLocal.withInitial(() -> ACCOUNT);
        private final OwnedFactory factory;
        private final Ec2Service service;
        private final VpcNetworkManager network;
        private final Map<String, String> vpcs = new HashMap<>();

        private Fixture(String mode, Path path) {
            this(mode, path, false);
        }

        @SuppressWarnings("unchecked")
        private Fixture(String mode, Path path, boolean allocateLeases) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.defaultAccountId()).thenReturn(ACCOUNT);
            when(config.services().ec2().mock()).thenReturn(true);
            when(config.services().ec2().mockPublicAddresses())
                    .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
            RequestContext context = mock(RequestContext.class);
            when(context.getAccountId()).thenAnswer(call -> account.get());
            Instance<RequestContext> contexts = mock(Instance.class);
            when(contexts.get()).thenReturn(context);
            factory = new OwnedFactory(mode, path, contexts);
            network = allocateLeases ? mock(VpcNetworkManager.class) : null;
            if (network != null) {
                when(network.allocatePrivateIp(anyString(), anyString())).thenReturn(Optional.empty());
            }
            Ec2ImageCatalog catalog = new Ec2ImageCatalog();
            service = new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                    new AmiImageResolver(catalog), catalog, new Ec2InstanceTypeCatalog(), factory,
                    contexts, network, null, null, null);
        }

        private Subnet subnet(String region, String zone, String cidr) {
            String vpc = vpcs.computeIfAbsent(account.get() + "/" + region,
                    ignored -> service.createVpc(region, "10.198.0.0/16", false).getVpcId());
            return service.createSubnet(region, vpc, cidr, zone);
        }

        private NatGateway create(Subnet subnet, String ip, String token, List<Tag> tags) {
            return service.createNatGateway(subnet.getRegion(), subnet.getSubnetId(), null, "private", ip,
                    List.of(), List.of(), null, tags, token);
        }

        private NatGateway publicGateway(Subnet subnet, Address primary, Address secondary, String token) {
            return service.createNatGateway(subnet.getRegion(), subnet.getSubnetId(), primary.getAllocationId(),
                    "public", null, List.of(secondary.getAllocationId()), List.of(), null, creationTags(), token);
        }

        private List<NatGateway> gateways(String region) {
            return service.describeNatGateways(region, List.of(), Map.of());
        }

        private Set<String> leases(Subnet subnet, List<String> pool) {
            Set<String> leased = new LinkedHashSet<>();
            when(network.allocatePrivateIp(subnet.getRegion(), subnet.getSubnetId())).thenAnswer(call -> {
                String address = pool.stream().filter(ip -> !leased.contains(ip)).findFirst().orElseThrow();
                assertTrue(leased.add(address));
                return Optional.of(address);
            });
            doAnswer(call -> {
                String address = call.getArgument(2);
                assertTrue(leased.remove(address));
                return null;
            }).when(network).releasePrivateIp(eq(subnet.getRegion()), eq(subnet.getSubnetId()), anyString());
            return leased;
        }

        @Override
        public void close() {
            try {
                factory.close();
            } finally {
                account.remove();
            }
        }
    }

    private static final class OwnedFactory extends StorageFactory implements AutoCloseable {
        private final String mode;
        private final Path directory;
        private final Instance<RequestContext> contexts;
        private final Map<String, AccountAwareStorageBackend<?>> stores = new TreeMap<>();
        private final AtomicInteger atomicAttempts = new AtomicInteger();
        private final AtomicInteger atomicCommits = new AtomicInteger();
        private final AtomicReference<RuntimeException> rejectNext = new AtomicReference<>();
        private final AtomicReference<NatGateway> lastAttempt = new AtomicReference<>();
        private final AtomicReference<Consumer<NatGateway>> beforeCommit = new AtomicReference<>(gateway -> {});

        private OwnedFactory(String mode, Path directory, Instance<RequestContext> contexts) {
            super(null, null);
            this.mode = mode;
            this.directory = directory;
            this.contexts = contexts;
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                                      TypeReference<Map<String, V>> type) {
            assertEquals("ec2", serviceName);
            AccountAwareStorageBackend<?> existing = stores.get(fileName);
            if (existing != null) {
                return (AccountAwareStorageBackend<V>) existing;
            }
            StorageBackend<String, V> raw = switch (mode) {
                case "memory" -> new InMemoryStorage<>();
                case "persistent" -> new PersistentStorage<>(directory.resolve(fileName), type);
                default -> throw new IllegalArgumentException(mode);
            };
            raw.load();
            AccountAwareStorageBackend<V> store = spy(new AccountAwareStorageBackend<>(raw, contexts, ACCOUNT));
            if (NAT_FILE.equals(fileName)) {
                doAnswer(call -> {
                    Map<String, NatGateway> written = call.getArgument(0);
                    assertEquals(1, written.size());
                    NatGateway attempted = new NatGateway(written.values().iterator().next());
                    lastAttempt.set(attempted);
                    atomicAttempts.incrementAndGet();
                    beforeCommit.get().accept(attempted);
                    RuntimeException failure = rejectNext.getAndSet(null);
                    if (failure != null) {
                        throw failure;
                    }
                    call.callRealMethod();
                    atomicCommits.incrementAndGet();
                    return null;
                }).when(store).putAllAtomically(anyMap());
            }
            stores.put(fileName, store);
            return store;
        }

        @SuppressWarnings("unchecked")
        private AccountAwareStorageBackend<NatGateway> nats() {
            return (AccountAwareStorageBackend<NatGateway>) stores.get(NAT_FILE);
        }

        private JsonNode snapshot() {
            Map<String, Object> all = new TreeMap<>();
            stores.forEach((name, store) -> all.put(name, new TreeMap<>(store.scanAllAccountsWithRawKeys())));
            assertTrue(all.containsKey(NAT_FILE) && all.containsKey(ADDRESS_FILE) && all.containsKey(TAG_FILE));
            return JSON.valueToTree(all);
        }

        private void assertPersistentSnapshot(JsonNode expected) throws Exception {
            if (!"persistent".equals(mode)) {
                return;
            }
            for (String name : stores.keySet()) {
                Path path = directory.resolve(name);
                if (Files.exists(path)) {
                    assertEquals(expected.path(name), JSON.readTree(Files.readString(path)), name);
                } else {
                    assertEquals(0, expected.path(name).size(), name);
                }
            }
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            for (AccountAwareStorageBackend<?> store : stores.values()) {
                try {
                    store.flush();
                } catch (RuntimeException error) {
                    if (failure == null) {
                        failure = error;
                    } else if (failure != error) {
                        failure.addSuppressed(error);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
