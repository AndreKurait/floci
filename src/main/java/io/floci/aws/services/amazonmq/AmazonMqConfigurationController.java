package io.floci.aws.services.amazonmq;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.IamEnforcementFilter;
import io.floci.aws.services.amazonmq.model.ConfigurationState.Configuration;
import io.floci.aws.services.amazonmq.model.ConfigurationState.Revision;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST JSON configuration operations, routed from the public MQ SDK paths. */
@Path(AmazonMqConfigurationRouteFilter.INTERNAL_PREFIX + "/v1/configurations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AmazonMqConfigurationController {

    private final AmazonMqConfigurationService service;
    private final IamEnforcementFilter authorization;

    @Inject
    public AmazonMqConfigurationController(AmazonMqConfigurationService service, IamEnforcementFilter authorization) {
        this.service = service;
        this.authorization = authorization;
    }

    @POST
    public Map<String, Object> createConfiguration(JsonNode request, @HeaderParam("Authorization") String header,
                                                   @Context UriInfo uri) {
        requireObject(request);
        Map<String, String> tags = AmazonMqConfigurationService.requestTags(request.get("tags"));
        Map<String, List<String>> conditions = AmazonMqConfigurationService.requestTagConditions(tags);
        String credential = IamEnforcementFilter.requestAuthorization(
                header == null || header.isBlank() ? null : header, uri.getQueryParameters());
        Configuration created = service.create(new AmazonMqConfigurationService.Create(string(request, "name"),
                string(request, "engineType"), string(request, "engineVersion"),
                string(request, "authenticationStrategy"), tags), arn ->
                authorization.authorizeAdditionalResource(credential, "mq:CreateTags", arn, conditions));
        return createResponse(created);
    }

    @GET
    @Path("/{configurationId}")
    public Map<String, Object> describeConfiguration(@PathParam("configurationId") String id) {
        return configurationResponse(service.describe(id));
    }

    @PUT
    @Path("/{configurationId}")
    public Map<String, Object> updateConfiguration(@PathParam("configurationId") String id, JsonNode request) {
        requireObject(request);
        Configuration updated = service.update(id, string(request, "data"), string(request, "description"));
        Map<String, Object> result = createResponse(updated);
        result.remove("authenticationStrategy");
        result.put("warnings", List.of());
        return result;
    }

    @DELETE
    @Path("/{configurationId}")
    public Map<String, Object> deleteConfiguration(@PathParam("configurationId") String id) {
        service.delete(id);
        return Map.of("configurationId", id);
    }

    @GET
    @Path("/{configurationId}/revisions/{configurationRevision}")
    public Map<String, Object> describeConfigurationRevision(@PathParam("configurationId") String id,
                                                             @PathParam("configurationRevision") String revision) {
        Revision value = service.revision(id, revision);
        Map<String, Object> result = revisionResponse(value);
        result.remove("revision");
        result.put("configurationId", id);
        if (value.data() != null) {
            result.put("data", value.data());
        }
        return result;
    }

    @GET
    @Path("/{configurationId}/revisions")
    public Map<String, Object> listConfigurationRevisions(@PathParam("configurationId") String id,
                                                          @QueryParam("maxResults") String maxResults,
                                                          @QueryParam("nextToken") String nextToken) {
        AmazonMqConfigurationService.Page<Revision> page = service.listRevisions(id, maxResults(maxResults), nextToken);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configurationId", id);
        result.put("revisions", page.items().stream().map(AmazonMqConfigurationController::revisionResponse).toList());
        token(result, page.nextToken());
        return result;
    }

    @GET
    public Map<String, Object> listConfigurations(@QueryParam("maxResults") String maxResults,
                                                  @QueryParam("nextToken") String nextToken) {
        AmazonMqConfigurationService.Page<Configuration> page = service.list(maxResults(maxResults), nextToken);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configurations", page.items().stream()
                .map(AmazonMqConfigurationController::configurationResponse).toList());
        token(result, page.nextToken());
        return result;
    }

    private static Map<String, Object> createResponse(Configuration value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", value.id());
        result.put("arn", value.arn());
        result.put("name", value.name());
        result.put("created", value.created().toString());
        result.put("authenticationStrategy", value.authenticationStrategy());
        result.put("latestRevision", revisionResponse(value.latest()));
        return result;
    }

    private static Map<String, Object> configurationResponse(Configuration value) {
        Map<String, Object> result = createResponse(value);
        result.put("engineType", value.engineType());
        result.put("engineVersion", value.engineVersion());
        result.put("description", value.latest().description());
        result.put("tags", value.tags());
        return result;
    }

    private static Map<String, Object> revisionResponse(Revision value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", value.revision());
        result.put("created", value.created().toString());
        result.put("description", value.description());
        return result;
    }

    private static void token(Map<String, Object> result, String nextToken) {
        if (nextToken != null) {
            result.put("nextToken", nextToken);
        }
    }

    private static Integer maxResults(String value) {
        if (value == null) {
            return null;
        }
        if (!value.matches("[0-9]{1,3}")) {
            throw new AwsException("BadRequestException", "Invalid maxResults", 400);
        }
        return Integer.valueOf(value);
    }

    private static String string(JsonNode request, String member) {
        JsonNode value = request.get(member);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new AwsException("BadRequestException", member + " must be a string", 400);
        }
        return value.textValue();
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw new AwsException("BadRequestException", "Expected a JSON object", 400);
        }
    }
}
