package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpointServiceConfiguration;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpointServicePermission;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** XML projection of the VPC endpoint service (PrivateLink provider) operations. */
final class Ec2PrivateLinkHandler {

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final Ec2PrivateLinkService service;
    private final Ec2Service ec2Service;

    Ec2PrivateLinkHandler(Ec2PrivateLinkService service, Ec2Service ec2Service) {
        this.service = service;
        this.ec2Service = ec2Service;
    }

    Response create(MultivaluedMap<String, String> p, String region, List<Tag> tags) {
        Ec2PrivateLinkService.ServiceSpec spec = new Ec2PrivateLinkService.ServiceSpec(
                Ec2QueryProtocol.boolParam(p, "AcceptanceRequired"),
                Ec2QueryProtocol.getList(p, "NetworkLoadBalancerArn"),
                Ec2QueryProtocol.getList(p, "GatewayLoadBalancerArn"),
                p.getFirst("PrivateDnsName"),
                Ec2QueryProtocol.getList(p, "SupportedIpAddressType"),
                Ec2QueryProtocol.getList(p, "SupportedRegion"),
                tags, p.getFirst("ClientToken"));
        checkDryRun(p);
        VpcEndpointServiceConfiguration created = service.create(region, spec);
        XmlBuilder xml = Ec2QueryProtocol.startResponse("CreateVpcEndpointServiceConfiguration");
        appendConfiguration(xml, "serviceConfiguration", created, region);
        if (created.getClientToken() != null) {
            xml.elem("clientToken", created.getClientToken());
        }
        return Ec2QueryProtocol.endResponse(xml, "CreateVpcEndpointServiceConfiguration");
    }

    Response describe(MultivaluedMap<String, String> p, String region) {
        List<VpcEndpointServiceConfiguration> found = service.describe(region,
                Ec2QueryProtocol.getList(p, "ServiceId"), Ec2QueryProtocol.getFiltersList(p));
        checkDryRun(p);
        XmlBuilder xml = Ec2QueryProtocol.startResponse("DescribeVpcEndpointServiceConfigurations")
                .start("serviceConfigurationSet");
        for (VpcEndpointServiceConfiguration configuration : found) {
            appendConfiguration(xml, "item", configuration, region);
        }
        xml.end("serviceConfigurationSet");
        return Ec2QueryProtocol.endResponse(xml, "DescribeVpcEndpointServiceConfigurations");
    }

    Response modify(MultivaluedMap<String, String> p, String region) {
        Ec2PrivateLinkService.ServiceChange change = new Ec2PrivateLinkService.ServiceChange(
                Ec2QueryProtocol.boolParam(p, "AcceptanceRequired"),
                Ec2QueryProtocol.getList(p, "AddNetworkLoadBalancerArn"),
                Ec2QueryProtocol.getList(p, "RemoveNetworkLoadBalancerArn"),
                Ec2QueryProtocol.getList(p, "AddGatewayLoadBalancerArn"),
                Ec2QueryProtocol.getList(p, "RemoveGatewayLoadBalancerArn"),
                p.getFirst("PrivateDnsName"),
                Boolean.TRUE.equals(Ec2QueryProtocol.boolParam(p, "RemovePrivateDnsName")),
                Ec2QueryProtocol.getList(p, "AddSupportedIpAddressType"),
                Ec2QueryProtocol.getList(p, "RemoveSupportedIpAddressType"),
                Ec2QueryProtocol.getList(p, "AddSupportedRegion"),
                Ec2QueryProtocol.getList(p, "RemoveSupportedRegion"));
        checkDryRun(p);
        service.modify(region, p.getFirst("ServiceId"), change);
        return Ec2QueryProtocol.booleanResponse("ModifyVpcEndpointServiceConfiguration");
    }

    Response delete(MultivaluedMap<String, String> p, String region) {
        checkDryRun(p);
        return unsuccessfulResponse("DeleteVpcEndpointServiceConfigurations",
                service.delete(region, Ec2QueryProtocol.getList(p, "ServiceId")));
    }

    Response describePermissions(MultivaluedMap<String, String> p, String region) {
        String serviceId = p.getFirst("ServiceId");
        List<VpcEndpointServicePermission> permissions =
                service.describePermissions(region, serviceId, Ec2QueryProtocol.getFiltersList(p));
        checkDryRun(p);
        XmlBuilder xml = Ec2QueryProtocol.startResponse("DescribeVpcEndpointServicePermissions")
                .start("allowedPrincipals");
        for (VpcEndpointServicePermission permission : permissions) {
            appendPrincipal(xml, permission, serviceId);
        }
        xml.end("allowedPrincipals");
        return Ec2QueryProtocol.endResponse(xml, "DescribeVpcEndpointServicePermissions");
    }

    Response modifyPermissions(MultivaluedMap<String, String> p, String region) {
        checkDryRun(p);
        String serviceId = p.getFirst("ServiceId");
        List<VpcEndpointServicePermission> added = service.modifyPermissions(region, serviceId,
                Ec2QueryProtocol.getList(p, "AddAllowedPrincipals"),
                Ec2QueryProtocol.getList(p, "RemoveAllowedPrincipals"));
        XmlBuilder xml = Ec2QueryProtocol.startResponse("ModifyVpcEndpointServicePermissions");
        if (!added.isEmpty()) {
            xml.start("addedPrincipalSet");
            for (VpcEndpointServicePermission permission : added) {
                appendPrincipal(xml, permission, serviceId);
            }
            xml.end("addedPrincipalSet");
        }
        xml.elem("return", "true");
        return Ec2QueryProtocol.endResponse(xml, "ModifyVpcEndpointServicePermissions");
    }

