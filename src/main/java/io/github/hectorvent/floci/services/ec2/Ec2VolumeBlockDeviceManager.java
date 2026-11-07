package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.command.ListVolumesResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerExec;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.VolumeAttachment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Manages backing files and block device attachments for EC2 EBS volumes.
 *
 * <p>EBS volumes are backed by raw files on persistent storage. When attached to a running
 * instance container (which runs privileged), a Linux kernel loop device is bound to the
 * backing file via a lightweight helper container, and the corresponding device node is
 * created inside the target container at the requested path.
 */
@ApplicationScoped
public class Ec2VolumeBlockDeviceManager implements ContainerTeardown, Resettable {

    private static final Logger LOG = Logger.getLogger(Ec2VolumeBlockDeviceManager.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final DockerClient dockerClient;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;

    private volatile Boolean dockerAvailableCached;
    private volatile long dockerAvailableCheckTime;

    @Inject
    public Ec2VolumeBlockDeviceManager(DockerClient dockerClient,
                                       ContainerBuilder containerBuilder,
                                       ContainerLifecycleManager lifecycleManager,
                                       EmulatorConfig config) {
        this.dockerClient = dockerClient;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
    }

    /**
     * Checks if volume block device support is configured and Docker is reachable.
     */
    public boolean isAvailable() {
        if (config == null || config.services() == null || config.services().ec2() == null) {
            return false;
        }
        if (!config.services().ec2().volumeBlockDevices() || config.services().ec2().mock()) {
            return false;
        }
        if (dockerClient == null || lifecycleManager == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (dockerAvailableCached != null && (now - dockerAvailableCheckTime) < 5000) {
            return dockerAvailableCached;
        }
        try {
            dockerClient.pingCmd().exec();
            dockerAvailableCached = true;
        } catch (Exception e) {
            dockerAvailableCached = false;
        }
        dockerAvailableCheckTime = now;
        return dockerAvailableCached;
    }

    /**
     * Creates a raw backing file for the given volume.
     */
    public void createVolume(String volumeId, int sizeGib) {
        if (!isAvailable()) {
            return;
        }
        String helperId = ensureHelperContainer();
        if (helperId == null) {
            LOG.warnv("EC2 volume helper container unavailable; backing file for volume {0} will be created on attach",
                    volumeId);
            return;
        }
        int effectiveSize = sizeGib > 0 ? sizeGib : 8;
        String rawFile = "/volumes/" + volumeId + ".raw";
        String cmd = "truncate -s " + effectiveSize + "G " + rawFile;
        ContainerExec.Result result = execInContainer(helperId, new String[]{"sh", "-c", cmd}, DEFAULT_TIMEOUT_SECONDS);
        if (result.exitCode() != 0) {
            LOG.warnv("Failed to create backing file for volume {0}: {1}", volumeId, result.summary());
        }
    }

    private static final Pattern VALID_DEVICE_PATTERN =
            Pattern.compile("^(/dev/)?[a-zA-Z0-9/_-]+$");

    /**
     * Shell helper function that finds loop devices backing a file across both BusyBox
     * losetup (which outputs `/dev/loopX: 0 /path/file`) and util-linux losetup
     * (which supports {@code losetup -j} and formats {@code losetup -a} as {@code /dev/loopX: [dev]:ino (/path/file)}).
     */
    private static final String SHELL_LOOP_FINDER =
            "find_loops() {\n"
            + "  f=\"$1\"\n"
            + "  if losetup -j \"$f\" >/dev/null 2>&1; then\n"
            + "    losetup -j \"$f\" 2>/dev/null | cut -d: -f1\n"
            + "  else\n"
            + "    losetup -a 2>/dev/null | grep -E \"[ (]$f[) ]?$\" | cut -d: -f1\n"
            + "  fi\n"
            + "}\n";

    private final Object loopAllocationLock = new Object();

    public record PreparedMount(String volumeId, String device, String loopDevice,
                                String dockerVolume, String target) {}

    /**
     * Prepares an opt-in image's data filesystem before its entrypoint runs. The service resolves
     * the volume in the caller's account before passing it here; Docker labels retain that owner.
     */
    public PreparedMount prepareMount(Volume volume, Instance instance, Ec2ImageCatalog.VolumeMount mapping) {
        validateManagedAttachment(volume, instance, mapping.device());
        requireAvailable(volume.getVolumeId());
        return withVolumeLock(volume.getVolumeId(), () -> {
            String name = managedVolumeName(volume.getVolumeId());
            Map<String, String> labels = managedLabels(volume, instance.getOwnerId());
            InspectVolumeResponse existing = inspectManagedVolume(name, labels);
            String expectedLoop = existing == null ? "" : managedLoop(existing);
            String helperId = requireHelper(volume.getVolumeId());
            String loop;
            synchronized (loopAllocationLock) {
                loop = runManagedScript(helperId, PREPARE_MOUNT_SCRIPT, volume.getVolumeId(),
                        rawFile(volume.getVolumeId()), String.valueOf(volume.getSize()), expectedLoop);
            }
            if (!loop.matches("/dev/loop[0-9]+")) {
                throw storageFailure(volume.getVolumeId(), "helper returned an invalid loop device");
            }
            if (existing == null) {
                dockerClient.createVolumeCmd().withName(name).withDriver("local").withLabels(labels)
                        .withDriverOpts(Map.of("type", "ext4", "device", loop, "o", "rw")).exec();
            }
            InspectVolumeResponse prepared = inspectManagedVolume(name, labels);
            if (prepared == null || !loop.equals(managedLoop(prepared))) {
                throw storageFailure(volume.getVolumeId(), "Docker volume binding changed during preparation");
            }
            return new PreparedMount(volume.getVolumeId(), mapping.device(), loop, name, mapping.target());
        });
    }

    public void verifyMount(Volume volume, Instance instance, PreparedMount mount) {
        validateManagedAttachment(volume, instance, mount.device());
        requireAvailable(volume.getVolumeId());
        withVolumeLock(volume.getVolumeId(), () -> {
            if (!volume.getVolumeId().equals(mount.volumeId())
                    || !managedVolumeName(volume.getVolumeId()).equals(mount.dockerVolume())) {
                throw storageFailure(volume.getVolumeId(), "mount identifies a different volume");
            }
            InspectVolumeResponse existing = inspectManagedVolume(mount.dockerVolume(),
                    managedLabels(volume, instance.getOwnerId()));
            if (existing == null || !mount.loopDevice().equals(managedLoop(existing))) {
                throw storageFailure(volume.getVolumeId(), "retained Docker volume binding changed");
            }
            runManagedScript(requireHelper(volume.getVolumeId()), VERIFY_MOUNT_SCRIPT,
                    volume.getVolumeId(), rawFile(volume.getVolumeId()),
                    String.valueOf(volume.getSize()), mount.loopDevice());
            return null;
        });
    }

    /** Recovers the retained binding from Docker, without storing a second volume database. */
    public void verifyInstanceMount(Volume volume, Instance instance, String device) {
        requireAvailable(volume.getVolumeId());
        if (isRawDeviceGuest(instance)) {
            verifyRetainedRawVolume(volume, instance, device, true);
            return;
        }
        String name = managedVolumeName(volume.getVolumeId());
        InspectVolumeResponse existing = inspectManagedVolume(name, managedLabels(volume, instance.getOwnerId()));
        if (existing == null || instance.getDockerContainerId() == null) {
            throw storageFailure(volume.getVolumeId(), "retained mount is missing");
        }
        InspectContainerResponse guest = dockerClient.inspectContainerCmd(instance.getDockerContainerId()).exec();
        InspectContainerResponse.Mount mounted = guest.getMounts().stream()
                .filter(mount -> name.equals(mount.getName())).findFirst()
                .orElseThrow(() -> storageFailure(volume.getVolumeId(), "guest does not retain its data mount"));
        String loop = managedLoop(existing);
        boolean mapped = guest.getHostConfig().getDevices() != null
                && Arrays.stream(guest.getHostConfig().getDevices()).anyMatch(entry ->
                        loop.equals(entry.getPathOnHost())
                                && Ec2Service.normalizedDevice(device).equals(entry.getPathInContainer()));
        if (!Boolean.TRUE.equals(mounted.getRW()) || !mapped) {
            throw storageFailure(volume.getVolumeId(), "guest mount or block device changed");
        }
        verifyMount(volume, instance, new PreparedMount(volume.getVolumeId(),
                Ec2Service.normalizedDevice(device), loop, name, mounted.getDestination().getPath()));
    }

    private boolean isRawDeviceGuest(Instance instance) {
        return instance != null && instance.getDockerContainerId() != null
                && Boolean.TRUE.equals(dockerClient.inspectContainerCmd(instance.getDockerContainerId()).exec()
                        .getHostConfig().getPrivileged());
    }

    /**
     * A retained filesystem may use the existing privileged raw-device path. It must already
     * exist: reattachment never formats, recreates, or remounts a filesystem in a running guest.
     */
    private String verifyRetainedRawVolume(Volume volume, Instance instance, String device, boolean requireLoop) {
        validateManagedAttachment(volume, instance, device);
        requireAvailable(volume.getVolumeId());
        if (inspectManagedVolume(managedVolumeName(volume.getVolumeId()),
                managedLabels(volume, instance.getOwnerId())) != null) {
            throw storageFailure(volume.getVolumeId(), "a Docker data mount still retains this volume");
        }
        String script = VERIFY_RAW_SCRIPT + """
                [ "$(blkid -p -s TYPE -o value "$raw")" = ext4 ] || fail "retained filesystem changed"
                expected=$(losetup -j "$raw" | cut -d: -f1)
                if [ -n "$expected" ]; then
                """ + VERIFY_LOOP_SCRIPT + """
                fi
                """ + (requireLoop ? "[ -n \"$expected\" ] || fail \"retained loop is missing\"\n" : "")
                + "printf '%s' \"$expected\"\n";
        return runManagedScript(requireHelper(volume.getVolumeId()), script, volume.getVolumeId(),
                rawFile(volume.getVolumeId()), String.valueOf(volume.getSize()), "");
    }

    private void checkRetainedDevice(Volume volume, Instance instance, String device, String loop,
                                     boolean remove) {
        String script = """
                dev="$1"
                loop="$2"
                if [ -e "$dev" ] || [ -L "$dev" ]; then
                    [ ! -L "$dev" ] && [ -b "$dev" ] && [ -n "$loop" ] || exit 1
                    minor="${loop#/dev/loop}"
                    [ "$(stat -c '%t:%T' "$dev")" = "7:$(printf '%x' "$minor")" ] || exit 1
                """ + (remove ? """
                    command -v awk >/dev/null
                    if awk -v device="7:$minor" '$3 == device { found=1 } END { exit !found }' /proc/self/mountinfo; then
                        echo "guest filesystem is still mounted" >&2
                        exit 1
                    fi
                    rm "$dev"
                """ : "") + """
                fi
                """;
        ContainerExec.Result result = execInContainer(instance.getDockerContainerId(),
                new String[]{"sh", "-ec", script, "device", Ec2Service.normalizedDevice(device), loop},
                DEFAULT_TIMEOUT_SECONDS);
        if (result.exitCode() != 0) {
            throw storageFailure(volume.getVolumeId(), "retained guest device could not be verified: "
                    + result.summary());
        }
    }

    private static void validateManagedAttachment(Volume volume, Instance instance, String device) {
        if (instance == null || instance.getOwnerId() == null || instance.getOwnerId().isBlank()
                || !Objects.equals(volume.getRegion(), instance.getRegion())
                || !"ext4".equals(volume.getManagedFilesystem())
                || volume.getSize() <= 0 || volume.getSnapshotId() != null && !volume.getSnapshotId().isBlank()
                || Boolean.TRUE.equals(volume.getMultiAttachEnabled())
                || volume.getVolumeId().equals(instance.getRootVolumeId())
                || !Ec2Service.validDeviceName(device)
                || instance.getRootDeviceName() != null && Objects.equals(Ec2Service.normalizedDevice(device),
                        Ec2Service.normalizedDevice(instance.getRootDeviceName()))
                || volume.getAttachments().size() != 1
                || volume.getAttachments().stream().noneMatch(attachment ->
                        instance.getInstanceId().equals(attachment.getInstanceId())
                                && "attached".equals(attachment.getState())
                                && Objects.equals(Ec2Service.normalizedDevice(device),
                                        Ec2Service.normalizedDevice(attachment.getDevice())))) {
            throw storageFailure(volume.getVolumeId(), "invalid managed data-volume attachment");
        }
    }

    private static String rawFile(String volumeId) {
        if (volumeId == null || !volumeId.matches("vol-[a-zA-Z0-9]+")) {
            throw storageFailure(volumeId, "invalid backing file identity");
        }
        return "/volumes/" + volumeId + ".raw";
    }

    private String managedVolumeName(String volumeId) {
        rawFile(volumeId);
        return ContainerStorageHelper.resourceName(config, "ec2", null, volumeId);
    }

    private Map<String, String> managedLabels(Volume volume, String accountId) {
        Map<String, String> labels = new LinkedHashMap<>(ContainerStorageHelper.defaultLabels(config));
        labels.putAll(ContainerStorageHelper.resourceIdentityLabels("ec2", volume.getVolumeId(),
                accountId, volume.getRegion()));
        return labels;
    }

    private InspectVolumeResponse inspectManagedVolume(String name, Map<String, String> labels) {
        try {
            InspectVolumeResponse existing = dockerClient.inspectVolumeCmd(name).exec();
            if (!"local".equals(existing.getDriver()) || existing.getLabels() == null
                    || !existing.getLabels().entrySet().containsAll(labels.entrySet())) {
                throw storageFailure(name, "Docker volume has a different owner or driver");
            }
            managedLoop(existing);
            return existing;
        } catch (NotFoundException expected) {
            return null;
        }
    }

    private static String managedLoop(InspectVolumeResponse volume) {
        Map<String, String> options = volume.getOptions();
        String loop = options == null ? null : options.get("device");
        if (loop == null || !loop.matches("/dev/loop[0-9]+")
                || !Map.of("type", "ext4", "device", loop, "o", "rw").equals(options)) {
            throw storageFailure(volume.getName(), "Docker volume driver options changed");
        }
        return loop;
    }

    private void requireAvailable(String volumeId) {
        if (!isAvailable()) {
            throw storageFailure(volumeId, "managed volume support is disabled or Docker is unavailable");
        }
    }

    private String requireHelper(String volumeId) {
        String helperId = ensureHelperContainer();
        if (helperId == null) {
            throw storageFailure(volumeId, "storage helper is unavailable");
        }
        return helperId;
    }

    private String runManagedScript(String helperId, String script, String volumeId,
                                    String file, String size, String loop) {
        ContainerExec.Result result = execInContainer(helperId,
                new String[]{"sh", "-ec", script, "volume", file, size, loop}, DEFAULT_TIMEOUT_SECONDS);
        if (result.exitCode() != 0) {
            throw storageFailure(volumeId, result.summary());
        }
        return result.summary().trim();
    }

    private static AwsException storageFailure(String volumeId, String reason) {
        return new AwsException("InternalError", "Volume " + volumeId + ": " + reason, 500);
    }

    private static final String VERIFY_RAW_SCRIPT = """
            raw="$1"
            bytes=$(($2 * 1024 * 1024 * 1024))
            expected="$3"
            fail() { echo "$*" >&2; exit 1; }
            [ ! -L "$raw" ] || fail "symlink backing file"
            [ -f "$raw" ] || fail "missing or nonregular backing file"
            [ "$(stat -c %h "$raw")" -eq 1 ] || fail "backing file has another hard link"
            [ "$(stat -c %s "$raw")" -eq "$bytes" ] || fail "backing size mismatch"
            """;

    // util-linux losetup -j matches the backing device and inode, including across helper restarts.
    private static final String VERIFY_LOOP_SCRIPT = """
            [ -n "$expected" ] || fail "missing retained loop"
            loops=$(losetup -j "$raw" | cut -d: -f1)
            [ "$loops" = "$expected" ] || fail "loop backing identity changed"
            [ "$(losetup -ln -O BACK-INO "$expected" | tr -d ' ')" = "$(stat -c %i "$raw")" ] \\
                || fail "loop backing inode changed"
            [ "$(blkid -p -s TYPE -o value "$raw")" = ext4 ] || fail "filesystem changed"
            """;

    private static final String VERIFY_MOUNT_SCRIPT = VERIFY_RAW_SCRIPT + VERIFY_LOOP_SCRIPT;

    private static final String PREPARE_MOUNT_SCRIPT = """
            raw="$1"
            for tool in truncate stat losetup blkid mkfs.ext4; do command -v "$tool" >/dev/null; done
            if [ ! -e "$raw" ] && [ ! -L "$raw" ]; then
                (umask 077; set -C; : > "$raw")
                truncate -s "$(($2 * 1024 * 1024 * 1024))" "$raw"
            fi
            """ + VERIFY_RAW_SCRIPT + """
            if [ -n "$expected" ]; then
            """ + VERIFY_LOOP_SCRIPT + """
                echo "$expected"
                exit 0
            fi
            fs=$(blkid -p -s TYPE -o value "$raw") || {
                rc=$?
                [ "$rc" -eq 2 ] || fail "cannot identify backing filesystem"
            }
            if [ -z "$fs" ]; then
                [ "$(stat -c %b "$raw")" -eq 0 ] || fail "refusing to format existing data"
                mkfs.ext4 -q -F "$raw"
            else
                [ "$fs" = ext4 ] || fail "unsupported existing filesystem"
            fi
            loops=$(losetup -j "$raw" | cut -d: -f1)
            if [ -z "$loops" ]; then
                loop=$(losetup -f)
                [ -b "$loop" ] || mknod "$loop" b 7 "${loop#/dev/loop}"
                losetup "$loop" "$raw"
                expected="$loop"
            else
                expected="$loops"
            fi
            """ + VERIFY_LOOP_SCRIPT + """
            echo "$expected"
            """;

    public void deleteManagedVolume(Volume volume, String accountId) {
        releaseManagedVolume(volume, accountId, true);
    }

    private void releaseManagedVolume(Volume volume, String accountId, boolean deleteFile) {
        requireAvailable(volume.getVolumeId());
        withVolumeLock(volume.getVolumeId(), () -> {
            String name = managedVolumeName(volume.getVolumeId());
            InspectVolumeResponse existing = inspectManagedVolume(name, managedLabels(volume, accountId));
            String loop = existing == null ? "" : managedLoop(existing);
            String helperId = requireHelper(volume.getVolumeId());
            String verify = """
                    if [ ! -e "$1" ] && [ ! -L "$1" ]; then
                        [ -z "$3" ] || exit 1
                        exit 0
                    fi
                    """ + VERIFY_RAW_SCRIPT + """
                    if [ -n "$expected" ]; then
                    """ + VERIFY_LOOP_SCRIPT + """
                    else
                        fs=$(blkid -p -s TYPE -o value "$raw") || {
                            rc=$?
                            [ "$rc" -eq 2 ] || fail "cannot identify backing filesystem"
                        }
                        [ "$fs" = ext4 ] || {
                            [ -z "$fs" ] && [ "$(stat -c %b "$raw")" -eq 0 ] \\
                                || fail "refusing to delete unrecognized backing data"
                        }
                    fi
                    """;
            runManagedScript(helperId, verify, volume.getVolumeId(),
                    rawFile(volume.getVolumeId()), String.valueOf(volume.getSize()), loop);
            if (existing != null) {
                try {
                    // Docker refuses while any retained guest references it, even when stopped.
                    dockerClient.removeVolumeCmd(name).exec();
                } catch (RuntimeException failure) {
                    throw storageFailure(volume.getVolumeId(), "Docker volume could not be removed: "
                            + failure.getMessage());
                }
            }
            String release = SHELL_LOOP_FINDER + """
                    raw="$1"
                    [ ! -L "$raw" ] || exit 1
                    for loop in $(find_loops "$raw"); do
                        losetup -d "$loop" || exit 1
                    done
                    [ -z "$(find_loops "$raw")" ] || { echo "backing storage still in use" >&2; exit 1; }
                    """ + (deleteFile ? "rm -f \"$raw\"\n" : "");
            runManagedScript(helperId, release, volume.getVolumeId(),
                    rawFile(volume.getVolumeId()), String.valueOf(volume.getSize()), "");
            return null;
        });
    }

    /**
     * Deletes the volume's backing file and releases any associated loop device.
     */
    public boolean deleteVolume(String volumeId) {
        if (!isAvailable() || volumeId == null) {
            return false;
        }
        return withVolumeLock(volumeId, () -> {
            String helperId = ensureHelperContainer();
            if (helperId == null) {
                LOG.warnv("EC2 volume helper container unavailable; backing file for volume {0} could not be deleted",
                        volumeId);
                return false;
            }
            String rawFile = "/volumes/" + volumeId + ".raw";
            String script = SHELL_LOOP_FINDER
                    + "raw=\"$1\"\n"
                    + "for loop in $(find_loops \"$raw\"); do\n"
                    + "  losetup -d \"$loop\" || exit 1\n"
                    + "done\n"
                    + "if [ -n \"$(find_loops \"$raw\")\" ]; then\n"
                    + "  echo \"backing storage still in use: $raw\" >&2\n"
                    + "  exit 1\n"
                    + "fi\n"
                    + "rm -f \"$raw\"";
            ContainerExec.Result result = execInContainer(helperId,
                    new String[]{"sh", "-c", script, "delete", rawFile}, DEFAULT_TIMEOUT_SECONDS);
            if (result.exitCode() != 0) {
                LOG.warnv("Failed to delete backing storage for volume {0}: {1}", volumeId, result.summary());
            }
            return result.exitCode() == 0;
        });
    }

    private static final class RefCountedLock {
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger refCount = new AtomicInteger(1);
    }

    private final ConcurrentHashMap<String, RefCountedLock> volumeLocks = new ConcurrentHashMap<>();

    private <T> T withVolumeLock(String volumeId, Supplier<T> action) {
        if (volumeId == null) {
            return action.get();
        }
        RefCountedLock refLock = volumeLocks.compute(volumeId, (k, v) -> {
            if (v == null) {
                return new RefCountedLock();
            }
            v.refCount.incrementAndGet();
            return v;
        });
        refLock.lock.lock();
        try {
            return action.get();
        } finally {
            refLock.lock.unlock();
            volumeLocks.computeIfPresent(volumeId, (k, v) -> {
                if (v.refCount.decrementAndGet() <= 0) {
                    return null;
                }
                return v;
            });
        }
    }

    /**
     * Resizes the volume's raw backing file and refreshes any associated loop device.
     */
    public boolean resizeVolume(String volumeId, int sizeGib) {
        if (!isAvailable() || volumeId == null) {
            return false;
        }
        return withVolumeLock(volumeId, () -> {
            String helperId = ensureHelperContainer();
            if (helperId == null) {
                LOG.warnv("EC2 volume helper container unavailable; backing file for volume {0} could not be resized",
                        volumeId);
                return false;
            }
            int effectiveSize = sizeGib > 0 ? sizeGib : 8;
            String rawFile = "/volumes/" + volumeId + ".raw";
            String script = SHELL_LOOP_FINDER
                    + "raw=\"$1\"\n"
                    + "size=\"$2\"\n"
                    + "cur_bytes=$(stat -c %s \"$raw\" 2>/dev/null || echo 0)\n"
                    + "target_bytes=$((size * 1024 * 1024 * 1024))\n"
                    + "truncated=0\n"
                    + "if [ \"$cur_bytes\" -lt \"$target_bytes\" ]; then\n"
                    + "  truncate -s \"${size}G\" \"$raw\" || exit 1\n"
                    + "  truncated=1\n"
                    + "fi\n"
                    + "for loop in $(find_loops \"$raw\"); do\n"
                    + "  minor=$(echo \"$loop\" | sed 's/[^0-9]*//g')\n"
                    + "  if [ -n \"$minor\" ] && [ ! -b \"$loop\" ]; then\n"
                    + "    mknod \"$loop\" b 7 \"$minor\" 2>/dev/null || true\n"
                    + "  fi\n"
                    + "  if ! losetup -c \"$loop\"; then\n"
                    + "    if [ \"$truncated\" -eq 1 ]; then\n"
                    + "      if [ \"$cur_bytes\" -gt 0 ]; then\n"
                    + "        truncate -s \"$cur_bytes\" \"$raw\" 2>/dev/null || true\n"
                    + "      else\n"
                    + "        rm -f \"$raw\" 2>/dev/null || true\n"
                    + "      fi\n"
                    + "      for rloop in $(find_loops \"$raw\"); do\n"
                    + "        losetup -c \"$rloop\" 2>/dev/null || true\n"
                    + "      done\n"
                    + "    fi\n"
                    + "    exit 2\n"
                    + "  fi\n"
                    + "done";
            ContainerExec.Result result = execInContainer(helperId,
                    new String[]{"sh", "-c", script, "resize", rawFile, String.valueOf(effectiveSize)},
                    DEFAULT_TIMEOUT_SECONDS);
            if (result.exitCode() == 1) {
                LOG.warnv("Failed to resize backing file for volume {0}: {1}", volumeId, result.summary());
                return false;
            }
            if (result.exitCode() == 2) {
                LOG.warnv("Failed to refresh loop device for volume {0}: {1}", volumeId, result.summary());
                return false;
            }
            if (result.exitCode() != 0) {
                LOG.warnv("Failed to resize volume {0}: {1}", volumeId, result.summary());
                return false;
            }
            return true;
        });
    }

    /**
     * Attaches a volume to a target instance container as a block device.
     *
     * @return the device path inside the container if block device creation succeeded,
     *         or empty if degraded or container was not running.
     */
    public Optional<String> attachVolume(Volume volume, Instance instance, String requestedDevice) {
        if (volume.getManagedFilesystem() != null) {
            requireAvailable(volume.getVolumeId());
            if (isRawDeviceGuest(instance)) {
                return withVolumeLock(volume.getVolumeId(), () -> {
                    if (!lifecycleManager.isContainerRunning(instance.getDockerContainerId())) {
                        throw storageFailure(volume.getVolumeId(), "raw-device reattachment requires a running guest");
                    }
                    String loop = verifyRetainedRawVolume(volume, instance, requestedDevice, false);
                    checkRetainedDevice(volume, instance, requestedDevice, loop, false);
                    String attached = attachRawVolume(volume, instance, requestedDevice)
                            .orElseThrow(() -> storageFailure(volume.getVolumeId(),
                                    "retained raw-device attachment failed"));
                    checkRetainedDevice(volume, instance, requestedDevice,
                            verifyRetainedRawVolume(volume, instance, requestedDevice, true), false);
                    return Optional.of(attached);
                });
            }
            verifyInstanceMount(volume, instance, requestedDevice);
            return Optional.of(Ec2Service.normalizedDevice(requestedDevice));
        }
        return attachRawVolume(volume, instance, requestedDevice);
    }

    private Optional<String> attachRawVolume(Volume volume, Instance instance, String requestedDevice) {
        if (!isAvailable()) {
            LOG.debugv("Volume block devices disabled or unavailable; skipping device creation for volume {0}",
                    volume.getVolumeId());
            return Optional.empty();
        }
        if (instance == null || requestedDevice == null || requestedDevice.isBlank()) {
            return Optional.empty();
        }
        if (!VALID_DEVICE_PATTERN.matcher(requestedDevice).matches() || requestedDevice.contains("..")) {
            LOG.warnv("Rejecting invalid device path {0} for volume {1}", requestedDevice, volume.getVolumeId());
            return Optional.empty();
        }

        String targetContainerId = instance.getDockerContainerId();
        if (targetContainerId == null || targetContainerId.isBlank()) {
            LOG.warnv("Instance {0} has no Docker container ID; volume {1} attached as metadata only",
                    instance.getInstanceId(), volume.getVolumeId());
            return Optional.empty();
        }
        if (!lifecycleManager.isContainerRunning(targetContainerId)) {
            LOG.warnv("Container {0} for instance {1} is not running; volume {2} attached as metadata only",
                    targetContainerId, instance.getInstanceId(), volume.getVolumeId());
            return Optional.empty();
        }
        if (!isRawDeviceGuest(instance)) {
            LOG.debugv("Instance {0} is not privileged; raw volume {1} attached as metadata only",
                    instance.getInstanceId(), volume.getVolumeId());
            return Optional.empty();
        }

        String helperId = ensureHelperContainer();
        if (helperId == null) {
            LOG.warnv("EC2 volume helper container unavailable; volume {0} attached as metadata only",
                    volume.getVolumeId());
            return Optional.empty();
        }

        int effectiveSize = volume.getSize() > 0 ? volume.getSize() : 8;
        String rawFile = "/volumes/" + volume.getVolumeId() + ".raw";
        String helperScript = SHELL_LOOP_FINDER
                + "file=\"$1\"\n"
                + "size=\"$2\"\n"
                + "[ -f \"$file\" ] || truncate -s \"${size}G\" \"$file\"\n"
                + "loop=$(find_loops \"$file\" | head -n1)\n"
                + "if [ -z \"$loop\" ]; then\n"
                + "  loop=$(losetup -f 2>/dev/null)\n"
                + "  if [ -n \"$loop\" ]; then\n"
                + "    minor=$(echo \"$loop\" | sed 's/[^0-9]*//g')\n"
                + "    if [ -n \"$minor\" ] && [ ! -b \"$loop\" ]; then\n"
                + "      mknod \"$loop\" b 7 \"$minor\" 2>/dev/null || true\n"
                + "    fi\n"
                + "    losetup \"$loop\" \"$file\" || exit 1\n"
                + "  else\n"
                + "    exit 1\n"
                + "  fi\n"
                + "fi\n"
                + "echo \"$loop\"";

        ContainerExec.Result helperResult;
        synchronized (loopAllocationLock) {
            helperResult = execInContainer(helperId,
                    new String[]{"sh", "-c", helperScript, "helper", rawFile, String.valueOf(effectiveSize)},
                    DEFAULT_TIMEOUT_SECONDS);
        }
        if (helperResult.exitCode() != 0) {
            LOG.warnv("Failed to allocate loop device for volume {0} in helper: {1}",
                    volume.getVolumeId(), helperResult.summary());
            return Optional.empty();
        }

        String loopDev = helperResult.summary();
        if (loopDev == null || !loopDev.startsWith("/dev/loop")) {
            LOG.warnv("Unexpected loop device format for volume {0}: {1}", volume.getVolumeId(), loopDev);
            return Optional.empty();
        }
        String minorStr = loopDev.replaceAll("[^0-9]", "");
        String normalizedDevice = requestedDevice.startsWith("/") ? requestedDevice : "/dev/" + requestedDevice;

        String targetScript = "dev=\"$1\"\n"
                + "loop=\"$2\"\n"
                + "minor=\"$3\"\n"
                + "mkdir -p $(dirname \"$dev\")\n"
                + "if [ -e \"$dev\" ] || [ -L \"$dev\" ]; then\n"
                + "  rm -f \"$dev\"\n"
                + "fi\n"
                + "if [ -n \"$minor\" ]; then\n"
                + "  mknod \"$dev\" b 7 \"$minor\" 2>/dev/null || ln -sf \"$loop\" \"$dev\"\n"
                + "else\n"
                + "  ln -sf \"$loop\" \"$dev\"\n"
                + "fi\n"
                + "[ -b \"$dev\" ] || [ -L \"$dev\" ]";

        ContainerExec.Result targetResult = execInContainer(targetContainerId,
                new String[]{"sh", "-c", targetScript, "target", normalizedDevice, loopDev, minorStr},
                DEFAULT_TIMEOUT_SECONDS);
        if (targetResult.exitCode() != 0) {
            LOG.warnv("Failed to create block device {0} in container {1} for volume {2}: {3}",
                    normalizedDevice, targetContainerId, volume.getVolumeId(), targetResult.summary());
            return Optional.empty();
        }

        LOG.infov("Attached volume {0} as block device {1} (loop device {2}) to instance {3}",
                volume.getVolumeId(), normalizedDevice, loopDev, instance.getInstanceId());
        return Optional.of(normalizedDevice);
    }

    /**
     * Detaches a volume block device from the target container and releases the loop device.
     */
    public void detachVolume(Volume volume, Instance instance, String requestedDevice) {
        if (volume.getManagedFilesystem() != null) {
            if (instance == null) {
                throw storageFailure(volume.getVolumeId(), "managed volume owner is missing");
            }
            if (instance.getDockerContainerId() != null
                    && lifecycleManager.isContainerRunning(instance.getDockerContainerId())
                    && isRawDeviceGuest(instance)) {
                String loop = verifyRetainedRawVolume(volume, instance, requestedDevice, true);
                checkRetainedDevice(volume, instance, requestedDevice, loop, true);
            }
            releaseManagedVolume(volume, instance.getOwnerId(), false);
            return;
        }
        if (!isAvailable()) {
            return;
        }
        if (instance != null && requestedDevice != null && !requestedDevice.isBlank()
                && VALID_DEVICE_PATTERN.matcher(requestedDevice).matches() && !requestedDevice.contains("..")) {
            String targetContainerId = instance.getDockerContainerId();
            if (targetContainerId != null && !targetContainerId.isBlank()
                    && lifecycleManager.isContainerRunning(targetContainerId)) {
                String normalizedDevice = requestedDevice.startsWith("/") ? requestedDevice : "/dev/" + requestedDevice;
                execInContainer(targetContainerId, new String[]{"rm", "-f", normalizedDevice}, DEFAULT_TIMEOUT_SECONDS);
            }
        }

        String helperId = ensureHelperContainer();
        if (helperId != null) {
            String rawFile = "/volumes/" + volume.getVolumeId() + ".raw";
            String script = SHELL_LOOP_FINDER
                    + "raw=\"$1\"\n"
                    + "while true; do\n"
                    + "  loop=$(find_loops \"$raw\" | head -n1)\n"
                    + "  [ -n \"$loop\" ] || break\n"
                    + "  losetup -d \"$loop\" 2>/dev/null || break\n"
                    + "done";
            execInContainer(helperId,
                    new String[]{"sh", "-c", script, "detach", rawFile}, DEFAULT_TIMEOUT_SECONDS);
        }
    }

    /**
     * Restores block device nodes inside an instance container after it is started or restarted.
     */
    public void restoreInstanceVolumeDevices(Instance instance,
                                             List<VolumeAttachment> attachments,
                                             Function<String, Optional<Volume>> volumeLookup) {
        if (!isAvailable() || instance == null || attachments == null || attachments.isEmpty()) {
            return;
        }
        for (VolumeAttachment att : attachments) {
            Optional<Volume> volumeOpt = volumeLookup.apply(att.getVolumeId());
            if (volumeOpt.isPresent()) {
                attachVolume(volumeOpt.get(), instance, att.getDevice());
            }
        }
    }

    private synchronized String ensureHelperContainer() {
        String helperName = ContainerStorageHelper.resourceName(config, "ec2", null, "volume-helper");
        try {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(helperName).exec();
            if (Boolean.TRUE.equals(inspect.getState().getRunning())) {
                return inspect.getId();
            }
            dockerClient.startContainerCmd(helperName).exec();
            return inspect.getId();
        } catch (NotFoundException e) {
            // Container does not exist yet; proceed to create it
        } catch (Exception e) {
            LOG.warnv("Failed to inspect EC2 volume helper container {0}: {1}", helperName, e.getMessage());
            return null;
        }

        try {
            String helperImage = config.services().ec2().volumeHelperImage();
            ContainerBuilder.Builder builder = containerBuilder.newContainer(helperImage)
                    .withName(helperName)
                    .withPrivileged(true)
                    .withEntrypoint(List.of("sleep", "2147483647"))
                    .withLabels(ContainerStorageHelper.defaultLabels(config))
                    .withLabels(ContainerStorageHelper.resourceIdentityLabels("ec2", "volume-helper", "default", "global"));

            if (ContainerStorageHelper.isNamedVolumeMode(config)) {
                String volumeName = ContainerStorageHelper.dockerName(config, "ec2-volumes");
                ContainerStorageHelper.applyNamedVolume(builder, lifecycleManager, volumeName, "/volumes");
            } else {
                Path hostPath = ContainerStorageHelper.hostResourcePath(config, "ec2", "volumes");
                Files.createDirectories(hostPath);
                builder.withBind(hostPath.toAbsolutePath().toString(), "/volumes");
            }

            ContainerSpec spec = builder.build();
            return lifecycleManager.createAndStart(spec).containerId();
        } catch (Exception e) {
            LOG.warnv("Failed to create and start EC2 volume helper container {0}: {1}", helperName, e.getMessage());
            return null;
        }
    }

    /**
     * Removes the helper container on shutdown and on a state reset. The helper is looked up by
     * name and never created here, so one left behind by an earlier process is collected too.
     * Backing files stay in storage, and the next volume operation recreates the helper.
     */
    @Override
    public synchronized void stopManagedContainers() {
        if (!isAvailable()) {
            return;
        }
        String helperName = ContainerStorageHelper.resourceName(config, "ec2", null, "volume-helper");
        InspectContainerResponse helper;
        try {
            helper = dockerClient.inspectContainerCmd(helperName).exec();
        } catch (NotFoundException e) {
            LOG.debugv("No EC2 volume helper container {0} to remove", helperName);
            return;
        } catch (Exception e) {
            LOG.warnv("Failed to inspect EC2 volume helper container {0}: {1}", helperName, e.getMessage());
            return;
        }
        if (Boolean.TRUE.equals(helper.getState().getRunning())) {
            // One pass per file: a loop device still held open by an instance container is
            // detached lazily and stays listed, so retrying it until it disappears would spin.
            // Every loop is tried, and one that would not detach fails the script so it is reported.
            String script = SHELL_LOOP_FINDER
                    + "retained=\"$1\"\n"
                    + "failed=0\n"
                    + "for f in /volumes/*.raw; do\n"
                    + "  [ -f \"$f\" ] || continue\n"
                    + "  case \"$retained\" in *\" ${f##*/} \"*) continue;; esac\n"
                    + "  for loop in $(find_loops \"$f\"); do\n"
                    + "    losetup -d \"$loop\" 2>/dev/null || { echo \"could not detach $loop from $f\"; failed=1; }\n"
                    + "  done\n"
                    + "done\n"
                    + "exit $failed";
            ContainerExec.Result released = execInContainer(helper.getId(),
                    new String[]{"sh", "-c", script, "shutdown", retainedManagedFiles()}, DEFAULT_TIMEOUT_SECONDS);
            if (released.exitCode() != 0) {
                LOG.warnv("Failed to release loop devices before removing EC2 volume helper container {0}: {1}",
                        helperName, released.summary());
            }
        }
        // The helper only sleeps and would ignore SIGTERM as PID 1, so do not wait on a graceful stop.
        lifecycleManager.stopAndRemove(helper.getId(), null, 0);
    }

    private String retainedManagedFiles() {
        ListVolumesResponse inventory = dockerClient.listVolumesCmd().exec();
        if (inventory == null || inventory.getVolumes() == null) {
            throw new IllegalStateException("Docker volume inventory is unavailable; retaining loop bindings");
        }
        StringBuilder retained = new StringBuilder(" ");
        Map<String, String> owner = ContainerStorageHelper.defaultLabels(config);
        for (InspectVolumeResponse volume : inventory.getVolumes()) {
            Map<String, String> labels = volume.getLabels();
            if (labels != null && labels.entrySet().containsAll(owner.entrySet())
                    && "ec2".equals(labels.get(ContainerStorageHelper.SERVICE_LABEL))) {
                String id = labels.get(ContainerStorageHelper.RESOURCE_ID_LABEL);
                if (id != null && id.matches("vol-[a-zA-Z0-9]+")
                        && managedVolumeName(id).equals(volume.getName())) {
                    retained.append(id).append(".raw ");
                }
            }
        }
        return retained.toString();
    }

    private ContainerExec.Result execInContainer(String containerId, String[] cmd, int timeoutSeconds) {
        try {
            return ContainerExec.runMerged(dockerClient, containerId, cmd, timeoutSeconds);
        } catch (RuntimeException e) {
            return new ContainerExec.Result(-1, "", Objects.requireNonNullElse(e.getMessage(), e.toString()), false);
        }
    }

    @Override
    public void clear() {
        if (!isAvailable()) {
            return;
        }
        String helperId;
        try {
            helperId = dockerClient.inspectContainerCmd(
                    ContainerStorageHelper.resourceName(config, "ec2", null, "volume-helper")).exec().getId();
        } catch (NotFoundException expected) {
            if (ContainerStorageHelper.isNamedVolumeMode(config)) {
                InspectVolumeResponse storage;
                try {
                    storage = dockerClient.inspectVolumeCmd(
                            ContainerStorageHelper.dockerName(config, "ec2-volumes")).exec();
                } catch (NotFoundException absent) {
                    return;
                }
                if (!"local".equals(storage.getDriver()) || storage.getLabels() == null
                        || !storage.getLabels().entrySet()
                                .containsAll(ContainerStorageHelper.defaultLabels(config).entrySet())) {
                    throw new IllegalStateException("Cannot reset EC2 backing storage owned by another emulator");
                }
            } else {
                Path storage = ContainerStorageHelper.hostResourcePath(config, "ec2", "volumes");
                if (!Files.exists(storage, LinkOption.NOFOLLOW_LINKS)) {
                    return;
                }
                if (!Files.isDirectory(storage, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("EC2 backing storage is not a regular directory");
                }
            }
            helperId = requireHelper("reset");
        }
        if (helperId != null) {
            String script = SHELL_LOOP_FINDER
                    + "retained=\"$1\"\n"
                    + "for f in /volumes/*.raw; do\n"
                    + "  [ -f \"$f\" ] || continue\n"
                    + "  [ ! -L \"$f\" ] || continue\n"
                    + "  case \"$retained\" in *\" ${f##*/} \"*) continue;; esac\n"
                    + "  for loop in $(find_loops \"$f\"); do\n"
                    + "    losetup -d \"$loop\" || exit 1\n"
                    + "  done\n"
                    + "  [ -z \"$(find_loops \"$f\")\" ] || exit 1\n"
                    + "  rm -f \"$f\"\n"
                    + "done";
            ContainerExec.Result cleared = execInContainer(helperId,
                    new String[]{"sh", "-c", script, "clear", retainedManagedFiles()}, DEFAULT_TIMEOUT_SECONDS);
            if (cleared.exitCode() != 0) {
                throw new IllegalStateException("Could not clear EC2 backing storage: " + cleared.summary());
            }
            // The helper was only needed to delete the files, so a reset ends as a shutdown does:
            // without one.
            lifecycleManager.stopAndRemove(helperId, null, 0);
        }
    }
}
