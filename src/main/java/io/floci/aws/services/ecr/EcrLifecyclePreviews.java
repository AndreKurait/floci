package io.floci.aws.services.ecr;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.ecr.model.ImageIdentifier;
import io.floci.aws.services.ecr.model.LifecyclePolicyPreview;
import io.floci.aws.services.ecr.model.Repository;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Synchronous evaluation with durable immutable completion and transient, generation-bound progress. */
public final class EcrLifecyclePreviews {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final EcrService service;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    EcrLifecyclePreviews(EcrService service) {
        this.service = service;
    }

    public record View(String registryId, String repositoryName, String policy, String status,
                       List<LifecyclePolicyPreview.Result> results, int expiringCount, String nextToken) {
        public View {
            results = List.copyOf(results);
        }
    }

    private record Pending(EcrService.PreviewRepository repository, String policy) {}

    View start(String name, String registryId, String suppliedPolicy, String region) {
        EcrService.PreviewRepository captured = service.previewRepository(name, registryId, region);
        String text = suppliedPolicy == null ? captured.repository().getLifecyclePolicyText() : suppliedPolicy;
        if (text == null) {
            throw new AwsException("LifecyclePolicyNotFoundException",
                    "No lifecycle policy associated with repository " + name, 400);
        }
        EcrLifecyclePreviewEvaluator.Policy policy = EcrLifecyclePreviewEvaluator.parse(text);
        Pending operation = new Pending(captured, text);
        while (true) {
            Pending previous = pending.putIfAbsent(captured.scope(), operation);
            if (previous == null) {
                break;
            }
            if (service.matchesPreview(previous.repository(), captured)) {
                throw new AwsException("LifecyclePolicyPreviewInProgressException",
                        "A lifecycle policy preview is already in progress for repository " + name, 400);
            }
            if (pending.replace(captured.scope(), previous, operation)) {
                break;
            }
        }
        try (EcrLifecyclePreviewInventory inventory = service.previewInventory()) {
            String physical = service.previewPhysical(captured, inventory.catalog());
            service.lockRepositoryWrites(physical);
            try {
                service.revalidatePreview(captured);
                if (!physical.equals(service.previewPhysical(captured, inventory.catalog()))) {
                    throw new AwsException("ServerException", "Registry namespace changed during preview", 500);
                }
                List<EcrLifecyclePreviewEvaluator.Image> images = inventory.read(physical,
                        captured.repository().getRepositoryGeneration(), service.previewMetadata(captured));
                Instant evaluatedAt = Instant.now();
                List<LifecyclePolicyPreview.Result> results = EcrLifecyclePreviewEvaluator.evaluate(
                        policy, images, evaluatedAt);
                LifecyclePolicyPreview complete = service.completePreview(captured, text, evaluatedAt, results);
                return view(captured.repository(), complete.policyText(), "COMPLETE", List.of(), results.size(), null);
            } finally {
                service.unlockRepositoryWrites(physical);
            }
        } finally {
            pending.remove(captured.scope(), operation);
        }
    }

