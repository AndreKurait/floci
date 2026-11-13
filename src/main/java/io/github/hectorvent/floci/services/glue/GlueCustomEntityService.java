package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SignedMarkers;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.glue.model.CustomEntityType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Native custom-pattern configuration, scoped like Glue's other regional definition stores. */
@ApplicationScoped
public class GlueCustomEntityService {
    private static final String INVALID = "InvalidInputException";
    private final AccountAwareStorageBackend<CustomEntityType> store;
    private final RegionResolver regions;
    private final ObjectMapper mapper;
    private final SignedMarkers markers = new SignedMarkers();

    @Inject
    public GlueCustomEntityService(StorageFactory storageFactory, RegionResolver regions, ObjectMapper mapper) {
        this(storageFactory.create("glue", "custom_entity_types.json", new TypeReference<>() {}), regions, mapper);
    }

    GlueCustomEntityService(AccountAwareStorageBackend<CustomEntityType> store,
                            RegionResolver regions, ObjectMapper mapper) {
        this.store = store;
        this.regions = regions;
        this.mapper = mapper;
    }

    public synchronized Map<String, Object> create(JsonNode request, String region) {
        requireObject(request);
        String name = patternString(request.get("Name"), "Name");
        String expression = patternString(request.get("RegexString"), "RegexString");
        List<String> words = optional(request, "ContextWords")
                ? patternStrings(request.get("ContextWords"), "ContextWords", 20) : null;
        Map<String, String> tags = tags(request.get("Tags"));
        String account = regions.getAccountId();
        String key = key(region, name);
        if (store.getForAccount(account, key).isPresent()) {
            throw new AwsException("AlreadyExistsException",
                    "A Custom Entity Type with the same name exist in this account", 400);
        }
        CustomEntityType pattern = new CustomEntityType(name, expression, words, tags);
        store.putAllAtomicallyForAccount(account, Map.of(key, pattern));
        return Map.of("Name", name);
    }

    public synchronized Map<String, Object> get(JsonNode request, String region) {
        requireObject(request);
        String name = patternString(request.get("Name"), "Name");
        return get(regions.getAccountId(), region, name).response();
    }

    public synchronized Map<String, Object> delete(JsonNode request, String region) {
        requireObject(request);
        String name = patternString(request.get("Name"), "Name");
        String account = regions.getAccountId();
        get(account, region, name);
        store.deleteAllAtomicallyForAccounts(Map.of(account, Set.of(key(region, name))));
        return Map.of("Name", name);
    }

    public synchronized Map<String, Object> batchGet(JsonNode request, String region) {
        requireObject(request);
        List<String> names = patternStrings(request.get("Names"), "Names", 50);
        String account = regions.getAccountId();
        List<Map<String, Object>> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            store.getForAccount(account, key(region, name))
                    .ifPresentOrElse(pattern -> found.add(pattern.response()), () -> missing.add(name));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("CustomEntityTypes", found);
        if (!missing.isEmpty()) {
            response.put("CustomEntityTypesNotFound", missing);
        }
        return response;
    }

    public synchronized Map<String, Object> list(JsonNode request, String region) {
        requireObject(request);
        int limit = 1000;
        if (optional(request, "MaxResults")) {
            JsonNode maximum = request.get("MaxResults");
            if (!maximum.isIntegralNumber() || !maximum.canConvertToInt()
                    || maximum.intValue() < 1 || maximum.intValue() > 1000) {
                throw invalid("MaxResults must be an integer between 1 and 1000.");
            }
            limit = maximum.intValue();
        }
        String token = null;
        if (optional(request, "NextToken")) {
            JsonNode value = request.get("NextToken");
            if (!value.isTextual()) {
                throw invalid("NextToken must be a string.");
            }
            token = value.textValue();
        }
        Map<String, String> filter = tags(request.get("Tags"));
        String account = regions.getAccountId();
        List<CustomEntityType> all = store.scanForAccount(account, key -> key.startsWith(region + "/"))
                .stream()
                .filter(pattern -> filter.entrySet().stream()
                        .allMatch(tag -> tag.getValue().equals(pattern.tags().get(tag.getKey()))))
                .toList();
        String scope;
        try {
            scope = mapper.writeValueAsString(List.of(account, region, new TreeMap<>(filter)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot encode a custom-pattern listing scope.", e);
        }
        PaginatedResult<CustomEntityType> page = Pagination.slice(
                all, CustomEntityType::name, limit, token, markers, scope,
                ignored -> invalid("Invalid NextToken."));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("CustomEntityTypes", page.items().stream().map(CustomEntityType::response).toList());
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    private CustomEntityType get(String account, String region, String name) {
        return store.getForAccount(account, key(region, name)).orElseThrow(() ->
                new AwsException("EntityNotFoundException",
                        "An existing Custom Entity Type: " + name + " cannot be found for Account Id " + account, 400));
    }

    private static String key(String region, String name) {
        return region + "/" + name;
    }

    private static void requireObject(JsonNode request) {
        if (request == null || !request.isObject()) {
            throw invalid("The request must be an object.");
        }
    }

    private static boolean optional(JsonNode request, String member) {
        return request.hasNonNull(member);
    }

    private static String patternString(JsonNode value, String member) {
        String text = string(value, member, 1, 255);
        if (!text.codePoints().allMatch(point -> point == '\t'
                || point >= 0x20 && point <= 0xD7FF
                || point >= 0xE000 && point <= 0xFFFD
                || point >= 0x10000 && point <= 0x10FFFF)) {
            throw invalid(member + " contains a character outside its supported pattern.");
        }
        return text;
    }

    private static String string(JsonNode value, String member, int minimum, int maximum) {
        if (value == null || !value.isTextual()) {
            throw invalid(member + " must be a string.");
        }
        String text = value.textValue();
        int length = text.codePointCount(0, text.length());
        if (length < minimum || length > maximum) {
            throw invalid(member + " must contain between " + minimum + " and " + maximum + " characters.");
        }
        return text;
    }

    private static List<String> patternStrings(JsonNode value, String member, int maximum) {
        if (value == null || !value.isArray() || value.isEmpty() || value.size() > maximum) {
            throw invalid(member + " must contain between 1 and " + maximum + " strings.");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : value) {
            result.add(patternString(element, member + "[]"));
        }
        return List.copyOf(result);
    }

    private static Map<String, String> tags(JsonNode value) {
        if (value == null || value.isNull()) {
            return Map.of();
        }
        if (!value.isObject() || value.size() > 50) {
            throw invalid("Tags must be an object containing at most 50 entries.");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        value.properties().forEach(entry -> {
            String key = entry.getKey();
            int length = key.codePointCount(0, key.length());
            if (length < 1 || length > 128) {
                throw invalid("A tag key must contain between 1 and 128 characters.");
            }
            tags.put(key, string(entry.getValue(), "Tags[" + key + "]", 0, 256));
        });
        return Map.copyOf(tags);
    }

    private static AwsException invalid(String message) {
        return new AwsException(INVALID, message, 400);
    }
}
