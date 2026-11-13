package io.floci.aws.core.common.docker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed {@code docker create} options an operator configures for the containers a service
 * launches: {@code floci.services.lambda.docker-flags} for Lambda execution containers and
 * {@code floci.services.ecs.docker-flags} for ECS task containers. One grammar and one
 * {@link #applyTo apply} step for both, so a flag means the same thing everywhere.
 */
public record DockerFlags(
        List<String> environment,
        List<String> volumes,
        List<String> publishedPorts,
        List<String> extraHosts,
        List<String> dnsServers,
        Map<String, String> labels,
        String network,
        String user,
        boolean privileged,
        String platform
) {

    /** No flags at all: what an unset or blank configuration parses to. */
    public static DockerFlags none() {
        return parse(null);
    }

    /** Parses Lambda's flags; error messages name Lambda. */
    public static DockerFlags parse(String input) {
        return parse(input, "Lambda");
    }

    /**
     * Parses {@code input}, naming {@code owner} (e.g. "Lambda", "ECS") in error messages.
     *
     * @throws IllegalArgumentException for an unsupported flag or a flag missing its value
     */
    public static DockerFlags parse(String input, String owner) {
        List<String> tokens = tokenize(input, owner);
        List<String> environment = new ArrayList<>();
        List<String> volumes = new ArrayList<>();
        List<String> publishedPorts = new ArrayList<>();
        List<String> extraHosts = new ArrayList<>();
        List<String> dnsServers = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        String network = null;
        String user = null;
        String platform = null;
        boolean privileged = false;

        for (int index = 0; index < tokens.size(); index++) {
            String flag = tokens.get(index);
            switch (flag) {
                case "-e", "--env" -> environment.add(value(tokens, ++index, flag, owner));
                case "-v", "--volume" -> volumes.add(value(tokens, ++index, flag, owner));
                case "-p", "--publish" -> publishedPorts.add(value(tokens, ++index, flag, owner));
                case "--add-host" -> extraHosts.add(value(tokens, ++index, flag, owner));
                case "--dns" -> dnsServers.add(value(tokens, ++index, flag, owner));
                case "--label" -> addKeyValue(labels, value(tokens, ++index, flag, owner), flag, owner);
                case "--network" -> network = value(tokens, ++index, flag, owner);
                case "-u", "--user" -> user = value(tokens, ++index, flag, owner);
                case "--privileged" -> privileged = true;
                case "--platform" -> platform = value(tokens, ++index, flag, owner);
                case "" -> { }
                default -> throw new IllegalArgumentException("Unsupported " + owner + " Docker flag: " + flag);
            }
        }
        return new DockerFlags(List.copyOf(environment), List.copyOf(volumes),
                List.copyOf(publishedPorts), List.copyOf(extraHosts), List.copyOf(dnsServers),
                Map.copyOf(labels), network, user, privileged, platform);
    }

    /** Whether any DNS server is configured, i.e. Floci's embedded DNS will not be added. */
    public boolean setsDns() {
        return !dnsServers.isEmpty();
    }

    /**
     * Applies these flags to a container being built. Call it before
     * {@link ContainerBuilder.Builder#withEmbeddedDns()}, which adds Floci's DNS only when no
     * {@code --dns} server is set, and before the caller's own labels, which must win over a
     * {@code --label} of the same key.
     *
     * @param owner names the service in error messages, e.g. "Lambda" or "ECS"
     */
    public void applyTo(ContainerBuilder.Builder builder, String owner) {
        // Only what is set is applied, so no flags leave the builder exactly as it was.
        if (!environment.isEmpty()) {
            builder.withEnv(environment);
        }
        for (String volume : volumes) {
            String[] parts = volume.split(":", -1);
            if (parts.length < 2 || parts.length > 3 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("Invalid " + owner + " Docker volume: " + volume);
            }
            if (parts.length == 3 && "ro".equals(parts[2])) {
                builder.withReadOnlyBind(parts[0], parts[1]);
            } else if (parts.length == 2 || "rw".equals(parts[2])) {
                builder.withBind(parts[0], parts[1]);
            } else {
                throw new IllegalArgumentException("Invalid " + owner + " Docker volume mode: " + volume);
            }
        }
        for (String publishedPort : publishedPorts) {
            String[] parts = publishedPort.split(":", -1);
            String hostIp = parts.length == 3 ? parts[0] : null;
            String hostPort = parts.length == 2 ? parts[0] : parts.length == 3 ? parts[1] : "";
            String containerPort = parts.length == 2 ? parts[1] : parts.length == 3 ? parts[2] : "";
            if (hostPort.isBlank() || containerPort.isBlank()) {
                throw new IllegalArgumentException("Invalid " + owner + " Docker published port: " + publishedPort);
            }
            int parsedHostPort = Integer.parseInt(hostPort);
            int parsedContainerPort = Integer.parseInt(containerPort);
            if (hostIp == null) {
                builder.withPortBinding(parsedContainerPort, parsedHostPort);
            } else if ("127.0.0.1".equals(hostIp)) {
                builder.withLoopbackPortBinding(parsedContainerPort, parsedHostPort);
            } else {
                throw new IllegalArgumentException(owner + " Docker published ports only support "
                        + "127.0.0.1 as an explicit host address: " + publishedPort);
            }
        }
        for (String extraHost : extraHosts) {
            int separator = extraHost.indexOf(':');
            if (separator <= 0 || separator == extraHost.length() - 1) {
                throw new IllegalArgumentException("Invalid " + owner + " Docker extra host: " + extraHost);
            }
            builder.withExtraHost(extraHost.substring(0, separator), extraHost.substring(separator + 1));
        }
        for (String dnsServer : dnsServers) {
            builder.withDnsServer(dnsServer);
        }
        if (!labels.isEmpty()) {
            builder.withLabels(labels);
        }
        if (network != null) {
            builder.withNetworkMode(network);
        }
        if (user != null) {
            builder.withUser(user);
        }
        if (privileged) {
            builder.withPrivileged(true);
        }
    }

    private static String value(List<String> tokens, int index, String flag, String owner) {
        if (index >= tokens.size() || tokens.get(index).isBlank() || tokens.get(index).startsWith("-")) {
            throw new IllegalArgumentException(owner + " Docker flag requires a value: " + flag);
        }
        return tokens.get(index);
    }

    private static void addKeyValue(Map<String, String> values, String token, String flag, String owner) {
        int separator = token.indexOf('=');
        if (separator <= 0) {
            throw new IllegalArgumentException(owner + " Docker flag requires KEY=VALUE: " + flag);
        }
        values.put(token.substring(0, separator), token.substring(separator + 1));
    }

    /** Splits {@code input} the way a shell would split Docker flags (quotes and backslashes). */
    public static List<String> tokenize(String input) {
        return tokenize(input, "Lambda");
    }

    private static List<String> tokenize(String input, String owner) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (char character : input.toCharArray()) {
            if (escaped) {
                token.append(character);
                escaped = false;
            } else if (character == '\\' && quote != '\'') {
                escaped = true;
            } else if (quote != 0) {
                if (character == quote) {
                    quote = 0;
                } else {
                    token.append(character);
                }
            } else if (character == '\'' || character == '"') {
                quote = character;
            } else if (Character.isWhitespace(character)) {
                if (token.length() > 0) {
                    tokens.add(token.toString());
                    token.setLength(0);
                }
            } else {
                token.append(character);
            }
        }
        if (escaped || quote != 0) {
            throw new IllegalArgumentException("Unterminated quote or escape in " + owner + " Docker flags");
        }
        if (token.length() > 0) {
            tokens.add(token.toString());
        }
        return tokens;
    }
}
