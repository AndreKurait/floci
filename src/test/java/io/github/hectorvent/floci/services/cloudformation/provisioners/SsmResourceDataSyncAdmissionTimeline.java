package io.github.hectorvent.floci.services.cloudformation.provisioners;

import io.github.hectorvent.floci.services.ssm.SsmResourceDataSyncService;
import io.github.hectorvent.floci.testing.TestTime;

import java.time.Duration;

/**
 * Builds the resource data sync provisioner with its admission pauses on a test's timeline, for
 * tests outside this package: the two-second callback and the ten-second retries move the timeline
 * from the stack worker instead of holding it for that long.
 */
public final class SsmResourceDataSyncAdmissionTimeline {

    private SsmResourceDataSyncAdmissionTimeline() {
    }

    public static SsmResourceDataSyncCfnProvisioner provisioner(SsmResourceDataSyncService syncs, TestTime time) {
        return new SsmResourceDataSyncCfnProvisioner(syncs,
                seconds -> time.sleepAdvancing(Duration.ofSeconds(seconds)));
    }
}
