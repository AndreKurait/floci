package io.floci.aws.services.apigateway;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigateway.model.ApiGatewayResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What a delete or a patch has to take with it, or leave alone, in the stores behind the REST API service. */
class ApiGatewayServiceCascadeTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private ApiGatewayService service;
    private String apiId;
    private String rootId;

    @BeforeEach
    void setUp() {
        Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                stores.computeIfAbsent(invocation.getArgument(1, String.class),
                        ignored -> AccountAwareStorageBackend.inMemory(ACCOUNT)));
        service = new ApiGatewayService(storageFactory, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(TlsCertificateManager.class), new RegionResolver(REGION, ACCOUNT));
        apiId = service.createRestApi(REGION, Map.of("name", "cascade")).getId();
        rootId = service.getResources(REGION, apiId).stream()
                .filter(resource -> "/".equals(resource.getPath()))
                .findFirst().orElseThrow().getId();
    }

    @Test
    void deletingAResourceDeletesTheResourcesBelowIt() {
        ApiGatewayResource users = service.createResource(REGION, apiId, rootId, Map.of("pathPart", "users"));
        ApiGatewayResource user = service.createResource(REGION, apiId, users.getId(), Map.of("pathPart", "{id}"));
        ApiGatewayResource orders = service.createResource(REGION, apiId, user.getId(), Map.of("pathPart", "orders"));
        ApiGatewayResource health = service.createResource(REGION, apiId, rootId, Map.of("pathPart", "health"));

        service.deleteResource(REGION, apiId, users.getId());

        assertThat(service.getResources(REGION, apiId).stream().map(ApiGatewayResource::getPath).sorted().toList(),
                contains("/", "/health"));
        assertNotFound(() -> service.getResource(REGION, apiId, user.getId()));
        assertNotFound(() -> service.getResource(REGION, apiId, orders.getId()));
        assertEquals("/health", service.getResource(REGION, apiId, health.getId()).getPath());
    }

    @Test
    void deletingARestApiDeletesItsAuthorizers() {
        String authorizerId = service.createAuthorizer(REGION, apiId, Map.of(
                "name", "tokens", "type", "TOKEN", "identitySource", "method.request.header.Authorization",
                "authorizerUri", "arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/"
                        + "arn:aws:lambda:us-east-1:000000000000:function:auth/invocations")).getId();

        service.deleteRestApi(REGION, apiId);

        assertNotFound(() -> service.getAuthorizer(REGION, apiId, authorizerId));
    }

    private static void assertNotFound(Runnable call) {
        AwsException thrown = assertThrows(AwsException.class, call::run);
        assertEquals("NotFoundException", thrown.getErrorCode());
        assertEquals(404, thrown.getHttpStatus());
    }
}