    Response describeConnections(MultivaluedMap<String, String> p, String region) {
        List<Ec2PrivateLinkService.Connection> connections =
                service.describeConnections(region, Ec2QueryProtocol.getFiltersList(p));
        checkDryRun(p);
        XmlBuilder xml = Ec2QueryProtocol.startResponse("DescribeVpcEndpointConnections")
                .start("vpcEndpointConnectionSet");
        for (Ec2PrivateLinkService.Connection connection : connections) {
            VpcEndpoint endpoint = connection.endpoint();
            xml.start("item")
                    .elem("serviceId", connection.service().getServiceId())
                    .elem("vpcEndpointId", endpoint.getVpcEndpointId())
                    .elem("vpcEndpointOwner", connection.ownerId())
                    .elem("vpcEndpointState", endpoint.getState())
                    .elem("vpcEndpointRegion", endpoint.getRegion())
                    .elem("ipAddressType", endpoint.getIpAddressType());
            if (endpoint.getCreationTimestamp() != null) {
                xml.elem("creationTimestamp", ISO_FMT.format(endpoint.getCreationTimestamp()));
            }
            appendStrings(xml, "networkLoadBalancerArnSet", connection.service().getNetworkLoadBalancerArns());
            appendStrings(xml, "gatewayLoadBalancerArnSet", connection.service().getGatewayLoadBalancerArns());
            xml.end("item");
        }
        xml.end("vpcEndpointConnectionSet");
        return Ec2QueryProtocol.endResponse(xml, "DescribeVpcEndpointConnections");
    }

    Response acceptConnections(MultivaluedMap<String, String> p, String region) {
        checkDryRun(p);
        return unsuccessfulResponse("AcceptVpcEndpointConnections", service.acceptConnections(region,
                p.getFirst("ServiceId"), Ec2QueryProtocol.getList(p, "VpcEndpointId")));
    }

    Response rejectConnections(MultivaluedMap<String, String> p, String region) {
        checkDryRun(p);
        return unsuccessfulResponse("RejectVpcEndpointConnections", service.rejectConnections(region,
                p.getFirst("ServiceId"), Ec2QueryProtocol.getList(p, "VpcEndpointId")));
    }

    private void appendConfiguration(XmlBuilder xml, String element, VpcEndpointServiceConfiguration configuration,
                                     String region) {
        xml.start(element)
                .elem("serviceId", configuration.getServiceId())
                .elem("serviceName", configuration.getServiceName())
                .elem("serviceState", configuration.getServiceState())
                .start("serviceType").start("item").elem("serviceType", configuration.getServiceType())
                .end("item").end("serviceType")
                .elem("acceptanceRequired", String.valueOf(configuration.isAcceptanceRequired()))
                .elem("managesVpcEndpoints", "false");
        appendStrings(xml, "availabilityZoneSet", configuration.getAvailabilityZones());
        appendStrings(xml, "baseEndpointDnsNameSet", List.of(Ec2PrivateLinkService.baseEndpointDnsName(configuration)));
        appendStrings(xml, "networkLoadBalancerArnSet", configuration.getNetworkLoadBalancerArns());
        appendStrings(xml, "gatewayLoadBalancerArnSet", configuration.getGatewayLoadBalancerArns());
        appendStrings(xml, "supportedIpAddressTypeSet", configuration.getSupportedIpAddressTypes());
        xml.start("supportedRegionSet");
        for (String supported : configuration.getSupportedRegions()) {
            xml.start("item").elem("region", supported).elem("serviceState", "Available").end("item");
        }
        xml.end("supportedRegionSet");
        if (configuration.getPrivateDnsName() != null) {
            xml.elem("privateDnsName", configuration.getPrivateDnsName())
                    .start("privateDnsNameConfiguration")
                    .elem("state", Ec2PrivateLinkService.privateDnsVerificationState(configuration))
                    .elem("type", "TXT")
                    .elem("value", configuration.getPrivateDnsVerificationValue())
                    .elem("name", configuration.getPrivateDnsVerificationName())
                    .end("privateDnsNameConfiguration");
        }
        xml.raw(Ec2QueryProtocol.tagSetXml(ec2Service.resourceTags(region, configuration.getServiceId())))
                .end(element);
    }

    private static void appendPrincipal(XmlBuilder xml, VpcEndpointServicePermission permission, String serviceId) {
        xml.start("item")
                .elem("principal", permission.getPrincipal())
                .elem("principalType", permission.getPrincipalType())
                .elem("serviceId", serviceId)
                .end("item");
    }

    private static void appendStrings(XmlBuilder xml, String element, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        xml.start(element);
        for (String value : values) {
            xml.elem("item", value);
        }
        xml.end(element);
    }

    private static Response unsuccessfulResponse(String action, List<Ec2PrivateLinkService.Unsuccessful> failed) {
        XmlBuilder xml = Ec2QueryProtocol.startResponse(action).start("unsuccessful");
        for (Ec2PrivateLinkService.Unsuccessful item : failed) {
            xml.start("item")
                    .start("error").elem("code", item.code()).elem("message", item.message()).end("error")
                    .elem("resourceId", item.resourceId())
                    .end("item");
        }
        xml.end("unsuccessful");
        return Ec2QueryProtocol.endResponse(xml, action);
    }

    private static void checkDryRun(MultivaluedMap<String, String> p) {
        if (Boolean.parseBoolean(p.getFirst("DryRun"))) {
            throw new AwsException("DryRunOperation", "Request would have succeeded, but DryRun flag is set.", 412);
        }
    }
}
