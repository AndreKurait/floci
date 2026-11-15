package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.RequestScopes;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Best-effort, bounded background delivery through the existing S3 object store. */
@ApplicationScoped
public class S3AccessLogDelivery {

    private static final Logger LOG = Logger.getLogger(S3AccessLogDelivery.class);
    private final S3Service s3;
    private final Clock clock;
    private final ThreadPoolExecutor worker;

    @Inject
    public S3AccessLogDelivery(S3Service s3, Clock clock) {
        this(s3, clock, 256);
    }

    S3AccessLogDelivery(S3Service s3, Clock clock, int queueCapacity) {
        this.s3 = s3;
        this.clock = clock;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), task -> {
                    Thread thread = new Thread(task, "s3-access-log-delivery");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    void submit(S3Service.AccessLogTarget target, S3AccessLogRecord record) {
        byte[] bytes = record.bytes(target.account(), target.sourceName());
        if (bytes == null) {
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    String key = target.configuration().objectKey(target.account(), target.region(),
                            target.sourceName(), record.received(), clock.instant(),
                            UUID.randomUUID().toString().replace("-", ""));
                    RequestScopes.runAs(target.account(), target.region(),
                            () -> s3.deliverAccessLog(target, key, bytes));
                } catch (RuntimeException failure) {
                    // Do not include the request, policy, credential or exception message.
                    LOG.debug("S3 access-log delivery failed");
                }
            });
        } catch (RejectedExecutionException fullOrClosed) {
            LOG.debug("S3 access-log delivery queue is full or closed");
        }
    }

    @PreDestroy
    void close() {
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) {
                LOG.debug("S3 access-log worker has not finished shutdown");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
