package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.dynamodb.DynamoDbRegistrationServiceTest.Fixture;
import io.floci.aws.services.dynamodb.DynamoDbRegistrationServiceTest.Target;
import io.floci.aws.services.dynamodb.model.AttributeDefinition;
import io.floci.aws.services.dynamodb.model.KeySchemaElement;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Main's existing provisioned quota owner also covers the new locked decision entrypoint. */
@Timeout(30)
class DynamoDbPolicyCapacityQuotaTest {
    private static final String REGION = "us-east-1";
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    @ParameterizedTest
    @EnumSource(Target.class)
    void perResourceAndAccountQuotaRefusalsPrecedeSelectedPersistence(Target target) {
        for (boolean account : List.of(false, true)) {
            Fixture fixture = new Fixture(new InMemoryStorage<>());
            if (account) {
                create(fixture.service, "anchor-one", 39980);
                create(fixture.service, "anchor-two", 39980);
            }
            JsonNode before = JSON.valueToTree(fixture.tables.scan(key -> true));
            long desired = account ? 31 : 40001;
            AwsException failure = assertThrows(AwsException.class, () -> fixture.service.decideScalableCapacity(
                    target.resource(), target.dimension(), REGION, current -> desired));
            assertEquals("LimitExceededException", failure.getErrorCode());
            assertEquals(before, JSON.valueToTree(fixture.tables.scan(key -> true)));
        }
    }

    @Test
    void competingDecisionsCannotBothConsumeTheSameRemainingAccountQuota() throws Exception {
        InMemoryStorage<String, TableDefinition> tables = new InMemoryStorage<>();
        DynamoDbService service = new DynamoDbService(tables);
        create(service, "anchor", 39999);
        TableDefinition first = create(service, "first", 1);
        TableDefinition second = create(service, "second", 1);
        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> one = executor.submit(() -> decide(service, "first", start));
            Future<Boolean> two = executor.submit(() -> decide(service, "second", start));
            boolean firstSucceeded = one.get(5, TimeUnit.SECONDS);
            boolean secondSucceeded = two.get(5, TimeUnit.SECONDS);
            assertNotEquals(firstSucceeded, secondSucceeded);
            assertEquals(firstSucceeded ? 40000 : 1, first.getProvisionedThroughput().getReadCapacityUnits());
            assertEquals(secondSucceeded ? 40000 : 1, second.getProvisionedThroughput().getReadCapacityUnits());
            assertEquals(80000, tables.scan(key -> true).stream()
                    .mapToLong(table -> table.getProvisionedThroughput().getReadCapacityUnits()).sum());
        }
    }

    private static boolean decide(DynamoDbService service, String name, CyclicBarrier start) throws Exception {
        start.await(3, TimeUnit.SECONDS);
        try {
            service.decideScalableCapacity("table/" + name, "dynamodb:table:ReadCapacityUnits",
                    REGION, current -> 40000);
            return true;
        } catch (AwsException refused) {
            assertEquals("LimitExceededException", refused.getErrorCode());
            return false;
        }
    }

    private static TableDefinition create(DynamoDbService service, String name, long capacity) {
        TableDefinition table = service.createTable(name, List.of(new KeySchemaElement("pk", "HASH")),
                List.of(new AttributeDefinition("pk", "S")), capacity, capacity, List.of(), REGION);
        table.setBillingMode("PROVISIONED");
        return table;
    }
}
