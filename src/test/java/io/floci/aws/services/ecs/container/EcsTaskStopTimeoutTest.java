package io.floci.aws.services.ecs.container;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A container definition without stopTimeout gets the ECS default grace between SIGTERM and
 * SIGKILL: 30 seconds (the ECS agent's ECS_CONTAINER_STOP_TIMEOUT default, and Fargate's default).
 * An application that drains on SIGTERM, such as releasing its work before exiting, needs it.
 */
class EcsTaskStopTimeoutTest {

    @Test
    void aContainerWithoutStopTimeoutGetsTheEcsDefaultThirtySeconds() {
        EcsTaskHandle handle = new EcsTaskHandle("arn:aws:ecs:us-east-1:000000000000:task/c/t",
                Map.of("app", "docker-id"), Map.of());
        assertEquals(30, handle.stopTimeoutFor("app"));
    }
}
