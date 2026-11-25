package io.floci.aws.services.appconfig;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.DisallowUnknownJsonMetaSchemaFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaException;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.appconfig.model.Validator;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The inline draft-04 validators supported by AppConfig configuration profiles. */
final class AppConfigValidators {
    private static final int MAX_VALIDATORS = 2;
    private static final int MAX_CONTENT_LENGTH = 32768;
    private static final Set<String> DIALECTS = Set.of(
            "http://json-schema.org/draft-04/schema", "http://json-schema.org/draft-04/schema#",
            "https://json-schema.org/draft-04/schema", "https://json-schema.org/draft-04/schema#");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4,
            builder -> builder.metaSchemaFactory(DisallowUnknownJsonMetaSchemaFactory.getInstance())
                    .schemaLoaders(loaders -> loaders.add(iri -> {
                        throw new IllegalArgumentException("Only local JSON Schema references are supported.");
                    })));
    private static final JsonSchema META_SCHEMA = metaSchema();

    private AppConfigValidators() {
    }

    static List<Validator> parse(Object value, String profileType) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> entries) || entries.size() > MAX_VALIDATORS) {
            throw invalid("Validators must be a list of at most " + MAX_VALIDATORS + " validators.");
        }
        List<Validator> validators = new ArrayList<>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> fields)
                    || !(fields.get("Type") instanceof String type)
                    || !(fields.get("Content") instanceof String content)
                    || content.length() > MAX_CONTENT_LENGTH) {
                throw invalid("Each validator requires a Type and string Content of at most "
                        + MAX_CONTENT_LENGTH + " characters.");
            }
            switch (type) {
                case "JSON_SCHEMA" -> {
                    if ("AWS.AppConfig.FeatureFlags".equals(profileType)) {
                        throw invalid("JSON_SCHEMA validators require a free form configuration profile.");
                    }
                }
                case "LAMBDA" -> {
                    // Preserve the declaration, but never report successful validation without invoking it.
                }
                default -> throw invalid("Validator Type must be JSON_SCHEMA or LAMBDA.");
            }
            validators.add(new Validator(type, content));
        }
        return List.copyOf(validators);
    }

    static void validate(List<Validator> validators, byte[] content) {
        if (validators == null || validators.isEmpty()) {
            return;
        }
        if (validators.stream().anyMatch(validator -> !"JSON_SCHEMA".equals(validator.type()))) {
            throw invalid("Lambda configuration validation is not currently supported.");
        }
        JsonNode configuration;
        try {
            configuration = MAPPER.readTree(content);
        } catch (IOException | IllegalArgumentException e) {
            throw invalid("Configuration is not a single JSON value.");
        }
        if (configuration == null || configuration.isMissingNode()) {
            throw invalid("Configuration is not a single JSON value.");
        }
        for (Validator validator : validators) {
            try {
                if (!compile(validator.content()).validate(configuration).isEmpty()) {
                    throw invalid("Configuration does not conform to its JSON Schema.");
                }
            } catch (JsonSchemaException | IllegalArgumentException e) {
                throw invalid("Configuration could not be validated against its JSON Schema.");
            }
        }
    }

    private static JsonSchema compile(String content) {
        try {
            JsonNode declaration = MAPPER.readTree(content);
            if (declaration == null || !declaration.isObject()) {
                throw invalid("JSON Schema must be an object.");
            }
            JsonNode dialect = declaration.get("$schema");
            if (dialect != null && (!dialect.isTextual() || !DIALECTS.contains(dialect.textValue()))) {
                throw invalid("Inline JSON Schema validators support draft-04 only.");
            }
            if (!META_SCHEMA.validate(declaration).isEmpty()) {
                throw invalid("JSON Schema declaration is invalid.");
            }
            JsonSchema schema = FACTORY.getSchema(declaration);
            schema.initializeValidators();
            return schema;
        } catch (JsonProcessingException | JsonSchemaException | IllegalArgumentException e) {
            throw invalid("JSON Schema declaration is invalid or uses an unsupported reference.");
        }
    }

    private static JsonSchema metaSchema() {
        try (InputStream resource = AppConfigValidators.class.getResourceAsStream("/draft-04/schema")) {
            if (resource == null) {
                throw new IllegalStateException("The bundled draft-04 meta-schema is missing.");
            }
            JsonSchema schema = FACTORY.getSchema(resource);
            schema.initializeValidators();
            return schema;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the bundled draft-04 meta-schema.", e);
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("BadRequestException", message, 400);
    }
}
