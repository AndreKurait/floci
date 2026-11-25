package io.floci.aws.services.codedeploy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ecs.EcsService;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.ssm.SsmCommandService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeDeployGroupRefusalPersistenceTest {
    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @CsvSource({"create,false", "rename,false", "delete,false", "create,true", "rename,true", "delete,true"})
    void refusedWriteKeepsPriorDiskState(String operation, boolean afterWrite, @TempDir Path directory) {
        RefusingStorage original = storage(directory);
        try {
            CodeDeployService service = service(original);
            service.createApplication(REGION, "app", "Server", null);
            service.createDeploymentGroup(REGION, "app", "existing", null, "original-role", null,
                    List.of(Map.of("Key", "team", "Value", "original")));
            original.afterWrite = afterWrite;
            original.refuse.set(true);
            assertThrows(IllegalStateException.class, () -> {
                switch (operation) {
                    case "create" -> service.createDeploymentGroup(REGION, "app", "new", null, "new-role", null, null);
                    case "rename" -> service.updateDeploymentGroup(REGION, "app", "existing", "renamed", null,
                            "new-role", null);
                    case "delete" -> service.deleteDeploymentGroup(REGION, "app", "existing");
                    default -> throw new AssertionError(operation);
                }
            });
            assertPriorState(service);
            // Open the disk snapshot before original shutdown can flush its in-memory state.
            RefusingStorage recovered = storage(directory);
            try {
                assertPriorState(service(recovered));
            } finally {
                recovered.shutdownAll();
            }
        } finally {
            original.shutdownAll();
        }
    }

    private static void assertPriorState(CodeDeployService service) {
        assertEquals(List.of("existing"), service.listDeploymentGroups(REGION, "app"));
        assertEquals("original-role", service.getDeploymentGroup(REGION, "app", "existing").getServiceRoleArn());
        assertEquals(List.of(Map.of("Key", "team", "Value", "original")),
                service.listTagsForResource(service.deploymentGroupArn(REGION, "app", "existing")));
        assertEquals(List.of(), service.listTagsForResource(service.deploymentGroupArn(REGION, "app", "renamed")));
    }

    private static CodeDeployService service(StorageFactory storage) {
        CodeDeployService service = new CodeDeployService(mock(LambdaService.class), mock(EcsService.class),
                mock(ElbV2Service.class), mock(SsmCommandService.class), mock(Ec2Service.class), new ObjectMapper(),
                new RegionResolver(REGION, "000000000000"), storage, mock(CodeDeployAgentDeployment.class),
                CodeDeployTestRoles.allowAll());
        service.initializeStorage();
        return service;
    }

    private static RefusingStorage storage(Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.defaultAccountId()).thenReturn("000000000000");
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("codedeploy")).thenReturn("persistent");
        return new RefusingStorage(config, access);
    }

    private static final class RefusingStorage extends StorageFactory {
        private final AtomicBoolean refuse = new AtomicBoolean();
        private boolean afterWrite;

        private RefusingStorage(EmulatorConfig config, ServiceConfigAccess access) {
            super(config, access);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String service, String file, TypeReference<Map<String, V>> type) {
            AccountAwareStorageBackend<V> backend = super.create(service, file, type);
            if (!file.equals("codedeploy-deployment-groups.json")) {
                return backend;
            }
            AccountAwareStorageBackend<V> controlled = spy(backend);
            doAnswer(call -> {
                if (!refuse.compareAndSet(true, false)) {
                    return call.callRealMethod();
                }
                if (afterWrite) {
                    call.callRealMethod();
                }
                throw new IllegalStateException("injected group write refusal");
            }).when(controlled).put(eq(REGION), any());
            return controlled;
        }
    }
}
