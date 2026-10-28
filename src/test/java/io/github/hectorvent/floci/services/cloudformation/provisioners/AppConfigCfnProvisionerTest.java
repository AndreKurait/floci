package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appconfig.AppConfigService;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppConfigCfnProvisionerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void unchangedReapplyKeepsIdentityAndChangedPropertiesRefuseBeforeMutation() {
        AppConfigService service = mock(AppConfigService.class);
        AppConfigCfnProvisioner provisioner = new AppConfigCfnProvisioner(service);
        Application app = new Application();
        app.setId("app1234");
        when(service.createApplication(any())).thenReturn(app);
        when(service.getApplication("app1234")).thenReturn(app);
        JsonNode properties = MAPPER.valueToTree(Map.of("Name", "configuration"));
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::AppConfig::Application");
        provisioner.provision(resource, properties, context(null));
        provisioner.provision(resource, properties, context("app1234"));
        assertEquals("app1234", resource.getPhysicalId());
        assertEquals("app1234", resource.getAttributes().get("ApplicationId"));
        assertThrows(AwsException.class, () -> provisioner.provision(resource,
                MAPPER.valueToTree(Map.of("Name", "changed")), context("app1234")));
        verify(service, times(1)).createApplication(any());
        verify(service, times(1)).getApplication("app1234");
    }

    @Test
    void unsupportedEncryptionAndMissingRequiredValuesRefuseBeforeCreation() {
        AppConfigService service = mock(AppConfigService.class);
        AppConfigCfnProvisioner provisioner = new AppConfigCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::AppConfig::ConfigurationProfile");
        assertThrows(AwsException.class, () -> provisioner.provision(resource,
                MAPPER.valueToTree(Map.of("Name", "configuration", "KmsKeyIdentifier", "key")), context(null)));
        assertThrows(AwsException.class, () -> provisioner.provision(resource,
                MAPPER.valueToTree(Map.of("Name", "configuration", "ApplicationId", "app1234")), context(null)));
        verifyNoInteractions(service);
    }

    @Test
    void invalidStrategyNumbersRefuseBeforeCreation() {
        AppConfigService service = mock(AppConfigService.class);
        AppConfigCfnProvisioner provisioner = new AppConfigCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::AppConfig::DeploymentStrategy");
        assertThrows(AwsException.class, () -> provisioner.provision(resource, MAPPER.valueToTree(Map.of(
                "Name", "invalid", "DeploymentDurationInMinutes", 1.5, "GrowthFactor", 100, "ReplicateTo", "NONE")),
                context(null)));
        assertThrows(AwsException.class, () -> provisioner.provision(resource, MAPPER.valueToTree(Map.of(
                "Name", "invalid", "DeploymentDurationInMinutes", 1, "GrowthFactor", 0, "ReplicateTo", "NONE")),
                context(null)));
        verifyNoInteractions(service);
    }

    @Test
    void environmentAlreadyDeletedIsSafeButOtherDeleteFailuresPropagate() {
        AppConfigService service = mock(AppConfigService.class);
        AppConfigCfnProvisioner provisioner = new AppConfigCfnProvisioner(service);
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::AppConfig::Environment");
        resource.setPhysicalId("env1234");
        resource.getAttributes().put("_floci_appconfig_application", "app1234");
        doThrow(new AwsException("ResourceNotFoundException", "Environment not found", 404))
                .when(service).deleteEnvironment("app1234", "env1234");
        assertDoesNotThrow(() -> provisioner.delete(resource, "us-east-1"));
        doThrow(new AwsException("InternalServerException", "storage failed", 500))
                .when(service).deleteEnvironment("app1234", "env1234");
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.delete(resource, "us-east-1"));
        assertEquals("InternalServerException", failure.getErrorCode());
    }

    private static ProvisionContext context(String prior) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new ProvisionContext(engine, "us-east-1", "000000000000", "configuration", prior);
    }
}
