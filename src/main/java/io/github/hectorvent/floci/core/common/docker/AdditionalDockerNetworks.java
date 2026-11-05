package io.github.hectorvent.floci.core.common.docker;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ContainerNetwork;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Existing Docker networks a launched container joins, besides its primary one, after it is
 * created and before it starts: {@code floci.services.lambda.additional-docker-networks} for
 * Lambda and {@code floci.services.ecs.additional-docker-networks} for ECS task containers.
 */
public final class AdditionalDockerNetworks {

    private AdditionalDockerNetworks() {
    }

    /**
     * The configured networks, checked to be distinct Docker network names or IDs.
     *
     * @param owner names the service in the error message, e.g. "Lambda" or "ECS"
     * @throws IllegalArgumentException for a blank, malformed or repeated entry
     */
    public static List<String> validated(Optional<List<String>> configured, String owner) {
        List<String> networks = configured == null ? List.of() : configured.orElse(List.of());
        Set<String> distinct = new HashSet<>();
        for (String network : networks) {
            if (network == null || !network.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")
                    || !distinct.add(network)) {
                throw new IllegalArgumentException(
                        owner + " additional Docker networks must contain distinct Docker network names or IDs");
            }
        }
        return List.copyOf(networks);
    }

    /**
     * Connects a created (not yet started) container to each network it is not already on.
     * Aliases of one network (its name and its ID) connect once; the primary network is skipped.
     */
    public static void attach(DockerClient dockerClient, String containerId, List<String> networks) {
        if (networks == null || networks.isEmpty()) {
            return;
        }
        Set<String> connectedIds = new HashSet<>();
        dockerClient.inspectContainerCmd(containerId).exec().getNetworkSettings().getNetworks()
                .forEach((name, endpoint) -> {
                    String id = endpoint.getNetworkID();
                    // Docker leaves NetworkID empty until the container starts.
                    connectedIds.add(id == null || id.isBlank()
                            ? dockerClient.inspectNetworkCmd().withNetworkId(name).exec().getId() : id);
                });
        for (String network : networks) {
            String id = dockerClient.inspectNetworkCmd().withNetworkId(network).exec().getId();
            if (connectedIds.add(id)) {
                dockerClient.connectToNetworkCmd()
                        .withNetworkId(id)
                        .withContainerId(containerId)
                        .withContainerNetwork(new AdditionalNetworkEndpoint())
                        .exec();
            }
        }
    }

    /**
     * Docker's gateway priority is not yet exposed by docker-java's endpoint model. -1 keeps the
     * primary network's gateway as the container's default route.
     */
    @RegisterForReflection
    static final class AdditionalNetworkEndpoint extends ContainerNetwork {
        @JsonProperty("GwPriority")
        public int getGatewayPriority() {
            return -1;
        }
    }
}
