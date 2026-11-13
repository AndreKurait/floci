package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.DocumentationVersion;
import io.github.hectorvent.floci.services.apigateway.model.Stage;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Flat legacy Stage rows and disjoint documentation snapshots in one atomic backing store.
 * ApiGatewayService's monitor coordinates the parent lifetime, capture and all stage writers.
 */
final class ApiGatewayStageDocumentationStore {
    static final String FILE = "apigateway-stages.json";
    private static final String VERSION_PREFIX = "/documentation-versions/";
    private static final String VERSION_MARKER = "_documentationVersionRecord";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AccountAwareStorageBackend<JsonNode> rows;

    ApiGatewayStageDocumentationStore(StorageFactory factory) {
        rows = factory.create("apigateway", FILE, new TypeReference<>() {});
    }

    Optional<Stage> get(String key) {
        return readStage(key).map(node -> JSON.convertValue(node, Stage.class));
    }

    void put(String key, Stage stage) {
        JsonNode value = JSON.valueToTree(stage);
        requireStage(key, value);
        migrateStage(key);
        rows.putAllAtomically(Map.of(key, value));
    }

    void delete(String key) {
        migrateStage(key);
        rows.deleteAllAtomically(Set.of(key));
    }

    List<Stage> scan(Predicate<String> filter) {
        return keys().stream().filter(filter).map(this::get).flatMap(Optional::stream).toList();
    }

    Set<String> keys() {
        Set<String> candidates = new LinkedHashSet<>();
        for (AccountAwareStorageBackend.AccountEntry<JsonNode> entry
                : rows.scanAllAccountEntries(key -> !key.startsWith(VERSION_PREFIX))) {
            if (rows.accountId().equals(entry.accountId())) {
                candidates.add(entry.key());
            }
        }
        candidates.removeIf(key -> readStage(key).isEmpty());
        return Set.copyOf(candidates);
    }

    boolean hasVersion(String region, String apiId, String version) {
        return rows.getForAccount(rows.accountId(), versionKey(region, apiId, version)).isPresent();
    }

    Optional<DocumentationVersion> version(String region, String apiId, String version) {
        return rows.getForAccount(rows.accountId(), versionKey(region, apiId, version))
                .filter(node -> isVersion(node, region, apiId, version))
                .map(node -> JSON.convertValue(node, DocumentationVersion.class));
    }

    List<DocumentationVersion> versions(String region, String apiId) {
        String prefix = versionPrefix(region, apiId);
        return rows.keysForAccount(rows.accountId()).stream().filter(key -> key.startsWith(prefix))
                .map(key -> rows.getForAccount(rows.accountId(), key)
                        .filter(node -> node.path("version").isTextual())
                        .filter(node -> key.equals(versionKey(region, apiId, node.path("version").textValue())))
                        .filter(node -> isVersion(node, region, apiId, node.path("version").textValue())))
                .flatMap(Optional::stream).map(node -> JSON.convertValue(node, DocumentationVersion.class)).toList();
    }

    void publish(DocumentationVersion version, String stageKey, Stage stage) {
        if (!rows.accountId().equals(version.accountId())) {
            throw new IllegalArgumentException("Documentation snapshot account does not match the store scope");
        }
        ObjectNode value = JSON.valueToTree(version);
        value.put(VERSION_MARKER, true);
        Map<String, JsonNode> replacements = new LinkedHashMap<>();
        replacements.put(versionKey(version.region(), version.apiId(), version.version()), value);
        if (stage != null) {
            JsonNode stageValue = JSON.valueToTree(stage);
            requireStage(stageKey, stageValue);
            // An unchanged owned legacy stage can move representation before a refused batch.
            // Every input, parent and optional-stage check has already completed in the service.
            migrateStage(stageKey);
            replacements.put(stageKey, stageValue);
        }
        rows.applyAtomically(replacements, Set.of());
    }

    void deleteVersion(String region, String apiId, String version) {
        rows.deleteAllAtomically(Set.of(versionKey(region, apiId, version)));
    }

    void deleteApi(String region, String apiId) {
        String stagePrefix = region + "::" + apiId + "::";
        for (String key : keys()) {
            if (key.startsWith(stagePrefix)) {
                migrateStage(key);
            }
        }
        String versionPrefix = versionPrefix(region, apiId);
        Set<String> deletions = new LinkedHashSet<>(rows.keysForAccount(rows.accountId()));
        deletions.removeIf(key -> !key.startsWith(stagePrefix) && !key.startsWith(versionPrefix));
        rows.deleteAllAtomically(deletions);
    }

    private Optional<JsonNode> readStage(String key) {
        if (!stageKey(key)) {
            return Optional.empty();
        }
        Optional<JsonNode> scoped = rows.getForAccount(rows.accountId(), key);
        if (scoped.isPresent()) {
            return scoped.filter(node -> isStage(key, node)).map(JsonNode::deepCopy);
        }
        return rows.getLegacyForAccount(rows.accountId(), key)
                .filter(node -> isStage(key, node)).map(JsonNode::deepCopy);
    }

    private void migrateStage(String key) {
        if (!stageKey(key)) {
            throw new IllegalArgumentException("Invalid stage storage key");
        }
        Optional<JsonNode> scoped = rows.getForAccount(rows.accountId(), key);
        if (scoped.isPresent() && !isStage(key, scoped.get())) {
            throw new AwsException("ConflictException", "Stage storage identity does not match its key", 409);
        }
        Optional<JsonNode> legacy = rows.getLegacyForAccount(rows.accountId(), key);
        if (legacy.isEmpty()) {
            return;
        }
        if (!isStage(key, legacy.get())) {
            throw new AwsException("ConflictException", "Legacy stage storage identity does not match its key", 409);
        }
        JsonNode expected = legacy.get().deepCopy();
        rows.migrateLegacyEntries(rows.accountId(), key::equals, ignored -> key,
                node -> isStage(key, node) && node.equals(expected));
    }

    private boolean isVersion(JsonNode node, String region, String apiId, String version) {
        return node.isObject() && node.path(VERSION_MARKER).isBoolean() && node.path(VERSION_MARKER).booleanValue()
                && rows.accountId().equals(node.path("accountId").asText())
                && region.equals(node.path("region").asText()) && apiId.equals(node.path("apiId").asText())
                && version.equals(node.path("version").asText()) && node.path("parts").isArray();
    }

    private static void requireStage(String key, JsonNode node) {
        if (!isStage(key, node)) {
            throw new IllegalArgumentException("Stage value does not match its storage key");
        }
    }

    private static boolean isStage(String key, JsonNode node) {
        return stageKey(key) && node.isObject() && !node.has(VERSION_MARKER)
                && node.path("stageName").isTextual() && key.split("::", 3)[2].equals(node.path("stageName").textValue());
    }

    private static boolean stageKey(String key) {
        return key != null && !key.startsWith("/") && !AccountAwareStorageBackend.hasAccountPrefix(key)
                && key.split("::", 3).length == 3;
    }

    private static String versionKey(String region, String apiId, String version) {
        return versionPrefix(region, apiId) + encode(version);
    }

    private static String versionPrefix(String region, String apiId) {
        return VERSION_PREFIX + encode(region) + "/" + encode(apiId) + "/";
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
