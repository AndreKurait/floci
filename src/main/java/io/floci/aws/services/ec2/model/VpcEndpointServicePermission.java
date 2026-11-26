package io.floci.aws.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** One principal allowed to connect to a VPC endpoint service, with its {@code principalType}. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class VpcEndpointServicePermission {

    private String principal;
    private String principalType;

    public VpcEndpointServicePermission() {}

    public VpcEndpointServicePermission(String principal, String principalType) {
        this.principal = principal;
        this.principalType = principalType;
    }

    public String getPrincipal() { return principal; }
    public void setPrincipal(String principal) { this.principal = principal; }

    public String getPrincipalType() { return principalType; }
    public void setPrincipalType(String principalType) { this.principalType = principalType; }
}
