package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;

/** Retains both reference forms so removing a helper cannot hide a live namespace consumer. */
public record NetworkNamespaceLease(String id, String name) {
    public NetworkNamespaceLease {
        name = normalized(name);
    }

    public void requireUnused(DockerClient docker) {
        for (Container container : docker.listContainersCmd().withShowAll(true).exec()) {
            try {
                String mode = container.getHostConfig() == null ? null : container.getHostConfig().getNetworkMode();
                if (mode == null) {
                    mode = docker.inspectContainerCmd(container.getId()).exec().getHostConfig().getNetworkMode();
                }
                if (mode != null && mode.startsWith("container:")) {
                    String reference = normalized(mode.substring("container:".length()));
                    if (referencesLease(docker, reference)) {
                        throw new IllegalStateException("Network namespace still has a consumer: " + container.getId());
                    }
                }
            } catch (NotFoundException ignored) {
                // A concurrently removed container no longer holds this namespace.
            }
        }
    }

    private boolean referencesLease(DockerClient docker, String reference) {
        if (id.equals(reference) || reference.equals(name)) {
            return true;
        }
        try {
            return id.equals(docker.inspectContainerCmd(reference).exec().getId());
        } catch (NotFoundException ignored) {
            // Removing the helper also removes Docker's short-ID lookup. A surviving
            // consumer may still retain that namespace, so keep an ambiguous lease.
            return !reference.isEmpty() && id.startsWith(reference);
        }
    }

    private static String normalized(String value) {
        return value != null && value.startsWith("/") ? value.substring(1) : value;
    }
}
