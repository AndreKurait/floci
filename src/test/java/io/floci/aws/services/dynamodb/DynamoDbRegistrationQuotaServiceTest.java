package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.applicationautoscaling.DynamoDbRegistrationTarget;
import io.floci.aws.services.applicationautoscaling.model.ScalableTarget;
import io.floci.aws.services.dynamodb.DynamoDbRegistrationServiceTest.Fixture;
import io.floci.aws.services.dynamodb.DynamoDbRegistrationServiceTest.Target;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DynamoDbRegistrationQuotaServiceTest {
    private static final String REGION = "us-east-1";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    @ParameterizedTest
    @EnumSource(Target.class)
    void perResourceAndAccountQuotaRefusalsPrecedeBothStoreMutations(Target target) {
        for (boolean account : List.of(false, true)) {
            Fixture fixture = new Fixture(new InMemoryStorage<>());
            if (account) {
                create(fixture.service, "anchor-one", 39980);
                create(fixture.service, "anchor-two", 39980);
            }
            InMemoryStorage<String, ScalableTarget> targets = new InMemoryStorage<>();
            JsonNode before = JSON.valueToTree(fixture.tables.scan(key -> true));
            int desired = account ? 31 : 40001;
            assertThrows(AwsException.class, () -> fixture.service.reconcileScalableTarget(
                    target.resource(), target.dimension(), desired, desired, REGION,
                    publication(targets, target.resource(), target.dimension(), desired)));
            assertEquals(before, JSON.valueToTree(fixture.tables.scan(key -> true)));
            assertTrue(targets.isEmpty());
        }
    }

    @Test
    void competingTableRegistrationsCannotBothConsumeTheSameAccountQuota() throws Exception {
        InMemoryStorage<String, TableDefinition> tables = new InMemoryStorage<>();
        DynamoDbService service = new DynamoDbService(tables);
        create(service, "anchor", 39999);
        TableDefinition first = create(service, "first", 1);
        TableDefinition second = create(service, "second", 1);
        InMemoryStorage<String, ScalableTarget> targets = new InMemoryStorage<>();
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> one = workers.submit(() -> register(service, targets, "first", start));
            Future<Boolean> two = workers.submit(() -> register(service, targets, "second", start));
            boolean firstSucceeded = one.get(5, TimeUnit.SECONDS);
            boolean secondSucceeded = two.get(5, TimeUnit.SECONDS);
            assertNotEquals(firstSucceeded, secondSucceeded);
            assertEquals(1, targets.keys().size());
            assertEquals(firstSucceeded ? 40000 : 1, first.getProvisionedThroughput().getReadCapacityUnits());
            assertEquals(secondSucceeded ? 40000 : 1, second.getProvisionedThroughput().getReadCapacityUnits());
            assertEquals(80000, tables.scan(key -> true).stream()
                    .mapToLong(table -> table.getProvisionedThroughput().getReadCapacityUnits()).sum());
        }
    }

    private static boolean register(DynamoDbService service, InMemoryStorage<String, ScalableTarget> targets,
                                    String name, CyclicBarrier start) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            service.reconcileScalableTarget("table/" + name, "dynamodb:table:ReadCapacityUnits",
                    40000, 40000, REGION,
                    publication(targets, "table/" + name, "dynamodb:table:ReadCapacityUnits", 40000));
            return true;
        } catch (AwsException quotaRefusal) {
            assertTrue(quotaRefusal.getMessage().contains("limit to be exceeded"));
            return false;
        }
    }

    private static DynamoDbRegistrationTarget publication(InMemoryStorage<String, ScalableTarget> targets,
                                                          String resource, String dimension, int desired) {
        ScalableTarget value = new ScalableTarget();
        value.setResourceId(resource);
        value.setScalableDimension(dimension);
        value.setServiceNamespace("dynamodb");
        value.setMinCapacity(desired);
        value.setMaxCapacity(desired);
        return new DynamoDbRegistrationTarget(targets, resource, value);
    }

    private static TableDefinition create(DynamoDbService service, String name, long capacity) {
        return service.createTable(name, List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")), capacity, capacity, REGION);
    }
}
