package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.ProvisionedConcurrencyPool;
import io.github.hectorvent.floci.services.lambda.model.LambdaAlias;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Lambda Version/Alias CFN provisioner in isolation. Covers the two paths the SAM
 * integration test cannot reach: the already-exists fallback, and delete.
 */
class LambdaVersionAliasCfnProvisionerTest {

    private static final String ALIAS_ARN =
            "arn:aws:lambda:us-east-1:000000000000:function:my-fn:production";

    private final LambdaService lambda = mock(LambdaService.class);
    private final ProvisionedConcurrencyPool concurrency = mock(ProvisionedConcurrencyPool.class);
    private final LambdaAliasDeployment deployments = mock(LambdaAliasDeployment.class);
    private final LambdaVersionAliasCfnProvisioner provisioner = new LambdaVersionAliasCfnProvisioner(
            lambda, concurrency, deployments);
    private final TestTime time = TestTime.create();
    private final LambdaVersionAliasCfnProvisioner timed = new LambdaVersionAliasCfnProvisioner(
            lambda, concurrency, deployments, time::nanoTime, time::sleepAdvancing);
    private final ObjectMapper mapper = new ObjectMapper();

    private ProvisionContext ctx() {
        // Scalar properties only: intrinsic resolution (Ref/GetAtt) is the engine's job and is
        // covered by its own tests, so a passthrough keeps this a true isolated unit test.
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(inv -> {
            JsonNode node = inv.getArgument(0);
            return node == null ? null : node.asText();
        });
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(inv -> inv.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "my-stack");
    }

    private StackResource resource(String type, String logicalId) {
        StackResource r = new StackResource();
        r.setLogicalId(logicalId);
        r.setResourceType(type);
        r.setAttributes(new HashMap<>());
        return r;
    }

    private LambdaFunction publishedVersion() {
        LambdaFunction version = new LambdaFunction();
        version.setFunctionName("my-fn");
        version.setVersion("3");
        version.setFunctionArn("arn:aws:lambda:us-east-1:000000000000:function:my-fn:3");
        return version;
    }

    private LambdaAlias alias(String functionVersion) {
        LambdaAlias alias = new LambdaAlias();
        alias.setName("production");
        alias.setFunctionName("my-fn");
        alias.setFunctionVersion(functionVersion);
        alias.setAliasArn(ALIAS_ARN);
        return alias;
    }

    @Test
    void versionSetsPhysicalIdAndGetAttAttributes() {
        when(lambda.publishVersion(eq("us-east-1"), eq("my-fn"), isNull())).thenReturn(publishedVersion());

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-fn");
        StackResource r = resource("AWS::Lambda::Version", "MyFuncVersion");

        provisioner.provision(r, props, ctx());

        // Ref -> qualified ARN, GetAtt Version -> the number the Alias resource references.
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-fn:3", r.getPhysicalId());
        assertEquals("3", r.getAttributes().get("Version"));
        assertEquals("arn:aws:lambda:us-east-1:000000000000:function:my-fn:3",
                r.getAttributes().get("FunctionArn"));
    }

    @Test
    void versionWithoutFunctionNameFails() {
        StackResource r = resource("AWS::Lambda::Version", "MyFuncVersion");
        assertThrows(IllegalArgumentException.class,
                () -> provisioner.provision(r, mapper.createObjectNode(), ctx()));
        verifyNoInteractions(lambda);
    }

    @Test
    void aliasSetsPhysicalIdFromAliasArn() {
        when(lambda.createAlias(eq("us-east-1"), eq("my-fn"), eq("production"), eq("3"), isNull(), isNull()))
                .thenReturn(alias("3"));

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-fn");
        props.put("Name", "production");
        props.put("FunctionVersion", "3");
        StackResource r = resource("AWS::Lambda::Alias", "MyFuncAliasproduction");

        provisioner.provision(r, props, ctx());

        assertEquals(ALIAS_ARN, r.getPhysicalId());
        assertEquals(ALIAS_ARN, r.getAttributes().get("AliasArn"));
    }

    private ObjectNode updatePolicy(String group) {
        ObjectNode policy = mapper.createObjectNode();
        policy.putObject("CodeDeployLambdaAliasUpdate")
                .put("ApplicationName", "application").put("DeploymentGroupName", group);
        return policy;
    }

