package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackEvent;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsSubscriptionAdmissionService;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsSubscriptionFilterService;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.MetricFilter;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.SubscriptionFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real typed account-aware native stores, including controlled atomic write and cleanup failures. */
class LogsSubscriptionFilterCfnProvisionerTest {

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";
    private static final String GROUP = "/app/source";
    private static final String OTHER = "/app/other";
    private static final String SNAPSHOT = "__FlociSubscriptionFilterUpdate";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FaultStore store;
    private CloudWatchLogsService logs;
    private CloudWatchLogsSubscriptionAdmissionService admission;
    private CloudWatchLogsSubscriptionFilterService filters;
    private LogsSubscriptionFilterCfnProvisioner provisioner;
    private CloudFormationTemplateEngine engine;
    private List<StackEvent> events;

    @BeforeEach
    void setUp() {
        store = new FaultStore();
        events = new ArrayList<>();
        AccountAwareStorageBackend<SubscriptionFilter> subscriptions = new AccountAwareStorageBackend<>(store, null, ACCOUNT);
        AccountAwareStorageBackend<MetricFilter> metrics = AccountAwareStorageBackend.inMemory(ACCOUNT);
        store.monitor = metrics;
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(any(), any(), any())).thenAnswer(invocation -> switch ((String) invocation.getArgument(1)) {
            case "cwlogs-subscription-filters.json" -> subscriptions;
            case "cwlogs-metric-filters.json" -> metrics;
            default -> AccountAwareStorageBackend.inMemory(ACCOUNT);
        });
        when(factory.create(any(), any(), any(), any())).thenAnswer(invocation ->
                AccountAwareStorageBackend.inMemory(ACCOUNT));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudwatchlogs().maxStoredEvents()).thenReturn(Integer.MAX_VALUE);
        logs = new CloudWatchLogsService(factory, config, new RegionResolver(REGION, ACCOUNT), null, null);
        logs.createLogGroup(GROUP, null, null, REGION);
        logs.createLogGroup(OTHER, null, null, REGION);
        admission = mock(CloudWatchLogsSubscriptionAdmissionService.class);
        filters = new CloudWatchLogsSubscriptionFilterService(logs, admission);
        provisioner = new LogsSubscriptionFilterCfnProvisioner(filters);
        engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static ObjectNode properties() {
        return MAPPER.createObjectNode().put("LogGroupName", GROUP).put("FilterName", "filter")
                .put("FilterPattern", "").put("DestinationArn", "arn:aws:lambda:us-east-1:111111111111:function:target")
                .put("Distribution", "Random");
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Filter");
        resource.setResourceType("AWS::Logs::SubscriptionFilter");
        return resource;
    }

    private void provision(StackResource resource, ObjectNode properties) {
        provisioner.provision(resource, properties,
                new ProvisionContext(engine, REGION, ACCOUNT, "owned-stack", resource.getPhysicalId(), events::add, null));
    }

    private StackResource create() {
        StackResource resource = resource();
        provision(resource, properties());
        return resource;
    }

    private JsonNode snapshot(String group, String name) {
        return MAPPER.valueToTree(filters.findSubscriptionFilter(group, name, REGION).orElseThrow());
    }

    private void commit(StackResource resource) {
        resource.setStatus("UPDATE_COMPLETE");
        assertTrue(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);
    }

    @Test
    void whitespaceOnlyNameKeepsItsOwnedIdentityAcrossUpdateAndDeletion() {
        String name = " ";
        assertFalse(new ProvisionContext(engine, REGION, ACCOUNT, "owned-stack", name).isUpdate());
        StackResource resource = resource();
        provision(resource, properties().put("FilterName", name));
        assertEquals(name, resource.getPhysicalId());
        JsonNode before = snapshot(GROUP, name);
        provision(resource, properties().put("FilterName", name).put("FilterPattern", "WARN"));
        assertEquals(name, resource.getPhysicalId());
        assertEquals("WARN", snapshot(GROUP, name).path("filterPattern").asText());
        assertNotEquals(before, snapshot(GROUP, name));
        assertEquals(1, store.keys().size());
        commit(resource);
        provisioner.delete(resource, REGION);
        assertTrue(store.keys().isEmpty());
    }

    @Test
    void sharedAdmissionRefusalPreservesTheFullNativeDefinitionAndOwnership() {
        StackResource resource = create();
        JsonNode before = snapshot(GROUP, "filter");
        String refused = "arn:aws:lambda:us-east-1:111111111111:function:refused";
        doThrow(new AwsException("InvalidParameterException", "controlled admission refusal", 400))
                .when(admission).validate(refused, GROUP, REGION, ACCOUNT);
        AwsException failure = assertThrows(AwsException.class,
                () -> provision(resource, properties().put("DestinationArn", refused)));
        assertEquals("InvalidParameterException", failure.getErrorCode());
        verify(admission).validate(refused, GROUP, REGION, ACCOUNT);
        assertEquals(before, snapshot(GROUP, "filter"));
        assertEquals("filter", resource.getPhysicalId());
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(before, snapshot(GROUP, "filter"));
        provisioner.delete(resource, REGION);
        assertTrue(store.keys().isEmpty());
    }

    @Test
    void explicitNameAndEveryNativeMemberHaveRealBackingStateAndDetachedReads() {
        StackResource resource = create();
        assertEquals("filter", resource.getPhysicalId());
        assertEquals(Set.of("AWS::Logs::SubscriptionFilter"), provisioner.resourceTypes());
        SubscriptionFilter stored = filters.findSubscriptionFilter(GROUP, "filter", REGION).orElseThrow();
        assertEquals(GROUP, stored.getLogGroupName());
        assertEquals("", stored.getFilterPattern());
        assertEquals(properties().path("DestinationArn").asText(), stored.getDestinationArn());
        assertEquals("Random", stored.getDistribution());
        assertTrue(stored.getCreationTime() > 0);
        JsonNode before = snapshot(GROUP, "filter");
        stored.setDestinationArn("mutated");
        stored.setFilterPattern("mutated");
        stored.setApplyOnTransformedLogs(true);
        assertEquals(before, snapshot(GROUP, "filter"));
        provisioner.delete(resource, REGION);
        assertTrue(filters.findSubscriptionFilter(GROUP, "filter", REGION).isEmpty());
        assertTrue(store.keys().isEmpty());
    }

    @Test
    void duplicateCreateAndReplacementNeverAdoptOrDeleteTheExistingTarget() {
        StackResource first = create();
        JsonNode prior = snapshot(GROUP, "filter");
        StackResource duplicate = resource();
        AwsException failure = assertThrows(AwsException.class, () -> provision(duplicate, properties()));
        assertEquals("AlreadyExistsException", failure.getErrorCode());
        assertNull(duplicate.getPhysicalId());
        provisioner.delete(duplicate, REGION);
        assertEquals(prior, snapshot(GROUP, "filter"));
        logs.putSubscriptionFilter(OTHER, "filter", "WARN", "foreign", "ByLogStream", REGION);
        JsonNode foreign = snapshot(OTHER, "filter");
        assertThrows(AwsException.class, () -> provision(first, properties().put("LogGroupName", OTHER)));
        assertTrue(provisioner.retainsFailedUpdateState(first));
        assertTrue(provisioner.rollbackUpdate(first));
        assertEquals(prior, snapshot(GROUP, "filter"));
        assertEquals(foreign, snapshot(OTHER, "filter"));
        provisioner.delete(first, REGION);
        assertEquals(foreign, snapshot(OTHER, "filter"));
    }

    @Test
    void fullInPlaceRollbackKeepsOriginalTimestampAndRetryMetadataAfterRestoreFailure() {
        StackResource resource = create();
        String key = AccountAwareStorageBackend.rawKey(ACCOUNT, REGION + "::" + GROUP + "::filter::filter");
        store.get(key).orElseThrow().setApplyOnTransformedLogs(true);
        JsonNode before = snapshot(GROUP, "filter");
        provision(resource, properties().put("FilterPattern", "ERROR").put("DestinationArn", "arn:aws:lambda:us-east-1:111111111111:function:other")
                .put("Distribution", "ByLogStream"));
        assertNotEquals(before, snapshot(GROUP, "filter"));
        assertEquals(before.path("creationTime"), snapshot(GROUP, "filter").path("creationTime"));
        store.putFailure = Fault.BEFORE;
        assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource));
        assertTrue(resource.getAttributes().containsKey(SNAPSHOT));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(before, snapshot(GROUP, "filter"));
        assertFalse(resource.getAttributes().containsKey(SNAPSHOT));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void groupReplacementDeletesTheOldGroupFilterFirstEvenWithRetain(boolean retain) {
        StackResource resource = create();
        resource.setUpdateReplacePolicy(retain ? "Retain" : "Delete");
        store.mutations.clear();
        provision(resource, properties().put("LogGroupName", OTHER).put("FilterPattern", "ERROR"));
        assertEquals("filter", resource.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertTrue(filters.findSubscriptionFilter(GROUP, "filter", REGION).isEmpty());
        assertEquals("ERROR", snapshot(OTHER, "filter").path("filterPattern").asText());
        assertEquals(List.of("delete", "put"), store.mutations);
        assertEquals(List.of("DELETE_IN_PROGRESS", "DELETE_COMPLETE", "CREATE_IN_PROGRESS", "CREATE_COMPLETE"),
                events.stream().map(StackEvent::getResourceStatus).toList());
        commit(resource);
        assertTrue(filters.findSubscriptionFilter(GROUP, "filter", REGION).isEmpty());
        provisioner.delete(resource, REGION);
        assertTrue(store.keys().isEmpty());
    }

    @Test
    void replacementRollbackDeletesOnlyTheNewFilterThenRecreatesTheOldCompleteDefinition() {
        StackResource resource = create();
        JsonNode before = snapshot(GROUP, "filter");
        provision(resource, properties().put("FilterName", "replacement").put("FilterPattern", "WARN"));
        store.deleteFailure = true;
        assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource));
        assertTrue(resource.getAttributes().containsKey(SNAPSHOT));
        assertTrue(filters.findSubscriptionFilter(GROUP, "filter", REGION).isEmpty());
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals("filter", resource.getPhysicalId());
        assertTrue(filters.findSubscriptionFilter(GROUP, "replacement", REGION).isEmpty());
        assertEquals(before, snapshot(GROUP, "filter"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE", "AFTER", "UNINSPECTABLE"})
    void failedOldDeletionRetainsItsSnapshotAndRecoversEveryObservedStorageOutcome(String fault) {
        StackResource resource = create();
        JsonNode before = snapshot(GROUP, "filter");
        store.deleteFault = Fault.valueOf(fault);
        assertThrows(IllegalStateException.class, () -> provision(resource, properties().put("FilterName", "replacement")));
        assertTrue(resource.getAttributes().containsKey(SNAPSHOT));
        assertTrue(provisioner.requiresUpdateRecovery(resource));
        assertThrows(IllegalStateException.class, () -> provisioner.clearUpdate(resource));
        assertFalse(provisioner.completeDeleteCleanup(resource).applicable());
        provisioner.clearDeleteCleanup(resource);
        assertTrue(resource.getAttributes().containsKey(SNAPSHOT));
        store.failReads = false;
        assertTrue(filters.findSubscriptionFilter(GROUP, "replacement", REGION).isEmpty());
        assertEquals("BEFORE".equals(fault), filters.findSubscriptionFilter(GROUP, "filter", REGION).isPresent());
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(before, snapshot(GROUP, "filter"));
        assertFalse(resource.getAttributes().containsKey(SNAPSHOT));
        provision(resource, properties().put("FilterName", "replacement"));
        commit(resource);
        provisioner.delete(resource, REGION);
        assertTrue(store.keys().isEmpty());
    }

    @Test
    void failedRestoreCannotAdoptAFilterCreatedAtTheOldAddressBeforeRetry() {
        StackResource resource = create();
        provision(resource, properties().put("FilterName", "replacement"));
        store.putFailure = Fault.BEFORE;
        assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource));
        assertTrue(resource.getAttributes().containsKey(SNAPSHOT));
        assertTrue(store.keys().isEmpty());
        logs.putSubscriptionFilter(GROUP, "filter", "foreign", "foreign", "ByLogStream", REGION);
        JsonNode foreign = snapshot(GROUP, "filter");
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));
        assertEquals("AlreadyExistsException", failure.getErrorCode());
        assertEquals(foreign, snapshot(GROUP, "filter"));
        assertTrue(resource.getAttributes().containsKey(SNAPSHOT));
        provisioner.delete(resource, REGION);
        assertEquals(foreign, snapshot(GROUP, "filter"));
        assertFalse(resource.getAttributes().containsKey(SNAPSHOT));
    }

    @Test
    void deleteStackKeepsAndDeletesBothIdentitiesAfterFailedReplacementRollback() {
        StackResource resource = create();
        provision(resource, properties().put("FilterName", "replacement"));
        store.deleteFailure = true;
        assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource));
        assertFalse(provisioner.completeDeleteCleanup(resource).applicable());
        provisioner.clearDeleteCleanup(resource);
        assertTrue(resource.getAttributes().containsKey(SNAPSHOT));
        provisioner.delete(resource, REGION);
        assertTrue(store.keys().isEmpty());
        assertFalse(resource.getAttributes().containsKey(SNAPSHOT));
    }

    @Test
    void generatedNameSurvivesMutableUpdatesAndExplicitNameOmissionReplaces() {
        StackResource generated = resource();
        ObjectNode unnamed = properties();
        unnamed.remove("FilterName");
        provision(generated, unnamed);
        String name = generated.getPhysicalId();
        assertTrue(name.startsWith("owned-stack-Filter-"));
        provision(generated, unnamed.put("FilterPattern", "WARN"));
        assertEquals(name, generated.getPhysicalId());
        commit(generated);
        StackResource explicit = create();
        provision(explicit, unnamed);
        assertNotEquals("filter", explicit.getPhysicalId());
        commit(explicit);
        assertTrue(filters.findSubscriptionFilter(GROUP, "filter", REGION).isEmpty());
        assertTrue(filters.findSubscriptionFilter(GROUP, name, REGION).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RoleArn", "ApplyOnTransformedLogs", "EmitSystemFields", "FieldSelectionCriteria"})
    void unsupportedNativePropertiesAreExplicitlyRejectedWithoutChangingTheFilter(String property) {
        StackResource resource = create();
        JsonNode before = snapshot(GROUP, "filter");
        AwsException failure = assertThrows(AwsException.class,
                () -> provision(resource, properties().put(property, "present")));
        assertEquals("ValidationError", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("Unsupported native property " + property));
        assertEquals(before, snapshot(GROUP, "filter"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:kinesis:us-east-1:111111111111:stream/target",
            "arn:aws:firehose:us-east-1:111111111111:deliverystream/target",
            "arn:aws:lambda:us-east-1:222222222222:function:target",
            "arn:aws:lambda:us-west-2:111111111111:function:target",
            "arn:aws-cn:lambda:us-east-1:111111111111:function:target",
            "arn:aws:logs:us-east-1:222222222222:destination:", "not-an-arn"})
    void unsupportedOrMalformedDestinationsRefuseWithoutChangingNativeState(String destination) {
        StackResource resource = create();
        JsonNode before = snapshot(GROUP, "filter");
        AwsException failure = assertThrows(AwsException.class,
                () -> provision(resource, properties().put("DestinationArn", destination)));
        assertEquals("InvalidParameterException", failure.getErrorCode());
        assertEquals(before, snapshot(GROUP, "filter"));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(before, snapshot(GROUP, "filter"));
    }

    @Test
    void logicalDestinationArnIsStoredWithoutClaimingDeliveryQualification() {
        StackResource resource = resource();
        String destination = "arn:aws:logs:us-east-1:222222222222:destination:target";
        provision(resource, properties().put("DestinationArn", destination));
        assertEquals(destination, snapshot(GROUP, "filter").path("destinationArn").asText());
        provisioner.delete(resource, REGION);
        assertTrue(store.keys().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"LogGroupName", "FilterPattern", "DestinationArn"})
    void missingRequiredPropertiesFailBeforeAnyWrite(String member) {
        ObjectNode props = properties();
        props.remove(member);
        assertThrows(AwsException.class, () -> provision(resource(), props));
        assertTrue(store.keys().isEmpty());
    }

    @Test
    void noValueOmissionIsDistinctFromTheValidEmptyPatternAndInvalidEmptyName() {
        ObjectNode props = properties();
        props.set("FilterName", MAPPER.createObjectNode().put("Ref", "AWS::NoValue"));
        props.set("RoleArn", MAPPER.createObjectNode().put("Ref", "AWS::NoValue"));
        StackResource resource = resource();
        provision(resource, props);
        assertTrue(resource.getPhysicalId().startsWith("owned-stack-Filter-"));
        assertEquals("", snapshot(GROUP, resource.getPhysicalId()).path("filterPattern").asText());
        assertThrows(AwsException.class, () -> provision(resource(), properties().put("FilterName", "")));
        props.set("FilterPattern", MAPPER.createObjectNode().put("Ref", "AWS::NoValue"));
        assertThrows(AwsException.class, () -> provision(resource(), props));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE", "AFTER", "UNINSPECTABLE"})
    void failedCreateTracksAppliedAbsentAndUnknownOutcomesWithoutAdoptingUnknownState(String scenario) {
        store.putFailure = Fault.valueOf(scenario);
        StackResource resource = resource();
        assertThrows(IllegalStateException.class, () -> provision(resource, properties()));
        if ("BEFORE".equals(scenario)) {
            assertNull(resource.getPhysicalId());
            assertTrue(store.keys().isEmpty());
        } else {
            assertEquals("filter", resource.getPhysicalId());
            assertEquals(1, store.keys().size());
        }
        store.failReads = false;
        if ("UNINSPECTABLE".equals(scenario)) {
            JsonNode before = snapshot(GROUP, "filter");
            assertThrows(IllegalStateException.class, () -> provisioner.delete(resource, REGION));
            assertEquals(before, snapshot(GROUP, "filter"));
            logs.deleteSubscriptionFilter(GROUP, "filter", REGION);
        }
        provisioner.delete(resource, REGION);
        assertTrue(store.keys().isEmpty());
    }

    @Test
    void concurrentCreatesShareTheNativeQuotaMonitorAndOnlyOneAcquiresOwnership() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<StackResource> resources = new ArrayList<>();
        List<Future<Boolean>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
            for (int index = 0; index < 6; index++) {
                StackResource resource = resource();
                resources.add(resource);
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        provision(resource, properties());
                        return true;
                    } catch (AwsException e) {
                        assertEquals("AlreadyExistsException", e.getErrorCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int owned = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    owned++;
                }
            }
            assertEquals(1, owned);
            assertEquals(1, store.atomicPuts);
            assertEquals(1, store.keys().size());
        }
        for (StackResource resource : resources) {
            provisioner.delete(resource, REGION);
        }
        assertTrue(store.keys().isEmpty());
    }

    private enum Fault { BEFORE, AFTER, UNINSPECTABLE }

    private static final class FaultStore extends InMemoryStorage<String, SubscriptionFilter> {
        private Object monitor;
        private Fault putFailure;
        private Fault deleteFault;
        private final List<String> mutations = new ArrayList<>();
        private boolean deleteFailure;
        private boolean failReads;
        private int atomicPuts;

        @Override
        public void putAllAtomically(Map<String, SubscriptionFilter> entries) {
            assertTrue(Thread.holdsLock(monitor), "native metric/subscription quota monitor is held");
            Fault failure = putFailure;
            putFailure = null;
            if (failure == Fault.BEFORE) {
                throw new IllegalStateException("controlled atomic write refusal");
            }
            super.putAllAtomically(entries);
            atomicPuts++;
            mutations.add("put");
            if (failure == Fault.UNINSPECTABLE) {
                failReads = true;
            }
            if (failure != null) {
                throw new IllegalStateException("controlled post-write refusal");
            }
        }

        @Override
        public void deleteAllAtomically(Set<String> keys) {
            assertTrue(Thread.holdsLock(monitor));
            Fault failure = deleteFault;
            deleteFault = null;
            if (deleteFailure || failure == Fault.BEFORE) {
                deleteFailure = false;
                throw new IllegalStateException("controlled delete refusal");
            }
            super.deleteAllAtomically(keys);
            mutations.add("delete");
            if (failure == Fault.UNINSPECTABLE) {
                failReads = true;
            }
            if (failure != null) {
                throw new IllegalStateException("controlled post-delete refusal");
            }
        }

        @Override
        public Optional<SubscriptionFilter> get(String key) {
            if (failReads) {
                throw new IllegalStateException("controlled inspection refusal");
            }
            return super.get(key);
        }
    }
}
