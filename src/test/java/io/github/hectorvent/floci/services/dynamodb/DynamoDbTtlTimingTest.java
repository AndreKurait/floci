package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.testing.TestTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * When a TTL attribute makes an item expire and when the sweeper removes it, on a controlled
 * timeline: an item is expired once the clock's epoch second is past its TTL value, and the
 * sweep runs every sixty seconds starting sixty seconds after startup.
 */
class DynamoDbTtlTimingTest {

    private static final String REGION = "us-east-1";
    private static final Duration ONE_NANO = Duration.ofNanos(1);
    private static final Duration SWEEP_INTERVAL = Duration.ofSeconds(60);

    private final TestTime time = TestTime.create();
    private final ObjectMapper mapper = new ObjectMapper();
    private DynamoDbService service;

    @BeforeEach
    void setUp() {
        service = new DynamoDbService(new InMemoryStorage<>(), new InMemoryStorage<>(), null, null,
                new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"),
                null, null, null, mapper, 0, 0, 0, time.clock());
        service.createTable("Sessions", List.of(new KeySchemaElement("id", "HASH")),
                List.of(new AttributeDefinition("id", "S")), 5L, 5L, REGION);
        service.updateTimeToLive("Sessions", "expiresAt", true, REGION);
    }

    @Test
    void anItemIsNotExpiredDuringTheSecondItsTtlNames() {
        putExpiringIn("s1", Duration.ofHours(1));

        time.advance(Duration.ofHours(1));
        service.deleteExpiredItems();
        assertNotNull(stored("s1"), "the clock reads exactly the TTL value");

        time.advance(Duration.ofSeconds(1).minus(ONE_NANO));
        service.deleteExpiredItems();
        assertNotNull(stored("s1"), "the clock is still within the second the TTL value names");
    }

    @Test
    void anItemIsExpiredOnceTheSecondItsTtlNamesHasPassed() {
        putExpiringIn("s1", Duration.ofHours(1));

        time.advance(Duration.ofHours(1).plusSeconds(1));
        service.deleteExpiredItems();

        assertNull(stored("s1"));
    }

    @Test
    void aSweepRemovesOnlyTheItemsThatHaveExpired() {
        putExpiringIn("soon", Duration.ofMinutes(5));
        putExpiringIn("later", Duration.ofDays(5 * 365));

        time.advance(Duration.ofMinutes(5).plusSeconds(1));
        service.deleteExpiredItems();

        assertNull(stored("soon"));
        assertNotNull(stored("later"));
    }

    @Test
    void anItemWhoseTtlIsZeroIsNeverExpired() {
        putWithTtl("unset", 0L);

        time.advance(Duration.ofDays(1));
        service.deleteExpiredItems();

        assertNotNull(stored("unset"), "a TTL of 0 is more than five years in the past, which TTL ignores");
    }

    @Test
    void anItemWhoseTtlIsMoreThanFiveYearsInThePastIsNotExpired() {
        long fiveYearsAgo = time.now().atOffset(ZoneOffset.UTC).minusYears(5).toEpochSecond();
        putWithTtl("ancient", fiveYearsAgo - 1);
        putWithTtl("fiveYears", fiveYearsAgo);
        putWithTtl("recent", time.now().minus(Duration.ofDays(1)).getEpochSecond());

        service.deleteExpiredItems();

        assertNotNull(stored("ancient"), "more than five years in the past: not considered for expiry");
        assertNull(stored("fiveYears"), "exactly five years in the past is still considered");
        assertNull(stored("recent"));
    }

    @Test
    void theSweeperFirstRunsSixtySecondsAfterStartup() {
        DynamoDbTtlService sweeper = new DynamoDbTtlService(service, time.newScheduler());
        putExpiringIn("s1", Duration.ofSeconds(10));
        sweeper.start();

        time.advance(SWEEP_INTERVAL.minus(ONE_NANO));
        assertNotNull(stored("s1"), "expired fifty seconds ago, and no sweep has run yet");

        time.advance(ONE_NANO);
        assertNull(stored("s1"));
        sweeper.stop();
    }

    @Test
    void theSweeperRunsAgainEverySixtySeconds() {
        DynamoDbTtlService sweeper = new DynamoDbTtlService(service, time.newScheduler());
        sweeper.start();
        time.advance(SWEEP_INTERVAL);
        putExpiringIn("s1", Duration.ofSeconds(10));

        time.advance(SWEEP_INTERVAL.minus(ONE_NANO));
        assertNotNull(stored("s1"), "expired, and the second sweep is one nanosecond away");

        time.advance(ONE_NANO);
        assertNull(stored("s1"));
        sweeper.stop();
    }

    @Test
    void aPausedSweeperSkipsItsTickAndSweepsOnTheNextOneAfterResuming() {
        DynamoDbTtlService sweeper = new DynamoDbTtlService(service, time.newScheduler());
        putExpiringIn("s1", Duration.ofSeconds(10));
        sweeper.start();
        sweeper.pause();

        time.advance(SWEEP_INTERVAL);
        assertNotNull(stored("s1"));

        sweeper.resume();
        time.advance(SWEEP_INTERVAL.minus(ONE_NANO));
        assertNotNull(stored("s1"), "resuming does not sweep by itself");
        time.advance(ONE_NANO);
        assertNull(stored("s1"));
        sweeper.stop();
    }

    @Test
    void aStoppedSweeperLeavesNothingScheduled() {
        TestTime own = TestTime.create();
        DynamoDbTtlService sweeper = new DynamoDbTtlService(service, own.scheduler());
        sweeper.start();
        assertEquals(1, own.pendingTasks());

        sweeper.stop();

        assertEquals(0, own.pendingTasks());
    }

    private void putExpiringIn(String id, Duration ttl) {
        putWithTtl(id, time.now().plus(ttl).getEpochSecond());
    }

    private void putWithTtl(String id, long epochSecond) {
        ObjectNode item = key(id);
        item.set("expiresAt", mapper.createObjectNode().put("N", String.valueOf(epochSecond)));
        service.putItem("Sessions", item, null, null, null, REGION, null);
    }

    private Object stored(String id) {
        return service.getItem("Sessions", key(id), REGION);
    }

    private ObjectNode key(String id) {
        ObjectNode key = mapper.createObjectNode();
        key.set("id", mapper.createObjectNode().put("S", id));
        return key;
    }
}
