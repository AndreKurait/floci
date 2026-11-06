package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.model.Device;

import java.util.List;
import java.util.Map;

/**
 * The Linux process options a container is created with, as {@code docker run} names them:
 * {@code --cap-add}, {@code --cap-drop}, {@code --device}, {@code --shm-size}, {@code --tmpfs},
 * {@code --memory-swap} and {@code --memory-swappiness}; {@code --init} is
 * {@link ContainerSpec#init()}. {@link #NONE} leaves every one of them to the daemon.
 *
 * @param capAdd capabilities added to the daemon's default set, named without {@code CAP_}
 * @param capDrop capabilities dropped from the daemon's default set, named without {@code CAP_}
 * @param devices host devices exposed inside the container
 * @param shmSizeBytes size of {@code /dev/shm} in bytes (null = daemon default)
 * @param tmpfs tmpfs mounts, keyed by container path, each with Docker's option string
 *        (for example {@code "noexec,size=64m"})
 * @param memorySwapBytes memory plus swap the container may use, in bytes; equal to the memory
 *        limit means no swap (null = daemon default)
 * @param memorySwappiness swappiness from 0 to 100 (null = daemon default)
 * @param networkSysctls settings for the container's own network namespace
 * @param pidMode Docker PID namespace mode (null = private namespace)
 */
public record LinuxOptions(
        List<String> capAdd,
        List<String> capDrop,
        List<Device> devices,
        Long shmSizeBytes,
        Map<String, String> tmpfs,
        Long memorySwapBytes,
        Long memorySwappiness,
        Map<String, String> networkSysctls,
        String pidMode
) {
    public LinuxOptions {
        networkSysctls = Map.copyOf(networkSysctls);
        networkSysctls.forEach((key, value) -> {
            if (!key.matches("net\\.[a-zA-Z0-9_.]+") || value.isBlank()) {
                throw new IllegalArgumentException("Expected a network-namespace sysctl and value");
            }
        });
    }

    public LinuxOptions(List<String> capAdd, List<String> capDrop, List<Device> devices,
                        Long shmSizeBytes, Map<String, String> tmpfs, Long memorySwapBytes,
                        Long memorySwappiness, Map<String, String> networkSysctls) {
        this(capAdd, capDrop, devices, shmSizeBytes, tmpfs, memorySwapBytes, memorySwappiness,
                networkSysctls, null);
    }

    public LinuxOptions(List<String> capAdd, List<String> capDrop, List<Device> devices,
                        Long shmSizeBytes, Map<String, String> tmpfs,
                        Long memorySwapBytes, Long memorySwappiness) {
        this(capAdd, capDrop, devices, shmSizeBytes, tmpfs, memorySwapBytes, memorySwappiness, Map.of());
    }

    public static final LinuxOptions NONE =
            new LinuxOptions(List.of(), List.of(), List.of(), null, Map.of(), null, null);
}
