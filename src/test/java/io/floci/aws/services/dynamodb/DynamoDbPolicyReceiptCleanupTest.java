package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalableTargetsRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalableTargetsResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingActivitiesRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingActivitiesResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingPoliciesRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingPoliciesResponse;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsRequest;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DynamoDbPolicyReceiptCleanupTest {
    @ParameterizedTest
    @ValueSource(strings = {"Workload", "ReadAdmission", "ConditionalAdmission", "AdmissionContract", "Clock"})
    void incompleteReceiptPreservesCleanupFailureAndClosesClients(String suite) throws Exception {
        exercise(suite, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Workload", "ReadAdmission", "ConditionalAdmission", "AdmissionContract", "Clock"})
    void completeReceiptAndSuccessfulCleanupCloseClients(String suite) throws Exception {
        exercise(suite, false);
    }

    private void exercise(String suite, boolean incomplete) throws Exception {
        Object fixture = switch (suite) {
            case "Workload" -> new DynamoDbPolicyWorkloadIntegrationTest();
            case "ReadAdmission" -> new DynamoDbPolicyReadAdmissionIntegrationTest();
            case "ConditionalAdmission" -> new DynamoDbPolicyConditionalAdmissionIntegrationTest();
            case "AdmissionContract" -> new DynamoDbPolicyAdmissionContractIntegrationTest();
            case "Clock" -> new DynamoDbPolicyClockIntegrationTest();
            default -> throw new IllegalArgumentException(suite);
        };
        DynamoDbClient dynamo = mock(DynamoDbClient.class);
        CloudWatchClient metrics = mock(CloudWatchClient.class);
        ApplicationAutoScalingClient scaling = mock(ApplicationAutoScalingClient.class);
        when(metrics.describeAlarms(ArgumentMatchers.<Consumer<DescribeAlarmsRequest.Builder>>any()))
                .thenReturn(DescribeAlarmsResponse.builder().build());
        when(scaling.describeScalingPolicies(ArgumentMatchers.<Consumer<DescribeScalingPoliciesRequest.Builder>>any()))
                .thenReturn(DescribeScalingPoliciesResponse.builder().build());
        when(scaling.describeScalableTargets(ArgumentMatchers.<Consumer<DescribeScalableTargetsRequest.Builder>>any()))
                .thenReturn(DescribeScalableTargetsResponse.builder().build());
        when(scaling.describeScalingActivities(ArgumentMatchers.<Consumer<DescribeScalingActivitiesRequest.Builder>>any()))
                .thenReturn(DescribeScalingActivitiesResponse.builder().build());
        RuntimeException original = new RuntimeException("Cleanup request received no response");
        if (incomplete) {
            when(dynamo.listTables()).thenThrow(original);
        } else {
            when(dynamo.listTables()).thenReturn(ListTablesResponse.builder().build());
        }
        set(fixture, "dynamo", dynamo);
        set(fixture, "metrics", metrics);
        if (!suite.equals("Clock")) {
            set(fixture, "scaling", scaling);
        }
        String table = "receipt-control-" + UUID.randomUUID();
        set(fixture, "table", table);
        Object wire = get(fixture, "wire");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) get(wire, "calls");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("operation", "ListTables");
        if (!incomplete) {
            row.put("responseBase64", "e30=");
        }
        calls.add(row);
        String directory = switch (suite) {
            case "Workload" -> "dynamodb-policy-workload-wire";
            case "ReadAdmission" -> "dynamodb-policy-read-admission-wire";
            case "ConditionalAdmission" -> "dynamodb-policy-conditional-admission-wire";
            case "AdmissionContract" -> "dynamodb-policy-admission-contract-wire";
            case "Clock" -> "dynamodb-policy-clock-wire";
            default -> throw new IllegalArgumentException(suite);
        };
        Path receipt = Path.of("target", directory, table + ".json");
        String methodName = switch (suite) {
            case "Workload" -> "independentlyRemoveOwnedPolicyTargetAlarmsAndTableThenSaveCapture";
            case "Clock" -> "independentlyRemoveOwnedAlarmAndTableThenSaveCapture";
            default -> "independentlyRemoveOwnedAdmissionResourcesThenSaveCapture";
        };
        Method teardown = fixture.getClass().getDeclaredMethod(methodName);
        try {
            if (incomplete) {
                InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                        () -> teardown.invoke(fixture));
                AssertionError aggregate = assertInstanceOf(AssertionError.class, thrown.getCause());
                List<Throwable> suppressed = Arrays.asList(aggregate.getSuppressed());
                assertTrue(suppressed.contains(original), "Original cleanup exception must survive by identity");
                assertEquals(2, suppressed.size());
                assertTrue(suppressed.stream().anyMatch(failure -> failure instanceof AssertionError
                        && failure.getMessage().contains("Every attempted exchange")));
            } else {
                teardown.invoke(fixture);
            }
            verify(dynamo).close();
            verify(metrics).close();
            if (!suite.equals("Clock")) {
                verify(scaling).close();
            }
            JsonNode saved = new ObjectMapper().readTree(receipt.toFile());
            assertEquals(1, saved.get("calls").size());
            assertEquals(!incomplete, saved.get("calls").get(0).has("responseBase64"));
            assertEquals(incomplete ? 1 : 0, saved.get("observations").get("cleanupFailures").size());
        } finally {
            Files.deleteIfExists(receipt);
        }
    }

    private static Object get(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
