package io.github.hectorvent.floci.services.cloudfront;

import java.time.Instant;
import java.util.List;

record CloudFrontFunctionSnapshot(
        String accountId, String partition, String arn, String name, String stage, String etag,
        Instant createdTime, Instant lastModifiedTime, String status, String runtime,
        String comment, String code, List<String> keyValueStoreArns) {

    CloudFrontFunctionSnapshot {
        keyValueStoreArns = List.copyOf(keyValueStoreArns);
    }
}
