package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appconfig.model.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigValidatorsTest {
    private static final String SCHEMA = """
            {"$schema":"http://json-schema.org/draft-04/schema#","type":"object",
             "definitions":{"limit":{"type":"integer","minimum":1,"maximum":100}},
             "properties":{"limit":{"$ref":"#/definitions/limit"}},
             "required":["limit"],"additionalProperties":false}
            """;

    @Test
    void localReferencesEnforceRequiredFieldsTypesAndNumericLimits() {
        List<Validator> validators = schema(SCHEMA);
        assertDoesNotThrow(() -> validate(validators, "{\"limit\":10}"));
        for (String content : List.of("{}", "{\"limit\":\"10\"}", "{\"limit\":0}", "{\"limit\":101}",
                "{\"limit\":10,\"unknown\":false}")) {
            assertInvalid(() -> validate(validators, content));
        }
    }

    @Test
    void patternPropertiesArraysAndEnumsAreValidatedByTheSchema() {
        List<Validator> validators = schema("""
                {"type":"object","patternProperties":{"^[a-z]+$":{
                  "type":"array","minItems":1,"uniqueItems":true,
                  "items":{"enum":["small","large"]}}},"additionalProperties":false}
                """);
        assertDoesNotThrow(() -> validate(validators, "{\"sizes\":[\"small\",\"large\"]}"));
        for (String content : List.of("{\"Sizes\":[\"small\"]}", "{\"sizes\":[]}",
                "{\"sizes\":[\"small\",\"small\"]}", "{\"sizes\":[\"unknown\"]}")) {
            assertInvalid(() -> validate(validators, content));
        }
    }

    @Test
    void draft04FormatsAreAssertions() {
        List<Validator> validators = schema("{\"type\":\"string\",\"format\":\"date-time\"}");
        assertDoesNotThrow(() -> validate(validators, "\"2025-01-02T03:04:05Z\""));
        assertInvalid(() -> validate(validators, "\"not-a-date\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "[]", "true", "not-json", "{} {}", "{\"type\":\"not-a-type\"}",
            "{\"maximum\":\"100\"}", "{\"required\":[]}", "{\"pattern\":\"[\"}",
            "{\"$ref\":\"#/definitions/missing\"}", "{\"$ref\":\"https://example.invalid/schema\"}",
            "{\"$schema\":\"http://json-schema.org/draft-07/schema#\"}"})
    void malformedOrUnsupportedSchemasAreRefusedBeforeStorage(String declaration) {
        assertInvalid(() -> schema(declaration));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "{", "{} {}", "{\"limit\":1} trailing", "raw-text"})
    void validationRequiresExactlyOneJsonValue(String content) {
        assertInvalid(() -> validate(schema("{}"), content));
    }

    @Test
    void jsonNullIsAValue() {
        assertDoesNotThrow(() -> validate(schema("{\"type\":\"null\"}"), "null"));
    }

    @Test
    void validatorShapeCountAndContentLengthAreChecked() {
        for (Object value : List.of("not-a-list", List.of("not-an-object"),
                List.of(Map.of("Type", "JSON_SCHEMA")),
                List.of(Map.of("Type", "JSON_SCHEMA", "Content", 1)),
                List.of(Map.of("Type", "UNKNOWN", "Content", "{}")),
                List.of(Map.of("Type", "JSON_SCHEMA", "Content", " ".repeat(32769))),
                List.of(Map.of("Type", "JSON_SCHEMA", "Content", "{}"),
                        Map.of("Type", "JSON_SCHEMA", "Content", "{}"),
                        Map.of("Type", "JSON_SCHEMA", "Content", "{}")))) {
            assertInvalid(() -> AppConfigValidators.parse(value, "AWS.Freeform"));
        }
        String prefix = "{\"description\":\"";
        String suffix = "\"}";
        String limit = prefix + "a".repeat(32768 - prefix.length() - suffix.length()) + suffix;
        assertDoesNotThrow(() -> schema(limit));
        assertInvalid(() -> AppConfigValidators.parse(
                List.of(Map.of("Type", "JSON_SCHEMA", "Content", "{}")), "AWS.AppConfig.FeatureFlags"));
    }

    @Test
    void everyValidatorMustPassAndTheParsedListIsIndependentOfTheRequest() {
        List<Map<String, String>> input = new ArrayList<>(List.of(
                Map.of("Type", "JSON_SCHEMA", "Content", "{\"type\":\"integer\"}"),
                Map.of("Type", "JSON_SCHEMA", "Content", "{\"minimum\":10}")));
        List<Validator> validators = AppConfigValidators.parse(input, "AWS.Freeform");
        input.clear();
        assertEquals(2, validators.size());
        assertThrows(UnsupportedOperationException.class, validators::clear);
        assertDoesNotThrow(() -> validate(validators, "10"));
        assertInvalid(() -> validate(validators, "9"));
        assertInvalid(() -> validate(validators, "\"10\""));
    }

    @Test
    void lambdaDeclarationsNeverPretendToHaveRunSuccessfully() {
        List<Validator> validators = AppConfigValidators.parse(List.of(Map.of(
                "Type", "LAMBDA", "Content", "arn:aws:lambda:us-east-1:000000000000:function:validate")),
                "AWS.AppConfig.FeatureFlags");
        assertEquals("LAMBDA", validators.getFirst().type());
        AwsException error = assertThrows(AwsException.class, () -> validate(validators, "{}"));
        assertTrue(error.getMessage().contains("not currently supported"));
    }

    private static List<Validator> schema(String content) {
        return AppConfigValidators.parse(List.of(Map.of("Type", "JSON_SCHEMA", "Content", content)), "AWS.Freeform");
    }

    private static void validate(List<Validator> validators, String content) {
        AppConfigValidators.validate(validators, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInvalid(Runnable action) {
        assertEquals("BadRequestException", assertThrows(AwsException.class, action::run).getErrorCode());
    }
}
