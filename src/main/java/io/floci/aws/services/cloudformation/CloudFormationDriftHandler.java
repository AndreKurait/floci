package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsQueryResponse;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.XmlBuilder;
import io.floci.aws.services.cloudformation.model.StackDriftRecord.Detection;
import io.floci.aws.services.cloudformation.model.StackDriftRecord.Difference;
import io.floci.aws.services.cloudformation.model.StackDriftRecord.ResourceDrift;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Query-protocol rendering and input bounds for the four native drift operations. */
@ApplicationScoped
public class CloudFormationDriftHandler {
    private static final String NS = "http://cloudformation.amazonaws.com/doc/2010-05-15/";
    private final CloudFormationDriftService drift;

    public CloudFormationDriftHandler(CloudFormationDriftService drift) {
        this.drift = drift;
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        XmlBuilder xml = new XmlBuilder().start(action + "Response", NS).start(action + "Result");
        String name = params.getFirst("StackName");
        switch (action) {
            case "DetectStackResourceDrift" -> resource(xml, "StackResourceDrift",
                    drift.detectResource(name, params.getFirst("LogicalResourceId"), region));
            case "DetectStackDrift" -> xml.elem("StackDriftDetectionId",
                    drift.detectStack(name, members(params, "LogicalResourceIds.member."), region));
            case "DescribeStackDriftDetectionStatus" -> {
                Detection result = drift.detection(params.getFirst("StackDriftDetectionId"), region);
                xml.elem("StackId", result.stackId()).elem("StackDriftDetectionId", result.id())
                        .elem("DetectionStatus", "DETECTION_COMPLETE").elem("StackDriftStatus", result.stackStatus())
                        .elem("DriftedStackResourceCount", String.valueOf(result.driftedResourceCount()))
                        .elem("Timestamp", DateTimeFormatter.ISO_INSTANT.format(result.timestamp()));
            }
            case "DescribeStackResourceDrifts" -> {
                PaginatedResult<ResourceDrift> page = drift.resources(name,
                        members(params, "StackResourceDriftStatusFilters.member."),
                        maxResults(params.getFirst("MaxResults")), params.getFirst("NextToken"), region);
                xml.start("StackResourceDrifts");
                page.items().forEach(result -> resource(xml, "member", result));
                xml.end("StackResourceDrifts");
                if (page.nextToken() != null) {
                    xml.elem("NextToken", page.nextToken());
                }
            }
            default -> throw new IllegalArgumentException("Unsupported drift action " + action);
        }
        xml.end(action + "Result").raw(AwsQueryResponse.responseMetadata()).end(action + "Response");
        return Response.ok(xml.build()).type("text/xml").build();
    }

    public static List<String> members(Map<String, List<String>> params, String prefix) {
        Map<Integer, String> ordered = new TreeMap<>();
        params.forEach((key, values) -> {
            if (key.startsWith(prefix)) {
                try {
                    int index = Integer.parseInt(key.substring(prefix.length()));
                    if (index < 1 || values == null || values.size() != 1
                            || ordered.putIfAbsent(index, values.getFirst()) != null) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException invalid) {
                    throw new AwsException("ValidationError", "Invalid list member " + key, 400);
                }
            }
        });
        return new ArrayList<>(ordered.values());
    }

    private static Integer maxResults(String text) {
        try {
            return text == null ? null : Integer.valueOf(text);
        } catch (NumberFormatException invalid) {
            throw new AwsException("ValidationError", "MaxResults must be an integer", 400);
        }
    }

    private static void resource(XmlBuilder xml, String element, ResourceDrift result) {
        xml.start(element).elem("StackId", result.stackId()).elem("LogicalResourceId", result.logicalId())
                .elem("PhysicalResourceId", result.physicalId()).elem("ResourceType", result.resourceType())
                .elem("StackResourceDriftStatus", result.status())
                .elem("Timestamp", DateTimeFormatter.ISO_INSTANT.format(result.timestamp()));
        if (result.expectedProperties() != null) {
            xml.elem("ExpectedProperties", result.expectedProperties()).elem("ActualProperties", result.actualProperties());
        }
        xml.start("PropertyDifferences");
        for (Difference difference : result.differences()) {
            xml.start("member").elem("PropertyPath", difference.propertyPath())
                    .elem("ExpectedValue", difference.expectedValue()).elem("ActualValue", difference.actualValue())
                    .elem("DifferenceType", "NOT_EQUAL").end("member");
        }
        xml.end("PropertyDifferences").end(element);
    }
}
