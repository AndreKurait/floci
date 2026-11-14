package io.floci.aws.services.cloudwatch.logs;

import io.floci.aws.services.cloudwatch.logs.model.LogEvent;
import io.floci.aws.services.cloudwatch.logs.model.SubscriptionFilter;

import java.util.List;

/**
 * Fired by {@link CloudWatchLogsService} once a PutLogEvents batch is stored, with the events as
 * stored, so the features AWS drives from ingestion (metric filters today) run without the log
 * service knowing about them. {@code accountId} is the account the batch was written for when the
 * writer named one, as a container streaming logs outside any request does, and null when the
 * batch belongs to the caller's own account. Subscription filters are detached snapshots selected
 * with ingestion under the metadata lock, so delayed observers cannot select a recreated group's filters.
 */
public record LogEventsIngested(String accountId, String region, String logGroupName, String logStreamName,
                                List<LogEvent> events, List<SubscriptionFilter> subscriptionFilters) {
    public LogEventsIngested(String accountId, String region, String logGroupName, String logStreamName,
                             List<LogEvent> events) {
        this(accountId, region, logGroupName, logStreamName, events, List.of());
    }
}
