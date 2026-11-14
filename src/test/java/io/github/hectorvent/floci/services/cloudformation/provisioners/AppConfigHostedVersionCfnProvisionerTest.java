package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.services.appconfig.AppConfigService;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersion;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AppConfigHostedVersionCfnProvisionerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AppConfigService service = mock(AppConfigService.class);
    private final ServiceConfigAccess serviceConfig = mock(ServiceConfigAccess.class);
    private final AppConfigHostedVersionCfnProvisioner provisioner =
            new AppConfigHostedVersionCfnProvisioner(service, serviceConfig);
    private final ProvisionContext context = new ProvisionContext(CloudFormationTemplateEngine.standalone(
            "000000000000", "us-east-1", "hosted", JSON, value -> value),
            "us-east-1", "000000000000", "hosted");

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void registryHonorsAppConfigEnablement(boolean enabled) {
        when(serviceConfig.isEnabled("appconfig")).thenReturn(enabled);
        CloudFormationResourceRegistry registry = new CloudFormationResourceRegistry(List.of(provisioner));

        assertEquals(enabled, registry.forType("AWS::AppConfig::HostedConfigurationVersion").isPresent());
        assertEquals(!enabled, registry.isServiceDisabled("AWS::AppConfig::HostedConfigurationVersion"));
        assertEquals("floci.services.appconfig.enabled",
                registry.serviceEnabledFlag("AWS::AppConfig::HostedConfigurationVersion"));
        verifyNoInteractions(service);
    }

    @Test
    void preservesContentAndSeparatesReferenceFromPhysicalIdentity() {
        HostedConfigurationVersion version = new HostedConfigurationVersion();
        version.setApplicationId("app0001");
        version.setConfigurationProfileId("prof001");
        version.setVersionNumber(7);
        when(service.createHostedConfigurationVersion(eq("application-name"), eq("profile-name"),
                any(byte[].class), eq("application/json"), isNull(), isNull(), isNull())).thenReturn(version);
        String content = "  {\"message\":\"café\"}\n";
        Map<String, Object> properties = properties(content);
        StackResource resource = resource();

        provisioner.provision(resource, JSON.valueToTree(properties), context);

        assertEquals("app0001|prof001|7", resource.getPhysicalId());
        assertEquals("7", provisioner.referenceValue(resource.getPhysicalId()));
        assertEquals("7", resource.getAttributes().get("VersionNumber"));
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(service).createHostedConfigurationVersion(
                eq("application-name"), eq("profile-name"), bytes.capture(), eq("application/json"), isNull(), isNull(), isNull());
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), bytes.getValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ApplicationId", "ConfigurationProfileId", "Content", "ContentType"})
    void missingRequiredPropertiesDoNotCreateAVersion(String missing) {
        Map<String, Object> properties = properties("{}");
        properties.remove(missing);
        StackResource resource = resource();

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, JSON.valueToTree(properties), context));

        assertEquals("ValidationError", failure.getErrorCode());
        assertNull(resource.getPhysicalId());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"release", ""})
    void labelsAndLatestTokenReachTheServiceWithoutLosingTheEmptyProperty(String label) {
        HostedConfigurationVersion version = new HostedConfigurationVersion();
        version.setApplicationId("app0001");
        version.setConfigurationProfileId("prof001");
        version.setVersionNumber(7);
        String serviceLabel = label.isEmpty() ? null : label;
        when(service.createHostedConfigurationVersion(eq("application-name"), eq("profile-name"),
                any(byte[].class), eq("application/json"), isNull(), eq(serviceLabel), eq(0))).thenReturn(version);
        Map<String, Object> properties = properties("{}");
        properties.put("LatestVersionNumber", 0);
        properties.put("VersionLabel", label);
        StackResource resource = resource();

        provisioner.provision(resource, JSON.valueToTree(properties), context);

        verify(service).createHostedConfigurationVersion(eq("application-name"), eq("profile-name"),
                any(byte[].class), eq("application/json"), isNull(), eq(serviceLabel), eq(0));
        assertEquals("7", provisioner.referenceValue(resource.getPhysicalId()));
        assertEquals("7", resource.getAttributes().get("VersionNumber"));
        assertEquals(JSON.valueToTree(properties).toString(), resource.getAttributes().get(
                CfnRollback.USES_ATTR_PREFIX + "AppConfigHostedVersionProperties"));
    }

    @Test
    void numericParameterTextRetainsItsSignedIntegerValue() {
        HostedConfigurationVersion version = new HostedConfigurationVersion();
        version.setApplicationId("app0001");
        version.setConfigurationProfileId("prof001");
        version.setVersionNumber(7);
        when(service.createHostedConfigurationVersion(eq("application-name"), eq("profile-name"),
                any(byte[].class), eq("application/json"), isNull(), isNull(), eq(Integer.MAX_VALUE))).thenReturn(version);
        Map<String, Object> properties = properties("{}");
        properties.put("LatestVersionNumber", Integer.toString(Integer.MAX_VALUE));

        provisioner.provision(resource(), JSON.valueToTree(properties), context);

        verify(service).createHostedConfigurationVersion(eq("application-name"), eq("profile-name"),
                any(byte[].class), eq("application/json"), isNull(), isNull(), eq(Integer.MAX_VALUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "2147483648", "-2147483649", "true", "{}", "[]"})
    void invalidIntegerValuesRefuseBeforeAnyServiceOrResourceMutation(String value) throws Exception {
        Map<String, Object> properties = properties("{}");
        properties.put("LatestVersionNumber", JSON.readTree(value));
        StackResource resource = resource();
        resource.getAttributes().put("Existing", "unchanged");

        AwsException failure = assertThrows(AwsException.class,
                () -> provisioner.provision(resource, JSON.valueToTree(properties), context));

        assertEquals("ValidationError", failure.getErrorCode());
        assertNull(resource.getPhysicalId());
        assertEquals(Map.of("Existing", "unchanged"), resource.getAttributes());
        verifyNoInteractions(service);
    }

    @Test
    void deletionUsesAllThreeIdentityPartsAndOnlyIgnoresNotFound() {
        provisioner.delete("AWS::AppConfig::HostedConfigurationVersion", "app0001|prof001|7", "us-east-1");
        verify(service).deleteHostedConfigurationVersion("app0001", "prof001", 7);
        doThrow(new AwsException("ResourceNotFoundException", "Already deleted", 404))
                .when(service).deleteHostedConfigurationVersion("app0001", "prof001", 7);
        provisioner.delete("AWS::AppConfig::HostedConfigurationVersion", "app0001|prof001|7", "us-east-1");
        doThrow(new AwsException("AccessDeniedException", "Denied", 403))
                .when(service).deleteHostedConfigurationVersion("app0001", "prof001", 7);
        assertThrows(AwsException.class, () -> provisioner.delete(
                "AWS::AppConfig::HostedConfigurationVersion", "app0001|prof001|7", "us-east-1"));
    }

    @Test
    void oldDispatcherStubsKeepTheirReferenceAndAreNotDeleted() {
        StackResource resource = resource();
        resource.setPhysicalId("Config-1234abcd");
        resource.getAttributes().put("Arn", CfnResourceDispatcher.STUB_ARN_PREFIX + "Config");
        assertEquals("Config-1234abcd", provisioner.referenceValue(resource.getPhysicalId()));
        provisioner.delete(resource, "us-east-1");
        verifyNoInteractions(service);
    }

    private static Map<String, Object> properties(String content) {
        return new HashMap<>(Map.of("ApplicationId", "application-name", "ConfigurationProfileId", "profile-name",
                "Content", content, "ContentType", "application/json"));
    }

    private static StackResource resource() {
        StackResource resource = new StackResource();
        resource.setLogicalId("Config");
        resource.setResourceType("AWS::AppConfig::HostedConfigurationVersion");
        return resource;
    }
}
