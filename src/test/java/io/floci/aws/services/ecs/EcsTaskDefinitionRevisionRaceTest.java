package io.floci.aws.services.ecs;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.TaskDefinition;
import io.floci.aws.testing.InMemoryStorageFactory;
import io.floci.aws.testing.ParkingStore;
import io.floci.aws.testing.ThreadStates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Two registrations of one family that overlap each get a revision of their own. */
class EcsTaskDefinitionRevisionRaceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String FAMILY = "race-fam";

    private final ParkingStore<Integer> latestRevisions = new ParkingStore<>();
    private final ConcurrentLinkedQueue<Object> outcomes = new ConcurrentLinkedQueue<>();
    private EcsService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        service = new EcsService(new RegionResolver(REGION, ACCOUNT), mock(EcsContainerManager.class), config,
                mock(EcsLoadBalancerRegistrar.class),
                new InMemoryStorageFactory(ACCOUNT).withStore("ecs-latest-revisions.json", latestRevisions), null);
        service.initializeStorage();
    }

    @AfterEach
    void stopReconciler() {
        latestRevisions.release();
        service.stopManagedContainers();
    }

    @Test
    void registerTaskDefinition_whileAnotherRegistrationOfTheFamilyIsBetweenReadAndWrite_takesTheNextRevision()
            throws InterruptedException {
        assertEquals(1, register().getRevision());
        Thread first = new Thread(this::registerAndRecord, "first-registration");
        Thread second = new Thread(this::registerAndRecord, "second-registration");
        // Holds the first registration after it has read revision 1 and before it writes revision 2.
        latestRevisions.parkNextRead();
        first.start();
        assertTrue(latestRevisions.awaitParked(), "the first registration read the latest revision");

        second.start();
        ThreadStates.awaitBlockedOrDone(second);
        latestRevisions.release();
        first.join();
        second.join();

        assertEquals(Set.of(2, 3), Set.copyOf(outcomes));
        assertEquals(List.of(1, 2, 3), service.listTaskDefinitions(REGION, FAMILY, null, null, null, null).arns().stream()
                .map(arn -> Integer.parseInt(arn.substring(arn.lastIndexOf(':') + 1))).sorted().toList());
    }

    private void registerAndRecord() {
        try {
            outcomes.add(register().getRevision());
        } catch (RuntimeException e) {
            outcomes.add(e);
        }
    }

    private TaskDefinition register() {
        ContainerDefinition container = new ContainerDefinition();
        container.setName("app");
        container.setImage("app:1");
        container.setEssential(true);
        return service.registerTaskDefinition(FAMILY, List.of(container), null, null, null, null, null,
                List.of(), REGION);
    }
}
