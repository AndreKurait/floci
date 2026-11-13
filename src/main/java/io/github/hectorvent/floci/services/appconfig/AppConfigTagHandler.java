package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.TagHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tags stored applications, environments, configuration profiles and deployment strategies. */
@ApplicationScoped
public class AppConfigTagHandler implements TagHandler {
    private static final Set<String> STORED_TYPES = Set.of(
            "application", "environment", "configurationprofile", "deploymentstrategy");
    private static final Set<String> TOP_LEVEL_TYPES = Set.of(
            "deploymentstrategy", "extension", "extensionassociation");
    private final AppConfigService service;

    @Inject
    public AppConfigTagHandler(AppConfigService service) {
        this.service = service;
    }

    @Override
    public String serviceKey() {
        return "appconfig";
    }

    @Override
    public String tagsBodyKey() {
        return "Tags";
    }

    @Override
    public boolean strictTagValidation() {
        return true;
    }

    @Override
    public String tagValidationErrorCode() {
        return "BadRequestException";
    }

    @Override
    public boolean allowEmptyTagKeys() {
        return true;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        ResourceRef ref = parseArn(region, arn);
        return STORED_TYPES.contains(ref.type())
                ? service.getResourceTags(ref.type(), ref.applicationId(), ref.id()) : Map.of();
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        ResourceRef ref = parseArn(region, arn);
        if (STORED_TYPES.contains(ref.type())) {
            service.tagResource(ref.type(), ref.applicationId(), ref.id(), tags);
        }
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        ResourceRef ref = parseArn(region, arn);
        if (STORED_TYPES.contains(ref.type())) {
            service.untagResource(ref.type(), ref.applicationId(), ref.id(), tagKeys);
        }
    }

    private record ResourceRef(String type, String applicationId, String id) {}

    private ResourceRef parseArn(String region, String arn) {
        String resource;
        try {
            resource = AwsArnUtils.parse(arn).resource();
        } catch (IllegalArgumentException e) {
            throw new AwsException("BadRequestException", "Invalid resource ARN: " + arn, 400);
        }
        String[] parts = resource.split("/", -1);
        ResourceRef ref;
        if (parts.length == 2 && TOP_LEVEL_TYPES.contains(parts[0]) && !parts[1].isEmpty()) {
            ref = new ResourceRef(parts[0], null, parts[1]);
        } else if (parts.length >= 2 && "application".equals(parts[0]) && !parts[1].isEmpty()) {
            ref = switch (parts.length) {
                case 2 -> new ResourceRef("application", null, parts[1]);
                case 4 -> {
                    if ((!"environment".equals(parts[2]) && !"configurationprofile".equals(parts[2]))
                            || parts[3].isEmpty()) {
                        throw new AwsException("BadRequestException", "Invalid resource ARN: " + arn, 400);
                    }
                    yield new ResourceRef(parts[2], parts[1], parts[3]);
                }
                case 6 -> {
                    if (!"environment".equals(parts[2]) || !"deployment".equals(parts[4])
                            || parts[3].isEmpty() || parts[5].isEmpty()) {
                        throw new AwsException("BadRequestException", "Invalid resource ARN: " + arn, 400);
                    }
                    yield new ResourceRef("deployment", parts[1], parts[5]);
                }
                default -> throw new AwsException("BadRequestException", "Invalid resource ARN: " + arn, 400);
            };
        } else {
            throw new AwsException("BadRequestException", "Invalid resource ARN: " + arn, 400);
        }
        if (STORED_TYPES.contains(ref.type()) && !arn.equals(service.tagResourceArn(region, resource))) {
            throw new AwsException("ResourceNotFoundException", "The AppConfig resource does not exist.", 404);
        }
        return ref;
    }
}
