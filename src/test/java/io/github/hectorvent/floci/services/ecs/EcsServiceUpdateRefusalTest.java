package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** An UpdateService the service refuses changes nothing: the next reconcile must not act on half of it. */
class EcsServiceUpdateRefusalTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String CLUSTER = "refusal-cluster";
    private static final String SERVICE = "refusal-svc";

    private EcsService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        service = new EcsService(new RegionResolver(REGION, ACCOUNT), mock(EcsContainerManager.class), config,
                mock(EcsLoadBalancerRegistrar.class), new InMemoryStorageFactory(ACCOUNT), null);
        service.initializeStorage();
        service.createCluster(CLUSTER, REGION);
        ContainerDefinition container = new ContainerDefinition();
        container.setName("app");
        container.setImage("app:1");
        container.setEssential(true);
        service.registerTaskDefinition("refusal-fam", List.of(container), null, null, null, null, null,
                List.of(), REGION);
        service.createService(CLUSTER, SERVICE, "refusal-fam", 1, LaunchType.FARGATE, List.of(), null, REGION);
    }

    @AfterEach
    void stopReconciler() {
        service.stopManagedContainers();
    }

    @Test
    void updateService_refusedForAnUnknownTaskDefinition_leavesTheDesiredCountAsItWas() {
        EcsServiceModel before = described();
        String taskDefinitionBefore = before.getTaskDefinition();
        String deploymentBefore = before.getDeploymentId();

        assertThrows(AwsException.class,
                () -> service.updateService(CLUSTER, SERVICE, "no-such-family", 5, null, REGION));

        EcsServiceModel after = described();
        assertEquals(1, after.getDesiredCount());
        assertEquals(taskDefinitionBefore, after.getTaskDefinition());
        assertEquals(deploymentBefore, after.getDeploymentId());
        service.reconcile();
        assertEquals(1, service.listTasks(CLUSTER, null, null, null, REGION).size());
    }

    @Test
    void updateService_withAKnownTaskDefinition_appliesTheDesiredCount() {
        service.updateService(CLUSTER, SERVICE, "refusal-fam", 5, null, REGION);

        assertEquals(5, described().getDesiredCount());
    }

    private EcsServiceModel described() {
        return service.describeServices(CLUSTER, List.of(SERVICE), REGION).getFirst();
    }
}
