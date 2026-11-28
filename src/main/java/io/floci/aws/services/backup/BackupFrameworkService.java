package io.floci.aws.services.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.Pagination;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.StoredResourceArns;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent definitions. Recording coverage and compliance evaluation are not inferred from a saved definition. */
@ApplicationScoped
public class BackupFrameworkService implements StoredResourceArns {

    private static final String INVALID = "InvalidParameterValueException";
    private static final int MAX_FRAMEWORKS = 15;
    private static final int MAX_CONTROLS = 50;
    private static final Set<String> CONTROL_NAMES = Set.of(
            "BACKUP_RESOURCES_PROTECTED_BY_BACKUP_PLAN",
            "BACKUP_PLAN_MIN_FREQUENCY_AND_MIN_RETENTION_CHECK",
            "BACKUP_RECOVERY_POINT_MANUAL_DELETION_DISABLED",
            "BACKUP_RECOVERY_POINT_MINIMUM_RETENTION_CHECK",
            "BACKUP_RECOVERY_POINT_ENCRYPTED",
            "BACKUP_RESOURCES_PROTECTED_BY_CROSS_REGION",
            "BACKUP_RESOURCES_PROTECTED_BY_CROSS_ACCOUNT",
            "BACKUP_RESOURCES_PROTECTED_BY_BACKUP_VAULT_LOCK",
            "BACKUP_LAST_RECOVERY_POINT_CREATED",
            "RESTORE_TIME_FOR_RESOURCES_MEET_TARGET",
            "RESOURCES_IN_LOGICALLY_AIR_GAPPED_VAULT");

