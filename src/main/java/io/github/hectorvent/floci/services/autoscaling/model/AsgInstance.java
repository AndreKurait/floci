package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AsgInstance {

    private String instanceId;
    private String availabilityZone;
    // Pending | Pending:Wait | Pending:Proceed | InService | EnteringStandby | Standby | Terminating | Terminated | Detached
    private String lifecycleState;
    private String healthStatus;     // Healthy | Unhealthy
    private String launchConfigurationName;
    private String launchTemplateId;
    private String launchTemplateName;
    private String launchTemplateVersion;
    private String instanceType;
    private boolean protectedFromScaleIn;
    // Retain the scale-in intent while ELB drains or EC2 termination is retried.
    // Older stored Terminating members retain the existing refresh termination path.
    private boolean scaleInTermination;
    // The EnterStandby or ExitStandby activity the reconciler completes when this instance finishes moving
    // into or out of standby; null otherwise, and for members stored before standby was supported.
    private String standbyActivityId;
    private String terminationActivityId;
    private Map<String, LaunchLifecycleAction> launchLifecycleActions = new LinkedHashMap<>();

    public AsgInstance() {}

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String v) { this.instanceId = v; }

    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String v) { this.availabilityZone = v; }

    public String getLifecycleState() { return lifecycleState; }
    public void setLifecycleState(String v) { this.lifecycleState = v; }

    public String getHealthStatus() { return healthStatus; }
    public void setHealthStatus(String v) { this.healthStatus = v; }

    public String getLaunchConfigurationName() { return launchConfigurationName; }
    public void setLaunchConfigurationName(String v) { this.launchConfigurationName = v; }

    public String getLaunchTemplateId() { return launchTemplateId; }
    public void setLaunchTemplateId(String v) { this.launchTemplateId = v; }

    public String getLaunchTemplateName() { return launchTemplateName; }
    public void setLaunchTemplateName(String v) { this.launchTemplateName = v; }

    public String getLaunchTemplateVersion() { return launchTemplateVersion; }
    public void setLaunchTemplateVersion(String v) { this.launchTemplateVersion = v; }

    public String getInstanceType() { return instanceType; }
    public void setInstanceType(String v) { this.instanceType = v; }

    public boolean isProtectedFromScaleIn() { return protectedFromScaleIn; }
    public void setProtectedFromScaleIn(boolean v) { this.protectedFromScaleIn = v; }

    public boolean isScaleInTermination() { return scaleInTermination; }
    public void setScaleInTermination(boolean v) { this.scaleInTermination = v; }

    public String getStandbyActivityId() { return standbyActivityId; }
    public void setStandbyActivityId(String v) { this.standbyActivityId = v; }

    public String getTerminationActivityId() { return terminationActivityId; }
    public void setTerminationActivityId(String v) { this.terminationActivityId = v; }

    public Map<String, LaunchLifecycleAction> getLaunchLifecycleActions() { return launchLifecycleActions; }
    public void setLaunchLifecycleActions(Map<String, LaunchLifecycleAction> v) {
        this.launchLifecycleActions = v == null ? new LinkedHashMap<>() : new LinkedHashMap<>(v);
    }
}
