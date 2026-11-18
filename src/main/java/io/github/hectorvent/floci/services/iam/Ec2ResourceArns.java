package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.Ec2LaunchAuthorization;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resource targets for EBS-backed images, snapshots, security-group rules, network interfaces, VPC
 * endpoints and their tags.
 *
 * <p>The network interface and VPC endpoint arms follow the resource types AWS's Service
 * Authorization Reference gives each action (servicereference {@code v1/ec2/ec2.json}), and only the
 * resources the request names: CreateNetworkInterface acts on network-interface, subnet and
 * security-group; DeleteNetworkInterface and CreateNetworkInterfacePermission on network-interface;
 * AttachNetworkInterface and DetachNetworkInterface on instance and network-interface;
 * CreateVpcEndpoint on vpc-endpoint, vpc, subnet, security-group and route-table; ModifyVpcEndpoint
 * on vpc-endpoint, subnet, security-group and route-table; DeleteVpcEndpoints on vpc-endpoint. An
 * action the reference gives no resource type (DescribeNetworkInterfaces,
 * DescribeNetworkInterfacePermissions) stays on {@code *}, as AWS checks it.
 */
final class Ec2ResourceArns {

    private Ec2ResourceArns() {
    }

    static List<String> of(String operation, ContainerRequestContext context, String region,
                           String account, String partition, Ec2Service service) {
        if (operation == null) {
            return List.of("*");
        }
        return switch (operation) {
            case "ec2:RunInstances" -> Ec2LaunchAuthorization.resources(
                    context, service, region, account, partition);
            case "ec2:TerminateInstances" -> instances(context, region, account, partition);
            case "ec2:CopyImage" -> copy(context, region, partition);
            case "ec2:DescribeImageAttribute", "ec2:ModifyImageAttribute", "ec2:DeregisterImage" ->
                    named(RequestBodyReader.formField(context, "ImageId"), "image", region, partition);
            case "ec2:DescribeSnapshotAttribute", "ec2:ModifySnapshotAttribute", "ec2:DeleteSnapshot" ->
                    named(RequestBodyReader.formField(context, "SnapshotId"), "snapshot", region, partition);
            case "ec2:AuthorizeSecurityGroupIngress", "ec2:AuthorizeSecurityGroupEgress",
                 "ec2:RevokeSecurityGroupIngress", "ec2:RevokeSecurityGroupEgress" ->
                    group(context, region, account, partition);
            case "ec2:CreateTags", "ec2:DeleteTags" -> tags(context, region, account, partition);
            case "ec2:CreateNetworkInterface" -> createNetworkInterface(context, region, account, partition);
            case "ec2:DeleteNetworkInterface", "ec2:CreateNetworkInterfacePermission" -> List.of(networkInterfaceArn(
                    RequestBodyReader.formField(context, "NetworkInterfaceId"), region, account, partition));
            case "ec2:AttachNetworkInterface" -> attachNetworkInterface(context, region, account, partition);
            case "ec2:DetachNetworkInterface" -> detachNetworkInterface(context, region, account, partition, service);
            case "ec2:CreateVpcEndpoint" -> createVpcEndpoint(context, region, account, partition);
            case "ec2:ModifyVpcEndpoint" -> modifyVpcEndpoint(context, region, account, partition);
            case "ec2:DeleteVpcEndpoints" -> deleteVpcEndpoints(context, region, account, partition);
            default -> List.of("*");
        };
    }

    private static List<String> instances(ContainerRequestContext context, String region,
                                          String account, String partition) {
        List<String> resources = new ArrayList<>();
        for (int index = 1; ; index++) {
            String id = RequestBodyReader.formField(context, "InstanceId." + index);
            if (id == null) {
                break;
            }
            resources.add(new AwsArnUtils.Arn(partition, "ec2", region, account, "instance/" + id).toString());
        }
        return resources.isEmpty() ? List.of("*") : List.copyOf(resources);
    }

    private static List<String> copy(ContainerRequestContext context, String region, String partition) {
        String source = RequestBodyReader.formField(context, "SourceImageId");
        String sourceRegion = RequestBodyReader.formField(context, "SourceRegion");
        if (source == null || source.isBlank() || sourceRegion == null || sourceRegion.isBlank()) {
            return List.of("*");
        }
        // The source is known; the destination AMI and snapshot IDs have not been allocated yet.
        return List.of(arn(AwsRegions.partitionFor(sourceRegion), sourceRegion, "image", source),
                arn(partition, region, "image", "*"), arn(partition, region, "snapshot", "*"));
    }

    private static List<String> named(String id, String type, String region, String partition) {
        return id == null || id.isBlank() ? List.of("*") : List.of(arn(partition, region, type, id));
    }

    private static List<String> group(ContainerRequestContext context, String region,
                                      String account, String partition) {
        String id = RequestBodyReader.formField(context, "GroupId");
        return id == null || id.isBlank() ? List.of("*") : List.of(groupArn(partition, region, account, id));
    }

    private static List<String> tags(ContainerRequestContext context, String region,
                                     String account, String partition) {
        List<String> resources = new ArrayList<>();
        // Match the contiguous ResourceId.N list consumed by Ec2QueryHandler.
        for (int index = 1; ; index++) {
            String id = RequestBodyReader.formField(context, "ResourceId." + index);
            if (id == null) {
                break;
            }
            if (id.startsWith("ami-")) {
                resources.add(arn(partition, region, "image", id));
            } else if (id.startsWith("snap-")) {
                resources.add(arn(partition, region, "snapshot", id));
            } else if (id.startsWith("sg-")) {
                resources.add(groupArn(partition, region, account, id));
            } else if (id.startsWith("eni-")) {
                resources.add(networkInterfaceArn(id, region, account, partition));
            } else if (id.startsWith("vpce-") && !id.startsWith("vpce-svc-")) {
                resources.add(owned(partition, region, account, "vpc-endpoint", id));
            } else {
                // Other EC2 resource types retain their existing unscoped authorization.
                resources.add("*");
            }
        }
        return resources.isEmpty() ? List.of("*") : List.copyOf(resources);
    }

