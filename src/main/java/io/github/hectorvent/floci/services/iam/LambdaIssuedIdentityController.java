package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Opt-in observations of issuer-owned executions, separate from AWS APIs and permission decisions. */
@Path("/_floci/lambda/issued-identities")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class LambdaIssuedIdentityController {
    private final LambdaIssuedIdentityVerifier verifier;
    private final boolean enabled;

    @Inject
    public LambdaIssuedIdentityController(LambdaIssuedIdentityVerifier verifier, EmulatorConfig config) {
        this.verifier = verifier;
        this.enabled = config.services().iam().enabled() && config.services().iam().lambdaIdentityVerificationEnabled();
    }

    @POST
    @Path("/verify")
    public Response verify(InputStream body) { return handle(body, true); }

    @POST
    @Path("/lookup")
    public Response lookup(InputStream body) { return handle(body, false); }

    private Response handle(InputStream body, boolean verify) {
        if (!enabled) {
            return error(404, "LambdaIdentityVerificationDisabled");
        }
        Map<String, String> request;
        try {
            request = IssuedSessionController.parse(body, verify);
        } catch (IOException | IllegalArgumentException error) {
            return error(400, "InvalidIssuedSessionRequest");
        }
        try {
            return Response.ok(verifier.observe(request, verify)).header("Cache-Control", "no-store").build();
        } catch (RuntimeException error) {
            return error(403, "LambdaIdentityVerificationFailed");
        }
    }

    private static Response error(int status, String code) {
        return Response.status(status).entity(Map.of("code", code)).header("Cache-Control", "no-store").build();
    }
}
