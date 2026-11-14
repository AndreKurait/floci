package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend.AccountEntry;
import io.github.hectorvent.floci.services.appconfig.model.Deployment;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class AppConfigDeploymentScanTest {
    private static final String ACCOUNT = "000000000000";
    private static final String OTHER = "771661204011";
    private static final String REGION = "us-east-1";
    private static final Instant START = Instant.parse("2026-11-13T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void enumerationRetainsTheStoredAccountAndRegionForTheSameLogicalKey() {
        AccountAwareStorageBackend<Deployment> backing = AccountAwareStorageBackend.inMemory(ACCOUNT);
        InMemoryStorageFactory factory = new InMemoryStorageFactory(ACCOUNT)
                .withStore("appconfig-deployments.json", backing);
        AppConfigStorage<Deployment> view = new AppConfigStorage<>(factory, new RegionResolver(REGION, ACCOUNT),
                "appconfig", "appconfig-deployments.json", new TypeReference<>() {});
        for (String account : List.of(ACCOUNT, OTHER)) {
            backing.putForAccount(account, REGION + "/shared", active());
        }
        backing.putForAccount(OTHER, "cn-north-1/shared", active());
        backing.putForAccount(ACCOUNT, "legacy", active());
        backing.putForAccount(ACCOUNT, "/empty-region", active());
        backing.putForAccount(ACCOUNT, "us-east-1/nested/key", active());
        Deployment completed = active();
        completed.setState("COMPLETE");
        backing.putForAccount(ACCOUNT, "us-east-1/completed", completed);
        assertEquals(Set.of(
                new AppConfigStorage.ScopedKey(ACCOUNT, REGION, "shared"),
                new AppConfigStorage.ScopedKey(OTHER, REGION, "shared"),
                new AppConfigStorage.ScopedKey(OTHER, "cn-north-1", "shared"),
                new AppConfigStorage.ScopedKey(ACCOUNT, REGION, "legacy")),
                view.scopedKeys(value -> "BAKING".equals(value.getState())));
        assertTrue(backing.getForAccount(OTHER, "cn-north-1/shared").isPresent());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void aCapturedActiveEntryCannotResurrectADeletedOrCompletedStoredOwner(boolean completedReplacement) {
        Fixture fixture = fixture();
        Deployment captured = fixture.deployment();
        Deployment replacement = MAPPER.convertValue(captured, Deployment.class);
        replacement.setState("COMPLETE");
        replacement.setCompletedAt(START.toString());
        replacement.setDescription("replacement must remain untouched");
        doAnswer(invocation -> {
            List<AccountEntry<Deployment>> enumerated = (List<AccountEntry<Deployment>>) invocation.callRealMethod();
            if (completedReplacement) {
                fixture.backing().putForAccount(ACCOUNT, fixture.key(), replacement);
            } else {
                fixture.backing().deleteForAccount(ACCOUNT, fixture.key());
            }
            return enumerated;
        }).when(fixture.backing()).scanAllAccountEntries(any());
        when(fixture.clock().instant()).thenReturn(START.plusSeconds(120));
        fixture.service().advanceDeployments();
        if (completedReplacement) {
            assertEquals(MAPPER.valueToTree(replacement), MAPPER.valueToTree(
                    fixture.backing().getForAccount(ACCOUNT, fixture.key()).orElseThrow()));
        } else {
            assertTrue(fixture.backing().getForAccount(ACCOUNT, fixture.key()).isEmpty());
        }
        assertEquals("BAKING", captured.getState());
    }

    @Test
    void anInvalidStoredIdentityDoesNotPreventAnotherOwnerFromCompletingAndCanBeRetried() {
        Fixture fixture = fixture();
        Deployment malformed = MAPPER.convertValue(fixture.deployment(), Deployment.class);
        malformed.setDeploymentNumber(99);
        String incorrectKey = REGION + "/different::owner::99";
        fixture.backing().putForAccount(ACCOUNT, incorrectKey, malformed);
        when(fixture.clock().instant()).thenReturn(START.plusSeconds(120));
        fixture.service().advanceDeployments();
        assertEquals("COMPLETE", fixture.backing().getForAccount(ACCOUNT, fixture.key()).orElseThrow().getState());
        assertEquals("BAKING", fixture.backing().getForAccount(ACCOUNT, incorrectKey).orElseThrow().getState());
        fixture.backing().deleteForAccount(ACCOUNT, incorrectKey);
        String correctedKey = REGION + "/" + malformed.getApplicationId() + "::"
                + malformed.getEnvironmentId() + "::" + malformed.getDeploymentNumber();
        fixture.backing().putForAccount(ACCOUNT, correctedKey, malformed);
        fixture.service().advanceDeployments();
        assertEquals("COMPLETE", fixture.backing().getForAccount(ACCOUNT, correctedKey).orElseThrow().getState());
        assertEquals(START.plusSeconds(60).toString(),
                fixture.backing().getForAccount(ACCOUNT, correctedKey).orElseThrow().getCompletedAt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resetPauseRejectsDeploymentsWithoutLambdaValidationAndResumes(boolean schema) {
        Fixture fixture = fixture(schema);
        Deployment prior = fixture.deployment();
        Map<String, Object> request = Map.of("ConfigurationProfileId", prior.getConfigurationProfileId(),
                "ConfigurationVersion", "1", "DeploymentStrategyId", prior.getDeploymentStrategyId());
        fixture.service().beforeReset();
        try {
            AwsException error = assertThrows(AwsException.class, () -> fixture.service().startDeployment(
                    prior.getApplicationId(), prior.getEnvironmentId(), request));
            assertEquals("BadRequestException", error.getErrorCode());
            assertTrue(error.getMessage().contains("reset"));
            assertEquals(1, fixture.backing().scanAllAccountEntries(value -> true).size());
            assertEquals(MAPPER.valueToTree(prior), MAPPER.valueToTree(
                    fixture.backing().getForAccount(ACCOUNT, fixture.key()).orElseThrow()));
        } finally {
            fixture.service().afterReset();
        }
        assertEquals(2, fixture.service().startDeployment(prior.getApplicationId(),
                prior.getEnvironmentId(), request).getDeploymentNumber());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void resetPausePreventsCapturedScansFromWritingAndResumes(boolean pauseAfterEnumeration) {
        Fixture fixture = fixture();
        when(fixture.clock().instant()).thenReturn(START.plusSeconds(120));
        if (pauseAfterEnumeration) {
            doAnswer(invocation -> {
                Object captured = invocation.callRealMethod();
                fixture.service().beforeReset();
                return captured;
            }).when(fixture.backing()).scanAllAccountEntries(any());
        } else {
            fixture.service().beforeReset();
        }
        try {
            fixture.service().advanceDeployments();
            assertEquals("BAKING", fixture.backing().getForAccount(ACCOUNT, fixture.key()).orElseThrow().getState());
        } finally {
            doCallRealMethod().when(fixture.backing()).scanAllAccountEntries(any());
            fixture.service().afterReset();
        }
        fixture.service().advanceDeployments();
        assertEquals("COMPLETE", fixture.backing().getForAccount(ACCOUNT, fixture.key()).orElseThrow().getState());
    }

    private static Fixture fixture() {
        return fixture(false);
    }

    private static Fixture fixture(boolean schema) {
        AccountAwareStorageBackend<Deployment> backing = spy(AccountAwareStorageBackend.<Deployment>inMemory(ACCOUNT));
        InMemoryStorageFactory storage = new InMemoryStorageFactory(ACCOUNT)
                .withStore("appconfig-deployments.json", backing);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(START);
        AppConfigService service = new AppConfigService(storage, new RegionResolver(REGION, ACCOUNT),
                clock, mock(AppConfigAlarmMonitor.class), null, null);
        String app = service.createApplication(Map.of("Name", "app")).getId();
        String env = service.createEnvironment(app, Map.of("Name", "environment")).getId();
        String profile = service.createConfigurationProfile(app,
                Map.of("Name", "profile", "LocationUri", "hosted", "Validators", schema
                        ? List.of(Map.of("Type", "JSON_SCHEMA", "Content", "{\"type\":\"string\"}"))
                        : List.of())).getId();
        service.createHostedConfigurationVersion(app, profile, "\"one\"".getBytes(StandardCharsets.UTF_8), "text/plain", null);
        String strategy = service.createDeploymentStrategy(Map.of("Name", "bake", "FinalBakeTimeInMinutes", 1)).getId();
        Deployment deployment = service.startDeployment(app, env, Map.of("ConfigurationProfileId", profile,
                "ConfigurationVersion", "1", "DeploymentStrategyId", strategy));
        String key = REGION + "/" + app + "::" + env + "::" + deployment.getDeploymentNumber();
        return new Fixture(service, backing, clock, deployment, key);
    }

    private static Deployment active() {
        Deployment deployment = new Deployment();
        deployment.setState("BAKING");
        return deployment;
    }

    private record Fixture(AppConfigService service, AccountAwareStorageBackend<Deployment> backing,
                           Clock clock, Deployment deployment, String key) {}
}
