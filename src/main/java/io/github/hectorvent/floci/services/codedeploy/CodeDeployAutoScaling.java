package io.github.hectorvent.floci.services.codedeploy;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService.LaunchInstance;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService.AutoScalingGroupReference;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService.AutoScalingLaunchView;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService.AutomaticDeployment;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Drives CodeDeploy's managed launch actions through the ordinary agent command mailbox. */
@ApplicationScoped
public class CodeDeployAutoScaling {
    private static final Logger LOG = Logger.getLogger(CodeDeployAutoScaling.class);
    private static final Duration HEARTBEAT = Duration.ofMinutes(5);
    private static final Set<String> TERMINAL = Set.of("Succeeded", "Failed", "Stopped");
    private static final Set<String> LAUNCH_STATES = Set.of("Pending:Wait", "Pending:Proceed", "InService");

    private final CodeDeployService deployments;
    private final AutoScalingService scaling;
    private final CodeDeployLaunchHooks hooks;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final Map<LaunchKey, LaunchWork> work = new LinkedHashMap<>();
    private boolean stopped;

    private record LaunchKey(AutoScalingGroupReference group, String scalingGroup, String instanceId, String token) {}

    private static final class LaunchWork {
        private String deploymentId;
        private String sourceDeploymentId;
        private boolean continued;
        private Instant lastHeartbeat;
        private String lastFailure;

        private LaunchWork(Instant now) {
            lastHeartbeat = now;
        }
    }

