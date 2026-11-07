package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LocallyBuiltHelperImage;
import io.github.hectorvent.floci.services.ec2.model.Instance;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

/** Delegates only a named service hierarchy to old init binaries, leaving resource limits with Docker. */
final class Ec2SystemdV1Runtime {
    static final String SERVICE = "ec2-init";
    static final String SCOPE_LABEL = "io.floci.ec2.init-scope";
    static final String RUNTIME_LABEL = "io.floci.ec2.init-runtime";
    static final String ROOT = "/run/floci-cgroups";
    static final String READY = "/run/floci-init-ready";
    private static final String BINARY = "/usr/local/bin/floci-ec2-init";
    private static final String BINARY_READY = "/run/floci-init-binary-ready";
    static final String GATE = """
            set -eu
            test -x /sbin/init
            printf '%s\\n' "$1" > /sys/hypervisor/uuid
            chmod 0444 /sys/hypervisor/uuid
            mount -o remount,ro /sys/hypervisor
            while [ ! -f /run/floci-init-binary-ready ]; do sleep 0.05; done
            exec /run/floci-ec2-init --boot /sbin/init
            """;
    private static final String MOUNT = ROOT + "/systemd";
    private final DockerClient docker;
    private final ContainerBuilder builder;
    private final ContainerLifecycleManager lifecycle;
    private final EmulatorConfig config;
    private final Map<String, String> helpers = new HashMap<>();
    private final Set<String> retiredHelpers = new HashSet<>();

    /** Keep helper ownership and retirement state separate from the workload container lifecycle. */
    Ec2SystemdV1Runtime(DockerClient docker, ContainerBuilder builder,
                       ContainerLifecycleManager lifecycle, EmulatorConfig config) {
        this.docker = docker;
        this.builder = builder;
        this.lifecycle = lifecycle;
        this.config = config;
    }

    /** Supply the EC2-shaped hardware marker cloud-init reads, stable across restarts and scoped to the instance. */
    static String hardwareUuid(String accountId, String region, String instanceId) {
        String hex = "ec2" + Digests.sha256Hex(accountId + "\n" + region + "\n" + instanceId).substring(3, 32);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    /** Adopt a verified helper or create one, then mount only this guest's delegated service hierarchy. */
    synchronized void prepare(Instance instance, String accountId, String region) {
        String name = name(instance.getInstanceId());
        try {
            InspectContainerResponse existing = docker.inspectContainerCmd(name).exec();
            String scope = requireHelper(existing, instance.getInstanceId(), region, accountId);
            helpers.put(instance.getInstanceId(), existing.getId());
            ensureMounted(existing, scope);
            return;
        } catch (NotFoundException expected) {
            // No lease exists for this instance.
        }
        String image = config.services().ec2().metadataHelperImage();
        LocallyBuiltHelperImage.ensureBuilt(docker, builder, image, "floci/network-helper:local",
                "/docker/network-helper.Dockerfile");
        String scope = "floci-" + UUID.randomUUID().toString().replace("-", "");
        ContainerSpec spec = builder.newContainer(image).withName(name)
                .withDockerNetwork(Optional.empty()).withNetworkMode("none")
                .withUser("0").withCgroupnsMode("host").withPidMode("host")
                .withApparmorProfile(config.services().ec2().nativeInitApparmorProfile().orElse("unconfined"))
                .withCapAdd("SYS_ADMIN").withCapAdd("SYS_TIME").withCapAdd("SYS_PTRACE")
                .withEntrypoint(List.of("sh", "-c")).withCmd(List.of("exec sleep 2147483647"))
                .withMount(new Mount().withType(MountType.TMPFS).withTarget(ROOT))
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(SERVICE,
                        instance.getInstanceId(), accountId, region))
                .withLabels(Map.of(ContainerStorageHelper.OWNER_LABEL, owner(), SCOPE_LABEL, scope))
                .build();
        String helper = lifecycle.create(spec);
        helpers.put(instance.getInstanceId(), helper);
        lifecycle.startCreated(helper, spec);
        ensureMounted(docker.inspectContainerCmd(helper).exec(), scope);
    }

