package io.github.hectorvent.floci.services.lambdaweb;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public final class LambdaWebInternalServerExceptionMapper implements ExceptionMapper<LambdaWebInternalServerException> {

    @Override
    public Response toResponse(LambdaWebInternalServerException exception) {
        return Response.status(exception.getHttpStatus())
                .type(MediaType.APPLICATION_JSON)
                .header("X-Amzn-Errortype", exception.getErrorCode())
                .entity(Map.of())
                .build();
    }
}
