package io.floci.aws.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.container.EcsTaskHandle;
import io.floci.aws.services.ecs.exec.EcsExecSessionRegistry;
import io.floci.aws.services.ecs.model.Attribute;
import io.floci.aws.services.ecs.model.ContainerDefinition;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.LaunchType;
import io.floci.aws.services.ecs.model.NetworkMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.floci.aws.testing.EcsTaskWaits.awaitLastStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The emulator's stand-in for a container instance's EC2 instance profile: real ECS reads it from
 * the EC2 instance the agent runs on; Floci takes it from the custom container-instance attribute
 * {@code floci.iam-instance-profile}, an instance-profile ARN in the registering account, and
 * hands it to every EC2 task placed on that instance.
 */
class EcsServiceInstanceProfileTest {

    private final List<EcsService> services = new ArrayList<>();

    @AfterEach
    void shutdownServices() {
        services.forEach(EcsService::shutdown);
    }

    private static final String REGION = "eu-west-1";
    private static final String ACCOUNT = "000000000000";
    private static final String PROFILE = "arn:aws:iam::" + ACCOUNT + ":instance-profile/app/app-host";
    private static final String DOCUMENT = "{\"instanceId\":\"i-0abc000000000001\","
            + "\"availabilityZone\":\"eu-west-1a\",\"instanceType\":\"m5.large\"}";

    @Test
    void ec2TaskOnAnInstanceWithAProfileCarriesIt() throws Exception {
        EcsContainerManager manager = manager();
        EcsService service = service(manager);
        service.createCluster("app-cluster", REGION);
        registerTaskDefinition(service);
        service.registerContainerInstance("app-cluster", DOCUMENT,
                List.of(new Attribute(EcsService.IAM_INSTANCE_PROFILE_ATTRIBUTE, PROFILE, null, null)), REGION);

        EcsTask launched = service.runTask("app-cluster", "app", 1, LaunchType.EC2,
                null, null, null, null, REGION).getFirst();
        awaitLastStatus(() -> service.describeTasks("app-cluster", List.of(launched.getTaskArn()), REGION).getFirst(),
                EcsTask::getLastStatus, Duration.ofSeconds(5), "RUNNING");

        ArgumentCaptor<EcsTask> task = ArgumentCaptor.forClass(EcsTask.class);
        verify(manager).startTask(task.capture(), any(), any(), anyString());
        assertEquals("i-0abc000000000001", task.getValue().getEc2InstanceId());
        assertEquals(PROFILE, task.getValue().getIamInstanceProfileArn());
    }

    @Test
    void ec2TaskOnAnInstanceWithoutAProfileHasNone() throws Exception {
        EcsContainerManager manager = manager();
        EcsService service = service(manager);
        service.createCluster("app-cluster", REGION);
        registerTaskDefinition(service);
        service.registerContainerInstance("app-cluster", DOCUMENT, null, REGION);

        EcsTask launched = service.runTask("app-cluster", "app", 1, LaunchType.EC2,
                null, null, null, null, REGION).getFirst();
        awaitLastStatus(() -> service.describeTasks("app-cluster", List.of(launched.getTaskArn()), REGION).getFirst(),
                EcsTask::getLastStatus, Duration.ofSeconds(5), "RUNNING");

        ArgumentCaptor<EcsTask> task = ArgumentCaptor.forClass(EcsTask.class);
        verify(manager).startTask(task.capture(), any(), any(), anyString());
        assertNull(task.getValue().getIamInstanceProfileArn());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:iam::111122223333:instance-profile/app-host",
            "arn:aws:iam::000000000000:role/app-host",
            "app-cluster",
            ""})
    void registrationRefusesAProfileThatIsNotAnInstanceProfileArnOfTheCallingAccount(String value) {
        EcsService service = service(manager());
        service.createCluster("app-cluster", REGION);

        AwsException failure = assertThrows(AwsException.class, () -> service.registerContainerInstance("app-cluster",
                DOCUMENT, List.of(new Attribute(EcsService.IAM_INSTANCE_PROFILE_ATTRIBUTE, value, null, null)),
                REGION));

        assertEquals("InvalidParameterException", failure.getErrorCode());
        assertTrue(failure.getMessage().contains(EcsService.IAM_INSTANCE_PROFILE_ATTRIBUTE), failure.getMessage());
    }

    private static void registerTaskDefinition(EcsService service) {
        ContainerDefinition definition = new ContainerDefinition();
        definition.setName("app");
        definition.setImage("busybox:stable");
        service.registerTaskDefinition("app", List.of(definition), NetworkMode.bridge, null, null,
                null, null, List.of(), REGION);
    }

    private static EcsContainerManager manager() {
        EcsContainerManager manager = mock(EcsContainerManager.class);
        when(manager.getExitCodeIfStopped(anyString())).thenReturn(null);
        when(manager.startTask(any(), any(), any(), anyString())).thenAnswer(invocation -> {
            EcsTask task = invocation.getArgument(0);
            return new EcsTaskHandle(task.getTaskArn(), Map.of("app", "docker-id"), Map.of());
        });
        return manager;
    }

    private EcsService service(EcsContainerManager manager) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(false);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        EcsService service = new EcsService(new RegionResolver(REGION, ACCOUNT), manager,
                config, mock(EcsLoadBalancerRegistrar.class), new InMemoryStorage(), null,
                new EcsExecSessionRegistry(), null);
        service.initializeStorage();
        services.add(service);
        return service;
    }

    private static final class InMemoryStorage extends StorageFactory {
        private InMemoryStorage() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory(ACCOUNT);
        }
    }
}
