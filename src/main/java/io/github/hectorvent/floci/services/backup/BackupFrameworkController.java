package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.backup.BackupFrameworkService.Framework;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.Map;

@Path("/audit/frameworks")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BackupFrameworkController {

    private static final Map<String, String> ACTIONS = Map.of(
            "createFramework", "backup:CreateFramework",
            "describeFramework", "backup:DescribeFramework",
            "listFrameworks", "backup:ListFrameworks",
            "updateFramework", "backup:UpdateFramework",
            "deleteFramework", "backup:DeleteFramework");

    private final BackupFrameworkService service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public BackupFrameworkController(BackupFrameworkService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    public static String iamAction(String method) {
        return ACTIONS.get(method);
    }

    @POST
    public Response createFramework(@Context HttpHeaders headers, String body) throws IOException {
        Framework framework = service.create(regions.resolveRegion(headers), request(body));
        return Response.ok(identity(framework)).build();
    }

    @GET
    @Path("/{frameworkName}")
    public Response describeFramework(@Context HttpHeaders headers, @PathParam("frameworkName") String name) {
        Framework framework = service.describe(regions.resolveRegion(headers), name);
        ObjectNode result = summary(framework);
        result.remove("NumberOfControls");
        result.set("FrameworkControls", mapper.valueToTree(framework.controls()));
        result.put("IdempotencyToken", framework.creatorToken());
        result.put("FrameworkStatus", "UNAVAILABLE");
        return Response.ok(result).build();
    }

    @GET
    public Response listFrameworks(@Context HttpHeaders headers, @QueryParam("MaxResults") String maxResults,
                                   @QueryParam("NextToken") String nextToken) {
        PaginatedResult<Framework> page = service.list(regions.resolveRegion(headers),
                Pagination.parseMaxResults(maxResults, "InvalidParameterValueException"), nextToken);
        ObjectNode result = mapper.createObjectNode();
        page.items().forEach(framework -> result.withArray("Frameworks").add(summary(framework)));
        result.withArray("Frameworks");
        if (page.nextToken() != null) {
            result.put("NextToken", page.nextToken());
        }
        return Response.ok(result).build();
    }

    @PUT
    @Path("/{frameworkName}")
    public Response updateFramework(@Context HttpHeaders headers, @PathParam("frameworkName") String name,
                                    String body) throws IOException {
        Framework framework = service.update(regions.resolveRegion(headers), name, request(body));
        ObjectNode result = identity(framework);
        result.put("CreationTime", framework.createdMillis() / 1000.0);
        return Response.ok(result).build();
    }

    @DELETE
    @Path("/{frameworkName}")
    public Response deleteFramework(@Context HttpHeaders headers, @PathParam("frameworkName") String name) {
        service.delete(regions.resolveRegion(headers), name);
        return Response.ok().build();
    }

    private JsonNode request(String body) throws IOException {
        return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
    }

    private ObjectNode identity(Framework framework) {
        ObjectNode result = mapper.createObjectNode();
        result.put("FrameworkName", framework.name());
        result.put("FrameworkArn", framework.arn());
        return result;
    }

    private ObjectNode summary(Framework framework) {
        ObjectNode result = identity(framework);
        if (framework.description() != null) {
            result.put("FrameworkDescription", framework.description());
        }
        result.put("CreationTime", framework.createdMillis() / 1000.0);
        result.put("NumberOfControls", framework.controls().size());
        result.put("DeploymentStatus", "COMPLETED");
        return result;
    }
}
