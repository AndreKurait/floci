package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.appconfig.model.DeploymentStrategy;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AppConfigDeploymentTimingValidationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ACCOUNT = "000000000000";

    private final InMemoryStorageFactory storage = new InMemoryStorageFactory(ACCOUNT);
    private final RegionResolver regions = new RegionResolver("us-east-1", ACCOUNT);
    private final AppConfigAlarmMonitor alarms = mock(AppConfigAlarmMonitor.class);
    private final AppConfigService service = new AppConfigService(storage, regions, Clock.systemUTC(), alarms);

    @ParameterizedTest
    @ValueSource(strings = {"rollout-low", "rollout-high", "bake-low", "bake-high",
            "growth-low", "growth-high", "growth-nan", "growth-infinity", "growth-type"})
    void invalidTimingCannotPublishAStrategy(String invalid) {
        service.createDeploymentStrategy(Map.of("Name", "existing"));
        JsonNode before = MAPPER.valueToTree(service.listDeploymentStrategies());
        Map<String, Object> request = new HashMap<>(Map.of("Name", "refused"));
        switch (invalid) {
            case "rollout-low" -> request.put("DeploymentDurationInMinutes", -1);
            case "rollout-high" -> request.put("DeploymentDurationInMinutes", 1441);
            case "bake-low" -> request.put("FinalBakeTimeInMinutes", -1);
            case "bake-high" -> request.put("FinalBakeTimeInMinutes", 1441);
            case "growth-low" -> request.put("GrowthFactor", 0);
            case "growth-high" -> request.put("GrowthFactor", 101);
            case "growth-nan" -> request.put("GrowthFactor", Float.NaN);
            case "growth-infinity" -> request.put("GrowthFactor", Float.POSITIVE_INFINITY);
            case "growth-type" -> request.put("GrowthType", "UNKNOWN");
            default -> throw new IllegalArgumentException(invalid);
        }
        AwsException error = assertThrows(AwsException.class, () -> service.createDeploymentStrategy(request));
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(before, MAPPER.valueToTree(service.listDeploymentStrategies()));
        verifyNoInteractions(alarms);
    }

    @ParameterizedTest
    @CsvSource({"0,0,1,LINEAR", "1440,1440,100,EXPONENTIAL"})
    void supportedBoundaryValuesRemainCaptured(int rollout, int bake, float growth, String type) {
        DeploymentStrategy strategy = service.createDeploymentStrategy(Map.of("Name", "boundary",
                "DeploymentDurationInMinutes", rollout, "FinalBakeTimeInMinutes", bake,
                "GrowthFactor", growth, "GrowthType", type));
        assertEquals(rollout, strategy.getDeploymentDurationInMinutes());
        assertEquals(bake, strategy.getFinalBakeTimeInMinutes());
        assertEquals(growth, strategy.getGrowthFactor());
        assertEquals(type, strategy.getGrowthType());
        assertEquals(MAPPER.valueToTree(strategy), MAPPER.valueToTree(service.getDeploymentStrategy(strategy.getId())));
    }

    @Test
    void invalidStoredGrowthIsRefusedBeforeMonitorReadsOrDeploymentMutation() {
        String app = service.createApplication(Map.of("Name", "app")).getId();
        String env = service.createEnvironment(app, Map.of("Name", "env")).getId();
        String profile = service.createConfigurationProfile(app, Map.of("Name", "profile", "LocationUri", "hosted")).getId();
        service.createHostedConfigurationVersion(
                app, profile, "original".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        DeploymentStrategy strategy = service.createDeploymentStrategy(Map.of("Name", "strategy"));
        Map<String, Object> request = Map.of("ConfigurationProfileId", profile,
                "ConfigurationVersion", "1", "DeploymentStrategyId", strategy.getId());
        service.startDeployment(app, env, request);
        AppConfigStorage<DeploymentStrategy> strategies = new AppConfigStorage<>(
                storage, regions, "appconfig", "appconfig-strategies.json", new TypeReference<>() {});
        DeploymentStrategy invalid = MAPPER.convertValue(strategy, DeploymentStrategy.class);
        invalid.setGrowthFactor(0f);
        strategies.put(strategy.getId(), invalid);
        JsonNode before = MAPPER.valueToTree(service.listDeployments(app, env, 50, null));
        clearInvocations(alarms);
        AwsException error = assertThrows(AwsException.class, () -> service.startDeployment(app, env, request));
        assertEquals("BadRequestException", error.getErrorCode());
        assertEquals(before, MAPPER.valueToTree(service.listDeployments(app, env, 50, null)));
        assertEquals("1", service.getActiveVersion(env, profile));
        assertEquals(MAPPER.valueToTree(invalid), MAPPER.valueToTree(strategies.get(strategy.getId()).orElseThrow()));
        verifyNoInteractions(alarms);
    }
}
