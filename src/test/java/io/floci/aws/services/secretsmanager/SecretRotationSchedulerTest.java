package io.floci.aws.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.secretsmanager.model.Secret;
import io.floci.aws.testing.TestTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Covers the background sweep that makes a rotation schedule actually fire. Rotation itself runs
 * asynchronously, so these assert on the scheduling state the sweep drives - which secret is due
 * and where its next rotation lands - rather than on the rotation's outcome. The service and the
 * sweep share one controlled timeline, so a schedule lands on an exact instant and the sweep fires
 * on the tick that reaches it.
 */
class SecretRotationSchedulerTest {

    private static final String REGION = "us-east-1";
    private static final String LAMBDA_ARN = "arn:aws:lambda:us-east-1:000000000000:function:rotate";
    private static final String TOKEN = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";

    /** The shipped {@code rotation-tick-seconds}. */
    private static final Duration TICK = Duration.ofSeconds(60);

    private final TestTime time = TestTime.create();
    private final LambdaService lambda = mock(LambdaService.class);
    private SecretsManagerService service;
    private SecretRotationScheduler scheduler;

    @BeforeEach
    void setUp() {
        service = new SecretsManagerService(new InMemoryStorage<>(), 30,
                new RegionResolver(REGION, "000000000000"), lambda, new ObjectMapper(), null, List.of(),
                time.clock());
        scheduler = new SecretRotationScheduler(service);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    /** The sweep as it runs in production, except that its executor and clock are the test's. */
    private SecretRotationScheduler startSweep(boolean enabled) {
        SecretRotationScheduler sweep = new SecretRotationScheduler(service, TICK.toSeconds(), enabled,
                time.scheduler(), time.clock());
        sweep.onStart(null);
        return sweep;
    }

    private Instant nextRotation(String name) {
        return service.describeSecret(name, REGION).getNextRotationDate();
    }

    private void enableRotation(String name, Secret.RotationRules rules) {
        service.createSecret(name, "v1", null, null, null, null, REGION);
        service.rotateSecret(name, TOKEN, LAMBDA_ARN, rules, false, REGION);
        awaitTestStep(service, lambda, name);
    }

    /**
     * Enabling rotation tests it in the background against an AWSPENDING version, and a rotation
     * requested while that version exists is refused. Waits until the test step has ended.
     */
    private static void awaitTestStep(SecretsManagerService service, LambdaService lambda, String name) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            verify(lambda).invoke(anyString(), anyString(),
                    argThat(payload -> new String(payload, StandardCharsets.UTF_8).contains(name)), any());
            assertTrue(service.describeSecret(name, REGION).getVersions().values().stream()
                    .noneMatch(version -> version.getVersionStages().contains("AWSPENDING")));
        });
    }

    // ─── Next-rotation-date computation ────────────────────────────────────────

    @Test
    void enablingRotationWithAutomaticallyAfterDaysSchedulesThatManyDaysOut() {
        enableRotation("daily", new Secret.RotationRules(14, null, null));

        assertEquals(time.now().plus(14, ChronoUnit.DAYS), nextRotation("daily"),
                "enabling rotation must schedule the next one");
    }

    @Test
    void enablingRotationWithARateExpressionSchedulesThatInterval() {
        enableRotation("rated", new Secret.RotationRules(null, null, "rate(10 days)"));

        assertEquals(time.now().plus(10, ChronoUnit.DAYS), nextRotation("rated"));
    }

    @Test
    void enablingRotationWithAnHourlyRateSchedulesInHours() {
        enableRotation("hourly", new Secret.RotationRules(null, null, "rate(12 hours)"));

        assertEquals(time.now().plus(12, ChronoUnit.HOURS), nextRotation("hourly"));
    }

    @Test
    void enablingRotationWithACronExpressionSchedulesTheNextMatchingTime() {
        // Every day at 16:00 UTC, enabled at midnight and again one second before the hour.
        time.advanceTo(Instant.parse("2026-03-10T00:00:00Z"));
        enableRotation("crony", new Secret.RotationRules(null, null, "cron(0 16 * * ? *)"));
        assertEquals(Instant.parse("2026-03-10T16:00:00Z"), nextRotation("crony"));

        time.advanceTo(Instant.parse("2026-03-10T15:59:59Z"));
        enableRotation("crony-late", new Secret.RotationRules(null, null, "cron(0 16 * * ? *)"));
        assertEquals(Instant.parse("2026-03-10T16:00:00Z"), nextRotation("crony-late"));

        // At the hour itself the next fire is tomorrow's.
        time.advanceTo(Instant.parse("2026-03-10T16:00:00Z"));
        enableRotation("crony-on-the-hour", new Secret.RotationRules(null, null, "cron(0 16 * * ? *)"));
        assertEquals(Instant.parse("2026-03-11T16:00:00Z"), nextRotation("crony-on-the-hour"));
    }

    @Test
    void anUnparseableScheduleExpressionIsRejected() {
        service.createSecret("bad-cron", "v1", null, null, null, null, REGION);
        AwsException ex = assertThrows(AwsException.class,
                () -> service.rotateSecret("bad-cron", TOKEN, LAMBDA_ARN,
                        new Secret.RotationRules(null, null, "every other tuesday"),
                        false, REGION));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void cancellingRotationClearsTheSchedule() {
        enableRotation("cancelled", new Secret.RotationRules(14, null, null));
        service.cancelRotateSecret("cancelled", REGION);

        assertNull(service.describeSecret("cancelled", REGION).getNextRotationDate());
    }

    // ─── The sweep ─────────────────────────────────────────────────────────────

    /** Drags the schedule into the past so the next tick sees the secret as due. */
    private void makeDue(String name) {
        Secret secret = service.describeSecret(name, REGION);
        secret.setNextRotationDate(time.now().minus(1, ChronoUnit.MINUTES));
    }

    @Test
    void tickRotatesASecretWhoseScheduleHasPassed() {
        enableRotation("due", new Secret.RotationRules(14, null, null));
        makeDue("due");

        scheduler.tick(time.now());

        assertEquals(time.now().plus(14, ChronoUnit.DAYS), nextRotation("due"),
                "a fired rotation must reschedule a full interval ahead");
    }

    @Test
    void tickLeavesASecretThatIsNotDueYet() {
        enableRotation("not-due", new Secret.RotationRules(14, null, null));
        Instant before = service.describeSecret("not-due", REGION).getNextRotationDate();

        scheduler.tick(time.now());

        assertEquals(before, service.describeSecret("not-due", REGION).getNextRotationDate());
    }

    @Test
    void tickIgnoresSecretsWithRotationTurnedOff() {
        service.createSecret("plain", "v1", null, null, null, null, REGION);

        scheduler.tick(time.now());

        assertNull(service.describeSecret("plain", REGION).getNextRotationDate());
    }

    @Test
    void tickIgnoresSecretsManagedByAnotherService() {
        // The owning service rotates these itself; floci must not invoke a Lambda for them.
        service.createSecret("rds!db-1", "v1", null, null, null, null, "rds", REGION);
        service.rotateSecret("rds!db-1", TOKEN, null, new Secret.RotationRules(1, null, null), false, REGION);
        makeDue("rds!db-1");
        Instant due = service.describeSecret("rds!db-1", REGION).getNextRotationDate();

        scheduler.tick(time.now());

        assertEquals(due, service.describeSecret("rds!db-1", REGION).getNextRotationDate());
    }

    @Test
    void tickIgnoresReplicas() {
        enableRotation("replicated", new Secret.RotationRules(14, null, null));
        service.replicateSecretToRegions("replicated",
                List.of(new SecretsManagerService.ReplicaRegion("eu-west-1", null)), false, REGION);

        Secret replica = service.describeSecret("replicated", "eu-west-1");
        replica.setNextRotationDate(time.now().minus(1, ChronoUnit.MINUTES));

        // The replica is due on paper, but rotating it would write to a read-only copy.
        scheduler.tick(time.now());

        assertTrue(service.describeSecret("replicated", "eu-west-1").isReplica());
    }

    @Test
    void tickIgnoresSecretsScheduledForDeletion() {
        enableRotation("doomed", new Secret.RotationRules(14, null, null));
        makeDue("doomed");
        service.deleteSecret("doomed", 7, false, REGION);
        Instant due = service.describeSecret("doomed", REGION).getNextRotationDate();

        scheduler.tick(time.now());

        assertEquals(due, service.describeSecret("doomed", REGION).getNextRotationDate());
    }

    @Test
    void tickRotatesASecretOwnedByANonDefaultAccount() {
        // The sweep has no request to resolve an account from, so it falls back to the default
        // one. Enumerating and writing through that fallback would leave every other account's
        // secrets unrotated - and worse, write a phantom copy into the default account.
        String foreignAccount = "111122223333";
        AccountAwareStorageBackend<Secret> store =
                AccountAwareStorageBackend.inMemory("000000000000");
        LambdaService foreignLambda = mock(LambdaService.class);
        SecretsManagerService accountAware = new SecretsManagerService(store, 30,
                new RegionResolver(REGION, "000000000000"), foreignLambda, new ObjectMapper(),
                null, List.of(), time.clock());
        SecretRotationScheduler sweep = new SecretRotationScheduler(accountAware);

        accountAware.createSecret("foreign", "v1", null, null, null, null, REGION);
        accountAware.rotateSecret("foreign", TOKEN, LAMBDA_ARN,
                new Secret.RotationRules(14, null, null), false, REGION);
        awaitTestStep(accountAware, foreignLambda, "foreign");

        // Re-home the secret into the other account, ARN included, and clear the default copy.
        Secret secret = accountAware.describeSecret("foreign", REGION);
        secret.setArn(secret.getArn().replace("000000000000", foreignAccount));
        secret.setNextRotationDate(time.now().minus(1, ChronoUnit.MINUTES));
        store.putForAccount(foreignAccount, REGION + "::foreign", secret);
        store.deleteForAccount("000000000000", REGION + "::foreign");

        sweep.tick(time.now());

        Secret afterSweep = store.getForAccount(foreignAccount, REGION + "::foreign").orElseThrow();
        assertEquals(time.now().plus(14, ChronoUnit.DAYS), afterSweep.getNextRotationDate(),
                "a secret in another account must still be rotated");
        assertTrue(store.getForAccount("000000000000", REGION + "::foreign").isEmpty(),
                "rotating it must not leave a phantom copy in the default account");
    }

    @Test
    void tickSurvivesASecretThatCannotRotate() {
        // An in-flight rotation makes RotateSecret throw; the sweep must carry on to the rest.
        enableRotation("blocked", new Secret.RotationRules(14, null, null));
        service.putSecretValue("blocked", "half", null,
                "b1b2c3d4-e5f6-7890-abcd-ef1234567890", REGION, List.of("AWSPENDING"));
        makeDue("blocked");

        enableRotation("healthy", new Secret.RotationRules(14, null, null));
        makeDue("healthy");

        scheduler.tick(time.now());

        assertEquals(time.now().plus(14, ChronoUnit.DAYS), nextRotation("healthy"));
    }

    // ─── The sweep on its schedule ─────────────────────────────────────────────

    @Test
    void theSweepRotatesOnTheTickThatReachesTheSchedule() {
        startSweep(true);
        enableRotation("fortnightly", new Secret.RotationRules(14, null, null));
        Instant due = time.now().plus(14, ChronoUnit.DAYS);

        time.advance(Duration.ofDays(14).minusSeconds(1));
        assertEquals(due, nextRotation("fortnightly"), "one second early, every tick so far left it alone");

        time.advance(Duration.ofSeconds(1));
        assertEquals(due.plus(14, ChronoUnit.DAYS), nextRotation("fortnightly"),
                "the tick at the scheduled instant rotates and reschedules a full interval ahead");
        verify(lambda, timeout(5000)).invoke(eq(REGION), eq(LAMBDA_ARN),
                argThat(payload -> new String(payload, StandardCharsets.UTF_8).contains("\"Step\":\"createSecret\"")),
                eq(InvocationType.RequestResponse));
    }

    @Test
    void aScheduleBetweenTwoTicksWaitsForTheLaterOne() {
        startSweep(true);
        time.advance(Duration.ofSeconds(30));
        enableRotation("off-beat", new Secret.RotationRules(null, null, "rate(4 hours)"));
        Instant due = time.now().plus(4, ChronoUnit.HOURS);

        // Due half a minute after the tick at four hours; the next tick is a full minute after it.
        time.advance(Duration.ofHours(4).plusSeconds(29));
        assertEquals(due, nextRotation("off-beat"), "no tick falls between the schedule and the next minute");

        time.advance(Duration.ofSeconds(1));
        assertEquals(time.now().plus(4, ChronoUnit.HOURS), nextRotation("off-beat"),
                "the interval restarts from the tick that fired the rotation");
    }

    @Test
    void aSweepDisabledByConfigurationNeverRotates() {
        startSweep(false);
        enableRotation("idle", new Secret.RotationRules(14, null, null));
        Instant due = nextRotation("idle");

        time.advance(Duration.ofDays(15));

        assertEquals(due, nextRotation("idle"));
    }

    @Test
    void aSweepThatFailsStillRunsOnTheNextTick() {
        SecretsManagerService failing = new SecretsManagerService(new InMemoryStorage<>(), 30,
                new RegionResolver(REGION, "000000000000"), lambda, new ObjectMapper(), null, List.of(),
                time.clock()) {
            private boolean failed;

            @Override
            public List<RegionalSecret> listAllSecrets() {
                if (!failed) {
                    failed = true;
                    throw new IllegalStateException("storage is briefly unavailable");
                }
                return super.listAllSecrets();
            }
        };
        try {
            failing.createSecret("survivor", "v1", null, null, null, null, REGION);
            failing.rotateSecret("survivor", TOKEN, LAMBDA_ARN,
                    new Secret.RotationRules(null, null, "rate(4 hours)"), false, REGION);
            failing.describeSecret("survivor", REGION).setNextRotationDate(time.now());
            new SecretRotationScheduler(failing, TICK.toSeconds(), true, time.scheduler(), time.clock())
                    .onStart(null);

            time.advance(TICK);
            assertEquals(time.now().minus(TICK), failing.describeSecret("survivor", REGION).getNextRotationDate(),
                    "the first sweep failed before it reached the secret");

            time.advance(TICK);
            assertEquals(time.now().plus(4, ChronoUnit.HOURS),
                    failing.describeSecret("survivor", REGION).getNextRotationDate(),
                    "a failed sweep must not cancel the ones after it");
        } finally {
            failing.shutdown();
        }
    }
}
