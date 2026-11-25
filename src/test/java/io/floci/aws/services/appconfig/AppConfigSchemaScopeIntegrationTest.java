package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RequestScopes;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AppConfigSchemaScopeIntegrationTest {
    @Inject
    AppConfigService service;

    @Inject
    ObjectMapper mapper;

    @Test
    void sameNamedSchemasAndHostedVersionsStayWithinTheirAccountAndRegion() {
        String name = "schema-scope-" + UUID.randomUUID();
        Map<Scope, Owned> owned = new LinkedHashMap<>();
        try {
            for (Scope scope : List.of(new Scope("111122223333", "us-east-1"),
                    new Scope("111122223333", "us-west-2"), new Scope("444455556666", "us-east-1"),
                    new Scope("111122223333", "cn-north-1"))) {
                RequestScopes.runAs(scope.account(), scope.region(), () -> {
                    String application = service.createApplication(Map.of("Name", name)).getId();
                    owned.put(scope, new Owned(application, null, null));
                    String value = scope.account() + "/" + scope.region();
                    String profile = service.createConfigurationProfile(application, Map.of(
                            "Name", name, "LocationUri", "hosted", "Validators",
                            List.of(Map.of("Type", "JSON_SCHEMA", "Content", "{\"enum\":[\"" + value + "\"]}")))).getId();
                    owned.put(scope, new Owned(application, profile, null));
                    int version = service.createHostedConfigurationVersion(application, profile,
                            ("\"" + value + "\"").getBytes(StandardCharsets.UTF_8), "application/json", name)
                            .getVersionNumber();
                    owned.put(scope, new Owned(application, profile, version));
                });
            }
            owned.forEach((scope, selected) -> RequestScopes.runAs(scope.account(), scope.region(), () -> {
                JsonNode before = snapshot(selected);
                service.validateConfiguration(name, name, selected.version().toString());
                owned.forEach((otherScope, other) -> {
                    if (!scope.equals(otherScope)) {
                        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                                () -> service.validateConfiguration(other.application(), other.profile(),
                                        other.version().toString())).getErrorCode());
                        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                                () -> service.validateConfiguration(selected.application(), other.profile(),
                                        selected.version().toString())).getErrorCode());
                    }
                });
                assertEquals(before, snapshot(selected));
            }));
        } finally {
            owned.forEach((scope, selected) -> RequestScopes.runAs(scope.account(), scope.region(), () -> {
                if (selected.version() != null) {
                    service.deleteHostedConfigurationVersion(selected.application(), selected.profile(), selected.version());
                    assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                            () -> service.getHostedConfigurationVersion(
                                    selected.application(), selected.profile(), selected.version())).getErrorCode());
                }
                if (selected.profile() != null) {
                    assertTrue(service.listHostedConfigurationVersions(selected.application(), selected.profile()).isEmpty());
                    service.deleteConfigurationProfile(selected.application(), selected.profile());
                }
                assertTrue(service.listConfigurationProfiles(selected.application()).isEmpty());
                service.deleteApplication(selected.application());
                assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                        () -> service.getApplication(selected.application())).getErrorCode());
            }));
        }
    }

    private JsonNode snapshot(Owned selected) {
        return mapper.valueToTree(Map.of(
                "application", service.getApplication(selected.application()),
                "profile", service.getConfigurationProfile(selected.application(), selected.profile()),
                "versions", service.listHostedConfigurationVersions(selected.application(), selected.profile()),
                "content", service.getHostedConfigurationVersion(
                        selected.application(), selected.profile(), selected.version())));
    }

    private record Scope(String account, String region) {}

    private record Owned(String application, String profile, Integer version) {}
}
