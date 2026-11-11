package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.Ec2ServiceFixtures;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** StartInstances starts only what is stopped. */
class Ec2StartInstancesStateTest {

    private static final String REGION = "us-east-1";

    @Test
    void startInstancesLeavesARunningContainerInstanceRunning() {
        Ec2ContainerManager containerManager = mock(Ec2ContainerManager.class);
        Ec2Service service = containerModeService(containerManager);
        Instance running = new Instance();
        running.setInstanceId("i-0aaaaaaaaaaaaaaaa");
        running.setRegion(REGION);
        running.setOwnerId(Ec2ServiceFixtures.ACCOUNT);
        running.setInstanceType("t3.micro");
        running.setPlacement(new Placement("us-east-1a"));
        running.setDockerContainerId("container-of-the-running-instance");
        running.setState(InstanceState.running());
        service.putInstanceForTest(running);

        Map<String, String> change = service.startInstances(REGION, List.of(running.getInstanceId())).getFirst();

        verify(containerManager, never()).start(any(), any(), any());
        assertEquals("running", running.getState().getName());
        assertEquals("running", change.get("previousState"));
        assertEquals("running", change.get("currentState"));
        assertEquals("16", change.get("currentCode"));
    }

    private static Ec2Service containerModeService(Ec2ContainerManager containerManager) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn(Ec2ServiceFixtures.ACCOUNT);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(false);
        return new Ec2Service(config, containerManager, mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Ec2ServiceFixtures.ACCOUNT));
    }
}