    @RegisterForReflection
    public record Parameter(@JsonProperty("ParameterName") String name,
                            @JsonProperty("ParameterValue") String value) {
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Scope(@JsonProperty("ComplianceResourceIds") List<String> ids,
                        @JsonProperty("ComplianceResourceTypes") List<String> types,
                        @JsonProperty("Tags") Map<String, String> tags) {
        public Scope {
            ids = ids == null ? List.of() : List.copyOf(ids);
            types = types == null ? List.of() : List.copyOf(types);
            tags = tags == null ? Map.of() : Map.copyOf(tags);
        }
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Control(@JsonProperty("ControlName") String name,
                          @JsonProperty("ControlInputParameters") List<Parameter> parameters,
                          @JsonProperty("ControlScope") Scope scope) {
        public Control {
            parameters = List.copyOf(parameters);
        }
    }

    @RegisterForReflection
    public record Framework(String name, String arn, long createdMillis, String description,
                            List<Control> controls, Map<String, String> tags, String creatorToken) {
        public Framework {
            controls = List.copyOf(controls);
            tags = Map.copyOf(tags);
        }

        Framework withTags(Map<String, String> replacement) {
            return new Framework(name, arn, createdMillis, description, controls, replacement, creatorToken);
        }
    }

    @RegisterForReflection
    public record Replay(String fingerprint, Framework result) {
    }

    /** Definitions and replay receipts are replaced by the same account-aware storage write. */
    @RegisterForReflection
    public record Catalog(Map<String, Framework> frameworks, Map<String, Replay> replays) {
        public Catalog {
            frameworks = Map.copyOf(frameworks);
            replays = Map.copyOf(replays);
        }
    }

    private final StorageBackend<String, Catalog> store;
    private final RegionResolver regions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    @Inject
    public BackupFrameworkService(StorageFactory factory, RegionResolver regions, ObjectMapper mapper) {
        this(factory, regions, mapper, Clock.systemUTC());
    }

    BackupFrameworkService(StorageFactory factory, RegionResolver regions, ObjectMapper mapper, Clock clock) {
        this.store = factory.create("backup", "backup-framework-definitions.json", new TypeReference<>() {});
        this.regions = regions;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Framework create(String region, JsonNode request) {
        object(request);
        String name = name(text(request, "FrameworkName", true));
        String description = description(text(request, "FrameworkDescription", false));
        List<Control> controls = controls(request.get("FrameworkControls"), true);
        Map<String, String> tags = stringMap(request.get("FrameworkTags"), "FrameworkTags");
        String token = token(request);
        String fingerprint = fingerprint(name, description, controls, tags);
        synchronized (lock(region)) {
            Catalog catalog = catalog(region);
            Framework replay = replay(catalog, "CreateFramework:" + token, fingerprint);
            if (replay != null) {
                return replay;
            }
            if (catalog.frameworks().containsKey(name)) {
                throw exists("A framework named " + name + " already exists.");
            }
            if (catalog.frameworks().size() >= MAX_FRAMEWORKS) {
                throw limit("A maximum of 15 frameworks is allowed per account and Region.");
            }
            validateReplacement(catalog, name, controls);
            String arn = new AwsArnUtils.Arn(regions.partitionForRegion(region), "backup", region,
                    regions.getAccountId(), "framework:" + name + "-" + UUID.randomUUID()).toString();
            Framework framework = new Framework(name, arn, clock.millis(), description, controls, tags, token);
            save(region, catalog, framework, "CreateFramework:" + token, fingerprint);
            return framework;
        }
    }

    public Framework describe(String region, String frameworkName) {
        return require(catalog(region), name(frameworkName));
    }

    public Framework update(String region, String frameworkName, JsonNode request) {
        object(request);
        String name = name(frameworkName);
        String description = description(text(request, "FrameworkDescription", false));
        List<Control> controls = controls(request.get("FrameworkControls"), false);
        String token = token(request);
        String fingerprint = fingerprint(name, description, controls, Map.of());
        synchronized (lock(region)) {
            Catalog catalog = catalog(region);
            Framework replay = replay(catalog, "UpdateFramework:" + token, fingerprint);
            if (replay != null) {
                return replay;
            }
            Framework old = require(catalog, name);
            List<Control> replacement = controls == null ? old.controls() : controls;
            validateReplacement(catalog, name, replacement);
            Framework updated = new Framework(name, old.arn(), old.createdMillis(),
                    description == null ? old.description() : description, replacement, old.tags(), old.creatorToken());
            save(region, catalog, updated, "UpdateFramework:" + token, fingerprint);
            return updated;
        }
    }

    public void delete(String region, String frameworkName) {
        String name = name(frameworkName);
        synchronized (lock(region)) {
            Catalog catalog = catalog(region);
            require(catalog, name);
            Map<String, Framework> remaining = new HashMap<>(catalog.frameworks());
            remaining.remove(name);
            store.put(region, new Catalog(remaining, catalog.replays()));
        }
    }

    public PaginatedResult<Framework> list(String region, Integer maxResults, String nextToken) {
        if (maxResults != null && (maxResults < 1 || maxResults > 1000)) {
            throw invalid("MaxResults must be between 1 and 1000.");
        }
        // At most 15 summaries, with bounded names/descriptions and no controls, fit below the default 1 MB page.
        String namespace = "backup:ListFrameworks:" + regions.getAccountId() + ":" + region;
        return Pagination.paginate(new ArrayList<>(catalog(region).frameworks().values()), Framework::name,
                maxResults == null ? 1000 : maxResults, nextToken, namespace,
                ignored -> invalid("Invalid NextToken."));
    }

    public Map<String, String> listTags(String region, String arn) {
        return byArn(region, arn).tags();
    }

    public void tag(String region, String arn, Map<String, String> tags) {
        validateTags(tags, "Tags");
        synchronized (lock(region)) {
            Catalog catalog = catalog(region);
            Framework old = byArn(catalog, region, arn);
            Map<String, String> merged = new HashMap<>(old.tags());
            merged.putAll(tags);
            replaceTags(region, catalog, old.withTags(merged));
        }
    }

    public void untag(String region, String arn, List<String> keys) {
        if (keys == null || keys.stream().anyMatch(key -> key == null || key.isEmpty())) {
            throw invalid("TagKeyList must contain tag keys.");
        }
        synchronized (lock(region)) {
            Catalog catalog = catalog(region);
            Framework old = byArn(catalog, region, arn);
            Map<String, String> remaining = new HashMap<>(old.tags());
            keys.forEach(remaining::remove);
            replaceTags(region, catalog, old.withTags(remaining));
        }
    }

    public Map<String, List<String>> resourceConditions(String region, String nameOrArn) {
        Optional<Framework> framework = catalog(region).frameworks().values().stream()
                .filter(value -> value.name().equals(nameOrArn) || value.arn().equals(nameOrArn)).findFirst();
        Map<String, List<String>> result = new LinkedHashMap<>();
        framework.ifPresent(value -> value.tags().forEach((key, tag) ->
                result.put("aws:ResourceTag/" + key, List.of(tag))));
        return result;
    }

    @Override
    public String namespace() {
        return "backup";
    }

    @Override
    public Optional<String> find(String type, List<String> names, String region) {
        if (!"framework".equals(type) || names.size() != 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(catalog(region).frameworks().get(names.getFirst())).map(Framework::arn);
    }

    public static boolean isFrameworkArn(String arn) {
        return AwsArnUtils.resourceIfArnFor(arn, "backup")
                .filter(resource -> resource.startsWith("framework:")).isPresent();
    }

    private Catalog catalog(String region) {
        return store.get(region).orElseGet(() -> new Catalog(Map.of(), Map.of()));
    }

    private Object lock(String region) {
        return locks.computeIfAbsent(regions.getAccountId() + ":" + region, ignored -> new Object());
    }

    private Framework byArn(String region, String arn) {
        return byArn(catalog(region), region, arn);
    }

    private Framework byArn(Catalog catalog, String region, String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException exception) {
            throw invalid("Invalid framework ARN.");
        }
        if (!"backup".equals(parsed.service()) || !region.equals(parsed.region())
                || !regions.getAccountId().equals(parsed.accountId())
                || !regions.partitionForRegion(region).equals(parsed.partition())) {
            throw notFound("Framework not found.");
        }
        return catalog.frameworks().values().stream().filter(framework -> framework.arn().equals(arn))
                .findFirst().orElseThrow(() -> notFound("Framework not found."));
    }

    private void replaceTags(String region, Catalog catalog, Framework replacement) {
        Map<String, Framework> frameworks = new HashMap<>(catalog.frameworks());
        frameworks.put(replacement.name(), replacement);
        store.put(region, new Catalog(frameworks, catalog.replays()));
    }

    private void save(String region, Catalog catalog, Framework framework, String key, String fingerprint) {
        Map<String, Framework> frameworks = new HashMap<>(catalog.frameworks());
        Map<String, Replay> replays = new HashMap<>(catalog.replays());
        frameworks.put(framework.name(), framework);
        replays.put(key, new Replay(fingerprint, framework));
        store.put(region, new Catalog(frameworks, replays));
    }

    private static Framework replay(Catalog catalog, String key, String fingerprint) {
        Replay previous = catalog.replays().get(key);
        if (previous == null) {
            return null;
        }
        if (!previous.fingerprint().equals(fingerprint)) {
            throw invalid("The idempotency token was already used with different parameters.");
        }
        return previous.result();
    }

    private void validateReplacement(Catalog catalog, String name, List<Control> controls) {
        String canonical = canonicalControls(controls);
        int total = controls.size();
        for (Framework existing : catalog.frameworks().values()) {
            if (!existing.name().equals(name)) {
                if (canonicalControls(existing.controls()).equals(canonical)) {
                    throw exists("A framework with the same controls, parameters and scope already exists.");
                }
                total += existing.controls().size();
            }
        }
        if (total > MAX_CONTROLS) {
            throw limit("A maximum of 50 framework controls is allowed per account and Region.");
        }
    }

    private String fingerprint(String name, String description, List<Control> controls, Map<String, String> tags) {
        Map<String, Object> values = new TreeMap<>();
        values.put("name", name);
        values.put("description", description);
        values.put("controls", controls == null ? null : canonicalControls(controls));
        values.put("tags", new TreeMap<>(tags));
        return json(values);
    }

    private String canonicalControls(List<Control> controls) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Control control : controls.stream().sorted(Comparator.comparing(Control::name)).toList()) {
            Map<String, Object> entry = new TreeMap<>();
            entry.put("name", control.name());
            Map<String, String> parameters = new TreeMap<>();
            control.parameters().forEach(parameter -> parameters.put(parameter.name(), parameter.value()));
            entry.put("parameters", parameters);
            Scope scope = control.scope();
            entry.put("ids", scope == null ? List.of() : scope.ids().stream().sorted().toList());
            entry.put("types", scope == null ? List.of() : scope.types().stream().sorted().toList());
            entry.put("tags", scope == null ? Map.of() : new TreeMap<>(scope.tags()));
            result.add(entry);
        }
        return json(result);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot encode framework definition", exception);
        }
    }

