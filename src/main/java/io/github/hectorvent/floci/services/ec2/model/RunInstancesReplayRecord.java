package io.github.hectorvent.floci.services.ec2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RegisterForReflection
public record RunInstancesReplayRecord(Map<String, List<String>> parameters,
                                      List<Allocation> allocations, Result result) {
    public RunInstancesReplayRecord {
        parameters = parameters.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        allocations = List.copyOf(allocations);
    }

    @RegisterForReflection
    public record Allocation(String instanceId, String rootVolumeId, String subnetId,
                             String privateIpAddress, String suppliedNetworkInterfaceId,
                             String generatedNetworkInterfaceId, Map<String, String> previousInterfaceTags,
                             Map<String, String> appliedInterfaceTags, List<String> additionalVolumeIds) {
        public Allocation {
            additionalVolumeIds = additionalVolumeIds == null ? List.of() : List.copyOf(additionalVolumeIds);
            previousInterfaceTags = previousInterfaceTags == null ? Map.of() : Map.copyOf(previousInterfaceTags);
            appliedInterfaceTags = appliedInterfaceTags == null ? Map.of() : Map.copyOf(appliedInterfaceTags);
        }

        public List<String> volumeIds() {
            List<String> ids = new ArrayList<>();
            ids.add(rootVolumeId);
            ids.addAll(additionalVolumeIds);
            return List.copyOf(ids);
        }

        public Allocation(String instanceId, String rootVolumeId, String subnetId,
                          String privateIpAddress, String suppliedNetworkInterfaceId, String generatedNetworkInterfaceId) {
            this(instanceId, rootVolumeId, subnetId, privateIpAddress, suppliedNetworkInterfaceId,
                    generatedNetworkInterfaceId, Map.of(), Map.of(), List.of());
        }

        public Allocation(String instanceId, String rootVolumeId, String subnetId,
                          String privateIpAddress, String suppliedNetworkInterfaceId) {
            this(instanceId, rootVolumeId, subnetId, privateIpAddress, suppliedNetworkInterfaceId, null);
        }
    }

    @RegisterForReflection
    public record Result(String reservationId, List<String> instanceIds) {
        public Result {
            instanceIds = List.copyOf(instanceIds);
        }
    }
}
