package io.github.hectorvent.floci.services.ecr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

/** An immutable, completed preview owned by the repository row containing it. */
@RegisterForReflection
public record LifecyclePolicyPreview(String previewId, String repositoryGeneration, String policyText,
                                     Instant evaluatedAt, List<Result> results) {

    public LifecyclePolicyPreview {
        results = List.copyOf(results);
    }

    @RegisterForReflection
    public record Result(String imageDigest, Instant imagePushedAt, List<String> imageTags,
                         int appliedRulePriority) {
        public Result {
            imageTags = List.copyOf(imageTags);
        }
    }
}
