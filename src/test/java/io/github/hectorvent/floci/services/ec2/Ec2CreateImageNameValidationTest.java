package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.Ec2ServiceFixtures;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A CreateImage that is refused for its name must not have rebooted the instance first. */
class Ec2CreateImageNameValidationTest {

    private static final String REGION = "us-east-1";
    private static final String INSTANCE_ID = "i-0bbbbbbbbbbbbbbbb";

    private Ec2ContainerManager containerManager;
    private Ec2Service service;

    @BeforeEach
    void setUp() {
        containerManager = mock(Ec2ContainerManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        when(config.defaultAccountId()).thenReturn(Ec2ServiceFixtures.ACCOUNT);
        when(config.services()).thenReturn(services);
        when(services.ec2()).thenReturn(ec2);
        when(ec2.mock()).thenReturn(false);
        service = new Ec2Service(config, containerManager, mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new InMemoryStorageFactory(Ec2ServiceFixtures.ACCOUNT));
        Instance running = new Instance();
        running.setInstanceId(INSTANCE_ID);
        running.setRegion(REGION);
        running.setOwnerId(Ec2ServiceFixtures.ACCOUNT);
        running.setInstanceType("t3.micro");
        running.setImageId("ami-0123456789abcdef0");
        running.setPlacement(new Placement("us-east-1a"));
        running.setDockerContainerId("container-of-the-source-instance");
        running.setState(InstanceState.running());
        service.putInstanceForTest(running);
    }

    @Test
    void createImageWithoutANameIsRefusedBeforeTheInstanceIsRebooted() {
        AwsException refused = assertThrows(AwsException.class,
                () -> service.createImage(REGION, INSTANCE_ID, null, null, false));

        assertEquals("MissingParameter", refused.getErrorCode());
        verify(containerManager, never()).reboot(any(), any());
    }

    @Test
    void createImageWithANameAlreadyInUseIsRefusedBeforeTheInstanceIsRebooted() {
        service.registerImage(REGION, "nightly", null, "x86_64", "/dev/xvda", List.of());

        AwsException refused = assertThrows(AwsException.class,
                () -> service.createImage(REGION, INSTANCE_ID, "nightly", null, false));

        assertEquals("InvalidAMIName.Duplicate", refused.getErrorCode());
        verify(containerManager, never()).reboot(any(), any());
    }
}
