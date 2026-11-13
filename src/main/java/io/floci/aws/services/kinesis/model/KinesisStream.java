package io.floci.aws.services.kinesis.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class KinesisStream {
    private String streamName;
    private String streamArn;
    private String accountId;
    private String streamStatus;
    private String resourcePolicyGeneration;
    private Map<String, String> resourcePolicies = Map.of();
    private volatile List<KinesisShard> shards = new CopyOnWriteArrayList<>();
    private int retentionPeriodHours = 24;
    private Instant streamCreationTimestamp;
    private Map<String, String> tags = new HashMap<>();
    private String encryptionType = "NONE";
    private String keyId;
    private String streamMode = "PROVISIONED";
    private Set<String> enhancedMonitoringMetrics = new HashSet<>();
    // AWS's default; streams persisted before this field existed read as the default too.
    private int maxRecordSizeInKiB = 1024;
    /**
     * UpdateShardCount call timestamps, kept to enforce the rolling 24-hour scaling-operation
     * limit. Streams persisted before this field existed read back as an empty history.
     */
    private List<Instant> shardCountUpdateTimestamps = new ArrayList<>();

    public KinesisStream() {}

    public KinesisStream(String streamName, String streamArn) {
        this.resourcePolicyGeneration = UUID.randomUUID().toString();
        this.streamName = streamName;
        this.streamArn = streamArn;
        this.streamStatus = "ACTIVE";
        this.streamCreationTimestamp = Instant.now();
    }

    public String getResourcePolicyGeneration() { return resourcePolicyGeneration; }
    public void setResourcePolicyGeneration(String generation) { this.resourcePolicyGeneration = generation; }

    public Map<String, String> getResourcePolicies() { return resourcePolicies; }
    public void setResourcePolicies(Map<String, String> policies) {
        this.resourcePolicies = policies == null ? Map.of() : Map.copyOf(policies);
    }

    /** Detached policy replacement; callers hold the stream lock shared by all metadata writers. */
    public KinesisStream withResourcePolicies(Map<String, String> policies) {
        KinesisStream copy = new KinesisStream();
        copy.streamName = streamName;
        copy.streamArn = streamArn;
        copy.accountId = accountId;
        copy.streamStatus = streamStatus;
        copy.resourcePolicyGeneration = resourcePolicyGeneration;
        copy.setResourcePolicies(policies);
        copy.shards = shards;
        copy.retentionPeriodHours = retentionPeriodHours;
        copy.streamCreationTimestamp = streamCreationTimestamp;
        copy.tags = tags;
        copy.encryptionType = encryptionType;
        copy.keyId = keyId;
        copy.streamMode = streamMode;
        copy.enhancedMonitoringMetrics = enhancedMonitoringMetrics;
        copy.maxRecordSizeInKiB = maxRecordSizeInKiB;
        copy.shardCountUpdateTimestamps = shardCountUpdateTimestamps;
        return copy;
    }

    public String getStreamName() { return streamName; }
    public void setStreamName(String streamName) { this.streamName = streamName; }

    public String getStreamArn() { return streamArn; }
    public void setStreamArn(String streamArn) { this.streamArn = streamArn; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getStreamStatus() { return streamStatus; }
    public void setStreamStatus(String streamStatus) { this.streamStatus = streamStatus; }

    public List<KinesisShard> getShards() { return shards; }
    public void setShards(List<KinesisShard> shards) {
        this.shards = shards == null ? new CopyOnWriteArrayList<>() : new CopyOnWriteArrayList<>(shards);
    }

    public int getRetentionPeriodHours() { return retentionPeriodHours; }
    public void setRetentionPeriodHours(int retentionPeriodHours) { this.retentionPeriodHours = retentionPeriodHours; }

    public Instant getStreamCreationTimestamp() { return streamCreationTimestamp; }
    public void setStreamCreationTimestamp(Instant timestamp) { this.streamCreationTimestamp = timestamp; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getEncryptionType() { return encryptionType; }
    public void setEncryptionType(String encryptionType) { this.encryptionType = encryptionType; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getStreamMode() { return streamMode; }
    public void setStreamMode(String streamMode) { this.streamMode = streamMode; }

    public int getMaxRecordSizeInKiB() { return maxRecordSizeInKiB; }
    public void setMaxRecordSizeInKiB(int maxRecordSizeInKiB) { this.maxRecordSizeInKiB = maxRecordSizeInKiB; }

    public Set<String> getEnhancedMonitoringMetrics() { return enhancedMonitoringMetrics; }
    public void setEnhancedMonitoringMetrics(Set<String> enhancedMonitoringMetrics) { this.enhancedMonitoringMetrics = enhancedMonitoringMetrics; }

    public List<Instant> getShardCountUpdateTimestamps() { return shardCountUpdateTimestamps; }
    public void setShardCountUpdateTimestamps(List<Instant> shardCountUpdateTimestamps) {
        this.shardCountUpdateTimestamps = shardCountUpdateTimestamps == null ? new ArrayList<>() : shardCountUpdateTimestamps;
    }
}
