package io.floci.aws.services.cloudwatch.insights;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.cloudwatch.logs.LogEventsIngested;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/** Consumes the same committed Logs event as metric filters, using the original log message. */
@ApplicationScoped
public class CloudWatchInsightRuleIngestion {
    private static final Logger LOG = Logger.getLogger(CloudWatchInsightRuleIngestion.class);
    private final CloudWatchInsightRulesService service;
    private final boolean enabled;

    public CloudWatchInsightRuleIngestion(CloudWatchInsightRulesService service, EmulatorConfig config) {
        this.service = service;
        this.enabled = config.services().cloudwatchlogs().enabled() && config.services().cloudwatchmetrics().enabled();
    }

    void onEvents(@Observes LogEventsIngested event) {
        if (enabled) {
            try {
                service.ingest(event);
            } catch (RuntimeException error) {
                // Logs committed before this observer runs; a failed count commit cannot undo ingestion.
                LOG.errorv(error, "Cannot commit insight contributions: region={0}, group={1}",
                        event.region(), event.logGroupName());
            }
        }
    }
}
