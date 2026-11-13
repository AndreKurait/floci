package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.applicationautoscaling.DynamoDbRegistrationTarget;
import io.github.hectorvent.floci.services.applicationautoscaling.model.ScalableTarget;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbRegistrationServiceTest.Fixture;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbRegistrationServiceTest.TableStore;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbRegistrationServiceTest.Target;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbRegistrationServiceTest.TargetStore;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService.ScalableCapacityDecision;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbBackendSelector;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbLocalBackend;
import io.github.hectorvent.floci.services.dynamodb.backend.NativeDynamoDbBackend;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual configured constructor and native metadata; no external DynamoDB Local process or calls. */
@Timeout(30)
class DynamoDbPolicyBackendBoundaryTest {
    private static final String REGION = "us-east-1";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    @ParameterizedTest
    @EnumSource(Target.class)
    void localBackendRefusesRegistrationBeforeNativeClampOrTargetPublication(Target target) {
        Boundary fixture = new Boundary(" LoCaL ");
        JsonNode before = fixture.snapshot();
        AwsException failure = capture(() -> fixture.service.reconcileScalableTarget(
                target.resource(), target.dimension(), 7, 12, REGION, fixture.publication(target)));
        fixture.assertLocalRefusal("registration", failure, before);
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void localBackendRefusesDecisionBeforeCallbackOrNativeWrite(Target target) {
        Boundary fixture = new Boundary(" LoCaL ");
        JsonNode before = fixture.snapshot();
        AwsException failure = capture(() -> fixture.service.decideScalableCapacity(
                target.resource(), target.dimension(), REGION, current -> {
                    fixture.decisions.incrementAndGet();
                    return 17;
                }));
        fixture.assertLocalRefusal("decision", failure, before);
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void capturedNativeSelectionAllowsBothEntrypointsAndRetainsCompleteSiblingMetadata(Target target) {
        Boundary fixture = new Boundary(" NaTiVe ");
        TableDefinition expected = JSON.convertValue(fixture.nativeFixture.table, TableDefinition.class);
        fixture.service.reconcileScalableTarget(
                target.resource(), target.dimension(), 7, 12, REGION, fixture.publication(target));
        assertEquals(7, target.capacity(fixture.nativeFixture.table));
        assertEquals(1, fixture.publications.get());
        assertEquals(7, fixture.targets.get("owned").orElseThrow().getMinCapacity());
        ScalableCapacityDecision result = fixture.service.decideScalableCapacity(
                target.resource(), target.dimension(), REGION, current -> {
                    fixture.decisions.incrementAndGet();
                    assertEquals(7, current);
                    return 17;
                });
        assertEquals(new ScalableCapacityDecision(true, 7, 17), result);
        if (target.read) {
            target.throughput(expected).setReadCapacityUnits(17);
        } else {
            target.throughput(expected).setWriteCapacityUnits(17);
        }
        target.rawWarm(expected, 17);
        assertEquals(JSON.valueToTree(expected), JSON.valueToTree(fixture.nativeFixture.table));
        assertEquals(2, fixture.puts.get());
        assertEquals(1, fixture.decisions.get());
        assertEquals(1, fixture.publications.get());
        verifyNoInteractions(fixture.external);
    }

    private static AwsException capture(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (AwsException failure) {
            return failure;
        }
    }

    private static final class Boundary {
        private final TableStore tables = new TableStore();
        private final TargetStore targets = new TargetStore();
        private final AccountAwareStorageBackend<TableDefinition> accountTables =
                new AccountAwareStorageBackend<>(tables, null, "000000000000");
        private final Fixture nativeFixture = new Fixture(accountTables);
        private final AtomicInteger puts = new AtomicInteger();
        private final AtomicInteger publications = new AtomicInteger();
        private final AtomicInteger decisions = new AtomicInteger();
        private final DynamoDbLocalBackend external = mock(DynamoDbLocalBackend.class);
        private final DynamoDbService service;

        Boundary(String configured) {
            nativeFixture.table.setTags(Map.of("owned", "unchanged"));
            EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
            when(config.services().dynamodb().backend()).thenReturn(configured);
            when(config.storage().services().dynamodb().itemLayout()).thenReturn("single-file");
            DynamoDbBackendSelector selector = new DynamoDbBackendSelector(
                    config, mock(NativeDynamoDbBackend.class), external);
            boolean local = configured.contains("LoCaL");
            assertEquals(local ? "local" : "native", selector.selected(),
                    "The actual public backend selector must observe the intended setup");
            StorageFactory factory = mock(StorageFactory.class);
            doAnswer(ignored -> AccountAwareStorageBackend.inMemory("000000000000"))
                    .when(factory).create(anyString(), anyString(), any());
            doReturn(accountTables).when(factory).create(eq("dynamodb"), eq("dynamodb-tables.json"), any());
            service = new DynamoDbService(factory, new RegionResolver(REGION, "000000000000"),
                    mock(DynamoDbStreamService.class), mock(KinesisStreamingForwarder.class),
                    mock(S3Service.class), JSON, config);
            assertEquals("ACTIVE", nativeFixture.table.getTableStatus());
            assertEquals("PROVISIONED", nativeFixture.table.getBillingMode());
            assertEquals(1, tables.keys().size());
            // Both the selector and service must retain their construction-time choice.
            when(config.services().dynamodb().backend()).thenReturn(local ? "native" : "local");
            assertEquals(local ? "local" : "native", selector.selected());
            tables.beforePut = ignored -> puts.incrementAndGet();
            targets.beforeCommit = publications::incrementAndGet;
        }

        DynamoDbRegistrationTarget publication(Target target) {
            ScalableTarget candidate = new ScalableTarget();
            candidate.setServiceNamespace("dynamodb");
            candidate.setResourceId(target.resource());
            candidate.setScalableDimension(target.dimension());
            candidate.setMinCapacity(7);
            candidate.setMaxCapacity(12);
            return new DynamoDbRegistrationTarget(targets, "owned", candidate);
        }

        JsonNode snapshot() {
            return JSON.valueToTree(Map.of(
                    "tables", tables.scan(key -> true),
                    "tableKeys", tables.keys(),
                    "targets", targets.scan(key -> true)));
        }

        void assertLocalRefusal(String operation, AwsException failure, JsonNode before) {
            JsonNode after = snapshot();
            System.out.println("DDB_POLICY_BACKEND_BOUNDARY operation=" + operation + " code="
                    + (failure == null ? "ACCEPTED" : failure.getErrorCode())
                    + " before=" + before + " after=" + after);
            assertAll("External selection refuses the native auto-scaling entrypoint",
                    () -> {
                        assertNotNull(failure, "The external-backend native entrypoint must be refused");
                        assertEquals("ValidationException", failure.getErrorCode());
                        assertEquals(400, failure.getHttpStatus());
                        assertEquals("DynamoDB auto scaling requires the native backend.", failure.getMessage());
                    },
                    () -> assertEquals(before, after),
                    () -> assertEquals(0, puts.get()),
                    () -> assertEquals(0, decisions.get()),
                    () -> assertEquals(0, publications.get()),
                    () -> verifyNoInteractions(external));
        }
    }
}
