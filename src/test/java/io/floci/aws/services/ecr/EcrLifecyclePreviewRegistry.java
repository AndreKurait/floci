package io.floci.aws.services.ecr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.floci.aws.core.common.Digests;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * A local registry protocol fixture, not a Docker registry substitute claim. Manifests are accepted
 * only after their actual content-addressed config and layer bytes have been uploaded.
 */
final class EcrLifecyclePreviewRegistry implements AutoCloseable {

    static final String OCI = "application/vnd.oci.image.manifest.v1+json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Map<String, byte[]>> blobs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> manifests = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> tags = new ConcurrentHashMap<>();
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();
    volatile Consumer<String> beforeRequest;
    final List<String> traffic = new CopyOnWriteArrayList<>();
    final List<String> failures = new CopyOnWriteArrayList<>();

    EcrLifecyclePreviewRegistry() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void delete(String repository) {
        blobs.remove(repository);
        manifests.remove(repository);
        tags.remove(repository);
    }

    int imageCount(String repository) {
        return manifests.getOrDefault(repository, Map.of()).size();
    }

    void seedBlob(String repository, byte[] body) {
        blobs.computeIfAbsent(repository, ignored -> new ConcurrentHashMap<>()).put(digest(body), body.clone());
    }

    Map<String, String> imageBodies(String repository) {
        return Map.copyOf(manifests.getOrDefault(repository, Map.of()));
    }

    Map<String, String> imageTags(String repository) {
        return Map.copyOf(tags.getOrDefault(repository, Map.of()));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        traffic.add(method + " " + path);
        try {
            if (beforeRequest != null) {
                beforeRequest.accept(method + " " + path);
            }
            if ("/v2/".equals(path)) {
                send(exchange, 200, "{}");
                return;
            }
            if ("/v2/_catalog".equals(path)) {
                send(exchange, 200, JSON.writeValueAsString(Map.of("repositories",
                        manifests.keySet().stream().sorted().toList())));
                return;
            }
            if (path.endsWith("/tags/list")) {
                String repository = path.substring(4, path.length() - "/tags/list".length());
                send(exchange, 200, JSON.writeValueAsString(Map.of("name", repository, "tags",
                        tags.getOrDefault(repository, Map.of()).keySet().stream().sorted().toList())));
                return;
            }
            int uploadAt = path.indexOf("/blobs/uploads/");
            if (uploadAt >= 0) {
                upload(exchange, path.substring(4, uploadAt), path.substring(uploadAt + 15));
                return;
            }
            int blobAt = path.indexOf("/blobs/");
            if (blobAt >= 0) {
                String repository = path.substring(4, blobAt);
                byte[] payload = blobs.getOrDefault(repository, Map.of()).get(path.substring(blobAt + 7));
                if (payload == null) {
                    send(exchange, 404, "{}");
                } else {
                    exchange.getResponseHeaders().set("Content-Length", String.valueOf(payload.length));
                    send(exchange, 200, "");
                }
                return;
            }
            int manifestAt = path.indexOf("/manifests/");
            if (manifestAt >= 0) {
                manifest(exchange, path.substring(4, manifestAt), path.substring(manifestAt + 11));
                return;
            }
            send(exchange, 404, "{}");
        } catch (RuntimeException failure) {
            failures.add(failure.toString());
            send(exchange, 500, "{\"errors\":[{\"code\":\"UNKNOWN\"}]}");
        }
    }

    private void upload(HttpExchange exchange, String repository, String id) throws IOException {
        String method = exchange.getRequestMethod();
        if ("POST".equals(method)) {
            String created = UUID.randomUUID().toString();
            uploads.put(created, new Upload(repository, new ByteArrayOutputStream()));
            exchange.getResponseHeaders().set("Location", "/v2/" + repository + "/blobs/uploads/" + created);
            send(exchange, 202, "");
            return;
        }
        Upload upload = uploads.get(id);
        if (upload == null || !upload.repository().equals(repository)) {
            send(exchange, 404, "{}");
            return;
        }
        if ("PATCH".equals(method)) {
            upload.bytes().write(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("Location", "/v2/" + repository + "/blobs/uploads/" + id);
            send(exchange, 202, "");
            return;
        }
        if ("PUT".equals(method)) {
            String supplied = URLDecoder.decode(exchange.getRequestURI().getRawQuery()
                    .substring("digest=".length()), StandardCharsets.UTF_8);
            byte[] content = upload.bytes().toByteArray();
            if (!digest(content).equals(supplied)) {
                throw new IllegalStateException("Uploaded blob digest does not match its actual bytes");
            }
            blobs.computeIfAbsent(repository, ignored -> new ConcurrentHashMap<>()).put(supplied, content);
            uploads.remove(id);
            exchange.getResponseHeaders().set("Docker-Content-Digest", supplied);
            send(exchange, 201, "");
            return;
        }
        if ("DELETE".equals(method)) {
            uploads.remove(id);
            send(exchange, 204, "");
            return;
        }
        send(exchange, 405, "{}");
    }

    private void manifest(HttpExchange exchange, String repository, String reference) throws IOException {
        String method = exchange.getRequestMethod();
        if ("PUT".equals(method)) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode document = JSON.readTree(body);
            if (document.path("schemaVersion").asInt() != 2 || !OCI.equals(document.path("mediaType").asText())) {
                throw new IllegalStateException("Fixture requires an OCI image manifest");
            }
            List<JsonNode> descriptors = new ArrayList<>();
            descriptors.add(document.path("config"));
            document.path("layers").forEach(descriptors::add);
            for (JsonNode descriptor : descriptors) {
                byte[] payload = blobs.getOrDefault(repository, Map.of()).get(descriptor.path("digest").asText());
                if (payload == null || payload.length != descriptor.path("size").asLong(-1)) {
                    throw new IllegalStateException("Manifest refers to an absent or incorrectly sized blob");
                }
            }
            String identifier = digest(body.getBytes(StandardCharsets.UTF_8));
            if (reference.startsWith("sha256:") && !identifier.equals(reference)) {
                throw new IllegalStateException("Manifest digest reference does not match its bytes");
            }
            manifests.computeIfAbsent(repository, ignored -> new ConcurrentHashMap<>()).put(identifier, body);
            if (!reference.equals(identifier)) {
                tags.computeIfAbsent(repository, ignored -> new ConcurrentHashMap<>()).put(reference, identifier);
            }
            exchange.getResponseHeaders().set("Docker-Content-Digest", identifier);
            send(exchange, 201, "");
            return;
        }
        String identifier = tags.getOrDefault(repository, Map.of()).getOrDefault(reference, reference);
        String body = manifests.getOrDefault(repository, Map.of()).get(identifier);
        if (body == null) {
            send(exchange, 404, "{}");
            return;
        }
        if ("DELETE".equals(method)) {
            manifests.get(repository).remove(identifier);
            Map<String, String> repositoryTags = tags.get(repository);
            if (repositoryTags != null) {
                repositoryTags.values().removeAll(Set.of(identifier));
            }
            send(exchange, 202, "");
            return;
        }
        exchange.getResponseHeaders().set("Docker-Content-Digest", identifier);
        exchange.getResponseHeaders().set("Content-Type", OCI);
        send(exchange, 200, body);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        if ("HEAD".equals(exchange.getRequestMethod()) || body.isEmpty()) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    static String digest(byte[] bytes) {
        return "sha256:" + Digests.sha256Hex(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private record Upload(String repository, ByteArrayOutputStream bytes) {}
}
