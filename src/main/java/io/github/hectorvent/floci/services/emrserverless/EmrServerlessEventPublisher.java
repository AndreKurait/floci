package io.github.hectorvent.floci.services.emrserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.emrserverless.model.JobRun;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "EMR Serverless Job Run State Change" events AWS sends to the default EventBridge bus of a job run's
 * account and Region at "every state change of a job run": the detail of the EMR Serverless User Guide's
 * example, without {@code previousState} for a run just submitted. An event is written as its change is
 * made and sent from a thread of its own, in the order the changes were made, so a rule's target that
 * calls EMR Serverless never waits on the call that made the change.
 */
@ApplicationScoped
public class EmrServerlessEventPublisher {

    static final String SOURCE = "aws.emr-serverless";
    static final String JOB_RUN_STATE_CHANGE = "EMR Serverless Job Run State Change";
    // The guide's example writes its times to the microsecond: "2022-05-31T21:07:42.299487Z".
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private final EventBridgeService eventBridgeService;
    private final ObjectMapper objectMapper;
    private final Executor deliveries;

    @Inject
    public EmrServerlessEventPublisher(EventBridgeService eventBridgeService, ObjectMapper objectMapper) {
        this(eventBridgeService, objectMapper, Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("emr-serverless-events").factory()));
    }

    EmrServerlessEventPublisher(EventBridgeService eventBridgeService, ObjectMapper objectMapper,
                                Executor deliveries) {
        this.eventBridgeService = eventBridgeService;
        this.objectMapper = objectMapper;
        this.deliveries = deliveries;
    }

    @PreDestroy
    void shutdown() {
        if (deliveries instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
    }

    /** {@code run} has entered its state from {@code previousState}, null for a run just submitted. */
    void jobRunStateChange(JobRun run, String previousState) {
        ObjectNode detail = objectMapper.createObjectNode()
                .put("jobRunId", run.getJobRunId())
                .put("applicationId", run.getApplicationId())
                .put("arn", run.getArn())
                .put("releaseLabel", run.getReleaseLabel())
                .put("state", run.getState());
        if (previousState != null) {
            detail.put("previousState", previousState);
        }
        detail.put("createdBy", run.getCreatedBy())
                .put("updatedAt", TIME.format(run.getUpdatedAt()))
                .put("createdAt", TIME.format(run.getCreatedAt()));
        String event = detail.toString();
        AwsArnUtils.Arn arn = AwsArnUtils.parse(run.getArn());
        deliveries.execute(() -> RequestScopes.runAs(arn.accountId(), arn.region(), () ->
                eventBridgeService.publishServiceEvent(SOURCE, JOB_RUN_STATE_CHANGE, event, null, arn.region())));
    }
}
