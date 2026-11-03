package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.elb.model.ClassicHealthCheck;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancer;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Health checking for Classic (2012-06-01) load balancers.
 *
 * <p>Classic health is per instance on one load balancer, not per target on a target group, and
 * the check itself is described by a single {@code Target} string such as {@code HTTP:8080/} or
 * {@code TCP:80} rather than by separate protocol, port and path members — so this cannot reuse
 * {@code ElbV2HealthChecker}. The probing shape is deliberately the same: a Vert.x periodic timer
 * per load balancer, consecutive success/failure counters, and the configured thresholds.
 *
 * <p>With {@code floci.services.elb.mock=true} no probe is ever made and a registered instance is
 * reported {@code InService} straight away.
 */
@ApplicationScoped
public class ElbClassicHealthChecker implements Resettable {

    private static final Logger LOG = Logger.getLogger(ElbClassicHealthChecker.class);

    /** The health of one registered instance, in the {@code InstanceState} shape of the v1 model. */
    public record InstanceHealth(String state, String reasonCode, String description) {

        static InstanceHealth inService() {
            return new InstanceHealth("InService", "N/A", "N/A");
        }

        static InstanceHealth registering() {
            return new InstanceHealth("OutOfService", "ELB",
                    "Instance registration is still in progress.");
        }

        static InstanceHealth failedChecks() {
            return new InstanceHealth("OutOfService", "Instance",
                    "Instance has failed at least the UnhealthyThreshold number of health checks "
                            + "consecutively.");
        }
    }

    private final Vertx vertx;
    private final EmulatorConfig config;
    private final Ec2Service ec2Service;
    private final SSLSocketFactory backendTls = backendTls();

    // "account|region|lbName" → instanceId → state
    private final Map<String, Map<String, InstanceState>> states = new ConcurrentHashMap<>();
    // "account|region|lbName" → the periodic check armed for it
    private final Map<String, ArmedCheck> timers = new ConcurrentHashMap<>();

    /** A periodic check: its Vert.x timer and the tick that timer runs. */
    private record ArmedCheck(long id, Runnable tick) {}

    @Inject
    public ElbClassicHealthChecker(Vertx vertx, EmulatorConfig config, Ec2Service ec2Service) {
        this.vertx = vertx;
        this.config = config;
        this.ec2Service = ec2Service;
    }

    /**
     * A Classic name is unique only within one account and region, so two accounts can each have
     * a load balancer called {@code web}; without the account they would share one state and timer.
     */
    private static String key(ClassicLoadBalancer lb) {
        return lb.getAccountId() + "|" + lb.getRegion() + "|" + lb.getLoadBalancerName();
    }

    public void startMonitoring(ClassicLoadBalancer lb) {
        String k = key(lb);
        states.computeIfAbsent(k, x -> new ConcurrentHashMap<>());
        if (mock() || vertx == null) {
            return;
        }
        stopTimer(k);
        int interval = lb.getHealthCheck() != null && lb.getHealthCheck().getInterval() != null
                ? lb.getHealthCheck().getInterval() : 30;
        Runnable tick = () -> probeAll(lb);
        timers.put(k, new ArmedCheck(vertx.setPeriodic(interval * 1000L, id -> tick.run()), tick));
    }

    /**
     * Runs one tick of every armed periodic check now, probing what each timer would probe, so
     * tests need not wait out a check interval. The timers themselves keep their schedule.
     */
    void runArmedChecks() {
        List.copyOf(timers.values()).forEach(timer -> timer.tick().run());
    }

    public void stopMonitoring(ClassicLoadBalancer lb) {
        String k = key(lb);
        stopTimer(k);
        states.remove(k);
    }

    /** Re-arms the periodic timer after {@code ConfigureHealthCheck} changed the interval. */
    public void healthCheckChanged(ClassicLoadBalancer lb) {
        if (states.containsKey(key(lb))) {
            startMonitoring(lb);
        }
    }

    public void registerInstances(ClassicLoadBalancer lb, Collection<String> instanceIds) {
        Map<String, InstanceState> lbStates = states.computeIfAbsent(key(lb), k -> new ConcurrentHashMap<>());
        for (String id : instanceIds) {
            lbStates.putIfAbsent(id, new InstanceState());
        }
        if (!mock() && vertx != null) {
            vertx.setTimer(1, ignored -> probeAll(lb));
        }
    }

    public void deregisterInstances(ClassicLoadBalancer lb, Collection<String> instanceIds) {
        Map<String, InstanceState> lbStates = states.get(key(lb));
        if (lbStates != null) {
            instanceIds.forEach(lbStates::remove);
        }
    }

    /**
     * The health of one instance registered with a load balancer.
     *
     * <p>An instance with no probe result yet is {@code OutOfService}/{@code ELB}, which is what a
     * live Classic load balancer reports between {@code RegisterInstancesWithLoadBalancer} and the
     * first successful check.
     */
    public InstanceHealth getHealth(ClassicLoadBalancer lb, String instanceId) {
        if (mock()) {
            return InstanceHealth.inService();
        }
        Map<String, InstanceState> lbStates = states.get(key(lb));
        InstanceState s = lbStates != null ? lbStates.get(instanceId) : null;
        if (s == null || s.health == null) {
            return InstanceHealth.registering();
        }
        return s.health;
    }

    @Override
    public void clear() {
        if (vertx != null) {
            timers.values().forEach(timer -> vertx.cancelTimer(timer.id()));
        }
        timers.clear();
        states.clear();
    }