    private ProvisionContext updateContext(JsonNode policy, JsonNode priorPolicy) {
        ProvisionContext create = ctx();
        return new ProvisionContext(create.engine(), create.region(), create.accountId(), create.stackName(),
                ALIAS_ARN, event -> {}, mapper.createObjectNode(), policy, priorPolicy);
    }

    private ObjectNode aliasProperties() {
        return mapper.createObjectNode().put("FunctionName", "my-fn").put("Name", "production")
                .put("FunctionVersion", "3").put("Description", "changed");
    }

    @Test
    void rollbackRestoresTheActualAliasAndCapacityUsingThePriorDeploymentPolicy() {
        LambdaAlias prior = alias("2");
        prior.setDescription("previous");
        prior.setRoutingConfig(Map.of("1", 0.25));
        LambdaAlias updated = alias("3");
        when(lambda.getAlias("us-east-1", "my-fn", "production")).thenReturn(prior, updated);
        when(lambda.updateAlias(any(), any(), any(), any(), any(), any())).thenReturn(updated);
        when(concurrency.get("us-east-1", "my-fn", "production", "000000000000"))
                .thenReturn(capacity("READY", 2, null));
        when(concurrency.put("us-east-1", "my-fn", "production", 2, "000000000000"))
                .thenReturn(capacity("READY", 2, null));
        JsonNode policy = updatePolicy("new-group");
        JsonNode priorPolicy = updatePolicy("previous-group");
        StackResource resource = resource("AWS::Lambda::Alias", "Alias");
        resource.setPhysicalId(ALIAS_ARN);

        provisioner.provision(resource, aliasProperties(), updateContext(policy, priorPolicy));
        assertTrue(provisioner.rollbackUpdate(resource, event -> {}));

        InOrder order = inOrder(deployments, lambda, concurrency);
        order.verify(deployments).shift(eq(resource), eq("us-east-1"), eq(prior), eq("3"),
                eq(policy.get("CodeDeployLambdaAliasUpdate")), any());
        order.verify(lambda).updateAlias("us-east-1", "my-fn", "production", "3", "changed", Map.of());
        order.verify(deployments).stopBeforeRestore(eq(resource), eq("us-east-1"), any());
        order.verify(deployments).shift(eq(resource), eq("us-east-1"), eq(updated), eq("2"),
                eq(priorPolicy.get("CodeDeployLambdaAliasUpdate")), any());
        order.verify(lambda).updateAlias("us-east-1", "my-fn", "production", "2", "previous", Map.of("1", 0.25));
        order.verify(concurrency).put("us-east-1", "my-fn", "production", 2, "000000000000");
        verify(lambda, never()).createAlias(any(), any(), any(), any(), any(), any());
    }

