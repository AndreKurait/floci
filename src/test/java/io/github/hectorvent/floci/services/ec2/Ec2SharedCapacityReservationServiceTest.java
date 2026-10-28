package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.CapacityReservation;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ram.RamService;
import io.github.hectorvent.floci.services.resourcegroups.ResourceGroupsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Ec2SharedCapacityReservationServiceTest {
    private static final String OWNER = "111111111111";
    private static final String RECIPIENT = "222222222222";
    private static final String REGION = "us-east-1";

    @TempDir
    Path directory;

    @Test
    void targetRequiresAuthorizationAndNeverGrantsOwnerMutation() throws Exception {
        try (Fixture f = new Fixture("memory", directory)) {
            CapacityReservation reservation = f.create();
            f.account.set(RECIPIENT);
            assertEquals("InvalidCapacityReservationId.NotFound", assertThrows(AwsException.class,
                    () -> f.launch(reservation.getCapacityReservationId())).getErrorCode());
            assertTrue(f.service.describeInstances(REGION, List.of(), Map.of()).isEmpty());
            f.shared.set(true);
            Instance instance = f.launch(reservation.getCapacityReservationId());
            assertEquals(reservation.getCapacityReservationId(), f.service.instanceCapacityReservationId(instance));
            assertEquals("InvalidCapacityReservationId.NotFound", assertThrows(AwsException.class,
                    () -> f.service.cancelCapacityReservation(REGION, reservation.getCapacityReservationId())).getErrorCode());
            assertEquals("InvalidCapacityReservationId.NotFound", assertThrows(AwsException.class,
                    () -> f.service.modifyCapacityReservation(REGION, reservation.getCapacityReservationId(),
                            3, null, null, null)).getErrorCode());
            assertEquals("InvalidCapacityReservationId.NotFound", assertThrows(AwsException.class,
                    () -> f.service.moveCapacityReservationInstances(REGION, reservation.getCapacityReservationId(),
                            "cr-11111111111111111", 1, null)).getErrorCode());
            f.account.set(OWNER);
            assertEquals(0, f.describe(reservation).getAvailableInstanceCount());
            assertEquals(RECIPIENT, f.describe(reservation).getInstanceOwners().get(instance.getInstanceId()));
            assertNull(f.service.instanceCapacityReservationId(instance));
            f.account.set("333333333333");
            assertTrue(f.service.describeCapacityReservations(REGION, List.of(), Map.of()).isEmpty());
            f.account.set(RECIPIENT);
            assertTrue(f.service.describeCapacityReservations("us-west-2", List.of(), Map.of()).isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void recipientMembershipSurvivesRecoveryAndReleasesAfterRevocation(String mode) throws Exception {
        CapacityReservation reservation;
        String instanceId;
        try (Fixture f = new Fixture(mode, directory)) {
            reservation = f.create();
            f.shared.set(true);
            f.account.set(RECIPIENT);
            instanceId = f.launch(reservation.getCapacityReservationId()).getInstanceId();
        }
        try (Fixture f = new Fixture(mode, directory)) {
            assertEquals(0, f.describe(reservation).getAvailableInstanceCount());
            f.account.set(RECIPIENT);
            Instance instance = f.service.describeInstances(REGION, List.of(instanceId), Map.of())
                    .getFirst().getInstances().getFirst();
            assertEquals(reservation.getCapacityReservationId(), f.service.instanceCapacityReservationId(instance));
            f.service.stopInstances(REGION, List.of(instanceId));
            assertNull(f.service.instanceCapacityReservationId(instance));
            assertThrows(AwsException.class, () -> f.service.startInstances(REGION, List.of(instanceId)));
            f.account.set(OWNER);
            assertEquals(1, f.describe(reservation).getAvailableInstanceCount());
            f.shared.set(true);
            f.account.set(RECIPIENT);
            f.service.startInstances(REGION, List.of(instanceId));
            assertEquals(reservation.getCapacityReservationId(), f.service.instanceCapacityReservationId(instance));
            f.service.terminateInstances(REGION, List.of(instanceId));
            f.account.set(OWNER);
            assertEquals(1, f.describe(reservation).getAvailableInstanceCount());
            assertTrue(f.describe(reservation).getInstanceOwners().isEmpty());
        }
    }

    @Test
    void failedSharedPublicationLeavesNoCapacityOrVolumeLeakAndRetryAcquiresOnce() throws Exception {
        try (Fixture f = new Fixture("persistent", directory)) {
            CapacityReservation reservation = f.create();
            f.shared.set(true);
            f.account.set(RECIPIENT);
            doThrow(new IllegalStateException("injected cross-account publication failure"))
                    .when(f.store).putAllAccountsAtomically(anyList());
            assertEquals("InternalError", assertThrows(AwsException.class,
                    () -> f.launch(reservation.getCapacityReservationId())).getErrorCode());
            assertTrue(f.service.describeVolumes(REGION, List.of(), Map.of()).isEmpty());
            reset(f.store);
            f.account.set(OWNER);
            assertEquals(1, f.describe(reservation).getAvailableInstanceCount());
            f.account.set(RECIPIENT);
            Instance instance = f.launch(reservation.getCapacityReservationId());
            f.service.acquireCapacity(REGION, List.of(instance));
            f.account.set(OWNER);
            assertEquals(0, f.describe(reservation).getAvailableInstanceCount());
            assertEquals(List.of(instance.getInstanceId()), f.describe(reservation).getInstanceIds());
        }
    }

    @Test
    void concurrentOwnerAndRecipientCannotConsumeTheSameSlot() throws Exception {
        try (Fixture f = new Fixture("memory", directory);
                ExecutorService workers = Executors.newFixedThreadPool(2)) {
            CapacityReservation reservation = f.create();
            f.shared.set(true);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> owner = () -> attemptLaunch(f, OWNER, reservation, ready, start);
            Callable<Boolean> recipient = () -> attemptLaunch(f, RECIPIENT, reservation, ready, start);
            Future<Boolean> first = workers.submit(owner);
            Future<Boolean> second = workers.submit(recipient);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(0, f.describe(reservation).getAvailableInstanceCount());
            assertEquals(1, f.describe(reservation).getInstanceIds().size());
        }
    }

    @Test
    void ownerMoveKeepsRecipientOwnershipAndRequiresDestinationAuthorization() throws Exception {
        try (Fixture f = new Fixture("memory", directory)) {
            CapacityReservation source = f.create();
            f.service.modifyCapacityReservation(REGION, source.getCapacityReservationId(), null,
                    null, null, "open");
            f.shared.set(true);
            f.account.set(RECIPIENT);
            Instance instance = f.launch(null);
            f.account.set(OWNER);
            CapacityReservation destination = f.create();
            f.service.modifyCapacityReservation(REGION, destination.getCapacityReservationId(), null,
                    null, null, "open");
            when(f.service.ramServices.get().canUseResource(eq(destination.getCapacityReservationArn()),
                    eq(OWNER), eq(RECIPIENT), eq(REGION))).thenReturn(false);
            assertEquals("Unsupported", assertThrows(AwsException.class,
                    () -> f.service.moveCapacityReservationInstances(REGION, source.getCapacityReservationId(),
                            destination.getCapacityReservationId(), 1, "move")).getErrorCode());
            assertEquals(List.of(instance.getInstanceId()), f.describe(source).getInstanceIds());
            when(f.service.ramServices.get().canUseResource(eq(destination.getCapacityReservationArn()),
                    eq(OWNER), eq(RECIPIENT), eq(REGION))).thenReturn(true);
            f.service.moveCapacityReservationInstances(REGION, source.getCapacityReservationId(),
                    destination.getCapacityReservationId(), 1, "move");
            assertEquals(RECIPIENT, f.describe(destination).getInstanceOwners().get(instance.getInstanceId()));
            assertTrue(f.describe(source).getInstanceOwners().isEmpty());
            f.account.set(RECIPIENT);
            assertEquals(destination.getCapacityReservationId(), f.service.instanceCapacityReservationId(instance));
            f.service.terminateInstances(REGION, List.of(instance.getInstanceId()));
            f.account.set(OWNER);
            assertEquals(2, f.describe(destination).getAvailableInstanceCount());
        }
    }

    @Test
    void failedRecipientReleaseRetainsOwnerMembershipUntilRetry() throws Exception {
        try (Fixture f = new Fixture("persistent", directory)) {
            CapacityReservation reservation = f.create();
            f.shared.set(true);
            f.account.set(RECIPIENT);
            Instance instance = f.launch(reservation.getCapacityReservationId());
            doThrow(new IllegalStateException("injected release failure"))
                    .when(f.store).putAllAccountsAtomically(anyList());
            assertThrows(AwsException.class,
                    () -> f.service.stopInstances(REGION, List.of(instance.getInstanceId())));
            assertEquals(0, f.store.getForAccount(OWNER, REGION + "::" + reservation.getCapacityReservationId())
                    .orElseThrow().getAvailableInstanceCount());
            reset(f.store);
            f.shared.set(false);
            f.account.set(OWNER);
            assertEquals(1, f.describe(reservation).getAvailableInstanceCount());
            assertTrue(f.describe(reservation).getInstanceOwners().isEmpty());
        }
    }

    @Test
    void dedicatedTenancyCannotBeConsumedThroughARamShare() throws Exception {
        try (Fixture f = new Fixture("memory", directory)) {
            CapacityReservation reservation = f.service.createCapacityReservation(REGION, "t3.micro", "Linux/UNIX",
                    REGION + "a", null, 1, "dedicated", null, null, null, null, "targeted", null, null);
            f.shared.set(true);
            f.account.set(RECIPIENT);
            assertTrue(f.service.describeCapacityReservations(REGION, List.of(), Map.of()).isEmpty());
            assertEquals("InvalidCapacityReservationId.NotFound", assertThrows(AwsException.class,
                    () -> f.launch(reservation.getCapacityReservationId())).getErrorCode());
        }
    }

    @Test
    void duplicateInstanceInOneAdmissionBatchConsumesOnlyOnce() throws Exception {
        try (Fixture f = new Fixture("memory", directory)) {
            f.account.set(RECIPIENT);
            Instance instance = f.launch(null);
            f.account.set(OWNER);
            CapacityReservation reservation = f.create();
            f.shared.set(true);
            f.account.set(RECIPIENT);
            instance.setCapacityReservationTarget(reservation.getCapacityReservationId());
            instance.setCapacityReservationPreference(null);
            f.service.acquireCapacity(REGION, List.of(instance, instance));
            f.account.set(OWNER);
            assertEquals(List.of(instance.getInstanceId()), f.describe(reservation).getInstanceIds());
            assertEquals(0, f.describe(reservation).getAvailableInstanceCount());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void acceptedOpenShareAdoptsRunningRecipientAndPersistsOwnership(String mode) throws Exception {
        CapacityReservation reservation;
        String id;
        try (Fixture f = new Fixture(mode, directory)) {
            f.account.set(RECIPIENT);
            Instance instance = f.launch(null);
            id = instance.getInstanceId();
            f.service.describeInstances(REGION, List.of(id), Map.of());
            f.account.set(OWNER);
            reservation = f.create();
            f.service.modifyCapacityReservation(REGION, reservation.getCapacityReservationId(), null,
                    null, null, "open");
            assertEquals(1, f.describe(reservation).getAvailableInstanceCount());
            f.shared.set(true);
            assertEquals(0, f.describe(reservation).getAvailableInstanceCount());
            assertEquals(RECIPIENT, f.describe(reservation).getInstanceOwners().get(id));
        }
        try (Fixture f = new Fixture(mode, directory)) {
            assertEquals(0, f.describe(reservation).getAvailableInstanceCount());
            f.account.set(RECIPIENT);
            f.service.terminateInstances(REGION, List.of(id));
            f.account.set(OWNER);
            assertEquals(1, f.describe(reservation).getAvailableInstanceCount());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lifecycleBatchScansCandidatesOnceAndReleasesMembership(boolean terminate) throws Exception {
        try (Fixture f = new Fixture("memory", directory)) {
            CapacityReservation reservation = f.create();
            f.service.modifyCapacityReservation(REGION, reservation.getCapacityReservationId(), 3, null, null, "open");
            f.shared.set(true);
            f.account.set(RECIPIENT);
            Instance first = f.launch(null);
            Instance second = f.launch(null);
            clearInvocations(f.instanceStore);
            if (terminate) {
                f.service.terminateInstances(REGION, List.of(first.getInstanceId(), second.getInstanceId()));
            } else {
                f.service.stopInstances(REGION, List.of(first.getInstanceId(), second.getInstanceId()));
            }
            verify(f.instanceStore, times(1)).scanAllAccountEntries(any());
            f.account.set(OWNER);
            assertEquals(3, f.describe(reservation).getAvailableInstanceCount());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partialLifecycleBatchStillReleasesMembership(boolean terminate) throws Exception {
        try (Fixture f = new Fixture("memory", directory)) {
            CapacityReservation reservation = f.create();
            f.shared.set(true);
            f.account.set(RECIPIENT);
            Instance first = f.launch(reservation.getCapacityReservationId());
            List<String> ids = List.of(first.getInstanceId(), "i-missing");
            AwsException failure = assertThrows(AwsException.class, () -> {
                if (terminate) {
                    f.service.terminateInstances(REGION, ids);
                } else {
                    f.service.stopInstances(REGION, ids);
                }
            });
            assertEquals("InvalidInstanceID.NotFound", failure.getErrorCode());
            assertEquals(1, f.store.getForAccount(OWNER, REGION + "::"
                    + reservation.getCapacityReservationId()).orElseThrow().getAvailableInstanceCount());
        }
    }

    @Test
    void missingOwnerCannotAcquireOrAdoptSharedCapacity() throws Exception {
        try (Fixture f = new Fixture("memory", directory)) {
            CapacityReservation reservation = f.create();
            f.service.modifyCapacityReservation(REGION, reservation.getCapacityReservationId(), null, null, null, "open");
            f.account.set(RECIPIENT);
            Instance instance = f.launch(null);
            f.service.describeInstances(REGION, List.of(instance.getInstanceId()), Map.of());
            instance.setOwnerId(null);
            f.shared.set(true);
            assertDoesNotThrow(() -> f.service.acquireCapacity(REGION, List.of(instance)));
            instance.setState(InstanceState.stopped());
            instance.setCapacityReservationTarget(reservation.getCapacityReservationId());
            assertEquals("InsufficientInstanceCapacity", assertThrows(AwsException.class,
                    () -> f.service.startInstances(REGION, List.of(instance.getInstanceId()))).getErrorCode());
            assertEquals("stopped", instance.getState().getName());
            f.account.set(OWNER);
            assertEquals(1, f.describe(reservation).getAvailableInstanceCount());
            assertTrue(f.describe(reservation).getInstanceIds().isEmpty());
        }
    }

    @Test
    void strictOpenAdmissionUsesAuthorizedSharedCapacity() throws Exception {
        try (Fixture f = new Fixture("memory", directory)) {
            CapacityReservation reservation = f.create();
            f.service.modifyCapacityReservation(REGION, reservation.getCapacityReservationId(), 1,
                    null, null, "open");
            f.account.set(RECIPIENT);
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            specification.setCapacityReservationPreference("capacity-reservations-only");
            assertThrows(AwsException.class, () -> f.launchSpecification(specification));
            assertTrue(f.instanceStore.scan(key -> true).isEmpty());
            f.shared.set(true);
            Instance launched = f.launchSpecification(specification);
            assertEquals(reservation.getCapacityReservationId(), f.service.instanceCapacityReservationId(launched));
            assertEquals(0, f.describe(reservation).getAvailableInstanceCount());
            assertEquals(RECIPIENT, f.describe(reservation).getInstanceOwners().get(launched.getInstanceId()));
            assertThrows(AwsException.class, () -> f.launchSpecification(specification));
            assertEquals(1, f.instanceStore.scan(key -> true).size());
        }
    }

    @Test
    void sharedOpenAdoptionDoesNotIgnoreGroupMembership() throws Exception {
        try (Fixture f = new Fixture("memory", directory)) {
            f.account.set(RECIPIENT);
            Instance instance = f.launchGroup();
            f.service.describeInstances(REGION, List.of(instance.getInstanceId()), Map.of());
            f.account.set(OWNER);
            CapacityReservation outside = f.create();
            f.service.modifyCapacityReservation(REGION, outside.getCapacityReservationId(), null,
                    null, null, "open");
            f.shared.set(true);
            assertEquals(1, f.describe(outside).getAvailableInstanceCount());
            f.account.set(RECIPIENT);
            assertNull(f.service.instanceCapacityReservationId(instance));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void groupAdoptionUsesOwnerMembersAndPersistsAcrossRecovery(String mode) throws Exception {
        CapacityReservation member;
        CapacityReservation outside;
        String id;
        try (Fixture f = new Fixture(mode, directory)) {
            f.account.set(RECIPIENT);
            Instance instance = f.launchGroup();
            id = instance.getInstanceId();
            f.service.describeInstances(REGION, List.of(id), Map.of());
            f.account.set(OWNER);
            outside = f.create();
            f.service.modifyCapacityReservation(REGION, outside.getCapacityReservationId(), null,
                    null, null, "open");
            f.shared.set(true);
            assertEquals(1, f.describe(outside).getAvailableInstanceCount());
            f.account.set(RECIPIENT);
            assertEquals(List.of(), f.groups.add(REGION, RECIPIENT, f.mapper.valueToTree(Map.of(
                    "Group", f.groupArn, "ResourceArns", List.of(outside.getCapacityReservationArn()))))
                    .get("Succeeded"));
            member = f.create();
            f.groupMember(member);
            assertEquals(member.getCapacityReservationId(), f.service.instanceCapacityReservationId(instance));
            assertEquals(0, f.describe(member).getAvailableInstanceCount());
            f.account.set(OWNER);
            assertEquals(1, f.describe(outside).getAvailableInstanceCount());
        }
        try (Fixture f = new Fixture(mode, directory)) {
            f.account.set(RECIPIENT);
            Instance instance = f.service.describeInstances(REGION, List.of(id), Map.of())
                    .getFirst().getInstances().getFirst();
            assertEquals(member.getCapacityReservationId(), f.service.instanceCapacityReservationId(instance));
            f.service.stopInstances(REGION, List.of(id));
            assertEquals(1, f.describe(member).getAvailableInstanceCount());
            assertTrue(f.describe(member).getInstanceIds().isEmpty());
            f.account.set(OWNER);
            assertEquals(1, f.describe(outside).getAvailableInstanceCount());
        }
    }

    @Test
    void failedGroupAdoptionRetainsCapacityAndRetriesOnce() throws Exception {
        try (Fixture f = new Fixture("memory", directory)) {
            f.account.set(RECIPIENT);
            Instance instance = f.launchGroup();
            f.service.describeInstances(REGION, List.of(instance.getInstanceId()), Map.of());
            CapacityReservation reservation = f.create();
            f.groupMember(reservation);
            doThrow(new IllegalStateException("injected group publication failure"))
                    .when(f.store).putAllAtomically(anyMap());
            assertEquals("InternalError", assertThrows(AwsException.class,
                    () -> f.service.instanceCapacityReservationId(instance)).getErrorCode());
            CapacityReservation stored = f.store.getForAccount(RECIPIENT,
                    REGION + "::" + reservation.getCapacityReservationId()).orElseThrow();
            assertEquals(1, stored.getAvailableInstanceCount());
            assertTrue(stored.getInstanceIds().isEmpty());
            reset(f.store);
            assertEquals(reservation.getCapacityReservationId(), f.service.instanceCapacityReservationId(instance));
            assertEquals(reservation.getCapacityReservationId(), f.service.instanceCapacityReservationId(instance));
            assertEquals(List.of(instance.getInstanceId()), f.describe(reservation).getInstanceIds());
        }
    }

    private boolean attemptLaunch(Fixture f, String account, CapacityReservation reservation,
                                  CountDownLatch ready, CountDownLatch start) throws Exception {
        f.account.set(account);
        ready.countDown();
        assertTrue(start.await(10, TimeUnit.SECONDS));
        try {
            f.launch(reservation.getCapacityReservationId());
            return true;
        } catch (AwsException failure) {
            assertEquals("ReservationCapacityExceeded", failure.getErrorCode());
            return false;
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ThreadLocal<String> account = ThreadLocal.withInitial(() -> OWNER);
        private final AtomicBoolean shared = new AtomicBoolean();
        private final ResourceGroupsService groups;
        private final ObjectMapper mapper = new ObjectMapper();
        private final String groupArn = "arn:aws:resource-groups:us-east-1:222222222222:group/shared";
        private final StorageFactory factory;
        private final AccountAwareStorageBackend<CapacityReservation> store;
        private final Ec2Service service;
        private final AccountAwareStorageBackend<Instance> instanceStore;

        @SuppressWarnings("unchecked")
        private Fixture(String mode, Path path) throws Exception {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.defaultAccountId()).thenReturn(OWNER);
            when(config.services().ec2().mock()).thenReturn(true);
            when(config.services().ec2().mockPublicAddresses())
                    .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
            when(config.storage().persistentPath()).thenReturn(path.toString());
            when(config.storage().wal().compactionIntervalMs()).thenReturn(3600000L);
            ServiceConfigAccess access = mock(ServiceConfigAccess.class);
            when(access.storageMode("ec2")).thenReturn(mode);
            when(access.storageFlushInterval("ec2")).thenReturn(3600000L);
            when(access.storageMode("resource-groups")).thenReturn(mode);
            when(access.storageFlushInterval("resource-groups")).thenReturn(3600000L);
            RequestContext context = mock(RequestContext.class);
            when(context.getAccountId()).thenAnswer(call -> account.get());
            // CDI Instance clashes with the imported EC2 Instance model.
            jakarta.enterprise.inject.Instance<RequestContext> contexts = mock(jakarta.enterprise.inject.Instance.class);
            when(contexts.get()).thenReturn(context);
            factory = new StorageFactory(config, access);
            Field field = StorageFactory.class.getDeclaredField("requestContextInstance");
            field.setAccessible(true);
            field.set(factory, contexts);
            store = spy(factory.create("ec2", "ec2-capacity-reservations.json", new TypeReference<>() {}));
            StorageFactory serviceFactory = spy(factory);
            doReturn(store).when(serviceFactory).create(eq("ec2"), eq("ec2-capacity-reservations.json"), any());
            instanceStore = spy(factory.create("ec2", "ec2-instances.json", new TypeReference<>() {}));
            doReturn(instanceStore).when(serviceFactory).create(eq("ec2"), eq("ec2-instances.json"), any());
            Ec2ImageCatalog catalog = new Ec2ImageCatalog();
            service = new Ec2Service(config, null, null, new AmiImageResolver(catalog),
                    catalog, new Ec2InstanceTypeCatalog(), serviceFactory, contexts);
            RamService ram = mock(RamService.class);
            when(ram.canUseResource(anyString(), eq(OWNER), eq(RECIPIENT), eq(REGION)))
                    .thenAnswer(call -> shared.get());
            groups = new ResourceGroupsService(factory, service);
            service.resourceGroupsServices = mock(jakarta.enterprise.inject.Instance.class);
            when(service.resourceGroupsServices.isResolvable()).thenReturn(true);
            when(service.resourceGroupsServices.get()).thenReturn(groups);
            service.ramServices = mock(jakarta.enterprise.inject.Instance.class);
            when(service.ramServices.isResolvable()).thenReturn(true);
            when(service.ramServices.get()).thenReturn(ram);
        }

        private void groupMember(CapacityReservation reservation) {
            assertEquals(List.of(reservation.getCapacityReservationArn()), groups.add(REGION, RECIPIENT,
                    mapper.valueToTree(Map.of("Group", groupArn,
                            "ResourceArns", List.of(reservation.getCapacityReservationArn())))).get("Succeeded"));
        }

        private Instance launchGroup() {
            assertEquals(groupArn, groups.create(REGION, RECIPIENT, mapper.valueToTree(Map.of(
                    "Name", "shared", "Configuration", List.of(
                            Map.of("Type", "AWS::EC2::CapacityReservationPool"),
                            Map.of("Type", "AWS::ResourceGroups::Generic", "Parameters",
                                    List.of(Map.of("Name", "allowed-resource-types",
                                            "Values", List.of("AWS::EC2::CapacityReservation")))))))).arn());
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            specification.setCapacityReservationPreference("open");
            LaunchTemplateData.CapacityReservationTarget target = new LaunchTemplateData.CapacityReservationTarget();
            target.setCapacityReservationResourceGroupArn(groupArn);
            specification.setCapacityReservationTarget(target);
            return launchSpecification(specification);
        }

        private CapacityReservation create() {
            return service.createCapacityReservation(REGION, "t3.micro", "Linux/UNIX", REGION + "a",
                    null, 1, null, null, null, null, null, "targeted", null, null);
        }

        private CapacityReservation describe(CapacityReservation reservation) {
            return service.describeCapacityReservations(REGION,
                    List.of(reservation.getCapacityReservationId()), Map.of()).getFirst();
        }

        private Instance launch(String targetId) {
            LaunchTemplateData.CapacityReservationSpecification specification =
                    new LaunchTemplateData.CapacityReservationSpecification();
            LaunchTemplateData.CapacityReservationTarget target = new LaunchTemplateData.CapacityReservationTarget();
            if (targetId == null) {
                specification.setCapacityReservationPreference("open");
            } else {
                target.setCapacityReservationId(targetId);
                specification.setCapacityReservationTarget(target);
            }
            return launchSpecification(specification);
        }

        private Instance launchSpecification(LaunchTemplateData.CapacityReservationSpecification specification) {
            return service.runInstances(REGION, "ami-test", "t3.micro", 1, 1, null, List.of(), null, null,
                    List.of(), null, null, null, null, 0, REGION + "a", null, null, null, false, null, null, null,
                    false, null, specification).getInstances().getFirst();
        }

        @Override
        public void close() {
            factory.shutdownAll();
        }
    }
}
