package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.appconfig.AppConfigDataService.ConfigurationData;
import io.github.hectorvent.floci.services.appconfig.AppConfigDataService.LegacyConfiguration;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppConfigDataController {
    private static final Logger LOG = Logger.getLogger(AppConfigDataController.class);

    private final AppConfigDataService service;
    private final ObjectMapper objectMapper;

    @Inject
    public AppConfigDataController(AppConfigDataService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/configurationsessions")
    public Response startConfigurationSession(String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        String token = service.startConfigurationSession(request);
        return Response.status(201).entity(Map.of("InitialConfigurationToken", token)).build();
    }

    @GET
    @Path("/applications/{application}/environments/{environment}/configurations/{configuration}")
    public Response getConfiguration(@PathParam("application") String application,
                                     @PathParam("environment") String environment,
                                     @PathParam("configuration") String configuration,
                                     @QueryParam("client_id") String clientId,
                                     @QueryParam("client_configuration_version") String clientVersion,
                                     @HeaderParam("Accept") String accept) {
        LegacyConfiguration data = service.getConfiguration(
                application, environment, configuration, clientId, clientVersion, accept);
        return Response.status(data.configurationVersion().equals(clientVersion) ? 204 : 200)
                .entity(data.content()).type(data.contentType())
                .header("Configuration-Version", data.configurationVersion()).build();
    }

    // Bound to an internal path rather than the public "/configuration" so that
    // an S3 bucket named "configuration" is not shadowed by this route (issue
    // #1294). AppConfigConfigurationRouteFilter rewrites genuine GetLatestConfiguration
    // requests (those carrying a configuration_token) onto this path; requests
    // without a token fall through to S3's /{bucket} handler.
    @GET
    @Path(AppConfigConfigurationRouteFilter.INTERNAL_PATH)
    public Response getLatestConfiguration(@QueryParam("configuration_token") String token,
                                           @HeaderParam("Accept") String accept) {
        ConfigurationData data = service.getLatestConfiguration(token, accept);
        return Response.ok(data.content())
                .header("Content-Type", data.contentType())
                .header("Version-Label", data.configurationVersion())
                .header("Next-Poll-Configuration-Token", data.nextPollConfigurationToken())
                .header("Next-Poll-Interval-In-Seconds", data.nextPollIntervalInSeconds())
                .build();
    }
}