    @Test
    void failedRestorationRetainsTheDeploymentAndSnapshotForRollbackRetry() {
        LambdaAlias prior = alias("2");
        when(lambda.getAlias("us-east-1", "my-fn", "production")).thenReturn(prior, alias("3"));
        noProvisionedConcurrency();
        JsonNode policy = updatePolicy("new-group");
        JsonNode priorPolicy = updatePolicy("previous-group");
        IllegalStateException failure = new IllegalStateException("post-traffic hook failed");
        doAnswer(invocation -> {
            StackResource resource = invocation.getArgument(0);
            resource.getAttributes().put(LambdaAliasDeployment.DEPLOYMENT_ATTR, "forward-deployment");
            throw failure;
        }).when(deployments).shift(any(), any(), any(), eq("3"), any(), any());
        doThrow(new IllegalStateException("deployment has not stopped")).doNothing()
                .when(deployments).stopBeforeRestore(any(), any(), any());
        StackResource attempted = resource("AWS::Lambda::Alias", "Alias");
        attempted.setPhysicalId(ALIAS_ARN);

        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                provisioner.provision(attempted, aliasProperties(), updateContext(policy, priorPolicy))));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(attempted.getAttributes().containsKey(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR));
        verify(lambda, never()).updateAlias(any(), any(), any(), any(), any(), any());

        StackResource restored = resource("AWS::Lambda::Alias", "Alias");
        restored.setPhysicalId(ALIAS_ARN);
        provisioner.mergeFailedUpdateResourceTracking(restored, attempted);
        assertEquals("forward-deployment", restored.getAttributes().get(LambdaAliasDeployment.DEPLOYMENT_ATTR));
        LambdaVersionAliasCfnProvisioner retry = new LambdaVersionAliasCfnProvisioner(lambda, concurrency, deployments);
        assertTrue(retry.rollbackUpdate(restored, event -> {}));
        verify(deployments).shift(eq(restored), eq("us-east-1"), any(), eq("2"),
                eq(priorPolicy.get("CodeDeployLambdaAliasUpdate")), any());
        verify(lambda).updateAlias("us-east-1", "my-fn", "production", "2", "", Map.of());
        verify(concurrency).delete("us-east-1", "my-fn", "production", "000000000000");
        assertFalse(restored.getAttributes().containsKey(LambdaAliasDeployment.DEPLOYMENT_ATTR));
    }

    private void noProvisionedConcurrency() {
        when(concurrency.get("us-east-1", "my-fn", "production", "000000000000"))
                .thenThrow(new AwsException("ProvisionedConcurrencyConfigNotFoundException",
                        "No Provisioned Concurrency Config found for this function", 404));
    }

    @Test
    void aliasWithoutProvisionedConcurrencyCanUpdateAndRollBack() {
        noProvisionedConcurrency();
        when(lambda.getAlias("us-east-1", "my-fn", "production")).thenReturn(alias("2"), alias("3"));
        when(lambda.updateAlias(any(), any(), any(), any(), any(), any())).thenReturn(alias("3"));
        StackResource resource = resource("AWS::Lambda::Alias", "Alias");
        resource.setPhysicalId(ALIAS_ARN);
        provisioner.provision(resource, aliasProperties(), updateContext(null, null));
        assertTrue(provisioner.rollbackUpdate(resource, event -> {}));
        verify(lambda).updateAlias("us-east-1", "my-fn", "production", "3", "changed", Map.of());
        verify(lambda).updateAlias("us-east-1", "my-fn", "production", "2", "", Map.of());
        verify(concurrency).delete("us-east-1", "my-fn", "production", "000000000000");
    }

    @Test
    void initialCreationWithAnUpdatePolicyDoesNotRunCodeDeploy() {
        when(lambda.createAlias(any(), any(), any(), any(), any(), any())).thenReturn(alias("3"));
        ProvisionContext create = ctx();
        ProvisionContext context = new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), null, event -> {}, null, updatePolicy("group"), null);
        provisioner.provision(resource("AWS::Lambda::Alias", "Alias"), aliasProperties(), context);
        verifyNoInteractions(deployments);
    }

    @Test
    void invalidProvisionedConcurrencyFailsBeforeCreatingTheAlias() throws Exception {
        when(lambda.createAlias(any(), any(), any(), any(), any(), any())).thenReturn(alias("3"));
        JsonNode props = mapper.readTree("""
            {"FunctionName":"my-fn","Name":"production","FunctionVersion":"3",
             "ProvisionedConcurrencyConfig":{"ProvisionedConcurrentExecutions":"invalid"}}
            """);
        StackResource r = resource("AWS::Lambda::Alias", "MyFuncAliasproduction");
        assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        verifyNoInteractions(lambda);
    }

    private JsonNode provisionedProperties() throws Exception {
        when(lambda.createAlias(any(), any(), any(), any(), any(), any())).thenReturn(alias("3"));
        return mapper.readTree("""
            {"FunctionName":"my-fn","Name":"production","FunctionVersion":"3",
             "ProvisionedConcurrencyConfig":{"ProvisionedConcurrentExecutions":2}}
            """);
    }

    private ProvisionedConcurrencyPool.Status capacity(String state, int allocated, String reason) {
        return new ProvisionedConcurrencyPool.Status(2, allocated, allocated, state, reason, "2026-01-01T00:00:00Z");
    }

    @Test
    void aliasWaitsForTheDeclaredCapacityToInitialize() throws Exception {
        JsonNode props = provisionedProperties();
        when(concurrency.put("us-east-1", "my-fn", "production", 2, "000000000000"))
                .thenReturn(capacity("IN_PROGRESS", 0, null));
        when(concurrency.get("us-east-1", "my-fn", "production", "000000000000"))
                .thenReturn(capacity("READY", 2, null));
        StackResource r = resource("AWS::Lambda::Alias", "Alias");
        timed.provision(r, props, ctx());
        verify(concurrency).get("us-east-1", "my-fn", "production", "000000000000");
        assertEquals(TestTime.DEFAULT_START.plusMillis(200), time.now(), "one 200 ms poll");
        assertEquals(ALIAS_ARN, r.getPhysicalId());
    }

    @Test
    void aliasInitializationFailureDoesNotReportSuccessfulProvisioning() throws Exception {
        JsonNode props = provisionedProperties();
        when(concurrency.put("us-east-1", "my-fn", "production", 2, "000000000000"))
                .thenReturn(capacity("FAILED", 0, "runtime initialization failed"));
        StackResource r = resource("AWS::Lambda::Alias", "Alias");
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        assertEquals("InternalFailure", failure.getErrorCode());
        assertTrue(failure.getMessage().contains("runtime initialization failed"));
        assertEquals(ALIAS_ARN, r.getPhysicalId(), "Failed initialization retains the alias identity for cleanup");
    }

    @Test
    void aliasInitializationFailsAfterFiveMinutesWithoutReadyCapacity() throws Exception {
        JsonNode props = provisionedProperties();
        capacityBecomesReadyAt(null);
        AwsException failure = assertThrows(AwsException.class,
                () -> timed.provision(resource("AWS::Lambda::Alias", "Alias"), props, ctx()));
        assertEquals("InternalFailure", failure.getErrorCode());
        assertEquals("Provisioned concurrency for alias production did not become READY (status IN_PROGRESS): "
                + "initialization deadline exceeded", failure.getMessage());
        assertEquals(TestTime.DEFAULT_START.plus(Duration.ofMinutes(5)), time.now());
        verify(concurrency, Mockito.times(1500)).get("us-east-1", "my-fn", "production", "000000000000");
    }

    @Test
    void capacityThatBecomesReadyAtTheFiveMinuteDeadlineStillCompletesTheAlias() throws Exception {
        JsonNode props = provisionedProperties();
        capacityBecomesReadyAt(TestTime.DEFAULT_START.plus(Duration.ofMinutes(5)));
        StackResource r = resource("AWS::Lambda::Alias", "Alias");
        timed.provision(r, props, ctx());
        assertEquals(TestTime.DEFAULT_START.plus(Duration.ofMinutes(5)), time.now());
        assertEquals(ALIAS_ARN, r.getPhysicalId());
    }

    @Test
    void capacityThatBecomesReadyJustAfterTheDeadlineIsNotWaitedFor() throws Exception {
        JsonNode props = provisionedProperties();
        capacityBecomesReadyAt(TestTime.DEFAULT_START.plus(Duration.ofMinutes(5)).plusNanos(1));
        AwsException failure = assertThrows(AwsException.class,
                () -> timed.provision(resource("AWS::Lambda::Alias", "Alias"), props, ctx()));
        assertTrue(failure.getMessage().contains("initialization deadline exceeded"), failure.getMessage());
        assertEquals(TestTime.DEFAULT_START.plus(Duration.ofMinutes(5)), time.now());
    }

    /** The capacity reads as IN_PROGRESS until the timeline reaches {@code instant}; always, when null. */
    private void capacityBecomesReadyAt(Instant instant) {
        when(concurrency.put("us-east-1", "my-fn", "production", 2, "000000000000"))
                .thenReturn(capacity("IN_PROGRESS", 0, null));
        when(concurrency.get("us-east-1", "my-fn", "production", "000000000000")).thenAnswer(call ->
                instant == null || time.now().isBefore(instant)
                        ? capacity("IN_PROGRESS", 0, null) : capacity("READY", 2, null));
    }

    @Test
    void interruptedInitializationPreservesTheInterruptAndFailsProvisioning() throws Exception {
        JsonNode props = provisionedProperties();
        when(concurrency.put("us-east-1", "my-fn", "production", 2, "000000000000"))
                .thenReturn(capacity("IN_PROGRESS", 0, null));
        boolean preserved;
        Thread.currentThread().interrupt();
        try {
            AwsException failure = assertThrows(AwsException.class,
                    () -> provisioner.provision(resource("AWS::Lambda::Alias", "Alias"), props, ctx()));
            assertTrue(failure.getMessage().contains("Interrupted"));
        } finally {
            preserved = Thread.interrupted();
        }
        assertTrue(preserved);
    }

    @Test
    void removingTheDeclaredPropertyDeletesItsAllocation() throws Exception {
        JsonNode prior = provisionedProperties();
        ObjectNode next = prior.deepCopy();
        next.remove("ProvisionedConcurrencyConfig");
        ProvisionContext create = ctx();
        ProvisionContext update = new ProvisionContext(create.engine(), create.region(), create.accountId(),
                create.stackName(), ALIAS_ARN, event -> {}, prior);
        when(lambda.getAlias("us-east-1", "my-fn", "production")).thenReturn(alias("3"));
        when(concurrency.get("us-east-1", "my-fn", "production", "000000000000"))
                .thenReturn(capacity("READY", 2, null));
        when(lambda.updateAlias(eq("us-east-1"), eq("my-fn"), eq("production"), eq("3"), eq(""), eq(Map.of())))
                .thenReturn(alias("3"));
        provisioner.provision(resource("AWS::Lambda::Alias", "Alias"), next, update);
        verify(concurrency).delete("us-east-1", "my-fn", "production", "000000000000");
    }

    @Test
    void anUndeclaredPropertyDoesNotDeleteAnExternalAllocation() throws Exception {
        ObjectNode props = provisionedProperties().deepCopy();
        props.remove("ProvisionedConcurrencyConfig");
        provisioner.provision(resource("AWS::Lambda::Alias", "Alias"), props, ctx());
        verifyNoInteractions(concurrency);
    }

    @Test
    void aliasSurvivingAPriorDeployIsUpdatedRatherThanFailingTheStack() {
        // Floci deletes and recreates a stack on redeploy; an alias that outlived the delete makes
        // createAlias conflict. Converging on it keeps a redeploy idempotent instead of leaving the
        // stack in ROLLBACK.
        when(lambda.createAlias(any(), any(), any(), any(), any(), any()))
                .thenThrow(new AwsException("ResourceConflictException", "Alias already exists: production", 409));
        // A template that declares no RoutingConfig means the alias carries no weights, so the
        // update clears them with an empty map rather than keeping whatever was there.
        when(lambda.updateAlias(eq("us-east-1"), eq("my-fn"), eq("production"), eq("4"), isNull(), eq(Map.of())))
                .thenReturn(alias("4"));

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-fn");
        props.put("Name", "production");
        props.put("FunctionVersion", "4");
        StackResource r = resource("AWS::Lambda::Alias", "MyFuncAliasproduction");

        provisioner.provision(r, props, ctx());

        verify(lambda).updateAlias(eq("us-east-1"), eq("my-fn"), eq("production"), eq("4"), isNull(), eq(Map.of()));
        assertEquals(ALIAS_ARN, r.getPhysicalId());
    }

    @Test
    void aliasFailureOtherThanConflictPropagates() {
        // Only the already-exists case is convergeable. A missing function (for example) must fail
        // the stack rather than be silently swallowed by the fallback.
        when(lambda.createAlias(any(), any(), any(), any(), any(), any()))
                .thenThrow(new AwsException("ResourceNotFoundException", "Function not found: my-fn", 404));

        ObjectNode props = mapper.createObjectNode();
        props.put("FunctionName", "my-fn");
        props.put("Name", "production");
        StackResource r = resource("AWS::Lambda::Alias", "MyFuncAliasproduction");

        AwsException e = assertThrows(AwsException.class, () -> provisioner.provision(r, props, ctx()));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void deleteAliasParsesFunctionAndAliasFromArn() {
        provisioner.delete("AWS::Lambda::Alias", ALIAS_ARN, "us-east-1");
        verify(lambda).deleteAlias("us-east-1", "my-fn", "production");
    }

    @Test
    void deleteAliasAlreadyGoneIsIdempotent() {
        // The function may have been deleted first, taking its aliases with it; a stack delete has
        // to stay idempotent rather than transition to DELETE_FAILED.
        Mockito.doThrow(new AwsException("ResourceNotFoundException", "Alias not found: production", 404))
                .when(lambda).deleteAlias(any(), any(), any());

        provisioner.delete("AWS::Lambda::Alias", ALIAS_ARN, "us-east-1");
    }

    @Test
    void deleteVersionIsANoOp() {
        // Lambda exposes no DeleteVersion; deleting the function drops its versions with it.
        provisioner.delete("AWS::Lambda::Version",
                "arn:aws:lambda:us-east-1:000000000000:function:my-fn:3", "us-east-1");
        verifyNoInteractions(lambda);
    }

    @Test
    void deleteToleratesAPhysicalIdThatIsNotAnAliasArn() {
        // Stacks created before this provisioner existed got the generic fallback's physical id
        // (logicalId + "-" + short uuid) for an explicitly declared Lambda::Alias. Deleting such a
        // stack after upgrading must not fail on an unparseable id.
        provisioner.delete("AWS::Lambda::Alias", "MyFuncAliasproduction-1a2b3c4d", "us-east-1");
        verifyNoInteractions(lambda);
    }
}
