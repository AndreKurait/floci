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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeDeployGroupRelatedStoreRefusalTest {
    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @CsvSource({"create,tags,put,false", "create,tags,put,true",
            "create,tags,second-put,false", "create,tags,second-put,true",
            "rename,tags,delete,false", "rename,tags,delete,true",
            "rename,tags,put,false", "rename,tags,put,true",
            "delete,tags,delete,false", "delete,tags,delete,true",
            "delete,agent-successes,delete,false", "delete,agent-successes,delete,true"})
    void refusedWriteKeepsPriorDiskState(String operation, String store, String method,
                                        boolean afterWrite, @TempDir Path directory) {
        RefusingStorage original = storage(directory);
        try {
            CodeDeployService service = service(original);
            service.createApplication(REGION, "app", "Server", null);
            service.createDeploymentGroup(REGION, "app", "existing", null, "original-role", null,
                    List.of(Map.of("Key", "team", "Value", "original")));
            String historyKey = "000000000000:" + REGION + ":"
                    + service.getDeploymentGroup(REGION, "app", "existing").getDeploymentGroupId();
            CodeDeployService.SuccessfulAgentRevision revision = new CodeDeployService.SuccessfulAgentRevision(
                    "d-success", Map.of("revisionType", "S3"), "DISALLOW", "CodeDeployDefault.OneAtATime");
            original.history().put(historyKey, revision);
            original.file = "codedeploy-" + store + ".json";
            original.method = method;
            original.afterWrite = afterWrite;
            original.refuse.set(true);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                switch (operation) {
                    case "create" -> service.createDeploymentGroup(REGION, "app", "new", null, "new-role", null,
                            List.of(Map.of("Key", "team", "Value", "new")));
                    case "rename" -> service.updateDeploymentGroup(REGION, "app", "existing", "renamed", null,
                            "new-role", null);
                    case "delete" -> service.deleteDeploymentGroup(REGION, "app", "existing");
                    default -> throw new AssertionError(operation);
                }
            });
            assertEquals("injected related-store refusal", failure.getMessage());
            assertFalse(original.refuse.get(), "The requested backing-store refusal must execute");
            assertPriorState(service);
            assertEquals(revision, original.history().get(historyKey).orElseThrow());
            // Open the disk snapshot before original shutdown can flush its in-memory state.
            RefusingStorage recovered = storage(directory);
            try {
                assertPriorState(service(recovered));
                assertEquals(revision, recovered.history().get(historyKey).orElseThrow());
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
        assertEquals(List.of(), service.listTagsForResource(service.deploymentGroupArn(REGION, "app", "new")));
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
        private String file;
        private String method;
        private int putCount;
        private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();

        private RefusingStorage(EmulatorConfig config, ServiceConfigAccess access) {
            super(config, access);
        }

        @SuppressWarnings("unchecked")
        private AccountAwareStorageBackend<CodeDeployService.SuccessfulAgentRevision> history() {
            return (AccountAwareStorageBackend<CodeDeployService.SuccessfulAgentRevision>)
                    stores.get("codedeploy-agent-successes.json");
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String service, String file, TypeReference<Map<String, V>> type) {
            AccountAwareStorageBackend<V> backend = super.create(service, file, type);
            AccountAwareStorageBackend<V> controlled = spy(backend);
            stores.put(file, controlled);
            doAnswer(call -> {
                if (!file.equals(this.file) || !refuse.get()) {
                    return call.callRealMethod();
                }
                putCount++;
                boolean matchingPut = "put".equals(method) || "second-put".equals(method) && putCount == 2;
                if (!matchingPut || !refuse.compareAndSet(true, false)) {
                    return call.callRealMethod();
                }
                if (afterWrite) {
                    call.callRealMethod();
                }
                throw new IllegalStateException("injected related-store refusal");
            }).when(controlled).put(anyString(), any());
            doAnswer(call -> {
                if (!file.equals(this.file) || !"delete".equals(method) || !refuse.compareAndSet(true, false)) {
                    return call.callRealMethod();
                }
                if (afterWrite) {
                    call.callRealMethod();
                }
                throw new IllegalStateException("injected related-store refusal");
            }).when(controlled).delete(anyString());
            return controlled;
        }
    }
}
