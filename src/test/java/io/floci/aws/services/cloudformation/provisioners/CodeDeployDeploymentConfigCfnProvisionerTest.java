package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.codedeploy.CodeDeployService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CodeDeployDeploymentConfigCfnProvisionerTest {
    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final CodeDeployService service = mock(CodeDeployService.class);
    private final CodeDeployCfnProvisioner provisioner = new CodeDeployCfnProvisioner(service);

    @Test
    void configurationDeletionToleratesOnlyTheMissingConfigurationError() {
        StackResource resource = configurationResource();
        resource.setPhysicalId("config");
        AwsException denied = new AwsException("AccessDeniedException", "not allowed", 403);
        doThrow(denied).when(service).deleteDeploymentConfig(REGION, "config");
        assertSame(denied, assertThrows(AwsException.class, () -> provisioner.delete(resource, REGION)));
        doThrow(new AwsException("DeploymentConfigDoesNotExistException", "already gone", 400))
                .when(service).deleteDeploymentConfig(REGION, "config");
        provisioner.delete(resource, REGION);
    }

    @Test
    void configurationReplacementRetriesFailedCleanupWithoutDeletingThePriorConfiguration() throws Exception {
        StackResource resource = configurationResource();
        provisioner.provision(resource, mapper.readTree("{\"DeploymentConfigName\":\"old\"}"), context(null));
        provisioner.provision(resource, mapper.readTree("{\"DeploymentConfigName\":\"new\"}"), context("old"));
        doThrow(new IllegalStateException("delete failed")).when(service).deleteDeploymentConfig(REGION, "new");
        assertThrows(IllegalStateException.class, () -> provisioner.rollbackUpdate(resource));
        assertEquals("old", resource.getPhysicalId());
        verify(service, never()).deleteDeploymentConfig(REGION, "old");
        doNothing().when(service).deleteDeploymentConfig(REGION, "new");
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(service, times(2)).deleteDeploymentConfig(REGION, "new");
        provisioner.clearUpdate(resource);
        provisioner.delete(resource, REGION);
        verify(service).deleteDeploymentConfig(REGION, "old");
    }

    @Test
    void configurationRejectsInvalidNestedShapesBeforeCreatingAnything() throws Exception {
        for (String properties : List.of(
                "{\"MinimumHealthyHosts\":{\"Value\":1}}",
                "{\"MinimumHealthyHosts\":{\"Type\":\"HOST_COUNT\",\"Value\":\"not-a-number\"}}",
                "{\"TrafficRoutingConfig\":{\"Type\":\"TimeBasedCanary\",\"TimeBasedCanary\":[]}}",
                "{\"TrafficRoutingConfig\":{\"Type\":\"TimeBasedLinear\",\"Extra\":true}}")) {
            JsonNode parsed = mapper.readTree(properties);
            assertThrows(AwsException.class,
                    () -> provisioner.provision(configurationResource(), parsed, context(null)));
        }
        verifyNoInteractions(service);
    }

    @Test
    void configurationPreservesTheSchemasInt64ZoneDuration() throws Exception {
        provisioner.provision(configurationResource(), mapper.readTree("""
                {"DeploymentConfigName":"config",
                 "ZonalConfig":{"MonitorDurationInSeconds":"4294967296"}}
                """), context(null));
        verify(service).createDeploymentConfig(eq(REGION), eq("config"), isNull(), eq("Server"), isNull(),
                eq(Map.of("monitorDurationInSeconds", 4294967296L)));
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

    private static StackResource configurationResource() {
        StackResource resource = resource();
        resource.setLogicalId("Config");
        resource.setResourceType("AWS::CodeDeploy::DeploymentConfig");
        return resource;
    }
}
