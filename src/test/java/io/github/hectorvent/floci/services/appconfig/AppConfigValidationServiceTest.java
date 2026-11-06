package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationContent;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppConfigValidationServiceTest {
    private static final String SCHEMA = "{\"type\":\"integer\",\"minimum\":1,\"maximum\":100}";
    private final AppConfigS3Source source = mock(AppConfigS3Source.class);
    private final AppConfigService service = new AppConfigService(new InMemoryStorageFactory("000000000000"),
            new RegionResolver("us-east-1", "000000000000"), mock(AppConfigAlarmMonitor.class), source);
    private String app;
    private String environment;
    private String profile;
    private String strategy;

    @BeforeEach
    void createConfiguration() {
        app = service.createApplication(Map.of("Name", "application")).getId();
        environment = service.createEnvironment(app, Map.of("Name", "test")).getId();
        profile = service.createConfigurationProfile(app, Map.of("Name", "settings", "LocationUri", "hosted",
                "Validators", validators(SCHEMA))).getId();
        strategy = service.createDeploymentStrategy(Map.of("Name", "immediate", "DeploymentDurationInMinutes", 0,
                "FinalBakeTimeInMinutes", 0, "GrowthFactor", 100, "ReplicateTo", "NONE")).getId();
    }

    @Test
    void invalidContentCannotChangeTheActiveVersionOrAllocateADeployment() {
        String good = version("10");
        deploy(profile, good);
        assertBadRequest(() -> deploy(profile, version("\"not-an-integer\"")));
        assertEquals(good, service.getActiveVersion(environment, profile));
        assertEquals(1, service.listDeployments(app, environment, null, null).items().size());
        Deployment recovered = deploy(profile, version("20"));
        assertEquals(2, recovered.getDeploymentNumber());
    }

    @Test
    void explicitValidationDoesNotDeployEvenWhenTheProfileHasNoValidators() {
        String selected = version("10");
        service.validateConfiguration("application", "settings", selected);
        assertNull(service.getActiveVersion(environment, profile));
        assertTrue(service.listDeployments(app, environment, null, null).items().isEmpty());
        assertBadRequest(() -> service.validateConfiguration(app, profile, version("101")));
        service.updateConfigurationProfile(app, profile, Map.of("Validators", List.of()));
        assertDoesNotThrow(() -> service.validateConfiguration(app, profile, version("raw-data")));
        AwsException missing = assertThrows(AwsException.class,
                () -> service.validateConfiguration(app, profile, "1000"));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void invalidProfileUpdatesAreAtomicAndClearingValidatorsTakesEffect() {
        ConfigurationProfile before = service.getConfigurationProfile(app, profile);
        assertBadRequest(() -> service.updateConfigurationProfile(app, profile,
                Map.of("Name", "changed", "Validators", validators("{\"type\":\"not-a-type\"}"))));
        assertEquals("settings", service.getConfigurationProfile(app, profile).getName());
        assertEquals(SCHEMA, service.getConfigurationProfile(app, profile).getValidators().getFirst().content());

        service.updateConfigurationProfile(app, profile, Map.of("Validators", validators("{\"maximum\":5}")));
        assertEquals("settings", service.getConfigurationProfile(app, profile).getName());
        assertEquals(SCHEMA, before.getValidators().getFirst().content());
        assertBadRequest(() -> deploy(profile, version("10")));
        service.updateConfigurationProfile(app, profile, Map.of("Validators", List.of()));
        assertDoesNotThrow(() -> deploy(profile, version("1000")));
    }

    @Test
    void anotherApplicationCannotUpdateOrValidateTheProfile() {
        String other = service.createApplication(Map.of("Name", "other")).getId();
        for (Runnable action : List.<Runnable>of(
                () -> service.updateConfigurationProfile(other, profile, Map.of("Validators", List.of())),
                () -> service.validateConfiguration(other, profile, "1"))) {
            AwsException error = assertThrows(AwsException.class, action::run);
            assertEquals("ResourceNotFoundException", error.getErrorCode());
        }
        assertEquals(SCHEMA, service.getConfigurationProfile(app, profile).getValidators().getFirst().content());
    }

    @Test
    void s3ValidationUsesTheRetrievedBytesAndStoresOnlyAdmittedVersions() {
        String s3Profile = service.createConfigurationProfile(app, Map.of("Name", "s3-settings",
                "LocationUri", "s3://configuration-source/settings.json",
                "RetrievalRoleArn", "arn:aws:iam::000000000000:role/appconfig",
                "Validators", validators(SCHEMA))).getId();
        byte[] good = "10".getBytes(StandardCharsets.UTF_8);
        when(source.retrieve(any(), eq("one"))).thenReturn(new ConfigurationContent(good, "application/json"));
        when(source.retrieve(any(), eq("two")))
                .thenReturn(new ConfigurationContent("101".getBytes(StandardCharsets.UTF_8), "application/json"));

        service.validateConfiguration(app, s3Profile, "one");
        assertThrows(AwsException.class, () -> service.getS3ConfigurationContent(app, s3Profile, "one"));
        deploy(s3Profile, "one");
        assertBadRequest(() -> deploy(s3Profile, "two"));
        assertEquals("one", service.getActiveVersion(environment, s3Profile));
        assertArrayEquals(good, service.getS3ConfigurationContent(app, s3Profile, "one").content());
        assertThrows(AwsException.class, () -> service.getS3ConfigurationContent(app, s3Profile, "two"));
        verify(source, times(2)).retrieve(any(), eq("one"));
        verify(source).retrieve(any(), eq("two"));
    }

    @Test
    void unsupportedLambdaValidationCannotActivateConfiguration() {
        service.updateConfigurationProfile(app, profile, Map.of("Validators", List.of(Map.of(
                "Type", "LAMBDA", "Content", "arn:aws:lambda:us-east-1:000000000000:function:validate"))));
        String selected = version("10");
        assertBadRequest(() -> deploy(profile, selected));
        assertBadRequest(() -> service.validateConfiguration(app, profile, selected));
        assertNull(service.getActiveVersion(environment, profile));
        assertTrue(service.listDeployments(app, environment, null, null).items().isEmpty());
    }

    @Test
    void invalidMutableFieldsDoNotPartiallyRenameAProfile() {
        for (Map<String, Object> change : List.<Map<String, Object>>of(
                Map.of("Name", ""), Map.of("Name", "n".repeat(129)),
                Map.of("Name", "changed", "Description", "d".repeat(1025)),
                Map.of("Name", "changed", "RetrievalRoleArn", 17),
                Map.of("Name", "changed", "KmsKeyIdentifier", "unsupported-key"))) {
            assertBadRequest(() -> service.updateConfigurationProfile(app, profile, change));
            assertEquals("settings", service.getConfigurationProfile(app, profile).getName());
        }
    }

    private String version(String content) {
        return String.valueOf(service.createHostedConfigurationVersion(app, profile,
                content.getBytes(StandardCharsets.UTF_8), "application/json", null).getVersionNumber());
    }

    private Deployment deploy(String profileId, String version) {
        return service.startDeployment(app, environment, Map.of("ConfigurationProfileId", profileId,
                "ConfigurationVersion", version, "DeploymentStrategyId", strategy));
    }

    private static List<Map<String, String>> validators(String schema) {
        return List.of(Map.of("Type", "JSON_SCHEMA", "Content", schema));
    }

    private static void assertBadRequest(Runnable action) {
        assertEquals("BadRequestException", assertThrows(AwsException.class, action::run).getErrorCode());
    }
}
