package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamoDbTtlAgeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void zeroAndMoreThanFiveYearsOldAreNotEligibleForExpiry() {
        Instant now = Instant.parse("2026-11-25T12:00:00Z");
        long oldest = now.atOffset(ZoneOffset.UTC).minusYears(5).toEpochSecond();
        assertFalse(expired(0, now));
        assertFalse(expired(oldest - 1, now));
        assertTrue(expired(oldest, now));
        assertTrue(expired(now.getEpochSecond() - 1, now));
        assertFalse(expired(now.getEpochSecond(), now));
        assertFalse(expired(now.getEpochSecond() + 1, now));
    }

    @Test
    void theFiveYearBoundaryUsesUtcCalendarYearsAcrossLeapDays() {
        Instant now = Instant.parse("2024-02-29T23:59:59Z");
        long oldest = Instant.parse("2019-02-28T23:59:59Z").getEpochSecond();
        assertFalse(expired(oldest - 1, now));
        assertTrue(expired(oldest, now));
    }

    private boolean expired(long ttl, Instant now) {
        TableDefinition table = new TableDefinition();
        table.setTtlEnabled(true);
        table.setTtlAttributeName("expires");
        ObjectNode item = mapper.createObjectNode();
        item.set("expires", mapper.createObjectNode().put("N", String.valueOf(ttl)));
        return DynamoDbService.isExpired(item, table, now);
    }
}
