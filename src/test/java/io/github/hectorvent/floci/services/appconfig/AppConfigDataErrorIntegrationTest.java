package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfig.AppConfigClient;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;
import software.amazon.awssdk.services.appconfigdata.model.ResourceNotFoundException;
import software.amazon.awssdk.services.appconfigdata.model.ResourceType;
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class AppConfigDataErrorIntegrationTest {
    private static final String ACCOUNT = "583583583583";

    @ParameterizedTest
    @EnumSource(value = ResourceType.class, names = {"APPLICATION", "ENVIRONMENT", "CONFIGURATION_PROFILE"})
    void missingResourcesKeepTheirSdkTypeAndRequestReference(ResourceType type) {
        try (AppConfigClient control = AwsTestClients.create(AppConfigClient.builder(), ACCOUNT, Region.US_EAST_1);
                AppConfigDataClient data = AwsTestClients.create(
                        AppConfigDataClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            List<Executable> cleanup = new ArrayList<>();
            try {
                Resources resources = create(control, cleanup);
                String missing = "missing-" + UUID.randomUUID();
                StartConfigurationSessionRequest.Builder request = resources.request().toBuilder();
                switch (type) {
                    case APPLICATION -> request.applicationIdentifier(missing);
                    case ENVIRONMENT -> request.environmentIdentifier(missing);
                    case CONFIGURATION_PROFILE -> request.configurationProfileIdentifier(missing);
                    default -> throw new AssertionError(type);
                }
                ResourceNotFoundException error = assertThrows(ResourceNotFoundException.class,
                        () -> data.startConfigurationSession(request.build()));
                assertMissing(error, type, missing);
                String token = data.startConfigurationSession(resources.request()).initialConfigurationToken();
                assertEquals(0, data.getLatestConfiguration(requestBuilder ->
                        requestBuilder.configurationToken(token)).configuration().asByteArray().length);
            } finally {
                assertAll(cleanup.reversed());
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = ResourceType.class, names = {"APPLICATION", "ENVIRONMENT"})
    void deletedSessionResourcesIdentifyTheConfigurationWithoutConsumingTheToken(ResourceType type) {
        try (AppConfigClient control = AwsTestClients.create(AppConfigClient.builder(), ACCOUNT, Region.US_EAST_1);
                AppConfigDataClient data = AwsTestClients.create(
                        AppConfigDataClient.builder(), ACCOUNT, Region.US_EAST_1)) {
            List<Executable> cleanup = new ArrayList<>();
            try {
                Resources resources = create(control, cleanup);
                String token = data.startConfigurationSession(resources.request()).initialConfigurationToken();
                if (type == ResourceType.APPLICATION) {
                    assertAll(cleanup.reversed());
                    cleanup.clear();
                } else {
                    control.deleteEnvironment(request -> request.applicationId(resources.application())
                            .environmentId(resources.environment()).deletionProtectionCheck("BYPASS"));
                    cleanup.remove(resources.environmentCleanup());
                }
                for (int attempt = 0; attempt < 2; attempt++) {
                    ResourceNotFoundException error = assertThrows(ResourceNotFoundException.class,
                            () -> data.getLatestConfiguration(request -> request.configurationToken(token)));
                    assertMissing(error, ResourceType.CONFIGURATION, token);
                }
                ResourceNotFoundException error = assertThrows(ResourceNotFoundException.class,
                        () -> data.startConfigurationSession(resources.request()));
                assertMissing(error, type, type == ResourceType.APPLICATION
                        ? resources.application() : resources.environment());
            } finally {
                assertAll(cleanup.reversed());
            }
        }
    }

    private static void assertMissing(ResourceNotFoundException error, ResourceType type, String identifier) {
        assertEquals(404, error.statusCode());
        assertEquals(type, error.resourceType());
        String parameter = switch (type) {
            case APPLICATION -> "ApplicationIdentifier";
            case ENVIRONMENT -> "EnvironmentIdentifier";
            case CONFIGURATION_PROFILE -> "ConfigurationProfileIdentifier";
            case CONFIGURATION -> "ConfigurationToken";
            default -> throw new AssertionError(type);
        };
        assertEquals(Map.of(parameter, identifier), error.referencedBy());
    }

    private static Resources create(AppConfigClient control, List<Executable> cleanup) {
        String application = control.createApplication(request -> request.name("errors-" + UUID.randomUUID())).id();
        cleanup.add(() -> control.deleteApplication(request -> request.applicationId(application)));
        String environment = control.createEnvironment(request -> request.applicationId(application).name("test")).id();
        Executable environmentCleanup = () -> control.deleteEnvironment(request -> request.applicationId(application)
                .environmentId(environment).deletionProtectionCheck("BYPASS"));
        cleanup.add(environmentCleanup);
        String profile = control.createConfigurationProfile(request -> request.applicationId(application)
                .name("settings").locationUri("hosted")).id();
        cleanup.add(() -> control.deleteConfigurationProfile(request -> request.applicationId(application)
                .configurationProfileId(profile).deletionProtectionCheck("BYPASS")));
        return new Resources(application, environment, profile, environmentCleanup);
    }

    private record Resources(String application, String environment, String profile, Executable environmentCleanup) {
        StartConfigurationSessionRequest request() {
            return StartConfigurationSessionRequest.builder().applicationIdentifier(application)
                    .environmentIdentifier(environment).configurationProfileIdentifier(profile).build();
        }
    }
}
