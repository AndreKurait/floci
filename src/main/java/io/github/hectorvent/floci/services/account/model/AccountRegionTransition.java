package io.github.hectorvent.floci.services.account.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** The committed owner and terminal intent survive restart without a background publisher. */
@RegisterForReflection
public record AccountRegionTransition(String accountId, String partition, String regionName,
                                      String terminalStatus, long deadlineMillis, String revision) {
}
