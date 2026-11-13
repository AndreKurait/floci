package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.account.AccountController;
import io.github.hectorvent.floci.services.iam.IamService;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Actual filter-selection boundaries; full valid signatures are exercised by the HTTP fixture. */
class AccountRegionSignatureScopeTest {
    @ParameterizedTest
    @ValueSource(strings = {"listRegions", "getRegionOptStatus", "enableRegion", "disableRegion"})
    void onlyTheFourMatchedRegionMethodsRejectAnotherSigningService(String method) throws Exception {
        Response response = filtered(resource(AccountController.class, method), "s3");
        assertEquals(403, response.getStatus());
        assertEquals("InvalidSignatureException", response.getHeaderString("X-Amzn-Errortype"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"listRegions", "getRegionOptStatus", "enableRegion", "disableRegion"})
    void anAccountScopeContinuesToTheExistingDateValidation(String method) throws Exception {
        assertDateValidation(filtered(resource(AccountController.class, method), "account"));
    }

    @Test
    void nullAndUnrelatedResourcesAndAllAlternateContactsKeepTheirExistingValidation() throws Exception {
        assertDateValidation(filtered(null, "s3"));
        assertDateValidation(filtered(resource(null, null), "s3"));
        assertDateValidation(filtered(resource(AccountController.class, null), "s3"));
        assertDateValidation(filtered(resource(OtherController.class, "enableRegion"), "s3"));
        for (String method : List.of("putAlternateContact", "getAlternateContact", "deleteAlternateContact")) {
            assertDateValidation(filtered(resource(AccountController.class, method), "s3"));
        }
    }

    @Test
    void disabledOrdinarySignatureValidationDoesNotReadOrRejectTheRequest() throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        IamService iam = mock(IamService.class);
        SigV4HeaderSignatureFilter filter = new SigV4HeaderSignatureFilter(() -> config, iam,
                mock(ResolvedServiceCatalog.class), null);
        filter.resourceInfo = resource(AccountController.class, "enableRegion");
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        filter.filter(request);
        verifyNoInteractions(request, iam);
    }

    private static Response filtered(ResourceInfo resource, String service) throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.auth().validateSignatures()).thenReturn(true);
        ResolvedServiceCatalog catalog = mock(ResolvedServiceCatalog.class);
        when(catalog.byResourceClass(any())).thenReturn(Optional.empty());
        IamService iam = mock(IamService.class);
        SigV4HeaderSignatureFilter filter = new SigV4HeaderSignatureFilter(() -> config, iam, catalog, null);
        filter.resourceInfo = resource;
        ContainerRequestContext request = mock(ContainerRequestContext.class, RETURNS_DEEP_STUBS);
        when(request.getUriInfo().getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(request.getHeaderString("Authorization")).thenReturn("AWS4-HMAC-SHA256 Credential="
                + "audit-only-access-key/20260101/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host;x-amz-date, Signature=" + "0".repeat(64));
        filter.filter(request);
        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(response.capture());
        verifyNoInteractions(iam);
        return response.getValue();
    }

    private static void assertDateValidation(Response response) {
        assertEquals(400, response.getStatus());
        assertEquals("IncompleteSignatureException", response.getHeaderString("X-Amzn-Errortype"));
    }

    private static ResourceInfo resource(Class<?> controller, String method) throws Exception {
        Method selected = method == null ? null : controller.getDeclaredMethod(method, String.class);
        return new ResourceInfo() {
            @Override
            public Method getResourceMethod() {
                return selected;
            }

            @Override
            public Class<?> getResourceClass() {
                return controller;
            }
        };
    }

    private static final class OtherController {
        public void enableRegion(String body) {}
    }
}
