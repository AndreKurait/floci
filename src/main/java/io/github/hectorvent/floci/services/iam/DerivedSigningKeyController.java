package io.github.hectorvent.floci.services.iam;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * Opt-in local endpoint that returns one SigV4 derived signing key (kSigning, or kService when
 * allowed) for a session this Floci issued, so a verifier outside Floci can check that session's
 * signatures. The secret access key is never returned. Only the one configured caller may call it,
 * SigV4-signed with signing name {@code floci}; nothing on this path is logged.
 */
@Singleton
@Path("/_floci/iam/derived-signing-keys")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class DerivedSigningKeyController {

    /** The one path the unknown-signing-scope refusal lets through, and only for {@code floci}. */
    public static final String DERIVE_PATH = "/_floci/iam/derived-signing-keys/derive";
    private static final String PATH_PREFIX = "/_floci/iam/derived-signing-keys/";

    private final DerivedSigningKeyEndpoint endpoint;
    private final DerivedSigningKeyCallerAuthenticator authenticator;
    private final DerivedSigningKeyService service;
    private final CurrentVertxRequest currentVertxRequest;
    private final Clock clock;

    @Inject
    public DerivedSigningKeyController(DerivedSigningKeyEndpoint endpoint, IamService iam,
                                       IssuedSessionVerifier verifier, CurrentVertxRequest currentVertxRequest) {
        this(endpoint, new DerivedSigningKeyCallerAuthenticator(iam), new DerivedSigningKeyService(verifier),
                currentVertxRequest, Clock.systemUTC());
    }

    DerivedSigningKeyController(DerivedSigningKeyEndpoint endpoint, DerivedSigningKeyCallerAuthenticator authenticator,
                                DerivedSigningKeyService service, CurrentVertxRequest currentVertxRequest,
                                Clock clock) {
        this.endpoint = endpoint;
        this.authenticator = authenticator;
        this.service = service;
        this.currentVertxRequest = currentVertxRequest;
        this.clock = clock;
    }

    /** Whether a request for {@code path} signed for {@code signingName} is this endpoint's caller. */
    public static boolean isCallerScope(String path, String signingName) {
        if (path == null) {
            return false;
        }
        String p = path.startsWith("/") ? path : "/" + path;
        return DerivedSigningKeyCallerAuthenticator.SIGNING_NAME.equals(signingName) && DERIVE_PATH.equals(p);
    }

    /** Paths no browser origin may read, whatever the CORS settings. */
    public static boolean isKeyPath(String path) {
        if (path == null) {
            return false;
        }
        String p = path.startsWith("/") ? path : "/" + path;
        return p.startsWith(PATH_PREFIX);
    }

    @POST
    @Path("/derive")
    public Response derive(InputStream body, @Context HttpHeaders headers, @Context UriInfo uriInfo) {
        Optional<DerivedSigningKeySettings> settings = endpoint.settings();
        if (settings.isEmpty()) {
            return error(404, "DerivedSigningKeysDisabled");
        }
        byte[] bytes;
        try {
            bytes = body == null ? new byte[0] : body.readNBytes(DerivedSigningKeyService.MAX_BODY_BYTES + 1);
        } catch (IOException error) {
            return error(400, "InvalidDerivedSigningKeyRequest");
        }
        if (bytes.length > DerivedSigningKeyService.MAX_BODY_BYTES) {
            return error(400, "InvalidDerivedSigningKeyRequest");
        }
        HttpServerRequest request = vertxRequest();
        String rawPath = request != null ? request.path() : uriInfo.getRequestUri().getRawPath();
        String rawQuery = request != null ? request.query() : uriInfo.getRequestUri().getRawQuery();
        DerivedSigningKeyCallerAuthenticator.Outcome outcome;
        try {
            outcome = authenticator.authenticate(settings.get(), "POST", rawPath, rawQuery,
                    DerivedSigningKeyCallerAuthenticator.headerLookup(headers.getRequestHeaders()), bytes,
                    clock.instant());
        } catch (RuntimeException error) {
            return error(403, "DerivedSigningKeyCallerRefused");
        }
        // Exhaustive on purpose: a new outcome must be mapped, never fall through to issuing a key.
        Response refusal = switch (outcome) {
            case ACCEPTED -> null;
            case REFUSED -> error(403, "DerivedSigningKeyCallerRefused");
            case REPLAYED -> error(403, "DerivedSigningKeyReplayed");
            case BUSY -> error(429, "DerivedSigningKeyRateExceeded");
        };
        if (refusal != null) {
            return refusal;
        }
        try {
            return Response.ok(service.derive(settings.get(), bytes, clock.instant()))
                    .header("Cache-Control", "no-store").build();
        } catch (DerivedSigningKeyService.Refused refused) {
            return switch (refused.refusal) {
                case INVALID_REQUEST -> error(400, "InvalidDerivedSigningKeyRequest");
                case SCOPE_NOT_ALLOWED -> error(403, "DerivedSigningKeyScopeNotAllowed");
                case CREDENTIAL_REFUSED -> error(403, "DerivedSigningKeyRefused");
            };
        } catch (RuntimeException error) {
            return error(403, "DerivedSigningKeyRefused");
        }
    }

    private HttpServerRequest vertxRequest() {
        RoutingContext routingContext = currentVertxRequest != null ? currentVertxRequest.getCurrent() : null;
        return routingContext != null ? routingContext.request() : null;
    }

    private static Response error(int status, String code) {
        return Response.status(status).entity(Map.of("code", code)).header("Cache-Control", "no-store").build();
    }
}
