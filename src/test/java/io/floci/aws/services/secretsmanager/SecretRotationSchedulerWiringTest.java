package io.floci.aws.services.secretsmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.secretsmanager.model.Secret;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The sweep in real time: the scheduler built the way the application builds it, on its own thread
 * and the system clock, picks up a due secret within its tick. {@code SecretRotationSchedulerTest}
 * covers when a tick fires a rotation, on a controlled timeline with the shipped tick interval; the
 * one-second interval here only keeps this single real wait short.
 */
class SecretRotationSchedulerWiringTest {

    private static final String REGION = "us-east-1";

    @Test
    void aDueSecretIsRotatedByTheSchedulersOwnThread() {
        SecretsManagerService service = new SecretsManagerService(new InMemoryStorage<>(), 30,
                new RegionResolver(REGION, "000000000000"), mock(LambdaService.class), new ObjectMapper());
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().secretsmanager().enabled()).thenReturn(true);
        when(config.services().secretsmanager().scheduledRotationEnabled()).thenReturn(true);
        when(config.services().secretsmanager().rotationTickSeconds()).thenReturn(1L);
        SecretRotationScheduler scheduler = new SecretRotationScheduler(service, config);
        try {
            service.createSecret("wired", "v1", null, null, null, null, REGION);
            service.rotateSecret("wired", "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
                    "arn:aws:lambda:us-east-1:000000000000:function:rotate",
                    new Secret.RotationRules(null, null, "rate(4 hours)"), false, REGION);
            Instant overdue = Instant.now().minusSeconds(60);
            service.describeSecret("wired", REGION).setNextRotationDate(overdue);

            scheduler.onStart(null);

            await("the scheduler's own tick").atMost(Duration.ofSeconds(30)).pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(20))
                    .until(() -> !overdue.equals(service.describeSecret("wired", REGION).getNextRotationDate()));
            assertTrue(service.describeSecret("wired", REGION).getNextRotationDate().isAfter(Instant.now()),
                    "a fired rotation must reschedule into the future");
        } finally {
            scheduler.onStop(null);
            service.shutdown();
        }
    }
}
