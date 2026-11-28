package io.floci.aws.services.codedeploy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.codedeploy.model.DeploymentGroup;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ecs.EcsService;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.ssm.SsmCommandService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeDeployGroupPublicationTest {
    private static final String REGION = "us-east-1";
    private final GroupStorage storage = new GroupStorage();
    private final CodeDeployLaunchHooks hooks = mock(CodeDeployLaunchHooks.class);
    private CodeDeployService service;

    @BeforeEach
    void setUp() {
        service = new CodeDeployService(mock(LambdaService.class), mock(EcsService.class),
                mock(ElbV2Service.class), mock(SsmCommandService.class), mock(Ec2Service.class),
                new ObjectMapper(), new RegionResolver(REGION, "000000000000"), storage,
                mock(CodeDeployAgentDeployment.class), hooks, CodeDeployTestRoles.allowAll());
        service.initializeStorage();
        service.createApplication(REGION, "app", "Server", null);
        service.createDeploymentGroup(REGION, "app", "existing", null, "original-role", null,
                List.of(Map.of("Key", "team", "Value", "original")));
        clearInvocations(hooks);
    }

    @Test
    void refusedCreateMustNotPublishGroup() {
        refuseGroupWrite();
        assertThrows(IllegalStateException.class, () -> service.createDeploymentGroup(
                REGION, "app", "new", null, "new-role", null, null));
        verify(hooks).configure(eq(REGION), isNull(), any(DeploymentGroup.class));
        assertEquals(List.of("existing"), service.listDeploymentGroups(REGION, "app"));
    }

    @Test
    void refusedRenameMustPreserveGroupAndTags() {
        String originalArn = service.deploymentGroupArn(REGION, "app", "existing");
        String renamedArn = service.deploymentGroupArn(REGION, "app", "renamed");
        refuseGroupWrite();
        assertThrows(IllegalStateException.class, () -> service.updateDeploymentGroup(
                REGION, "app", "existing", "renamed", null, "new-role", null));
        verify(hooks).configure(eq(REGION), any(DeploymentGroup.class),
                argThat(group -> "renamed".equals(group.getDeploymentGroupName())));
        assertAll(
                () -> assertEquals(List.of("existing"), service.listDeploymentGroups(REGION, "app")),
                () -> assertEquals(List.of(Map.of("Key", "team", "Value", "original")),
                        service.listTagsForResource(originalArn)),
                () -> assertEquals(List.of(), service.listTagsForResource(renamedArn)));
    }

    @Test
    void refusedDeleteMustPreserveGroup() {
        refuseGroupWrite();
        assertThrows(IllegalStateException.class, () -> service.deleteDeploymentGroup(REGION, "app", "existing"));
        verifyNoInteractions(hooks);
        assertEquals(List.of("existing"), service.listDeploymentGroups(REGION, "app"));
    }

    @Test
    void rejectedCreateHookLeavesGroupAbsent() {
        doThrow(new IllegalStateException("hook refused")).when(hooks)
                .configure(eq(REGION), isNull(), any(DeploymentGroup.class));
        assertThrows(IllegalStateException.class, () -> service.createDeploymentGroup(
                REGION, "app", "new", null, "new-role", null, null));
        assertEquals(List.of("existing"), service.listDeploymentGroups(REGION, "app"));
        assertEquals("DeploymentGroupDoesNotExistException", assertThrows(AwsException.class,
                () -> service.getDeploymentGroup(REGION, "app", "new")).getErrorCode());
    }

    @Test
    void rejectedUpdateHookPreservesPriorGroup() {
        doThrow(new IllegalStateException("hook refused")).when(hooks)
                .configure(eq(REGION), any(DeploymentGroup.class), any(DeploymentGroup.class));
        assertThrows(IllegalStateException.class, () -> service.updateDeploymentGroup(
                REGION, "app", "existing", "renamed", null, "new-role", null));
        assertEquals(List.of("existing"), service.listDeploymentGroups(REGION, "app"));
        assertEquals("original-role", service.getDeploymentGroup(REGION, "app", "existing").getServiceRoleArn());
    }

    @Test
    void successfulRenameAndDeleteRemainUsable() {
        service.updateDeploymentGroup(REGION, "app", "existing", "renamed", null, "new-role", null);
        assertEquals("new-role", service.getDeploymentGroup(REGION, "app", "renamed").getServiceRoleArn());
        service.deleteDeploymentGroup(REGION, "app", "renamed");
        assertEquals(List.of(), service.listDeploymentGroups(REGION, "app"));
        verify(hooks).delete(eq(REGION), any(DeploymentGroup.class));
    }

    @Test
    void refusedSameNameUpdatePreservesPriorFields() {
        DeploymentGroup previous = service.getDeploymentGroup(REGION, "app", "existing");
        refuseGroupWrite();
        assertThrows(IllegalStateException.class, () -> service.updateDeploymentGroup(
                REGION, "app", "existing", null, "new-config", "new-role", null));
        assertSame(previous, service.getDeploymentGroup(REGION, "app", "existing"));
        assertEquals("original-role", previous.getServiceRoleArn());
    }

    @Test
    void failedHookCompensationDoesNotHideStorageFailure() {
        refuseGroupWrite();
        IllegalStateException hookFailure = new IllegalStateException("hook cleanup refused");
        doThrow(hookFailure).when(hooks).delete(eq(REGION), any(DeploymentGroup.class));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.createDeploymentGroup(REGION, "app", "new", null, "new-role", null, null));
        assertEquals("group storage refused", failure.getMessage());
        assertTrue(List.of(failure.getSuppressed()).contains(hookFailure));
        assertEquals(List.of("existing"), service.listDeploymentGroups(REGION, "app"));
    }

    @Test
    void undeletedHookIsReportedAsCompensationFailure() {
        refuseGroupWrite();
        when(hooks.delete(eq(REGION), any(DeploymentGroup.class)))
                .thenReturn(List.of(Map.of("name", "asg", "hook", "managed")));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.createDeploymentGroup(REGION, "app", "new", null, "new-role", null, null));
        assertTrue(List.of(failure.getSuppressed()).stream()
                .anyMatch(suppressed -> suppressed.getMessage().contains("Managed launch hooks")));
    }

    @Test
    void refusedDeleteDoesNotRemoveSuccessfulRevisionOrTags() {
        DeploymentGroup group = service.getDeploymentGroup(REGION, "app", "existing");
        String historyKey = "000000000000:" + REGION + ":" + group.getDeploymentGroupId();
        CodeDeployService.SuccessfulAgentRevision revision = new CodeDeployService.SuccessfulAgentRevision(
                "d-success", Map.of("revisionType", "S3"), "DISALLOW", "CodeDeployDefault.OneAtATime");
        storage.history().put(historyKey, revision);
        refuseGroupWrite();
        assertThrows(IllegalStateException.class, () -> service.deleteDeploymentGroup(REGION, "app", "existing"));
        assertSame(revision, storage.history().get(historyKey).orElseThrow());
        assertEquals(List.of(Map.of("Key", "team", "Value", "original")),
                service.listTagsForResource(service.deploymentGroupArn(REGION, "app", "existing")));
    }

    private void refuseGroupWrite() {
        doThrow(new IllegalStateException("group storage refused"))
                .when(storage.groups()).put(eq(REGION), anyMap());
    }

    private static final class GroupStorage extends StorageFactory {
        private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();

        private GroupStorage() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String service, String file,
                                                        TypeReference<Map<String, V>> type) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(file,
                    ignored -> spy(AccountAwareStorageBackend.inMemory("000000000000")));
        }

        @SuppressWarnings("unchecked")
        private AccountAwareStorageBackend<CodeDeployService.SuccessfulAgentRevision> history() {
            return (AccountAwareStorageBackend<CodeDeployService.SuccessfulAgentRevision>)
                    stores.get("codedeploy-agent-successes.json");
        }

        @SuppressWarnings("unchecked")
        private AccountAwareStorageBackend<Map<String, Map<String, DeploymentGroup>>> groups() {
            return (AccountAwareStorageBackend<Map<String, Map<String, DeploymentGroup>>>)
                    stores.get("codedeploy-deployment-groups.json");
        }
    }
}
