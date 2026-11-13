package io.floci.aws.services.memorydb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.Resettable;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.memorydb.model.MemoryDbParameterGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Standalone parameter groups; the contextual monitor protects every snapshot and publication. */
@ApplicationScoped
public class MemoryDbParameterGroupService implements Resettable {
    private static final Pattern NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9-]{0,254}");
    private static final String GROUP_PREFIX = "parametergroup/";
    private final AccountAwareStorageBackend<MemoryDbParameterGroup> groups;
    private final MemoryDbParameterCatalog catalog;
    private final RegionResolver regions;
    private final ObjectMapper mapper;
    private byte[] tokenSecret = newSecret();
    private boolean resetting;

    @Inject
    public MemoryDbParameterGroupService(StorageFactory storage, MemoryDbParameterCatalog catalog,
                                        RegionResolver regions, ObjectMapper mapper) {
        this.groups = storage.create("memorydb", "memorydb-parameter-groups.json",
                new TypeReference<Map<String, MemoryDbParameterGroup>>() {});
        this.catalog = catalog;
        this.regions = regions;
        this.mapper = mapper;
    }

    public synchronized MemoryDbParameterGroup create(String name, String family, String description,
                                                       Map<String, String> tags, String region) {
        admit();
        Scope scope = scope(region);
        String canonical = name(name, true);
        String selectedFamily = family == null ? "" : family.toLowerCase(Locale.ROOT);
        if (!catalog.hasFamily(selectedFamily)) {
            throw invalid("Unknown parameter group family.");
        }
        String selectedDescription = description == null ? " " : description;
        if (selectedDescription.codePointCount(0, selectedDescription.length()) > 255) {
            throw invalid("Description exceeds the supported 255 character limit.");
        }
        Map<String, String> selectedTags = tags(tags);
        if (groups.getForAccount(scope.account(), key(scope, canonical)).isPresent()) {
            throw new AwsException("ParameterGroupAlreadyExistsFault", "Parameter group already exists.", 400);
        }
        MemoryDbParameterGroup created = new MemoryDbParameterGroup(scope.account(), region, scope.partition(),
                canonical, MemoryDbParameterGroupTarget.arn(scope.partition(), region, scope.account(), canonical),
                selectedFamily, selectedDescription, UUID.randomUUID().toString(), 0, catalog.version(),
                Map.of(), selectedTags);
        publish(scope, created);
        return created;
    }

    public synchronized MemoryDbParameterGroup delete(String name, String region) {
        admit();
        Scope scope = scope(region);
        MemoryDbParameterGroup current = required(scope, name(name, false));
        mutable(current);
        groups.deleteAllAtomicallyForAccounts(Map.of(scope.account(), Set.of(key(scope, current.name()))));
        return current;
    }

    public synchronized Page<MemoryDbParameterGroup> describe(String name, Integer maxResults, String nextToken,
                                                              String region) {
        admit();
        Scope scope = scope(region);
        int size = pageSize(maxResults);
        String selected = name == null ? "" : name(name, false);
        List<MemoryDbParameterGroup> snapshot;
        if (!selected.isEmpty()) {
            snapshot = List.of(required(scope, selected));
        } else {
            Map<String, MemoryDbParameterGroup> values = new TreeMap<>();
            catalog.defaults().keySet().forEach(group -> values.put(group, required(scope, group)));
            for (MemoryDbParameterGroup group : groups.scanForAccount(scope.account(),
                    relative -> relative.startsWith(region + ":"))) {
                if (owned(scope, group) && !catalog.defaults().containsKey(group.name())
                        && groups.getForAccount(scope.account(), key(scope, group.name())).filter(group::equals).isPresent()) {
                    validateStored(group);
                    values.put(group.name(), group);
                }
            }
            snapshot = List.copyOf(values.values());
        }
        String fingerprint = fingerprint("groups", scope, selected, size, snapshot.stream()
                .map(group -> List.of(group.name(), group.generation(), Long.toString(group.revision()),
                        group.catalogVersion())).toList());
        return page(snapshot, MemoryDbParameterGroup::name, size, nextToken, fingerprint);
    }

    public synchronized Page<Map<String, String>> parameters(String name, Integer maxResults, String nextToken,
                                                              String region) {
        admit();
        Scope scope = scope(region);
        int size = pageSize(maxResults);
        MemoryDbParameterGroup current = required(scope, name(name, false));
        Map<String, String> values = new TreeMap<>(current.overrides());
        MemoryDbParameterCatalog.DefaultGroup defaults = catalog.defaults().get(current.name());
        if (defaults != null) {
            values.putAll(defaults.overrides());
        }
        List<Map<String, String>> snapshot = catalog.parameters(current.family(), values);
        String fingerprint = fingerprint("parameters", scope, current.name(), size,
                List.of(current.generation(), Long.toString(current.revision()), current.catalogVersion()));
        return page(snapshot, row -> row.get("Name"), size, nextToken, fingerprint);
    }

    public synchronized MemoryDbParameterGroup update(String name, List<ParameterValue> updates, String region) {
        admit();
        Scope scope = scope(region);
        MemoryDbParameterGroup current = required(scope, name(name, false));
        mutable(current);
        if (updates == null || updates.isEmpty() || updates.size() > 20) {
            throw invalid("ParameterNameValues must contain between 1 and 20 entries.");
        }
        Map<String, String> next = new TreeMap<>(current.overrides());
        Set<String> seen = new HashSet<>();
        for (ParameterValue update : updates) {
            if (update == null || update.name() == null || !seen.add(update.name())) {
                throw invalid("Parameter names must be present and unique.");
            }
            MemoryDbParameterCatalog.Definition parameter = catalog.parameter(current.family(), update.name());
            if (!parameter.modifiable()) {
                throw invalid("Parameter " + update.name() + " is read-only.");
            }
            parameter.validate(update.value());
            next.put(update.name(), update.value());
        }
        MemoryDbParameterGroup replacement = current.replace(next, current.tags());
        publish(scope, replacement);
        return replacement;
    }

    public synchronized MemoryDbParameterGroup reset(String name, Boolean all, List<String> names, String region) {
        admit();
        Scope scope = scope(region);
        MemoryDbParameterGroup current = required(scope, name(name, false));
        mutable(current);
        if (Boolean.TRUE.equals(all) ? names != null : names == null || names.isEmpty()) {
            throw new AwsException("InvalidParameterCombinationException",
                    "Use AllParameters=true without ParameterNames, or provide ParameterNames.", 400);
        }
        Map<String, String> next = new TreeMap<>(current.overrides());
        if (Boolean.TRUE.equals(all)) {
            next.clear();
        } else {
            Set<String> seen = new HashSet<>();
            for (String parameterName : names) {
                if (parameterName == null || !seen.add(parameterName)) {
                    throw invalid("Parameter names must be present and unique.");
                }
                MemoryDbParameterCatalog.Definition parameter = catalog.parameter(current.family(), parameterName);
                if (!parameter.modifiable()) {
                    throw invalid("Parameter " + parameterName + " is read-only.");
                }
                next.remove(parameterName);
            }
        }
        MemoryDbParameterGroup replacement = current.replace(next, current.tags());
        publish(scope, replacement);
        return replacement;
    }

    public synchronized Map<String, String> listTags(String arn, String region) {
        admit();
        Scope scope = scope(region);
        return required(scope, arnName(arn, scope)).tags();
    }

    public synchronized Map<String, String> tag(String arn, Map<String, String> added, String region) {
        admit();
        Scope scope = scope(region);
        MemoryDbParameterGroup current = required(scope, arnName(arn, scope));
        Map<String, String> next = new TreeMap<>(current.tags());
        next.putAll(tags(added));
        MemoryDbParameterGroup replacement = current.replace(current.overrides(), tags(next));
        publish(scope, replacement);
        return replacement.tags();
    }

    public synchronized Map<String, String> untag(String arn, List<String> removed, String region) {
        admit();
        Scope scope = scope(region);
        MemoryDbParameterGroup current = required(scope, arnName(arn, scope));
        if (removed == null) {
            throw invalid("TagKeys is required.");
        }
        Map<String, String> next = new TreeMap<>(current.tags());
        Map<String, String> removedTags = new TreeMap<>();
        for (String tagKey : removed) {
            tagKey(tagKey);
            String value = next.remove(tagKey);
            if (value != null) {
                removedTags.put(tagKey, value);
            }
        }
        MemoryDbParameterGroup replacement = current.replace(current.overrides(), next);
        publish(scope, replacement);
        return Map.copyOf(removedTags);
    }

    /** Read-only lookup for the already resolved caller; it never migrates or materializes a default. */
    public synchronized Map<String, String> iamTags(String arn, String partition, String region, String account) {
        admit();
        Scope scope = new Scope(partition, region, account);
        String selected;
        try {
            selected = arnName(arn, scope);
        } catch (AwsException invalidTarget) {
            return Map.of();
        }
        MemoryDbParameterGroup stored = groups.getForAccount(account, key(scope, selected)).orElse(null);
        if (stored == null || !owned(scope, stored) || !selected.equals(stored.name())) {
            return Map.of();
        }
        validateStored(stored);
        return stored.tags();
    }

    @Override
    public synchronized void beforeReset() {
        resetting = true;
    }

    @Override
    public synchronized void clear() {
        tokenSecret = newSecret();
    }

    @Override
    public synchronized void afterReset() {
        resetting = false;
    }

    private void admit() {
        if (resetting) {
            throw new AwsException("ServiceUnavailable", "MemoryDB parameter groups are resetting.", 503);
        }
    }

    private MemoryDbParameterGroup required(Scope scope, String name) {
        MemoryDbParameterGroup stored = groups.getForAccount(scope.account(), key(scope, name)).orElse(null);
        if (stored != null) {
            if (!owned(scope, stored) || !name.equals(stored.name())) {
                throw missing();
            }
            validateStored(stored);
            return stored;
        }
        MemoryDbParameterCatalog.DefaultGroup defaults = catalog.defaults().get(name);
        if (defaults != null) {
            return new MemoryDbParameterGroup(scope.account(), scope.region(), scope.partition(), name,
                    MemoryDbParameterGroupTarget.arn(scope.partition(), scope.region(), scope.account(), name),
                    defaults.family(), "Default parameter group for " + defaults.family(),
                    "default:" + catalog.version() + ":" + name, 0, catalog.version(), Map.of(), Map.of());
        }
        throw missing();
    }

    private void validateStored(MemoryDbParameterGroup group) {
        if (!catalog.version().equals(group.catalogVersion()) || !catalog.hasFamily(group.family())
                || group.generation() == null || group.revision() < 0) {
            throw new AwsException("InvalidParameterGroupStateFault", "Unsupported stored parameter group catalog or generation.", 400);
        }
        MemoryDbParameterCatalog.DefaultGroup defaults = catalog.defaults().get(group.name());
        if (defaults != null && (!defaults.family().equals(group.family()) || !group.overrides().isEmpty()
                || !("Default parameter group for " + defaults.family()).equals(group.description())
                || !("default:" + catalog.version() + ":" + group.name()).equals(group.generation()))) {
            throw new AwsException("InvalidParameterGroupStateFault", "Invalid default parameter group overlay.", 400);
        }
        group.overrides().forEach((name, value) -> catalog.parameter(group.family(), name).validate(value));
    }

    private static boolean owned(Scope scope, MemoryDbParameterGroup group) {
        return group != null && scope.account().equals(group.accountId()) && scope.region().equals(group.region())
                && scope.partition().equals(group.partition()) && group.name() != null
                && MemoryDbParameterGroupTarget.arn(scope.partition(), scope.region(), scope.account(), group.name())
                .equals(group.arn());
    }

    private void mutable(MemoryDbParameterGroup group) {
        if (catalog.defaults().containsKey(group.name())) {
            throw new AwsException("InvalidParameterGroupStateFault", "Default parameter groups cannot be changed or deleted.", 400);
        }
    }

    private void publish(Scope scope, MemoryDbParameterGroup group) {
        groups.putAllAtomicallyForAccount(scope.account(), Map.of(key(scope, group.name()), group));
    }

    private Scope scope(String region) {
        return new Scope(regions.partitionForRegion(region), region, regions.getAccountId());
    }

    private static String key(Scope scope, String name) {
        return scope.region() + ":" + name;
    }

    private String name(String supplied, boolean create) {
        if (supplied == null) {
            throw invalid("ParameterGroupName is required.");
        }
        String canonical = supplied.toLowerCase(Locale.ROOT);
        if (!create && catalog.defaults().containsKey(canonical)) {
            return canonical;
        }
        if (!NAME.matcher(supplied).matches() || supplied.contains("--") || supplied.endsWith("-")) {
            throw invalid("ParameterGroupName must be 1-255 ASCII letters, digits or hyphens, begin with a letter, "
                    + "and contain neither consecutive hyphens nor a trailing hyphen.");
        }
        return canonical;
    }

    private String arnName(String value, Scope scope) {
        try {
            if (!AwsArnUtils.isArnFor(value, "memorydb")) {
                throw invalidArn();
            }
            AwsArnUtils.Arn arn = AwsArnUtils.parse(value);
            if (!arn.resource().startsWith(GROUP_PREFIX)) {
                throw invalidArn();
            }
            if (!arn.partition().equals(scope.partition()) || !arn.region().equals(scope.region())
                    || !arn.accountId().equals(scope.account())) {
                throw missing();
            }
            String selected;
            try {
                selected = name(arn.resource().substring(GROUP_PREFIX.length()), false);
            } catch (AwsException invalidName) {
                throw invalidArn();
            }
            if (!MemoryDbParameterGroupTarget.arn(scope.partition(), scope.region(), scope.account(), selected).equals(value)) {
                throw invalidArn();
            }
            return selected;
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw invalidArn();
        }
    }

    private static Map<String, String> tags(Map<String, String> input) {
        Map<String, String> result = new TreeMap<>();
        if (input != null) {
            input.forEach((key, value) -> {
                tagKey(key);
                String normalized = value == null ? "" : value;
                if (normalized.codePointCount(0, normalized.length()) > 256) {
                    throw invalid("Tag values must have at most 256 characters.");
                }
                result.put(key, normalized);
            });
        }
        if (result.size() > 50) {
            throw new AwsException("TagQuotaPerResourceExceeded", "A parameter group supports at most 50 customer tags.", 400);
        }
        return Map.copyOf(result);
    }

    private static void tagKey(String key) {
        if (key == null || key.isEmpty() || key.codePointCount(0, key.length()) > 128
                || key.toLowerCase(Locale.ROOT).startsWith("aws:")) {
            throw invalid("Tag keys must contain 1-128 characters and cannot start with aws:.");
        }
    }

    private static int pageSize(Integer selected) {
        int result = selected == null ? 100 : selected;
        if (result < 20 || result > 100) {
            throw invalid("MaxResults must be between 20 and 100.");
        }
        return result;
    }

    private String fingerprint(String operation, Scope scope, String selected, int size, Object revision) {
        try {
            byte[] payload = mapper.writeValueAsBytes(List.of(operation, scope.partition(), scope.region(),
                    scope.account(), selected, size, catalog.version(), revision));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (IOException | GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot fingerprint parameter group snapshot", failure);
        }
    }

    private <T> Page<T> page(List<T> snapshot, Function<T, String> name, int size, String token, String fingerprint) {
        String after = token == null ? null : decode(token, fingerprint);
        int start = 0;
        if (after != null) {
            while (start < snapshot.size() && !name.apply(snapshot.get(start)).equals(after)) {
                start++;
            }
            if (start == snapshot.size()) {
                throw invalid("NextToken does not name this snapshot.");
            }
            start++;
        }
        int end = Math.min(start + size, snapshot.size());
        String next = end < snapshot.size() ? encode(fingerprint, name.apply(snapshot.get(end - 1))) : null;
        return new Page<>(List.copyOf(snapshot.subList(start, end)), next);
    }

    private String encode(String fingerprint, String after) {
        try {
            byte[] payload = mapper.writeValueAsBytes(Map.of("fingerprint", fingerprint, "after", after));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode parameter group token", failure);
        }
    }

    private String decode(String token, String fingerprint) {
        try {
            if (token.length() > 4096) {
                throw invalid("NextToken is invalid.");
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 2) {
                throw invalid("NextToken is invalid.");
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
            byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(signature, sign(payload))) {
                throw invalid("NextToken is invalid.");
            }
            JsonNode decoded = mapper.readTree(payload);
            if (!decoded.isObject() || decoded.size() != 2 || !decoded.path("fingerprint").isTextual()
                    || !decoded.path("after").isTextual() || !fingerprint.equals(decoded.path("fingerprint").textValue())) {
                throw invalid("NextToken belongs to a different or changed snapshot.");
            }
            return decoded.path("after").textValue();
        } catch (IOException | IllegalArgumentException failure) {
            throw invalid("NextToken is invalid.");
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenSecret, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot authenticate parameter group token", failure);
        }
    }

    private static byte[] newSecret() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    private static AwsException missing() {
        return new AwsException("ParameterGroupNotFoundFault", "Parameter group not found.", 400);
    }

    private static AwsException invalidArn() {
        return new AwsException("InvalidARNFault", "ResourceArn must be an exact MemoryDB parameter group ARN.", 400);
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValueException", message, 400);
    }

    private record Scope(String partition, String region, String account) {}

    public record ParameterValue(String name, String value) {}

    public record Page<T>(List<T> values, String nextToken) {}
}
