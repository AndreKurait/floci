package io.floci.aws.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A CreateReplaceRootVolumeTask record, as DescribeReplaceRootVolumeTasks reports it. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReplaceRootVolumeTask {

    private String replaceRootVolumeTaskId;
    private String instanceId;
    private String taskState;
    private Instant startTime;
    private Instant completeTime;
    private String imageId;
    private String snapshotId;
    private Boolean deleteReplacedRootVolume;
    private String clientToken;
    /** Why a failed task failed. Not on the AWS wire shape; kept for logs and tests. */
    private String failureReason;
    private String region;
    private List<Tag> tags = new ArrayList<>();

    public ReplaceRootVolumeTask() {}

    public String getReplaceRootVolumeTaskId() { return replaceRootVolumeTaskId; }
    public void setReplaceRootVolumeTaskId(String replaceRootVolumeTaskId) {
        this.replaceRootVolumeTaskId = replaceRootVolumeTaskId;
    }

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }

    public String getTaskState() { return taskState; }
    public void setTaskState(String taskState) { this.taskState = taskState; }

    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }

    public Instant getCompleteTime() { return completeTime; }
    public void setCompleteTime(Instant completeTime) { this.completeTime = completeTime; }

    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }

    public String getSnapshotId() { return snapshotId; }
    public void setSnapshotId(String snapshotId) { this.snapshotId = snapshotId; }

    public Boolean getDeleteReplacedRootVolume() { return deleteReplacedRootVolume; }
    public void setDeleteReplacedRootVolume(Boolean deleteReplacedRootVolume) {
        this.deleteReplacedRootVolume = deleteReplacedRootVolume;
    }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags == null ? new ArrayList<>() : tags; }
}
