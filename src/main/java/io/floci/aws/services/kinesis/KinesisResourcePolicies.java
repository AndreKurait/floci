package io.floci.aws.services.kinesis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.services.kinesis.model.KinesisConsumer;
import io.floci.aws.services.kinesis.model.KinesisStream;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Configuration storage only: these documents do not authorize Kinesis data-plane requests. */
final class KinesisResourcePolicies {
    private static final Pattern RESOURCE = Pattern.compile(
            "stream/([A-Za-z0-9_.-]{1,128})(/consumer/[A-Za-z0-9_.-]{1,128}:[0-9]+)?");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final StorageBackend<String, KinesisStream> streams;
    private final StorageBackend<String, KinesisConsumer> consumers;
    private final RegionResolver regions;
    private final Function<String, Object> locks;

    KinesisResourcePolicies(StorageBackend<String, KinesisStream> streams,
                            StorageBackend<String, KinesisConsumer> consumers,
                            RegionResolver regions, Function<String, Object> locks) {
        this.streams = streams;
        this.consumers = consumers;
        this.regions = regions;
        this.locks = locks;
    }

    String get(String resourceArn, String region) {
        Target target = target(resourceArn, region);
        synchronized (locks.apply(target.key())) {
            return resolve(target, true, true).stream().getResourcePolicies().getOrDefault(resourceArn, "{}");
        }
    }

    void put(String resourceArn, String policy, String region) {
        validatePolicy(policy);
        Target target = target(resourceArn, region);
        synchronized (locks.apply(target.key())) {
            KinesisStream stream = resolve(target, true, true).stream();
            Map<String, String> policies = new HashMap<>(stream.getResourcePolicies());
            policies.put(resourceArn, policy);
            commit(target, stream.withResourcePolicies(policies));
        }
    }

    void delete(String resourceArn, String region) {
        Target target = target(resourceArn, region);
        synchronized (locks.apply(target.key())) {
            KinesisStream stream = resolve(target, true, true).stream();
            if (!stream.getResourcePolicies().containsKey(resourceArn)) {
                throw new AwsException("ResourceNotFoundException",
                        "No resource policy found for resource ARN " + resourceArn + ".", 400);
            }
            clear(target, stream);
        }
    }

    Map<String, String> tags(String resourceArn, String region) {
        Target target = target(resourceArn, region);
        synchronized (locks.apply(target.key())) {
            Resolved resolved = resolve(target, false, false);
            Map<String, String> tags = resolved.consumer() == null ? resolved.stream().getTags() : resolved.consumer().getTags();
            return tags == null ? Map.of() : Map.copyOf(tags);
        }
    }

    String consumerKey(String resourceArn, String region) {
        Target target = target(resourceArn, region);
        if (!target.consumer()) {
            throw invalid("ConsumerARN must name a consumer");
        }
        return target.key();
    }

    /** Called under consumerKey's monitor; reject a child retained from an older parent generation. */
    KinesisConsumer consumerForTags(String resourceArn, String region) {
        return resolve(target(resourceArn, region), true, false).consumer();
    }

    /** Called under the parent's lock before publishing or deleting a separate consumer record. */
    String clearConsumerPolicy(String consumerArn, String region) {
        Target target = target(consumerArn, region);
        Optional<KinesisStream> parent = parent(target, false);
        if (parent.isEmpty()) {
            return null;
        }
        KinesisStream stream = parent.get();
        if (stream.getResourcePolicies().containsKey(consumerArn)) {
            // A successful parent write followed by a failed child write intentionally leaves no policy.
            // The two existing backends do not provide a cross-store transaction.
            stream = parent(target, true).orElseThrow(() -> missing(target));
            clear(target, stream);
        }
        return generation(stream);
    }

    private void clear(Target target, KinesisStream stream) {
        Map<String, String> policies = new HashMap<>(stream.getResourcePolicies());
        policies.remove(target.arn());
        commit(target, stream.withResourcePolicies(policies));
    }

    private void commit(Target target, KinesisStream replacement) {
        if (streams instanceof AccountAwareStorageBackend<KinesisStream> scoped) {
            scoped.putAllAtomicallyForAccount(target.account(), Map.of(target.key(), replacement));
        } else {
            streams.putAllAtomically(Map.of(target.key(), replacement));
        }
    }

    private Resolved resolve(Target target, boolean migrate, boolean checkState) {
        KinesisStream stream = parent(target, migrate).orElseThrow(() -> missing(target));
        if (checkState && !"ACTIVE".equals(stream.getStreamStatus()) && !"UPDATING".equals(stream.getStreamStatus())) {
            throw new AwsException("ResourceInUseException", "Stream " + stream.getStreamName() + " under account "
                    + target.account() + " not ACTIVE or UPDATING, instead in state " + stream.getStreamStatus(), 400);
        }
        if (!target.consumer()) {
            return new Resolved(stream, null);
        }
        KinesisConsumer consumer = read(consumers, target.account(), target.region() + "::" + target.arn(),
                value -> target.arn().equals(value.getConsumerArn())
                        && target.parentArn().equals(value.getStreamArn()), migrate)
                .orElseThrow(() -> missing(target));
        String recordedGeneration = consumer.getPolicyStreamGeneration();
        boolean legacy = recordedGeneration == null && stream.getResourcePolicyGeneration() == null
                && consumer.getConsumerCreationTimestamp() != null && stream.getStreamCreationTimestamp() != null
                && !consumer.getConsumerCreationTimestamp().isBefore(stream.getStreamCreationTimestamp());
        if (!legacy && !Objects.equals(generation(stream), recordedGeneration)) {
            throw missing(target);
        }
        if (checkState && !"ACTIVE".equals(consumer.getConsumerStatus())) {
            throw new AwsException("ResourceInUseException", "Consumer is not ACTIVE", 400);
        }
        return new Resolved(stream, consumer);
    }

