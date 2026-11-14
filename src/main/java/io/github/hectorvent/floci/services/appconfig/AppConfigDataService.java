package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appconfig.model.Application;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationContent;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationProfile;
import io.github.hectorvent.floci.services.appconfig.model.ConfigurationSession;
import io.github.hectorvent.floci.services.appconfig.model.Environment;
import io.github.hectorvent.floci.services.appconfig.model.HostedConfigurationVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@ApplicationScoped
public class AppConfigDataService {
    private static final Logger LOG = Logger.getLogger(AppConfigDataService.class);

    private final StorageBackend<String, ConfigurationSession> sessionStore;
    private final AppConfigService appConfigService;
    private final ObjectMapper objectMapper;

    @Inject
    public AppConfigDataService(StorageFactory storageFactory, AppConfigService appConfigService,
                                ObjectMapper objectMapper, RegionResolver regions) {
        this.sessionStore = new AppConfigStorage<>(storageFactory, regions,
                "appconfigdata", "appconfigdata-sessions.json", new TypeReference<>() {});
        this.appConfigService = appConfigService;
        this.objectMapper = objectMapper;
    }

    public String startConfigurationSession(Map<String, Object> request) {
        String appId = (String) request.get("ApplicationIdentifier");
        String envId = (String) request.get("EnvironmentIdentifier");
        String profileId = (String) request.get("ConfigurationProfileIdentifier");

        requireIdentifier(appId, "ApplicationIdentifier");
        requireIdentifier(envId, "EnvironmentIdentifier");
        requireIdentifier(profileId, "ConfigurationProfileIdentifier");

        // Like AWS, each identifier is either the resource's ID or its name.
        Application application = resource("Application", "ApplicationIdentifier", appId,
                appConfigService::resolveApplication);
        Environment environment = resource("Environment", "EnvironmentIdentifier", envId,
                identifier -> appConfigService.resolveEnvironment(application.getId(), identifier));
        ConfigurationProfile profile = resource("ConfigurationProfile", "ConfigurationProfileIdentifier", profileId,
                identifier -> appConfigService.resolveConfigurationProfile(application.getId(), identifier));
        appId = application.getId();
        envId = environment.getId();
        profileId = profile.getId();

        ConfigurationSession session = new ConfigurationSession();
        session.setId(UUID.randomUUID().toString());
        session.setApplicationId(appId);
        session.setEnvironmentId(envId);
        session.setConfigurationProfileId(profileId);
        int pollInterval = parsePollInterval(request.getOrDefault("RequiredMinimumPollIntervalInSeconds", 15));
        session.setRequiredMinimumPollIntervalInSeconds(pollInterval);
        session.setCurrentToken(UUID.randomUUID().toString());

        sessionStore.put(session.getCurrentToken(), session);
        LOG.infov("Started AppConfigData session {0} for app {1}, env {2}, profile {3}", session.getId(), appId, envId, profileId);
        return session.getCurrentToken();
    }

    private static int parsePollInterval(Object value) {
        if (!(value instanceof Number)) {
            throw invalidPollInterval();
        }
        try {
            long interval = new BigDecimal(value.toString()).longValueExact();
            if (interval < 15 || interval > 86400) {
                throw invalidPollInterval();
            }
            return (int) interval;
        } catch (NumberFormatException | ArithmeticException e) {
            throw invalidPollInterval();
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new AwsException("BadRequestException", name + " is required", 400);
        }
    }

    private static AwsException invalidPollInterval() {
        return new AwsException("BadRequestException",
                "RequiredMinimumPollIntervalInSeconds must be an integer between 15 and 86400", 400);
    }

    private static <T> T resource(String type, String parameter, String identifier, Function<String, T> lookup) {
        try {
            return lookup.apply(identifier);
        } catch (AwsException error) {
            if ("ResourceNotFoundException".equals(error.getErrorCode())) {
                throw new AwsException(error.getErrorCode(), error.getMessage(), error.getHttpStatus(),
                        Map.of("ResourceType", type, "ReferencedBy", Map.of(parameter, identifier)));
            }
            throw error;
        }
    }

    public ConfigurationData getLatestConfiguration(String token) {
        return getLatestConfiguration(token, null);
    }

    /** The resource a valid token names, without consuming the token or changing its session. */
    Map<String, String> configurationForToken(String token) {
        ConfigurationSession session = sessionStore.get(token)
                .orElseThrow(() -> new AwsException("BadRequestException", "Invalid configuration token", 400));
        synchronized (session) {
            if (!token.equals(session.getCurrentToken())) {
                throw new AwsException("BadRequestException", "Invalid configuration token", 400);
            }
            return Map.of("ApplicationId", session.getApplicationId(), "EnvironmentId", session.getEnvironmentId(),
                    "ConfigurationProfileId", session.getConfigurationProfileId());
        }
    }

