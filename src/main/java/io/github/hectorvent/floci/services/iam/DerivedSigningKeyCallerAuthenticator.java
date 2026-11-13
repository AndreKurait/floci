package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsPartitions;
import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.auth.CredentialScope;
import io.github.hectorvent.floci.core.common.auth.SigV4AuthorizationHeader;
import io.github.hectorvent.floci.core.common.auth.SigV4Canonicalization;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Verifies that a request to the derived signing key endpoint is SigV4-signed by the one configured
 * caller key, byte for byte, whatever the global signature setting says. Canonicalization is
 * {@link SigV4Canonicalization}, shared with the global signature filter; the rules here are stricter:
 * Authorization header only, fixed signing name {@code floci}, the body hash always enforced, and
 * each accepted signature usable once.
 */
final class DerivedSigningKeyCallerAuthenticator {

    /** {@code BUSY}: the replay set is full of live signatures, so no new one can be remembered. */
    enum Outcome { ACCEPTED, REFUSED, REPLAYED, BUSY }

    static final String SIGNING_NAME = "floci";
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final Duration MAX_SKEW = Duration.ofMinutes(15);
    static final int REPLAY_CAPACITY = 10_000;
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'")
            .withResolverStyle(ResolverStyle.STRICT).withZone(ZoneOffset.UTC);
    private static final Pattern DATE = Pattern.compile("[0-9]{8}");
    private static final Pattern SIGNATURE = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern LOWER_HEX_SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SIGNED_HEADERS = Pattern.compile("[a-z0-9-]+(;[a-z0-9-]+)*");
    private static final List<String> SINGLE_HEADERS = List.of("authorization", "x-amz-date", "host");

    private final IamService iam;
    /** signature -> when it may be forgotten. */
    private final Map<String, Instant> seen = new HashMap<>();

    DerivedSigningKeyCallerAuthenticator(IamService iam) {
        this.iam = iam;
    }

    /**
     * @param headers every value of a header, looked up case-insensitively by lowercase name; an empty
     *                list when absent
     */
    Outcome authenticate(DerivedSigningKeySettings settings, String method, String rawPath, String rawQuery,
                         Function<String, List<String>> headers, byte[] body, Instant now) {
        try {
            if (!verified(settings, method, rawPath, rawQuery, headers, body, now)) {
                return Outcome.REFUSED;
            }
        } catch (Exception error) {
            return Outcome.REFUSED;
        }
        // The caller ID must never name a Floci credential; a key minted under it later refuses the call.
        if (iam.isKnownAccessKey(settings.callerAccessKeyId())) {
            return Outcome.REFUSED;
        }
        Instant signedAt = Instant.from(AMZ_DATE.parse(headerValue(headers, "x-amz-date")));
        return remember(headerValue(headers, "authorization"), signedAt, now);
    }

