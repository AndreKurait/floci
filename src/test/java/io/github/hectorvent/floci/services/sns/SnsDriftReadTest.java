package io.github.hectorvent.floci.services.sns;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.sns.model.Topic;
import io.github.hectorvent.floci.services.sqs.SqsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SnsDriftReadTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "us-east-1";
    private static final String ARN = "arn:aws:sns:us-east-1:123456789012:owned";
    private static final String KEY = "topic::" + REGION + "::" + ARN;

    @Test
    void defaultLegacyReadDoesNotMigrateOrSynthesizeAndReturnsDetachedPolicyAndTags() throws Exception {
        InMemoryStorage<String, Topic> raw = new InMemoryStorage<>();
        Topic legacy = new Topic("owned", ARN);
        legacy.getAttributes().put("Policy", "{\"Statement\":[]}");
        legacy.getTags().put("Owner", "");
        raw.put(KEY, legacy);
        AccountAwareStorageBackend<Topic> scoped = new AccountAwareStorageBackend<>(raw, null, ACCOUNT);
        SnsService service = service(scoped);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        byte[] before = json.writeValueAsBytes(raw.scan(key -> true));
        SnsService.DriftTopic read = service.readTopicForDrift(ARN, REGION, ACCOUNT);
        assertFalse(read.absent());
        assertEquals("", read.tags().get("Owner"));
        assertEquals("{\"Statement\":[]}", read.attributes().get("Policy"));
        assertArrayEquals(before, json.writeValueAsBytes(raw.scan(key -> true)));
        assertEquals(Set.of(KEY), raw.keys());
        legacy.getAttributes().put("DisplayName", "later");
        legacy.getTags().put("Owner", "later");
        assertEquals("", read.attributes().get("DisplayName"));
        assertEquals("", read.tags().get("Owner"));
        assertThrows(UnsupportedOperationException.class, () -> read.tags().put("No", "write"));
    }

    @Test
    void scopedRecordWinsOverLegacyAndAbsentResourceStaysAnExplicitScopedAbsence() {
        InMemoryStorage<String, Topic> raw = new InMemoryStorage<>();
        raw.put(KEY, new Topic("owned", ARN));
        Topic current = new Topic("owned", ARN);
        current.getAttributes().put("DisplayName", "scoped");
        AccountAwareStorageBackend<Topic> scoped = new AccountAwareStorageBackend<>(raw, null, ACCOUNT);
        scoped.putForAccount(ACCOUNT, KEY, current);
        assertEquals("scoped", service(scoped).readTopicForDrift(ARN, REGION, ACCOUNT).attributes().get("DisplayName"));
        String missing = "arn:aws:sns:us-east-1:123456789012:missing";
        SnsService.DriftTopic absent = service(scoped).readTopicForDrift(missing, REGION, ACCOUNT);
        assertTrue(absent.absent());
        assertEquals(missing, absent.arn());
        assertTrue(absent.tags().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "arn", "attributeArn", "created", "attributes", "tags", "nullValue"})
    void corruptPresentRowNeverBecomesDeleted(String corruption) {
        InMemoryStorage<String, Topic> raw = new InMemoryStorage<>();
        Topic topic = new Topic("owned", ARN);
        switch (corruption) {
            case "name" -> topic.setName("foreign");
            case "arn" -> topic.setTopicArn("arn:aws:sns:us-east-1:999999999999:owned");
            case "attributeArn" -> topic.getAttributes().put("TopicArn", "foreign");
            case "created" -> topic.setCreatedAt(null);
            case "attributes" -> topic.setAttributes(null);
            case "tags" -> topic.setTags(null);
            case "nullValue" -> {
                topic.setTags(new HashMap<>());
                topic.getTags().put("bad", null);
            }
            default -> throw new IllegalArgumentException(corruption);
        }
        raw.put(KEY, topic);
        assertThrows(AwsException.class, () -> service(new AccountAwareStorageBackend<>(raw, null, ACCOUNT))
                .readTopicForDrift(ARN, REGION, ACCOUNT));
        assertEquals(Map.of(KEY, topic).keySet(), raw.keys());
    }

    @ParameterizedTest
    @ValueSource(strings = {"arn:aws:sns:us-east-1:999999999999:owned",
            "arn:aws:sns:eu-west-1:123456789012:owned", "arn:aws-cn:sns:us-east-1:123456789012:owned",
            "arn:aws:sqs:us-east-1:123456789012:owned"})
    void malformedOrForeignPhysicalIdsAreRefusedBeforeLookingForAbsence(String foreign) {
        AccountAwareStorageBackend<Topic> scoped = AccountAwareStorageBackend.inMemory(ACCOUNT);
        assertThrows(AwsException.class, () -> service(scoped).readTopicForDrift(foreign, REGION, ACCOUNT));
        assertTrue(scoped.keys().isEmpty());
    }

    private static SnsService service(AccountAwareStorageBackend<Topic> topics) {
        return new SnsService(topics, new InMemoryStorage<>(), new RegionResolver(REGION, ACCOUNT),
                mock(SqsService.class), mock(LambdaService.class));
    }
}
