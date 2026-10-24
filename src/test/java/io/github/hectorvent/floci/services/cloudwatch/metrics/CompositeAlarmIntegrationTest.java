package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CompositeAlarmIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCOPE =
            "AWS4-HMAC-SHA256 Credential=test/20261010/cn-north-1/monitoring/aws4_request";

    @TestHTTPResource
    URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothProtocolsCreateDescribeEvaluateAndDeleteRealAlarms(boolean json) throws Exception {
        String prefix = "wire-composite-" + Long.toString(System.nanoTime(), 36);
        String source = prefix + "-source";
        String aggregate = prefix + "-aggregate";
        call(json, "PutCompositeAlarm", Map.of("AlarmName", source, "AlarmRule", "TRUE")).then().statusCode(200);
        call(json, "PutCompositeAlarm", Map.of("AlarmName", aggregate, "AlarmRule", "ALARM(\"" + source + "\")",
                "ActionsEnabled", false, "AlarmDescription", "combined")).then().statusCode(200);
        Response result = call(json, "DescribeAlarms", Map.of("AlarmNames", List.of(aggregate),
                "AlarmTypes", List.of("CompositeAlarm")));
        result.then().statusCode(200);
        if (json) {
            assertEquals(List.of(), result.jsonPath().getList("LogAlarms"));
            assertEquals(1, result.jsonPath().getList("CompositeAlarms").size());
            assertEquals("ALARM", result.jsonPath().getString("CompositeAlarms[0].StateValue"));
            assertEquals("arn:aws-cn:cloudwatch:cn-north-1:000000000000:alarm:" + aggregate,
                    result.jsonPath().getString("CompositeAlarms[0].AlarmArn"));
            assertFalse(result.jsonPath().getBoolean("CompositeAlarms[0].ActionsEnabled"));
        } else {
            assertTrue(result.asString().contains("<LogAlarms>"));
            assertEquals("ALARM", result.xmlPath().getString(
                    "DescribeAlarmsResponse.DescribeAlarmsResult.CompositeAlarms.member.StateValue"));
            assertTrue(result.asString().contains("arn:aws-cn:cloudwatch:cn-north-1:000000000000:alarm:" + aggregate));
            assertEquals("false", result.xmlPath().getString(
                    "DescribeAlarmsResponse.DescribeAlarmsResult.CompositeAlarms.member.ActionsEnabled"));
        }
        assertFalse(call(json, "DescribeAlarms", Map.of("AlarmNames", List.of(aggregate)))
                .then().statusCode(200).extract().asString().contains(aggregate));
        call(json, "SetAlarmState", Map.of("AlarmName", source, "StateValue", "OK",
                "StateReason", "recovery")).then().statusCode(200);
        Response recovered = call(json, "DescribeAlarms", Map.of("AlarmNames", List.of(aggregate),
                "AlarmTypes", List.of("CompositeAlarm")));
        assertEquals("OK", json ? recovered.jsonPath().getString("CompositeAlarms[0].StateValue")
                : recovered.xmlPath().getString(
                        "DescribeAlarmsResponse.DescribeAlarmsResult.CompositeAlarms.member.StateValue"));
        for (String name : List.of(aggregate, source)) {
            call(json, "DeleteAlarms", Map.of("AlarmNames", List.of(name))).then().statusCode(200);
        }
        Response empty = call(json, "DescribeAlarms", Map.of("AlarmNamePrefix", prefix,
                "AlarmTypes", List.of("CompositeAlarm")));
        assertFalse(empty.asString().contains(aggregate));
        assertFalse(empty.asString().contains(source));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidRuleAndUnsupportedSuppressionCannotCreateAnAlarm(boolean json) throws Exception {
        String name = "invalid-composite-" + Long.toString(System.nanoTime(), 36);
        call(json, "PutCompositeAlarm", Map.of("AlarmName", name, "AlarmRule", "TRUE AND"))
                .then().statusCode(400);
        call(json, "PutCompositeAlarm", Map.of("AlarmName", name, "AlarmRule", "TRUE",
                "ActionsSuppressor", "other")).then().statusCode(400);
        assertFalse(call(json, "DescribeAlarms", Map.of("AlarmNames", List.of(name),
                "AlarmTypes", List.of("CompositeAlarm"))).asString().contains(name));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void alarmTypeValidationAcceptsLogAlarmsAndUsesSmithyErrors(boolean json) throws Exception {
        call(json, "DescribeAlarms", Map.of("AlarmTypes", List.of("LogAlarm"))).then().statusCode(200);
        Response error = call(json, "DescribeAlarms", Map.of("AlarmTypes", List.of("Unknown")));
        error.then().statusCode(400);
        assertEquals("1 validation error detected: Value '[Unknown]' at 'alarmTypes' failed to satisfy constraint: "
                + "Member must satisfy constraint: [Member must satisfy enum value set: "
                + "[CompositeAlarm, MetricAlarm, LogAlarm]]", errorMessage(json, error));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lengthErrorsUseSmithyMessages(boolean json) throws Exception {
        for (String field : List.of("AlarmName", "AlarmRule")) {
            int maximum = "AlarmName".equals(field) ? 255 : 10240;
            String path = "AlarmName".equals(field) ? "alarmName" : "alarmRule";
            for (String value : List.of("", " ", "x".repeat(maximum + 1), " ".repeat(maximum + 1))) {
                Map<String, String> request = new LinkedHashMap<>(Map.of("AlarmName", "length-validation", "AlarmRule", "TRUE"));
                request.put(field, value);
                Response error = call(json, "PutCompositeAlarm", request);
                error.then().statusCode(400);
                assertEquals("1 validation error detected: Value '" + value + "' at '" + path
                        + "' failed to satisfy constraint: Member must have length "
                        + (value.length() <= maximum ? "greater than or equal to 1" : "less than or equal to " + maximum),
                        errorMessage(json, error));
            }
        }
    }

    private String errorMessage(boolean json, Response response) {
        return json ? response.jsonPath().getString("message") : response.xmlPath().getString("ErrorResponse.Error.Message");
    }

    private Response call(boolean json, String operation, Map<String, ?> body)
            throws JsonProcessingException {
        if ("PutCompositeAlarm".equals(operation)) {
            String name = body.get("AlarmName").toString();
            cleanup.register(() -> given().port(baseUri.getPort()).contentType("application/x-amz-json-1.0")
                    .header("Authorization", SCOPE)
                    .header("X-Amz-Target", "GraniteServiceVersion20100801.DeleteAlarms")
                    .body(MAPPER.valueToTree(Map.of("AlarmNames", List.of(name))).toString())
                    .post("/").then().statusCode(200));
        }
        if (json) {
            return given().contentType("application/x-amz-json-1.0").header("Authorization", SCOPE)
                    .header("X-Amz-Target", "GraniteServiceVersion20100801." + operation)
                    .body(MAPPER.writeValueAsString(body)).post("/");
        }
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("Action", operation);
        body.forEach((key, value) -> {
            if (value instanceof List<?> values) {
                for (int index = 0; index < values.size(); index++) {
                    form.put(key + ".member." + (index + 1), values.get(index));
                }
            } else {
                form.put(key, value);
            }
        });
        return given().contentType("application/x-www-form-urlencoded").header("Authorization", SCOPE)
                .formParams(form).post("/");
    }
}
