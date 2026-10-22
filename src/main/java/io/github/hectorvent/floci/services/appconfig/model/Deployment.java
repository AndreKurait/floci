package io.github.hectorvent.floci.services.appconfig.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Deployment {
    @JsonProperty("ApplicationId")
    private String applicationId;
    @JsonProperty("EnvironmentId")
    private String environmentId;
    @JsonProperty("ConfigurationProfileId")
    private String configurationProfileId;
    @JsonProperty("DeploymentNumber")
    private int deploymentNumber;
    @JsonProperty("ConfigurationName")
    private String configurationName;
    @JsonProperty("ConfigurationLocationUri")
    private String configurationLocationUri;
    @JsonProperty("ConfigurationVersion")
    private String configurationVersion;
    @JsonProperty("DeploymentStrategyId")
    private String deploymentStrategyId;
    @JsonProperty("State")
    private String state; // BAKING, VALIDATING, DEPLOYING, COMPLETE, ROLLING_BACK, ROLLED_BACK
    @JsonProperty("Description")
    private String description;

    public Deployment() {}

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }

    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }

    public String getConfigurationProfileId() { return configurationProfileId; }
    public void setConfigurationProfileId(String configurationProfileId) { this.configurationProfileId = configurationProfileId; }

    public int getDeploymentNumber() { return deploymentNumber; }
    public void setDeploymentNumber(int deploymentNumber) { this.deploymentNumber = deploymentNumber; }

    public String getConfigurationName() { return configurationName; }
    public void setConfigurationName(String configurationName) { this.configurationName = configurationName; }

    public String getConfigurationLocationUri() { return configurationLocationUri; }
    public void setConfigurationLocationUri(String configurationLocationUri) { this.configurationLocationUri = configurationLocationUri; }

    public String getConfigurationVersion() { return configurationVersion; }
    public void setConfigurationVersion(String configurationVersion) { this.configurationVersion = configurationVersion; }

    public String getDeploymentStrategyId() { return deploymentStrategyId; }
    public void setDeploymentStrategyId(String deploymentStrategyId) { this.deploymentStrategyId = deploymentStrategyId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
