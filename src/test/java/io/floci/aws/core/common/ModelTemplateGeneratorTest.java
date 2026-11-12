package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTemplateGeneratorTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "true", "null", "{\"type\":[\"string\",\"null\"]}",
            "{\"allOf\":[{\"type\":\"object\"}]}", "{\"type\":\"string\",\"enum\":[\"a\"]}",
            "{\"$ref\":\"https://example.invalid/schema\"}", "{\"$ref\":\"#/missing\"}",
            "{\"type\":\"array\"}", "{\"type\":\"array\",\"items\":[{\"type\":\"string\"}]}",
            "{\"type\":\"object\",\"properties\":[]}",
            "{\"type\":\"object\",\"properties\":{\"#set\":{\"type\":\"string\"}}}",
            "{\"definitions\":{\"Loop\":{\"$ref\":\"#/definitions/Loop\"}},\"$ref\":\"#/definitions/Loop\"}"
    })
    void explicitlyRefusesUnsupportedSchemas(String schema) {
        AwsException error = assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate(schema, "application/json", true));
        assertEquals(501, error.getHttpStatus());
    }

    @Test
    void malformedSchemaHasAClientError() {
        assertEquals(400, assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate("{", "application/json", true)).getHttpStatus());
        assertEquals(400, assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate(null, "application/json", false)).getHttpStatus());
    }

    @Test
    void refusesNonJsonContent() {
        assertEquals(501, assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate("{\"type\":\"string\"}", "application/xml", false))
                .getHttpStatus());
    }

    @Test
    void boundsDeepAndLargeSchemasBeforeUnboundedExpansion() {
        String schema = "{\"type\":\"string\"}";
        for (int i = 0; i < 35; i++) {
            schema = "{\"type\":\"array\",\"items\":" + schema + "}";
        }
        String deep = schema;
        assertEquals(501, assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate(deep, "application/json", true)).getHttpStatus());
        assertEquals(501, assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate("x".repeat(1_048_577), "application/json", true))
                .getHttpStatus());
    }

    @Test
    void referenceExpansionIsBoundedAndRepeatedFiniteReferencesWork() {
        String schema = """
                {"definitions":{"S":{"type":"string"}},"type":"object","properties":{
                "left":{"$ref":"#/definitions/S"},"right":{"$ref":"#/definitions/S"}}}
                """;
        assertTrue(ModelTemplateGenerator.generate(schema, "application/json; charset=utf-8", true)
                .contains(".get('right')"));
        String many = "{\"type\":\"object\",\"properties\":{";
        StringBuilder properties = new StringBuilder(many);
        for (int i = 0; i < 4_100; i++) {
            if (i > 0) {
                properties.append(',');
            }
            properties.append('"').append(i).append("\":{\"type\":\"string\"}");
        }
        properties.append("}}");
        assertEquals(501, assertThrows(AwsException.class,
                () -> ModelTemplateGenerator.generate(properties.toString(), "application/json", true))
                .getHttpStatus());
    }
}
