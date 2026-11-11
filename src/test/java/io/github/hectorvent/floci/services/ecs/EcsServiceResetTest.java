package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A state reset wipes the ECS stores, so what ECS keeps only in memory has to go with them: a cluster
 * created after the reset under an earlier name starts with no tasks, container instances or deployments.
 */
class EcsServiceResetTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String CLUSTER = "reset-cluster";
    private static final String FAMILY = "reset-fam";
    private static final List<String> STORE_FILES = List.of("ecs-clusters.json", "ecs-task-definitions.json",
            "ecs-latest-revisions.json", "ecs-services.json", "ecs-capacity-providers.json",
            "ecs-attributes.json", "ecs-account-settings.json");

    private final List<AccountAwareStorageBackend<Object>> stores = new ArrayList<>();
    private EcsService service;

    @BeforeEach
    void setUp() {
        InMemoryStorageFactory storageFactory = new InMemoryStorageFactory(ACCOUNT);
        for (String file : STORE_FILES) {
            AccountAwareStorageBackend<Object> store = AccountAwareStorageBackend.inMemory(ACCOUNT);
            stores.add(store);
            storageFactory.withStore(file, store);
        }
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        service = new EcsService(new RegionResolver(REGION, ACCOUNT), mock(EcsContainerManager.class), config,
                mock(EcsLoadBalancerRegistrar.class), storageFactory, null);
        service.initializeStorage();
        createClusterAndTaskDefinition();
    }

    @AfterEach
    void stopReconciler() {
        service.stopManagedContainers();
    }

    @Test
    void reset_forgetsTheTasksOfAClusterItWiped() {
        EcsTask task = service.runTask(CLUSTER, FAMILY, 1, LaunchType.FARGATE, null, null,
                List.of(), null, REGION).getFirst();
        assertEquals(List.of(task.getTaskArn()), service.listTasks(CLUSTER, null, null, null, REGION));

        reset();
        createClusterAndTaskDefinition();

        assertEquals(List.of(), service.listTasks(CLUSTER, null, null, null, REGION));
    }

    @Test
    void reset_forgetsTheContainerInstancesOfAClusterItWiped() {
        service.registerContainerInstance(CLUSTER, null, List.of(), REGION);
        assertEquals(1, service.listContainerInstances(CLUSTER, null, REGION).size());

        reset();
        createClusterAndTaskDefinition();

        assertEquals(List.of(), service.listContainerInstances(CLUSTER, null, REGION));
    }

    @Test
    void reset_forgetsTheDeploymentsOfAServiceItWiped() {
        service.createService(CLUSTER, "reset-svc", FAMILY, 1, LaunchType.FARGATE, List.of(), null, REGION);
        assertEquals(1, service.listServiceDeployments("reset-svc", CLUSTER, null, REGION).size());

        reset();
        createClusterAndTaskDefinition();
        service.createService(CLUSTER, "reset-svc", FAMILY, 1, LaunchType.FARGATE, List.of(), null, REGION);

        assertEquals(1, service.listServiceDeployments("reset-svc", CLUSTER, null, REGION).size());
    }

    /** What the reset endpoint does to ECS, in its order: teardown, storage wipe, clear, restart. */
    private void reset() {
        service.stopManagedContainers();
        stores.forEach(AccountAwareStorageBackend::clear);
        service.clear();
        service.afterReset();
    }

    private void createClusterAndTaskDefinition() {
        service.createCluster(CLUSTER, REGION);
        ContainerDefinition container = new ContainerDefinition();
        container.setName("app");
        container.setImage("app:1");
        container.setEssential(true);
        service.registerTaskDefinition(FAMILY, List.of(container), null, null, null, null, null,
                List.of(), REGION);
    }
}
