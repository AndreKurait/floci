package io.floci.aws.services.lambda;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/2024-08-31/functions/{functionName}/recursion-config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaRecursionConfigController {

    private final LambdaService lambdaService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaRecursionConfigController(LambdaService lambdaService, RegionResolver regionResolver,
                                          ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    public Response getFunctionRecursionConfig(@Context HttpHeaders headers,
                                              @PathParam("functionName") String functionName) {
        String value = lambdaService.getFunctionRecursionConfig(
                regionResolver.resolveRegion(headers), functionName);
        return Response.ok(objectMapper.createObjectNode().put("RecursiveLoop", value)).build();
    }

    @PUT
    public Response putFunctionRecursionConfig(@Context HttpHeaders headers,
                                              @PathParam("functionName") String functionName, String body) {
        JsonNode request;
        try {
            request = body == null ? null : objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new AwsException("SerializationException", "Invalid JSON request body", 400);
        }
        if (request == null || !request.isObject()) {
            throw new AwsException("SerializationException", "Expected a JSON object", 400);
        }
        JsonNode recursiveLoop = request.get("RecursiveLoop");
        if (recursiveLoop != null && !recursiveLoop.isNull() && !recursiveLoop.isTextual()) {
            throw new AwsException("SerializationException", "RecursiveLoop must be a string", 400);
        }
        String value = lambdaService.putFunctionRecursionConfig(regionResolver.resolveRegion(headers),
                functionName, recursiveLoop == null || recursiveLoop.isNull() ? null : recursiveLoop.textValue());
        return Response.ok(objectMapper.createObjectNode().put("RecursiveLoop", value)).build();
    }
}
