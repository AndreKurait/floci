package io.floci.aws.services.iam;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsPartitions;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.services.iam.model.AccessKey;
import io.floci.aws.services.iam.model.IamUser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit local startup administrators, stored as ordinary IAM identities, never an auth bypass. */
final class IamBootstrapPrincipals {
    private static final Set<String> FIELDS = Set.of("accountId", "partition", "userName", "accessKeyId",
            "secretAccessKey", "grant");
    private static final int MAX_FILE_BYTES = 65536;
    private final List<Principal> principals;
    private final AccountAwareStorageBackend<IamUser> users;
    private final AccountAwareStorageBackend<AccessKey> keys;
    private final AccountAwareStorageBackend<String> markers;

    IamBootstrapPrincipals(String file, AccountAwareStorageBackend<IamUser> users,
                           AccountAwareStorageBackend<AccessKey> keys,
                           AccountAwareStorageBackend<String> markers) {
        this.principals = read(file);
        this.users = users;
        this.keys = keys;
        this.markers = markers;
    }

    /** Validate the entire file and every stored identity before startup writes anything. */
    void validateStoredIdentities() {
        if (keys.scanAllAccountEntries("floci"::equals).stream()
                .anyMatch(entry -> "Active".equals(entry.value().getStatus()))) {
            throw invalid("conflicts with an active stored public deployer key; remove or deactivate it first");
        }
        for (Principal principal : principals) {
            List<AccountAwareStorageBackend.AccountEntry<IamUser>> matchingUsers =
                    users.scanAllAccountEntries(principal.userName()::equalsIgnoreCase).stream()
                            .filter(entry -> entry.accountId().equals(principal.accountId())).toList();
            if (matchingUsers.size() > 1) {
                throw invalid("conflicts with duplicate stored IAM identities");
            }
            IamUser user = matchingUsers.isEmpty() ? null : matchingUsers.getFirst().value();
            List<AccountAwareStorageBackend.AccountEntry<AccessKey>> matches =
                    keys.scanAllAccountEntries(principal.accessKeyId()::equals);
            List<AccountAwareStorageBackend.AccountEntry<String>> matchingMarkers =
                    markers.scanAllAccountEntries(principal.userName()::equalsIgnoreCase).stream()
                            .filter(entry -> entry.accountId().equals(principal.accountId())).toList();
            if (matchingMarkers.size() > 1 || (!matchingMarkers.isEmpty()
                    && !matchingMarkers.getFirst().key().equals(principal.userName()))) {
                throw invalid("conflicts with duplicate or renamed bootstrap identity markers");
            }
            String marker = matchingMarkers.isEmpty() ? null : matchingMarkers.getFirst().value();
            if (marker == null) {
                if (user != null || !matches.isEmpty()) {
                    throw invalid("conflicts with an existing IAM identity");
                }
            } else {
                if (user == null || !marker.equals(user.getUserId()) || !principal.arn().equals(user.getArn())
                        || !principal.userName().equals(user.getUserName())
                        || !user.getAttachedPolicyArns().contains(principal.policyArn()) || matches.size() != 1) {
                    throw invalid("does not match the stored bootstrap identity; it will not be recreated or regranted");
                }
                AccountAwareStorageBackend.AccountEntry<AccessKey> entry = matches.getFirst();
                AccessKey key = entry.value();
                if (!entry.accountId().equals(principal.accountId())
                        || !principal.accessKeyId().equals(key.getAccessKeyId())
                        || !principal.userName().equals(key.getUserName())
                        || !principal.secretAccessKey().equals(key.getSecretAccessKey())
                        || !"Active".equals(key.getStatus())) {
                    throw invalid("does not match the stored bootstrap key; it will not be overwritten or reactivated");
                }
            }
        }
    }

