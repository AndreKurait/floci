package io.github.hectorvent.floci.services.codedeploy;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService.LaunchInstance;
import io.github.hectorvent.floci.services.autoscaling.model.LaunchLifecycleAction;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService.AutoScalingGroupReference;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService.AutoScalingLaunchView;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService.AutomaticDeployment;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService.SuccessfulAgentRevision;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.github.hectorvent.floci.services.codedeploy.model.DeploymentGroup;
import io.quarkus.runtime.ShutdownEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodeDeployAutoScalingTest {
    private static final AutoScalingGroupReference REFERENCE =
            new AutoScalingGroupReference("111122223333", "eu-west-1", "application", "group-id");
    private static final String REGION = REFERENCE.region();
    private static final String ASG = "workers";
    private static final String INSTANCE = "i-launched";
    private static final String TOKEN = "launch-token";
    private static final Instant START = Instant.parse("2026-11-08T00:00:00Z");

    private final CodeDeployService deployments = mock(CodeDeployService.class);
    private final AutoScalingService scaling = mock(AutoScalingService.class);
    private final CodeDeployLaunchHooks hooks = mock(CodeDeployLaunchHooks.class);
    private final DeploymentGroup group = new DeploymentGroup();
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final Deployment launchDeployment = deployment("InProgress");
    private CodeDeployAutoScaling coordinator;
    private LaunchLifecycleAction action;

    @BeforeEach
    void configureLaunch() {
        group.setDeploymentGroupId(REFERENCE.groupId());
        group.setAutoScalingGroups(List.of(Map.of("name", ASG)));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(ignored -> now.get());
        coordinator = new CodeDeployAutoScaling(deployments, scaling, hooks, clock,
                mock(ScheduledExecutorService.class));
        action = new LaunchLifecycleAction(CodeDeployLaunchHooks.hookName(group), TOKEN,
                START.plusSeconds(600), START.plusSeconds(60000), 600, "ABANDON", null, null);
        when(deployments.autoScalingDeploymentGroups()).thenReturn(List.of(REFERENCE));
        when(deployments.getDeployment(REGION, "d-launch")).thenReturn(launchDeployment);
        when(deployments.startAutomaticDeployment(REFERENCE, ASG, INSTANCE, false))
                .thenReturn(new AutomaticDeployment("d-launch", "d-original"));
        view("d-original", null);
        instance("Pending:Wait", null);
    }

    @Test
    void shutdownPreventsQueuedLaunchWorkFromStarting() {
        coordinator.onStop(new ShutdownEvent());
        coordinator.reconcile();

        verifyNoInteractions(deployments, scaling, hooks);
    }

    @Test
    void launchIsStartedOnceAndHeartbeatsDoNotAdmitAnUnfinishedDeployment() {
        coordinator.reconcile();
        coordinator.reconcile();
        now.set(START.plusSeconds(299));
        coordinator.reconcile();
        verify(hooks, never()).heartbeat(anyString(), any(), anyString(), anyString(), anyString());
        now.set(START.plus(Duration.ofMinutes(5)));
        coordinator.reconcile();
        verify(deployments).startAutomaticDeployment(REFERENCE, ASG, INSTANCE, false);
        verify(hooks).heartbeat(REGION, group, ASG, INSTANCE, TOKEN);
        verify(hooks, never()).complete(anyString(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void onlySucceededAdmitsTheInstanceAndDoesNotRedeployTheSameRevision() {
        coordinator.reconcile();
        launchDeployment.setStatus("Succeeded");
        coordinator.reconcile();
        verify(hooks).complete(REGION, group, ASG, INSTANCE, TOKEN, "CONTINUE");
        instance("InService", "CONTINUE");
        coordinator.reconcile();
        coordinator.reconcile();
        verify(deployments, never()).startAutomaticDeployment(REFERENCE, ASG, INSTANCE, true);
        verify(deployments).startAutomaticDeployment(REFERENCE, ASG, INSTANCE, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Failed", "Stopped"})
    void unsuccessfulLaunchIsAbandoned(String status) {
        coordinator.reconcile();
        launchDeployment.setStatus(status);
        coordinator.reconcile();
        verify(hooks).complete(REGION, group, ASG, INSTANCE, TOKEN, "ABANDON");
        verify(hooks, never()).complete(REGION, group, ASG, INSTANCE, TOKEN, "CONTINUE");
    }

    @Test
    void newGroupWithoutARevisionContinuesWithoutInventingADeployment() {
        view(null, null);
        coordinator.reconcile();
        verify(hooks).complete(REGION, group, ASG, INSTANCE, TOKEN, "CONTINUE");
        verify(deployments, never()).startAutomaticDeployment(any(), anyString(), anyString(), eq(false));
    }

    @Test
    void waitsForTheForegroundDeploymentBeforeUpdatingAnOutdatedLaunch() {
        view("d-original", "d-user");
        coordinator.reconcile();
        launchDeployment.setStatus("Succeeded");
        coordinator.reconcile();
        instance("InService", "CONTINUE");
        coordinator.reconcile();
        verify(deployments, never()).startAutomaticDeployment(REFERENCE, ASG, INSTANCE, true);

        view("d-new", null);
        when(deployments.startAutomaticDeployment(REFERENCE, ASG, INSTANCE, true))
                .thenReturn(new AutomaticDeployment("d-followup", "d-new"));
        Deployment followup = deployment("InProgress");
        when(deployments.getDeployment(REGION, "d-followup")).thenReturn(followup);
        coordinator.reconcile();
        coordinator.reconcile();
        verify(deployments).startAutomaticDeployment(REFERENCE, ASG, INSTANCE, true);
        followup.setStatus("Succeeded");
        coordinator.reconcile();
        coordinator.reconcile();
        verify(deployments).startAutomaticDeployment(REFERENCE, ASG, INSTANCE, true);
        verify(hooks).complete(REGION, group, ASG, INSTANCE, TOKEN, "CONTINUE");
    }

    @Test
    void tracksTheRevisionActuallySelectedUnderTheDeploymentGroupLock() {
        when(deployments.startAutomaticDeployment(REFERENCE, ASG, INSTANCE, false))
                .thenReturn(new AutomaticDeployment("d-launch", "d-new"));
        coordinator.reconcile();
        view("d-new", null);
        launchDeployment.setStatus("Succeeded");
        coordinator.reconcile();
        instance("InService", "CONTINUE");
        coordinator.reconcile();
        verify(deployments, never()).startAutomaticDeployment(REFERENCE, ASG, INSTANCE, true);
    }

    @Test
    void ignoreStrategyLeavesTheLaunchedInstanceAtItsOriginalRevision() {
        group.setOutdatedInstancesStrategy("IGNORE");
        coordinator.reconcile();
        view("d-new", null);
        launchDeployment.setStatus("Succeeded");
        coordinator.reconcile();
        instance("InService", "CONTINUE");
        coordinator.reconcile();
        verify(deployments, never()).startAutomaticDeployment(REFERENCE, ASG, INSTANCE, true);
    }

    @Test
    void deletionKeepsTheOwnedDeploymentUntilItsStopIsTerminal() {
        coordinator.reconcile();
        when(deployments.autoScalingDeploymentGroups()).thenReturn(List.of());
        when(deployments.autoScalingLaunchView(REFERENCE)).thenReturn(null);
        coordinator.reconcile();
        coordinator.reconcile();
        verify(deployments, times(2)).stopDeployment(REGION, "d-launch", false);
        launchDeployment.setStatus("Stopped");
        coordinator.reconcile();
        coordinator.reconcile();
        verify(deployments, times(2)).stopDeployment(REGION, "d-launch", false);
        verify(hooks, never()).complete(anyString(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void expirationCancelsWorkWithoutExtendingTheExpiredAction() {
        coordinator.reconcile();
        now.set(START.plusSeconds(600));
        coordinator.reconcile();
        verify(deployments).stopDeployment(REGION, "d-launch", false);
        verify(hooks, never()).heartbeat(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void aRoleThatCannotCompleteAnActionCannotAdmitTheInstance() {
        coordinator.reconcile();
        launchDeployment.setStatus("Succeeded");
        doThrow(new AwsException("InvalidRoleException", "CompleteLifecycleAction denied", 400))
                .when(hooks).complete(REGION, group, ASG, INSTANCE, TOKEN, "CONTINUE");
        coordinator.reconcile();
        now.set(START.plusSeconds(300));
        coordinator.reconcile();
        verify(hooks, times(2)).complete(REGION, group, ASG, INSTANCE, TOKEN, "CONTINUE");
        verify(hooks, never()).heartbeat(anyString(), any(), anyString(), anyString(), anyString());
        verify(deployments, never()).startAutomaticDeployment(REFERENCE, ASG, INSTANCE, true);
    }

    @Test
    void anOverlappingHostDeploymentIsRetriedButAnInvalidRevisionAbandonsTheLaunch() {
        when(deployments.startAutomaticDeployment(REFERENCE, ASG, INSTANCE, false))
                .thenThrow(new AwsException("DeploymentLimitExceededException", "host occupied", 400))
                .thenThrow(new AwsException("InvalidRevisionException", "revision unavailable", 400));
        coordinator.reconcile();
        verify(hooks, never()).complete(anyString(), any(), anyString(), anyString(), anyString(), anyString());
        coordinator.reconcile();
        verify(hooks).complete(REGION, group, ASG, INSTANCE, TOKEN, "ABANDON");
    }

    private void view(String revision, String foreground) {
        when(deployments.autoScalingLaunchView(REFERENCE)).thenReturn(new AutoScalingLaunchView(group,
                revision == null ? null : new SuccessfulAgentRevision(
                        revision, Map.of("revisionType", "S3"), "OVERWRITE", "CodeDeployDefault.OneAtATime"), foreground));
    }

    private void instance(String state, String result) {
        when(scaling.launchInstances(REGION, ASG, CodeDeployLaunchHooks.hookName(group)))
                .thenReturn(List.of(new LaunchInstance(INSTANCE, state,
                        result == null ? action : action.complete(result, "completed"))));
    }

    private static Deployment deployment(String status) {
        Deployment deployment = new Deployment();
        deployment.setStatus(status);
        return deployment;
    }
}
