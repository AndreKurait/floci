package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
            assertEquals(1, result.jsonPath().getList("CompositeAlarms").size());
            assertEquals("ALARM", result.jsonPath().getString("CompositeAlarms[0].StateValue"));
            assertEquals("arn:aws-cn:cloudwatch:cn-north-1:000000000000:alarm:" + aggregate,
                    result.jsonPath().getString("CompositeAlarms[0].AlarmArn"));
            assertFalse(result.jsonPath().getBoolean("CompositeAlarms[0].ActionsEnabled"));
        } else {
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

    private static Response call(boolean json, String operation, Map<String, ?> body)
            throws JsonProcessingException {
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
