package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/2021-07-20/functions/{functionName}/runtime-management-config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaRuntimeManagementController {

    private final LambdaRuntimeManagementService service;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    public LambdaRuntimeManagementController(
            LambdaRuntimeManagementService service, RegionResolver regions, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.mapper = mapper;
    }

    @GET
    public Response getRuntimeManagementConfig(@Context HttpHeaders headers,
                                               @PathParam("functionName") String functionName,
                                               @QueryParam("Qualifier") String qualifier) {
        return response(service.get(regions.resolveRegion(headers), functionName, qualifier));
    }

    @PUT
    public Response putRuntimeManagementConfig(@Context HttpHeaders headers,
                                               @PathParam("functionName") String functionName,
                                               @QueryParam("Qualifier") String qualifier, String body) {
        JsonNode request;
        try {
            request = body == null ? null
                    : mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (JsonProcessingException malformed) {
            throw LambdaRuntimeManagementReference.invalid("Could not parse request body into JSON");
        }
        return response(service.put(regions.resolveRegion(headers), functionName, qualifier, request));
    }

    private Response response(LambdaRuntimeManagementService.Result result) {
        ObjectNode body = mapper.createObjectNode();
        body.put("FunctionArn", result.functionArn());
        body.put("UpdateRuntimeOn", result.configuration().updateRuntimeOn());
        body.put("RuntimeVersionArn", result.configuration().runtimeVersionArn());
        return Response.ok(body).build();
    }
}