    private Optional<KinesisStream> parent(Target target, boolean migrate) {
        return read(streams, target.account(), target.key(), value -> target.parentArn().equals(value.getStreamArn())
                && (value.getAccountId() == null || target.account().equals(value.getAccountId())), migrate);
    }

    private static <T> Optional<T> read(StorageBackend<String, T> store, String account, String key,
                                       Predicate<T> owner, boolean migrate) {
        if (store instanceof AccountAwareStorageBackend<T> scoped) {
            if (migrate) {
                return scoped.getForAccountMigratingLegacy(account, key, owner).filter(owner);
            }
            return scoped.getForAccount(account, key).filter(owner)
                    .or(() -> scoped.getLegacyForAccount(account, key).filter(owner));
        }
        return store.get(key).filter(owner);
    }

    private static String generation(KinesisStream stream) {
        return stream.getResourcePolicyGeneration() == null
                ? "legacy:" + stream.getStreamCreationTimestamp() : stream.getResourcePolicyGeneration();
    }

    private Target target(String resourceArn, String region) {
        if (resourceArn == null || resourceArn.isEmpty() || resourceArn.length() > 2048) {
            throw invalid("ResourceARN must name a stream or consumer");
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException expected) {
            throw invalid("ResourceARN must be a valid ARN");
        }
        Matcher match = RESOURCE.matcher(arn.resource());
        if (!"kinesis".equals(arn.service()) || !arn.accountId().matches("[0-9]{12}") || !match.matches()) {
            throw invalid("ResourceARN must name a stream or consumer");
        }
        String account = regions.getAccountId();
        String parent = regions.buildArn("kinesis", region, account, "stream/" + match.group(1));
        Target target = new Target(resourceArn, parent, region, account, region + "::" + match.group(1),
                match.group(2) != null);
        if (!account.equals(arn.accountId()) || !region.equals(arn.region())
                || !regions.partitionForRegion(region).equals(arn.partition())) {
            throw missing(target);
        }
        return target;
    }

    static String requiredString(JsonNode body, String name) {
        JsonNode value = body.path(name);
        if (!value.isTextual() || value.textValue().isEmpty()) {
            throw invalid(name + " must be a nonempty string");
        }
        return value.textValue();
    }

    static Map<String, String> consumerTags(JsonNode body) {
        JsonNode tags = body.path("Tags");
        if (tags.isMissingNode()) {
            return Map.of();
        }
        if (!tags.isObject()) {
            throw invalid("Tags must be a map containing at most 50 tags");
        }
        Map<String, String> result = new HashMap<>();
        tags.properties().forEach(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            if (key.isEmpty() || key.codePointCount(0, key.length()) > 128 || !value.isTextual()
                    || value.textValue().codePointCount(0, value.textValue().length()) > 256) {
                throw invalid("Invalid consumer tag");
            }
            result.put(key, value.textValue());
        });
        return result;
    }

    private static void validatePolicy(String policy) {
        if (policy == null || policy.isEmpty()) {
            throw invalid("Policy must be a nonempty string");
        }
        JsonNode document;
        try {
            document = JSON.readTree(policy);
        } catch (JsonProcessingException expected) {
            throw invalid("Policy validation error: This policy contains invalid Json");
        }
        if (document == null || !document.isObject()) {
            throw invalid("Policy must be a JSON object");
        }
        JsonNode statements = document.path("Statement");
        if (statements.isObject()) {
            validateStatement(statements);
        } else if (statements.isArray() && !statements.isEmpty()) {
            statements.forEach(KinesisResourcePolicies::validateStatement);
        } else {
            throw invalid("Policy must contain a Statement");
        }
    }

    private static void validateStatement(JsonNode statement) {
        if (!statement.isObject() || !("Allow".equals(statement.path("Effect").asText())
                || "Deny".equals(statement.path("Effect").asText()))) {
            throw invalid("Policy statement Effect must be Allow or Deny");
        }
        JsonNode principal = exclusive(statement, "Principal", "NotPrincipal");
        if (principal.isObject()) {
            if (principal.isEmpty() || !principal.properties().stream().allMatch(entry -> strings(entry.getValue()))) {
                throw invalid("Policy statement must name a principal");
            }
        } else if (!strings(principal)) {
            throw invalid("Policy statement must name a principal");
        }
        if (!strings(exclusive(statement, "Action", "NotAction"))) {
            throw invalid("Policy statement must name an action");
        }
        if ((statement.has("Resource") || statement.has("NotResource"))
                && !strings(exclusive(statement, "Resource", "NotResource"))) {
            throw invalid("Invalid policy statement resource");
        }
    }

    private static JsonNode exclusive(JsonNode node, String positive, String negative) {
        if (node.has(positive) && node.has(negative)) {
            throw invalid("Policy statement cannot contain both " + positive + " and " + negative);
        }
        return node.has(negative) ? node.path(negative) : node.path(positive);
    }

    private static boolean strings(JsonNode node) {
        if (node.isTextual()) {
            return !node.textValue().isEmpty();
        }
        return node.isArray() && !node.isEmpty()
                && node.valueStream().allMatch(value -> value.isTextual() && !value.textValue().isEmpty());
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidArgumentException", message, 400);
    }

    private static AwsException missing(Target target) {
        return new AwsException("ResourceNotFoundException", "Resource " + target.arn() + " not found", 400);
    }

    private record Target(String arn, String parentArn, String region, String account, String key, boolean consumer) {}
    private record Resolved(KinesisStream stream, KinesisConsumer consumer) {}
}