    View get(String name, String registryId, List<ImageIdentifier> ids, String tagStatus,
             Integer maxResults, String token, String region) {
        validateSelectors(ids, tagStatus, maxResults, token);
        EcrService.PreviewRepository captured = service.previewRepository(name, registryId, region);
        Pending operation = pending.get(captured.scope());
        if (operation != null && service.matchesPreview(operation.repository(), captured)) {
            if (token != null) {
                throw invalid("Pagination token does not identify a completed preview");
            }
            return view(captured.repository(), operation.policy(), "IN_PROGRESS", List.of(), 0, null);
        }
        LifecyclePolicyPreview snapshot = captured.repository().getLifecyclePolicyPreview();
        if (snapshot == null || snapshot.repositoryGeneration() == null || !snapshot.repositoryGeneration().equals(
                captured.repository().getRepositoryGeneration())) {
            throw new AwsException("LifecyclePolicyPreviewNotFoundException",
                    "No lifecycle policy preview associated with repository " + name, 400);
        }
        String filter = tagStatus == null ? "ANY" : tagStatus;
        List<LifecyclePolicyPreview.Result> selected = snapshot.results().stream()
                .filter(row -> matchesFilter(row, filter))
                .filter(row -> ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> matchesId(row, id)))
                .toList();
        if (ids != null && !ids.isEmpty()) {
            return view(captured.repository(), snapshot.policyText(), "COMPLETE", selected,
                    snapshot.results().size(), null);
        }
        int offset = token == null ? 0 : decodeToken(token, captured.scope(), snapshot.previewId(), filter);
        if (offset > selected.size()) {
            throw invalid("Pagination token offset is outside the preview");
        }
        int end = Math.min(selected.size(), offset + (maxResults == null ? 100 : maxResults));
        String next = end < selected.size() ? encodeToken(captured.scope(), snapshot.previewId(), filter, end) : null;
        return view(captured.repository(), snapshot.policyText(), "COMPLETE", selected.subList(offset, end),
                snapshot.results().size(), next);
    }

    private static void validateSelectors(List<ImageIdentifier> ids, String status, Integer maxResults, String token) {
        if (status != null && !List.of("TAGGED", "UNTAGGED", "ANY").contains(status)) {
            throw invalid("filter.tagStatus must be TAGGED, UNTAGGED or ANY");
        }
        if (maxResults != null && (maxResults < 1 || maxResults > 100)) {
            throw invalid("maxResults must be between 1 and 100");
        }
        if (ids != null && !ids.isEmpty()) {
            if (ids.size() > 100 || maxResults != null || token != null) {
                throw invalid("imageIds cannot be combined with pagination and accepts at most 100 identifiers");
            }
            for (ImageIdentifier id : ids) {
                if (id == null || (id.getImageDigest() == null && id.getImageTag() == null)
                        || (id.getImageDigest() != null && !id.getImageDigest().matches("sha256:[a-f0-9]{64}"))
                        || (id.getImageTag() != null && (id.getImageTag().isEmpty() || id.getImageTag().length() > 300))) {
                    throw invalid("Each image identifier requires a valid digest or tag");
                }
            }
        }
    }

    private static boolean matchesFilter(LifecyclePolicyPreview.Result row, String filter) {
        return "ANY".equals(filter) || ("TAGGED".equals(filter) && !row.imageTags().isEmpty())
                || ("UNTAGGED".equals(filter) && row.imageTags().isEmpty());
    }

    private static boolean matchesId(LifecyclePolicyPreview.Result row, ImageIdentifier id) {
        return (id.getImageDigest() == null || id.getImageDigest().equals(row.imageDigest()))
                && (id.getImageTag() == null || row.imageTags().contains(id.getImageTag()));
    }

    private static View view(Repository repository, String policy, String status,
                             List<LifecyclePolicyPreview.Result> results, int total, String token) {
        return new View(repository.getRegistryId(), repository.getRepositoryName(), policy, status, results, total, token);
    }

    private static String encodeToken(String scope, String preview, String filter, int offset) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(
                    Map.of("scope", scope, "preview", preview, "filter", filter, "offset", offset)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int decodeToken(String token, String scope, String preview, String filter) {
        try {
            if (token.isEmpty() || token.length() > 4096) {
                throw invalid("Invalid pagination token");
            }
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            JsonNode value = JSON.readTree(decoded);
            if (!value.isObject() || value.size() != 4 || !scope.equals(value.path("scope").asText())
                    || !preview.equals(value.path("preview").asText()) || !filter.equals(value.path("filter").asText())
                    || !value.path("offset").isIntegralNumber() || !value.path("offset").canConvertToInt()
                    || value.path("offset").intValue() < 1) {
                throw invalid("Pagination token does not match this preview and filter");
            }
            return value.path("offset").intValue();
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalid("Invalid pagination token");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
