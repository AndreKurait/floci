package io.floci.aws.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * A VPC endpoint service (AWS PrivateLink, provider side): the configuration an account offers
 * through its Network or Gateway Load Balancers, and the principals allowed to connect to it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpcEndpointServiceConfiguration {

    private String serviceId;
    private String serviceName;
    /** {@code Interface} for a Network Load Balancer service, {@code GatewayLoadBalancer} otherwise. */
    private String serviceType;
    private String serviceState = "Available";
    private String region;
    private String ownerId;
    private boolean acceptanceRequired;
    private List<String> networkLoadBalancerArns = new ArrayList<>();
    private List<String> gatewayLoadBalancerArns = new ArrayList<>();
    private List<String> availabilityZones = new ArrayList<>();
    private List<String> supportedIpAddressTypes = new ArrayList<>();
    private List<String> supportedRegions = new ArrayList<>();
    private String privateDnsName;
    /** The TXT record name and value the provider would publish to verify {@link #privateDnsName}. */
    private String privateDnsVerificationName;
    private String privateDnsVerificationValue;
    private List<VpcEndpointServicePermission> allowedPrincipals = new ArrayList<>();
    private String clientToken;

    public VpcEndpointServiceConfiguration() {}

    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getServiceType() { return serviceType; }
    public void setServiceType(String serviceType) { this.serviceType = serviceType; }

    public String getServiceState() { return serviceState; }
    public void setServiceState(String serviceState) { this.serviceState = serviceState; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public boolean isAcceptanceRequired() { return acceptanceRequired; }
    public void setAcceptanceRequired(boolean acceptanceRequired) { this.acceptanceRequired = acceptanceRequired; }

    public List<String> getNetworkLoadBalancerArns() { return networkLoadBalancerArns; }
    public void setNetworkLoadBalancerArns(List<String> networkLoadBalancerArns) {
        this.networkLoadBalancerArns = networkLoadBalancerArns;
    }

    public List<String> getGatewayLoadBalancerArns() { return gatewayLoadBalancerArns; }
    public void setGatewayLoadBalancerArns(List<String> gatewayLoadBalancerArns) {
        this.gatewayLoadBalancerArns = gatewayLoadBalancerArns;
    }

    public List<String> getAvailabilityZones() { return availabilityZones; }
    public void setAvailabilityZones(List<String> availabilityZones) { this.availabilityZones = availabilityZones; }

    public List<String> getSupportedIpAddressTypes() { return supportedIpAddressTypes; }
    public void setSupportedIpAddressTypes(List<String> supportedIpAddressTypes) {
        this.supportedIpAddressTypes = supportedIpAddressTypes;
    }

    public List<String> getSupportedRegions() { return supportedRegions; }
    public void setSupportedRegions(List<String> supportedRegions) { this.supportedRegions = supportedRegions; }

    public String getPrivateDnsName() { return privateDnsName; }
    public void setPrivateDnsName(String privateDnsName) { this.privateDnsName = privateDnsName; }

    public String getPrivateDnsVerificationName() { return privateDnsVerificationName; }
    public void setPrivateDnsVerificationName(String privateDnsVerificationName) {
        this.privateDnsVerificationName = privateDnsVerificationName;
    }

    public String getPrivateDnsVerificationValue() { return privateDnsVerificationValue; }
    public void setPrivateDnsVerificationValue(String privateDnsVerificationValue) {
        this.privateDnsVerificationValue = privateDnsVerificationValue;
    }

    public List<VpcEndpointServicePermission> getAllowedPrincipals() { return allowedPrincipals; }
    public void setAllowedPrincipals(List<VpcEndpointServicePermission> allowedPrincipals) {
        this.allowedPrincipals = allowedPrincipals;
    }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }
}
