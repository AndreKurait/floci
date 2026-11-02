package io.github.hectorvent.floci.services.resourcegroups;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.services.resourcegroups.model.ResourceGroup;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

@Path(ResourceGroupsRouteFilter.INTERNAL_PREFIX)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ResourceGroupsController {
    private final ResourceGroupsService service;
    private final RegionResolver regions;
    private final RequestContext context;
    private final ObjectMapper mapper;

    @Inject
    public ResourceGroupsController(ResourceGroupsService service, RegionResolver regions,
                                    RequestContext context, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.context = context;
        this.mapper = mapper;
    }

    @POST
    @Path("/groups")
    public Response createGroup(@Context HttpHeaders headers, String body) {
        ResourceGroup group = service.create(regions.resolveRegion(headers), context.getAccountId(), parse(body));
        return Response.ok(Map.of("Group", groupMetadata(group), "Tags", group.tags(),
                "GroupConfiguration", Map.of("Configuration", group.configuration(), "Status", "UPDATE_COMPLETE"))).build();
    }

    @POST
    @Path("/get-group")
    public Response getGroup(@Context HttpHeaders headers, String body) {
        return Response.ok(Map.of("Group", groupMetadata(
                service.get(regions.resolveRegion(headers), context.getAccountId(), parse(body))))).build();
    }

    @POST
    @Path("/delete-group")
    public Response deleteGroup(@Context HttpHeaders headers, String body) {
        return Response.ok(Map.of("Group", groupMetadata(
                service.delete(regions.resolveRegion(headers), context.getAccountId(), parse(body))))).build();
    }

    @POST
    @Path("/group-resources")
    public Response groupResources(@Context HttpHeaders headers, String body) {
        return Response.ok(service.add(regions.resolveRegion(headers), context.getAccountId(), parse(body))).build();
    }

    @POST
    @Path("/list-group-resources")
    public Response listGroupResources(@Context HttpHeaders headers, String body) {
        return Response.ok(service.list(regions.resolveRegion(headers), context.getAccountId(), parse(body))).build();
    }

    @POST
    @Path("/groups-list")
    public Response listGroups(@Context HttpHeaders headers, @QueryParam("maxResults") String maximum,
                               @QueryParam("nextToken") String token, String body) {
        JsonNode request = parse(body == null || body.isBlank() ? "{}" : body);
        if (!(request instanceof ObjectNode object)) {
            throw new AwsException("BadRequestException", "The request must contain a JSON object.", 400);
        }
        // These two ListGroups members are query parameters in the AWS REST protocol.
        object.remove("MaxResults");
        object.remove("NextToken");
        if (maximum != null) {
            try {
                object.put("MaxResults", Integer.parseInt(maximum));
            } catch (NumberFormatException error) {
                throw new AwsException("BadRequestException", "MaxResults must be an integer between 1 and 50.", 400);
            }
        }
        if (token != null) {
            object.put("NextToken", token);
        }
        return Response.ok(service.listGroups(regions.resolveRegion(headers), context.getAccountId(), object)).build();
    }

    private Map<String, Object> groupMetadata(ResourceGroup group) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("GroupArn", group.arn());
        result.put("Name", group.name());
        if (group.description() != null) {
            result.put("Description", group.description());
        }
        return result;
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body == null ? "{}" : body);
        } catch (JsonProcessingException error) {
            throw new AwsException("BadRequestException", "The request must contain a JSON object.", 400);
        }
    }
}
