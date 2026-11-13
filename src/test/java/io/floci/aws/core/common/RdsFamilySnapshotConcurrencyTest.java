package io.floci.aws.core.common;

import io.floci.aws.services.docdb.DocDbService;
import io.floci.aws.services.neptune.NeptuneQueryHandler;
import io.floci.aws.services.neptune.NeptuneService;
import io.floci.aws.services.neptune.model.NeptuneCluster;
import io.floci.aws.services.rds.RdsQueryHandler;
import io.floci.aws.services.rds.RdsService;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RdsFamilySnapshotConcurrencyTest {
    @Mock
    NeptuneService neptune;
    @Mock
    DocDbService docdb;
    @Mock
    RdsService rds;
    @Mock
    NeptuneQueryHandler neptuneHandler;
    @Mock
    RdsQueryHandler rdsHandler;
    @Mock
    AwsQueryServiceResolver services;
    @Mock
    RegionResolver regions;
    @Mock
    UriInfo uriInfo;
    @InjectMocks
    AwsQueryController controller;

    @Test
    void concurrentCreatesInDifferentFamiliesCannotClaimTheSameSnapshotIdentifier() throws Exception {
        when(services.resolve(anyString(), anyString(), any())).thenReturn("rds");
        when(regions.resolveRegionFromAuth(anyString())).thenReturn("us-east-1");
        when(regions.getAccountId()).thenReturn("111111111111");
        when(neptune.hasCluster("graph")).thenReturn(true);
        when(neptune.listDbClusters("graph")).thenReturn(List.of(new NeptuneCluster()));
        AtomicBoolean exists = new AtomicBoolean();
        when(neptune.hasClusterSnapshot(any())).thenAnswer(invocation ->
                "snapshot".equalsIgnoreCase(invocation.getArgument(0)) && exists.get());
        CountDownLatch capturing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(neptuneHandler.handle(eq("CreateDBClusterSnapshot"), any())).thenAnswer(invocation -> {
            capturing.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            exists.set(true);
            return Response.ok("created").build();
        });
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Response> first = executor.submit(() -> controller.dispatch("signed", uriInfo, request("graph")));
            assertTrue(capturing.await(5, TimeUnit.SECONDS));
            Future<Response> second = executor.submit(() -> controller.dispatch("signed", uriInfo, request("relational")));
            try {
                Response refused = second.get(5, TimeUnit.SECONDS);
                assertEquals(400, refused.getStatus());
                assertTrue(refused.getEntity().toString().contains("DBClusterSnapshotAlreadyExistsFault"));
                assertFalse(first.isDone(), "The duplicate must be refused before the original archive completes");
                assertEquals(400, controller.dispatch("signed", uriInfo, request("relational")).getStatus(),
                        "A refused duplicate must not release the original request's claim");
            } finally {
                release.countDown();
            }
            assertEquals(200, first.get(5, TimeUnit.SECONDS).getStatus());
            verify(rdsHandler, never()).handle(anyString(), any(), anyString());
        }
    }

    @Test
    void failedCaptureReleasesItsClaimSoAnotherFamilyCanRetry() {
        when(services.resolve(anyString(), anyString(), any())).thenReturn("rds");
        when(regions.resolveRegionFromAuth(anyString())).thenReturn("us-east-1");
        when(regions.getAccountId()).thenReturn("111111111111");
        when(neptune.hasCluster("graph")).thenReturn(true);
        when(neptune.listDbClusters("graph")).thenReturn(List.of(new NeptuneCluster()));
        when(neptuneHandler.handle(eq("CreateDBClusterSnapshot"), any()))
                .thenThrow(new AwsException("InvalidDBClusterStateFault", "Capture failed", 400));
        Response failed = controller.dispatch("signed", uriInfo, request("graph"));
        assertEquals(400, failed.getStatus());
        assertTrue(failed.getEntity().toString().contains("InvalidDBClusterStateFault"));
        when(rdsHandler.handle(eq("CreateDBClusterSnapshot"), any(), eq("us-east-1")))
                .thenReturn(Response.ok("created").build());
        assertEquals(200, controller.dispatch("signed", uriInfo, request("relational")).getStatus());
    }

    private static MultivaluedMap<String, String> request(String source) {
        MultivaluedMap<String, String> parameters = new MultivaluedHashMap<>();
        parameters.putSingle("Action", "CreateDBClusterSnapshot");
        parameters.putSingle("DBClusterIdentifier", source);
        parameters.putSingle("DBClusterSnapshotIdentifier", "snapshot");
        return parameters;
    }
}