    /** {@code accept} is the caller's Accept header: the AppConfig Agent asks for Ion to evaluate variant flags itself. */
    public ConfigurationData getLatestConfiguration(String token, String accept) {
        ConfigurationSession session = sessionStore.get(token)
                .orElseThrow(() -> new AwsException("BadRequestException", "Invalid configuration token", 400));
        synchronized (session) {
            if (!token.equals(session.getCurrentToken())) {
                throw new AwsException("BadRequestException", "Invalid configuration token", 400);
            }
            return pollConfiguration(session, token, accept);
        }
    }

    private ConfigurationData pollConfiguration(ConfigurationSession session, String token, String accept) {
        resource("Configuration", "ConfigurationToken", token, ignored -> {
            appConfigService.getApplication(session.getApplicationId());
            return appConfigService.getEnvironment(session.getApplicationId(), session.getEnvironmentId());
        });

        int pollInterval = normalizePollInterval(session.getRequiredMinimumPollIntervalInSeconds());
        session.setRequiredMinimumPollIntervalInSeconds(pollInterval);

        String activeVersion = appConfigService.getActiveVersion(session.getEnvironmentId(), session.getConfigurationProfileId());
        boolean changed = activeVersion != null && !activeVersion.equals(session.getLastConfigurationVersion());

        ConfigurationContent version = null;
        HostedConfigurationVersion hosted = null;
        ConfigurationProfile profile = null;
        if (activeVersion != null) {
            try {
                profile = appConfigService.getConfigurationProfile(
                        session.getApplicationId(), session.getConfigurationProfileId());
                if (AppConfigS3Source.matches(profile)) {
                    version = configurationContent(profile, activeVersion);
                } else {
                    hosted = appConfigService.getHostedConfigurationVersion(
                            profile.getApplicationId(), profile.getId(), hostedVersionNumber(activeVersion));
                    version = new ConfigurationContent(hosted.getContent(), hosted.getContentType());
                }
                if (version != null && changed) {
                    session.setLastConfigurationVersion(activeVersion);
                }
            } catch (AwsException e) {
                LOG.warnv("Active version {0} not found for session {1}", activeVersion, session.getId());
            }
        }

        // Generate next token
        String nextToken = UUID.randomUUID().toString();
        session.setCurrentToken(nextToken);
        sessionStore.delete(token); // Old token is invalid
        sessionStore.put(nextToken, session);

        byte[] content = new byte[0];
        String contentType = "application/octet-stream";
        if (version != null) {
            ResolvedContent resolved = resolveContent(profile, version, accept);
            if (changed) {
                content = resolved.bytes();
            }
            contentType = resolved.contentType();
        }
        String configurationVersion = version != null && changed ? activeVersion : "";
        if (hosted != null && changed) {
            configurationVersion = Integer.toString(hosted.getVersionNumber());
        }
        String versionLabel = profile != null && AppConfigS3Source.matches(profile)
                ? null : configurationVersion;
        if (hosted != null) {
            versionLabel = changed ? hosted.getVersionLabel() : null;
        }

        return new ConfigurationData(content, contentType, configurationVersion, versionLabel, nextToken,
                pollInterval);
    }