    /** Restore an owned native guest's helper reference; refuse a changed identity instead of silently adopting it. */
    synchronized boolean rememberGuest(Instance instance, InspectContainerResponse guest) {
        Map<String, String> labels = guest.getConfig() == null ? null : guest.getConfig().getLabels();
        if (labels == null || !ResolvedAmiImage.SYSTEMD_V1_RUNTIME.equals(labels.get(RUNTIME_LABEL))) {
            if (helpers.containsKey(instance.getInstanceId())) {
                throw new IllegalStateException("EC2 init guest lost its runtime identity");
            }
            return false;
        }
        if (!ContainerStorageHelper.CONTAINER_LABEL_ALIASES.consistent(guest.getId(), labels)
                || !"ec2".equals(labels.get(ContainerStorageHelper.SERVICE_LABEL))
                || !owner().equals(labels.get(ContainerStorageHelper.OWNER_LABEL))
                || instance.getOwnerId() == null || instance.getOwnerId().isBlank()
                || !instance.getOwnerId().equals(labels.get(ContainerStorageHelper.ACCOUNT_LABEL))
                || !instance.getInstanceId().equals(labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL))
                || !instance.getRegion().equals(labels.get(ContainerStorageHelper.REGION_LABEL))) {
            throw new IllegalStateException("EC2 init guest ownership changed");
        }
        helpers.putIfAbsent(instance.getInstanceId(), name(instance.getInstanceId()));
        return true;
    }

    synchronized boolean hasGuest(Instance instance) {
        return helpers.containsKey(instance.getInstanceId());
    }

    /**
     * Install the native bootstrap, isolate its clock and delegate its service hierarchy before releasing init.
     * A guest whose final gate already exists is left running; any failed prerequisite keeps that gate closed.
     */
    synchronized boolean release(Instance instance, String containerId) {
        InspectContainerResponse guest = docker.inspectContainerCmd(containerId).exec();
        if (!rememberGuest(instance, guest)) {
            return false;
        }
        ContainerExec.Result ready = ContainerExec.run(docker, containerId,
                new String[]{"test", "-f", READY}, 10);
        ready.throwIfTimedOut(containerId);
        if (ready.exitCode() == 0) {
            return true;
        }
        if (ready.exitCode() != 1) {
            throw new IllegalStateException("Cannot inspect EC2 init gate: " + ready.summary());
        }
        if (!Boolean.TRUE.equals(guest.getState().getRunning()) || guest.getState().getPidLong() == null
                || guest.getState().getPidLong() <= 1) {
            throw new IllegalStateException("EC2 init guest has no running PID");
        }
        InspectContainerResponse helper = docker.inspectContainerCmd(name(instance.getInstanceId())).exec();
        String scope = requireHelper(helper, instance.getInstanceId(), instance.getRegion(), instance.getOwnerId());
        ensureMounted(helper, scope);
        String staging = "/.floci-ec2-init-" + UUID.randomUUID();
        try (InputStream archive = docker.copyArchiveFromContainerCmd(helper.getId(), BINARY).exec()) {
            run(containerId, "mkdir", "-m", "0700", staging);
            try {
                // Docker's archive API writes beneath the running guest's tmpfs mounts.
                docker.copyArchiveToContainerCmd(containerId).withRemotePath(staging)
                        .withTarInputStream(archive).exec();
                run(containerId, "mv", staging + "/floci-ec2-init", "/run/floci-ec2-init");
            } finally {
                run(containerId, "rm", "-rf", "--", staging);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot install the native EC2 bootstrap from its helper", failure);
        }
        run(containerId, "touch", BINARY_READY);
        run(containerId, "sh", "-ec", """
                for attempt in $(seq 1 100); do
                    test ! -f /run/floci-time-ready || exit 0
                    sleep 0.05
                done
                echo 'Native EC2 init did not create its private clock namespace' >&2
                exit 1
                """);
        String pid = Long.toString(guest.getState().getPidLong());
        run(helper.getId(), BINARY, "--configure-time", pid, containerId);
        run(helper.getId(), BINARY, "--delegate-init", pid, containerId,
                MOUNT + "/" + scope + "/cgroup.procs");
        InspectContainerResponse current = docker.inspectContainerCmd(containerId).exec();
        if (!guest.getState().getPidLong().equals(current.getState().getPidLong())
                || !Boolean.TRUE.equals(current.getState().getRunning())) {
            throw new IllegalStateException("EC2 init guest changed while delegating its cgroup");
        }
        run(containerId, "touch", READY);
        return true;
    }

    /** Retire the empty service hierarchy before deleting its helper, retaining proof for a lost delete response. */
    synchronized void remove(Instance instance) {
        String reference = helpers.get(instance.getInstanceId());
        if (reference == null) {
            return;
        }
        InspectContainerResponse helper;
        try {
            helper = docker.inspectContainerCmd(reference).exec();
        } catch (NotFoundException expected) {
            if (retiredHelpers.remove(reference)) {
                helpers.remove(instance.getInstanceId());
                return;
            }
            throw new IllegalStateException("EC2 init helper disappeared before its service hierarchy was retired",
                    expected);
        }
        remove(helper, instance.getInstanceId(), instance.getRegion(), instance.getOwnerId());
        helpers.remove(instance.getInstanceId());
    }

    /** Recover declared helpers after restart and retire only verified helpers whose instance no longer exists. */
    synchronized void reconcileOrphans(BiPredicate<String, String> stillDeclared) {
        for (Container container : docker.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(ContainerStorageHelper.SERVICE_LABEL, SERVICE,
                        ContainerStorageHelper.OWNER_LABEL, owner())).exec()) {
            Map<String, String> listedLabels = container.getLabels();
            if (listedLabels == null || !SERVICE.equals(listedLabels.get(ContainerStorageHelper.SERVICE_LABEL))
                    || !owner().equals(listedLabels.get(ContainerStorageHelper.OWNER_LABEL))) {
                continue;
            }
            InspectContainerResponse helper = docker.inspectContainerCmd(container.getId()).exec();
            Map<String, String> labels = helper.getConfig().getLabels();
            String instance = labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL);
            String region = labels.get(ContainerStorageHelper.REGION_LABEL);
            String account = labels.get(ContainerStorageHelper.ACCOUNT_LABEL);
            requireHelper(helper, instance, region, account);
            if (!stillDeclared.test(region, instance)) {
                remove(helper, instance, region, account);
            } else {
                helpers.put(instance, helper.getId());
            }
        }
    }

    /** Remove only the verified, empty guest scope; another guest's hierarchy remains mounted by its own helper. */
    private void remove(InspectContainerResponse helper, String instance, String region, String account) {
        String scope = requireHelper(helper, instance, region, account);
        helpers.put(instance, helper.getId());
        if (!Boolean.TRUE.equals(helper.getState().getRunning())) {
            docker.startContainerCmd(helper.getId()).exec();
        }
        run(helper.getId(), "sh", "-ec", """
                if ! mountpoint -q "$1" &&
                    awk -F: '$2 == "name=systemd" { found=1 } END { exit !found }' /proc/self/cgroup; then
                    mkdir -p "$1"
                    mount -t cgroup -o none,name=systemd cgroup "$1"
                fi
                if mountpoint -q "$1"; then
                    if [ -d "$1/$2" ]; then
                        find "$1/$2" -depth -type d -exec sh -ec '
                            test -z "$(cat "$1/cgroup.procs")"
                            rmdir "$1"
                        ' retire-group {} \\;
                        test ! -e "$1/$2"
                    fi
                    umount "$1"
                    # Directory removal precedes asynchronous kernel release of its groups.
                    attempts=0
                    while awk -F: '$2 == "name=systemd" { found=1 } END { exit !found }' /proc/self/cgroup; do
                        mount -t cgroup -o none,name=systemd,ro cgroup "$1"
                        remaining=$(find "$1" -mindepth 1 -maxdepth 1 -type d -print -quit)
                        umount "$1"
                        # Another guest still owns the hierarchy.
                        [ -z "$remaining" ] || break
                        attempts=$((attempts + 1))
                        [ "$attempts" -lt 40 ] || break
                        sleep 0.1
                    done
                fi
                """, "retire-init", MOUNT, scope);
        // Keep retirement proof if Docker deletes the helper but its response is lost.
        retiredHelpers.add(helper.getId());
        lifecycle.removeIfExistsStrict(helper.getId());
        retiredHelpers.remove(helper.getId());
        helpers.remove(instance, helper.getId());
    }

    /** Return the private scope only after checking the helper's complete resource ownership and scope syntax. */
    String requireHelper(InspectContainerResponse helper, String instance, String region, String account) {
        Map<String, String> labels = helper.getConfig().getLabels();
        if (labels == null || instance == null || region == null
                || account == null || account.isBlank()
                || !ContainerStorageHelper.CONTAINER_LABEL_ALIASES.consistent(helper.getId(), labels)
                || !SERVICE.equals(labels.get(ContainerStorageHelper.SERVICE_LABEL))
                || !owner().equals(labels.get(ContainerStorageHelper.OWNER_LABEL))
                || !account.equals(labels.get(ContainerStorageHelper.ACCOUNT_LABEL))
                || !instance.equals(labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL))
                || !region.equals(labels.get(ContainerStorageHelper.REGION_LABEL))) {
            throw new IllegalStateException("EC2 init helper ownership changed");
        }
        String scope = labels.get(SCOPE_LABEL);
        if (scope == null || !scope.matches("floci-[a-f0-9]{32}")) {
            throw new IllegalStateException("EC2 init helper has no valid private cgroup scope");
        }
        return scope;
    }

    /** Reestablish the verified service mount, invalidating any earlier proof that this helper was retired. */
    private void ensureMounted(InspectContainerResponse helper, String scope) {
        retiredHelpers.remove(helper.getId());
        if (!Boolean.TRUE.equals(helper.getState().getRunning())) {
            docker.startContainerCmd(helper.getId()).exec();
        }
        run(helper.getId(), "sh", "-ec",
                "mkdir -p \"$1\"; if ! mountpoint -q \"$1\"; then "
                        + "mount -t cgroup -o none,name=systemd cgroup \"$1\"; fi; mkdir -p \"$1/$2\"",
                "prepare-init", MOUNT, scope);
    }

    private String name(String instanceId) {
        return ContainerStorageHelper.resourceName(config, SERVICE, null, instanceId);
    }

    private String owner() {
        return ContainerStorageHelper.ownerIdentity(config);
    }

    /** Bound helper operations and propagate failures so launch cannot release init after incomplete preparation. */
    private void run(String container, String... command) {
        ContainerExec.Result result = ContainerExec.run(docker, container, command, 15);
        if (result.timedOut() || result.exitCode() != 0) {
            throw new IllegalStateException("EC2 init operation failed: " + result.summary());
        }
    }
}