    private boolean mock() {
        return config != null && config.services().elb().mock();
    }

    private void stopTimer(String k) {
        ArmedCheck timer = timers.remove(k);
        if (timer != null && vertx != null) {
            vertx.cancelTimer(timer.id());
        }
    }

    private void probeAll(ClassicLoadBalancer lb) {
        Map<String, InstanceState> lbStates = states.get(key(lb));
        if (lbStates == null || lbStates.isEmpty()) {
            return;
        }
        ClassicHealthCheck hc = lb.getHealthCheck() != null ? lb.getHealthCheck() : ClassicHealthCheck.defaults();
        Target target = Target.parse(hc.getTarget());
        if (target == null) {
            return;
        }
        int timeout = hc.getTimeout() != null ? hc.getTimeout() : 5;
        int healthyThreshold = hc.getHealthyThreshold() != null ? hc.getHealthyThreshold() : 10;
        int unhealthyThreshold = hc.getUnhealthyThreshold() != null ? hc.getUnhealthyThreshold() : 2;

        for (Map.Entry<String, InstanceState> entry : lbStates.entrySet()) {
            String instanceId = entry.getKey();
            InstanceState state = entry.getValue();
            String host = resolveHost(lb.getAccountId(), instanceId);
            vertx.executeBlocking(() -> probe(host, target, timeout))
                    .onSuccess(ok -> {
                        if (Boolean.TRUE.equals(ok)) {
                            state.consecutiveFailures = 0;
                            state.consecutiveSuccesses++;
                            if (state.consecutiveSuccesses >= healthyThreshold) {
                                state.health = InstanceHealth.inService();
                            }
                        } else {
                            recordFailure(state, unhealthyThreshold);
                        }
                    })
                    .onFailure(err -> {
                        recordFailure(state, unhealthyThreshold);
                        LOG.debugv("Classic ELB health check failed for {0} ({1}): {2}",
                                instanceId, host, err.getMessage());
                    });
        }
    }

    private static void recordFailure(InstanceState state, int unhealthyThreshold) {
        state.consecutiveSuccesses = 0;
        state.consecutiveFailures++;
        if (state.consecutiveFailures >= unhealthyThreshold) {
            state.health = InstanceHealth.failedChecks();
        }
    }

    private String resolveHost(String instanceId) {
        return resolveHost(null, instanceId);
    }

    private String resolveHost(String accountId, String instanceId) {
        if (ec2Service == null) {
            return instanceId;
        }
        Instance instance = accountId != null
                ? ec2Service.findInstanceById(accountId, instanceId)
                : ec2Service.findInstanceById(instanceId);
        if (instance == null) {
            return instanceId;
        }
        String bridgeIp = instance.getContainerBridgeIp();
        return bridgeIp != null && !bridgeIp.isBlank() ? bridgeIp : instanceId;
    }

    private boolean probe(String host, Target target, int timeoutSeconds) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        FutureTask<Boolean> attempt = new FutureTask<>(() -> probeTransport(host, target, timeoutSeconds));
        Thread.ofVirtual().name("classic-elb-health-probe").start(attempt);
        try {
            return attempt.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException cause) {
                throw cause;
            }
            throw new IllegalStateException("Classic ELB health probe failed", e.getCause());
        } finally {
            // Interrupting the virtual thread closes its blocking JDK socket, including TLS reads.
            attempt.cancel(true);
        }
    }

    private boolean probeTransport(String host, Target target, int timeoutSeconds) throws IOException {
        int timeoutMs = timeoutSeconds * 1000;
        if (target.path() == null) {
            try (Socket socket = "ssl".equals(target.scheme()) ? backendTls.createSocket() : new Socket()) {
                socket.connect(new InetSocketAddress(host, target.port()), timeoutMs);
                socket.setSoTimeout(timeoutMs);
                if (socket instanceof SSLSocket tls) {
                    tls.startHandshake();
                }
                return true;
            }
        }
        URL url = new URL(target.scheme(), host, target.port(), target.path());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (conn instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(backendTls);
            https.setHostnameVerifier((hostname, session) -> true);
        }
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(false);
        try {
            return conn.getResponseCode() == HttpURLConnection.HTTP_OK;
        } finally {
            conn.disconnect();
        }
    }

    private static SSLSocketFactory backendTls() {
        try {
            // Classic backend certificate authentication is opt-in; its policy APIs remain unsupported.
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot initialize Classic ELB backend TLS", e);
        }
    }

    /**
     * A parsed {@code HealthCheck.Target}, such as {@code HTTP:8080/health} or {@code TCP:80}.
     * {@code path} is null for TCP and SSL; SSL also requires the TLS handshake to succeed.
     */
    record Target(String scheme, int port, String path) {

        static Target parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            int colon = raw.indexOf(':');
            if (colon < 0) {
                return null;
            }
            String protocol = raw.substring(0, colon).toUpperCase();
            String rest = raw.substring(colon + 1);
            int slash = rest.indexOf('/');
            String portPart = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? null : rest.substring(slash);
            int port;
            try {
                port = Integer.parseInt(portPart.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            return switch (protocol) {
                case "HTTP" -> new Target("http", port, path != null ? path : "/");
                case "HTTPS" -> new Target("https", port, path != null ? path : "/");
                case "TCP" -> new Target(null, port, null);
                case "SSL" -> new Target("ssl", port, null);
                default -> null;
            };
        }
    }

    private static final class InstanceState {
        volatile InstanceHealth health;
        volatile int consecutiveSuccesses;
        volatile int consecutiveFailures;
    }
}