    private static List<Control> controls(JsonNode node, boolean required) {
        if (node == null || node.isNull()) {
            if (required) {
                throw missing("FrameworkControls is required.");
            }
            return null;
        }
        if (!node.isArray() || node.isEmpty()) {
            throw invalid("FrameworkControls must contain at least one control.");
        }
        List<Control> controls = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonNode value : node) {
            object(value);
            String name = text(value, "ControlName", true);
            if (!CONTROL_NAMES.contains(name) || !names.add(name)) {
                throw invalid("ControlName must name a supported control and be unique within the framework.");
            }
            List<Parameter> parameters = new ArrayList<>();
            JsonNode input = value.get("ControlInputParameters");
            if (input != null && !input.isNull()) {
                if (!input.isArray()) {
                    throw invalid("ControlInputParameters must be a list.");
                }
                Set<String> keys = new HashSet<>();
                for (JsonNode parameter : input) {
                    object(parameter);
                    String key = text(parameter, "ParameterName", true);
                    String parameterValue = text(parameter, "ParameterValue", true);
                    if (key.isEmpty() || !keys.add(key)) {
                        throw invalid("Control parameter names must be nonempty and unique.");
                    }
                    parameters.add(new Parameter(key, parameterValue));
                }
            }
            validateParameters(name, parameters);
            Scope scope = scope(value.get("ControlScope"));
            validateScope(name, scope);
            controls.add(new Control(name, parameters, scope));
        }
        return List.copyOf(controls);
    }

    private static void validateScope(String control, Scope scope) {
        if (scope == null) {
            return;
        }
        if (Set.of("BACKUP_RECOVERY_POINT_ENCRYPTED", "BACKUP_RECOVERY_POINT_MINIMUM_RETENTION_CHECK")
                .contains(control) && (!scope.ids().isEmpty() || !scope.types().isEmpty())) {
            throw invalid("This control supports an empty scope or a tag scope.");
        }
        if ("RESOURCES_IN_LOGICALLY_AIR_GAPPED_VAULT".equals(control) && !scope.tags().isEmpty()) {
            throw invalid("The logically air-gapped vault control does not support tag scope.");
        }
        if (Set.of("BACKUP_PLAN_MIN_FREQUENCY_AND_MIN_RETENTION_CHECK",
                "BACKUP_RECOVERY_POINT_MANUAL_DELETION_DISABLED").contains(control)
                && !scope.types().isEmpty() && scope.ids().isEmpty()) {
            throw invalid("This control supports a single-resource scope or a tag scope.");
        }
    }

    private static void validateParameters(String control, List<Parameter> parameters) {
        if (parameters.isEmpty()) {
            return;
        }
        Set<String> allowed = switch (control) {
            case "BACKUP_PLAN_MIN_FREQUENCY_AND_MIN_RETENTION_CHECK" ->
                    Set.of("requiredRetentionDays", "requiredFrequencyUnit", "requiredFrequencyValue");
            case "BACKUP_RECOVERY_POINT_MANUAL_DELETION_DISABLED" -> Set.of("principalArnList");
            case "BACKUP_RECOVERY_POINT_MINIMUM_RETENTION_CHECK" -> Set.of("requiredRetentionDays");
            case "RESTORE_TIME_FOR_RESOURCES_MEET_TARGET" -> Set.of("maxRestoreTime");
            case "RESOURCES_IN_LOGICALLY_AIR_GAPPED_VAULT" ->
                    Set.of("recoveryPointAgeValue", "recoveryPointAgeUnit");
            default -> Set.of();
        };
        Map<String, String> values = new HashMap<>();
        for (Parameter parameter : parameters) {
            if (!allowed.contains(parameter.name())) {
                throw invalid("Control parameter " + parameter.name() + " is not supported for " + control + ".");
            }
            values.put(parameter.name(), parameter.value());
        }
        for (String numeric : List.of("requiredRetentionDays", "maxRestoreTime")) {
            if (values.containsKey(numeric)) {
                positive(values.get(numeric), numeric);
            }
        }
        timePair(values, "requiredFrequencyUnit", "requiredFrequencyValue", 1, 24, 1, 31);
        timePair(values, "recoveryPointAgeUnit", "recoveryPointAgeValue", 24, 2184, 1, 91);
        if (values.containsKey("principalArnList")) {
            String[] principals = values.get("principalArnList").split(",", -1);
            if (principals.length > 5) {
                throw invalid("principalArnList supports at most five role ARNs.");
            }
            for (String principal : principals) {
                if (AwsArnUtils.resourceIfArnFor(principal.trim(), "iam")
                        .filter(resource -> resource.startsWith("role/") && resource.length() > 5).isEmpty()) {
                    throw invalid("principalArnList must contain IAM role ARNs.");
                }
            }
        }
    }

    private static void timePair(Map<String, String> values, String unitName, String valueName,
                                  long minimumHours, long maximumHours, long minimumDays, long maximumDays) {
        String unit = values.get(unitName);
        String value = values.get(valueName);
        if (unit == null && value == null) {
            return;
        }
        if (unit == null || value == null || !Set.of("hours", "days").contains(unit)) {
            throw invalid(unitName + " and " + valueName + " must specify hours or days together.");
        }
        long number = positive(value, valueName);
        long minimum = "hours".equals(unit) ? minimumHours : minimumDays;
        long maximum = "hours".equals(unit) ? maximumHours : maximumDays;
        if (number < minimum || number > maximum) {
            throw invalid(valueName + " is outside the range for " + unit + ".");
        }
    }

    private static long positive(String value, String member) {
        try {
            if (!value.matches("[0-9]+") || Long.parseLong(value) < 1) {
                throw invalid(member + " must be a positive integer.");
            }
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalid(member + " must be a positive integer.");
        }
    }

    private static Scope scope(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        object(node);
        List<String> ids = strings(node.get("ComplianceResourceIds"), "ComplianceResourceIds");
        List<String> types = strings(node.get("ComplianceResourceTypes"), "ComplianceResourceTypes");
        Map<String, String> tags = stringMap(node.get("Tags"), "ControlScope.Tags");
        if (ids.size() > 100 || tags.size() > 1 || !ids.isEmpty() && types.size() != 1
                || !tags.isEmpty() && (!ids.isEmpty() || !types.isEmpty())) {
            throw invalid("ControlScope must select resource types, one tag, or one resource type with resource IDs.");
        }
        return new Scope(ids, types, tags);
    }

    static List<String> strings(JsonNode node, String member) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw invalid(member + " must be a list of strings.");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isEmpty() || result.contains(value.asText())) {
                throw invalid(member + " must contain distinct nonempty strings.");
            }
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    static Map<String, String> stringMap(JsonNode node, String member) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw invalid(member + " must be a string map.");
        }
        Map<String, String> result = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) {
                throw invalid(member + " must contain string values.");
            }
            result.put(entry.getKey(), entry.getValue().asText());
        });
        validateTags(result, member);
        return Map.copyOf(result);
    }

    private static void validateTags(Map<String, String> tags, String member) {
        if (tags == null || tags.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isEmpty() || entry.getValue() == null)) {
            throw invalid(member + " must contain nonempty keys and string values.");
        }
    }

    private static String name(String name) {
        if (name == null) {
            throw missing("FrameworkName is required.");
        }
        if (name.length() > 256 || !name.matches("[a-zA-Z][_a-zA-Z0-9]*")) {
            throw invalid("FrameworkName must start with a letter and contain 1 to 256 letters, digits or underscores.");
        }
        return name;
    }

    private static String description(String value) {
        if (value != null && (value.length() > 1024 || value.isBlank())) {
            throw invalid("FrameworkDescription must contain non-whitespace text and at most 1024 characters.");
        }
        return value;
    }

    private static String token(JsonNode request) {
        String token = text(request, "IdempotencyToken", false);
        return token == null ? UUID.randomUUID().toString() : token;
    }

    private static String text(JsonNode node, String member, boolean required) {
        JsonNode value = node.get(member);
        if (value == null || value.isNull()) {
            if (required) {
                throw missing(member + " is required.");
            }
            return null;
        }
        if (!value.isTextual()) {
            throw invalid(member + " must be a string.");
        }
        return value.asText();
    }

    private static void object(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid("Expected a JSON object.");
        }
    }

    private static Framework require(Catalog catalog, String name) {
        Framework framework = catalog.frameworks().get(name);
        if (framework == null) {
            throw notFound("Framework " + name + " not found.");
        }
        return framework;
    }

    private static AwsException invalid(String message) {
        return new AwsException(INVALID, message, 400);
    }

    private static AwsException missing(String message) {
        return new AwsException("MissingParameterValueException", message, 400);
    }

    private static AwsException exists(String message) {
        return new AwsException("AlreadyExistsException", message, 400);
    }

    private static AwsException limit(String message) {
        return new AwsException("LimitExceededException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }
}
