package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.SweepWorker;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Restarts native guests after Linux reports a reboot of their private PID namespace. */
final class Ec2GuestReboots implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Ec2GuestReboots.class);
    private static final long REBOOT_EXIT_CODE = 128 + 1; // Linux reports namespace reboot as SIGHUP.
    private final DockerClient docker;
    private final String owner;
    private final BiFunction<String, Runnable, CompletableFuture<Void>> schedule;
    private final Consumer<Instance> restart;
    private final Map<String, Guest> guests = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final SweepWorker worker;

    Ec2GuestReboots(DockerClient docker, EmulatorConfig config,
                    BiFunction<String, Runnable, CompletableFuture<Void>> schedule,
                    Consumer<Instance> restart) {
        this(docker, config, schedule, restart, true);
    }

    Ec2GuestReboots(DockerClient docker, EmulatorConfig config,
                    BiFunction<String, Runnable, CompletableFuture<Void>> schedule,
                    Consumer<Instance> restart, boolean automatic) {
        this.docker = docker;
        this.owner = ContainerStorageHelper.ownerIdentity(config);
        this.schedule = schedule;
        this.restart = restart;
        worker = new SweepWorker("ec2-guest-reboots", automatic, 1, false, ignored -> poll());
    }

    void watch(Instance instance) {
        String container = instance.getDockerContainerId();
        String state = instance.getState() == null ? "" : instance.getState().getName();
        if (closed.get() || container == null || !("running".equals(state) || "pending".equals(state))) {
            return;
        }
        guests.putIfAbsent(container, new Guest(instance));
        if (started.compareAndSet(false, true)) {
            worker.start();
        }
    }

    void forget(Instance instance) {
        if (instance.getDockerContainerId() != null) {
            guests.remove(instance.getDockerContainerId());
        }
    }

    void poll() {
        guests.keySet().forEach(this::isRebooting);
    }

    /** Normal reboot must not make Auto Scaling replace the instance while its init restarts. */
    boolean isRebooting(String container) {
        Guest guest = guests.get(container);
        if (guest == null) {
            return false;
        }
        if (!container.equals(guest.instance.getDockerContainerId()) || guest.instance.getState() == null
                || !("running".equals(guest.instance.getState().getName())
                || "pending".equals(guest.instance.getState().getName()))) {
            guests.remove(container, guest);
            return false;
        }
        if (!wanted(container, guest) || guest.phase.get() == Phase.FAILED) {
            return false;
        }
        if (guest.phase.get() == Phase.RESTARTING) {
            return true;
        }
        InspectContainerResponse current;
        try {
            current = docker.inspectContainerCmd(container).exec();
        } catch (NotFoundException missing) {
            guests.remove(container, guest);
            return false;
        } catch (RuntimeException failure) {
            LOG.debugv(failure, "Could not inspect native EC2 guest for reboot; will retry: {0}", container);
            return false;
        }
        try {
            if (!requestedReboot(current)) {
                return false;
            }
            if (!owned(container, guest.instance, current)) {
                guest.phase.set(Phase.FAILED);
                LOG.warnv("Refusing native reboot after EC2 guest ownership changed: {0}", container);
                return false;
            }
            if (guest.phase.compareAndSet(Phase.IDLE, Phase.RESTARTING)) {
                schedule.apply(container, () -> resume(container, guest)).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        guest.phase.set(Phase.FAILED);
                        LOG.warnv(failure, "Could not schedule native EC2 reboot for {0}", container);
                    }
                });
            }
            return wanted(container, guest) && guest.phase.get() != Phase.FAILED;
        } catch (NotFoundException missing) {
            guests.remove(container, guest);
            return false;
        } catch (RuntimeException failure) {
            guest.phase.set(Phase.FAILED);
            LOG.warnv(failure, "Could not recover native EC2 reboot for {0}", container);
            return false;
        }
    }

    private void resume(String container, Guest guest) {
        try {
            // Stop, terminate and a replacement lease win even after a reboot was queued.
            if (!wanted(container, guest)) {
                return;
            }
            InspectContainerResponse current = docker.inspectContainerCmd(container).exec();
            if (!requestedReboot(current) || !owned(container, guest.instance, current)) {
                return;
            }
            RequestScopes.runAs(guest.instance.getOwnerId(), guest.instance.getRegion(),
                    () -> restart.accept(guest.instance));
        } catch (NotFoundException missing) {
            guests.remove(container, guest);
        } catch (RuntimeException failure) {
            guest.phase.set(Phase.FAILED);
            LOG.warnv(failure, "Native EC2 guest could not restart: {0}", container);
        } finally {
            guest.phase.compareAndSet(Phase.RESTARTING, Phase.IDLE);
        }
    }

    private boolean wanted(String container, Guest guest) {
        return !closed.get() && guests.get(container) == guest
                && container.equals(guest.instance.getDockerContainerId())
                && guest.instance.getState() != null
                && "running".equals(guest.instance.getState().getName());
    }

    private static boolean requestedReboot(InspectContainerResponse current) {
        return current.getState() != null && !Boolean.TRUE.equals(current.getState().getRunning())
                && "exited".equals(current.getState().getStatus())
                && Long.valueOf(REBOOT_EXIT_CODE).equals(current.getState().getExitCodeLong());
    }

    private boolean owned(String container, Instance instance, InspectContainerResponse current) {
        Map<String, String> labels = current.getConfig() == null ? null : current.getConfig().getLabels();
        return labels != null && container.equals(current.getId())
                && instance.getOwnerId() != null && !instance.getOwnerId().isBlank()
                && instance.getRegion() != null
                && ContainerStorageHelper.CONTAINER_LABEL_ALIASES.consistent(container, labels)
                && owner.equals(labels.get(ContainerStorageHelper.OWNER_LABEL))
                && "ec2".equals(labels.get(ContainerStorageHelper.SERVICE_LABEL))
                && instance.getOwnerId().equals(labels.get(ContainerStorageHelper.ACCOUNT_LABEL))
                && instance.getRegion().equals(labels.get(ContainerStorageHelper.REGION_LABEL))
                && instance.getInstanceId().equals(labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL))
                && ResolvedAmiImage.SYSTEMD_V1_RUNTIME.equals(labels.get(Ec2SystemdV1Runtime.RUNTIME_LABEL));
    }

    @Override
    public void close() {
        closed.set(true);
        guests.clear();
        worker.stop();
    }

    private enum Phase { IDLE, RESTARTING, FAILED }

    private static final class Guest {
        private final Instance instance;
        private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.IDLE);

        private Guest(Instance instance) {
            this.instance = instance;
        }
    }
}