    private boolean verified(DerivedSigningKeySettings settings, String method, String rawPath, String rawQuery,
                             Function<String, List<String>> headers, byte[] body, Instant now) throws Exception {
        for (String name : SINGLE_HEADERS) {
            if (headers.apply(name).size() > 1) {
                return false;
            }
        }
        if (!headers.apply("content-encoding").isEmpty() || !headers.apply("x-amz-security-token").isEmpty()) {
            return false;
        }
        if (rawQuery != null && (rawQuery.contains("X-Amz-Credential") || rawQuery.contains("X-Amz-Signature"))) {
            return false;
        }
        String authorization = headerValue(headers, "authorization");
        if (authorization == null || !authorization.startsWith(ALGORITHM + " ")) {
            return false;
        }
        SigV4AuthorizationHeader signed = SigV4AuthorizationHeader.parse(authorization);
        if (signed == null || signed.missingEqualSign() || signed.credential() == null
                || signed.signedHeaders() == null || signed.signature() == null
                || !SIGNATURE.matcher(signed.signature()).matches()
                || !SIGNED_HEADERS.matcher(signed.signedHeaders()).matches()) {
            return false;
        }
        List<String> signedNames = Arrays.asList(signed.signedHeaders().split(";"));
        if (!signedNames.contains("host") || !signedNames.contains("x-amz-date")) {
            return false;
        }
        // Parsed byte for byte: CredentialScope.parse would lowercase the service.
        String[] credential = signed.credential().split("/", -1);
        String amzDate = headerValue(headers, "x-amz-date");
        if (credential.length != 5 || amzDate == null
                || !MessageDigest.isEqual(credential[0].getBytes(StandardCharsets.UTF_8),
                        settings.callerAccessKeyId().getBytes(StandardCharsets.UTF_8))
                || !DATE.matcher(credential[1]).matches() || !amzDate.startsWith(credential[1])
                || !isRealRegion(credential[2])
                || !SIGNING_NAME.equals(credential[3]) || !"aws4_request".equals(credential[4])) {
            return false;
        }
        Instant signedAt;
        try {
            signedAt = Instant.from(AMZ_DATE.parse(amzDate));
        } catch (DateTimeParseException error) {
            return false;
        }
        if (signedAt.isBefore(now.minus(MAX_SKEW)) || signedAt.isAfter(now.plus(MAX_SKEW))) {
            return false;
        }
        String bodyHash = Digests.sha256Hex(body);
        String declared = headerValue(headers, "x-amz-content-sha256");
        // Checked before canonicalization: payloadHash would sign a non-hex declared value verbatim.
        if (declared != null && (!LOWER_HEX_SHA256.matcher(declared).matches() || !declared.equals(bodyHash))) {
            return false;
        }
        String canonicalHeaders = SigV4Canonicalization.canonicalHeaders(signed.signedHeaders(),
                name -> String.join(",", headers.apply(name)));
        CredentialScope scope = new CredentialScope(credential[0], credential[1], credential[2], credential[3]);
        return SigV4Canonicalization.signatureMatches(method, rawPath,
                SigV4Canonicalization.canonicalQueryString(rawQuery, false), canonicalHeaders,
                signed.signedHeaders(), bodyHash, amzDate, scope, settings.callerSecret(), signed.signature());
    }

    /** A known region named as itself: a {@code <partition>-global} pseudo-region is refused. */
    static boolean isRealRegion(String region) {
        return region.matches("[a-z0-9-]{1,64}") && RegionResolver.isKnownRegion(region)
                && region.equals(AwsPartitions.normalizeRegion(region));
    }

    /**
     * Remembers a signature until just after the last instant the skew check could still accept it
     * ({@code x-amz-date + 15 min}), so an entry is never forgotten while it is still acceptable.
     */
    private synchronized Outcome remember(String authorization, Instant signedAt, Instant now) {
        SigV4AuthorizationHeader signed = SigV4AuthorizationHeader.parse(authorization);
        String signature = signed.signature();
        for (Iterator<Map.Entry<String, Instant>> entries = seen.entrySet().iterator(); entries.hasNext(); ) {
            if (!entries.next().getValue().isAfter(now)) {
                entries.remove();
            }
        }
        if (seen.containsKey(signature)) {
            return Outcome.REPLAYED;
        }
        if (seen.size() >= REPLAY_CAPACITY) {
            return Outcome.BUSY;
        }
        seen.put(signature, signedAt.plus(MAX_SKEW).plusNanos(1));
        return Outcome.ACCEPTED;
    }

    private static String headerValue(Function<String, List<String>> headers, String name) {
        List<String> values = headers.apply(name);
        return values.isEmpty() ? null : values.getFirst();
    }

    /** Header values as received, joined as SigV4 joins repeated values. Package-private for tests. */
    static Function<String, List<String>> headerLookup(Map<String, List<String>> raw) {
        Map<String, List<String>> lower = new HashMap<>();
        raw.forEach((name, values) -> lower.computeIfAbsent(name.toLowerCase(Locale.ROOT),
                ignored -> new ArrayList<>()).addAll(values));
        return name -> lower.getOrDefault(name, List.of());
    }
}
