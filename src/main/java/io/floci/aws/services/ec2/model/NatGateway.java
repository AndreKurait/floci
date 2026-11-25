package io.floci.aws.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NatGateway {

    private String clientToken;
    private String creationAccountId;
    private String creationAvailabilityZone;
    private Map<String, List<String>> creationParameters = new LinkedHashMap<>();
    private String natGatewayId;
    private String subnetId;
    private String vpcId;
    private String allocationId;
    private String state = "available";
    private String connectivityType = "public";
    private String failureCode;
    private String failureMessage;
    private Instant createTime;
    private String region;
    private List<NatGatewayAddress> natGatewayAddresses = new ArrayList<>();
    private List<Tag> tags = new ArrayList<>();

    public NatGateway() {}

    public NatGateway(NatGateway source) {
        natGatewayId = source.natGatewayId;
        subnetId = source.subnetId;
        vpcId = source.vpcId;
        allocationId = source.allocationId;
        state = source.state;
        connectivityType = source.connectivityType;
        failureCode = source.failureCode;
        failureMessage = source.failureMessage;
        createTime = source.createTime;
        region = source.region;
        clientToken = source.clientToken;
        creationAccountId = source.creationAccountId;
        creationAvailabilityZone = source.creationAvailabilityZone;
        source.creationParameters.forEach((name, values) ->
                creationParameters.put(name, new ArrayList<>(values)));
        for (NatGatewayAddress original : source.natGatewayAddresses) {
            NatGatewayAddress address = new NatGatewayAddress();
            address.setAllocationId(original.getAllocationId());
            address.setAssociationId(original.getAssociationId());
            address.setNetworkInterfaceId(original.getNetworkInterfaceId());
            address.setPrivateIp(original.getPrivateIp());
            address.setPublicIp(original.getPublicIp());
            address.setPrimary(original.isPrimary());
            address.setStatus(original.getStatus());
            natGatewayAddresses.add(address);
        }
        source.tags.forEach(tag -> tags.add(new Tag(tag.getKey(), tag.getValue())));
    }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public String getCreationAccountId() { return creationAccountId; }
    public void setCreationAccountId(String creationAccountId) { this.creationAccountId = creationAccountId; }

    public String getCreationAvailabilityZone() { return creationAvailabilityZone; }
    public void setCreationAvailabilityZone(String zone) { this.creationAvailabilityZone = zone; }

    public Map<String, List<String>> getCreationParameters() { return creationParameters; }
    public void setCreationParameters(Map<String, List<String>> parameters) {
        creationParameters = new LinkedHashMap<>();
        parameters.forEach((name, values) -> creationParameters.put(name, new ArrayList<>(values)));
    }

    public String getNatGatewayId() { return natGatewayId; }
    public void setNatGatewayId(String natGatewayId) { this.natGatewayId = natGatewayId; }

    public String getSubnetId() { return subnetId; }
    public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getAllocationId() { return allocationId; }
    public void setAllocationId(String allocationId) { this.allocationId = allocationId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }

    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }

    public String getConnectivityType() { return connectivityType; }
    public void setConnectivityType(String connectivityType) { this.connectivityType = connectivityType; }

    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public List<NatGatewayAddress> getNatGatewayAddresses() { return natGatewayAddresses; }
    public void setNatGatewayAddresses(List<NatGatewayAddress> natGatewayAddresses) { this.natGatewayAddresses = natGatewayAddresses; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
