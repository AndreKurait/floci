package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.XmlBuilder;
import io.floci.aws.services.ssm.SsmChannelAuthentication.Caller;
import io.floci.aws.services.ssmmessages.ChannelAuthentication;
import io.floci.aws.services.ssmmessages.SsmMessageGateway.Node;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** The agent's HTTPS token endpoint and WebSocket control-channel upgrade. */
@Path("/v1/control-channel")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_XML)
@ApplicationScoped
public class SsmMessagesController {
    private static final Logger LOG = Logger.getLogger(SsmMessagesController.class);

    private final SsmMessagesService service;
    private final SsmChannelAuthentication authentication;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final CurrentVertxRequest requests;
    private final ChannelAuthentication gatewayAuthentication;

    public SsmMessagesController(SsmMessagesService service, SsmChannelAuthentication authentication,
                                 ObjectMapper mapper, EmulatorConfig config, CurrentVertxRequest requests) {
        this(service, authentication, mapper, config, requests, null);
    }

    @Inject
    public SsmMessagesController(SsmMessagesService service, SsmChannelAuthentication authentication,
                                 ObjectMapper mapper, EmulatorConfig config, CurrentVertxRequest requests,
                                 ChannelAuthentication gatewayAuthentication) {
        this.gatewayAuthentication = gatewayAuthentication;
        this.service = service;
        this.authentication = authentication;
        this.mapper = mapper;
        this.config = config;
        this.requests = requests;
    }

    @POST
    @Path("/{instanceId}")
    public Response createControlChannel(@PathParam("instanceId") String instanceId, String body) {
        try {
            requireEnabled();
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 4096) {
                throw new AwsException("ValidationException", "The channel request is too large.", 400);
            }
            Caller caller = authenticate(requests.getCurrent().request(), bytes, "CreateControlChannel", instanceId);
            String token = service.create(caller, instanceId, mapper.readTree(bytes));
            return Response.status(201).entity(new XmlBuilder().start("ChannelOutput")
                    .elem("MessageSchemaVersion", "1.0").elem("TokenValue", token)
                    .end("ChannelOutput").build()).build();
        } catch (AwsException rejected) {
            return errorResponse(rejected);
        } catch (Exception invalid) {
            LOG.debugv(invalid, "Invalid CreateControlChannel request");
            AwsException error = new AwsException("ValidationException", "Invalid channel request.", 400);
            return errorResponse(error);
        }
    }

    void routes(@Observes Router router) {
        router.get("/v1/control-channel/:instanceId").handler(this::upgrade);
    }

    private void upgrade(RoutingContext context) {
        HttpServerRequest request = context.request();
        if (request.method() != HttpMethod.GET || !"websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
            context.next();
            return;
        }
        try {
            requireEnabled();
            String instanceId = context.pathParam("instanceId");
            SsmMessagesService.validateInstanceId(instanceId);
            if (!"subscribe".equals(request.getParam("role")) || !"input".equals(request.getParam("stream"))) {
                throw new AwsException("ValidationException", "Invalid control-channel direction.", 400);
            }
            Caller caller = authenticate(request, new byte[0], "OpenControlChannel", instanceId);
            authentication.authorizeNode(caller, instanceId);
            request.toWebSocket().onSuccess(socket -> service.open(caller, instanceId, socket))
                    .onFailure(error -> LOG.debugv(error, "SSM control-channel upgrade failed"));
        } catch (AwsException rejected) {
            context.response().setStatusCode(rejected.getHttpStatus())
                    .putHeader("Content-Type", MediaType.APPLICATION_JSON)
                    .putHeader("x-amzn-errortype", rejected.getErrorCode())
                    .end(new JsonObject().put("message", rejected.getMessage()).encode());
        }
    }

    private Caller authenticate(HttpServerRequest request, byte[] bytes, String action, String instanceId) {
        Caller caller = authentication.authenticate(request.method().name(), URI.create(request.absoluteURI()),
                request::getHeader, request::getParam, bytes, action,
                request.remoteAddress().hostAddress(), request.isSSL());
        if (gatewayAuthentication != null) {
            gatewayAuthentication.authenticateAgent(request, bytes, "ssmmessages:" + action,
                    new Node(caller.account(), caller.region(), instanceId));
        }
        return caller;
    }

    private void requireEnabled() {
        if (!config.services().ssm().enabled()) {
            throw new AwsException("UnknownOperationException", "Systems Manager is disabled.", 503);
        }
    }

    private static Response errorResponse(AwsException error) {
        return Response.status(error.getHttpStatus()).type(MediaType.APPLICATION_JSON)
                .header("x-amzn-errortype", error.getErrorCode())
                .entity(Map.of("message", error.getMessage())).build();
    }
}
