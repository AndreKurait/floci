package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogExportTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudWatchLogsExportHandlerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CloudWatchLogsExportService service = mock(CloudWatchLogsExportService.class);
    private final CloudWatchLogsExportHandler handler = new CloudWatchLogsExportHandler(service, JSON);

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidWireValuesCannotReachAdmissionOrMutation(String action, String request) throws Exception {
        AwsException error = assertThrows(AwsException.class,
                () -> handler.handle(action, JSON.readTree(request), "us-east-1", "original authorization"));
        assertEquals("InvalidParameterException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        verifyNoInteractions(service);
    }

    @Test
    void defaultPrefixAndOriginalAuthorizationReachAdmissionAndInternalOwnershipStaysOutOfResponse() throws Exception {
        when(service.create("group", null, 0, 2, "bucket", "exportedlogs", null,
                "us-east-1", "original authorization")).thenReturn("id");
        JsonNode created = (JsonNode) handler.handle("CreateExportTask",
                JSON.readTree("{\"logGroupName\":\"group\",\"destination\":\"bucket\",\"from\":0,\"to\":2}"),
                "us-east-1", "original authorization").getEntity();
        assertEquals(JSON.readTree("{\"taskId\":\"id\"}"), created);
        when(service.describe(null, null, null, null, "us-east-1")).thenReturn(
                new CloudWatchLogsExportService.Page(List.of(new LogExportTask("123456789012", "us-east-1",
                        "id", null, "group", 0, 2, "bucket", "exportedlogs",
                        3, 4L, "COMPLETED", "complete")), null));
        JsonNode described = (JsonNode) handler.handle("DescribeExportTasks", JSON.createObjectNode(),
                "us-east-1", "original authorization").getEntity();
        assertEquals(JSON.readTree("""
                {"exportTasks":[{"taskId":"id","logGroupName":"group","from":0,"to":2,
                "destination":"bucket","destinationPrefix":"exportedlogs",
                "status":{"code":"COMPLETED","message":"complete"},
                "executionInfo":{"creationTime":3,"completionTime":4}}]}
                """), JSON.readTree(described.toString()));
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("CreateExportTask", "null"),
                Arguments.of("CreateExportTask", "[]"),
                Arguments.of("CreateExportTask", "{}"),
                create("from", "\"1\""),
                create("from", "1.5"),
                create("from", "9223372036854775808"),
                create("from", "-1"),
                create("from", "3"),
                create("to", "null"),
                create("destination", "17"),
                create("logGroupName", "\"bad:*\""),
                create("logStreamNamePrefix", "\"bad:*\""),
                create("destinationPrefix", "\"" + "界".repeat(400) + "\""),
                create("taskName", "\"\""),
                Arguments.of("DescribeExportTasks", "{\"limit\":0}"),
                Arguments.of("DescribeExportTasks", "{\"limit\":51}"),
                Arguments.of("DescribeExportTasks", "{\"limit\":1.5}"),
                Arguments.of("DescribeExportTasks", "{\"limit\":4294967297}"),
                Arguments.of("DescribeExportTasks", "{\"taskId\":true}"),
                Arguments.of("DescribeExportTasks", "{\"nextToken\":null}"),
                Arguments.of("CancelExportTask", "{}"),
                Arguments.of("CancelExportTask", "{\"taskId\":17}"));
    }

    private static Arguments create(String member, String value) {
        return Arguments.of("CreateExportTask",
                "{\"logGroupName\":\"group\",\"destination\":\"bucket\",\"from\":0,\"to\":2,"
                        + "\"" + member + "\":" + value + "}");
    }
}
