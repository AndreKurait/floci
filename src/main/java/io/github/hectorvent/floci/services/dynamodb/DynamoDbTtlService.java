package io.github.hectorvent.floci.services.dynamodb;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class DynamoDbTtlService {

    private static final Logger LOG = Logger.getLogger(DynamoDbTtlService.class);
    static final long SWEEP_INTERVAL_SECONDS = 60;

    private final DynamoDbService dynamoDbService;
    private final ScheduledExecutorService scheduler;
    private boolean paused;

    @Inject
    public DynamoDbTtlService(DynamoDbService dynamoDbService) {
        this(dynamoDbService, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dynamodb-ttl-sweeper");
            t.setDaemon(true);
            return t;
        }));
    }

    DynamoDbTtlService(DynamoDbService dynamoDbService, ScheduledExecutorService scheduler) {
        this.dynamoDbService = dynamoDbService;
        this.scheduler = scheduler;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::sweep, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOG.infov("DynamoDB TTL sweeper scheduled (60s interval)");
    }

    synchronized void sweep() {
        if (paused) {
            return;
        }
        try {
            dynamoDbService.deleteExpiredItems();
        } catch (RuntimeException e) {
            LOG.warnv(e, "DynamoDB TTL sweep failed; retrying on the next run");
        }
    }

    /** Skips sweeps until {@link #resume()}, first waiting for a sweep already running to finish. */
    public synchronized void pause() {
        paused = true;
    }

    public synchronized void resume() {
        paused = false;
    }

    /** Stops sweeping for good, first waiting for a sweep already running to finish. */
    @PreDestroy
    public void stop() {
        pause();
        scheduler.shutdownNow();
    }
}
