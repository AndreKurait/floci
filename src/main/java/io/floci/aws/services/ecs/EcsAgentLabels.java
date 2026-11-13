package io.floci.aws.services.ecs;

import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.TaskDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The Docker labels the ECS agent puts on every task container it creates
 * ({@code agent/engine/docker_task_engine.go}, {@code createContainer}): the task ARN, the container
 * name, the task definition family and revision, and the cluster. The agent sets them after the
 * container definition's {@code dockerLabels}, so they override a duplicate key there. The task
 * metadata endpoint reports a container's Docker labels, so it reports these too.
 *
 * <p>The agent's cluster label is its configured {@code ECS_CLUSTER}, which may be a cluster name or
 * ARN; Floci has no agent configuration and gives the cluster ARN.
 */
public final class EcsAgentLabels {

    public static final String PREFIX = "com.amazonaws.ecs.";
    public static final String TASK_ARN = PREFIX + "task-arn";
    public static final String CONTAINER_NAME = PREFIX + "container-name";
    public static final String TASK_DEFINITION_FAMILY = PREFIX + "task-definition-family";
    public static final String TASK_DEFINITION_VERSION = PREFIX + "task-definition-version";
    public static final String CLUSTER = PREFIX + "cluster";

    private EcsAgentLabels() {
    }

    /**
     * The labels for container {@code containerName} of {@code task}, as the task metadata endpoint
     * reports them. Without its task definition (a task restored without one) the family and version
     * are left out. A task, cluster or container value Floci does not know is kept as a null value,
     * which the endpoint reports as null; {@link #forDocker} drops it.
     */
    public static Map<String, String> of(EcsTask task, TaskDefinition taskDef, String containerName) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(CLUSTER, task.getClusterArn());
        labels.put(CONTAINER_NAME, containerName);
        labels.put(TASK_ARN, task.getTaskArn());
        if (taskDef != null) {
            labels.put(TASK_DEFINITION_FAMILY, taskDef.getFamily());
            labels.put(TASK_DEFINITION_VERSION, String.valueOf(taskDef.getRevision()));
        }
        return labels;
    }

    /** {@link #of} without null values, which a Docker label cannot carry. */
    public static Map<String, String> forDocker(EcsTask task, TaskDefinition taskDef, String containerName) {
        Map<String, String> labels = of(task, taskDef, containerName);
        labels.values().removeIf(Objects::isNull);
        return labels;
    }
}
