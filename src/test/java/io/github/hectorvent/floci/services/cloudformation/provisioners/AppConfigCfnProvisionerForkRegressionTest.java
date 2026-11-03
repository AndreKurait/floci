package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appconfig.AppConfigService;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

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

class AppConfigCfnProvisionerForkRegressionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void applicationUpdatesKeepIdentity() {
        AppConfigService service = mock(AppConfigService.class);
        AppConfigCfnProvisioner provisioner = new AppConfigCfnProvisioner(service);
        Application app = new Application();
        app.setId("app1234");
        when(service.createApplication(any())).thenReturn(app);
        when(service.findApplication("app1234")).thenReturn(Optional.of(app));
        JsonNode properties = MAPPER.valueToTree(Map.of("Name", "configuration"));
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::AppConfig::Application");
        provisioner.provision(resource, properties, context(null));
        provisioner.provision(resource, properties, context("app1234"));
        provisioner.provision(resource, MAPPER.valueToTree(Map.of("Name", "changed")), context("app1234"));
        assertEquals("app1234", resource.getPhysicalId());
        assertEquals("app1234", resource.getAttributes().get("ApplicationId"));
        verify(service, times(1)).createApplication(any());
        verify(service).updateApplication("app1234", "configuration", null);
        verify(service).updateApplication("app1234", "changed", null);
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
                .when(service).deleteEnvironmentById("env1234");
        assertDoesNotThrow(() -> provisioner.delete(resource, "us-east-1"));
        doThrow(new AwsException("InternalServerException", "storage failed", 500))
                .when(service).deleteEnvironmentById("env1234");
        AwsException failure = assertThrows(AwsException.class, () -> provisioner.delete(resource, "us-east-1"));
        assertEquals("InternalServerException", failure.getErrorCode());
    }

    @Test
    void namedParentCreationRetainsResolvedApplicationIdentity() {
        AppConfigService service = mock(AppConfigService.class);
        AppConfigCfnProvisioner provisioner = new AppConfigCfnProvisioner(service);
        Environment environment = new Environment();
        environment.setId("env1234");
        environment.setApplicationId("app1234");
        ConfigurationProfile profile = new ConfigurationProfile();
        profile.setId("prof123");
        profile.setApplicationId("app1234");
        when(service.createEnvironment(any(), any())).thenReturn(environment);
        when(service.createConfigurationProfile(any(), any())).thenReturn(profile);
        for (String type : new String[]{"Environment", "ConfigurationProfile"}) {
            StackResource resource = new StackResource();
            resource.setResourceType("AWS::AppConfig::" + type);
            Map<String, Object> properties = "Environment".equals(type)
                    ? Map.of("Name", "environment", "ApplicationId", "application-name")
                    : Map.of("Name", "profile", "ApplicationId", "application-name", "LocationUri", "hosted");
            provisioner.provision(resource, MAPPER.valueToTree(properties), context(null));
            assertEquals("app1234", resource.getAttributes().get(CfnRollback.USES_ATTR_PREFIX + "AppConfigApplicationId"));
            provisioner.delete(resource, "us-east-1");
        }
        verify(service).deleteEnvironmentById("env1234");
        verify(service).deleteConfigurationProfileById("prof123");
    }

    private static ProvisionContext context(String prior) {
        CloudFormationTemplateEngine engine = CloudFormationTemplateEngine.standalone(
                "000000000000", "us-east-1", "configuration", MAPPER, value -> value);
        return new ProvisionContext(engine, "us-east-1", "000000000000", "configuration", prior);
    }
}