    /** The interface to be created, the subnet it is created in and each group it names. */
    private static List<String> createNetworkInterface(ContainerRequestContext context, String region,
                                                       String account, String partition) {
        List<String> resources = new ArrayList<>();
        resources.add(owned(partition, region, account, "network-interface", "*"));
        String subnet = RequestBodyReader.formField(context, "SubnetId");
        if (!blank(subnet)) {
            resources.add(owned(partition, region, account, "subnet", subnet));
        }
        for (String group : numbered(context, "SecurityGroupId.")) {
            resources.add(owned(partition, region, account, "security-group", group));
        }
        return List.copyOf(resources);
    }

    private static List<String> attachNetworkInterface(ContainerRequestContext context, String region,
                                                       String account, String partition) {
        String instance = RequestBodyReader.formField(context, "InstanceId");
        return List.of(owned(partition, region, account, "instance", blank(instance) ? "*" : instance),
                networkInterfaceArn(RequestBodyReader.formField(context, "NetworkInterfaceId"),
                        region, account, partition));
    }

    /**
     * The request names only the attachment, so the instance and the interface are the ones it joins.
     * An attachment the caller cannot see is checked on any instance and interface of its account, and
     * the action then answers it as not found.
     */
    private static List<String> detachNetworkInterface(ContainerRequestContext context, String region,
                                                       String account, String partition, Ec2Service service) {
        String attachment = RequestBodyReader.formField(context, "AttachmentId");
        Optional<Ec2Service.AttachmentTarget> target = service == null
                ? Optional.empty() : service.attachmentForAuthorization(region, attachment);
        if (target.isEmpty()) {
            return List.of(owned(partition, region, account, "instance", "*"),
                    owned(partition, region, account, "network-interface", "*"));
        }
        Ec2Service.AttachmentTarget joined = target.get();
        return List.of(owned(partition, region, account, "instance", joined.instanceId()),
                owned(partition, region, account, "network-interface", joined.networkInterfaceId()));
    }

    private static List<String> createVpcEndpoint(ContainerRequestContext context, String region,
                                                  String account, String partition) {
        List<String> resources = new ArrayList<>();
        resources.add(owned(partition, region, account, "vpc-endpoint", "*"));
        String vpc = RequestBodyReader.formField(context, "VpcId");
        if (!blank(vpc)) {
            resources.add(owned(partition, region, account, "vpc", vpc));
        }
        addAll(resources, context, "SubnetId.", "subnet", region, account, partition);
        addAll(resources, context, "SecurityGroupId.", "security-group", region, account, partition);
        addAll(resources, context, "RouteTableId.", "route-table", region, account, partition);
        return List.copyOf(resources);
    }

    private static List<String> modifyVpcEndpoint(ContainerRequestContext context, String region,
                                                  String account, String partition) {
        String endpoint = RequestBodyReader.formField(context, "VpcEndpointId");
        List<String> resources = new ArrayList<>();
        resources.add(owned(partition, region, account, "vpc-endpoint", blank(endpoint) ? "*" : endpoint));
        for (String change : List.of("Add", "Remove")) {
            addAll(resources, context, change + "SubnetId.", "subnet", region, account, partition);
            addAll(resources, context, change + "SecurityGroupId.", "security-group", region, account, partition);
            addAll(resources, context, change + "RouteTableId.", "route-table", region, account, partition);
        }
        return List.copyOf(resources);
    }

    private static List<String> deleteVpcEndpoints(ContainerRequestContext context, String region,
                                                   String account, String partition) {
        List<String> resources = new ArrayList<>();
        addAll(resources, context, "VpcEndpointId.", "vpc-endpoint", region, account, partition);
        return resources.isEmpty() ? List.of(owned(partition, region, account, "vpc-endpoint", "*"))
                : List.copyOf(resources);
    }

    /** A network interface's ARN in the caller's account, or the type wildcard when the request names none. */
    private static String networkInterfaceArn(String id, String region, String account, String partition) {
        return owned(partition, region, account, "network-interface", blank(id) ? "*" : id);
    }

    private static void addAll(List<String> resources, ContainerRequestContext context, String prefix,
                               String type, String region, String account, String partition) {
        for (String id : numbered(context, prefix)) {
            resources.add(owned(partition, region, account, type, id));
        }
    }

    /** The values of {@code prefix1}, {@code prefix2}, ... up to the first one missing. */
    private static List<String> numbered(ContainerRequestContext context, String prefix) {
        List<String> values = new ArrayList<>();
        for (int index = 1; ; index++) {
            String value = RequestBodyReader.formField(context, prefix + index);
            if (value == null) {
                return values;
            }
            values.add(value);
        }
    }

    private static String owned(String partition, String region, String account, String type, String id) {
        return new AwsArnUtils.Arn(partition, "ec2", region, account, type + "/" + id).toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String arn(String partition, String region, String type, String id) {
        // EC2 image and snapshot ARNs omit the account component.
        return new AwsArnUtils.Arn(partition, "ec2", region, "", type + "/" + id).toString();
    }

    private static String groupArn(String partition, String region, String account, String id) {
        return new AwsArnUtils.Arn(partition, "ec2", region, account, "security-group/" + id).toString();
    }
}
