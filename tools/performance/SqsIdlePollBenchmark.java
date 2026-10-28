package io.github.hectorvent.floci.services.sqs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process workload: completed empty SQS long polls and authoritative queue-store reads.
 * Run against each build's packaged application classpath. No network or Docker resources.
 */
public final class SqsIdlePollBenchmark {
    public static void main(String[] args) throws Exception {
        String label = args[0];
        String revision = args[1];
        int consumers = args.length > 2 ? Integer.parseInt(args[2]) : 100;
        int seconds = args.length > 3 ? Integer.parseInt(args[3]) : 20;
        int runs = args.length > 4 ? Integer.parseInt(args[4]) : 3;
        if (consumers < 1 || consumers > 1000 || seconds < 1 || seconds > 20 || runs < 1 || runs > 10) {
            throw new IllegalArgumentException("Use 1..1000 consumers, 1..20 seconds and 1..10 runs");
        }
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int run = 0; run < runs; run++) {
            AtomicLong reads = new AtomicLong();
            InMemoryStorage<String, Queue> store = new InMemoryStorage<>() {
                @Override
                public Optional<Queue> get(String key) {
                    reads.incrementAndGet();
                    return super.get(key);
                }
            };
            SqsService service = new SqsService(store, 30, 1048576, "http://localhost:4566");
            List<Queue> queues = new ArrayList<>();
            for (int i = 0; i < consumers; i++) {
                queues.add(service.createQueue("idle-benchmark-" + i, null, "us-east-1"));
            }
            service.receiveMessage(queues.getFirst().getQueueUrl(), 1, 30, 0, "us-east-1");
            reads.set(0);
            CountDownLatch ready = new CountDownLatch(consumers);
            CountDownLatch start = new CountDownLatch(1);
            long started;
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<List<Message>>> calls = new ArrayList<>();
                for (Queue queue : queues) {
                    calls.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return service.receiveMessage(queue.getQueueUrl(), 1, 30, seconds, "us-east-1");
                    }));
                }
                try {
                    if (!ready.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Consumers did not start");
                    }
                    started = System.nanoTime();
                } finally {
                    start.countDown();
                }
                for (Future<List<Message>> call : calls) {
                    if (!call.get(seconds + 10L, TimeUnit.SECONDS).isEmpty()) {
                        throw new IllegalStateException("Empty queue returned messages");
                    }
                }
            } finally {
                service.stop();
            }
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("completed_empty_receives", consumers);
            sample.put("elapsed_seconds", (System.nanoTime() - started) / 1_000_000_000.0);
            sample.put("queue_store_reads", reads.get());
            sample.put("reads_per_receive", reads.get() / (double) consumers);
            samples.add(sample);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label);
        result.put("revision", revision);
        result.put("java", Runtime.version().toString());
        result.put("active_processors", Runtime.getRuntime().availableProcessors());
        result.put("consumers", consumers);
        result.put("wait_seconds", seconds);
        result.put("samples", samples);
        System.out.println(new ObjectMapper().writeValueAsString(result));
    }
}
