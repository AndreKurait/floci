package io.github.hectorvent.floci.services.sns;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.sns.model.Subscription;
import io.github.hectorvent.floci.services.sns.model.Topic;
import io.github.hectorvent.floci.services.sqs.SqsService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnsConfirmationPersistenceTest {
    private static final String OWNER = "111122223333";
    private static final String RECIPIENT = "444455556666";
    private static final String REGION = "us-east-1";
    private static final String QUEUE = "arn:aws:sqs:us-west-2:" + RECIPIENT + ":inventory";

    @TempDir
    Path state;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void tokenSurvivesRestartAndConfirmationPersistsOnlyInItsOwningAccount(String mode) {
        String topic;
        String subscription;
        String token;
        try (Fixture original = new Fixture(state, mode, OWNER)) {
            topic = original.sns.createTopic("inventory", Map.of(), Map.of(), REGION).getTopicArn();
            Subscription pending = original.sns.subscribe(topic, "sqs", QUEUE, REGION, Map.of());
            subscription = pending.getSubscriptionArn();
            token = pending.getAttributes().get("ConfirmationToken");
            assertEquals("true", pending.getAttributes().get("PendingConfirmation"));
        }
        try (Fixture restarted = new Fixture(state, mode, RECIPIENT)) {
            String other = restarted.sns.createTopic("inventory", Map.of(), Map.of(), REGION).getTopicArn();
            assertThrows(AwsException.class, () -> restarted.sns.confirmSubscription(other, token, REGION));
            assertEquals(subscription, restarted.sns.confirmSubscription(topic, token, "us-west-2"));
            assertTrue(restarted.sns.listSubscriptions(REGION).isEmpty(),
                    "Confirming another account's topic must not copy its subscription into this account.");
        }
        try (Fixture reloadedOwner = new Fixture(state, mode, OWNER)) {
            Map<String, String> attributes = reloadedOwner.sns.getSubscriptionAttributes(subscription, REGION);
            assertEquals("false", attributes.get("PendingConfirmation"));
            assertFalse(attributes.containsKey("ConfirmationToken"));
            assertEquals(OWNER, attributes.get("Owner"));
            assertEquals(1, reloadedOwner.sns.listSubscriptions(REGION).size());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final StorageFactory storage;
        private final SnsService sns;

        private Fixture(Path state, String mode, String account) {
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.defaultAccountId()).thenReturn(account);
            when(config.storage().persistentPath()).thenReturn(state.toString());
            when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
            ServiceConfigAccess access = mock(ServiceConfigAccess.class);
            when(access.storageMode("sns")).thenReturn(mode);
            when(access.storageFlushInterval("sns")).thenReturn(60000L);
            storage = new StorageFactory(config, access);
            sns = new SnsService(
                    storage.create("sns", "sns-topics.json", new TypeReference<Map<String, Topic>>() { }),
                    storage.create("sns", "sns-subscriptions.json", new TypeReference<Map<String, Subscription>>() { }),
                    new RegionResolver(REGION, account), mock(SqsService.class), mock(LambdaService.class));
        }

        @Override
        public void close() {
            storage.shutdownAll();
        }
    }
}
