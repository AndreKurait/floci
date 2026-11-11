package io.floci.aws.services.ecs;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.Base64Url;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A list token is an offset into the listing: one that decodes to no position in it is the caller's error. */
class EcsServiceListTokenTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private EcsService service;

    @BeforeEach
    void setUp() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");
        service = new EcsService(new RegionResolver(REGION, ACCOUNT), mock(EcsContainerManager.class), config,
                mock(EcsLoadBalancerRegistrar.class), new InMemoryStorageFactory(ACCOUNT), null);
        service.initializeStorage();
        service.createCluster("token-a", REGION);
        service.createCluster("token-b", REGION);
    }

    @AfterEach
    void stopReconciler() {
        service.stopManagedContainers();
    }

    @Test
    void listClusters_tokenForANegativeOffset_isRejectedAsAnInvalidParameter() {
        AwsException error = assertThrows(AwsException.class,
                () -> service.listClusters(1, Base64Url.encode("-1"), REGION));

        assertEquals("InvalidParameterException", error.getErrorCode());
        assertEquals("The nextToken is not valid.", error.getMessage());
    }

    @Test
    void listClusters_tokenForOffsetZero_isTheFirstPage() {
        EcsService.ListPage first = service.listClusters(1, null, REGION);

        assertEquals(first.arns(), service.listClusters(1, Base64Url.encode("0"), REGION).arns());
    }

    @Test
    void listClusters_tokenAtTheEndOfTheListing_isAnEmptyLastPage() {
        EcsService.ListPage past = service.listClusters(1, Base64Url.encode("2"), REGION);

        assertEquals(List.of(), past.arns());
        assertNull(past.nextToken());
    }
}
