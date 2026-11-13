package io.floci.aws.services.ec2;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.XmlBuilder;
import io.floci.aws.services.ec2.model.Ec2PlacementGroup;
import io.floci.aws.services.ec2.model.Tag;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** XML projection of the native placement-group metadata operations. */
final class Ec2PlacementGroupHandler {

    private final Ec2PlacementGroupService service;

    Ec2PlacementGroupHandler(Ec2PlacementGroupService service) {
        this.service = service;
    }

    Response create(MultivaluedMap<String, String> params, String region, List<Tag> tags) {
        for (String member : List.of("ParentGroupName", "ParentGroupId", "Operator.Principal", "ClientToken")) {
            if (params.containsKey(member)) {
                throw new AwsException("UnsupportedOperation", member + " is not supported for placement groups.", 400);
            }
        }
        Map<String, String> copiedTags = new LinkedHashMap<>();
        for (Tag tag : tags) {
            copiedTags.put(tag.getKey(), tag.getValue() == null ? "" : tag.getValue());
        }
        Ec2PlacementGroup group = service.create(region, params.getFirst("GroupName"), params.getFirst("Strategy"),
                Ec2QueryProtocol.intParam(params, "PartitionCount"), params.getFirst("SpreadLevel"),
                copiedTags, dryRun(params));
        XmlBuilder xml = Ec2QueryProtocol.startResponse("CreatePlacementGroup").elem("return", "true");
        append(xml, "placementGroup", group);
        return Ec2QueryProtocol.endResponse(xml, "CreatePlacementGroup");
    }

    Response describe(MultivaluedMap<String, String> params, String region) {
        List<Ec2PlacementGroup> groups = service.describe(region,
                Ec2QueryProtocol.getList(params, "GroupName"), Ec2QueryProtocol.getList(params, "GroupId"),
                Ec2QueryProtocol.getFiltersList(params), dryRun(params));
        XmlBuilder xml = Ec2QueryProtocol.startResponse("DescribePlacementGroups").start("placementGroupSet");
        for (Ec2PlacementGroup group : groups) {
            append(xml, "item", group);
        }
        xml.end("placementGroupSet");
        return Ec2QueryProtocol.endResponse(xml, "DescribePlacementGroups");
    }

    Response delete(MultivaluedMap<String, String> params, String region) {
        service.delete(region, params.getFirst("GroupName"), dryRun(params));
        return Ec2QueryProtocol.booleanResponse("DeletePlacementGroup");
    }

    private static void append(XmlBuilder xml, String element, Ec2PlacementGroup group) {
        xml.start(element).elem("groupName", group.groupName()).elem("groupId", group.groupId())
                .elem("groupArn", group.arn()).elem("strategy", group.strategy()).elem("state", "available");
        if (group.partitionCount() != null) {
            xml.elem("partitionCount", String.valueOf(group.partitionCount()));
        }
        if (group.spreadLevel() != null) {
            xml.elem("spreadLevel", group.spreadLevel());
        }
        xml.start("tagSet");
        group.tags().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(tag ->
                xml.start("item").elem("key", tag.getKey()).elem("value", tag.getValue()).end("item"));
        xml.end("tagSet").end(element);
    }

    private static boolean dryRun(MultivaluedMap<String, String> params) {
        String value = params.getFirst("DryRun");
        if (value == null) {
            return false;
        }
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new AwsException("InvalidParameterValue", "DryRun must be true or false.", 400);
        }
        return Boolean.parseBoolean(value);
    }
}
