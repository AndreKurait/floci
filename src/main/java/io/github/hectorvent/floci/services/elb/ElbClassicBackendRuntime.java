package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.services.elb.model.ClassicListener;
import io.github.hectorvent.floci.services.elb.model.ClassicLoadBalancerAttributes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** Updates one protected proxy without replacing its listeners or existing connections. */
final class ElbClassicBackendRuntime {
    private final List<ClassicListener> listeners;
    private final Map<String, Target> targets = new LinkedHashMap<>();
    private final Function<String, String> control;
    private final LongSupplier nanoTime;
    private boolean drainingEnabled;
    private long generation;

    ElbClassicBackendRuntime(List<ClassicListener> listeners, Map<String, List<String>> servers,
                             Map<String, String> addresses, Function<String, String> control,
                             LongSupplier nanoTime) {
        this.listeners = List.copyOf(listeners);
        this.control = control;
        this.nanoTime = nanoTime;
        addresses.forEach((id, address) -> targets.put(id,
                new Target(address, servers.getOrDefault(id, List.of()), ++generation)));
    }

    synchronized boolean reconcile(Map<String, String> wanted, ClassicLoadBalancerAttributes attributes) {
        Map<String, String> before = addresses();
        boolean changed = !before.equals(wanted);
        boolean disableDraining = drainingEnabled && !attributes.isConnectionDrainingEnabled();
        drainingEnabled = attributes.isConnectionDrainingEnabled();
        long now = nanoTime.getAsLong();
        long timeout = Duration.ofSeconds(attributes.getConnectionDrainingTimeout()).toNanos();
        for (Map.Entry<String, Target> entry : targets.entrySet()) {
            Target target = entry.getValue();
            if (target.address.equals(wanted.get(entry.getKey()))) {
                if (!target.registered) {
                    target.generation = ++generation;
                    target.healthy = false;
                }
                target.registered = true;
                target.completed = false;
                if (disableDraining && !target.healthy) {
                    command(target, "shutdown sessions server ", "");
                }
            } else {
                if (target.registered) {
                    command(target, "set server ", " state maint");
                    target.registered = false;
                    target.deadline = now + timeout;
                }
                if (!drainingEnabled || wanted.containsKey(entry.getKey())) {
                    target.deadline = now;
                }
            }
        }
        refresh();
        for (Map.Entry<String, String> entry : wanted.entrySet()) {
            Target target = targets.get(entry.getKey());
            if (target != null && !target.address.equals(entry.getValue())) {
                throw new IllegalStateException("The previous Classic backend address is still retiring");
            }
            if (target == null) {
                target = new Target(entry.getValue(), List.of(), ++generation);
                targets.put(entry.getKey(), target);
            }
            target.registered = true;
            target.completed = false;
            for (ClassicListener listener : listeners) {
                String section = "listener_" + listener.getLoadBalancerPort() + "/";
                if (target.servers.stream().anyMatch(server -> server.startsWith(section))) {
                    continue;
                }
                String server = section + "target_" + UUID.randomUUID().toString().replace("-", "");
                String tls = List.of("HTTPS", "SSL").stream().anyMatch(
                        protocol -> protocol.equalsIgnoreCase(listener.getInstanceProtocol())) ? " ssl verify none" : "";
                String response = control.apply("add server " + server + " "
                        + ElbClassicProxyConfig.ipv4(target.address) + ":" + listener.getInstancePort() + tls + "\n");
                require(response, "New server registered.");
                target.servers.add(server);
                changed = true;
                // Dynamic servers start in maintenance; admission belongs to the native health checker.
            }
        }
        return changed;
    }

