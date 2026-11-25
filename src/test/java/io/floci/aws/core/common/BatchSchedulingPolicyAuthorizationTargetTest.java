package io.floci.aws.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.batch.BatchController;
import io.floci.aws.services.iam.IamActionRegistry;
import io.floci.aws.services.iam.ResourceArnBuilder;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BatchSchedulingPolicyAuthorizationTargetTest {
    @ParameterizedTest
    @ValueSource(strings = {"createSchedulingPolicy", "deleteSchedulingPolicy", "describeSchedulingPolicies",
            "listSchedulingPolicies", "updateSchedulingPolicy"})
    void actualControllerMethodBindsFiveActions(String method) throws Exception {
        ResourceInfo route = mock(ResourceInfo.class);
        doReturn(BatchController.class).when(route).getResourceClass();
        when(route.getResourceMethod()).thenReturn(BatchController.class.getMethod(method, String.class));
        assertEquals("batch:" + Character.toUpperCase(method.charAt(0)) + method.substring(1),
                new IamActionRegistry().resolveMatchedRoute("batch", route, mock(ContainerRequestContext.class)));
    }

    @Test
    void directConditionResolverReadsUncachedBodyAndRestoresExactBytes() throws Exception {
        byte[] bytes = "{\"name\":\"held\",\"tags\":{\"team\":\"blue\"}}".getBytes(StandardCharsets.UTF_8);
        ContainerRequestContext request = body(bytes);
        IamConditionContextResolver resolver = mock(IamConditionContextResolver.class, CALLS_REAL_METHODS);
        assertEquals(Map.of("aws:RequestTag/team", List.of("blue"), "aws:TagKeys", List.of("team")),
                resolver.resolve("batch", "batch:CreateSchedulingPolicy", request));
        assertArrayEquals(bytes, request.getEntityStream().readAllBytes());
        assertNotNull(request.getProperty(IamConditionContextResolver.BUFFERED_JSON_BODY));
    }

    @Test
    void resourceBuilderUsesExplicitPartitionAndExactMintedNameThenRestoresBody() throws Exception {
        byte[] bytes = "{\"name\":\"Mixed_Name\",\"tags\":{\"team\":\"blue\"}}".getBytes(StandardCharsets.UTF_8);
        ContainerRequestContext request = body(bytes);
        ResourceArnBuilder builder = new ResourceArnBuilder(new ObjectMapper(), null, null);
        assertEquals(List.of("arn:aws-us-gov:batch:us-gov-west-1:123456789012:scheduling-policy/Mixed_Name"),
                builder.buildResources("batch", "batch:CreateSchedulingPolicy", request,
                        "us-gov-west-1", "123456789012", "aws-us-gov"));
        assertArrayEquals(bytes, request.getEntityStream().readAllBytes());
        assertEquals(List.of("*"), builder.buildResources("batch", "batch:DescribeSchedulingPolicies",
                request, "us-gov-west-1", "123456789012", "aws-us-gov"));
    }

    @Test
    void matchedTagActionAndResourceUseIdenticalDecodedPathParameter() throws Exception {
        String arn = "arn:aws:batch:us-east-1:123456789012:scheduling-policy/Mixed_Name";
        ContainerRequestContext request = body(new byte[0]);
        UriInfo uri = request.getUriInfo();
        MultivaluedHashMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.putSingle("arn", arn);
        when(uri.getPathParameters()).thenReturn(parameters);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost/v1/tags/unrelated%2Fpath"));
        ResourceInfo route = mock(ResourceInfo.class);
        doReturn(V1TagsController.class).when(route).getResourceClass();
        when(route.getResourceMethod()).thenReturn(AbstractTagsController.class.getMethod(
                "tagResourcePost", HttpHeaders.class, String.class, String.class));
        assertEquals("batch:TagResource", new IamActionRegistry().resolveMatchedRoute("batch", route, request));
        assertEquals(List.of(arn), new ResourceArnBuilder(new ObjectMapper(), null, null)
                .buildResources("batch", "batch:TagResource", request, "us-east-1", "123456789012", "aws"));
    }

    private static ContainerRequestContext body(byte[] bytes) {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPathParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost/v1/createschedulingpolicy"));
        when(request.hasEntity()).thenReturn(bytes.length > 0);
        AtomicReference<InputStream> stream = new AtomicReference<>(new ByteArrayInputStream(bytes));
        when(request.getEntityStream()).thenAnswer(call -> stream.get());
        doAnswer(call -> {
            stream.set(call.getArgument(0));
            return null;
        }).when(request).setEntityStream(any());
        Map<String, Object> properties = new HashMap<>();
        when(request.getProperty(anyString())).thenAnswer(call -> properties.get(call.getArgument(0)));
        doAnswer(call -> {
            properties.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(request).setProperty(anyString(), any());
        return request;
    }
}
