package io.floci.aws.services.amazonmq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.common.SignedMarkers;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.amazonmq.model.ConfigurationState;
import io.floci.aws.services.amazonmq.model.ConfigurationState.Configuration;
import io.floci.aws.services.amazonmq.model.ConfigurationState.Revision;
import io.floci.aws.services.amazonmq.model.ConfigurationState.Scope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Standalone RabbitMQ configuration storage; no broker or engine work is performed here. */
@ApplicationScoped
public class AmazonMqConfigurationService implements Resettable {

    static final int MAX_CONFIGURATIONS = 50;
    static final int MAX_REVISIONS = 300;
    static final int MAX_RETIRED = 1_000;
    static final int MAX_DOCUMENT_BYTES = 64 * 1024;
    static final int MAX_SCOPE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_MARKER_LENGTH = 2048;
    private static final Pattern ID = Pattern.compile("c-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._~-]{1,150}");
    private static final String ENGINE = "RABBITMQ";
    private static final String AUTHENTICATION = "SIMPLE";

    private final AccountAwareStorageBackend<ConfigurationState> storage;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final Supplier<String> ids;
    private SignedMarkers markers = new SignedMarkers(MAX_MARKER_LENGTH);
    private boolean resetting;

    @Inject
    public AmazonMqConfigurationService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("amazonmq", "amazonmq-configurations.json",
                new TypeReference<Map<String, ConfigurationState>>() {}), regionResolver,
                Clock.systemUTC(), () -> "c-" + UUID.randomUUID());
    }

    AmazonMqConfigurationService(AccountAwareStorageBackend<ConfigurationState> storage,
                                 RegionResolver regionResolver, Clock clock, Supplier<String> ids) {
        this.storage = storage;
        this.regionResolver = regionResolver;
        this.clock = clock;
        this.ids = ids;
    }

    public record Create(String name, String engineType, String engineVersion,
                         String authenticationStrategy, Map<String, String> tags) {
        public Create {
            tags = tags == null ? Map.of() : Map.copyOf(tags);
        }
    }

    public record Page<T>(List<T> items, String nextToken) {
        public Page {
            items = List.copyOf(items);
        }
    }

    public Scope scope() {
        return new Scope(regionResolver.getPartition(), regionResolver.getAccountId(), regionResolver.getRegion());
    }

    public static String configurationId(String value) {
        if (value == null || !ID.matcher(value).matches()) {
            throw badRequest("Invalid configurationId");
        }
        return value;
    }

    public static String configurationArn(Scope scope, String id) {
        return new AwsArnUtils.Arn(scope.partition(), "mq", scope.region(), scope.accountId(),
                "configuration:" + configurationId(id)).toString();
    }

    public synchronized Configuration create(Create request, Consumer<String> authorizeTags) {
        requireOpen();
        if (request == null || request.name() == null || !NAME.matcher(request.name()).matches()) {
            throw badRequest("Name must contain 1 to 150 letters, numbers, or . _ ~ -");
        }
        if (!ENGINE.equals(request.engineType())) {
            throw badRequest("Only RABBITMQ configurations are supported");
        }
        if (request.authenticationStrategy() != null && !AUTHENTICATION.equals(request.authenticationStrategy())) {
            throw badRequest("Only SIMPLE authentication is supported");
        }
        String version = request.engineVersion() == null ? "3.13" : request.engineVersion();
        if (version.isBlank() || bytes(version) > 64) {
            throw badRequest("Invalid engineVersion");
        }
        validateTags(request.tags());
        Scope scope = scope();
        ConfigurationState before = load(scope);
        if (before.configurations().size() >= MAX_CONFIGURATIONS) {
            throw limit("Local configuration capacity reached");
        }
        String id = configurationId(ids.get());
        if (before.configurations().containsKey(id) || before.retired().containsKey(id)) {
            throw internal("Configuration identity collision");
        }
        Instant now = clock.instant();
        Configuration created = new Configuration(id, configurationArn(scope, id), request.name(), ENGINE,
                version, AUTHENTICATION, now, request.tags(), List.of(new Revision(1, now, "", null)));
        Map<String, Configuration> next = new LinkedHashMap<>(before.configurations());
        next.put(id, created);
        ConfigurationState candidate = new ConfigurationState(scope, next, before.retired());
        validate(candidate, scope);
        if (!created.tags().isEmpty()) {
            if (authorizeTags == null) {
                throw internal("Tagged creation requires an authorization callback");
            }
            authorizeTags.accept(created.arn());
        }
        commit(candidate);
        return created;
    }

    public synchronized Configuration describe(String id) {
        requireOpen();
        return existing(load(scope()), configurationId(id));
    }

    public synchronized Configuration update(String id, String data, String description) {
        requireOpen();
        configurationId(id);
        String accepted = document(data);
        String acceptedDescription = description(description);
        Scope scope = scope();
        ConfigurationState before = load(scope);
        if (before.retired().containsKey(id)) {
            throw new AwsException("ConflictException", "Configuration has been deleted", 409);
        }
        Configuration current = existing(before, id);
        if (current.revisions().size() >= MAX_REVISIONS) {
            throw limit("Configuration revision capacity reached");
        }
        if (documentBytes(before) + decodedBytes(accepted) > MAX_SCOPE_BYTES) {
            throw limit("Local configuration document capacity reached");
        }
        List<Revision> revisions = new ArrayList<>(current.revisions());
        revisions.add(new Revision(revisions.size() + 1, clock.instant(), acceptedDescription, accepted));
        Configuration updated = new Configuration(current.id(), current.arn(), current.name(), current.engineType(),
                current.engineVersion(), current.authenticationStrategy(), current.created(), current.tags(), revisions);
        Map<String, Configuration> next = new LinkedHashMap<>(before.configurations());
        next.put(id, updated);
        commit(new ConfigurationState(scope, next, before.retired()));
        return updated;
    }

    public synchronized void delete(String id) {
        requireOpen();
        configurationId(id);
        Scope scope = scope();
        ConfigurationState before = load(scope);
        existing(before, id);
        Map<String, Configuration> next = new LinkedHashMap<>(before.configurations());
        next.remove(id);
        Map<String, Instant> retired = new LinkedHashMap<>(before.retired());
        retired.put(id, clock.instant());
        while (retired.size() > MAX_RETIRED) {
            retired.remove(retired.keySet().iterator().next());
        }
        commit(new ConfigurationState(scope, next, retired));
    }

    public synchronized Revision revision(String id, String label) {
        requireOpen();
        Configuration current = existing(load(scope()), configurationId(id));
        if (label == null || !label.matches("[1-9][0-9]{0,8}")) {
            throw badRequest("Invalid configurationRevision");
        }
        int number = Integer.parseInt(label);
        if (number > current.revisions().size()) {
            throw notFound("Configuration revision not found");
        }
        return current.revisions().get(number - 1);
    }

    public synchronized Page<Configuration> list(Integer maxResults, String nextToken) {
        requireOpen();
        Scope scope = scope();
        int size = pageSize(maxResults);
        String markerScope = markerScope(scope, "configurations", "", size);
        String cursor = cursor(markerScope, nextToken);
        List<Configuration> rows = load(scope()).configurations().values().stream()
                .sorted(Comparator.comparing(Configuration::id))
                .filter(row -> cursor == null || row.id().compareTo(cursor) > 0).limit(size + 1L).toList();
        boolean more = rows.size() > size;
        List<Configuration> page = more ? rows.subList(0, size) : rows;
        return new Page<>(page, more ? markers.issue(markerScope, page.getLast().id()) : null);
    }

    public synchronized Page<Revision> listRevisions(String id, Integer maxResults, String nextToken) {
        requireOpen();
        configurationId(id);
        Scope scope = scope();
        Configuration current = existing(load(scope), id);
        int size = pageSize(maxResults);
        String markerScope = markerScope(scope, "revisions", id, size);
        String cursor = cursor(markerScope, nextToken);
        int after = cursor == null ? 0 : Integer.parseInt(cursor);
        List<Revision> rows = current.revisions().stream().filter(row -> row.revision() > after)
                .limit(size + 1L).toList();
        boolean more = rows.size() > size;
        List<Revision> page = more ? rows.subList(0, size) : rows;
        return new Page<>(page, more ? markers.issue(markerScope,
                Integer.toString(page.getLast().revision())) : null);
    }

    /** Uses explicit account keys, so IAM inspection cannot migrate a legacy row or publish a change. */
    public synchronized Map<String, String> tagsForAuthorization(String id) {
        requireOpen();
        Configuration current = load(scope()).configurations().get(configurationId(id));
        return current == null ? Map.of() : current.tags();
    }

    public static Map<String, String> requestTags(JsonNode tags) {
        if (tags == null || tags.isNull()) {
            return Map.of();
        }
        if (!tags.isObject()) {
            throw badRequest("tags must be an object");
        }
        Map<String, String> result = new LinkedHashMap<>();
        tags.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) {
                throw badRequest("Tag values must be strings");
            }
            result.put(entry.getKey(), entry.getValue().textValue());
        });
        validateTags(result);
        return Map.copyOf(result);
    }

    public static Map<String, List<String>> requestTagConditions(Map<String, String> tags) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        tags.forEach((key, value) -> conditions.put("aws:RequestTag/" + key, List.of(value)));
        if (!tags.isEmpty()) {
            conditions.put("aws:TagKeys", List.copyOf(tags.keySet()));
        }
        return Map.copyOf(conditions);
    }

    private ConfigurationState load(Scope scope) {
        try {
            ConfigurationState state = storage.getForAccount(scope.accountId(), scope.key())
                    .orElseGet(() -> new ConfigurationState(scope, Map.of(), Map.of()));
            validate(state, scope);
            return state;
        } catch (RuntimeException failure) {
            throw internal("Configuration storage cannot be read consistently");
        }
    }

    private void commit(ConfigurationState state) {
        validate(state, state.scope());
        try {
            storage.putAllAtomicallyForAccount(state.scope().accountId(), Map.of(state.scope().key(), state));
        } catch (RuntimeException failure) {
            throw internal("Configuration storage could not commit the mutation");
        }
    }

    private static void validate(ConfigurationState state, Scope scope) {
        if (!scope.equals(state.scope()) || state.configurations().size() > MAX_CONFIGURATIONS
                || state.retired().size() > MAX_RETIRED) {
            throw internal("Invalid configuration scope");
        }
        state.configurations().forEach((key, value) -> {
            if (value == null || !configurationId(key).equals(value.id())
                    || !configurationArn(scope, key).equals(value.arn()) || value.name() == null
                    || !NAME.matcher(value.name()).matches() || !ENGINE.equals(value.engineType())
                    || !AUTHENTICATION.equals(value.authenticationStrategy()) || value.engineVersion() == null
                    || value.engineVersion().isBlank() || bytes(value.engineVersion()) > 64 || value.created() == null
                    || value.revisions().isEmpty() || value.revisions().size() > MAX_REVISIONS
                    || !value.created().equals(value.revisions().getFirst().created())) {
                throw internal("Invalid configuration identity or metadata");
            }
            validateTags(value.tags());
            for (int index = 0; index < value.revisions().size(); index++) {
                Revision revision = value.revisions().get(index);
                if (revision.revision() != index + 1 || revision.created() == null
                        || revision.description() == null || bytes(revision.description()) > 1024
                        || (index == 0 ? revision.data() != null : revision.data() == null)) {
                    throw internal("Invalid stored configuration revision");
                }
                if (revision.data() != null) {
                    document(revision.data());
                }
            }
        });
        state.retired().forEach((key, retiredAt) -> {
            configurationId(key);
            if (retiredAt == null || state.configurations().containsKey(key)) {
                throw internal("Invalid retired configuration identity");
            }
        });
        if (documentBytes(state) > MAX_SCOPE_BYTES) {
            throw internal("Invalid configuration document capacity");
        }
    }

    private static long documentBytes(ConfigurationState state) {
        return state.configurations().values().stream().flatMap(value -> value.revisions().stream())
                .filter(revision -> revision.data() != null).mapToLong(revision -> decodedBytes(revision.data())).sum();
    }

    private static String document(String value) {
        if (value == null || value.length() > 4 * ((MAX_DOCUMENT_BYTES + 2) / 3) + 2) {
            throw badRequest("data must contain a base64 configuration document within the local limit");
        }
        String accepted = value.trim();
        if (accepted.isEmpty() || decodedBytes(accepted) > MAX_DOCUMENT_BYTES) {
            throw badRequest("data must contain a nonempty base64 configuration document within the local limit");
        }
        return accepted;
    }

    private static int decodedBytes(String value) {
        try {
            int length = Base64.getDecoder().decode(value).length;
            if (length == 0) {
                throw badRequest("Configuration data must not be empty");
            }
            return length;
        } catch (IllegalArgumentException failure) {
            throw badRequest("Configuration data is not valid base64");
        }
    }

    private static String description(String value) {
        String accepted = value == null ? "" : value;
        if (bytes(accepted) > 1024) {
            throw badRequest("description exceeds the local byte limit");
        }
        return accepted;
    }

    private static void validateTags(Map<String, String> tags) {
        if (tags == null || tags.size() > 50) {
            throw badRequest("At most 50 tags are supported");
        }
        tags.forEach((key, value) -> {
            if (key == null || key.isEmpty() || bytes(key) > 128 || value == null || bytes(value) > 256) {
                throw badRequest("Invalid tag key or value");
            }
        });
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Configuration existing(ConfigurationState state, String id) {
        Configuration result = state.configurations().get(id);
        if (result == null) {
            throw notFound("Configuration not found");
        }
        return result;
    }

    private static int pageSize(Integer requested) {
        if (requested != null && (requested < 5 || requested > 100)) {
            throw badRequest("maxResults must be between 5 and 100");
        }
        return requested == null ? 20 : requested;
    }

    private static String markerScope(Scope scope, String operation, String parent, int size) {
        return scope.partition() + "/" + scope.accountId() + "/" + scope.region() + "/" + operation + "/" + parent
                + "/" + size;
    }

    private String cursor(String scope, String marker) {
        if (marker == null) {
            return null;
        }
        if (marker.isEmpty() || marker.length() > MAX_MARKER_LENGTH) {
            throw badRequest("Invalid nextToken");
        }
        String cursor = markers.cursorOf(scope, marker);
        if (cursor == null) {
            throw badRequest("Invalid or expired nextToken");
        }
        return cursor;
    }

    private void requireOpen() {
        if (resetting) {
            throw internal("Configuration service is resetting");
        }
    }

    @Override
    public synchronized void beforeReset() {
        resetting = true;
    }

    @Override
    public synchronized void clear() {
        markers = new SignedMarkers(MAX_MARKER_LENGTH);
    }

    @Override
    public synchronized void afterReset() {
        resetting = false;
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("NotFoundException", message, 404);
    }

    private static AwsException limit(String message) {
        return new AwsException("ResourceLimitExceededException", message, 429);
    }

    private static AwsException internal(String message) {
        return new AwsException("InternalServerErrorException", message, 500);
    }
}
