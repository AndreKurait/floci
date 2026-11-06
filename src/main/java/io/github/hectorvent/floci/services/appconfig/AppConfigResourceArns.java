package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.StoredResourceArns;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Resolves AppConfig names and configuration tokens to the resource IDs used by IAM. */
@ApplicationScoped
public class AppConfigResourceArns implements StoredResourceArns {
    private static final Set<String> APPLICATION_TYPES = Set.of(
            "application", "environment", "configurationprofile", "hostedconfigurationversion",
            "configuration", "deployment");

    private final AppConfigService appConfig;
    private final AppConfigDataService data;
    private final RegionResolver regions;

    public AppConfigResourceArns(AppConfigService appConfig, AppConfigDataService data, RegionResolver regions) {
        this.appConfig = appConfig;
        this.data = data;
        this.regions = regions;
    }

    @Override
    public String namespace() {
        return "appconfig";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        return Optional.empty();
    }

    @Override
    public Optional<String> findByVariables(String type, Map<String, String> variables, String region) {
        if (!region.equals(regions.getRegion())) {
            return Optional.empty();
        }
        try {
            if ("deploymentstrategy".equals(type) && variables.containsKey("DeploymentStrategyId")) {
                String strategy = appConfig.getDeploymentStrategy(variables.get("DeploymentStrategyId")).getId();
                return Optional.of(regions.buildArn("appconfig", region, "deploymentstrategy/" + strategy));
            }
            if (!APPLICATION_TYPES.contains(type) || !variables.containsKey("ApplicationId")) {
                return Optional.empty();
            }
            String application = appConfig.resolveApplication(variables.get("ApplicationId")).getId();
            String parent = "application/" + application;
            String resource = switch (type) {
                case "application" -> parent;
                case "environment" -> parent + "/environment/" + environment(application, variables);
                case "configurationprofile", "hostedconfigurationversion" ->
                        parent + "/configurationprofile/" + profile(application, variables);
                case "configuration" -> parent + "/environment/" + environment(application, variables)
                        + "/configuration/" + profile(application, variables);
                case "deployment" -> variables.containsKey("DeploymentNumber")
                        ? parent + "/environment/" + environment(application, variables)
                                + "/deployment/" + variables.get("DeploymentNumber") : null;
                default -> null;
            };
            return Optional.ofNullable(resource).map(value -> regions.buildArn("appconfig", region, value));
        } catch (AwsException e) {
            if ("ResourceNotFoundException".equals(e.getErrorCode())) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<String> through(String member, String value, String region) {
        if (!"ConfigurationToken".equals(member) || !region.equals(regions.getRegion())) {
            return List.of();
        }
        Map<String, String> ids = data.configurationForToken(value);
        return List.of(regions.buildArn("appconfig", region,
                "application/" + ids.get("ApplicationId") + "/environment/" + ids.get("EnvironmentId")
                        + "/configuration/" + ids.get("ConfigurationProfileId")));
    }

    private String environment(String application, Map<String, String> variables) {
        String identifier = variables.get("EnvironmentId");
        if (identifier == null) {
            throw new AwsException("ResourceNotFoundException", "Environment not named", 404);
        }
        return appConfig.resolveEnvironment(application, identifier).getId();
    }

    private String profile(String application, Map<String, String> variables) {
        String identifier = variables.get("ConfigurationProfileId");
        if (identifier == null) {
            throw new AwsException("ResourceNotFoundException", "Configuration profile not named", 404);
        }
        return appConfig.resolveConfigurationProfile(application, identifier).getId();
    }
}
