package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerReachableEndpoint;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Consumes real, versioned S3 signals written to a CloudFormation wait handle. */
@ApplicationScoped
public class WaitConditionCfnProvisioner implements CfnResourceProvisioner {
    private static final Logger LOG = Logger.getLogger(WaitConditionCfnProvisioner.class);
    private static final String TYPE = "AWS::CloudFormation::WaitCondition";
    private static final String SETTINGS = "__FlociWaitConditionSettings";
    private static final Set<String> PROPERTIES = Set.of("Count", "Handle", "Timeout");
    private static final String BUCKET_PREFIX = S3Service.INTERNAL_BUCKET_PREFIX + "cfn-wait-";

    private final S3Service s3;
    private final ObjectMapper mapper;
    private final ContainerReachableEndpoint endpoint;

    public WaitConditionCfnProvisioner(S3Service s3, ObjectMapper mapper, ContainerReachableEndpoint endpoint) {
        this.s3 = s3;
        this.mapper = mapper;
        this.endpoint = endpoint;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext context) {
        if (properties == null || !properties.isObject()) {
            throw invalid("WaitCondition requires Handle and Timeout properties.");
        }
        properties.fieldNames().forEachRemaining(name -> {
            if (!PROPERTIES.contains(name)) {
                throw invalid("WaitCondition does not support property " + name + ".");
            }
        });
        Integer suppliedCount = context.resolveOptionalInt(properties, "Count");
        int count = suppliedCount == null ? 1 : suppliedCount;
        Integer timeout = context.resolveOptionalInt(properties, "Timeout");
        if (count < 0 || timeout == null || timeout < 1 || timeout > 43200) {
            throw invalid("WaitCondition Count must be nonnegative and Timeout must be from 1 to 43200 seconds.");
        }
        String handle = context.resolveOptional(properties, "Handle");
        String settings = mapper.valueToTree(List.of(count, timeout, handle == null ? "" : handle)).toString();
        if (context.isUpdate()) {
            if (!settings.equals(resource.getAttributes().get(SETTINGS))) {
                throw invalid("WaitCondition properties cannot be updated; create a new logical resource and handle.");
            }
            resource.setPhysicalId(context.priorPhysicalId());
            resource.getAttributes().put("Id", context.priorPhysicalId());
            return;
        }
        String bucket = signalBucket(handle, context);
        resource.setPhysicalId(context.generatePhysicalName(resource.getLogicalId(), 128, false));
        resource.getAttributes().put("Id", resource.getPhysicalId());
        resource.getAttributes().put(SETTINGS, settings);
        Map<String, String> data = awaitSignals(bucket, count, timeout);
        // CloudFormation GetAtt returns this attribute as a JSON string.
        resource.getAttributes().put("Data", mapper.valueToTree(data).toString());
    }

    private String signalBucket(String handle, ProvisionContext context) {
        if (handle == null || handle.isBlank()) {
            throw invalid("WaitCondition requires a Handle; CreationPolicy resource signals are not supported.");
        }
        URI uri;
        try {
            uri = URI.create(handle);
        } catch (IllegalArgumentException failure) {
            throw invalid("WaitCondition Handle must be a local CloudFormation wait handle.");
        }
        URI reachable = URI.create(endpoint.baseUrl(false));
        String path = uri.getRawPath();
        if (!reachable.getScheme().equals(uri.getScheme())
                || !reachable.getRawAuthority().equals(uri.getRawAuthority())
                || uri.getRawQuery() == null || uri.getRawFragment() != null
                || path == null || !path.startsWith("/" + BUCKET_PREFIX) || !path.endsWith("/signals")) {
            throw invalid("WaitCondition Handle must be a local CloudFormation wait handle.");
        }
        String bucket = path.substring(1, path.length() - "/signals".length());
        if (bucket.contains("/") || !s3.isServiceOwnedBucket(bucket)
                || !context.accountId().equals(s3.getBucketOwnerAccountId(bucket))
                || !context.region().equals(s3.getBucketRegion(bucket))) {
            throw invalid("WaitCondition Handle must belong to this account and Region.");
        }
        return bucket;
    }

    private Map<String, String> awaitSignals(String bucket, int count, int timeout) {
        Map<String, String> data = new LinkedHashMap<>();
        Set<String> observed = new HashSet<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(timeout).toNanos();
        while (data.size() < count) {
            if (System.nanoTime() >= deadline) {
                throw timedOut(timeout, count, data.size());
            }
            for (S3Object version : signalVersions(bucket)) {
                if (version.isDeleteMarker() || !observed.add(version.getVersionId())) {
                    continue;
                }
                JsonNode signal;
                try {
                    signal = mapper.readTree(s3.getObject(bucket, "signals", version.getVersionId()).getData());
                } catch (IOException failure) {
                    LOG.debugv("Ignoring malformed wait-condition signal version {0}", version.getVersionId());
                    continue;
                }
                if (!validSignal(signal)) {
                    LOG.debugv("Ignoring invalid wait-condition signal version {0}", version.getVersionId());
                    continue;
                }
                String id = signal.get("UniqueId").textValue();
                if (data.containsKey(id)) {
                    continue;
                }
                if ("FAILURE".equals(signal.get("Status").textValue())) {
                    throw invalid("WaitCondition received FAILURE for " + id
                            + ": " + signal.get("Reason").textValue());
                }
                data.put(id, signal.get("Data").textValue());
                if (data.size() >= count) {
                    return data;
                }
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw timedOut(timeout, count, data.size());
            }
            try {
                Thread.sleep(Duration.ofNanos(Math.min(remaining, Duration.ofMillis(50).toNanos())));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for CloudFormation signals", failure);
            }
        }
        return data;
    }

    private List<S3Object> signalVersions(String bucket) {
        List<S3Object> versions = new ArrayList<>();
        S3Service.ListVersionsResult page;
        String keyMarker = null;
        String versionMarker = null;
        do {
            page = s3.listObjectVersions(bucket, "signals", null, 1000, keyMarker, versionMarker);
            page.versions().stream().filter(object -> "signals".equals(object.getKey())).forEach(versions::add);
            keyMarker = page.nextKeyMarker();
            versionMarker = page.nextVersionIdMarker();
        } while (page.isTruncated());
        // The persisted S3 sequencer orders writes even within one timestamp millisecond.
        versions.sort(Comparator.comparing(object -> object.getEventSequencer() == null
                ? BigInteger.ZERO : new BigInteger(object.getEventSequencer(), 16)));
        return versions;
    }

    private static boolean validSignal(JsonNode signal) {
        return signal != null && signal.isObject()
                && List.of("Status", "UniqueId", "Data", "Reason").stream()
                .allMatch(name -> signal.has(name) && signal.get(name).isTextual())
                && !signal.get("UniqueId").textValue().isEmpty()
                && Set.of("SUCCESS", "FAILURE").contains(signal.get("Status").textValue());
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        // Changed properties fail before mutation; unchanged properties retain their result.
        return true;
    }

    private static AwsException invalid(String message) {
        return new AwsException("ValidationError", message, 400);
    }

    private static AwsException timedOut(int timeout, int required, int observed) {
        return invalid("WaitCondition timed out after " + timeout + " seconds: received "
                + observed + " of " + required + " required unique success signals.");
    }
}
