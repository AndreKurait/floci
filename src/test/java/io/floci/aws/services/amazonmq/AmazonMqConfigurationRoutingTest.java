package io.floci.aws.services.amazonmq;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.ResolvedServiceCatalog;
import io.floci.aws.core.common.ServiceDescriptor;
import io.floci.aws.core.common.ServiceProtocol;
import io.floci.aws.services.iam.IamActionRegistry;
import io.floci.aws.services.iam.ResourceArnBuilder;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AmazonMqConfigurationRoutingTest {

    private static final String ID = "c-00000000-0000-0000-0000-000000000001";

    @ParameterizedTest
    @CsvSource({"createConfiguration,CreateConfiguration", "describeConfiguration,DescribeConfiguration",
            "updateConfiguration,UpdateConfiguration", "deleteConfiguration,DeleteConfiguration",
            "describeConfigurationRevision,DescribeConfigurationRevision",
            "listConfigurationRevisions,ListConfigurationRevisions", "listConfigurations,ListConfigurations"})
    void dispatchedControllerMethodDefinesActionAndExactResource(String methodName, String operation) {
        ResourceInfo matched = mock(ResourceInfo.class);
        Method method = Arrays.stream(AmazonMqConfigurationController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName)).findFirst().orElseThrow();
        doReturn(AmazonMqConfigurationController.class).when(matched).getResourceClass();
        when(matched.getResourceMethod()).thenReturn(method);
        IamActionRegistry registry = new IamActionRegistry();
        assertEquals("mq:" + operation, registry.resolveMatchedRoute("mq", matched));
        assertNull(registry.resolveMatchedRoute("kafka", matched));
        ContainerRequestContext context = context("/_amazonmq-config/v1/configurations/" + ID, "mq");
        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.putSingle("configurationId", ID);
        parameters.putSingle("configurationRevision", "999");
        when(context.getUriInfo().getPathParameters()).thenReturn(parameters);
        String expected = operation.equals("CreateConfiguration") || operation.equals("ListConfigurations") ? "*"
                : "arn:aws-cn:mq:cn-north-1:111122223333:configuration:" + ID;
        assertEquals(List.of(expected), new ResourceArnBuilder().buildResources("mq", "mq:" + operation,
                context, "cn-north-1", "111122223333", "aws-cn"));
    }

    @Test
    void malformedDecodedIdCannotFallBackToWildcardAuthorization() {
        ContainerRequestContext context = context("/_amazonmq-config/v1/configurations/x%2Fy", "mq");
        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.putSingle("configurationId", "x/y");
        when(context.getUriInfo().getPathParameters()).thenReturn(parameters);
        assertEquals(400, assertThrows(AwsException.class, () -> new ResourceArnBuilder().buildResources("mq",
                "mq:DescribeConfiguration", context, "us-east-1", "000000000000", "aws")).getHttpStatus());
    }

    @Test
    void publicRoutesSelectMqButLeaveKafkaBrokerAndUnrelatedPathsAlone() {
        ResolvedServiceCatalog catalog = catalog();
        AmazonMqConfigurationRouteFilter filter = new AmazonMqConfigurationRouteFilter(catalog);
        ContainerRequestContext mq = context("/v1/configurations/" + ID + "/revisions", "mq");
        filter.filter(mq);
        verify(mq).setRequestUri(URI.create("http://localhost/_amazonmq-config/v1/configurations/" + ID + "/revisions"));
        ContainerRequestContext kafka = context("/v1/configurations", "kafka");
        filter.filter(kafka);
        verify(kafka, never()).setRequestUri(any());
        ContainerRequestContext broker = context("/v1/brokers", "mq");
        filter.filter(broker);
        verify(broker, never()).setRequestUri(any());
        ContainerRequestContext similar = context("/v1/configurations-other", "mq");
        filter.filter(similar);
        verify(similar, never()).setRequestUri(any());
    }

    @Test
    void blankHeaderUsesMqPresignedScopeAndInternalRouteRejectsOtherScopes() {
        AmazonMqConfigurationRouteFilter filter = new AmazonMqConfigurationRouteFilter(catalog());
        ContainerRequestContext query = context("/v1/configurations", "mq");
        when(query.getHeaderString("Authorization")).thenReturn("");
        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.putSingle("X-Amz-Credential", "test/20261113/us-east-1/mq/aws4_request");
        when(query.getUriInfo().getQueryParameters()).thenReturn(parameters);
        filter.filter(query);
        verify(query).setRequestUri(URI.create("http://localhost/_amazonmq-config/v1/configurations"));
        for (String signingScope : List.of("kafka", "s3", "unknown")) {
            ContainerRequestContext internal = context("/_amazonmq-config/v1/configurations", signingScope);
            filter.filter(internal);
            ArgumentCaptor<Response> refusal = ArgumentCaptor.forClass(Response.class);
            verify(internal).abortWith(refusal.capture());
            assertEquals(400, refusal.getValue().getStatus());
        }
    }

    private static ResolvedServiceCatalog catalog() {
        ResolvedServiceCatalog catalog = mock(ResolvedServiceCatalog.class);
        ServiceDescriptor mq = new ServiceDescriptor("mq", "amazonmq", true, true, "amazonmq", "memory", 0L,
                null, ServiceProtocol.REST_JSON, Set.of(ServiceProtocol.REST_JSON), Set.of(), Set.of("mq"),
                Set.of(), Set.of(AmazonMqConfigurationController.class), Set.of());
        when(catalog.byCredentialScope("mq")).thenReturn(Optional.of(mq));
        return catalog;
    }

    private static ContainerRequestContext context(String path, String scope) {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn(path);
        when(uri.getRequestUri()).thenReturn(URI.create("http://localhost" + path));
        when(uri.getRequestUriBuilder()).thenReturn(UriBuilder.fromUri("http://localhost" + path));
        when(uri.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(context.getHeaderString("Authorization")).thenReturn("AWS4-HMAC-SHA256 Credential=test/20261113/us-east-1/"
                + scope + "/aws4_request, SignedHeaders=host, Signature=placeholder");
        return context;
    }
}
