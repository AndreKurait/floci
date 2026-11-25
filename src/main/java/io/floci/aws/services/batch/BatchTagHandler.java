package io.floci.aws.services.batch;

import io.floci.aws.core.common.TagHandler;
import io.floci.aws.core.common.V1Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Batch's tag endpoints, which AWS puts on {@code /v1/tags/{resourceArn}}: served by the shared
 * {@code V1TagsController}, which resolves the owning service from the ARN, the same way MSK and
 * AppSync are. A separate bean for the reason {@code MskTagHandler} gives: a class-level
 * {@link V1Tags} on the service would strip its {@code @Default} qualifier.
 *
 * <p>Batch's wire shape matches the {@link TagHandler} defaults on every axis but one: a lowercase
 * {@code tags} map, POST for TagResource, {@code tagKeys} for UntagResource, and a 200 on
 * TagResource and UntagResource where MSK answers 204: the Batch model declares no
 * {@code responseCode} on either, which in the rest-json protocol is the default 200.
 */
@ApplicationScoped
@V1Tags
public class BatchTagHandler implements TagHandler {

    private final BatchService batchService;
    private final BatchSchedulingPolicyService policies;
    private final BatchSchedulingPolicyReader policyReader;

    @Inject
    public BatchTagHandler(BatchService batchService, BatchSchedulingPolicyService policies,
                           BatchSchedulingPolicyReader policyReader) {
        this.batchService = batchService;
        this.policies = policies;
        this.policyReader = policyReader;
    }

    public BatchTagHandler(BatchService batchService) {
        this(batchService, null, null);
    }

    @Override
    public String serviceKey() {
        return "batch";
    }

    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        if (BatchSchedulingPolicyTarget.isPolicySelector(arn)) {
            return policies.listTags(arn);
        }
        return batchService.listTagsForResource(arn);
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        if (BatchSchedulingPolicyTarget.isPolicySelector(arn)) {
            policies.tag(arn, tags);
            return;
        }
        batchService.tagResource(arn, tags);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        if (BatchSchedulingPolicyTarget.isPolicySelector(arn)) {
            policies.untag(arn, tagKeys);
            return;
        }
        batchService.untagResource(arn, tagKeys);
    }

    @Override
    public Optional<Map<String, String>> parseTagResourceBody(String arn, String body) {
        return BatchSchedulingPolicyTarget.isPolicySelector(arn)
                ? Optional.of(BatchSchedulingPolicyReader.tags(policyReader.object(body), true)) : Optional.empty();
    }
}
