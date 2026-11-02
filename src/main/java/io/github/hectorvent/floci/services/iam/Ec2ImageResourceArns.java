package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsRegions;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.util.ArrayList;
import java.util.List;

/** Resource targets for EBS-backed image copies and image/snapshot attributes and tags. */
final class Ec2ImageResourceArns {

    private Ec2ImageResourceArns() {
    }

    static List<String> of(String operation, ContainerRequestContext context, String region, String partition) {
        if (operation == null) {
            return List.of("*");
        }
        return switch (operation) {
            case "ec2:CopyImage" -> copy(context, region, partition);
            case "ec2:DescribeImageAttribute", "ec2:ModifyImageAttribute", "ec2:DeregisterImage" ->
                    named(RequestBodyReader.formField(context, "ImageId"), "image", region, partition);
            case "ec2:DescribeSnapshotAttribute", "ec2:ModifySnapshotAttribute", "ec2:DeleteSnapshot" ->
                    named(RequestBodyReader.formField(context, "SnapshotId"), "snapshot", region, partition);
            case "ec2:CreateTags", "ec2:DeleteTags" -> tags(context, region, partition);
            default -> List.of("*");
        };
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

    private static List<String> tags(ContainerRequestContext context, String region, String partition) {
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
            } else {
                // Other EC2 resource types retain their existing unscoped authorization.
                resources.add("*");
            }
        }
        return resources.isEmpty() ? List.of("*") : List.copyOf(resources);
    }

    private static String arn(String partition, String region, String type, String id) {
        // EC2 image and snapshot ARNs omit the account component.
        return new AwsArnUtils.Arn(partition, "ec2", region, "", type + "/" + id).toString();
    }
}