    synchronized Map<String, String> addresses() {
        return targets.entrySet().stream().filter(entry -> entry.getValue().registered).collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().address));
    }

    synchronized Map<String, List<String>> servers() {
        return targets.entrySet().stream().filter(entry -> entry.getValue().registered).collect(
                Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue().servers)));
    }

    synchronized Set<String> draining() {
        return targets.entrySet().stream()
                .filter(entry -> !entry.getValue().registered && !entry.getValue().completed)
                .map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());
    }

    synchronized boolean hasPendingWork() {
        return targets.values().stream().anyMatch(target -> !target.registered);
    }

    synchronized long generation(String instanceId) {
        Target target = targets.get(instanceId);
        return target == null || !target.registered ? 0 : target.generation;
    }

    synchronized void healthChanged(String instanceId, boolean healthy) {
        Target target = targets.get(instanceId);
        if (target == null || !target.registered) {
            return;
        }
        command(target, "set server ", healthy ? " state ready" : " state maint");
        target.healthy = healthy;
        if (!healthy && !drainingEnabled) {
            command(target, "shutdown sessions server ", "");
        }
    }

    synchronized void refresh() {
        if (!hasPendingWork()) {
            return;
        }
        Map<String, Long> sessions = sessions(control.apply("show stat -1 4 -1\n"));
        long now = nanoTime.getAsLong();
        Iterator<Target> entries = targets.values().iterator();
        while (entries.hasNext()) {
            Target target = entries.next();
            if (target.registered) {
                continue;
            }
            if (!target.completed) {
                if (now - target.deadline >= 0) {
                    command(target, "shutdown sessions server ", "");
                    target.completed = true;
                } else {
                    boolean busy = false;
                    for (String server : target.servers) {
                        Long active = sessions.get(server);
                        if (active == null) {
                            throw new IllegalStateException("Classic proxy omitted backend statistics for " + server);
                        }
                        busy |= active > 0;
                    }
                    target.completed = !busy;
                }
            }
            if (!target.completed) {
                continue;
            }
            Iterator<String> servers = target.servers.iterator();
            while (servers.hasNext()) {
                String response = control.apply("del server " + servers.next() + "\n").strip();
                if (response.equals("Server still has connections attached to it, cannot remove it.")) {
                    continue;
                }
                require(response, "Server deleted.");
                servers.remove();
            }
            if (target.servers.isEmpty()) {
                entries.remove();
            }
        }
    }

    private void command(Target target, String prefix, String suffix) {
        String commands = target.servers.stream().map(server -> prefix + server + suffix + "\n")
                .collect(Collectors.joining());
        if (!commands.isEmpty()) {
            require(control.apply(commands), "");
        }
    }

    private static void require(String response, String expected) {
        if (!response.strip().equals(expected)) {
            throw new IllegalStateException("Classic proxy rejected its backend update: " + response.strip());
        }
    }

    static Map<String, Long> sessions(String response) {
        String[] lines = response.strip().split("\\R");
        if (lines.length == 0 || !lines[0].startsWith("# ")) {
            throw new IllegalStateException("Classic proxy did not return its statistics header");
        }
        List<String> columns = List.of(lines[0].substring(2).split(",", -1));
        int proxy = columns.indexOf("pxname");
        int server = columns.indexOf("svname");
        int active = columns.indexOf("scur");
        int queued = columns.indexOf("qcur");
        if (proxy < 0 || server < 0 || active < 0 || queued < 0) {
            throw new IllegalStateException("Classic proxy omitted required statistics columns");
        }
        Map<String, Long> result = new LinkedHashMap<>();
        int last = Math.max(Math.max(proxy, server), Math.max(active, queued));
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isBlank()) {
                continue;
            }
            String[] fields = lines[index].split(",", -1);
            if (fields.length <= last) {
                throw new IllegalStateException("Classic proxy returned an incomplete statistics row");
            }
            long activeCount = Long.parseLong(fields[active]);
            long queuedCount = Long.parseLong(fields[queued]);
            if (activeCount < 0 || queuedCount < 0) {
                throw new IllegalStateException("Classic proxy returned negative connection counts");
            }
            String key = fields[proxy] + "/" + fields[server];
            if (result.putIfAbsent(key, Math.addExact(activeCount, queuedCount)) != null) {
                throw new IllegalStateException("Classic proxy repeated backend statistics for " + key);
            }
        }
        return Map.copyOf(result);
    }

    private static final class Target {
        private final String address;
        private final List<String> servers;
        private boolean registered = true;
        private boolean completed;
        private boolean healthy;
        private long deadline;
        private long generation;

        private Target(String address, List<String> servers, long generation) {
            this.address = address;
            this.servers = new ArrayList<>(servers);
            this.generation = generation;
        }
    }
}
