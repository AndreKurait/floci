package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The existing resources and unallocated resource types used by an EC2 launch. */
public final class Ec2LaunchAuthorization {

    private static final String TEMPLATE = Ec2LaunchAuthorization.class.getName() + ".template";

    private Ec2LaunchAuthorization() {
    }

    public static LaunchTemplate template(ContainerRequestContext context, Ec2Service service, String region) {
        LaunchTemplate captured = capturedTemplate(context);
        if (captured != null) {
            return captured;
        }
        Map<String, String> form = IamConditionContextResolver.formParameters(context);
        String id = form.get("LaunchTemplate.LaunchTemplateId");
        String name = form.get("LaunchTemplate.LaunchTemplateName");
        if (service == null || blank(id) && blank(name)) {
            return null;
        }
        LaunchTemplate template = service.launchTemplateForAuthorization(
                region, id, name, form.get("LaunchTemplate.Version"));
        context.setProperty(TEMPLATE, template);
        return template;
    }

    /** Resource and PassRole checks use the same resolved template version. */
    static LaunchTemplate capturedTemplate(ContainerRequestContext context) {
        return context.getProperty(TEMPLATE) instanceof LaunchTemplate template ? template : null;
    }

    public static List<String> resources(ContainerRequestContext context, Ec2Service service,
                                          String region, String account, String partition) {
        Map<String, String> form = IamConditionContextResolver.formParameters(context);
        LaunchTemplate selected = template(context, service, region);
        LaunchTemplateData data = selected == null ? new LaunchTemplateData() : selected.getData();
        String image = first(form.get("ImageId"), data.getImageId());
        if (blank(image)) {
            return List.of("*");
        }
        Set<String> resources = new LinkedHashSet<>();
        resources.add(arn(partition, region, "", "image", image));
        resources.add(arn(partition, region, account, "instance", "*"));
        resources.add(arn(partition, region, account, "volume", "*"));
        String key = first(form.get("KeyName"), data.getKeyName());
        if (!blank(key)) {
            resources.add(arn(partition, region, account, "key-pair", key));
        }

        String subnet = first(form.get("SubnetId"), form.get("NetworkInterface.1.SubnetId"));
        String networkInterface = form.get("NetworkInterface.1.NetworkInterfaceId");
        List<String> groups = list(form, "SecurityGroupId.");
        if ("0".equals(form.getOrDefault("NetworkInterface.1.DeviceIndex", "0"))) {
            List<String> interfaceGroups = list(form, "NetworkInterface.1.SecurityGroupId.");
            if (!interfaceGroups.isEmpty()) {
                groups = interfaceGroups;
            }
        }
        boolean suppliedInterfaces = form.keySet().stream().anyMatch(keyName -> keyName.startsWith("NetworkInterface."));
        LaunchTemplateData.NetworkInterface primary = data.primaryNetworkInterface();
        if (!suppliedInterfaces && primary != null) {
            subnet = primary.getSubnetId();
            networkInterface = primary.getNetworkInterfaceId();
        }
        if (groups.isEmpty()) {
            groups = data.effectiveSecurityGroupIds();
        }
        String zone = first(form.get("Placement.AvailabilityZone"),
                data.getPlacement() == null ? null : data.getPlacement().getAvailabilityZone());
        if (service != null) {
            Ec2Service.LaunchNetwork network = service.launchNetworkForAuthorization(
                    region, subnet, networkInterface, groups, zone);
            subnet = network.subnetId();
            groups = network.securityGroupIds();
        }
        if (!blank(subnet)) {
            resources.add(arn(partition, region, account, "subnet", subnet));
        }
        for (String group : groups) {
            resources.add(arn(partition, region, account, "security-group", group));
        }
        resources.add(arn(partition, region, account, "network-interface",
                blank(networkInterface) ? "*" : networkInterface));
        return List.copyOf(resources);
    }

    private static List<String> list(Map<String, String> form, String prefix) {
        List<String> values = new ArrayList<>();
        for (int index = 1; form.containsKey(prefix + index); index++) {
            values.add(form.get(prefix + index));
        }
        return values;
    }

    private static String arn(String partition, String region, String account, String type, String id) {
        return new AwsArnUtils.Arn(partition, "ec2", region, account, type + "/" + id).toString();
    }

    private static String first(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
