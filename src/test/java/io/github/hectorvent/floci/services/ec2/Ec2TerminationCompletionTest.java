package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import io.github.hectorvent.floci.testing.Ec2ServiceFixtures;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Ec2TerminationCompletionTest {
    private static final String REGION = "us-east-1";

    @Test
    void mockCompletionReportsSuccessfulTransitionOnce() {
        Ec2Service service = Ec2ServiceFixtures.mockModeService();
        Instance instance = launch(service);
        List<Instance> completed = new ArrayList<>();

        service.terminateInstancesWithCompletion(REGION, List.of(instance.getInstanceId()), completed::add);
        service.terminateInstancesWithCompletion(REGION, List.of(instance.getInstanceId()), completed::add);

        assertEquals(List.of(instance), completed);
        assertEquals("terminated", completed.getFirst().getState().getName());
    }

    @Test
    void rejectedBatchDoesNotPublishACompletion() {
        Ec2Service service = Ec2ServiceFixtures.mockModeService();
        Instance instance = launch(service);
        List<Instance> completed = new ArrayList<>();

        AwsException failure = assertThrows(AwsException.class, () ->
                service.terminateInstancesWithCompletion(REGION,
                        List.of(instance.getInstanceId(), "i-00000000000000000"), completed::add));

        assertEquals("InvalidInstanceID.NotFound", failure.getErrorCode());
        assertTrue(completed.isEmpty());
    }

    @Test
    void modelTerminationAloneDoesNotProveNativeTeardown() {
        NativeFixture fixture = new NativeFixture();
        Instance instance = launch(fixture.service);
        fixture.nativeMode();
        List<Instance> completed = new ArrayList<>();
        AtomicReference<Runnable> afterTeardown = new AtomicReference<>();
        doAnswer(call -> {
            instance.setState(InstanceState.shuttingDown());
            afterTeardown.set(call.getArgument(1));
            return null;
        }).when(fixture.containers).terminate(eq(instance), any(Runnable.class));

        fixture.service.terminateInstancesWithCompletion(REGION, List.of(instance.getInstanceId()), completed::add);
        assertTrue(completed.isEmpty());
        instance.setState(InstanceState.terminated());
        assertTrue(completed.isEmpty(), "A model state is not the native proven-gone callback");
        afterTeardown.get().run();

        assertEquals(List.of(instance), completed);
    }

    @Test
    void teardownCallbackBeforeRejectedRequestDoesNotPublish() {
        NativeFixture fixture = new NativeFixture();
        Instance instance = launch(fixture.service);
        fixture.nativeMode();
        List<Instance> completed = new ArrayList<>();
        doAnswer(call -> {
            instance.setState(InstanceState.terminated());
            Runnable callback = call.getArgument(1);
            callback.run();
            throw new IllegalStateException("termination request did not finish");
        }).when(fixture.containers).terminate(eq(instance), any(Runnable.class));

        assertThrows(IllegalStateException.class, () -> fixture.service.terminateInstancesWithCompletion(
                REGION, List.of(instance.getInstanceId()), completed::add));
        assertTrue(completed.isEmpty());
    }

    @Test
    void failedNativeTeardownDoesNotPublish() {
        NativeFixture fixture = new NativeFixture();
        Instance instance = launch(fixture.service);
        fixture.nativeMode();
        List<Instance> completed = new ArrayList<>();
        doThrow(new IllegalStateException("native termination rejected")).when(fixture.containers)
                .terminate(eq(instance), any(Runnable.class));

        assertThrows(IllegalStateException.class, () -> fixture.service.terminateInstancesWithCompletion(
                REGION, List.of(instance.getInstanceId()), completed::add));
        assertTrue(completed.isEmpty());
    }

    @Test
    void observerFailureDoesNotReverseSuccessfulTermination() {
        Ec2Service service = Ec2ServiceFixtures.mockModeService();
        Instance instance = launch(service);

        assertDoesNotThrow(() -> service.terminateInstancesWithCompletion(REGION,
                List.of(instance.getInstanceId()), ignored -> {
                    throw new IllegalStateException("event bus unavailable");
                }));

        assertEquals("terminated", instance.getState().getName());
    }

    private static Instance launch(Ec2Service service) {
        return service.runInstances(REGION, "ami-test", "t3.micro", 1, 1, null, List.of(), null,
                null, List.of(), null, null).getInstances().getFirst();
    }

    private static final class NativeFixture {
        private final EmulatorConfig.Ec2ServiceConfig ec2 = mock(EmulatorConfig.Ec2ServiceConfig.class);
        private final Ec2ContainerManager containers = mock(Ec2ContainerManager.class);
        private final Ec2Service service;

        private NativeFixture() {
            EmulatorConfig config = mock(EmulatorConfig.class);
            EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
            when(config.defaultAccountId()).thenReturn(Ec2ServiceFixtures.ACCOUNT);
            when(config.services()).thenReturn(services);
            when(services.ec2()).thenReturn(ec2);
            when(ec2.mock()).thenReturn(true);
            service = new Ec2Service(config, containers, mock(Ec2PortForwardManager.class),
                    mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                    new InMemoryStorageFactory(Ec2ServiceFixtures.ACCOUNT));
        }

        private void nativeMode() {
            when(ec2.mock()).thenReturn(false);
        }
    }
}
