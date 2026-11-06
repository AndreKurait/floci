package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigResourceArnsTest {
    private static final String ACCOUNT = "111122223333";

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "cn-north-1,aws-cn", "us-gov-west-1,aws-us-gov"})
    void namesAndIdsAuthorizeTheSameResources(String region, String partition) {
        Fixture fixture = fixture(region);
        String prefix = "arn:" + partition + ":appconfig:" + region + ":" + ACCOUNT + ":";
        Map<String, String> names = Map.of("ApplicationId", "orders", "EnvironmentId", "production",
                "ConfigurationProfileId", "settings", "DeploymentNumber", "42");
        Map<String, String> ids = Map.of("ApplicationId", fixture.application(), "EnvironmentId", fixture.environment(),
                "ConfigurationProfileId", fixture.profile(), "DeploymentNumber", "42");
        String application = "application/" + fixture.application();
        Map<String, String> resources = Map.of(
                "application", application,
                "environment", application + "/environment/" + fixture.environment(),
                "configurationprofile", application + "/configurationprofile/" + fixture.profile(),
                "hostedconfigurationversion", application + "/configurationprofile/" + fixture.profile(),
                "configuration", application + "/environment/" + fixture.environment()
                        + "/configuration/" + fixture.profile(),
                "deployment", application + "/environment/" + fixture.environment() + "/deployment/42");
        resources.forEach((type, resource) -> {
            assertEquals(prefix + resource, fixture.arns().findByVariables(type, names, region).orElseThrow());
            assertEquals(prefix + resource, fixture.arns().findByVariables(type, ids, region).orElseThrow());
        });
        assertTrue(fixture.arns().findByVariables("configurationprofile",
                Map.of("ApplicationId", fixture.application()), region).isEmpty());
        assertTrue(fixture.arns().findByVariables("application", names, "eu-west-1").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "cn-north-1,aws-cn", "us-gov-west-1,aws-us-gov"})
    void authorizationDoesNotConsumeTheTokenOrReinterpretADeletedProfile(String region, String partition) {
        Fixture fixture = fixture(region);
        String token = fixture.data().startConfigurationSession(Map.of(
                "ApplicationIdentifier", "orders", "EnvironmentIdentifier", "production",
                "ConfigurationProfileIdentifier", "settings"));
        String arn = "arn:" + partition + ":appconfig:" + region + ":" + ACCOUNT
                + ":application/" + fixture.application() + "/environment/" + fixture.environment()
                + "/configuration/" + fixture.profile();
        assertEquals(List.of(arn), fixture.arns().through("ConfigurationToken", token, region));
        assertEquals(List.of(arn), fixture.arns().through("ConfigurationToken", token, region));
        String next = fixture.data().getLatestConfiguration(token).nextPollConfigurationToken();
        assertEquals("BadRequestException",
                assertThrows(AwsException.class, () -> fixture.arns().through("ConfigurationToken", token, region))
                        .getErrorCode());
        assertEquals(List.of(arn), fixture.arns().through("ConfigurationToken", next, region));

        // A deleted profile's ID must not be reinterpreted as a different profile's name.
        fixture.service().deleteConfigurationProfile(fixture.application(), fixture.profile());
        fixture.service().createConfigurationProfile(fixture.application(),
                Map.of("Name", fixture.profile(), "LocationUri", "hosted"));
        assertEquals(List.of(arn), fixture.arns().through("ConfigurationToken", next, region));
    }

    private static Fixture fixture(String region) {
        InMemoryStorageFactory storage = new InMemoryStorageFactory(ACCOUNT);
        RegionResolver regions = new RegionResolver(region, ACCOUNT);
        AppConfigService service = new AppConfigService(storage, regions);
        AppConfigDataService data = new AppConfigDataService(storage, service, new ObjectMapper(), regions);
        String app = service.createApplication(Map.of("Name", "orders")).getId();
        String env = service.createEnvironment(app, Map.of("Name", "production")).getId();
        String profile = service.createConfigurationProfile(app,
                Map.of("Name", "settings", "LocationUri", "hosted")).getId();
        return new Fixture(service, data, new AppConfigResourceArns(service, data, regions), app, env, profile);
    }

    private record Fixture(AppConfigService service, AppConfigDataService data, AppConfigResourceArns arns,
                           String application, String environment, String profile) {}
}
