package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.S3Uri;
import io.github.hectorvent.floci.core.common.ServiceRoleCallAuthorizer;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationContent;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator.Decision;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Retrieves an immutable S3 object version using the profile's AppConfig service role. */
@ApplicationScoped
public class AppConfigS3Source {
    private static final long MAX_CONTENT_BYTES = 1024 * 1024;
    private static final Set<String> STORAGE_CLASSES = Set.of(
            "STANDARD", "INTELLIGENT_TIERING", "REDUCED_REDUNDANCY", "STANDARD_IA", "ONEZONE_IA");

    private final S3Service s3;
    private final ServiceRoleCallAuthorizer authorizer;
    private final RegionResolver regions;

    public AppConfigS3Source(S3Service s3, ServiceRoleCallAuthorizer authorizer, RegionResolver regions) {
        this.s3 = s3;
        this.authorizer = authorizer;
        this.regions = regions;
    }

    static boolean matches(ConfigurationProfile profile) {
        String location = profile.getLocationUri();
        return location != null && (location.startsWith("s3://") || AwsArnUtils.isArnFor(location, "s3"));
    }

    static void validateProfile(ConfigurationProfile profile) {
        if (!matches(profile)) {
            return;
        }
        location(profile);
        if (profile.getType() != null && !"AWS.Freeform".equals(profile.getType())) {
            throw invalid("S3 configuration profiles must use AWS.Freeform.");
        }
        String role = profile.getRetrievalRoleArn();
        if (role == null || role.length() > 2048 || !AwsArnUtils.isArnFor(role, "iam")) {
            throw invalid("An S3 configuration requires an IAM RetrievalRoleArn.");
        }
        AwsArnUtils.Arn arn = AwsArnUtils.parse(role);
        if (!arn.region().isEmpty() || !arn.accountId().matches("\\d{12}") || !arn.resource().startsWith("role/")
                || arn.resource().length() <= "role/".length()) {
            throw invalid("RetrievalRoleArn must name an IAM role.");
        }
    }

    public ConfigurationContent retrieve(ConfigurationProfile profile, String version) {
        validateProfile(profile);
        if (version == null || version.isBlank() || "null".equals(version)) {
            throw invalid("ConfigurationVersion must identify an immutable S3 object version.");
        }
        S3Uri location = location(profile);
        try {
            String partition = s3.bucketPartition(location.bucket());
            if (!regions.getPartition().equals(partition)
                    || AwsArnUtils.isArnFor(profile.getLocationUri(), "s3")
                    && !AwsArnUtils.parse(profile.getLocationUri()).partition().equals(partition)) {
                throw invalid("The S3 configuration must belong to the application's AWS partition.");
            }
            String bucketArn = AwsArnUtils.Arn.global(partition,
                    "s3", "", location.bucket()).toString();
            authorize(profile, "s3:GetBucketVersioning", bucketArn, Map.of());
            String bucketOwner = s3.getBucketOwnerAccountId(location.bucket());
            String versioning = RequestScopes.callAs(bucketOwner, regions.getRegion(),
                    () -> s3.getBucketVersioning(location.bucket()));
            if (!"Enabled".equals(versioning) && !"Suspended".equals(versioning)) {
                throw invalid("The S3 configuration bucket must use versioning.");
            }
            S3Object metadata = s3.getObjectMetadata(location.bucket(), location.key(), version);
            Map<String, List<String>> context = new HashMap<>();
            context.put("s3:VersionId", List.of(version));
            metadata.getTags().forEach((key, value) ->
                    context.put("s3:ExistingObjectTag/" + key, List.of(value)));
            authorize(profile, "s3:GetObjectVersion", bucketArn + "/" + location.key(), context);
            validateObject(metadata);
            S3Object object = s3.getObject(location.bucket(), location.key(), version);
            if (object.getData().length > MAX_CONTENT_BYTES) {
                throw invalid("S3 configurations must not exceed 1 MB.");
            }
            return new ConfigurationContent(object.getData(), object.getContentType());
        } catch (AwsException failure) {
            if ("BadRequestException".equals(failure.getErrorCode())) {
                throw failure;
            }
            throw invalid("Cannot retrieve S3 configuration version: " + failure.getErrorCode() + ".");
        }
    }

    private void authorize(ConfigurationProfile profile, String action, String resource,
                           Map<String, List<String>> resourceContext) {
        Map<String, List<String>> context = new HashMap<>(resourceContext);
        context.put("aws:SecureTransport", List.of("true"));
        context.put("aws:RequestedRegion", List.of(regions.getRegion()));
        if (authorizer.authorizeWithoutSourceContext("appconfig", profile.getRetrievalRoleArn(),
                action, resource, regions.getAccountId(), regions.getPartition(), context) != Decision.ALLOW) {
            throw invalid("The retrieval role cannot perform " + action + " for this S3 configuration.");
        }
    }

    private static S3Uri location(ConfigurationProfile profile) {
        S3Uri location;
        try {
            String value = profile.getLocationUri();
            if (AwsArnUtils.isArnFor(value, "s3")) {
                AwsArnUtils.Arn arn = AwsArnUtils.parse(value);
                int separator = arn.resource().indexOf('/');
                if (!arn.region().isEmpty() || !arn.accountId().isEmpty() || separator < 1) {
                    throw invalid("LocationUri must identify an S3 bucket and object key.");
                }
                location = new S3Uri(arn.resource().substring(0, separator),
                        arn.resource().substring(separator + 1));
            } else {
                location = S3Uri.parse(value);
            }
        } catch (IllegalArgumentException failure) {
            throw invalid("LocationUri must identify an S3 bucket and object key.");
        }
        if (!location.bucket().matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]") || location.key().isEmpty()) {
            throw invalid("LocationUri must identify an S3 bucket and object key.");
        }
        return location;
    }

    private static void validateObject(S3Object object) {
        if (object.isDeleteMarker()) {
            throw invalid("The S3 configuration version is a delete marker.");
        }
        if (object.getSize() > MAX_CONTENT_BYTES) {
            throw invalid("S3 configurations must not exceed 1 MB.");
        }
        if (object.getStorageClass() != null && !STORAGE_CLASSES.contains(object.getStorageClass())) {
            throw invalid("The S3 configuration uses an unsupported storage class.");
        }
        String encryption = object.getServerSideEncryption();
        if (object.getSseCustomerAlgorithm() != null
                || encryption != null && !"AES256".equals(encryption)) {
            throw invalid("Floci currently supports unencrypted and SSE-S3 AppConfig source objects.");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("BadRequestException", message, 400);
    }
}
