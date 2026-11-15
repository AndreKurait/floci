package io.github.hectorvent.floci.services.ecr;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Digests;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Complete, read-only registry reads. Callers hold the physical manifest semaphore. */
final class EcrLifecyclePreviewInventory implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> IMAGE_TYPES = Set.of("application/vnd.oci.image.manifest.v1+json",
            "application/vnd.docker.distribution.manifest.v2+json");
    private static final String ACCEPT = String.join(", ", IMAGE_TYPES)
            + ", application/vnd.oci.image.index.v1+json"
            + ", application/vnd.docker.distribution.manifest.list.v2+json";
    private static final Pattern DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern NEXT = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"?next\"?\\s*");
    private final URI base;
    private final HttpClient http;
    private final int pageLimit;

    EcrLifecyclePreviewInventory(String endpoint) {
        this(endpoint, 10_000);
    }

    EcrLifecyclePreviewInventory(String endpoint, int pageLimit) {
        base = URI.create(endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.pageLimit = pageLimit;
    }

    record Metadata(String digest, Instant pushedAt, String generation, boolean nativeFirstPush) {}

    List<String> catalog() {
        return pages("/v2/_catalog", "repositories", false);
    }

    List<EcrLifecyclePreviewEvaluator.Image> read(String repository, String generation, List<Metadata> metadata) {
        requireRepository(repository);
        List<String> listedTags = pages("/v2/" + repository + "/tags/list", "tags", true);
        Map<String, Set<String>> tagsByDigest = new TreeMap<>();
        Map<String, Manifest> observed = new HashMap<>();
        for (String tag : listedTags) {
            if (!tag.matches("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}")) {
                throw unavailable("Registry returned an unsupported tag");
            }
            Manifest manifest = manifest(repository, tag);
            Manifest previous = observed.putIfAbsent(manifest.digest(), manifest);
            if (previous != null && !previous.body().equals(manifest.body())) {
                throw unavailable("Registry returned inconsistent bytes for a manifest digest");
            }
            tagsByDigest.computeIfAbsent(manifest.digest(), ignored -> new TreeSet<>()).add(tag);
        }
        Map<String, Metadata> byDigest = new LinkedHashMap<>();
        for (Metadata image : metadata) {
            if (image.digest() == null || !DIGEST.matcher(image.digest()).matches()
                    || byDigest.putIfAbsent(image.digest(), image) != null) {
                throw unavailable("Image metadata contains an invalid or repeated digest");
            }
        }
        Set<String> digests = new TreeSet<>(tagsByDigest.keySet());
        digests.addAll(byDigest.keySet());
        List<EcrLifecyclePreviewEvaluator.Image> images = new ArrayList<>();
        for (String digest : digests) {
            Metadata recorded = byDigest.get(digest);
            if (generation == null || recorded == null || !recorded.nativeFirstPush() || recorded.pushedAt() == null
                    || !generation.equals(recorded.generation())) {
                throw unavailable("Preview requires a verified native first-push timestamp for every known image; "
                        + "legacy and direct-Docker timestamps cannot be inferred");
            }
            Manifest manifest = manifest(repository, digest);
            if (!digest.equals(manifest.digest())) {
                throw unavailable("Registry digest lookup returned a different manifest");
            }
            Manifest tagged = observed.get(digest);
            if (tagged != null && !tagged.body().equals(manifest.body())) {
                throw unavailable("Tag and digest lookups returned different manifest bytes");
            }
            verifyImage(repository, manifest);
            images.add(new EcrLifecyclePreviewEvaluator.Image(digest, recorded.pushedAt(),
                    List.copyOf(tagsByDigest.getOrDefault(digest, Set.of()))));
        }
        return List.copyOf(images);
    }

    private List<String> pages(String path, String member, boolean missingIsEmpty) {
        URI next = base.resolve(path);
        Set<URI> visited = new HashSet<>();
        Set<String> values = new HashSet<>();
        List<String> result = new ArrayList<>();
        while (next != null) {
            if (visited.size() >= pageLimit || !visited.add(next)) {
                throw unavailable("Registry pagination did not complete within its bound");
            }
            HttpResponse<String> response = get(next, null);
            if (response.statusCode() == 404 && missingIsEmpty && visited.size() == 1) {
                return List.of();
            }
            requireSuccess(response, "Registry inventory page");
            JsonNode document = parse(response.body());
            JsonNode rows = document.get(member);
            if (!document.isObject() || rows == null || (!rows.isArray() && !(missingIsEmpty && rows.isNull()))) {
                throw unavailable("Registry inventory page has no valid " + member + " member");
            }
            if ("tags".equals(member)) {
                String expectedName = path.substring(4, path.length() - "/tags/list".length());
                if (!document.path("name").isTextual() || !expectedName.equals(document.path("name").textValue())) {
                    throw unavailable("Registry tag page does not identify the requested repository");
                }
            }
            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    if (!row.isTextual() || row.textValue().isEmpty() || !values.add(row.textValue())) {
                        throw unavailable("Registry inventory pages contain invalid or repeated entries");
                    }
                    result.add(row.textValue());
                }
            }
            next = continuation(response, path);
        }
        return List.copyOf(result);
    }

    private URI continuation(HttpResponse<?> response, String expectedPath) {
        List<String> links = response.headers().allValues("Link");
        if (links.isEmpty()) {
            return null;
        }
        if (links.size() != 1) {
            throw unavailable("Registry returned ambiguous pagination links");
        }
        Matcher match = NEXT.matcher(links.getFirst().strip());
        if (!match.matches()) {
            throw unavailable("Registry returned an unsupported pagination link");
        }
        URI next;
        try {
            next = response.uri().resolve(URI.create(match.group(1)));
        } catch (IllegalArgumentException failure) {
            throw unavailable("Registry returned a malformed pagination link");
        }
        if (!base.getScheme().equals(next.getScheme()) || !base.getHost().equals(next.getHost())
                || base.getPort() != next.getPort() || next.getUserInfo() != null || next.getFragment() != null
                || !expectedPath.equals(next.getRawPath())) {
            throw unavailable("Registry pagination left the expected inventory endpoint");
        }
        return next;
    }

    private Manifest manifest(String repository, String reference) {
        HttpResponse<String> response = get(base.resolve("/v2/" + repository + "/manifests/" + reference), ACCEPT);
        requireSuccess(response, "Known image manifest");
        String digest = response.headers().firstValue("Docker-Content-Digest").orElse("");
        if (!DIGEST.matcher(digest).matches()
                || !digest.equals("sha256:" + Digests.sha256Hex(response.body().getBytes(StandardCharsets.UTF_8)))) {
            throw unavailable("Registry manifest has no matching content digest");
        }
        String mediaType = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip();
        return new Manifest(digest, response.body(), mediaType);
    }

    private void verifyImage(String repository, Manifest manifest) {
        JsonNode image = parse(manifest.body());
        if (!IMAGE_TYPES.contains(manifest.mediaType()) || !image.isObject()
                || !image.path("schemaVersion").isIntegralNumber() || !image.path("schemaVersion").canConvertToInt()
                || image.path("schemaVersion").intValue() != 2
                || image.has("manifests") || image.has("subject") || image.has("artifactType")) {
            throw unavailable("Lifecycle preview does not support manifest lists, indexes or reference artifacts");
        }
        if (image.has("mediaType") && !manifest.mediaType().equals(image.path("mediaType").asText())) {
            throw unavailable("Registry manifest media types disagree");
        }
        JsonNode layers = image.get("layers");
        if (layers == null || !layers.isArray()) {
            throw unavailable("Image manifest has no layer descriptors");
        }
        verifyBlob(repository, image.get("config"));
        for (JsonNode descriptor : layers) {
            verifyBlob(repository, descriptor);
        }
    }

    private void verifyBlob(String repository, JsonNode descriptor) {
        if (descriptor == null || !descriptor.isObject() || !descriptor.path("digest").isTextual()
                || !DIGEST.matcher(descriptor.path("digest").textValue()).matches()
                || !descriptor.path("size").isIntegralNumber() || !descriptor.path("size").canConvertToLong()
                || descriptor.path("size").longValue() < 0) {
            throw unavailable("Image manifest has an invalid blob descriptor");
        }
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/v2/" + repository + "/blobs/"
                        + descriptor.path("digest").textValue()))
                .timeout(Duration.ofSeconds(10)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200
                    || response.headers().firstValueAsLong("Content-Length").orElse(-1)
                    != descriptor.path("size").longValue()) {
                throw unavailable("Image manifest references an absent or incorrectly sized blob");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("Registry inventory read was interrupted");
        } catch (IOException | IllegalArgumentException failure) {
            throw unavailable("Registry blob inventory could not be read completely");
        }
    }

    private HttpResponse<String> get(URI uri, String accept) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
        if (accept != null) {
            request.header("Accept", accept);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable("Registry inventory read was interrupted");
        } catch (IOException failure) {
            throw unavailable("Backing registry inventory is unavailable");
        }
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null) {
                throw unavailable("Registry inventory returned an empty document");
            }
            return node;
        } catch (IOException failure) {
            throw unavailable("Registry inventory returned malformed JSON");
        }
    }

    private static void requireSuccess(HttpResponse<?> response, String what) {
        if (response.statusCode() != 200) {
            throw unavailable(what + " returned HTTP " + response.statusCode());
        }
    }

    static void requireRepository(String repository) {
        if (repository == null || !repository.matches("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*")) {
            throw unavailable("Repository has no safe registry namespace");
        }
        for (String part : repository.split("/")) {
            if (".".equals(part) || "..".equals(part)) {
                throw unavailable("Repository has no safe registry namespace");
            }
        }
    }

    static AwsException unavailable(String message) {
        return new AwsException("ServerException", message, 500);
    }

    @Override
    public void close() {
        http.close();
    }

    private record Manifest(String digest, String body, String mediaType) {}
}
