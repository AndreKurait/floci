package io.floci.aws.services.ec2;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.services.ec2.SecurityGroupNftCompiler.ControlConnection;
import io.netty.util.NetUtil;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Immutable deployment-owned dependencies for protected EC2 guests, never guest-provided overrides. */
@ApplicationScoped
@Startup
public class Ec2NativePeerDependencies {
    private static final Set<String> FIELDS = Set.of("hostname", "address", "port");
    private final Map<String, ControlConnection> dependencies;
    private final String sha256;

    @Inject
    public Ec2NativePeerDependencies(EmulatorConfig config) {
        this(config.services().ec2().nativePeerDependencies());
    }

    public Ec2NativePeerDependencies(String value) {
        ObjectMapper mapper = JsonMapper.builder(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        TreeMap<String, ControlConnection> parsed = new TreeMap<>();
        try {
            JsonNode root = mapper.readTree(value == null ? "[]" : value);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("EC2 native peer dependencies must be a JSON array");
            }
            for (JsonNode item : root) {
                if (!item.isObject() || item.size() != FIELDS.size()
                        || !item.has("hostname") || !item.has("address") || !item.has("port")
                        || !item.get("hostname").isTextual() || !item.get("address").isTextual()
                        || !item.get("port").isIntegralNumber() || !item.get("port").canConvertToInt()) {
                    throw new IllegalArgumentException("EC2 native peers require only hostname, address and integer port");
                }
                String hostname = item.get("hostname").textValue();
                requireHostname(hostname);
                String address = item.get("address").textValue();
                requireAddress(address);
                ControlConnection connection = new ControlConnection(address, item.get("port").intValue());
                if (parsed.putIfAbsent(hostname, connection) != null) {
                    throw new IllegalArgumentException("An EC2 native peer hostname is declared more than once");
                }
            }
            List<Map<String, Object>> canonical = new ArrayList<>();
            parsed.forEach((hostname, connection) -> {
                Map<String, Object> row = new TreeMap<>();
                row.put("hostname", hostname);
                row.put("address", connection.address());
                row.put("port", connection.port());
                canonical.add(row);
            });
            byte[] encoded = mapper.writeValueAsString(canonical).getBytes(StandardCharsets.US_ASCII);
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid EC2 native peer dependency JSON", failure);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("The JVM cannot hash its native peer policy", failure);
        }
        dependencies = Collections.unmodifiableMap(parsed);
    }

    static void requireHostname(String hostname) {
        if (hostname == null || hostname.length() > 253
                || !hostname.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"
                        + "(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
                || NetUtil.isValidIpV4Address(hostname)) {
            throw new IllegalArgumentException("EC2 native peer hostname must be an exact canonical DNS name");
        }
        for (String label : hostname.split("\\.")) {
            if (label.length() > 63) {
                throw new IllegalArgumentException("EC2 native peer DNS label is too long");
            }
        }
    }

    private static void requireAddress(String address) {
        if (address == null || !NetUtil.isValidIpV4Address(address)) {
            throw new IllegalArgumentException("EC2 native peer address must be a canonical private IPv4 literal");
        }
        String[] octets = address.split("\\.");
        for (String octet : octets) {
            if (!Integer.toString(Integer.parseInt(octet)).equals(octet)) {
                throw new IllegalArgumentException("EC2 native peer address must not contain ambiguous octets");
            }
        }
        int first = Integer.parseInt(octets[0]);
        int second = Integer.parseInt(octets[1]);
        if (!(first == 10 || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168)) {
            throw new IllegalArgumentException("EC2 native peer address must be inside RFC1918 space");
        }
    }

    public Map<String, ControlConnection> dependencies() {
        return dependencies;
    }

    public List<ControlConnection> connections() {
        return dependencies.values().stream().distinct()
                .sorted(Comparator.comparing(ControlConnection::address).thenComparingInt(ControlConnection::port))
                .toList();
    }

    public Map<String, Object> attestation() {
        return Map.of("contract", 1, "count", dependencies.size(), "sha256", sha256);
    }
}