    public LegacyConfiguration getConfiguration(String application, String environment, String configuration,
                                                 String clientId, String clientVersion, String accept) {
        for (String identifier : new String[]{application, environment, configuration, clientId}) {
            requireIdentifier(identifier, "Configuration identifier or client_id");
            if (identifier.length() > 64) {
                throw new AwsException("BadRequestException", "Identifiers must not exceed 64 characters", 400);
            }
        }
        if (clientVersion != null && (clientVersion.isEmpty() || clientVersion.length() > 1024)) {
            throw new AwsException("BadRequestException", "Invalid client_configuration_version", 400);
        }
        Application app = appConfigService.resolveApplication(application);
        Environment env;
        try {
            env = appConfigService.resolveEnvironment(app.getId(), environment);
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            // AWS answers a missing environment as it does an undeployed one; an unknown application or
            // profile stays ResourceNotFoundException.
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
        ConfigurationProfile profile = appConfigService.resolveConfigurationProfile(app.getId(), configuration);
        String active = appConfigService.getActiveVersion(env.getId(), profile.getId());
        if (active == null) {
            throw new AwsException("BadRequestException", "No configuration is deployed", 400);
        }
        ConfigurationContent version = configurationContent(profile, active);
        if (active.equals(clientVersion)) {
            return new LegacyConfiguration(new byte[0], version.contentType(), active, AppConfigS3Source.matches(profile));
        }
        ResolvedContent content = resolveContent(profile, version, accept);
        return new LegacyConfiguration(content.bytes(), content.contentType(), active, AppConfigS3Source.matches(profile));
    }

    /** A deployed version that is not a version number names no hosted configuration version. */
    private static int hostedVersionNumber(String deployedVersion) {
        try {
            return Integer.parseInt(deployedVersion);
        } catch (NumberFormatException e) {
            throw new AwsException("ResourceNotFoundException", "Hosted configuration version not found", 404);
        }
    }

    private ConfigurationContent configurationContent(ConfigurationProfile profile, String version) {
        if (AppConfigS3Source.matches(profile)) {
            return appConfigService.getS3ConfigurationContent(profile.getApplicationId(), profile.getId(), version);
        }
        HostedConfigurationVersion hosted = appConfigService.getHostedConfigurationVersion(
                profile.getApplicationId(), profile.getId(), hostedVersionNumber(version));
        return new ConfigurationContent(hosted.getContent(), hosted.getContentType());
    }

    private ResolvedContent resolveContent(ConfigurationProfile profile, ConfigurationContent version,
                                           String accept) {
        if (!"AWS.AppConfig.FeatureFlags".equals(profile.getType())) {
            return new ResolvedContent(version.content(), version.contentType());
        }
        // As AWS does: a caller that accepts Ion gets variant flags whole, to evaluate against its own context.
        if (acceptsFeatureFlagIon(accept) && FeatureFlagIonEncoder.hasVariants(version.content(), objectMapper)) {
            byte[] ion = FeatureFlagIonEncoder.encode(version.content(), objectMapper);
            if (ion != null) {
                return new ResolvedContent(ion, FeatureFlagIonEncoder.CONTENT_TYPE);
            }
            LOG.warnv("Feature flag profile {0} has variants Floci cannot encode as Ion; returning the stored JSON",
                    profile.getId());
        }
        return new ResolvedContent(transformFeatureFlags(version.content(), objectMapper),
                version.contentType());
    }

    static boolean acceptsFeatureFlagIon(String accept) {
        if (accept == null) {
            return false;
        }
        for (String range : accept.split(",")) {
            String[] parts = range.split(";");
            if (!"application/ion".equalsIgnoreCase(parts[0].trim())) {
                continue;
            }
            boolean featureFlags = false;
            boolean acceptable = true;
            for (int i = 1; i < parts.length; i++) {
                String[] parameter = parts[i].trim().split("=", 2);
                if (parameter.length != 2) {
                    continue;
                }
                String name = parameter[0].trim();
                String value = parameter[1].trim();
                if (name.equalsIgnoreCase("type")) {
                    featureFlags = "AWS.AppConfig.FeatureFlags".equalsIgnoreCase(parameterValue(value));
                } else if (name.equalsIgnoreCase("q")) {
                    acceptable = !value.matches("0(\\.0+)?");
                }
            }
            if (featureFlags && acceptable) {
                return true;
            }
        }
        return false;
    }

    /**
     * A media-type parameter value as RFC 9110 section 5.6.6 defines it: a token as is, or a quoted-string without its
     * DQUOTEs and with each quoted-pair read as the character after its backslash (section 5.6.4). {@code null} for a
     * quoted-string with no closing DQUOTE or with anything after it.
     */
    private static String parameterValue(String value) {
        if (!value.startsWith("\"")) {
            return value;
        }
        StringBuilder unquoted = new StringBuilder();
        boolean escaped = false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                unquoted.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i == value.length() - 1 ? unquoted.toString() : null;
            } else {
                unquoted.append(c);
            }
        }
        return null;
    }

    static byte[] transformFeatureFlags(byte[] content, ObjectMapper objectMapper) {
        if (content == null || content.length == 0) {
            return content;
        }
        try {
            JsonNode document = objectMapper.readTree(content);
            if (document == null || !document.isObject()) {
                return content;
            }
            JsonNode values = document.get("values");
            if (values == null || !values.isObject()) {
                return content;
            }

            ObjectNode retrieval = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                // Multi-variant feature flags require context evaluation and Amazon Ion output, which only a
                // caller that accepts Ion gets; preserve the stored bytes for any other.
                ObjectNode flag = retrievalFlag(entry.getValue());
                if (flag == null) {
                    return content;
                }
                retrieval.set(entry.getKey(), flag);
            }
            return objectMapper.writeValueAsBytes(retrieval);
        } catch (IOException e) {
            LOG.debugv(e, "Could not convert AppConfig feature flags to retrieval format");
            return content;
        }
    }

    /**
     * A basic flag's value in the retrieval-time format: only {@code enabled} if it is off, else its attributes too.
     * {@code null} for a flag with variants or one that is not a valid basic flag.
     */
    static ObjectNode retrievalFlag(JsonNode definition) {
        if (!definition.isObject() || definition.has("_variants")) {
            return null;
        }
        JsonNode enabled = definition.get("enabled");
        if (enabled == null || !enabled.isBoolean()) {
            return null;
        }
        if (!enabled.booleanValue()) {
            return JsonNodeFactory.instance.objectNode().put("enabled", false);
        }
        ObjectNode flag = definition.deepCopy();
        flag.remove("_createdAt");
        flag.remove("_updatedAt");
        return flag;
    }

    static int normalizePollInterval(int interval) {
        return interval >= 15 && interval <= 86400 ? interval : 15;
    }

    private record ResolvedContent(byte[] bytes, String contentType) {}

    public record LegacyConfiguration(byte[] content, String contentType, String configurationVersion,
                                      boolean s3Source) {
        public LegacyConfiguration(byte[] content, String contentType, String configurationVersion) {
            this(content, contentType, configurationVersion, false);
        }
    }

    public record ConfigurationData(byte[] content, String contentType, String configurationVersion, String versionLabel,
                                    String nextPollConfigurationToken, int nextPollIntervalInSeconds) {}
}
