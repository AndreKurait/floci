package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses a derive request, binds it to the configured scope, resolves the one eligible session and
 * derives its key. Every refusal carries a fixed code and no input.
 */
final class DerivedSigningKeyService {

    enum Refusal { INVALID_REQUEST, SCOPE_NOT_ALLOWED, CREDENTIAL_REFUSED }

    static final class Refused extends RuntimeException {
        final Refusal refusal;

        Refused(Refusal refusal) {
            super(refusal.name(), null, false, false);
            this.refusal = refusal;
        }
    }

    static final int MAX_BODY_BYTES = 16384;
    private static final Duration DATE_SKEW = Duration.ofMinutes(15);
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    private static final Set<String> FIELDS = Set.of(
            "correlationId", "accessKeyId", "sessionToken", "date", "region", "service", "level");
    private static final Pattern CORRELATION_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern ACCESS_KEY = Pattern.compile("ASIA[A-Z0-9]{16}");
    private static final Pattern NAME = Pattern.compile("[\\x21-\\x7e]{1,64}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final IssuedSessionVerifier verifier;

    DerivedSigningKeyService(IssuedSessionVerifier verifier) {
        this.verifier = verifier;
    }

    Map<String, Object> derive(DerivedSigningKeySettings settings, byte[] body, Instant now) {
        Map<String, String> request = parse(body);
        String date = request.get("date");
        if (!allowedDates(now).contains(date) || !settings.regions().contains(request.get("region"))
                || !settings.services().contains(request.get("service"))
                || !settings.levels().contains(request.get("level"))) {
            throw new Refused(Refusal.SCOPE_NOT_ALLOWED);
        }
        String accessKeyId = request.get("accessKeyId");
        List<IssuedSessionVerifier.Snapshot> initial = verifier.eligible(accessKeyId);
        if (initial.size() != 1) {
            throw new Refused(Refusal.CREDENTIAL_REFUSED);
        }
        IssuedSessionVerifier.Snapshot session = initial.getFirst();
        if (!MessageDigest.isEqual(session.token().getBytes(StandardCharsets.UTF_8),
                request.get("sessionToken").getBytes(StandardCharsets.UTF_8))
                || !session.expiry().isAfter(now)) {
            throw new Refused(Refusal.CREDENTIAL_REFUSED);
        }
        byte[] key;
        try {
            key = derive(session.secret(), date, request.get("region"), request.get("service"),
                    request.get("level"));
        } catch (Exception error) {
            throw new Refused(Refusal.CREDENTIAL_REFUSED);
        }
        // A rotation or revocation between the lookup and the derivation refuses the answer.
        if (!initial.equals(verifier.eligible(accessKeyId))) {
            throw new Refused(Refusal.CREDENTIAL_REFUSED);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("correlationId", request.get("correlationId"));
        response.put("accessKeyId", accessKeyId);
        response.put("credentialKind", session.kind());
        response.put("date", date);
        response.put("region", request.get("region"));
        response.put("service", request.get("service"));
        response.put("level", request.get("level"));
        response.put("derivedKey", Base64.getEncoder().encodeToString(key));
        response.put("credentialExpiresAt", session.expiry().toString());
        return response;
    }

    /** The SigV4 key chain, stopped at kService for {@code service} or carried to kSigning for {@code signing}. */
    static byte[] derive(String secret, String date, String region, String service, String level) throws Exception {
        byte[] kDate = SigV4RequestValidator.hmacSha256(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = SigV4RequestValidator.hmacSha256(kDate, region);
        byte[] kService = SigV4RequestValidator.hmacSha256(kRegion, service);
        return DerivedSigningKeySettings.LEVEL_SERVICE.equals(level)
                ? kService : SigV4RequestValidator.hmacSha256(kService, "aws4_request");
    }

    /** The UTC dates a request signed within the clock-skew window of now can carry. */
    static Set<String> allowedDates(Instant now) {
        return Set.copyOf(List.of(dateOf(now.minus(DATE_SKEW)), dateOf(now), dateOf(now.plus(DATE_SKEW))));
    }

    private static String dateOf(Instant instant) {
        return DATE.format(LocalDate.ofInstant(instant, ZoneOffset.UTC));
    }

    static Map<String, String> parse(byte[] body) {
        JsonNode tree;
        try (JsonParser parser = JSON.createParser(body)) {
            tree = JSON.readTree(parser);
            if (tree == null || !tree.isObject() || parser.nextToken() != null) {
                throw new Refused(Refusal.INVALID_REQUEST);
            }
        } catch (Refused refused) {
            throw refused;
        } catch (Exception error) {
            throw new Refused(Refusal.INVALID_REQUEST);
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, JsonNode> field : tree.properties()) {
            if (!field.getValue().isTextual()) {
                throw new Refused(Refusal.INVALID_REQUEST);
            }
            result.put(field.getKey(), field.getValue().textValue());
        }
        if (!result.keySet().equals(FIELDS)
                || !CORRELATION_ID.matcher(result.get("correlationId")).matches()
                || !ACCESS_KEY.matcher(result.get("accessKeyId")).matches()
                || result.get("sessionToken").isEmpty() || result.get("sessionToken").length() > 4096
                || !validDate(result.get("date"))
                || !NAME.matcher(result.get("region")).matches()
                || !NAME.matcher(result.get("service")).matches()
                || !NAME.matcher(result.get("level")).matches()) {
            throw new Refused(Refusal.INVALID_REQUEST);
        }
        return Map.copyOf(result);
    }

    private static boolean validDate(String value) {
        if (!value.matches("[0-9]{8}")) {
            return false;
        }
        try {
            DATE.parse(value);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }
}
