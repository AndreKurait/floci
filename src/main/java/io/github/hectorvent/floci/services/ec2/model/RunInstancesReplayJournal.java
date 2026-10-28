package io.github.hectorvent.floci.services.ec2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
public record RunInstancesReplayJournal(String regionalZone, Map<String, RunInstancesReplayRecord> zones) {
    public RunInstancesReplayJournal {
        zones = Map.copyOf(zones);
    }
}
