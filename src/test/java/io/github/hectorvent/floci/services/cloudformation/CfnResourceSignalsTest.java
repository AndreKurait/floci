package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CfnResourceSignalsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CfnResourceSignals signals = new CfnResourceSignals();
    private final List<String> events = new ArrayList<>();

    @Test
    void signalsReceivedDuringProvisioningSurviveUntilTheWaitStarts() throws Exception {
        CfnResourceSignals.Pending pending = begin("""
                {"ResourceSignal":{"Count":2,"Timeout":"PT1S"}}
                """);
        signals.signal("stack", "Compute", "first", "SUCCESS", events::add);
        signals.signal("stack", "Compute", "first", "SUCCESS", events::add);
        signals.signal("stack", "Compute", "second", "SUCCESS", events::add);
        assertEquals(2, events.size());
        assertTrue(events.getLast().contains("2 of 2"));
        assertNull(pending.await());
        signals.finish("stack", "Compute", pending);
        assertThrows(AwsException.class, () ->
                signals.signal("stack", "Compute", "late", "SUCCESS", events::add));
    }

    @Test
    void timeoutStartsAfterProvisioningAndReportsTheObservedCount() throws Exception {
        CfnResourceSignals.Pending pending = begin("""
                {"ResourceSignal":{"Count":2,"Timeout":"PT1S"}}
                """);
        Thread.sleep(Duration.ofMillis(1050));
        signals.signal("stack", "Compute", "first", "SUCCESS", events::add);
        long started = System.nanoTime();
        assertTrue(pending.await().contains("received 1 of 2"));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) >= 0);
    }

    @Test
    void failureIsPublishedBeforeTheWaitIsReleased() throws Exception {
        CfnResourceSignals.Pending pending = begin("{\"ResourceSignal\":{}}");
        signals.signal("stack", "Compute", "failed", "FAILURE", events::add);
        assertEquals(1, events.size());
        assertEquals(events.getFirst(), pending.await());
    }

    @Test
    void interruptionRetiresTheWaitBeforeALateSignalCanPublishAnEvent() throws Exception {
        CfnResourceSignals.Pending pending = begin("{\"ResourceSignal\":{}}");
        Thread.currentThread().interrupt();
        try {
            assertTrue(pending.await().startsWith("Interrupted"));
            assertTrue(Thread.currentThread().isInterrupted());
            signals.signal("stack", "Compute", "late", "SUCCESS", events::add);
            assertTrue(events.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void percentageRoundsUpAndCountsDistinctSignals() throws Exception {
        CfnResourceSignals.Pending pending = signals.begin("stack", "Compute", "AWS::AutoScaling::AutoScalingGroup",
                JSON.readTree("""
                        {"AutoScalingCreationPolicy":{"MinSuccessfulInstancesPercent":50},
                         "ResourceSignal":{"Timeout":"PT1S"}}
                        """), JSON.readTree("{\"DesiredCapacity\":5}"));
        signals.signal("stack", "Compute", "first", "SUCCESS", events::add);
        signals.signal("stack", "Compute", "first", "SUCCESS", events::add);
        signals.signal("stack", "Compute", "second", "SUCCESS", events::add);
        assertTrue(events.getLast().contains("2 of 3"));
        signals.signal("stack", "Compute", "third", "SUCCESS", events::add);
        assertNull(pending.await());
    }

    @Test
    void explicitCountAndPercentageBothApplyToTheInitialCapacity() throws Exception {
        CfnResourceSignals.Pending pending = signals.begin("stack", "Compute", "AWS::AutoScaling::AutoScalingGroup",
                JSON.readTree("""
                        {"AutoScalingCreationPolicy":{},"ResourceSignal":{"Count":3,"Timeout":"PT1S"}}
                        """), JSON.readTree("{\"MinSize\":2}"));
        for (int index = 0; index < 3; index++) {
            signals.signal("stack", "Compute", "instance-" + index, "SUCCESS", events::add);
        }
        assertTrue(events.getLast().contains("3 of 3"));
        assertNull(pending.await());
    }

    @Test
    void stackCompletionRetiresOnlyItsOwnCollectors() throws Exception {
        begin("{\"ResourceSignal\":{}}");
        CfnResourceSignals.Pending other = signals.begin("other", "Compute", "AWS::EC2::Instance",
                JSON.readTree("{\"ResourceSignal\":{}}"), JSON.createObjectNode());
        signals.finishStack("stack");
        assertThrows(AwsException.class, () ->
                signals.signal("stack", "Compute", "first", "SUCCESS", events::add));
        signals.signal("other", "Compute", "second", "SUCCESS", events::add);
        assertNull(other.await());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]", "{\"Other\":{}}", "{\"ResourceSignal\":null}",
            "{\"ResourceSignal\":{\"Count\":-1}}", "{\"ResourceSignal\":{\"Count\":1.5}}",
            "{\"ResourceSignal\":{\"Count\":true}}", "{\"ResourceSignal\":{\"Count\":\"2147483648\"}}",
            "{\"ResourceSignal\":{\"Timeout\":\"PT0S\"}}", "{\"ResourceSignal\":{\"Timeout\":\"P1D\"}}",
            "{\"ResourceSignal\":{\"Timeout\":\"PT12H1S\"}}", "{\"ResourceSignal\":{\"Timeout\":\"PT-1S\"}}",
            "{\"ResourceSignal\":{\"Extra\":true}}", "{\"AutoScalingCreationPolicy\":{}}"
    })
    void rejectsInvalidPoliciesBeforeRegisteringASignalCollector(String policy) throws Exception {
        assertThrows(AwsException.class, () -> begin(policy));
        assertThrows(AwsException.class, () ->
                signals.signal("stack", "Compute", "first", "SUCCESS", events::add));
    }

    @Test
    void unsupportedResourceTypesCannotSilentlyIgnoreTheirCreationPolicy() throws Exception {
        assertThrows(AwsException.class, () -> signals.begin("stack", "Bucket", "AWS::S3::Bucket",
                JSON.readTree("{\"ResourceSignal\":{}}"), JSON.createObjectNode()));
    }

    @Test
    void invalidSignalsCannotAffectTheCollector() throws Exception {
        CfnResourceSignals.Pending pending = begin("{\"ResourceSignal\":{}}");
        for (String id : List.of("", "x".repeat(65))) {
            assertThrows(AwsException.class, () ->
                    signals.signal("stack", "Compute", id, "SUCCESS", events::add));
        }
        assertThrows(AwsException.class, () ->
                signals.signal("stack", "Compute", "first", "UNKNOWN", events::add));
        assertThrows(AwsException.class, () ->
                signals.signal("stack", "Other", "first", "SUCCESS", events::add));
        signals.signal("stack", "Compute", "valid", "SUCCESS", events::add);
        assertNull(pending.await());
        assertEquals(1, events.size());
    }

    private CfnResourceSignals.Pending begin(String policy) throws Exception {
        return signals.begin("stack", "Compute", "AWS::EC2::Instance", JSON.readTree(policy), JSON.createObjectNode());
    }
}
