package io.floci.aws.services.cloudfront;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.cloudfront.model.CloudFrontFunction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@ApplicationScoped
public class CloudFrontFunctionTesting {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_-]{1,64}");
    private final AccountAwareStorageBackend<CloudFrontFunction> functions;
    private final AccountAwareStorageBackend<Map<String, String>> tags;
    private final CloudFrontService service;
    private final CloudFrontFunctionRuntime runtime;
    private final RegionResolver regions;
    private final ObjectMapper mapper;

    @Inject
    CloudFrontFunctionTesting(StorageFactory factory, CloudFrontService service, CloudFrontFunctionRuntime runtime,
                             RegionResolver regions, ObjectMapper mapper) {
        this.functions = factory.create("cloudfront", "cloudfront-functions.json",
                new TypeReference<Map<String, CloudFrontFunction>>() {});
        this.tags = factory.create("cloudfront", "cloudfront-tags.json",
                new TypeReference<Map<String, Map<String, String>>>() {});
        this.service = service;
        this.runtime = runtime;
        this.regions = regions;
        this.mapper = mapper;
    }

    CloudFrontFunctionRuntime.Result test(String name, String stage, String ifMatch, String encodedEvent) {
        requireName(name);
        String effectiveStage = stage == null ? "DEVELOPMENT" : stage;
        if (!"DEVELOPMENT".equals(effectiveStage) && !"LIVE".equals(effectiveStage)) {
            throw invalid("Stage must be DEVELOPMENT or LIVE.");
        }
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new AwsException("InvalidIfMatchVersion", "The If-Match version is missing or not valid.", 400);
        }
        String event = event(encodedEvent);
        String account = functions.accountId();
        String partition = regions.getPartition();
        return runtime.evaluate(() -> capture(account, partition, name, effectiveStage, ifMatch), event);
    }

    private String event(String encoded) {
        if (encoded == null || encoded.length() > 54616) {
            throw invalid("EventObject must contain at most 40960 bytes.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException malformed) {
            throw invalid("EventObject must be base64 encoded.");
        }
        if (bytes.length > 40960) {
            throw invalid("EventObject must contain at most 40960 bytes.");
        }
        String text = utf8(bytes);
        try {
            JsonNode value = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            if (value == null || !value.isObject()) {
                throw invalid("EventObject must contain a JSON object.");
            }
        } catch (IOException malformed) {
            throw invalid("EventObject must contain valid JSON.");
        }
        return text;
    }

    CloudFrontFunctionSnapshot capture(String account, String partition, String name, String stage, String ifMatch) {
        return service.readFunctionState(() -> {
            CloudFrontFunction function = find(account, name, stage).orElseThrow(() ->
                    new AwsException("NoSuchFunctionExists", "The specified function does not exist.", 404));
            if (function.getEtag() == null || function.getEtag().isBlank()
                    || function.getCreatedTime() == null || function.getLastModifiedTime() == null) {
                throw invalid("The stored function does not contain a complete version.");
            }
            if (!function.getEtag().equals(ifMatch)) {
                throw new AwsException("PreconditionFailed", "The function version no longer matches.", 412);
            }
            String code = code(function);
            return new CloudFrontFunctionSnapshot(account, partition,
                    AwsArnUtils.Arn.global(partition, "cloudfront", account, "function/" + name).toString(),
                    name, stage, function.getEtag(), function.getCreatedTime(), function.getLastModifiedTime(),
                    function.getStatus(), function.getRuntime() == null ? "cloudfront-js-2.0" : function.getRuntime(),
                    function.getComment() == null ? "" : function.getComment(), code,
                    function.getKeyValueStoreArns() == null ? List.of() : function.getKeyValueStoreArns());
        });
    }

    public Map<String, String> currentTags(String name) {
        requireName(name);
        String account = functions.accountId();
        String partition = regions.getPartition();
        return service.readFunctionState(() -> {
            if (find(account, name, "DEVELOPMENT").isEmpty() && find(account, name, "LIVE").isEmpty()) {
                return Map.of();
            }
            String arn = AwsArnUtils.Arn.global(partition, "cloudfront", account, "function/" + name).toString();
            Map<String, String> current = tags.getForAccount(account, arn)
                    .or(() -> tags.getLegacyForAccount(account, arn)).orElse(Map.of());
            if (current.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
                throw invalid("The stored function tags are invalid.");
            }
            return Map.copyOf(current);
        });
    }

    private Optional<CloudFrontFunction> find(String account, String name, String stage) {
        String key = "LIVE".equals(stage) ? name + "::LIVE" : name;
        Optional<CloudFrontFunction> function = read(account, key);
        if (function.isPresent()) {
            CloudFrontFunction value = function.get();
            if (!name.equals(value.getName())) {
                throw invalid("The stored function name does not match its key.");
            }
            if (stage.equals(value.getStage())) {
                return function;
            }
            if (!"DEVELOPMENT".equals(stage) || !"LIVE".equals(value.getStage())) {
                throw invalid("The stored function stage does not match its key.");
            }
            return Optional.empty();
        }
        if ("LIVE".equals(stage)) {
            Optional<CloudFrontFunction> old = read(account, name);
            if (old.isPresent()) {
                if (!name.equals(old.get().getName())) {
                    throw invalid("The stored function name does not match its key.");
                }
                if ("LIVE".equals(old.get().getStage())) {
                    return old;
                }
                if (!"DEVELOPMENT".equals(old.get().getStage())) {
                    throw invalid("The stored function stage is invalid.");
                }
            }
        }
        return Optional.empty();
    }

    private Optional<CloudFrontFunction> read(String account, String key) {
        return functions.getForAccount(account, key).or(() -> functions.getLegacyForAccount(account, key));
    }

    private static String code(CloudFrontFunction function) {
        String encoded = function.getFunctionCode();
        if (encoded == null || encoded.length() > 16384) {
            throw invalid("The local function source limit is 10240 UTF-8 bytes.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException legacyPlainCode) {
            // Older CloudFormation records stored plain source, as GetFunction still serves it.
            bytes = encoded.getBytes(StandardCharsets.UTF_8);
        }
        if (bytes.length > 10240) {
            throw invalid("The local function source limit is 10240 UTF-8 bytes.");
        }
        return utf8(bytes);
    }

    private static String utf8(byte[] value) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException malformed) {
            throw invalid("The function source and event must contain valid UTF-8.");
        }
    }

    private static void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw invalid("The function name is invalid.");
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidArgument", message, 400);
    }
}
