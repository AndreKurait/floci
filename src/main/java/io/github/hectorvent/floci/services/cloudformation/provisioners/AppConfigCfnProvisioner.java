package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appconfig.AppConfigService;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.DeploymentStrategy;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class AppConfigCfnProvisioner implements CfnResourceProvisioner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROPERTIES = "_floci_appconfig_properties";
    private static final String APPLICATION = "_floci_appconfig_application";
    private final AppConfigService service;

    @Inject
    public AppConfigCfnProvisioner(AppConfigService service) {
        this.service = service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::AppConfig::Application", "AWS::AppConfig::Environment",
                "AWS::AppConfig::ConfigurationProfile", "AWS::AppConfig::DeploymentStrategy");
    }

    @Override
    public void provision(StackResource resource, JsonNode properties, ProvisionContext context) {
        JsonNode resolved = context.engine().resolveNode(properties);
        Map<String, Object> request = MAPPER.convertValue(resolved, new TypeReference<>() {});
        Set<String> supported = switch (resource.getResourceType()) {
            case "AWS::AppConfig::Application" -> Set.of("Name", "Description", "Tags");
            case "AWS::AppConfig::Environment" -> Set.of("ApplicationId", "Name", "Description", "Monitors");
            case "AWS::AppConfig::ConfigurationProfile" ->
                    Set.of("ApplicationId", "Name", "Description", "LocationUri", "Type");
            case "AWS::AppConfig::DeploymentStrategy" -> Set.of("Name", "Description", "DeploymentDurationInMinutes",
                    "FinalBakeTimeInMinutes", "GrowthFactor", "GrowthType", "ReplicateTo");
            default -> throw new IllegalStateException("Unsupported AppConfig resource type");
        };
        if (!supported.containsAll(request.keySet())) {
            throw new AwsException("ValidationError", "Unsupported AppConfig resource properties", 400);
        }
        required(request, "Name");
        if (context.isUpdate()) {
            verifyUnchanged(resource, resolved);
            verifyExists(resource);
            return;
        }
        switch (resource.getResourceType()) {
            case "AWS::AppConfig::Application" -> {
                Application app = service.createApplication(request);
                resource.setPhysicalId(app.getId());
                resource.getAttributes().put("ApplicationId", app.getId());
                service.tagApplication(app.getId(), context.resolveTags(properties, "Tags"));
            }
            case "AWS::AppConfig::Environment" -> {
                String appId = required(request, "ApplicationId");
                Environment environment = service.createEnvironment(appId, request);
                resource.setPhysicalId(environment.getId());
                resource.getAttributes().put("EnvironmentId", environment.getId());
                resource.getAttributes().put(APPLICATION, appId);
            }
            case "AWS::AppConfig::ConfigurationProfile" -> {
                String appId = required(request, "ApplicationId");
                required(request, "LocationUri");
                ConfigurationProfile profile = service.createConfigurationProfile(appId, request);
                resource.setPhysicalId(profile.getId());
                resource.getAttributes().put("ConfigurationProfileId", profile.getId());
                resource.getAttributes().put(APPLICATION, appId);
            }
            case "AWS::AppConfig::DeploymentStrategy" -> {
                request.put("DeploymentDurationInMinutes", minutes(request.get("DeploymentDurationInMinutes")));
                if (request.containsKey("FinalBakeTimeInMinutes")) {
                    request.put("FinalBakeTimeInMinutes", minutes(request.get("FinalBakeTimeInMinutes")));
                }
                if (!(request.get("GrowthFactor") instanceof Number growth)
                        || !Double.isFinite(growth.doubleValue()) || growth.doubleValue() <= 0
                        || growth.doubleValue() > 100) {
                    throw new AwsException("ValidationError", "Deployment strategy requires a growth factor in (0, 100]", 400);
                }
                required(request, "ReplicateTo");
                DeploymentStrategy strategy = service.createDeploymentStrategy(request);
                resource.setPhysicalId(strategy.getId());
                resource.getAttributes().put("Id", strategy.getId());
            }
            default -> throw new IllegalStateException("Unsupported AppConfig resource type");
        }
        resource.getAttributes().put(PROPERTIES, resolved.toString());
    }

    private static int minutes(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < 0 || number.doubleValue() > 1440
                || number.doubleValue() != number.intValue()) {
            throw new AwsException("ValidationError", "Deployment strategy minutes must be an integer from 0 to 1440", 400);
        }
        return number.intValue();
    }

    private void verifyExists(StackResource resource) {
        String id = resource.getPhysicalId();
        switch (resource.getResourceType()) {
            case "AWS::AppConfig::Application" -> service.getApplication(id);
            case "AWS::AppConfig::Environment" -> service.getEnvironment(application(resource), id);
            case "AWS::AppConfig::ConfigurationProfile" -> service.getConfigurationProfile(application(resource), id);
            case "AWS::AppConfig::DeploymentStrategy" -> service.getDeploymentStrategy(id);
            default -> throw new IllegalStateException("Unsupported AppConfig resource type");
        }
    }

    private static void verifyUnchanged(StackResource resource, JsonNode properties) {
        String prior = resource.getAttributes().get(PROPERTIES);
        try {
            if (prior == null || !MAPPER.readTree(prior).equals(properties)) {
                throw new AwsException("ValidationError", "AppConfig property-changing stack updates are not supported", 400);
            }
        } catch (JsonProcessingException error) {
            throw new AwsException("ValidationError", "Stored AppConfig resource properties are invalid", 400);
        }
    }

    private static String required(Map<String, Object> request, String key) {
        if (!(request.get(key) instanceof String value) || value.isBlank()) {
            throw new AwsException("ValidationError", "AppConfig resource requires " + key, 400);
        }
        return value;
    }

    private static String application(StackResource resource) {
        String id = resource.getAttributes().get(APPLICATION);
        if (id == null || id.isBlank()) {
            throw new AwsException("ValidationError", "AppConfig resource is missing its application identity", 400);
        }
        return id;
    }

    @Override
    public void delete(StackResource resource, String region) {
        String id = resource.getPhysicalId();
        switch (resource.getResourceType()) {
            case "AWS::AppConfig::Application" -> service.deleteApplication(id);
            case "AWS::AppConfig::Environment" -> service.deleteEnvironment(application(resource), id);
            case "AWS::AppConfig::ConfigurationProfile" -> service.deleteConfigurationProfile(application(resource), id);
            case "AWS::AppConfig::DeploymentStrategy" -> service.deleteDeploymentStrategy(id);
            default -> throw new IllegalStateException("Unsupported AppConfig resource type");
        }
    }
}
