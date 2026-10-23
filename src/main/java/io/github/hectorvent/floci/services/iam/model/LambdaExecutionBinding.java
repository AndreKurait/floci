package io.github.hectorvent.floci.services.iam.model;

/** In-process launch provenance, never accepted from a request or restored from storage. */
public record LambdaExecutionBinding(String accountId, String region, String functionName, String functionArn,
                                     String version, String roleArn, String codeSha256, String revisionId,
                                     String containerId, String containerStartedAt) {
    public LambdaExecutionBinding withContainer(String id) {
        return new LambdaExecutionBinding(accountId, region, functionName, functionArn, version,
                roleArn, codeSha256, revisionId, id, null);
    }
    public LambdaExecutionBinding withStartedAt(String value) {
        return new LambdaExecutionBinding(accountId, region, functionName, functionArn, version,
                roleArn, codeSha256, revisionId, containerId, value);
    }
}
