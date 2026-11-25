package io.floci.aws.services.apigateway;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigateway.model.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGatewayStageVariableRemovalTest {
    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private ApiGatewayService service;
    private String apiId;

    @BeforeEach
    void setUp() {
        Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                stores.computeIfAbsent(invocation.getArgument(1, String.class),
                        ignored -> AccountAwareStorageBackend.inMemory(ACCOUNT)));
        service = new ApiGatewayService(storageFactory, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(TlsCertificateManager.class), new RegionResolver(REGION, ACCOUNT));
        apiId = service.createRestApi(REGION, Map.of("name", "stage-variable-remove")).getId();
    }

    @Test
    void removePatchDeletesAStageVariable() {
        String deploymentId = service.createDeployment(REGION, apiId, new HashMap<>()).id();
        Map<String, Object> request = new HashMap<>();
        request.put("stageName", "dev");
        request.put("deploymentId", deploymentId);
        request.put("variables", new HashMap<>(Map.of("keep", "1", "drop", "2")));
        service.createStage(REGION, apiId, request);

        Stage patched = service.updateStage(REGION, apiId, "dev",
                List.of(Map.of("op", "remove", "path", "/variables/drop")));

        assertEquals(Map.of("keep", "1"), patched.getVariables());
        assertFalse(service.getStage(REGION, apiId, "dev").getVariables().containsKey("drop"));
    }

}
