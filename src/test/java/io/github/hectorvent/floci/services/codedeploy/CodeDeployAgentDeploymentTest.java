package io.github.hectorvent.floci.services.codedeploy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.github.hectorvent.floci.services.codedeploy.model.DeploymentGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeDeployAgentDeploymentTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-west-2";
    private static final String FIRST = "i-first";
    private static final String SECOND = "i-second";
    private static final List<String> PHASES = List.of("ApplicationStop", "DownloadBundle", "BeforeInstall",
            "Install", "AfterInstall", "ApplicationStart", "ValidateService");
    private static final Map<String, Object> REVISION = Map.of("revisionType", "S3",
            "s3Location", Map.of("bucket", "bundle", "key", "release.zip", "bundleType", "zip",
                    "version", "version-one", "eTag", "etag-one"));

    private CodeDeployAgentCommands commands;
    private CodeDeployAgentDeployment runner;
    private DeploymentGroup group;
    private AutoScalingService autoScaling;
    private CodeDeployClassicTraffic traffic;
    private final List<Running> runs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        CodeDeploySpecificationSigner signer = mock(CodeDeploySpecificationSigner.class);
        when(signer.sign(anyString())).thenAnswer(call -> call.getArgument(0));
        commands = new CodeDeployAgentCommands(new ObjectMapper(), signer, Duration.ZERO, Duration.ZERO);
        autoScaling = mock(AutoScalingService.class);
        traffic = new CodeDeployClassicTraffic(mock(ElbClassicService.class),
                mock(ServiceRoleCallAuthorizer.class), new ObjectMapper());
        runner = runner(Duration.ofSeconds(5), Duration.ofSeconds(5));
        group = new DeploymentGroup();
        group.setDeploymentGroupId("group-id");
        group.setDeploymentGroupName("group");
        group.setAutoScalingGroups(List.of(Map.of("name", "fleet")));
        members(FIRST);
    }

    @AfterEach
    void stopWorkers() throws Exception {
        runs.forEach(run -> run.stop().set(true));
        commands.shutdown();
        for (Running run : runs) {
            run.finished().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void selectsCurrentAutoScalingMembersAndSnapshotsTheExactS3Revision() {
        AutoScalingGroup fleet = fleet(FIRST, SECOND, FIRST);
        AsgInstance terminating = new AsgInstance();
        terminating.setInstanceId("i-terminating");
        terminating.setLifecycleState("Terminating");
        fleet.getInstances().add(terminating);
        when(autoScaling.describeAutoScalingGroups(REGION, List.of("fleet"))).thenReturn(List.of(fleet));
        CodeDeployAgentDeployment.Prepared prepared = prepare(null);
        assertEquals(List.of(FIRST, SECOND), prepared.instanceIds());
        assertEquals("arn:aws:ec2:us-west-2:123456789012:instance/i-first", prepared.hosts().get(FIRST));
        assertEquals(Map.of("Bucket", "bundle", "Key", "release.zip", "BundleType", "zip",
                "Version", "version-one", "ETag", "etag-one"), prepared.source());
        assertEquals("CodeDeployDefault.OneAtATime", prepared.config());
    }

    @Test
    void incompleteTrafficControlAndUnknownPoliciesCannotStartAnAgentDeployment() {
        assertThrows(AwsException.class, () -> prepare("CodeDeployDefault.HalfAtATime"));
        group.setDeploymentStyle(Map.of("deploymentType", "IN_PLACE", "deploymentOption", "WITH_TRAFFIC_CONTROL"));
        assertThrows(AwsException.class, () -> prepare(null));
        group.setDeploymentStyle(Map.of("deploymentType", "BLUE_GREEN"));
        assertThrows(AwsException.class, () -> prepare(null));
        group.setDeploymentStyle(null);
        group.setAlarmConfiguration(Map.of("enabled", true));
        assertThrows(AwsException.class, () -> prepare(null));
        assertTrue(commands.poll(ACCOUNT, REGION, host(FIRST)).isEmpty());
    }

    @Test
    void downloadFailureCannotBecomeASuccessfulInstallAndKeepsAgentDiagnostics() throws Exception {
        Running run = start(prepare(null));
        complete(FIRST, "ApplicationStop", "Succeeded", "{}");
        complete(FIRST, "DownloadBundle", "Failed",
                "{\"error_code\":5,\"script_name\":\"\",\"message\":\"S3 NoSuchKey\",\"log\":\"download failed\"}");
        done(run);
        assertEquals("Failed", run.deployment().getStatus());
        List<Map<String, Object>> events = events(run, FIRST);
        assertEquals(List.of("ApplicationStop", "DownloadBundle"),
                events.stream().map(event -> event.get("lifecycleEventName")).toList());
        assertEquals("Failed", events.getLast().get("status"));
        assertEquals(Map.of("errorCode", "UnknownError", "scriptName", "",
                "message", "S3 NoSuchKey", "logTail", "download failed"), events.getLast().get("diagnostics"));
        assertTrue(commands.poll(ACCOUNT, REGION, host(FIRST)).isEmpty());
    }

    @Test
    void everyLifecyclePhaseWaitsForItsActualAgentCompletion() throws Exception {
        Running run = start(prepare(null));
        for (String phase : PHASES) {
            String id = pending(FIRST, phase);
            commands.acknowledge(ACCOUNT, REGION, id);
            assertEquals("InProgress", run.deployment().getStatus());
            assertFalse(run.finished().isDone());
            assertTrue(commands.poll(ACCOUNT, REGION, host(FIRST)).isEmpty());
            commands.complete(ACCOUNT, REGION, id, "Succeeded", "{\"error_code\":0}");
        }
        done(run);
        assertEquals("Succeeded", run.deployment().getStatus());
        assertEquals(PHASES, events(run, FIRST).stream().map(event -> event.get("lifecycleEventName")).toList());
        assertTrue(events(run, FIRST).stream().allMatch(event -> "Succeeded".equals(event.get("status"))));
    }

    @Test
    void serviceTrafficPhasesWaitForDrainAndHealthBetweenStockAgentHooks() throws Exception {
        useTraffic();
        CompletableFuture<Void> drained = new CompletableFuture<>();
        CompletableFuture<Void> healthy = new CompletableFuture<>();
        CountDownLatch drainStarted = new CountDownLatch(1);
        CountDownLatch healthStarted = new CountDownLatch(1);
        doAnswer(call -> {
            boolean allow = call.getArgument(2);
            (allow ? healthStarted : drainStarted).countDown();
            (allow ? healthy : drained).get(5, TimeUnit.SECONDS);
            return null;
        }).when(traffic).change(any(), eq(FIRST), anyBoolean(), any(), any());
        Running run = start(prepare(null));
        try {
            complete(FIRST, "BeforeBlockTraffic", "Succeeded", "{}");
            assertTrue(drainStarted.await(5, TimeUnit.SECONDS));
            assertEquals("BlockTraffic", events(run, FIRST).getLast().get("lifecycleEventName"));
            assertEquals("InProgress", events(run, FIRST).getLast().get("status"));
            assertTrue(commands.poll(ACCOUNT, REGION, host(FIRST)).isEmpty());
            drained.complete(null);
            complete(FIRST, "AfterBlockTraffic", "Succeeded", "{}");
            for (String phase : PHASES) {
                complete(FIRST, phase, "Succeeded", "{}");
            }
            complete(FIRST, "BeforeAllowTraffic", "Succeeded", "{}");
            assertTrue(healthStarted.await(5, TimeUnit.SECONDS));
            assertEquals("AllowTraffic", events(run, FIRST).getLast().get("lifecycleEventName"));
            assertFalse(run.finished().isDone());
            assertTrue(commands.poll(ACCOUNT, REGION, host(FIRST)).isEmpty());
            healthy.complete(null);
            complete(FIRST, "AfterAllowTraffic", "Succeeded", "{}");
            done(run);
            assertEquals("Succeeded", run.deployment().getStatus());
            assertEquals(13, events(run, FIRST).size());
            assertTrue(events(run, FIRST).stream().allMatch(event -> "Succeeded".equals(event.get("status"))));
        } finally {
            drained.complete(null);
            healthy.complete(null);
        }
    }

    @Test
    void stopDuringServiceTrafficWaitSkipsTheRemainingAgentHooks() throws Exception {
        useTraffic();
        CountDownLatch draining = new CountDownLatch(1);
        doAnswer(call -> {
            draining.countDown();
            BooleanSupplier cancelled = call.getArgument(4);
            await().atMost(Duration.ofSeconds(5)).until(cancelled::getAsBoolean);
            throw new CancellationException("Stopped during drain");
        }).when(traffic).change(any(), eq(FIRST), eq(false), any(), any());
        Running run = start(prepare(null));
        complete(FIRST, "BeforeBlockTraffic", "Succeeded", "{}");
        assertTrue(draining.await(5, TimeUnit.SECONDS));
        run.stop().set(true);
        done(run);
        assertEquals("Stopped", run.deployment().getStatus());
        assertEquals("Skipped", events(run, FIRST).getLast().get("status"));
        assertEquals(2, events(run, FIRST).size());
        assertTrue(commands.poll(ACCOUNT, REGION, host(FIRST)).isEmpty());
    }

    @Test
    void allAtOncePermissionFailureReleasesAnotherHostsTrafficWait() throws Exception {
        useTraffic();
        members(FIRST, SECOND);
        CountDownLatch firstWaiting = new CountDownLatch(1);
        doAnswer(call -> {
            String instance = call.getArgument(1);
            if (FIRST.equals(instance)) {
                firstWaiting.countDown();
                BooleanSupplier cancelled = call.getArgument(4);
                await().atMost(Duration.ofSeconds(5)).until(cancelled::getAsBoolean);
                throw new CancellationException("Coordinator closed");
            }
            assertTrue(firstWaiting.await(5, TimeUnit.SECONDS));
            throw new AwsException("IAM_ROLE_PERMISSIONS", "Service role denied traffic change", 403);
        }).when(traffic).change(any(), anyString(), eq(false), any(), any());
        Running run = start(prepare("CodeDeployDefault.AllAtOnce"));
        complete(FIRST, "BeforeBlockTraffic", "Succeeded", "{}");
        complete(SECOND, "BeforeBlockTraffic", "Succeeded", "{}");
        done(run);
        assertEquals("Failed", run.deployment().getStatus());
        assertEquals("IAM_ROLE_PERMISSIONS", run.deployment().getErrorInformation().get("code"));
        assertEquals("Skipped", events(run, FIRST).getLast().get("status"));
        assertEquals("Failed", events(run, SECOND).getLast().get("status"));
        assertTrue(commands.poll(ACCOUNT, REGION, host(FIRST)).isEmpty());
        assertTrue(commands.poll(ACCOUNT, REGION, host(SECOND)).isEmpty());
    }

    private void useTraffic() {
        traffic = mock(CodeDeployClassicTraffic.class);
        group.setDeploymentStyle(Map.of("deploymentType", "IN_PLACE", "deploymentOption", "WITH_TRAFFIC_CONTROL"));
        when(traffic.prepare(ACCOUNT, REGION, group)).thenReturn(new CodeDeployClassicTraffic.Plan(
                ACCOUNT, REGION, "arn:aws:iam::123456789012:role/deploy", List.of("frontend")));
        runner = runner(Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    @Test
    void aMissingAgentFailsTheStartDeadlineWithoutInventingInstallSuccess() throws Exception {
        runner = runner(Duration.ofMillis(25), Duration.ofSeconds(5));
        Running run = start(prepare(null));
        done(run);
        assertEquals("Failed", run.deployment().getStatus());
        Map<String, Object> event = events(run, FIRST).getFirst();
        assertEquals("ApplicationStop", event.get("lifecycleEventName"));
        assertEquals("Failed", event.get("status"));
        assertTrue(event.get("diagnostics").toString().contains("did not start"));
        assertTrue(commands.poll(ACCOUNT, REGION, host(FIRST)).isEmpty());
    }

    @Test
    void anAcknowledgedCommandHasItsOwnCompletionDeadlineAndRejectsLateSuccess() throws Exception {
        runner = runner(Duration.ofSeconds(5), Duration.ofMillis(25));
        Running run = start(prepare(null));
        String id = pending(FIRST, "ApplicationStop");
        commands.acknowledge(ACCOUNT, REGION, id);
        done(run);
        assertEquals("Failed", run.deployment().getStatus());
        assertTrue(events(run, FIRST).getFirst().get("diagnostics").toString().contains("did not complete"));
        assertThrows(AwsException.class, () -> commands.complete(ACCOUNT, REGION, id, "Succeeded", "{}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Succeeded", "Failed"})
    void stoppingAnAcknowledgedCommandWaitsForItsCompletionWithoutQueuingAnotherPhase(String completion)
            throws Exception {
        Running run = start(prepare(null));
        String id = pending(FIRST, "ApplicationStop");
        commands.acknowledge(ACCOUNT, REGION, id);
        run.stop().set(true);
        assertThrows(TimeoutException.class, () -> run.finished().get(350, TimeUnit.MILLISECONDS));
        assertEquals("InProgress", run.deployment().getStatus());
        assertEquals("InProgress", commands.update(ACCOUNT, REGION, id).get("CommandStatus"));
        commands.complete(ACCOUNT, REGION, id, completion, "{}");
        done(run);
        assertEquals("Stopped", run.deployment().getStatus());
        assertEquals(1, events(run, FIRST).size());
        assertEquals(completion, events(run, FIRST).getFirst().get("status"));
        assertTrue(commands.poll(ACCOUNT, REGION, host(FIRST)).isEmpty());
    }

    @Test
    void stopCancelsAQueuedCommandWithoutWaitingForTheStartDeadline() throws Exception {
        Running run = start(prepare(null));
        String id = pending(FIRST, "ApplicationStop");
        run.stop().set(true);
        done(run);
        assertEquals("Stopped", run.deployment().getStatus());
        assertEquals("Skipped", events(run, FIRST).getFirst().get("status"));
        assertEquals("Failed", commands.acknowledge(ACCOUNT, REGION, id).get("CommandStatus"));
        assertTrue(commands.poll(ACCOUNT, REGION, host(FIRST)).isEmpty());
    }

    @Test
    void allAtOnceStopWaitsForTheActiveHostAfterCancellingAQueuedPeer() throws Exception {
        members(FIRST, SECOND);
        Running run = start(prepare("CodeDeployDefault.AllAtOnce"));
        String active = pending(FIRST, "ApplicationStop");
        commands.acknowledge(ACCOUNT, REGION, active);
        pending(SECOND, "ApplicationStop");
        run.stop().set(true);
        assertThrows(TimeoutException.class, () -> run.finished().get(350, TimeUnit.MILLISECONDS));
        assertEquals("InProgress", run.deployment().getStatus());
        assertTrue(commands.poll(ACCOUNT, REGION, host(SECOND)).isEmpty());
        commands.complete(ACCOUNT, REGION, active, "Succeeded", "{}");
        done(run);
        assertEquals("Stopped", run.deployment().getStatus());
        assertEquals("Succeeded", events(run, FIRST).getFirst().get("status"));
        assertEquals("Skipped", events(run, SECOND).getFirst().get("status"));
    }

    @Test
    void allAtOnceStopWaitsForEveryAcknowledgedCommand() throws Exception {
        members(FIRST, SECOND);
        Running run = start(prepare("CodeDeployDefault.AllAtOnce"));
        String first = pending(FIRST, "ApplicationStop");
        String second = pending(SECOND, "ApplicationStop");
        commands.acknowledge(ACCOUNT, REGION, first);
        commands.acknowledge(ACCOUNT, REGION, second);
        run.stop().set(true);
        commands.complete(ACCOUNT, REGION, first, "Succeeded", "{}");
        assertThrows(TimeoutException.class, () -> run.finished().get(350, TimeUnit.MILLISECONDS));
        assertEquals("InProgress", commands.update(ACCOUNT, REGION, second).get("CommandStatus"));
        commands.complete(ACCOUNT, REGION, second, "Succeeded", "{}");
        done(run);
        assertEquals("Stopped", run.deployment().getStatus());
        assertEquals(1, events(run, FIRST).size());
        assertEquals(1, events(run, SECOND).size());
    }

    @Test
    void allAtOncePermissionFailureWaitsForAnAcknowledgedPeerBeforeReleasingTheGroup() throws Exception {
        useTraffic();
        members(FIRST, SECOND);
        doAnswer(call -> {
            throw new AwsException("IAM_ROLE_PERMISSIONS", "Service role denied traffic change", 403);
        }).when(traffic).change(any(), eq(FIRST), eq(false), any(), any());
        Running run = start(prepare("CodeDeployDefault.AllAtOnce"));
        String active = pending(SECOND, "BeforeBlockTraffic");
        commands.acknowledge(ACCOUNT, REGION, active);
        complete(FIRST, "BeforeBlockTraffic", "Succeeded", "{}");
        assertThrows(TimeoutException.class, () -> run.finished().get(350, TimeUnit.MILLISECONDS));
        assertEquals("InProgress", commands.update(ACCOUNT, REGION, active).get("CommandStatus"));
        commands.complete(ACCOUNT, REGION, active, "Succeeded", "{}");
        done(run);
        assertEquals("Failed", run.deployment().getStatus());
        assertEquals("IAM_ROLE_PERMISSIONS", run.deployment().getErrorInformation().get("code"));
        assertEquals("Succeeded", events(run, SECOND).getFirst().get("status"));
        assertEquals(1, events(run, SECOND).size());
    }

    @Test
    void allAtOnceStartsBothHostsAndSucceedsWhenOneActuallyInstalls() throws Exception {
        members(FIRST, SECOND);
        Running run = start(prepare("CodeDeployDefault.AllAtOnce"));
        pending(FIRST, "ApplicationStop");
        pending(SECOND, "ApplicationStop");
        complete(FIRST, "ApplicationStop", "Failed", "{\"error_code\":4,\"message\":\"script failed\"}");
        for (String phase : PHASES) {
            complete(SECOND, phase, "Succeeded", "{}");
        }
        done(run);
        assertEquals("Succeeded", run.deployment().getStatus());
        assertEquals("Failed", target(run, FIRST).get("status"));
        assertEquals("Succeeded", target(run, SECOND).get("status"));
    }

    @Test
    void oneAtATimeStopsOnAnEarlyFailureWithoutSendingCommandsToTheRemainingHosts() throws Exception {
        members(FIRST, SECOND);
        Running run = start(prepare(null));
        complete(FIRST, "ApplicationStop", "Failed", "{\"error_code\":4}");
        done(run);
        assertEquals("Failed", run.deployment().getStatus());
        assertEquals("Skipped", target(run, SECOND).get("status"));
        assertTrue(events(run, SECOND).isEmpty());
        assertTrue(commands.poll(ACCOUNT, REGION, host(SECOND)).isEmpty());
    }

    @Test
    void oneAtATimeAllowsTheDocumentedLastHostFailure() throws Exception {
        members(FIRST, SECOND);
        Running run = start(prepare(null));
        pending(FIRST, "ApplicationStop");
        assertTrue(commands.poll(ACCOUNT, REGION, host(SECOND)).isEmpty());
        for (String phase : PHASES) {
            complete(FIRST, phase, "Succeeded", "{}");
        }
        complete(SECOND, "ApplicationStop", "Failed", "{\"error_code\":4}");
        done(run);
        assertEquals("Succeeded", run.deployment().getStatus());
        assertEquals("Failed", target(run, SECOND).get("status"));
    }

    private CodeDeployAgentDeployment runner(Duration start, Duration complete) {
        return new CodeDeployAgentDeployment(commands, autoScaling, mock(Ec2Service.class),
                new RegionResolver(REGION, ACCOUNT), new ObjectMapper(), traffic, start, complete);
    }

    private CodeDeployAgentDeployment.Prepared prepare(String config) {
        return runner.prepare(REGION, group, config, REVISION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "codeDeployRollback"})
    void fileBehaviorAndCreatorArePartOfTheSignedAgentSpecification(String creator) throws Exception {
        CodeDeployAgentDeployment.Prepared prepared = runner.prepare(REGION, group, null, REVISION, "RETAIN");
        Deployment deployment = new Deployment();
        deployment.setDeploymentId("d-options");
        deployment.setApplicationName("app");
        deployment.setDeploymentGroupName("group");
        deployment.setCreator(creator);
        CodeDeployAgentCommands.Session session = runner.open(prepared, deployment);
        Map<?, ?> specification = (Map<?, ?>) commands.specification(ACCOUNT, REGION, "d-options", host(FIRST))
                .get("DeploymentSpecification");
        Map<?, ?> envelope = (Map<?, ?>) specification.get("GenericEnvelope");
        assertEquals("RETAIN", new ObjectMapper().readTree((String) envelope.get("Payload"))
                .path("AgentActionOverrides").path("AgentOverrides").path("FileExistsBehavior").asText());
        assertEquals(creator, new ObjectMapper().readTree((String) envelope.get("Payload"))
                .path("DeploymentCreator").asText());
        commands.close(session);
    }

    @Test
    void enabledAlarmsAndInvalidFileBehaviorFailBeforeAgentAdmission() {
        assertThrows(AwsException.class, () -> runner.prepare(REGION, group, null, REVISION, "unknown"));
        assertThrows(AwsException.class, () -> runner.prepare(REGION, group, null, REVISION, null));
        group.setAlarmConfiguration(Map.of("enabled", true, "alarms", List.of(Map.of("name", "health"))));
        assertThrows(AwsException.class, () -> prepare(null));
        assertTrue(commands.poll(ACCOUNT, REGION, host(FIRST)).isEmpty());
    }

    private void members(String... ids) {
        when(autoScaling.describeAutoScalingGroups(REGION, List.of("fleet"))).thenReturn(List.of(fleet(ids)));
    }

    private AutoScalingGroup fleet(String... ids) {
        AutoScalingGroup fleet = new AutoScalingGroup();
        for (String id : ids) {
            AsgInstance instance = new AsgInstance();
            instance.setInstanceId(id);
            instance.setLifecycleState("InService");
            fleet.getInstances().add(instance);
        }
        return fleet;
    }

    private Running start(CodeDeployAgentDeployment.Prepared prepared) {
        Deployment deployment = new Deployment();
        deployment.setDeploymentId("d-test");
        deployment.setApplicationName("app");
        deployment.setDeploymentGroupName("group");
        deployment.setStatus("Queued");
        List<Map<String, Object>> targets = prepared.instanceIds().stream().map(id -> {
            Map<String, Object> target = new ConcurrentHashMap<>();
            target.put("targetId", id);
            target.put("status", "Pending");
            target.put("lifecycleEvents", new CopyOnWriteArrayList<>());
            return target;
        }).toList();
        CodeDeployAgentCommands.Session session = runner.open(prepared, deployment);
        AtomicBoolean stop = new AtomicBoolean();
        CompletableFuture<Void> finished = new CompletableFuture<>();
        Running run = new Running(deployment, targets, stop, finished);
        runs.add(run);
        Thread.ofVirtual().start(() -> {
            try {
                runner.run(prepared, session, deployment, targets, stop);
                finished.complete(null);
            } catch (Throwable failure) {
                finished.completeExceptionally(failure);
            }
        });
        return run;
    }

    private String pending(String instance, String phase) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(5))
                .until(() -> commands.poll(ACCOUNT, REGION, host(instance)).containsKey("HostCommand"));
        Map<?, ?> command = (Map<?, ?>) commands.poll(ACCOUNT, REGION, host(instance)).get("HostCommand");
        assertEquals(phase, command.get("CommandName"));
        return (String) command.get("HostCommandIdentifier");
    }

    private void complete(String instance, String phase, String status, String diagnostics) {
        String id = pending(instance, phase);
        commands.acknowledge(ACCOUNT, REGION, id);
        commands.complete(ACCOUNT, REGION, id, status, diagnostics);
    }

    private void done(Running run) throws Exception {
        run.finished().get(5, TimeUnit.SECONDS);
        assertNotNull(run.deployment().getCompleteTime());
    }

    private String host(String id) {
        return "arn:aws:ec2:" + REGION + ":" + ACCOUNT + ":instance/" + id;
    }

    private Map<String, Object> target(Running run, String id) {
        return run.targets().stream().filter(target -> id.equals(target.get("targetId"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(Running run, String id) {
        return (List<Map<String, Object>>) target(run, id).get("lifecycleEvents");
    }

    private record Running(Deployment deployment, List<Map<String, Object>> targets,
                           AtomicBoolean stop, CompletableFuture<Void> finished) {}
}
