package io.floci.aws.services.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.backup.model.BackupJob;
import io.floci.aws.services.backup.model.RecoveryPoint;
import io.floci.aws.services.sns.SnsService;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.TestTime;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The delays a backup job and a vault lock are measured in, each checked on both sides of the
 * moment it elapses. The completion delay is the one the emulator ships with.
 */
class BackupServiceTimingTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "eu-west-1";
    private static final String VAULT = "timing";
    /** floci.services.backup.job-completion-delay-seconds as shipped in the main application.yml. */
    private static final int COMPLETION_DELAY_SECONDS = 3;
    private static final Duration ONE_MILLI = Duration.ofMillis(1);

    private final TestTime time = TestTime.create();
    private final InMemoryStorageFactory storage = new InMemoryStorageFactory(ACCOUNT);
    private BackupService service = newService();

    private BackupService newService() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().backup().jobCompletionDelaySeconds()).thenReturn(COMPLETION_DELAY_SECONDS);
        return new BackupService(storage, config, new RegionResolver("us-east-1", ACCOUNT), new ObjectMapper(),
                mock(SnsService.class), time.clock(), time.newScheduler());
    }

    private BackupJob startJob() {
        return service.startBackupJob(VAULT, "arn:aws:s3:::timing-bucket", "role", null, REGION);
    }

    private BackupJob job(BackupJob started) {
        return service.describeBackupJob(started.getBackupJobId());
    }

    @Test
    void aJobRunsAfterOneSecondAndCompletesWhenTheCompletionDelayIsUp() {
        service.createBackupVault(VAULT, null, null, Map.of(), REGION);
        long created = time.now().getEpochSecond();
        BackupJob started = startJob();
        assertEquals(created + COMPLETION_DELAY_SECONDS, started.getExpectedCompletionDate());

        time.advance(Duration.ofSeconds(1).minus(ONE_MILLI));
        assertEquals("CREATED", job(started).getState());
        assertEquals("0.0", job(started).getPercentDone());

        time.advance(ONE_MILLI);
        assertEquals("RUNNING", job(started).getState());
        assertEquals("50.0", job(started).getPercentDone());

        time.advance(Duration.ofSeconds(COMPLETION_DELAY_SECONDS - 1).minus(ONE_MILLI));
        assertEquals("RUNNING", job(started).getState());
        assertNull(job(started).getRecoveryPointArn());
        assertTrue(service.listRecoveryPointsByBackupVault(VAULT, REGION).isEmpty());

        time.advance(ONE_MILLI);
        assertEquals("COMPLETED", job(started).getState());
        assertEquals("100.0", job(started).getPercentDone());
        assertEquals(created + COMPLETION_DELAY_SECONDS, job(started).getCompletionDate());
        RecoveryPoint point = service.describeRecoveryPoint(VAULT, job(started).getRecoveryPointArn(), REGION);
        assertEquals(created, point.getCreationDate());
        assertEquals(created + COMPLETION_DELAY_SECONDS, point.getCompletionDate());
        assertEquals(1, service.describeBackupVault(VAULT, REGION).getNumberOfRecoveryPoints());
        assertEquals(0, time.pendingTasks(), "A finished job leaves nothing scheduled");
    }

    @Test
    void aStopTakesEffectOneSecondLaterAndTheJobNeverCompletes() {
        service.createBackupVault(VAULT, null, null, Map.of(), REGION);
        long created = time.now().getEpochSecond();
        BackupJob started = startJob();
        time.advance(Duration.ofMillis(1500));
        assertEquals("RUNNING", job(started).getState());

        service.stopBackupJob(started.getBackupJobId());
        assertEquals("ABORTING", job(started).getState());
        time.advance(Duration.ofSeconds(1).minus(ONE_MILLI));
        assertEquals("ABORTING", job(started).getState());

        time.advance(ONE_MILLI);
        assertEquals("ABORTED", job(started).getState());
        assertEquals(created + 2, job(started).getCompletionDate());

        time.advance(Duration.ofSeconds(COMPLETION_DELAY_SECONDS));
        assertEquals("ABORTED", job(started).getState());
        assertNull(job(started).getRecoveryPointArn());
        assertEquals(0, service.describeBackupVault(VAULT, REGION).getNumberOfRecoveryPoints());
        AwsException stoppedTwice = assertThrows(AwsException.class,
                () -> service.stopBackupJob(started.getBackupJobId()));
        assertEquals("InvalidRequestException", stoppedTwice.getErrorCode());
    }

    @Test
    void aJobStillCreatedAtRestartStartsInTheLastSecondOfItsStartWindow() {
        service.createBackupVault(VAULT, null, null, Map.of(), REGION);
        BackupJob started = startJob();
        assertEquals(time.now().getEpochSecond() + 3600, started.getStartBy());
        service.shutdown();

        time.advance(Duration.ofSeconds(3600));
        service = newService();
        service.resumeJobs();
        time.runDue();

        // Long past its completion delay as well, so it starts and finishes at once.
        assertEquals("COMPLETED", job(started).getState());
        assertEquals(1, service.describeBackupVault(VAULT, REGION).getNumberOfRecoveryPoints());
    }

    @Test
    void aJobStillCreatedAtRestartExpiresOnceItsStartWindowHasPassed() {
        service.createBackupVault(VAULT, null, null, Map.of(), REGION);
        BackupJob started = startJob();
        service.shutdown();

        time.advance(Duration.ofSeconds(3601));
        service = newService();
        service.resumeJobs();
        time.runDue();

        assertEquals("EXPIRED", job(started).getState());
        assertEquals(started.getStartBy() + 1, job(started).getCompletionDate());
        assertNull(job(started).getRecoveryPointArn());
        assertEquals(0, service.describeBackupVault(VAULT, REGION).getNumberOfRecoveryPoints());
    }

    @Test
    void aComplianceLockCanBeRemovedUntilItsChangeableDaysAreUpAndNeverAfter() {
        service.createBackupVault("removed-in-time", null, null, Map.of(), REGION);
        service.createBackupVault("left-too-long", null, null, Map.of(), REGION);
        long locked = time.now().getEpochSecond();
        service.putBackupVaultLockConfiguration("removed-in-time", REGION, 1L, null, 3L);
        service.putBackupVaultLockConfiguration("left-too-long", REGION, 1L, null, 3L);
        assertEquals(locked + Duration.ofDays(3).toSeconds(),
                service.describeBackupVault("left-too-long", REGION).getLockDate());

        time.advance(Duration.ofDays(3).minus(ONE_MILLI));
        assertDoesNotThrow(() -> service.deleteBackupVaultLockConfiguration("removed-in-time", REGION));
        assertFalse(service.describeBackupVault("removed-in-time", REGION).isLocked());

        time.advance(ONE_MILLI);
        AwsException deleted = assertThrows(AwsException.class,
                () -> service.deleteBackupVaultLockConfiguration("left-too-long", REGION));
        assertEquals("InvalidRequestException", deleted.getErrorCode());
        AwsException changed = assertThrows(AwsException.class,
                () -> service.putBackupVaultLockConfiguration("left-too-long", REGION, 2L, null, 3L));
        assertEquals("InvalidRequestException", changed.getErrorCode());
        assertTrue(service.describeBackupVault("left-too-long", REGION).isLocked());
        assertEquals(1L, service.describeBackupVault("left-too-long", REGION).getMinRetentionDays());
    }

    @Test
    void aLockedVaultReleasesARecoveryPointWhenItsMinimumRetentionHasElapsed() {
        service.createBackupVault(VAULT, null, null, Map.of(), REGION);
        BackupJob started = startJob();
        time.advance(Duration.ofSeconds(COMPLETION_DELAY_SECONDS));
        String point = job(started).getRecoveryPointArn();
        service.putBackupVaultLockConfiguration(VAULT, REGION, 1L, null, null);

        // Retention runs from the recovery point's creation, which is when its job started.
        time.advance(Duration.ofDays(1).minusSeconds(COMPLETION_DELAY_SECONDS).minus(ONE_MILLI));
        AwsException protectedPoint = assertThrows(AwsException.class,
                () -> service.deleteRecoveryPoint(VAULT, point, REGION));
        assertEquals("InvalidRequestException", protectedPoint.getErrorCode());
        assertEquals(1, service.describeBackupVault(VAULT, REGION).getNumberOfRecoveryPoints());

        time.advance(ONE_MILLI);
        service.deleteRecoveryPoint(VAULT, point, REGION);
        assertTrue(service.listRecoveryPointsByBackupVault(VAULT, REGION).isEmpty());
        assertEquals(0, service.describeBackupVault(VAULT, REGION).getNumberOfRecoveryPoints());
    }
}
