package io.floci.aws.services.iam;

import io.floci.aws.services.cloudfront.CloudFrontController;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudFrontOriginAccessControlAuthorizationTest {

    @Test
    void onlyTheMatchedCloudFrontUpdateMethodGetsTheNewPermission() throws NoSuchMethodException {
        IamActionRegistry registry = new IamActionRegistry();
        ResourceInfo route = mock(ResourceInfo.class);
        doReturn(CloudFrontController.class).when(route).getResourceClass();
        when(route.getResourceMethod()).thenReturn(CloudFrontController.class.getMethod(
                "updateOriginAccessControl", String.class, String.class, String.class));
        assertEquals("cloudfront:UpdateOriginAccessControl", registry.resolveMatchedRoute("cloudfront", route));
        assertNull(registry.resolveMatchedRoute("s3", route));
        assertNull(registry.resolveMatchedRoute("cloudfront", null));
        when(route.getResourceMethod()).thenReturn(
                CloudFrontController.class.getMethod("getOriginAccessControl", String.class));
        assertNull(registry.resolveMatchedRoute("cloudfront", route));
    }

    @ParameterizedTest
    @CsvSource({
            "aws,us-east-1",
            "aws-cn,cn-north-1",
            "aws-us-gov,us-gov-west-1"
    })
    void resourceUsesDecodedMatchedIdAndAccountWithoutARegion(String partition, String region) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uri);
        MultivaluedHashMap<String, String> path = new MultivaluedHashMap<>();
        path.add("Id", "EOWNED");
        when(uri.getPathParameters()).thenReturn(path);
        MultivaluedHashMap<String, String> query = new MultivaluedHashMap<>();
        query.add("Id", "EOTHER");
        when(uri.getQueryParameters()).thenReturn(query);

        ResourceArnBuilder resources = new ResourceArnBuilder();
        assertEquals(List.of("arn:" + partition + ":cloudfront::123456789012:origin-access-control/EOWNED"),
                resources.buildResources("cloudfront", "cloudfront:UpdateOriginAccessControl",
                        request, region, "123456789012", partition));
    }
}
