package io.floci.aws.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises the current metadata through the actual resource resolver, without HTTP or SDK calls. */
class RequestResourcesRoute53CreateCompositionTest {

    @ParameterizedTest
    @CsvSource({
            "CreateCidrCollection,cidrcollection",
            "CreateHealthCheck,healthcheck",
            "CreateHostedZone,hostedzone",
            "CreateReusableDelegationSet,delegationset",
            "CreateTrafficPolicy,trafficpolicy"
    })
    void primaryCreateRemainsUnscopedWhileCreatedResourceKeepsItsGlobalArn(String operation, String type) {
        String action = "route53:" + operation;
        assertTrue(RequestResources.actsOnNoResource(action));
        for (String partition : List.of("aws", "aws-us-gov")) {
            ContainerRequestContext request = request(operation);
            assertEquals(List.of(), RequestResources.of(action, request, partition,
                    "us-gov-west-1", "111122223333"));
            assertEquals(List.of("arn:" + partition + ":route53:::" + type + "/*"),
                    RequestResources.created(action, request, partition, "us-gov-west-1", "111122223333"));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "CreateCidrCollection,cidrcollection,false",
            "CreateHealthCheck,healthcheck,true",
            "CreateHostedZone,hostedzone,true",
            "CreateReusableDelegationSet,delegationset,false",
            "CreateTrafficPolicy,trafficpolicy,false"
    })
    void additionalTagActionUsesOnlyTheResourceTypesThatActionSupports(String operation, String type,
                                                                      boolean taggable) {
        for (String partition : List.of("aws", "aws-us-gov")) {
            List<String> expected = taggable
                    ? List.of("arn:" + partition + ":route53:::" + type + "/*") : List.of();
            assertEquals(expected, RequestResources.created("route53:" + operation, "route53:ChangeTagsForResource",
                    request(operation), partition, "us-gov-west-1", "111122223333", Map.of()));
        }
    }

    private static ContainerRequestContext request(String operation) {
        byte[] body = ("<" + operation + "Request xmlns=\"https://route53.amazonaws.com/doc/2013-04-01/\"/>")
                .getBytes(StandardCharsets.UTF_8);
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getMediaType()).thenReturn(MediaType.APPLICATION_XML_TYPE);
        when(request.getEntityStream()).thenAnswer(invocation -> new ByteArrayInputStream(body));
        return request;
    }
}
