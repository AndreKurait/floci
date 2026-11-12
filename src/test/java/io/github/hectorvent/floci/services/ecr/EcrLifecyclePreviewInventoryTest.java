package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Digests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class EcrLifecyclePreviewInventoryTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REPOSITORY = "000000000000/us-east-1/preview";
    private static final String ROOT = "/v2/" + REPOSITORY;
    private static final String GENERATION = "owned-generation";
    private static final Instant PUSHED = Instant.parse("2026-01-01T12:00:00Z");
    private HttpServer server;
    private EcrLifecyclePreviewInventory inventory;
    private String manifest;
    private String digest;
    private String configDigest;
    private Function<String, Reply> override;
    private final List<String> traffic = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        configDigest = digest("{}");
        manifest = JSON.writeValueAsString(Map.of("schemaVersion", 2, "mediaType", EcrLifecyclePreviewRegistry.OCI,
                "config", Map.of("digest", configDigest, "size", 2,
                        "mediaType", "application/vnd.oci.image.config.v1+json"), "layers", List.of()));
        digest = digest(manifest);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/", this::handle);
        server.start();
        inventory = new EcrLifecyclePreviewInventory(endpoint());
    }

    @AfterEach
    void cleanup() {
        inventory.close();
        server.stop(0);
    }

    @Test
    void liveTagsAndNativeDigestRowsProduceOneImmutableImageWithItsExactTimestamp() {
        List<EcrLifecyclePreviewEvaluator.Image> images = inventory.read(REPOSITORY, GENERATION, metadata(true, GENERATION));
        assertEquals(1, images.size());
        assertEquals(digest, images.getFirst().digest());
        assertEquals(PUSHED, images.getFirst().pushedAt());
        assertEquals(List.of("prod", "release"), images.getFirst().tags());
        assertThrows(UnsupportedOperationException.class, images::clear);
        assertThrows(UnsupportedOperationException.class, () -> images.getFirst().tags().clear());
        assertTrue(traffic.stream().allMatch(row -> row.startsWith("GET ") || row.startsWith("HEAD ")));
    }

    @Test
    void nativeDigestOnlyMetadataIsReadEvenWhenNoTagsExist() {
        override = path -> path.endsWith("/tags/list")
                ? ok("{\"name\":\"" + REPOSITORY + "\",\"tags\":[]}") : null;
        List<EcrLifecyclePreviewEvaluator.Image> images = inventory.read(REPOSITORY, GENERATION, metadata(true, GENERATION));
        assertEquals(1, images.size());
        assertTrue(images.getFirst().tags().isEmpty());
        assertTrue(traffic.contains("GET " + ROOT + "/manifests/" + digest));
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-metadata", "unknown-time", "old-generation", "no-generation"})
    void incompleteProvenanceNeverProducesACompletedInventory(String kind) {
        List<EcrLifecyclePreviewInventory.Metadata> records = switch (kind) {
            case "no-metadata" -> List.of();
            case "unknown-time" -> metadata(false, GENERATION);
            case "old-generation" -> metadata(true, "old-generation");
            default -> metadata(true, GENERATION);
        };
        assertUnavailable(() -> inventory.read(REPOSITORY, "no-generation".equals(kind) ? null : GENERATION, records));
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign-name", "missing-name", "malformed-tags", "duplicate-tags", "tags-500",
            "digest-404", "digest-header", "blob-404", "blob-size", "schema-overflow", "index", "trailing-json"})
    void malformedOrIncompleteRegistryResponsesFailClosed(String kind) throws Exception {
        if ("schema-overflow".equals(kind)) {
            manifest = manifest.replace("\"schemaVersion\":2", "\"schemaVersion\":4294967298");
            digest = digest(manifest);
        } else if ("index".equals(kind)) {
            manifest = JSON.writeValueAsString(Map.of("schemaVersion", 2, "manifests", List.of()));
            digest = digest(manifest);
        }
        override = path -> {
            if (path.endsWith("/tags/list")) {
                return switch (kind) {
                    case "foreign-name" -> ok("{\"name\":\"another/repository\",\"tags\":[]}");
                    case "missing-name" -> ok("{\"tags\":[]}");
                    case "malformed-tags" -> ok("{\"name\":\"" + REPOSITORY + "\",\"tags\":{}}");
                    case "duplicate-tags" -> ok("{\"name\":\"" + REPOSITORY + "\",\"tags\":[\"prod\",\"prod\"]}");
                    case "tags-500" -> new Reply(500, "{}", Map.of());
                    case "trailing-json" -> ok("{\"name\":\"" + REPOSITORY + "\",\"tags\":[]}{}");
                    default -> null;
                };
            }
            if (path.endsWith("/manifests/" + digest) && "digest-404".equals(kind)) {
                return new Reply(404, "{}", Map.of());
            }
            if (path.contains("/manifests/") && "digest-header".equals(kind)) {
                return new Reply(200, manifest, Map.of("Docker-Content-Digest", "sha256:" + "0".repeat(64),
                        "Content-Type", EcrLifecyclePreviewRegistry.OCI));
            }
            if (path.contains("/blobs/")) {
                if ("blob-404".equals(kind)) {
                    return new Reply(404, "", Map.of());
                }
                if ("blob-size".equals(kind)) {
                    return new Reply(200, "", Map.of("Content-Length", "3"));
                }
            }
            return null;
        };
        assertUnavailable(() -> inventory.read(REPOSITORY, GENERATION, metadata(true, GENERATION)));
    }

    @Test
    void catalogConsumesEveryPageAndExplicitlyRefusesItsBound() {
        override = path -> switch (path) {
            case "/v2/_catalog" -> new Reply(200, "{\"repositories\":[\"first\"]}",
                    Map.of("Link", "</v2/_catalog?last=first>; rel=\"next\""));
            case "/v2/_catalog?last=first" -> ok("{\"repositories\":[\"second\"]}");
            default -> null;
        };
        assertEquals(List.of("first", "second"), inventory.catalog());
        try (EcrLifecyclePreviewInventory bounded = new EcrLifecyclePreviewInventory(endpoint(), 1)) {
            assertUnavailable(bounded::catalog);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cycle", "foreign-path", "foreign-host", "malformed-link", "duplicate-page-entry"})
    void paginationCannotSilentlySkipOrAdoptAnotherEndpoint(String kind) {
        override = path -> {
            if (path.equals("/v2/_catalog?next")) {
                return ok("{\"repositories\":[\"same\"]}");
            }
            String link = switch (kind) {
                case "cycle" -> "</v2/_catalog>; rel=next";
                case "foreign-path" -> "</v2/elsewhere>; rel=next";
                case "foreign-host" -> "<http://invalid.example/v2/_catalog>; rel=next";
                case "malformed-link" -> "invalid";
                default -> "</v2/_catalog?next>; rel=next";
            };
            return new Reply(200, "{\"repositories\":[\"same\"]}", Map.of("Link", link));
        };
        assertUnavailable(inventory::catalog);
        assertTrue(traffic.stream().allMatch(row -> row.contains("/v2/_catalog")));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().toString();
        traffic.add(exchange.getRequestMethod() + " " + path);
        Reply reply = override == null ? null : override.apply(path);
        if (reply == null) {
            if (path.equals("/v2/_catalog")) {
                reply = ok("{\"repositories\":[\"" + REPOSITORY + "\"]}");
            } else if (path.endsWith("/tags/list")) {
                reply = ok("{\"name\":\"" + REPOSITORY + "\",\"tags\":[\"release\",\"prod\"]}");
            } else if (path.contains("/manifests/")) {
                reply = new Reply(200, manifest, Map.of("Docker-Content-Digest", digest,
                        "Content-Type", EcrLifecyclePreviewRegistry.OCI));
            } else if (path.endsWith("/blobs/" + configDigest)) {
                reply = new Reply(200, "", Map.of("Content-Length", "2"));
            } else {
                reply = new Reply(404, "{}", Map.of());
            }
        }
        reply.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(reply.status(), -1);
        } else {
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private List<EcrLifecyclePreviewInventory.Metadata> metadata(boolean nativeFirstPush, String generation) {
        return List.of(new EcrLifecyclePreviewInventory.Metadata(digest, PUSHED, generation, nativeFirstPush));
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String digest(String text) {
        return "sha256:" + Digests.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Reply ok(String body) {
        return new Reply(200, body, Map.of());
    }

    private static void assertUnavailable(Runnable action) {
        assertEquals("ServerException", assertThrows(AwsException.class, action::run).getErrorCode());
    }

    private record Reply(int status, String body, Map<String, String> headers) {}
}
