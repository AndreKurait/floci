package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsPartitions;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Operator-supplied local service-account memberships, independent of IAM account aliases.
 * Membership names an account in a policy; it neither authenticates requests nor grants actions.
 */
@Startup
@ApplicationScoped
public class ServicePrincipalMemberships {

    static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,252}");
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final Map<String, Set<String>> memberships;

    @Inject
    public ServicePrincipalMemberships(EmulatorConfig config) {
        this(config.services().iam().servicePrincipalMembershipsPath().map(Path::of).orElse(null));
    }

    ServicePrincipalMemberships() {
        memberships = Map.of();
    }

    ServicePrincipalMemberships(Path path) {
        memberships = path == null ? Map.of() : load(path);
    }

    /** Uses the current authenticated principal's account, never the account of its previous caller. */
    boolean contains(String callerArn, String servicePrincipal) {
        if (!AwsArnUtils.isArn(callerArn) || servicePrincipal == null) {
            return false;
        }
        AwsArnUtils.Arn caller = AwsArnUtils.parse(callerArn);
        if (!("iam".equals(caller.service()) || "sts".equals(caller.service())) || !caller.region().isEmpty()) {
            return false;
        }
        String account = AwsArnUtils.Arn.global(caller.partition(), "iam", caller.accountId(), "root").toString();
        return memberships.getOrDefault(account, Set.of()).contains(servicePrincipal);
    }

    private static Map<String, Set<String>> load(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) {
                throw new IllegalArgumentException("Service-principal membership file exceeds 1 MiB");
            }
            JsonNode root = MAPPER.readTree(bytes);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("Service-principal memberships must be an account-root object");
            }
            Map<String, Set<String>> result = new HashMap<>();
            for (Map.Entry<String, JsonNode> entry : root.properties()) {
                validateAccount(entry.getKey());
                if (!entry.getValue().isArray()) {
                    throw new IllegalArgumentException("Service-principal memberships must be arrays of names");
                }
                Set<String> names = new HashSet<>();
                for (JsonNode name : entry.getValue()) {
                    if (!name.isTextual() || !NAME.matcher(name.asText()).matches()
                            || !names.add(name.asText())) {
                        throw new IllegalArgumentException("Service-principal names must be distinct exact identifiers");
                    }
                }
                result.put(entry.getKey(), Set.copyOf(names));
            }
            return Map.copyOf(result);
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid service-principal membership file: " + path, e);
        }
    }

    private static void validateAccount(String value) {
        AwsArnUtils.Arn account = AwsArnUtils.parse(value);
        if (!AwsPartitions.ids().contains(account.partition()) || !"iam".equals(account.service())
                || !account.region().isEmpty() || !account.accountId().matches("\\d{12}")
                || !"root".equals(account.resource())) {
            throw new IllegalArgumentException("Service-principal membership keys must be account-root ARNs");
        }
    }
}
