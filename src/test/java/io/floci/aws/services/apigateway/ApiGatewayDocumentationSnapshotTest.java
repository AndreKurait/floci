package io.floci.aws.services.apigateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiGatewayDocumentationSnapshotTest {
    @TempDir
    Path directory;

    @Test
    void ignoresUnrelatedDirectoriesBeforeReadingTheirMetadata() throws Exception {
        Path unrelated = Files.createDirectory(directory.resolve("iam-users"));
        Files.writeString(unrelated.resolve("iam-users.json.tmp"), "pending");
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(unrelated);
        Files.setPosixFilePermissions(unrelated, Set.of());
        try {
            assertTrue(ApiGatewayDocumentationVersionTagIamIntegrationTest.persistedRecords(directory).isEmpty());
        } finally {
            Files.setPosixFilePermissions(unrelated, permissions);
        }
    }

    @Test
    void comparesDurableFilesAndExcludesAtomicWriteTemporaryFiles() throws Exception {
        Path json = directory.resolve("apigateway-restapis.json");
        byte[] original = {0, 1, 2, -1};
        Files.write(json, original);
        Files.writeString(directory.resolve("apigateway-stages.wal"), "journal");
        Files.writeString(directory.resolve("apigateway-stages-snapshot.json"), "snapshot");
        Files.writeString(directory.resolve("apigateway-restapis.json.tmp"), "pending");
        Files.writeString(directory.resolve("iam-users.json"), "unrelated");
        Map<String, JsonNode> before = ApiGatewayDocumentationVersionTagIamIntegrationTest.persistedRecords(directory);
        assertEquals(Set.of("file:apigateway-restapis.json", "file:apigateway-stages.wal",
                "file:apigateway-stages-snapshot.json"), before.keySet());
        assertEquals(Base64.getEncoder().encodeToString(original), before.get("file:apigateway-restapis.json").asText());
        Files.writeString(directory.resolve("iam-users.json"), "changed");
        Files.delete(directory.resolve("apigateway-restapis.json.tmp"));
        assertEquals(before, ApiGatewayDocumentationVersionTagIamIntegrationTest.persistedRecords(directory));
        Files.writeString(json, "changed");
        assertNotEquals(before, ApiGatewayDocumentationVersionTagIamIntegrationTest.persistedRecords(directory));
        Files.delete(json);
        assertEquals(2, ApiGatewayDocumentationVersionTagIamIntegrationTest.persistedRecords(directory).size());
    }
}
