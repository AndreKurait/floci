package io.floci.aws.services.sqs;

import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.sqs.model.Message;
import io.floci.aws.services.sqs.model.Queue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqsRedrivePolicyCacheTest {

    private static final String REGION = "eu-west-1";
    private final SqsService service = new SqsService(new InMemoryStorage<>(), 30, 1048576,
            "http://localhost:4566");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedPolicyChangeSurvivesAnOlderReceive(boolean recreate) throws Exception {
        Queue oldDlq = service.createQueue("old-dlq", null, REGION);
        Queue newDlq = service.createQueue("new-dlq", null, REGION);
        Queue source = service.createQueue("source", Map.of("RedrivePolicy", policy(oldDlq)), REGION);
        Map<String, String> attributes = spy(new ConcurrentHashMap<>(source.getAttributes()));
        source.setAttributes(attributes);
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean pause = new AtomicBoolean(true);
        doAnswer(invocation -> {
            String value = (String) invocation.callRealMethod();
            if (pause.compareAndSet(true, false)) {
                captured.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS), "policy reader was not released");
            }
            return value;
        }).when(attributes).get("RedrivePolicy");

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<List<Message>> receive = executor.submit(() -> receive(source));
            try {
                assertTrue(captured.await(10, TimeUnit.SECONDS), "receive did not read the old policy");
                if (recreate) {
                    service.deleteQueue(source.getQueueUrl(), REGION);
                    service.createQueue("source", Map.of("RedrivePolicy", policy(newDlq)), REGION);
                    service.sendMessage(source.getQueueUrl(), "replacement", 0, REGION);
                } else {
                    service.setQueueAttributes(source.getQueueUrl(),
                            Map.of("RedrivePolicy", policy(newDlq)), REGION);
                }
            } finally {
                release.countDown();
            }
            assertTrue(receive.get(10, TimeUnit.SECONDS).isEmpty());
        }

        if (!recreate) {
            service.sendMessage(source.getQueueUrl(), "payload", 0, REGION);
        }
        Message delivered = receive(source).getFirst();
        assertEquals(recreate ? "replacement" : "payload", delivered.getBody());
        assertEquals(1, delivered.getReceiveCount(), "old receive must not consume replacement delivery budget");
        assertTrue(receive(source).isEmpty());
        assertTrue(receive(oldDlq).isEmpty(), "later receives must not use the stale cached target");
        assertEquals(delivered.getBody(), receive(newDlq).getFirst().getBody());
    }

    @Test
    void unchangedPolicyContinuesToRedrive() {
        Queue dlq = service.createQueue("dlq", null, REGION);
        Queue source = service.createQueue("source", Map.of("RedrivePolicy", policy(dlq)), REGION);
        assertTrue(receive(source).isEmpty());
        service.sendMessage(source.getQueueUrl(), "payload", 0, REGION);
        assertEquals(1, receive(source).size());
        assertTrue(receive(source).isEmpty());
        assertEquals("payload", receive(dlq).getFirst().getBody());
    }

    @Test
    void removingPolicyStopsRedrive() {
        Queue dlq = service.createQueue("dlq", null, REGION);
        Queue source = service.createQueue("source", Map.of("RedrivePolicy", policy(dlq)), REGION);
        assertTrue(receive(source).isEmpty());
        service.setQueueAttributes(source.getQueueUrl(), Map.of("RedrivePolicy", ""), REGION);
        service.sendMessage(source.getQueueUrl(), "payload", 0, REGION);
        assertEquals(1, receive(source).size());
        assertEquals(1, receive(source).size());
        assertTrue(receive(dlq).isEmpty());
    }

    private List<Message> receive(Queue queue) {
        return service.receiveMessage(queue.getQueueUrl(), 1, 0, 0, REGION);
    }

    private String policy(Queue dlq) {
        String arn = service.getQueueAttributes(dlq.getQueueUrl(), List.of("QueueArn"), REGION).get("QueueArn");
        return "{\"deadLetterTargetArn\":\"" + arn + "\",\"maxReceiveCount\":1}";
    }
}
