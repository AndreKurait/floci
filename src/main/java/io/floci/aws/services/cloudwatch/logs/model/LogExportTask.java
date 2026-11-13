package io.floci.aws.services.cloudwatch.logs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** An immutable task revision. Owner fields are persisted but never projected into the public response. */
@RegisterForReflection
public record LogExportTask(String accountId, String region, String taskId, String taskName,
                            String logGroupName, long from, long to, String destination,
                            String destinationPrefix, long creationTime, Long completionTime,
                            String status, String message) {

    public boolean terminal() {
        return "COMPLETED".equals(status) || "CANCELLED".equals(status) || "FAILED".equals(status);
    }

    public LogExportTask transition(String next, String detail, long now) {
        Long completed = "COMPLETED".equals(next) || "CANCELLED".equals(next) || "FAILED".equals(next)
                ? Math.max(creationTime, now) : null;
        return new LogExportTask(accountId, region, taskId, taskName, logGroupName, from, to,
                destination, destinationPrefix, creationTime, completed, next, detail);
    }
}
