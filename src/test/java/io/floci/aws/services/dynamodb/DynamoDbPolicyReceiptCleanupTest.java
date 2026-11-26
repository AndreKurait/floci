package io.floci.aws.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.applicationautoscaling.ApplicationAutoScalingClient;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalableTargetsRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalableTargetsResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingActivitiesRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingActivitiesResponse;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingPoliciesRequest;
import software.amazon.awssdk.services.applicationautoscaling.model.DescribeScalingPoliciesResponse;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.DeleteAlarmsRequest;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsRequest;
import software.amazon.awssdk.services.cloudwatch.model.DescribeAlarmsResponse;
import software.amazon.awssdk.services.cloudwatch.model.MetricAlarm;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableResponse;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableResponse;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;
import software.amazon.awssdk.services.dynamodb.model.TableStatus;

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
        exercise(suite, true, "none");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Workload", "ReadAdmission", "ConditionalAdmission", "AdmissionContract", "Clock"})
    void completeReceiptAndSuccessfulCleanupCloseClients(String suite) throws Exception {
        exercise(suite, false, "none");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Workload", "ReadAdmission", "ConditionalAdmission", "AdmissionContract", "Clock"})
    void retriesDeletionAfterTransportRecovery(String suite) throws Exception {
        exercise(suite, false, "recover");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Workload", "ReadAdmission", "ConditionalAdmission", "AdmissionContract", "Clock"})
    void alreadyAbsentTableNeedsNoDeletion(String suite) throws Exception {
        exercise(suite, false, "absent");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Workload", "ReadAdmission", "ConditionalAdmission", "AdmissionContract", "Clock"})
    void replacementTableIsNeverDeletedAfterTransportFailure(String suite) throws Exception {
        exercise(suite, false, "replacement");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Workload", "ReadAdmission", "ConditionalAdmission", "AdmissionContract", "Clock"})
    void acknowledgedDeletionOnlyPollsForAbsence(String suite) throws Exception {
        exercise(suite, false, "acknowledged");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Workload", "ReadAdmission", "ConditionalAdmission", "AdmissionContract", "Clock"})
    void deletingTableAfterLostResponseOnlyPollsForAbsence(String suite) throws Exception {
        exercise(suite, false, "deleting");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Workload", "ReadAdmission", "ConditionalAdmission", "AdmissionContract"})
    void ownershipFailureDoesNotPreventLaterOwnedAlarmCleanup(String suite) throws Exception {
        exercise(suite, false, "none", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Workload", "ReadAdmission", "ConditionalAdmission", "AdmissionContract"})
    void cachedAlarmWithChangedActionIsNotDeleted(String suite) throws Exception {
        exercise(suite, false, "none", true, true);
    }

    private void exercise(String suite, boolean incomplete, String deletion) throws Exception {
        exercise(suite, incomplete, deletion, false);
    }

    private void exercise(String suite, boolean incomplete, String deletion, boolean alarmFailure) throws Exception {
        exercise(suite, incomplete, deletion, alarmFailure, false);
    }

    private void exercise(String suite, boolean incomplete, String deletion,
                          boolean alarmFailure, boolean cachedAlarm) throws Exception {
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
        String ownedAlarmName = "TargetTracking-table/" + table + "-owned";
        if (alarmFailure) {
            String account = "123456789012";
            String resource = "table/" + table;
            String policyName = "owned-policy";
            String policyArn = "arn:aws:autoscaling:us-east-1:" + account
                    + ":scalingPolicy:owned:resource/dynamodb/" + resource + ":policyName/" + policyName;
            set(fixture, "account", account);
            set(fixture, "resource", resource);
            set(fixture, "policyName", policyName);
            set(fixture, "policyArn", policyArn);
            set(fixture, "policyAttempted", true);
            MetricAlarm owned = MetricAlarm.builder().alarmName(ownedAlarmName)
                    .alarmArn("arn:aws:cloudwatch:us-east-1:" + account + ":alarm:" + ownedAlarmName)
                    .alarmActions(policyArn).build();
            MetricAlarm unowned = owned.toBuilder().alarmName("TargetTracking-" + resource + "-unowned")
                    .alarmArn("arn:aws:cloudwatch:us-east-1:" + account + ":alarm:unowned")
                    .alarmActions(policyArn + "-unexpected").build();
            if (cachedAlarm) {
                @SuppressWarnings("unchecked")
                Map<String, String> cache = (Map<String, String>) get(fixture, "ownedAlarms");
                cache.put(unowned.alarmName(), unowned.alarmArn());
            }
            when(metrics.describeAlarms(ArgumentMatchers.<Consumer<DescribeAlarmsRequest.Builder>>any()))
                    .thenAnswer(invocation -> {
                        Consumer<DescribeAlarmsRequest.Builder> action = invocation.getArgument(0);
                        DescribeAlarmsRequest.Builder request = DescribeAlarmsRequest.builder();
                        action.accept(request);
                        List<String> names = request.build().alarmNames();
                        if (!names.isEmpty()) {
                            return DescribeAlarmsResponse.builder().metricAlarms(
                                    names.equals(List.of(owned.alarmName())) ? owned : unowned).build();
                        }
                        return DescribeAlarmsResponse.builder().metricAlarms(unowned, owned).build();
                    });
        }
        if (!deletion.equals("none")) {
            set(fixture, "createAttempted", true);
            TableDescription owned = TableDescription.builder().tableName(table).tableId("owned-id").build();
            TableDescription replacement = owned.toBuilder().tableId("replacement-id").build();
            set(fixture, "initialTable", owned);
            ResourceNotFoundException absent = ResourceNotFoundException.builder().statusCode(400).build();
            if (deletion.equals("absent")) {
                when(dynamo.describeTable(ArgumentMatchers.<Consumer<DescribeTableRequest.Builder>>any()))
                        .thenThrow(absent);
            } else if (deletion.equals("recover") || deletion.equals("acknowledged") || deletion.equals("deleting")) {
                when(dynamo.describeTable(ArgumentMatchers.<Consumer<DescribeTableRequest.Builder>>any()))
                        .thenReturn(DescribeTableResponse.builder().table(owned).build())
                        .thenReturn(DescribeTableResponse.builder().table(deletion.equals("deleting")
                                ? owned.toBuilder().tableStatus(TableStatus.DELETING).build() : owned).build())
                        .thenThrow(absent);
            } else {
                when(dynamo.describeTable(ArgumentMatchers.<Consumer<DescribeTableRequest.Builder>>any()))
                        .thenReturn(DescribeTableResponse.builder().table(owned).build())
                        .thenReturn(DescribeTableResponse.builder().table(replacement).build());
            }
            if (deletion.equals("acknowledged")) {
                when(dynamo.deleteTable(ArgumentMatchers.<Consumer<DeleteTableRequest.Builder>>any()))
                        .thenReturn(DeleteTableResponse.builder().build());
            } else {
                when(dynamo.deleteTable(ArgumentMatchers.<Consumer<DeleteTableRequest.Builder>>any()))
                        .thenThrow(SdkClientException.create("Delete received no response"))
                        .thenReturn(DeleteTableResponse.builder().build());
            }
        }
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
            } else if (alarmFailure) {
                InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                        () -> teardown.invoke(fixture));
                AssertionError aggregate = assertInstanceOf(AssertionError.class, thrown.getCause());
                assertEquals(cachedAlarm ? 3 : 2, aggregate.getSuppressed().length,
                        "Discovery, cached ownership and retained inventory failures must be reported");
                verify(metrics).deleteAlarms(ArgumentMatchers.<Consumer<DeleteAlarmsRequest.Builder>>argThat(action -> {
                    DeleteAlarmsRequest.Builder request = DeleteAlarmsRequest.builder();
                    action.accept(request);
                    return request.build().alarmNames().equals(List.of(ownedAlarmName));
                }));
                verify(metrics, times(1)).deleteAlarms(
                        ArgumentMatchers.<Consumer<DeleteAlarmsRequest.Builder>>any());
            } else if (deletion.equals("replacement")) {
                InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                        () -> teardown.invoke(fixture));
                AssertionError aggregate = assertInstanceOf(AssertionError.class, thrown.getCause());
                assertTrue(Arrays.stream(aggregate.getSuppressed()).anyMatch(failure ->
                        failure instanceof AssertionError && failure.getMessage().contains("replacement-id")));
            } else {
                teardown.invoke(fixture);
            }
            if (!deletion.equals("none")) {
                int deletions = switch (deletion) {
                    case "recover" -> 2;
                    case "absent" -> 0;
                    default -> 1;
                };
                verify(dynamo, times(deletions)).deleteTable(
                        ArgumentMatchers.<Consumer<DeleteTableRequest.Builder>>any());
            }
            verify(dynamo).close();
            verify(metrics).close();
            if (!suite.equals("Clock")) {
                verify(scaling).close();
            }
            JsonNode saved = new ObjectMapper().readTree(receipt.toFile());
            assertEquals(1, saved.get("calls").size());
            assertEquals(!incomplete, saved.get("calls").get(0).has("responseBase64"));
            assertEquals(alarmFailure ? cachedAlarm ? 3 : 2 : incomplete || deletion.equals("replacement") ? 1 : 0,
                    saved.get("observations").get("cleanupFailures").size());
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
