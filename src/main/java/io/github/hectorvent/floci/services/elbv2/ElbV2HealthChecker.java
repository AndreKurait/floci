package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.concurrent.KeyedWorkQueue;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ElbV2HealthChecker implements Resettable {

    private static final Logger LOG = Logger.getLogger(ElbV2HealthChecker.class);

    private final Vertx vertx;
    private final EmulatorConfig config;
    private final Ec2Service ec2Service;
    private final long maxHealthCheckIntervalMillis;

    // tgArn → (targetKey → TargetState)
    private final Map<String, Map<String, TargetState>> states = new ConcurrentHashMap<>();
    private final Map<String, Monitoring> timers = new ConcurrentHashMap<>();
    private final KeyedWorkQueue<ProbeWork> probes = new KeyedWorkQueue<>(
            "elb-health-probe", 32, 4096, this::runProbe);

    @Inject
    public ElbV2HealthChecker(Vertx vertx, EmulatorConfig config, Ec2Service ec2Service) {
        this.vertx = vertx;
        this.config = config;
        this.ec2Service = ec2Service;
        maxHealthCheckIntervalMillis = config.services().elbv2().maxHealthCheckIntervalMillis();
        if (maxHealthCheckIntervalMillis < 0) {
            throw new IllegalArgumentException(
                    "floci.services.elbv2.max-health-check-interval-millis must not be negative");
        }
    }

    public static int effectivePort(TargetDescription target, TargetGroup tg) {
        if (target.getPort() != null) {
            return target.getPort();
        }
        if (tg.getPort() != null) {
            return tg.getPort();
        }
        return 80;
    }

    public synchronized void startMonitoring(TargetGroup tg) {
        if (config.services().elbv2().mock() || "lambda".equals(tg.getTargetType())) {
            return;
        }
        String tgArn = tg.getTargetGroupArn();
        states.computeIfAbsent(tgArn, k -> new ConcurrentHashMap<>());
        long intervalMs = (tg.getHealthCheckIntervalSeconds() != null
                ? tg.getHealthCheckIntervalSeconds() : 30) * 1000L;
        if (maxHealthCheckIntervalMillis > 0) {
            intervalMs = Math.min(intervalMs, maxHealthCheckIntervalMillis);
        }
        ProbeSettings settings = ProbeSettings.of(tg);
        Monitoring previous = timers.get(tgArn);
        if (previous != null && previous.intervalMillis() == intervalMs
                && previous.settings().equals(settings) && previous.group() == tg) {
            return;
        }
        long timerId;
        if (previous != null && previous.intervalMillis() == intervalMs) {
            timerId = previous.timerId();
        } else {
            if (previous != null) {
                vertx.cancelTimer(previous.timerId());
            }
            timerId = vertx.setPeriodic(intervalMs, id -> probeAll(tgArn, id));
        }
        timers.put(tgArn, new Monitoring(timerId, intervalMs, tg, settings));
    }

    public synchronized void stopMonitoring(String tgArn) {
        Monitoring monitoring = timers.remove(tgArn);
        if (monitoring != null) {
            vertx.cancelTimer(monitoring.timerId());
        }
        states.remove(tgArn);
    }

    public synchronized void clear() {
        timers.values().forEach(monitoring -> vertx.cancelTimer(monitoring.timerId()));
        timers.clear();
        states.clear();
    }

    @PreDestroy
    void shutdown() {
        clear();
        probes.close();
    }

    public synchronized void addTargets(String tgArn, List<TargetDescription> targets, TargetGroup tg) {
        if (config.services().elbv2().mock()) {
            return;
        }
        Map<String, TargetState> tgStates = states.computeIfAbsent(tgArn, k -> new ConcurrentHashMap<>());
        Map<String, TargetState> registered = new HashMap<>();
        for (TargetDescription target : targets) {
            String key = stateKey(target.getId(), effectivePort(target, tg));
            TargetState state = new TargetState();
            if (tgStates.putIfAbsent(key, state) == null) {
                registered.put(key, state);
            }
        }
        if (!registered.isEmpty() && Boolean.TRUE.equals(tg.getHealthCheckEnabled())) {
            vertx.setTimer(1, ignored -> probeTargets(tgArn, tg, registered));
        }
    }

    public synchronized void removeTargets(String tgArn, List<TargetDescription> targets, TargetGroup tg) {
        Map<String, TargetState> tgStates = states.get(tgArn);
        if (tgStates == null) {
            return;
        }
        for (TargetDescription t : targets) {
            int port = effectivePort(t, tg);
            tgStates.remove(stateKey(t.getId(), port));
        }
    }

    public String getState(String tgArn, String targetId, int port) {
        return getHealth(tgArn, targetId, port).state();
    }

    public TargetHealthStatus getHealth(String tgArn, String targetId, int port) {
        Map<String, TargetState> tgStates = states.get(tgArn);
        if (tgStates == null) {
            return TargetHealthStatus.registrationInProgress();
        }
        TargetState s = tgStates.get(stateKey(targetId, port));
        if (s == null) {
            return TargetHealthStatus.registrationInProgress();
        }
        return s.health;
    }

    public boolean isHealthy(String tgArn, TargetDescription target, int port) {
        String state = getState(tgArn, target.getId(), port);
        return "healthy".equals(state) || "initial".equals(state);
    }

    private synchronized void probeAll(String tgArn, long timerId) {
        Monitoring monitoring = timers.get(tgArn);
        Map<String, TargetState> targets = states.get(tgArn);
        if (monitoring != null && monitoring.timerId() == timerId && targets != null) {
            probeTargets(tgArn, monitoring.group(), targets);
        }
    }

    private synchronized void probeTargets(String tgArn, TargetGroup tg, Map<String, TargetState> selected) {
        Map<String, TargetState> targets = states.get(tgArn);
        if (targets == null || !Boolean.TRUE.equals(tg.getHealthCheckEnabled())) {
            return;
        }
        for (Map.Entry<String, TargetState> entry : selected.entrySet()) {
            String key = entry.getKey();
            TargetState state = entry.getValue();
            if (targets.get(key) != state || state.inFlight) {
                continue;
            }
            // Split at the last colon so IPv6 literals remain intact.
            int separator = key.lastIndexOf(':');
            if (separator < 0) {
                continue;
            }
            String targetId = key.substring(0, separator);
            int port = Integer.parseInt(key.substring(separator + 1));
            state.inFlight = true;
            ProbeWork work = new ProbeWork(tgArn, key, targetId, port, tg, state,
                    timers.get(tgArn), ProbeSettings.of(tg));
            if (!probes.submit(work)) {
                state.inFlight = false;
            }
        }
    }

    private boolean current(ProbeWork work) {
        Map<String, TargetState> targets = states.get(work.groupArn());
        return targets != null && targets.get(work.key()) == work.state()
                && timers.get(work.groupArn()) == work.monitoring()
                && work.settings().equals(ProbeSettings.of(work.group()));
    }

    private void runProbe(ProbeWork work) {
        TargetState state = work.state();
        try {
            synchronized (this) {
                if (!current(work)) {
                    return;
                }
            }
            int statusCode = 0;
            Exception failure = null;
            try {
                String host = ElbV2TargetResolver.resolveHost(ec2Service, work.group(), work.targetId());
                statusCode = probe(host, healthCheckPort(work.group(), work.port()),
                        work.settings().path(), work.settings().timeout());
            } catch (IOException | RuntimeException error) {
                failure = error;
            }
            synchronized (this) {
                if (!current(work)) {
                    return;
                }
                applyResult(state, work.settings(), statusCode, failure);
            }
        } finally {
            synchronized (this) {
                state.inFlight = false;
            }
        }
    }

    private void applyResult(TargetState state, ProbeSettings settings, int statusCode, Exception failure) {
        if (failure == null && matchesStatusCode(statusCode, settings.matcher())) {
            state.consecutiveFailures = 0;
            state.consecutiveSuccesses++;
            boolean initialApplicationTarget = "initial".equals(state.health.state())
                    && ("HTTP".equals(settings.targetProtocol()) || "HTTPS".equals(settings.targetProtocol())
                    || "lambda".equals(settings.targetType()));
            if (initialApplicationTarget || state.consecutiveSuccesses >= settings.healthyThreshold()) {
                state.health = new TargetHealthStatus("healthy", null, null);
            } else if ("initial".equals(state.health.state())) {
                state.health = new TargetHealthStatus("initial", "Elb.InitialHealthChecking",
                        "Initial health checks in progress");
            }
        } else {
            state.consecutiveSuccesses = 0;
            state.consecutiveFailures++;
            if (state.consecutiveFailures >= settings.unhealthyThreshold()) {
                if (failure == null) {
                    state.health = new TargetHealthStatus("unhealthy", "Target.ResponseCodeMismatch",
                            "Health checks failed with these codes: [" + statusCode + "]");
                } else if (isTimeout(failure)) {
                    state.health = new TargetHealthStatus("unhealthy", "Target.Timeout", "Request timed out");
                } else {
                    state.health = new TargetHealthStatus("unhealthy", "Target.FailedHealthChecks", "Health checks failed");
                }
            }
        }
    }

    private record Monitoring(long timerId, long intervalMillis, TargetGroup group, ProbeSettings settings) {}

    private record ProbeWork(String groupArn, String key, String targetId, int port, TargetGroup group,
                             TargetState state, Monitoring monitoring, ProbeSettings settings) {}

    private record ProbeSettings(String path, String matcher, String port, String protocol, boolean enabled,
                                 int timeout, int healthyThreshold, int unhealthyThreshold,
                                 String targetProtocol, String targetType) {
        static ProbeSettings of(TargetGroup group) {
            return new ProbeSettings(group.getHealthCheckPath() != null ? group.getHealthCheckPath() : "/",
                    group.getMatcher() != null ? group.getMatcher() : "200", group.getHealthCheckPort(),
                    group.getHealthCheckProtocol(), Boolean.TRUE.equals(group.getHealthCheckEnabled()),
                    group.getHealthCheckTimeoutSeconds() != null ? group.getHealthCheckTimeoutSeconds() : 5,
                    group.getHealthyThresholdCount() != null ? group.getHealthyThresholdCount() : 5,
                    group.getUnhealthyThresholdCount() != null ? group.getUnhealthyThresholdCount() : 2,
                    group.getProtocol(), group.getTargetType());
        }
    }

    private int probe(String host, int port, String path, int timeoutSeconds) throws IOException {
        URL url = new URL("http", ElbV2TargetResolver.resolveCheckedAddress(host), port, path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(timeoutSeconds * 1000);
        conn.setReadTimeout(timeoutSeconds * 1000);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        try {
            conn.connect();
            return conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * AWS probes {@code HealthCheckPort} when it is a number and the target's own port when it is
     * {@code traffic-port} or unset. Health state stays keyed by the traffic port either way.
     * A stored value that is not a valid port, which the API accepted before it validated the
     * field, keeps probing the traffic port as before.
     */
    static int healthCheckPort(TargetGroup tg, int trafficPort) {
        String configured = tg.getHealthCheckPort();
        if (configured == null || configured.isBlank() || "traffic-port".equals(configured)) {
            return trafficPort;
        }
        Integer port = parsePort(configured);
        if (port == null) {
            LOG.debugv("Target group {0} has invalid HealthCheckPort {1}; probing traffic port {2}",
                    tg.getTargetGroupArn(), configured, trafficPort);
            return trafficPort;
        }
        return port;
    }

    /** Returns the port number {@code value} names, or null when it is not a port from 1 to 65535. */
    static Integer parsePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535 ? port : null;
        } catch (NumberFormatException ignored) {
            // Not a number: the caller treats it the same as an out-of-range port.
            return null;
        }
    }

    static boolean matchesStatusCode(int code, String matcher) {
        String codeStr = String.valueOf(code);
        if (matcher.contains("-")) {
            String[] range = matcher.split("-", 2);
            try {
                int low = Integer.parseInt(range[0].trim());
                int high = Integer.parseInt(range[1].trim());
                return code >= low && code <= high;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return Arrays.stream(matcher.split(","))
                .map(String::trim)
                .anyMatch(codeStr::equals);
    }

    private static boolean isTimeout(Throwable err) {
        Throwable current = err;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String stateKey(String targetId, int port) {
        return targetId + ":" + port;
    }

    public record TargetHealthStatus(String state, String reason, String description) {
        private static TargetHealthStatus registrationInProgress() {
            return new TargetHealthStatus(
                    "initial",
                    "Elb.RegistrationInProgress",
                    "Target registration is in progress");
        }
    }

    private static class TargetState {
        boolean inFlight;
        volatile TargetHealthStatus health = TargetHealthStatus.registrationInProgress();
        int consecutiveSuccesses;
        int consecutiveFailures;
    }
}
