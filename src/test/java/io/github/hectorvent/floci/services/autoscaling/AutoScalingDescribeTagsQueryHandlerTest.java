package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoScalingDescribeTagsQueryHandlerTest {

    private static final String REGION = "us-east-1";

    @Test
    void emptyValuesSentinelDiffersFromMissingValuesAndMissingName() {
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.createAutoScalingGroup(REGION, "query-tags", null, "lt", null, "1", null,
                0, 0, 0, 300, List.of(REGION + "a"), List.of(), List.of(), List.of(),
                "EC2", 0, List.of("Default"), Map.of("key", "<ops&team>"), Map.of(), AsgOptionalFields.none());
        AutoScalingQueryHandler handler = new AutoScalingQueryHandler(service);
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        params.add("Filters.member.1.Name", "key");
        params.add("Filters.member.1.Values", "");

        Response empty = handler.handle("DescribeTags", params, REGION);
        assertEquals(200, empty.getStatus());
        assertTrue(((String) empty.getEntity()).contains("<Value>&lt;ops&amp;team&gt;</Value>"));
        assertTrue(((String) empty.getEntity()).contains("<PropagateAtLaunch>false</PropagateAtLaunch>"));

        params.remove("Filters.member.1.Values");
        assertValidationError(handler.handle("DescribeTags", params, REGION));
        params.remove("Filters.member.1.Name");
        params.add("Filters.member.1.Values", "");
        assertValidationError(handler.handle("DescribeTags", params, REGION));
        params.putSingle("Filters.member.1.Values.member.1", "key");
        assertValidationError(handler.handle("DescribeTags", params, REGION));
    }

    private static void assertValidationError(Response response) {
        assertEquals(400, response.getStatus());
        assertTrue(((String) response.getEntity()).contains("<Code>ValidationError</Code>"));
    }

    @Test
    void malformedRecordCountsReturnAwsQueryErrors() {
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        AutoScalingQueryHandler handler = new AutoScalingQueryHandler(service);
        MultivaluedHashMap<String, String> params = new MultivaluedHashMap<>();
        for (String value : List.of("", "invalid", "1.5")) {
            params.putSingle("MaxRecords", value);
            Response response = handler.handle("DescribeTags", params, REGION);
            assertEquals(400, response.getStatus());
            assertTrue(((String) response.getEntity()).contains("<Code>MalformedInput</Code>"));
        }
    }
}
