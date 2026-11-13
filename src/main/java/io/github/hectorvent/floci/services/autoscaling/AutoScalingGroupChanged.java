package io.github.hectorvent.floci.services.autoscaling;

/** Coordinates only: reconciliation reads the current group in its owning account. */
record AutoScalingGroupChanged(String accountId, String region, String name) {
}
