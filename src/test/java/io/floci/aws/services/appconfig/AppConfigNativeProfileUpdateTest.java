package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.appconfig.model.ConfigurationProfile;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigNativeProfileUpdateTest {
    private static final String ACCOUNT = "111111111111";
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"RetrievalRoleArn", "KmsKeyIdentifier"})
    void anUnsupportedProfileFieldRefusesAllOtherSuppliedChanges(String unsupported) {
        AppConfigService service = new AppConfigService(new InMemoryStorageFactory(ACCOUNT),
                new RegionResolver("us-east-1", ACCOUNT));
        String app = service.createApplication(Map.of("Name", "owned-profile-update")).getId();
        ConfigurationProfile profile = service.createConfigurationProfile(app, Map.of(
                "Name", "owned-profile", "Description", "original", "LocationUri", "hosted",
                "Validators", List.of(Map.of("Type", "JSON_SCHEMA", "Content", "{\"type\":\"integer\"}"))));
        service.createConfigurationProfile(app, Map.of(
                "Name", "owned-sibling", "Description", "sibling", "LocationUri", "hosted"));
        service.createHostedConfigurationVersion(app, profile.getId(),
                "1".getBytes(StandardCharsets.UTF_8), "application/json", "first");
        service.createHostedConfigurationVersion(app, profile.getId(),
                "2".getBytes(StandardCharsets.UTF_8), "application/json", "second");
        JsonNode before = snapshot(service, app, profile.getId());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("Name", "must-not-persist");
        request.put("Description", "must-not-persist");
        request.put("Validators", List.of());
        request.put(unsupported, "RetrievalRoleArn".equals(unsupported)
                ? "arn:aws:iam::111111111111:role/owned-monitor" : "alias/owned-profile");

        AwsException failure = assertThrows(AwsException.class,
                () -> service.updateConfigurationProfile(app, profile.getId(), request));

        assertEquals(400, failure.getHttpStatus());
        assertEquals("BadRequestException", failure.getErrorCode());
        assertTrue(failure.getMessage().contains(unsupported));
        assertEquals(before, snapshot(service, app, profile.getId()));
        assertEquals("owned-profile", profile.getName());
        assertEquals("original", profile.getDescription());
        assertEquals(1, profile.getValidators().size());
    }

    private static JsonNode snapshot(AppConfigService service, String app, String profile) {
        return JSON.valueToTree(Map.of(
                "application", service.getApplication(app),
                "profiles", service.listConfigurationProfiles(app),
                "versions", service.listHostedConfigurationVersions(app, profile),
                "first", service.getHostedConfigurationVersion(app, profile, 1),
                "second", service.getHostedConfigurationVersion(app, profile, 2)));
    }
}
