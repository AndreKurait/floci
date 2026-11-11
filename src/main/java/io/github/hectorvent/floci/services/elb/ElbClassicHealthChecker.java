package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.concurrent.DeadlineWaits;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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
    private final ElbClassicFrontendManager frontends;
    private final DeadlineWaits probeDeadline;
    private final SSLSocketFactory backendTls = backendTls();

    // "account|region|lbName" → instanceId → state
    private final Map<String, Map<String, InstanceState>> states = new ConcurrentHashMap<>();
    // "account|region|lbName" → the periodic check armed for it
    private final Map<String, ArmedCheck> timers = new ConcurrentHashMap<>();

    /** A periodic check: its Vert.x timer and the tick that timer runs. */
    private record ArmedCheck(long id, Runnable tick, ClassicLoadBalancer loadBalancer) {}

    public ElbClassicHealthChecker(Vertx vertx, EmulatorConfig config, Ec2Service ec2Service) {
        this(vertx, config, ec2Service, null);
    }

    @Inject
    public ElbClassicHealthChecker(Vertx vertx, EmulatorConfig config, Ec2Service ec2Service,
                                  ElbClassicFrontendManager frontends) {
        this(vertx, config, ec2Service, frontends, DeadlineWaits.SYSTEM);
    }

    /** The timeout of a direct probe is counted on {@code probeDeadline}. */
    ElbClassicHealthChecker(Vertx vertx, EmulatorConfig config, Ec2Service ec2Service,
                            ElbClassicFrontendManager frontends, DeadlineWaits probeDeadline) {
        this.vertx = vertx;
        this.config = config;
        this.ec2Service = ec2Service;
        this.frontends = frontends;
        this.probeDeadline = probeDeadline;
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
        timers.put(k, new ArmedCheck(vertx.setPeriodic(interval * 1000L, id -> tick.run()), tick, lb));
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

    /** Keep healthy existing members admitted while a registration change starts new probes. */
    void reconcileInstances(ClassicLoadBalancer lb) {
        Map<String, InstanceState> lbStates = states.computeIfAbsent(key(lb), k -> new ConcurrentHashMap<>());
        lbStates.keySet().retainAll(lb.getInstanceIds());
        registerInstances(lb, lb.getInstanceIds());
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
        if (s == null) {
            return InstanceHealth.registering();
        }
        if (frontends != null && frontends.enabled()) {
            List<ElbClassicFrontendManager.Probe> probes = frontends.probesFor(lb, instanceId);
            if (probes.isEmpty()) {
                return InstanceHealth.registering();
            }
            boolean allFailed = true;
            for (ElbClassicFrontendManager.Probe probe : probes) {
                ProbeState node = s.nodes.get(probe.frontend().eni());
                InstanceHealth health = node != null && node.probe.frontend() == probe.frontend()
                        && node.probe.generation() == probe.generation()
                        && Objects.equals(node.probe.address(), probe.address())
                        ? node.publishedHealth : null;
                if (health != null && "InService".equals(health.state())) {
                    return health;
                }
                allFailed &= InstanceHealth.failedChecks().equals(health);
            }
            return allFailed ? InstanceHealth.failedChecks() : InstanceHealth.registering();
        }
        InstanceHealth health = s.direct.health;
        return health == null ? InstanceHealth.registering() : health;
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
        boolean protectedFrontend = frontends != null && frontends.enabled();
        ArmedCheck monitoring = timers.get(key(lb));
        if (protectedFrontend && (monitoring == null || monitoring.loadBalancer() != lb)) {
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
            if (protectedFrontend) {
                List<ElbClassicFrontendManager.Probe> probes = frontends.probesFor(lb, instanceId);
                Set<String> currentNodes = probes.stream().map(probe -> probe.frontend().eni()).collect(Collectors.toSet());
                state.nodes.keySet().retainAll(currentNodes);
                for (ElbClassicFrontendManager.Probe probe : probes) {
                    ProbeState node = state.nodes.compute(probe.frontend().eni(), (ignored, previous) ->
                            previous != null && previous.probe.frontend() == probe.frontend()
                                    && previous.probe.generation() == probe.generation()
                                    && Objects.equals(previous.probe.address(), probe.address())
                                    ? previous : new ProbeState(probe));
                    probeOne(lb, instanceId, node, target, timeout, healthyThreshold, unhealthyThreshold);
                }
            } else {
                probeOne(lb, instanceId, state.direct, target, timeout, healthyThreshold, unhealthyThreshold);
            }
        }
    }

    private void probeOne(ClassicLoadBalancer lb, String instanceId, ProbeState state, Target target,
                          int timeout, int healthyThreshold, int unhealthyThreshold) {
        String host = resolveHost(lb.getAccountId(), instanceId);
        ElbClassicFrontendManager.Probe namespace = state.probe;
        vertx.executeBlocking(() -> namespace != null
                ? frontends.probe(namespace, target, timeout) : probe(host, target, timeout), false)
                .onSuccess(ok -> {
                    if (namespace != null && !frontends.current(lb, namespace)) {
                        return;
                    }
                    if (Boolean.TRUE.equals(ok)) {
                        recordSuccess(state, healthyThreshold);
                    } else {
                        recordFailure(state, unhealthyThreshold);
                    }
                    if (namespace != null) {
                        publishProtectedHealth(lb, namespace, state);
                    }
                })
                .onFailure(err -> {
                    if (namespace != null && !frontends.current(lb, namespace)) {
                        return;
                    }
                    recordFailure(state, unhealthyThreshold);
                    if (namespace != null) {
                        publishProtectedHealth(lb, namespace, state);
                    }
                    LOG.debugv("Classic ELB health check failed for {0} ({1}): {2}",
                            instanceId, host, err.getMessage());
                });
    }

    private void publishProtectedHealth(ClassicLoadBalancer loadBalancer,
                                        ElbClassicFrontendManager.Probe probe, ProbeState state) {
        if (!state.publishing.compareAndSet(false, true)) {
            return;
        }
        vertx.executeBlocking(() -> {
            while (frontends.current(loadBalancer, probe)) {
                InstanceHealth desired = state.health == null ? InstanceHealth.registering() : state.health;
                if (Objects.equals(desired, state.publishedHealth)) {
                    break;
                }
                frontends.healthChanged(loadBalancer, probe, desired);
                if (frontends.current(loadBalancer, probe)) {
                    state.publishedHealth = desired;
                }
            }
            return null;
        }, false).onSuccess(ignored -> {
            state.publishing.set(false);
            InstanceHealth desired = state.health == null ? InstanceHealth.registering() : state.health;
            if (frontends.current(loadBalancer, probe) && !Objects.equals(desired, state.publishedHealth)) {
                publishProtectedHealth(loadBalancer, probe, state);
            }
        }).onFailure(failure -> {
            state.publishedHealth = InstanceHealth.registering();
            state.publishing.set(false);
            LOG.warnv(failure, "Could not apply protected Classic health for {0}", probe.instanceId());
        });
    }

    private static void recordSuccess(ProbeState state, int healthyThreshold) {
        synchronized (state) {
            state.consecutiveFailures = 0;
            state.consecutiveSuccesses++;
            if (state.consecutiveSuccesses >= healthyThreshold) {
                state.health = InstanceHealth.inService();
            }
        }
    }

    private static void recordFailure(ProbeState state, int unhealthyThreshold) {
        synchronized (state) {
            state.consecutiveSuccesses = 0;
            state.consecutiveFailures++;
            if (state.consecutiveFailures >= unhealthyThreshold) {
                state.health = InstanceHealth.failedChecks();
            }
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
        return instanceHost(instance, instanceId);
    }

    static String instanceHost(Instance instance, String instanceId) {
        String bridgeIp = instance.getContainerBridgeIp();
        return bridgeIp != null && !bridgeIp.isBlank() ? bridgeIp : instanceId;
    }

    private boolean probe(String host, Target target, int timeoutSeconds) throws IOException {
        FutureTask<Boolean> attempt = new FutureTask<>(() -> probeTransport(host, target, timeoutSeconds));
        Thread.ofVirtual().name("classic-elb-health-probe").start(attempt);
        try {
            return probeDeadline.get(attempt, TimeUnit.SECONDS.toNanos(timeoutSeconds));
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
        final ProbeState direct = new ProbeState(null);
        final Map<String, ProbeState> nodes = new ConcurrentHashMap<>();
    }

    private static final class ProbeState {
        final ElbClassicFrontendManager.Probe probe;
        final AtomicBoolean publishing = new AtomicBoolean();
        volatile InstanceHealth health;
        volatile InstanceHealth publishedHealth;
        volatile int consecutiveSuccesses;
        volatile int consecutiveFailures;

        private ProbeState(ElbClassicFrontendManager.Probe probe) {
            this.probe = probe;
        }
    }
}
