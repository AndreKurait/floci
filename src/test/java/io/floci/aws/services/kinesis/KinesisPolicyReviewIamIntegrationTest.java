package io.floci.aws.services.kinesis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.kinesis.model.KinesisConsumer;
import io.floci.aws.services.kinesis.model.KinesisStream;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.EnforcementFixtures;
import io.floci.aws.testing.IamEnforcementProfile;
import io.floci.aws.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisClient;
import software.amazon.awssdk.services.kinesis.model.KinesisException;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.floci.aws.services.kinesis.KinesisResourcePolicyServiceTest.*;
import static io.floci.aws.testing.EnforcementFixtures.auth;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(KinesisPolicyReviewIamIntegrationTest.PersistentIamProfile.class)
class KinesisPolicyReviewIamIntegrationTest {
    @Inject
    StorageFactory factory;
    @Inject
    EmulatorConfig config;
    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    public static class PersistentIamProfile extends IamEnforcementProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("floci.storage.mode", "persistent");
            overrides.put("floci.storage.persistent-path", "./target/kinesis-review-" + UUID.randomUUID());
            return overrides;
        }
    }

    @ParameterizedTest
    @CsvSource({"false,GetResourcePolicy", "false,PutResourcePolicy", "false,DeleteResourcePolicy",
            "true,GetResourcePolicy", "true,PutResourcePolicy", "true,DeleteResourcePolicy"})
    void firstSignedRequestHonorsNondefaultLegacyTagDenyWithoutMigrating(boolean child, String action)
            throws Exception {
        assertNotEquals(ACCOUNT, config.defaultAccountId());
        AccountAwareStorageBackend<KinesisStream> streamStore = factory.create("kinesis", "kinesis-streams.json",
                new TypeReference<Map<String, KinesisStream>>() {});
        AccountAwareStorageBackend<KinesisConsumer> consumerStore = factory.create("kinesis", "kinesis-consumers.json",
                new TypeReference<Map<String, KinesisConsumer>>() {});
        StorageBackend<String, KinesisStream> rawStreams = raw(streamStore);
        StorageBackend<String, KinesisConsumer> rawConsumers = raw(consumerStore);
        String name = "review-legacy-" + UUID.randomUUID();
        String userName = "review-user-" + UUID.randomUUID();
        String parent = "arn:aws:kinesis:" + REGION + ":" + ACCOUNT + ":stream/" + name;
        String consumerArn = parent + "/consumer/child:1";
        String streamKey = REGION + "::" + name;
        String consumerKey = REGION + "::" + consumerArn;
        KinesisStream stream = new KinesisStream(name, parent);
        stream.setAccountId(ACCOUNT);
        stream.setTags(Map.of("review", "deny"));
        stream.setResourcePolicies(Map.of(parent, POLICY, consumerArn, DENY));
        KinesisConsumer consumer = new KinesisConsumer("child", consumerArn, parent);
        consumer.setPolicyStreamGeneration(stream.getResourcePolicyGeneration());
        consumer.setTags(Map.of("review", "deny"));
        rawStreams.put(streamKey, stream);
        rawConsumers.put(consumerKey, consumer);
        try {
            EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup,
                    RestAssured.port, auth(ACCOUNT, "iam"), userName);
            String target = child ? consumerArn : parent;
            String deny = """
                    {"Version":"2012-10-17","Statement":[
                    {"Effect":"Allow","Action":"kinesis:*ResourcePolicy","Resource":"*"},
                    {"Effect":"Deny","Action":"kinesis:%s","Resource":"%s",
                     "Condition":{"StringEquals":{"aws:ResourceTag/review":"deny"}}}]}
                    """.formatted(action, target);
            EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "review", deny);
            rawStreams.flush();
            rawConsumers.flush();
            Path directory = Path.of(config.storage().persistentPath());
            Path streamFile = directory.resolve("kinesis-streams.json");
            Path consumerFile = directory.resolve("kinesis-consumers.json");
            byte[] streamBytes = Files.readAllBytes(streamFile);
            byte[] consumerBytes = Files.readAllBytes(consumerFile);
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            String streamBefore = mapper.writeValueAsString(stream);
            String consumerBefore = mapper.writeValueAsString(consumer);
            try (KinesisClient user = AwsTestClients.create(KinesisClient.builder(),
                    credentials.accessKeyId(), credentials.secretAccessKey(), Region.US_EAST_1)) {
                KinesisException denied = assertThrows(KinesisException.class, () -> invoke(user, action, target));
                assertEquals(403, denied.statusCode());
                assertEquals("AccessDeniedException", denied.awsErrorDetails().errorCode());
                assertSame(stream, rawStreams.get(streamKey).orElseThrow());
                assertSame(consumer, rawConsumers.get(consumerKey).orElseThrow());
                assertTrue(streamStore.getForAccount(ACCOUNT, streamKey).isEmpty());
                assertTrue(consumerStore.getForAccount(ACCOUNT, consumerKey).isEmpty());
                assertEquals(streamBefore, mapper.writeValueAsString(stream));
                assertEquals(consumerBefore, mapper.writeValueAsString(consumer));
                rawStreams.flush();
                rawConsumers.flush();
                assertArrayEquals(streamBytes, Files.readAllBytes(streamFile));
                assertArrayEquals(consumerBytes, Files.readAllBytes(consumerFile));
                EnforcementFixtures.putUserPolicy(RestAssured.port, auth(ACCOUNT, "iam"), userName, "review",
                        "{\"Statement\":{\"Effect\":\"Allow\",\"Action\":\"kinesis:*ResourcePolicy\",\"Resource\":\"*\"}}");
                assertEquals(child ? DENY : POLICY, user.getResourcePolicy(r -> r.resourceARN(target)).policy());
                invoke(user, action, target);
                assertTrue(streamStore.getForAccount(ACCOUNT, streamKey).isPresent());
            }
        } finally {
            rawConsumers.delete(consumerKey);
            consumerStore.deleteForAccount(ACCOUNT, consumerKey);
            rawStreams.delete(streamKey);
            streamStore.deleteForAccount(ACCOUNT, streamKey);
        }
    }

    private static void invoke(KinesisClient client, String action, String target) {
        switch (action) {
            case "GetResourcePolicy" -> client.getResourcePolicy(r -> r.resourceARN(target));
            case "PutResourcePolicy" -> client.putResourcePolicy(r -> r.resourceARN(target).policy(POLICY));
            case "DeleteResourcePolicy" -> client.deleteResourcePolicy(r -> r.resourceARN(target));
            default -> throw new AssertionError(action);
        }
    }

    @SuppressWarnings("unchecked")
    private static <V> StorageBackend<String, V> raw(AccountAwareStorageBackend<V> scoped) throws Exception {
        Field delegate = AccountAwareStorageBackend.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        return (StorageBackend<String, V>) delegate.get(scoped);
    }
}
