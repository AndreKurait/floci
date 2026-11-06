package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.config.EmulatorConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * The validated settings of the derived signing key endpoint. Built once at startup when the
 * endpoint is enabled; any invalid setting refuses startup with a message that names the setting
 * and never the secret.
 */
record DerivedSigningKeySettings(String callerAccessKeyId, String callerSecret, Set<String> regions,
                                 Set<String> services, Set<String> levels) {

    static final String LEVEL_SIGNING = "signing";
    static final String LEVEL_SERVICE = "service";
    private static final String PREFIX = "floci.services.iam.derived-signing-keys.";
    private static final Pattern ACCESS_KEY_ID = Pattern.compile("[A-Z0-9]{16,128}");
    private static final Pattern SIGNING_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern SECRET = Pattern.compile("[\\x21-\\x7e]{40,128}");
    private static final int MIN_DISTINCT_SECRET_CHARS = 16;

    @Override
    public String toString() {
        return "DerivedSigningKeySettings[redacted]";
    }

    /**
     * @param servedByFloci whether Floci itself serves a signing name; such a name is refused,
     *                      because a derived key plus the session token could forge requests to it
     */
    static DerivedSigningKeySettings from(EmulatorConfig.DerivedSigningKeysConfig config, boolean iamEnabled,
                                          Predicate<String> servedByFloci) {
        if (!iamEnabled) {
            throw invalid("enabled", "requires floci.services.iam.enabled");
        }
        String keyId = config.callerAccessKeyId().orElseThrow(() -> invalid("caller-access-key-id", "is required"));
        if (!ACCESS_KEY_ID.matcher(keyId).matches()) {
            throw invalid("caller-access-key-id", "must match [A-Z0-9]{16,128}");
        }
        String secret = readSecret(config.callerSecretAccessKeyFile()
                .orElseThrow(() -> invalid("caller-secret-access-key-file", "is required")));
        Set<String> regions = exactSet("allowed-regions", config.allowedRegions().orElse(List.of()),
                DerivedSigningKeyCallerAuthenticator::isRealRegion,
                "must list known regions");
        Set<String> services = exactSet("allowed-services", config.allowedServices().orElse(List.of()),
                value -> SIGNING_NAME.matcher(value).matches(), "must list SigV4 signing names");
        for (String service : services) {
            if (servedByFloci.test(service)) {
                throw invalid("allowed-services", "must not list a signing name Floci itself serves");
            }
        }
        Set<String> levels = exactSet("allowed-levels", config.allowedLevels(),
                value -> value.equals(LEVEL_SIGNING) || value.equals(LEVEL_SERVICE),
                "must list only signing or service");
        return new DerivedSigningKeySettings(keyId, secret, regions, services, levels);
    }

    private static Set<String> exactSet(String name, List<String> values, Predicate<String> valid, String rule) {
        if (values.isEmpty()) {
            throw invalid(name, "must not be empty");
        }
        for (String value : values) {
            if (value == null || !valid.test(value)) {
                throw invalid(name, rule);
            }
        }
        return Set.copyOf(values);
    }

    private static String readSecret(String file) {
        String secret;
        try {
            // A mounted secret is often a symlink (a Kubernetes secret volume, for one): the
            // target's permissions are the ones that protect it, so the link is resolved first.
            Path path = Path.of(file).toRealPath();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("caller-secret-access-key-file", "must name a readable regular file");
            }
            PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                Set<PosixFilePermission> permissions = posix.readAttributes().permissions();
                if (permissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_")
                        || permission.name().startsWith("OTHERS_"))) {
                    throw invalid("caller-secret-access-key-file", "must not be readable by group or others");
                }
            }
            byte[] bytes = Files.readAllBytes(path);
            secret = new String(bytes, StandardCharsets.US_ASCII);
        } catch (IOException error) {
            throw invalid("caller-secret-access-key-file", "must name a readable regular file");
        }
        if (secret.endsWith("\r\n")) {
            secret = secret.substring(0, secret.length() - 2);
        } else if (secret.endsWith("\n")) {
            secret = secret.substring(0, secret.length() - 1);
        }
        if (!SECRET.matcher(secret).matches() || secret.chars().distinct().count() < MIN_DISTINCT_SECRET_CHARS) {
            throw invalid("caller-secret-access-key-file",
                    "must hold 40-128 printable ASCII characters with at least 16 distinct ones");
        }
        return secret;
    }

    private static IllegalStateException invalid(String setting, String rule) {
        return new IllegalStateException("Refusing to start the derived signing key endpoint: " + PREFIX
                + setting + " " + rule + ".");
    }
}
