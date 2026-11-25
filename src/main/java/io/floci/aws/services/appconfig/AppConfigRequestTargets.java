package io.floci.aws.services.appconfig;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.appconfig.model.Environment;
import jakarta.enterprise.context.RequestScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Keeps name resolution stable between IAM resource selection and handler execution. */
@RequestScoped
public class AppConfigRequestTargets {
    private final AppConfigService service;
    private final Map<String, Resolution<String>> applications = new HashMap<>();
    private final Map<Selector, Resolution<EnvironmentTarget>> environments = new HashMap<>();

    public AppConfigRequestTargets(AppConfigService service) {
        this.service = service;
    }

    public String application(String identifier) {
        return resolve(applications, identifier, value -> service.resolveApplication(value).getId());
    }

    public EnvironmentTarget environment(String application, String environment) {
        return resolve(environments, new Selector(application, environment), selector -> {
            String applicationId = application(selector.application());
            Environment selected = service.resolveEnvironment(applicationId, selector.environment());
            return new EnvironmentTarget(applicationId, selected.getId());
        });
    }

    public Environment getEnvironment(String application, String environment) {
        EnvironmentTarget target = environment(application, environment);
        return service.findEnvironment(target.environmentId())
                .filter(value -> target.applicationId().equals(value.getApplicationId()))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException", "Environment not found", 404));
    }

    private static <K, V> V resolve(Map<K, Resolution<V>> cache, K key, Function<K, V> lookup) {
        Resolution<V> resolution = cache.computeIfAbsent(key, value -> {
            try {
                return new Resolution<>(lookup.apply(value), null);
            } catch (AwsException failure) {
                if (!"ResourceNotFoundException".equals(failure.getErrorCode())) {
                    throw failure;
                }
                return new Resolution<>(null, failure);
            }
        });
        if (resolution.failure() != null) {
            throw resolution.failure();
        }
        return resolution.value();
    }

    public record EnvironmentTarget(String applicationId, String environmentId) {}

    private record Selector(String application, String environment) {}

    private record Resolution<V>(V value, AwsException failure) {}
}
