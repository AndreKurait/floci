package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodeDeployCfnProvisionerTest {
    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodeDeployService service = mock(CodeDeployService.class);
    private final CodeDeployCfnProvisioner provisioner = new CodeDeployCfnProvisioner(service);

    @Test
    void replacementRollbackRetainsApplicationIdentityThroughFailedCleanupAndRetry() throws Exception {
        when(service.deploymentGroupArn(anyString(), anyString(), anyString()))
                .thenAnswer(call -> "arn:aws:codedeploy:us-east-1:000000000000:deploymentgroup:"
                        + call.getArgument(1) + "/" + call.getArgument(2));
        when(service.listTagsForResource(anyString())).thenReturn(List.of());
        StackResource resource = resource();
        provisioner.provision(resource, group("old-application", "old-group"), context(null));
        provisioner.provision(resource, group("new-application", "new-group"), context("old-group"));
        doThrow(new IllegalStateException("delete failed")).when(service)
                .deleteDeploymentGroup(REGION, "new-application", "new-group");

        assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource));
        assertEquals("old-group", resource.getPhysicalId());
        assertEquals("old-application", resource.getAttributes()
                .get(CfnRollback.USES_ATTR_PREFIX + "CodeDeployApplication"));
        verify(service, never()).deleteDeploymentGroup(REGION, "old-application", "old-group");

        doNothing().when(service).deleteDeploymentGroup(REGION, "new-application", "new-group");
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(service, times(2)).deleteDeploymentGroup(REGION, "new-application", "new-group");
        provisioner.clearUpdate(resource);
        assertEquals("{\"old-group\":\"old-application\"}", resource.getAttributes()
                .get(CfnRollback.CODEDEPLOY_GROUP_APPLICATIONS_ATTR));
        provisioner.delete(resource, REGION);
        verify(service).deleteDeploymentGroup(REGION, "old-application", "old-group");
    }

    @Test
    void missingParentIdentityFailsWithoutSearchingOrDeletingAnotherApplication() {
        StackResource resource = resource();
        resource.setPhysicalId("shared-name");
        assertThrows(IllegalStateException.class, () -> provisioner.delete(resource, REGION));
        verifyNoInteractions(service);
    }

    @Test
    void deletionToleratesOnlyMissingResourcesAndDoesNotHideAccessDenial() {
        StackResource resource = resource();
        resource.setPhysicalId("group");
        resource.getAttributes().put(CfnRollback.CODEDEPLOY_GROUP_APPLICATIONS_ATTR,
                "{\"group\":\"application\"}");
        doThrow(new AwsException("AccessDeniedException", "not allowed", 403)).when(service)
                .deleteDeploymentGroup(REGION, "application", "group");
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.delete(resource, REGION));
        assertEquals("AccessDeniedException", failure.getErrorCode());
        doThrow(new AwsException("DeploymentGroupDoesNotExistException", "already gone", 400)).when(service)
                .deleteDeploymentGroup(REGION, "application", "group");
        provisioner.delete(resource, REGION);
    }

    private JsonNode group(String application, String group) {
        return mapper.createObjectNode().put("ApplicationName", application)
                .put("DeploymentGroupName", group).put("ServiceRoleArn", "arn:aws:iam::000000000000:role/deploy");
    }

    private ProvisionContext context(String prior) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(call -> {
            JsonNode node = call.getArgument(0);
            return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(call -> call.getArgument(0));
        when(engine.resolveNodeOmittingNoValue(any())).thenAnswer(call -> call.getArgument(0));
        return new ProvisionContext(engine, REGION, "000000000000", "stack", prior);
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Group");
        resource.setResourceType("AWS::CodeDeploy::DeploymentGroup");
        resource.setAttributes(new HashMap<>());
        return resource;
    }
}
