package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignedServiceRouteTest {

    private final ResolvedServiceCatalog catalog = mock(ResolvedServiceCatalog.class);

    @Test
    void rewritingAndDirectServicePathsRecordTheirPrefix() {
        SignedServiceRoute route = new SignedServiceRoute(catalog, "dlm", "/_dlm", SignedServiceRoute.under("/policies"));
        for (String path : new String[]{"/policies/example", "/_dlm/policies/example"}) {
            ContainerRequestContext ctx = request(path, true);
            route.apply(ctx);
            verify(ctx).setProperty(SignedServiceRoute.INTERNAL_PREFIX_PROPERTY, "/_dlm");
        }
    }

    @Test
    void unsignedInternalPathsAreRefusedWithoutRecordingAPrefix() {
        SignedServiceRoute route = new SignedServiceRoute(catalog, "dlm", "/_dlm", SignedServiceRoute.under("/policies"));
        ContainerRequestContext ctx = request("/_dlm/policies/example", false);
        route.apply(ctx);
        verify(ctx).abortWith(any());
        verify(ctx, never()).setProperty(any(), any());
    }

    private ContainerRequestContext request(String path, boolean signed) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(ctx.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn(path);
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uri.getRequestUriBuilder()).thenReturn(UriBuilder.fromUri("http://localhost" + path));
        if (signed) {
            ServiceDescriptor service = new ServiceDescriptor("dlm", "dlm", true, true, null, null, 0L,
                    null, ServiceProtocol.REST_JSON, Set.of(ServiceProtocol.REST_JSON), Set.of(), Set.of("dlm"),
                    Set.of(), Set.of(), Set.of());
            when(catalog.byCredentialScope("dlm")).thenReturn(Optional.of(service));
            when(ctx.getHeaderString("Authorization"))
                    .thenReturn("AWS4-HMAC-SHA256 Credential=test/20261113/us-east-1/dlm/aws4_request");
        }
        return ctx;
    }

    @Test
    void underMatchesTheRootAndPathsBeneathItOnly() {
        Predicate<String> plans = SignedServiceRoute.under("/malware-protection-plan");

        assertTrue(plans.test("/malware-protection-plan"));
        assertTrue(plans.test("/malware-protection-plan/abc"));
        assertFalse(plans.test("/malware-protection-planner"));
        assertFalse(plans.test("/other/malware-protection-plan"));
    }

    @Test
    void theInternalPrefixMustBeOneSegmentNoS3BucketCanBeNamed() {
        assertDoesNotThrow(() -> new SignedServiceRoute(catalog, "dlm", "/_dlm", path -> false));
        // A bucket may be named re2, so a prefix without the underscore could shadow one.
        assertThrows(IllegalArgumentException.class,
                () -> new SignedServiceRoute(catalog, "resource-explorer-2", "/re2", path -> false));
        assertThrows(IllegalArgumentException.class,
                () -> new SignedServiceRoute(catalog, "dlm", "/_dlm/policies", path -> false));
    }
}
