package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.ProvisionedConcurrencyPool;
import io.floci.aws.services.lambda.model.LambdaAlias;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LambdaAliasProvisionedConcurrencyCfnProvisionerTest {
    private static final String ARN = "arn:aws:lambda:us-east-1:000000000000:function:pc-function:live";
    private final ObjectMapper mapper = new ObjectMapper();
    private final LambdaService lambda = mock(LambdaService.class);
    private final ProvisionedConcurrencyPool pool = mock(ProvisionedConcurrencyPool.class);
    private final AtomicLong time = new AtomicLong();
    private final LambdaVersionAliasCfnProvisioner provisioner = new LambdaVersionAliasCfnProvisioner(
            lambda, pool, time::get, delay -> time.addAndGet(delay.toNanos()));

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"bad\"", "{}", "{\"ProvisionedConcurrentExecutions\":null}",
            "{\"ProvisionedConcurrentExecutions\":0}", "{\"ProvisionedConcurrentExecutions\":-1}",
            "{\"ProvisionedConcurrentExecutions\":1.5}", "{\"ProvisionedConcurrentExecutions\":2147483648}",
            "{\"ProvisionedConcurrentExecutions\":true}"})
    void invalidDeclarationFailsBeforeAliasOrCapacityMutation(String config) throws Exception {
        ObjectNode props = properties();
        props.set("ProvisionedConcurrencyConfig", mapper.readTree(config));
        assertThrows(AwsException.class, () -> provisioner.provision(resource(), props, context()));
        verifyNoInteractions(lambda, pool);
    }

    @Test
    void waitsForExactRequestedAndAllocatedCapacityBeforeReturning() throws Exception {
        alias();
        when(pool.put(anyString(), anyString(), anyString(), eq(2), anyString()))
                .thenReturn(status(2, 0, "IN_PROGRESS", null));
        when(pool.get(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(status(1, 1, "READY", null), status(2, 1, "READY", null),
                        status(2, 2, "READY", null));
        StackResource resource = resource();

        provisioner.provision(resource, properties(), context());

        assertEquals(ARN, resource.getPhysicalId());
        assertEquals(ARN, resource.getAttributes().get("AliasArn"));
        assertFalse(resource.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
        assertEquals(Duration.ofMillis(600).toNanos(), time.get());
        verify(pool, times(3)).get("us-east-1", "pc-function", "live", "000000000000");
    }

    @Test
    void failedInitializationRetainsPhysicalIdentityForRollback() throws Exception {
        alias();
        when(pool.put(anyString(), anyString(), anyString(), eq(2), anyString()))
                .thenReturn(status(2, 0, "FAILED", "runtime initialization failed"));
        StackResource resource = resource();

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, properties(), context()));

        assertEquals("InternalFailure", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("runtime initialization failed"));
        assertEquals(ARN, resource.getPhysicalId());
        assertEquals(ARN, resource.getAttributes().get("AliasArn"));
        assertEquals("true", resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR));
        verify(pool, never()).get(anyString(), anyString(), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedInitializationDoesNotMarkAReusedAliasForDeletion(boolean update) throws Exception {
        LambdaAlias alias = new LambdaAlias();
        alias.setAliasArn(ARN);
        when(lambda.createAlias(anyString(), anyString(), anyString(), anyString(), isNull(), isNull()))
                .thenThrow(new AwsException("ResourceConflictException", "Alias already exists", 409));
        when(lambda.updateAlias(anyString(), anyString(), anyString(), anyString(), isNull(), eq(Map.of())))
                .thenReturn(alias);
        when(pool.put(anyString(), anyString(), anyString(), eq(2), anyString()))
                .thenReturn(status(2, 0, "FAILED", "runtime initialization failed"));
        ProvisionContext create = context();
        ProvisionContext context = update
                ? new ProvisionContext(create.engine(), create.region(), create.accountId(), create.stackName(), ARN)
                : create;
        StackResource resource = resource();

        assertThrows(AwsException.class, () -> provisioner.provision(resource, properties(), context));

        assertEquals(ARN, resource.getPhysicalId());
        assertFalse(resource.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
        verify(lambda, never()).deleteAlias(anyString(), anyString(), anyString());
    }

    @Test
    void newlyCreatedAliasDuringUpdateRecordsDisplacedAliasWhenCapacityIsReady() throws Exception {
        alias();
        when(pool.put(anyString(), anyString(), anyString(), eq(2), anyString()))
                .thenReturn(status(2, 2, "READY", null));
        ProvisionContext create = context();
        ProvisionContext update = new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), ARN.replace(":live", ":prior"));
        StackResource resource = resource();

        provisioner.provision(resource, properties(), update);

        assertEquals(ARN, resource.getPhysicalId());
        assertFalse(resource.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
        assertEquals(Set.of(update.priorPhysicalId()), ReplacementCleanup.owedPhysicalIds(resource));
        verify(lambda, never()).deleteAlias(anyString(), anyString(), anyString());
        UpdateCleanupResult cleanup = provisioner.completeUpdate(resource);
        assertTrue(cleanup.applicable());
        assertTrue(cleanup.complete());
        assertEquals(update.priorPhysicalId(), cleanup.previousPhysicalId());
        verify(lambda).deleteAlias("us-east-1", "pc-function", "prior");
        verify(lambda, never()).deleteAlias("us-east-1", "pc-function", "live");
    }

    @Test
    void successfulReplacementRollbackRestoresPriorIdentityAndAttributes() throws Exception {
        alias();
        when(pool.put(anyString(), anyString(), anyString(), eq(2), anyString()))
                .thenReturn(status(2, 2, "READY", null));
        ProvisionContext create = context();
        String priorArn = ARN.replace(":live", ":prior");
        ProvisionContext update = new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), priorArn);
        StackResource resource = resource();
        resource.setPhysicalId(priorArn);
        resource.getAttributes().put("AliasArn", priorArn);
        resource.getAttributes().put("PriorMarker", "retained");

        provisioner.provision(resource, properties(), update);

        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(priorArn, resource.getPhysicalId());
        assertEquals(priorArn, resource.getAttributes().get("AliasArn"));
        assertEquals("retained", resource.getAttributes().get("PriorMarker"));
        verify(lambda).deleteAlias("us-east-1", "pc-function", "live");
        verify(lambda, never()).deleteAlias("us-east-1", "pc-function", "prior");
        assertFalse(provisioner.completeUpdate(resource).applicable());
    }

    @ParameterizedTest
    @ValueSource(strings = {"prior", "live"})
    void failedInitializationRemovesAnAliasCreatedDuringAnUpdate(String priorName) throws Exception {
        alias();
        when(pool.put(anyString(), anyString(), anyString(), eq(2), anyString()))
                .thenReturn(status(2, 0, "FAILED", "runtime initialization failed"));
        ProvisionContext create = context();
        ProvisionContext update = new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), ARN.replace(":live", ":" + priorName));
        StackResource resource = resource();

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, properties(), update));

        assertEquals("InternalFailure", failure.getErrorCode());
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(ARN, resource.getPhysicalId());
        assertFalse(resource.getAttributes().containsKey(CfnRollback.ROLLBACK_OWNED_ATTR));
        assertFalse(resource.getAttributes().containsKey(CfnRollback.REPLACEMENT_CLEANUP_ATTR));
        verify(lambda).deleteAlias("us-east-1", "pc-function", "live");
        verify(lambda, never()).deleteAlias("us-east-1", "pc-function", "prior");
    }

    @Test
    void failedCreatedAliasCleanupIsMergedAndRetriedWithoutDeletingThePriorAlias() throws Exception {
        alias();
        when(pool.put(anyString(), anyString(), anyString(), eq(2), anyString()))
                .thenReturn(status(2, 0, "FAILED", "runtime initialization failed"));
        AwsException denied = new AwsException("AccessDeniedException", "cleanup denied", 403);
        doThrow(denied).doThrow(denied).doThrow(denied).doThrow(denied).doNothing()
                .when(lambda).deleteAlias("us-east-1", "pc-function", "live");
        ProvisionContext create = context();
        String priorArn = ARN.replace(":live", ":prior");
        ProvisionContext update = new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), priorArn);
        StackResource attempted = resource();

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(attempted, properties(), update));

        assertEquals("InternalFailure", failure.getErrorCode());
        assertArrayEquals(new Throwable[] {denied}, failure.getSuppressed());
        assertTrue(attempted.getAttributes().get(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR).contains("cleanup denied"));
        assertEquals(Set.of(ARN), ReplacementCleanup.owedPhysicalIds(attempted));
        StackResource previous = resource();
        previous.setPhysicalId(priorArn);
        previous.getAttributes().put("AliasArn", priorArn);
        CloudFormationResourceRegistry registry = mock(CloudFormationResourceRegistry.class);
        when(registry.forType("AWS::Lambda::Alias")).thenReturn(Optional.of(provisioner));
        CfnResourceDispatcher dispatcher = new CfnResourceDispatcher(mapper, registry, null, null);

        dispatcher.mergeFailedUpdateResourceTracking(previous, attempted);

        assertEquals(priorArn, previous.getPhysicalId());
        assertEquals(priorArn, previous.getAttributes().get("AliasArn"));
        assertEquals(Set.of(ARN), ReplacementCleanup.owedPhysicalIds(previous));
        assertTrue(dispatcher.hasReplacementUpdate(previous));
        assertEquals(ARN, dispatcher.updateCleanupPhysicalId(previous));
        previous.setUpdateReplacePolicy("Retain");
        for (int attempt = 1; attempt <= 3; attempt++) {
            UpdateCleanupResult failed = dispatcher.completeDeleteCleanup(previous);
            assertTrue(failed.applicable());
            assertFalse(failed.complete());
            assertEquals(attempt, failed.attempts());
            assertEquals("cleanup denied", failed.failureReason());
            assertEquals(Set.of(ARN), ReplacementCleanup.owedPhysicalIds(previous));
        }
        dispatcher.clearDeleteCleanup(previous);
        assertTrue(dispatcher.hasReplacementUpdate(previous));
        assertEquals(Set.of(ARN), ReplacementCleanup.owedPhysicalIds(previous));

        UpdateCleanupResult removed = dispatcher.completeDeleteCleanup(previous);

        assertTrue(removed.applicable());
        assertTrue(removed.complete());
        dispatcher.clearDeleteCleanup(previous);
        assertFalse(dispatcher.hasReplacementUpdate(previous));
        assertEquals(Map.of("AliasArn", priorArn), previous.getAttributes());
        verify(lambda, times(5)).deleteAlias("us-east-1", "pc-function", "live");
        verify(lambda, never()).deleteAlias("us-east-1", "pc-function", "prior");
    }

    @ParameterizedTest
    @CsvSource({"404,ResourceNotFoundException,true", "404,AccessDeniedException,false",
            "403,ResourceNotFoundException,false", "500,InternalFailure,false"})
    void aliasCleanupAcceptsOnlyExactTypedAbsence(int status, String code, boolean absent) {
        AwsException failure = new AwsException(code, "delete outcome", status);
        doThrow(failure).when(lambda).deleteAlias("us-east-1", "pc-function", "live");

        if (absent) {
            assertDoesNotThrow(() -> provisioner.delete("AWS::Lambda::Alias", ARN, "us-east-1"));
        } else {
            assertSame(failure, assertThrows(AwsException.class,
                    () -> provisioner.delete("AWS::Lambda::Alias", ARN, "us-east-1")));
        }
    }

    @Test
    void orphanRetryResetPreservesOtherTypesAndRetainableCleanupEntries() throws Exception {
        StackResource resource = resource();
        resource.setPhysicalId(ARN.replace(":live", ":committed"));
        ReplacementCleanup.recordOrphan(resource, ARN, "AWS::Lambda::Alias", "us-east-1");
        ReplacementCleanup.recordOrphan(resource, ARN.replace(":live", ":3"), "AWS::Lambda::Version", "us-east-1");
        ProvisionContext create = context();
        ProvisionContext update = new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), ARN.replace(":live", ":prior"));
        ReplacementCleanup.record(resource, update, Map.of("AliasArn", update.priorPhysicalId()));
        for (int attempt = 1; attempt <= 3; attempt++) {
            UpdateCleanupResult result = ReplacementCleanup.complete(resource, (type, id, region) -> {
                throw new AwsException("AccessDeniedException", "still owned", 403);
            });
            assertEquals(attempt, result.attempts());
        }
        ObjectNode expected = (ObjectNode) mapper.readTree(
                resource.getAttributes().get(CfnRollback.REPLACEMENT_CLEANUP_ATTR));
        expected.path("displaced").forEach(entry -> {
            if (ARN.equals(entry.path("physicalId").asText())) {
                ((ObjectNode) entry).put("cleanupAttempts", 0);
            }
        });

        ReplacementCleanup.retainExhaustedOrphansForRetry(resource, "AWS::Lambda::Alias");

        assertEquals(expected, mapper.readTree(resource.getAttributes().get(CfnRollback.REPLACEMENT_CLEANUP_ATTR)));
    }

    @Test
    void fiveMinuteDeadlineUsesElapsedTimeAcrossNanoTimeWrap() throws Exception {
        alias();
        when(pool.put(anyString(), anyString(), anyString(), eq(2), anyString()))
                .thenReturn(status(2, 0, "IN_PROGRESS", null));
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 1000);
        long started = clock.get();
        AtomicInteger polls = new AtomicInteger();
        when(pool.get(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            long elapsed = clock.get() - started;
            assertEquals(polls.incrementAndGet() == 1
                    ? Duration.ofMinutes(5).toNanos() - 1 : Duration.ofMinutes(5).toNanos(), elapsed);
            return status(2, 0, "IN_PROGRESS", null);
        });
        LambdaVersionAliasCfnProvisioner bounded = new LambdaVersionAliasCfnProvisioner(
                lambda, pool, clock::get, delay -> {
                    assertEquals(Duration.ofMillis(200), delay);
                    clock.addAndGet(polls.get() == 0 ? Duration.ofMinutes(5).toNanos() - 1 : 1);
                });

        AwsException failure = assertThrows(AwsException.class,
                () -> bounded.provision(resource(), properties(), context()));

        assertEquals("InternalFailure", failure.getErrorCode());
        assertEquals(2, polls.get(), "Still polls just before five minutes; fails at the unchanged deadline");
    }

    @Test
    void interruptionPreservesInterruptAndAliasIdentity() throws Exception {
        alias();
        when(pool.put(anyString(), anyString(), anyString(), eq(2), anyString()))
                .thenReturn(status(2, 0, "IN_PROGRESS", null));
        LambdaVersionAliasCfnProvisioner interrupted = new LambdaVersionAliasCfnProvisioner(
                lambda, pool, time::get, delay -> { throw new InterruptedException("test interrupt"); });
        StackResource resource = resource();
        boolean interruptPreserved;
        try {
            AwsException failure = assertThrows(AwsException.class,
                    () -> interrupted.provision(resource, properties(), context()));
            assertTrue(failure.getMessage().contains("Interrupted"));
        } finally {
            interruptPreserved = Thread.interrupted();
        }
        assertTrue(interruptPreserved);
        assertEquals(ARN, resource.getPhysicalId());
    }

    @Test
    void removingPreviouslyDeclaredCapacityDeletesOnlyThatAliasAllocation() throws Exception {
        alias();
        ObjectNode prior = properties();
        ObjectNode next = prior.deepCopy();
        next.remove("ProvisionedConcurrencyConfig");
        ProvisionContext create = context();
        ProvisionContext update = new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), ARN, event -> {}, prior);

        provisioner.provision(resource(), next, update);

        verify(pool).delete("us-east-1", "pc-function", "live", "000000000000");
        verifyNoMoreInteractions(pool);
    }

    @Test
    void templateThatNeverDeclaredCapacityPreservesExternalAllocation() throws Exception {
        alias();
        ObjectNode props = properties();
        props.remove("ProvisionedConcurrencyConfig");
        ProvisionContext create = context();
        ProvisionContext update = new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), ARN, event -> {}, props);

        provisioner.provision(resource(), props, update);

        verifyNoInteractions(pool);
    }

    private ObjectNode properties() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"FunctionName":"pc-function","Name":"live","FunctionVersion":"3",
                 "ProvisionedConcurrencyConfig":{"ProvisionedConcurrentExecutions":2}}
                """);
    }

    private void alias() {
        LambdaAlias alias = new LambdaAlias();
        alias.setAliasArn(ARN);
        when(lambda.createAlias(anyString(), anyString(), anyString(), anyString(), isNull(), isNull()))
                .thenReturn(alias);
    }

    private ProvisionContext context() {
        CloudFormationTemplateEngine engine = ProvisionContexts.passthroughEngine();
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(call -> call.getArgument(0));
        return new ProvisionContext(engine, "us-east-1",
                "000000000000", "pc-stack");
    }

    private StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Alias");
        resource.setResourceType("AWS::Lambda::Alias");
        resource.setAttributes(new HashMap<>());
        return resource;
    }

    private ProvisionedConcurrencyPool.Status status(int requested, int allocated, String state, String reason) {
        return new ProvisionedConcurrencyPool.Status(requested, allocated, allocated, state, reason,
                "2026-11-13T00:00:00Z");
    }
}
