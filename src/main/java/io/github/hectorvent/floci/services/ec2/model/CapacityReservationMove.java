package io.github.hectorvent.floci.services.ec2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** The parameters of a completed capacity move, retained for client-token retries. */
@RegisterForReflection
public record CapacityReservationMove(String sourceId, String destinationId, int instanceCount) {
}
