package io.github.hectorvent.floci.services.autoscaling.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.UUID;

/** An instance's launch action keeps the hook settings that applied when it started. */
@RegisterForReflection
public record LaunchLifecycleAction(String hookName, String token, Instant deadline,
                                     Instant globalDeadline, int heartbeatTimeout, String defaultResult,
                                     String result, String reason) {

    public static LaunchLifecycleAction start(LifecycleHook hook, Instant now) {
        return new LaunchLifecycleAction(hook.getLifecycleHookName(), UUID.randomUUID().toString(),
                now.plusSeconds(hook.getHeartbeatTimeout()), now.plusSeconds(hook.getGlobalTimeout()),
                hook.getHeartbeatTimeout(), hook.getDefaultResult(), null, null);
    }

    public boolean expired(Instant now) {
        return !now.isBefore(deadline) || !now.isBefore(globalDeadline);
    }

    public LaunchLifecycleAction heartbeat(Instant now) {
        Instant extended = now.plusSeconds(heartbeatTimeout);
        return new LaunchLifecycleAction(hookName, token,
                extended.isBefore(globalDeadline) ? extended : globalDeadline,
                globalDeadline, heartbeatTimeout, defaultResult, result, reason);
    }

    public LaunchLifecycleAction complete(String actionResult, String completionReason) {
        return new LaunchLifecycleAction(hookName, token, deadline, globalDeadline,
                heartbeatTimeout, defaultResult, actionResult, completionReason);
    }
}