    @Inject
    CodeDeployAutoScaling(CodeDeployService deployments, AutoScalingService scaling, CodeDeployLaunchHooks hooks) {
        this(deployments, scaling, hooks, Clock.systemUTC(),
                Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "codedeploy-autoscaling")));
    }

    CodeDeployAutoScaling(CodeDeployService deployments, AutoScalingService scaling, CodeDeployLaunchHooks hooks,
                         Clock clock, ScheduledExecutorService scheduler) {
        this.deployments = deployments;
        this.scaling = scaling;
        this.hooks = hooks;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void start() {
        scheduler.scheduleWithFixedDelay(this::reconcile, 1, 1, TimeUnit.SECONDS);
    }

    void onStart(@Observes StartupEvent ignored) {
        LOG.debug("CodeDeploy Auto Scaling coordinator initialized");
    }

    @PreDestroy
    synchronized void stop() {
        stopped = true;
        scheduler.shutdownNow();
    }

    void onStop(@Observes @Priority(1000) ShutdownEvent ignored) {
        stop();
    }

    synchronized void reconcile() {
        if (stopped) {
            return;
        }
        try {
            Set<AutoScalingGroupReference> groups = new LinkedHashSet<>(deployments.autoScalingDeploymentGroups());
            work.keySet().forEach(key -> groups.add(key.group()));
            for (AutoScalingGroupReference group : groups) {
                try {
                    RequestScopes.runAs(group.account(), group.region(), () -> reconcile(group));
                } catch (RuntimeException failure) {
                    LOG.warnv(failure, "Could not reconcile CodeDeploy launch actions for {0}", group);
                }
            }
        } catch (RuntimeException failure) {
            LOG.warnv(failure, "Could not enumerate CodeDeploy launch actions");
        }
    }

    private void reconcile(AutoScalingGroupReference group) {
        AutoScalingLaunchView view = deployments.autoScalingLaunchView(group);
        Set<String> attached = view == null ? Set.of() : CodeDeployLaunchHooks.groupNames(view.group());
        Map<LaunchKey, LaunchInstance> instances = new HashMap<>();
        for (String name : attached) {
            for (LaunchInstance instance : scaling.launchInstances(group.region(), name,
                    CodeDeployLaunchHooks.hookName(view.group()))) {
                LaunchKey key = new LaunchKey(group, name, instance.instanceId(), instance.action().token());
                instances.put(key, instance);
                if (pending(instance)) {
                    work.computeIfAbsent(key, ignored -> new LaunchWork(clock.instant()));
                }
            }
        }
        for (Map.Entry<LaunchKey, LaunchWork> entry : new ArrayList<>(work.entrySet())) {
            LaunchKey key = entry.getKey();
            if (!key.group().equals(group)) {
                continue;
            }
            LaunchWork launch = entry.getValue();
            try {
                LaunchInstance instance = instances.get(key);
                if (instance == null || !LAUNCH_STATES.contains(instance.lifecycleState())
                        || "ABANDON".equals(instance.action().result())
                        || (instance.action().result() == null && instance.action().expired(clock.instant()))) {
                    cancel(key, launch);
                } else {
                    advance(key, launch, instance, view);
                }
                launch.lastFailure = null;
            } catch (RuntimeException failure) {
                String message = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                if (!message.equals(launch.lastFailure)) {
                    LOG.warnv(failure, "CodeDeploy launch action {0} could not advance", key);
                    launch.lastFailure = message;
                }
            }
        }
    }

    private void advance(LaunchKey key, LaunchWork launch, LaunchInstance instance, AutoScalingLaunchView view) {
        if (launch.continued) {
            updateOutdated(key, launch, instance, view);
            return;
        }
        if (launch.deploymentId == null) {
            if (view.revision() == null) {
                complete(key, launch, instance, view, "CONTINUE");
                return;
            }
            try {
                remember(launch, deployments.startAutomaticDeployment(
                        key.group(), key.scalingGroup(), key.instanceId(), false));
            } catch (AwsException failure) {
                if ("DeploymentLimitExceededException".equals(failure.getErrorCode())) {
                    return;
                }
                complete(key, launch, instance, view, "ABANDON");
                LOG.warnv(failure, "CodeDeploy could not start the launch deployment for {0}", key);
            }
            return;
        }
        Deployment deployment = deployments.getDeployment(key.group().region(), launch.deploymentId);
        if (TERMINAL.contains(deployment.getStatus())) {
            complete(key, launch, instance, view, "Succeeded".equals(deployment.getStatus()) ? "CONTINUE" : "ABANDON");
        } else if (pending(instance) && !clock.instant().isBefore(launch.lastHeartbeat.plus(HEARTBEAT))) {
            hooks.heartbeat(key.group().region(), view.group(), key.scalingGroup(), key.instanceId(), key.token());
            launch.lastHeartbeat = clock.instant();
        }
    }

    private void complete(LaunchKey key, LaunchWork launch, LaunchInstance instance,
                          AutoScalingLaunchView view, String result) {
        if (instance.action().result() == null) {
            hooks.complete(key.group().region(), view.group(), key.scalingGroup(),
                    key.instanceId(), key.token(), result);
        }
        if ("CONTINUE".equals(result)) {
            launch.continued = true;
            launch.deploymentId = null;
        } else {
            work.remove(key);
        }
    }

    private void updateOutdated(LaunchKey key, LaunchWork launch, LaunchInstance instance, AutoScalingLaunchView view) {
        if (launch.deploymentId != null) {
            Deployment deployment = deployments.getDeployment(key.group().region(), launch.deploymentId);
            if (!TERMINAL.contains(deployment.getStatus())) {
                return;
            }
            if (!"Succeeded".equals(deployment.getStatus())) {
                LOG.warnv("CodeDeploy update {0} for launched instance {1} ended as {2}",
                        launch.deploymentId, key.instanceId(), deployment.getStatus());
                work.remove(key);
                return;
            }
            launch.deploymentId = null;
        }
        if (!"InService".equals(instance.lifecycleState())) {
            return;
        }
        if ("IGNORE".equals(view.group().getOutdatedInstancesStrategy())) {
            work.remove(key);
        } else if (view.foregroundDeployment() == null) {
            if (view.revision() != null && !Objects.equals(launch.sourceDeploymentId, view.revision().deploymentId())) {
                remember(launch, deployments.startAutomaticDeployment(
                        key.group(), key.scalingGroup(), key.instanceId(), true));
            } else {
                work.remove(key);
            }
        }
    }

    private void cancel(LaunchKey key, LaunchWork launch) {
        if (launch.deploymentId != null) {
            Deployment deployment = deployments.getDeployment(key.group().region(), launch.deploymentId);
            if (!TERMINAL.contains(deployment.getStatus())) {
                deployments.stopDeployment(key.group().region(), launch.deploymentId, false);
                return;
            }
        }
        work.remove(key);
    }

    private boolean pending(LaunchInstance instance) {
        return "Pending:Wait".equals(instance.lifecycleState()) && instance.action().result() == null
                && !instance.action().expired(clock.instant());
    }

    private static void remember(LaunchWork launch, AutomaticDeployment deployment) {
        if (deployment != null) {
            launch.deploymentId = deployment.deploymentId();
            launch.sourceDeploymentId = deployment.sourceDeploymentId();
        }
    }
}
