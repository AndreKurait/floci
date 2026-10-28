package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.AppConfigCfnProvisioner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppConfigCfnIdentityTest {
    @TempDir
    Path directory;
    private StorageFactory factory;
    private AppConfigService service;
    private AppConfigCfnProvisioner provisioner;

    @BeforeEach
    void setUp() {
        factory = AppConfigStorageTest.factory(directory, "memory");
        RegionResolver regions = mock(RegionResolver.class);
        when(regions.getRegion()).thenReturn("us-east-1");
        when(regions.getDefaultRegion()).thenReturn("us-east-1");
        service = new AppConfigService(factory, regions);
        provisioner = new AppConfigCfnProvisioner(service);
    }

    @AfterEach
    void close() {
        factory.shutdownAll();
    }

    @Test
    void applicationRemovedBeforeStackDeleteDoesNotSelectAnotherName() {
        String original = service.createApplication(Map.of("Name", "original")).getId();
        String other = service.createApplication(Map.of("Name", original)).getId();
        service.deleteApplication(original);
        delete("Application", original, null);
        assertEquals(other, service.getApplication(other).getId());
    }

    @Test
    void removedEnvironmentDoesNotSelectAnotherName() {
        String app = service.createApplication(Map.of("Name", "app")).getId();
        String original = service.createEnvironment(app, Map.of("Name", "original")).getId();
        String other = service.createEnvironment(app, Map.of("Name", original)).getId();
        service.deleteEnvironment(app, original);
        delete("Environment", original, app);
        assertEquals(other, service.getEnvironment(app, other).getId());
    }

    @Test
    void removedProfileDoesNotDeleteAnotherProfilesVersions() {
        String app = service.createApplication(Map.of("Name", "app")).getId();
        String original = profile(app, "original");
        String other = profile(app, original);
        service.createHostedConfigurationVersion(app, other, new byte[]{1}, "text/plain", null);
        service.deleteConfigurationProfile(app, original);
        delete("ConfigurationProfile", original, app);
        assertEquals(other, service.getConfigurationProfile(app, other).getId());
        assertEquals(1, service.getHostedConfigurationVersion(app, other, 1).getVersionNumber());
    }

    @Test
    void absentParentIdDoesNotResolveAnotherApplicationNameForEnvironment() {
        String missing = "goneapp";
        String otherApp = service.createApplication(Map.of("Name", missing)).getId();
        String env = service.createEnvironment(otherApp, Map.of("Name", "env")).getId();
        delete("Environment", env, missing);
        assertEquals(env, service.getEnvironment(otherApp, env).getId());
    }

    @Test
    void absentParentIdDoesNotResolveAnotherApplicationNameForProfile() {
        String missing = "goneapp";
        String otherApp = service.createApplication(Map.of("Name", missing)).getId();
        String profile = profile(otherApp, "profile");
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> delete("ConfigurationProfile", profile, missing)).getErrorCode());
        assertEquals(profile, service.getConfigurationProfile(otherApp, profile).getId());
    }

    @Test
    void exactProfileDeletionCascadesVersionsAndCanBeRepeated() {
        String app = service.createApplication(Map.of("Name", "app")).getId();
        String profile = profile(app, "profile");
        service.createHostedConfigurationVersion(app, profile, new byte[]{1}, "text/plain", null);
        delete("ConfigurationProfile", profile, app);
        delete("ConfigurationProfile", profile, app);
        assertThrows(AwsException.class, () -> service.getHostedConfigurationVersion(app, profile, 1));
        assertEquals(0, service.listConfigurationProfiles(app).size());
    }

    @Test
    void managementDeletionStillAcceptsNames() {
        String app = service.createApplication(Map.of("Name", "app-name")).getId();
        service.createEnvironment(app, Map.of("Name", "environment-name"));
        profile(app, "profile-name");
        service.deleteEnvironment("app-name", "environment-name");
        service.deleteConfigurationProfile("app-name", "profile-name");
        assertEquals(0, service.listEnvironments(app).size());
        assertEquals(0, service.listConfigurationProfiles(app).size());
        service.deleteApplication("app-name");
        assertEquals(0, service.listApplications().size());
    }

    private String profile(String app, String name) {
        return service.createConfigurationProfile(app, Map.of("Name", name, "LocationUri", "hosted")).getId();
    }

    private void delete(String type, String id, String app) {
        StackResource resource = new StackResource();
        resource.setResourceType("AWS::AppConfig::" + type);
        resource.setPhysicalId(id);
        if (app != null) {
            resource.getAttributes().put("_floci_appconfig_application", app);
        }
        provisioner.delete(resource, "us-east-1");
    }
}
