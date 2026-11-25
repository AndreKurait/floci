package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.appconfig.model.ConfigurationProfile;
import io.floci.aws.services.appconfig.model.Validator;
import io.floci.aws.testing.StorageFactories;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppConfigSchemaPersistenceTest {
    private static final String SCHEMA = "{\"type\":\"integer\",\"minimum\":1}";

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void validatorsSurviveProfileCopiesAndRestartAndLeaveNoStateAfterReset(String mode) {
        RegionResolver regions = new RegionResolver("us-east-1", "000000000000");
        String application;
        String profile;
        int valid;
        int invalid;
        StorageFactory first = StorageFactories.open(directory, mode, "000000000000");
        try {
            AppConfigService service = new AppConfigService(first, regions);
            application = service.createApplication(Map.of("Name", "schema-application")).getId();
            ConfigurationProfile original = service.createConfigurationProfile(application, Map.of(
                    "Name", "schema-profile", "LocationUri", "hosted",
                    "Validators", List.of(Map.of("Type", "JSON_SCHEMA", "Content", SCHEMA))));
            profile = original.getId();
            valid = service.createHostedConfigurationVersion(application, profile,
                    bytes("1"), "application/json", "valid").getVersionNumber();
            invalid = service.createHostedConfigurationVersion(application, profile,
                    bytes("0"), "application/json", "invalid").getVersionNumber();
            ConfigurationProfile updated = service.updateConfigurationProfile(application, profile, "renamed", "retained");
            assertNotSame(original, updated);
            assertEquals("schema-profile", original.getName());
            assertEquals(List.of(new Validator("JSON_SCHEMA", SCHEMA)), updated.getValidators());
            assertThrows(UnsupportedOperationException.class, () -> updated.getValidators().clear());
        } finally {
            first.shutdownAll();
        }

        StorageFactory restarted = StorageFactories.open(directory, mode, "000000000000");
        try {
            AppConfigService service = new AppConfigService(restarted, regions);
            ConfigurationProfile restored = service.getConfigurationProfile(application, profile);
            assertEquals("renamed", restored.getName());
            assertEquals("retained", restored.getDescription());
            assertEquals(List.of(new Validator("JSON_SCHEMA", SCHEMA)), restored.getValidators());
            ObjectMapper mapper = new ObjectMapper();
            JsonNode before = mapper.valueToTree(restored);
            service.validateConfiguration(application, profile, Integer.toString(valid));
            assertEquals("BadRequestException", assertThrows(AwsException.class,
                    () -> service.validateConfiguration(application, profile, Integer.toString(invalid))).getErrorCode());
            assertArrayEquals(bytes("1"), service.getHostedConfigurationVersion(application, profile, valid).getContent());
            assertArrayEquals(bytes("0"), service.getHostedConfigurationVersion(application, profile, invalid).getContent());
            assertEquals(before, mapper.valueToTree(service.getConfigurationProfile(application, profile)));

            restarted.clearAll();
            assertEquals(List.of(), service.listApplications());
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> service.validateConfiguration(application, profile, Integer.toString(valid))).getErrorCode());
            String replacement = service.createApplication(Map.of("Name", "schema-application")).getId();
            ConfigurationProfile unvalidated = service.createConfigurationProfile(replacement,
                    Map.of("Name", "renamed", "LocationUri", "hosted"));
            assertNull(unvalidated.getValidators());
            int textVersion = service.createHostedConfigurationVersion(replacement, unvalidated.getId(),
                    bytes("plain text"), "text/plain", null).getVersionNumber();
            service.validateConfiguration(replacement, unvalidated.getId(), Integer.toString(textVersion));
        } finally {
            restarted.shutdownAll();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
