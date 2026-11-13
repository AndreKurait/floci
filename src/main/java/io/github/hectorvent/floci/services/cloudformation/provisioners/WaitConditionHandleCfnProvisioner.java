package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.PreSignedUrlGenerator;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A wait handle is a presigned S3 PUT URL. Creating the handle itself never waits for a signal.
 * {@link WaitConditionCfnProvisioner} consumes the separate resource's success/failure signals.
 */
@ApplicationScoped
public class WaitConditionHandleCfnProvisioner implements CfnResourceProvisioner {

    static final String BUCKET_ATTRIBUTE = "__FlociWaitConditionBucket";
    static final String KEY_ATTRIBUTE = "__FlociWaitConditionKey";
    private static final String SIGNAL_KEY = "signals";
    private static final int URL_EXPIRY_SECONDS = 604800;

    private final S3Service s3;
    private final PreSignedUrlGenerator presigner;
    private final ContainerReachableEndpoint endpoint;

    public WaitConditionHandleCfnProvisioner(S3Service s3, PreSignedUrlGenerator presigner,
                                            ContainerReachableEndpoint endpoint) {
        this.s3 = s3;
        this.presigner = presigner;
        this.endpoint = endpoint;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::CloudFormation::WaitConditionHandle");
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext context) {
        if (properties != null && !properties.isMissingNode() && !properties.isNull()
                && (!properties.isObject() || !properties.isEmpty())) {
            throw new AwsException("ValidationError", "WaitConditionHandle does not support properties.", 400);
        }
        if (context.isUpdate()) {
            resource.setPhysicalId(context.priorPhysicalId());
            resource.getAttributes().put("Id", context.priorPhysicalId());
            return;
        }
        String bucket = S3Service.INTERNAL_BUCKET_PREFIX + "cfn-wait-" + UUID.randomUUID();
        s3.createServiceOwnedBucket(bucket, context.region());
        try {
            // Each PUT remains an independent signal for the WaitCondition consumer.
            s3.putBucketVersioning(bucket, "Enabled");
            String url = presigner.generatePresignedUrl(endpoint.baseUrl(false), bucket, SIGNAL_KEY,
                    "PUT", URL_EXPIRY_SECONDS, context.region(), context.accountId());
            resource.getAttributes().put(BUCKET_ATTRIBUTE, bucket);
            resource.getAttributes().put(KEY_ATTRIBUTE, SIGNAL_KEY);
            resource.getAttributes().put("Id", url);
            resource.setPhysicalId(url);
        } catch (RuntimeException failure) {
            try {
                s3.deleteBucket(bucket);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        // An existing handle has no mutable properties and retains its original URL and signals.
        return true;
    }

    @Override
    public void delete(StackResource resource, String region) {
        String bucket = resource.getAttributes().get(BUCKET_ATTRIBUTE);
        String key = resource.getAttributes().get(KEY_ATTRIBUTE);
        if (bucket == null || key == null || !s3.isServiceOwnedBucket(bucket)) {
            // A failed create owns nothing; a customer replacement must never be removed.
            return;
        }
        CfnDeletes.safeDelete("Wait condition signal bucket", bucket,
                () -> deleteSignalsAndBucket(bucket, key), "NoSuchBucket");
    }

    private void deleteSignalsAndBucket(String bucket, String key) {
        List<S3Object> signals = new ArrayList<>();
        S3Service.ListVersionsResult page;
        String keyMarker = null;
        String versionMarker = null;
        do {
            page = s3.listObjectVersions(bucket, key, null, 1000, keyMarker, versionMarker);
            page.versions().stream().filter(object -> key.equals(object.getKey())).forEach(signals::add);
            keyMarker = page.nextKeyMarker();
            versionMarker = page.nextVersionIdMarker();
        } while (page.isTruncated());
        for (S3Object signal : signals) {
            s3.deleteObject(bucket, key, signal.getVersionId());
        }
        // A concurrent write or an unrelated object fails deletion instead of being silently erased.
        s3.deleteBucket(bucket);
    }
}
