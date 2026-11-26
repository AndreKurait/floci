package io.floci.aws.services.ssm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.XmlBuilder;
import io.floci.aws.services.ssm.SsmChannelAuthentication.Caller;
import io.floci.aws.services.ssmmessages.DataChannelHandler;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.inject.Vetoed;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * The ssmmessages data channel of a Session Manager session: the agent's signed CreateDataChannel token
 * endpoint and the WebSocket both the agent and the session's client open at the stream URL.
 */
@Vetoed
public class SsmDataChannelController implements DataChannelHandler {
    private static final Logger LOG = Logger.getLogger(SsmDataChannelController.class);
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9+=,.@_-]{1,96}");

    private final SsmSessionService sessions;
    private final SsmChannelAuthentication authentication;
    private final ObjectMapper mapper;
    private final EmulatorConfig config;
    private final CurrentVertxRequest requests;

    public SsmDataChannelController(SsmSessionService sessions, SsmChannelAuthentication authentication,
                                    ObjectMapper mapper, EmulatorConfig config, CurrentVertxRequest requests) {
        this.sessions = sessions;
        this.authentication = authentication;
        this.mapper = mapper;
        this.config = config;
        this.requests = requests;
    }

    public Response createDataChannel(String sessionId, String body) {
        try {
            requireEnabled();
            validateSessionId(sessionId);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 4096) {
                throw new AwsException("ValidationException", "The channel request is too large.", 400);
            }
            HttpServerRequest request = requests.getCurrent().request();
            Caller caller = authentication.authenticate(request.method().name(), URI.create(request.absoluteURI()),
                    request::getHeader, request::getParam, bytes, "CreateDataChannel",
                    request.remoteAddress().hostAddress(), request.isSSL());
            String token = sessions.createDataChannel(caller, sessionId, mapper.readTree(bytes));
            return Response.status(201).entity(new XmlBuilder().start("CreateDataChannelResponse")
                    .elem("MessageSchemaVersion", "1.0").elem("TokenValue", token)
                    .end("CreateDataChannelResponse").build()).build();
        } catch (AwsException rejected) {
            return Response.status(rejected.getHttpStatus()).entity(errorXml(rejected)).build();
        } catch (Exception invalid) {
            LOG.debugv(invalid, "Invalid CreateDataChannel request");
            AwsException error = new AwsException("ValidationException", "Invalid channel request.", 400);
            return Response.status(error.getHttpStatus()).entity(errorXml(error)).build();
        }
    }

    @Override
    public boolean supportsSession(String sessionId) {
        return config.services().ssm().enabled() && sessionId != null && sessions.holds(sessionId);
    }

    /**
     * The agent's upgrade is SigV4-signed by its node. A client's may be signed, presigned or carry no
     * signature; its session token, sent in the first text frame, authenticates it either way, and a
     * signature it does carry must verify.
     */
    @Override
    public void open(String sessionId, RoutingContext context) {
        HttpServerRequest request = context.request();
        if (request.method() != HttpMethod.GET || !"websocket".equalsIgnoreCase(request.getHeader("Upgrade"))) {
            context.next();
            return;
        }
        if (!supportsSession(sessionId)) {
            context.next();
            return;
        }
        try {
            validateSessionId(sessionId);
            if (!"publish_subscribe".equals(request.getParam("role"))) {
                throw new AwsException("ValidationException", "Invalid data-channel role.", 400);
            }
            String authorization = request.getHeader("Authorization");
            boolean signed = (authorization != null && !authorization.isBlank())
                    || request.getParam("X-Amz-Signature") != null;
            Caller caller = signed ? authentication.verify(request.method().name(),
                    URI.create(request.absoluteURI()), request::getHeader, request::getParam, new byte[0],
                    request.remoteAddress().hostAddress(), request.isSSL()) : null;
            request.toWebSocket().onSuccess(socket -> sessions.openDataChannel(caller, sessionId, socket))
                    .onFailure(error -> LOG.debugv(error, "SSM data-channel upgrade failed"));
        } catch (AwsException rejected) {
            context.response().setStatusCode(rejected.getHttpStatus())
                    .putHeader("Content-Type", MediaType.APPLICATION_XML).end(errorXml(rejected));
        }
    }

    private static void validateSessionId(String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            throw new AwsException("ValidationException", "Invalid session identifier.", 400);
        }
    }

    private void requireEnabled() {
        if (!config.services().ssm().enabled()) {
            throw new AwsException("UnknownOperationException", "Systems Manager is disabled.", 503);
        }
    }

    private static String errorXml(AwsException error) {
        return new XmlBuilder().start(error.getErrorCode()).elem("Message", error.getMessage())
                .end(error.getErrorCode()).build();
    }
}