    /** Called only after validation of all entries. Existing bootstrap records remain byte-for-byte unchanged. */
    void seed() {
        for (Principal principal : principals) {
            if (markers.getForAccount(principal.accountId(), principal.userName()).isPresent()) {
                continue;
            }
            IamUser user = new IamUser("AIDA" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    principal.userName(), "/", principal.arn());
            user.getAttachedPolicyArns().add(principal.policyArn());
            users.putAllDurablyForAccount(principal.accountId(), Map.of(principal.userName(), user));
            keys.putAllDurablyForAccount(principal.accountId(), Map.of(principal.accessKeyId(),
                    new AccessKey(principal.accessKeyId(), principal.secretAccessKey(), principal.userName())));
            markers.putAllDurablyForAccount(principal.accountId(), Map.of(principal.userName(), user.getUserId()));
        }
    }

    private static List<Principal> read(String file) {
        JsonNode root;
        try {
            Path path = Path.of(file);
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("must name a regular non-symlink file");
            }
            PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (view == null || view.readAttributes().permissions().stream().anyMatch(permission ->
                    permission != PosixFilePermission.OWNER_READ && permission != PosixFilePermission.OWNER_WRITE)) {
                throw invalid("requires owner-only POSIX read/write permissions");
            }
            if (Files.size(path) > MAX_FILE_BYTES) {
                throw invalid("exceeds the size limit");
            }
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            root = mapper.readTree(readBytes(path));
        } catch (IOException | IllegalArgumentException error) {
            // Jackson errors can contain the original JSON, so never retain or log the cause.
            throw invalid("must contain readable valid JSON");
        }
        if (root == null || !root.isArray() || root.isEmpty() || root.size() > 32) {
            throw invalid("must contain between one and 32 explicit bootstrap administrators");
        }
        List<Principal> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (JsonNode node : root) {
            Set<String> fields = new HashSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!node.isObject() || !fields.equals(FIELDS)) {
                throw invalid("has missing or unrecognized fields");
            }
            String account = text(node, "accountId");
            String partition = text(node, "partition");
            String user = text(node, "userName");
            String id = text(node, "accessKeyId");
            String secret = text(node, "secretAccessKey");
            if (!account.matches("[0-9]{12}") || AwsPartitions.find(partition).isEmpty()
                    || !user.matches("[A-Za-z0-9+=,.@_-]{1,64}") || !id.matches("AKIA[A-Z0-9]{16}")
                    || !secret.matches("[\\x21-\\x7e]{40,128}") || secret.chars().distinct().count() < 16
                    || !"AdministratorAccess".equals(text(node, "grant"))) {
                throw invalid("has an invalid account, partition, user, key, secret, or explicit administrator grant");
            }
            if (!ids.add(id) || !names.add(account + "/" + user.toLowerCase(Locale.ROOT))) {
                throw invalid("contains duplicate keys or account/user identities");
            }
            result.add(new Principal(account, AwsPartitions.byId(partition).id(), user, id, secret));
        }
        return List.copyOf(result);
    }

    static byte[] readBytes(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw invalid("exceeds the size limit");
            }
            return bytes;
        }
    }

    private static String text(JsonNode node, String field) {
        if (!node.path(field).isTextual()) {
            throw invalid("requires string fields");
        }
        return node.path(field).textValue();
    }

    private static IllegalStateException invalid(String rule) {
        return new IllegalStateException("Refusing IAM bootstrap: floci.services.iam.bootstrap-principals-file "
                + rule + ".");
    }

    // JSON is parsed as a tree; this secret-bearing internal record is never serialized or reflected.
    private record Principal(String accountId, String partition, String userName,
                             String accessKeyId, String secretAccessKey) {
        String arn() {
            return AwsArnUtils.Arn.global(partition, "iam", accountId, "user/" + userName).toString();
        }

        String policyArn() {
            return AwsManagedPolicies.arnPrefix(partition) + "/AdministratorAccess";
        }

        @Override
        public String toString() {
            return "Principal[redacted]";
        }
    }
}
