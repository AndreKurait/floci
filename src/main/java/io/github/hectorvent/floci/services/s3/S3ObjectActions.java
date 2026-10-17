package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.IamConditionContextResolver;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The IAM actions AWS checks for an S3 object request in place of, or besides, the one its operation
 * names, and the condition keys an object creation offers those checks. The sources are the user
 * guide's "Required permissions for S3 API operations" table, and the Service Authorization Reference
 * for the version actions and the condition keys.
 */
public final class S3ObjectActions {

    private static final Logger LOG = Logger.getLogger(S3ObjectActions.class);

    /**
     * The object actions that have a version counterpart, which AWS checks in their place when the
     * request names a {@code versionId}. Retention, legal hold, restore and the multipart actions
     * have none, and are checked as they are.
     */
    private static final Map<String, String> VERSION_ACTIONS = Map.ofEntries(
            Map.entry("s3:GetObject", "s3:GetObjectVersion"),
            Map.entry("s3:DeleteObject", "s3:DeleteObjectVersion"),
            Map.entry("s3:GetObjectAcl", "s3:GetObjectVersionAcl"),
            Map.entry("s3:PutObjectAcl", "s3:PutObjectVersionAcl"),
            Map.entry("s3:GetObjectTagging", "s3:GetObjectVersionTagging"),
            Map.entry("s3:PutObjectTagging", "s3:PutObjectVersionTagging"),
            Map.entry("s3:DeleteObjectTagging", "s3:DeleteObjectVersionTagging"),
            Map.entry("s3:GetObjectAttributes", "s3:GetObjectVersionAttributes"),
            Map.entry("s3:GetObjectAnnotation", "s3:GetObjectVersionAnnotation"),
            Map.entry("s3:ListObjectAnnotations", "s3:ListObjectVersionAnnotations"),
            Map.entry("s3:PutObjectAnnotation", "s3:PutObjectVersionAnnotation"),
            Map.entry("s3:DeleteObjectAnnotation", "s3:DeleteObjectVersionAnnotation"));

    private static final List<String> GRANT_HEADERS = List.of(
            "x-amz-grant-read", "x-amz-grant-write", "x-amz-grant-read-acp", "x-amz-grant-write-acp",
            "x-amz-grant-full-control");

    private S3ObjectActions() {
    }

    /** What a request naming {@code versionId} is checked as: {@code action}'s version counterpart, if it has one. */
    public static String forVersion(String action, String versionId) {
        return versionId == null ? action : VERSION_ACTIONS.getOrDefault(action, action);
    }

    /** True for an action AWS checks only for a request naming a version, which carries {@code s3:versionid}. */
    public static boolean isVersionAction(String action) {
        return VERSION_ACTIONS.containsValue(action);
    }

    /**
     * The actions a PutObject, CopyObject or CreateMultipartUpload needs besides {@code s3:PutObject},
     * one for each thing its headers set on the new object: an ACL, tags, a retention period, a legal
     * hold. The permissions table marks each one conditionally required for all three operations. A
     * CopyObject applies the request's tags only under {@code x-amz-tagging-directive: REPLACE}; under
     * the default {@code COPY} it gives the new object the source's tags, which S3Controller checks,
     * since only it reads the source.
     *
     * @param header  reads one request header, null when it is absent
     * @param tagging the request's {@code x-amz-tagging}, from its header or, for a presigned URL, its
     *                query string
     */
    public static List<String> objectCreationActions(Function<String, String> header, String tagging) {
        List<String> actions = new ArrayList<>();
        if (header.apply("x-amz-acl") != null || GRANT_HEADERS.stream().anyMatch(name -> header.apply(name) != null)) {
            actions.add("s3:PutObjectAcl");
        }
        if (tagging != null && !tagging.isBlank() && appliesRequestTags(header)) {
            actions.add("s3:PutObjectTagging");
        }
        if (header.apply("x-amz-object-lock-mode") != null
                || header.apply("x-amz-object-lock-retain-until-date") != null) {
            actions.add("s3:PutObjectRetention");
        }
        if (header.apply("x-amz-object-lock-legal-hold") != null) {
            actions.add("s3:PutObjectLegalHold");
        }
        return actions;
    }

    /**
     * The condition keys an object creation offers {@code s3:PutObject} and each of
     * {@link #objectCreationActions}: {@code s3:RequestObjectTag/<key>} and
     * {@code s3:RequestObjectTagKeys} from the tags it applies, which a copy under the default
     * {@code COPY} directive takes from the source rather than the request, and the Object Lock keys
     * from its Object Lock headers. Null when it offers none.
     *
     * @param header  reads one request header, null when it is absent
     * @param tagging the request's {@code x-amz-tagging}, as {@link #objectCreationActions} takes it
     * @param now     the moment {@code s3:object-lock-remaining-retention-days} counts from
     */
    public static Map<String, List<String>> objectCreationConditions(Function<String, String> header, String tagging,
                                                                     Instant now) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        Map<String, List<String>> tags = appliesRequestTags(header) ? requestObjectTagConditions(tagging) : null;
        if (tags != null) {
            conditions.putAll(tags);
        }
        Map<String, List<String>> lock = objectLockConditions(header.apply("x-amz-object-lock-mode"),
                header.apply("x-amz-object-lock-retain-until-date"), header.apply("x-amz-object-lock-legal-hold"),
                now);
        if (lock != null) {
            conditions.putAll(lock);
        }
        return conditions.isEmpty() ? null : conditions;
    }

    /**
     * Whether the new object gets the request's {@code x-amz-tagging}: a PutObject's and a
     * CreateMultipartUpload's always, a CopyObject's only under {@code x-amz-tagging-directive:
     * REPLACE}. Under the default {@code COPY} a copy keeps the source's tags and ignores the header.
     */
    private static boolean appliesRequestTags(Function<String, String> header) {
        return header.apply("x-amz-copy-source") == null
                || "REPLACE".equalsIgnoreCase(header.apply("x-amz-tagging-directive"));
    }

    /**
     * {@code s3:RequestObjectTag/<key>} and {@code s3:RequestObjectTagKeys} from an
     * {@code x-amz-tagging} value. Null when it names no tag.
     */
    static Map<String, List<String>> requestObjectTagConditions(String tagging) {
        if (tagging == null || tagging.isBlank()) {
            return null;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        Set<String> requestedKeys = new LinkedHashSet<>();
        for (String pair : tagging.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = urlDecode(pair.substring(0, eq));
            String value = urlDecode(pair.substring(eq + 1));
            if (name == null || value == null) {
                continue;
            }
            conditions.put(IamConditionContextResolver.REQUEST_OBJECT_TAG_PREFIX + name, List.of(value));
            requestedKeys.add(name);
        }
        if (!requestedKeys.isEmpty()) {
            conditions.put(IamConditionContextResolver.REQUEST_OBJECT_TAG_KEYS, new ArrayList<>(requestedKeys));
        }
        return conditions.isEmpty() ? null : conditions;
    }

    /**
     * {@code s3:object-lock-mode}, {@code s3:object-lock-retain-until-date},
     * {@code s3:object-lock-remaining-retention-days} and {@code s3:object-lock-legal-hold} from the
     * Object Lock settings a request applies. A setting the request leaves out offers no key, and
     * null is returned when it leaves out all of them.
     */
    public static Map<String, List<String>> objectLockConditions(String mode, String retainUntilDate,
                                                                 String legalHold, Instant now) {
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        if (mode != null && !mode.isBlank()) {
            conditions.put(IamConditionContextResolver.OBJECT_LOCK_MODE, List.of(mode));
        }
        if (legalHold != null && !legalHold.isBlank()) {
            conditions.put(IamConditionContextResolver.OBJECT_LOCK_LEGAL_HOLD, List.of(legalHold));
        }
        if (retainUntilDate != null && !retainUntilDate.isBlank()) {
            conditions.put(IamConditionContextResolver.OBJECT_LOCK_RETAIN_UNTIL_DATE, List.of(retainUntilDate));
            Long days = remainingRetentionDays(retainUntilDate, now);
            if (days != null) {
                conditions.put(IamConditionContextResolver.OBJECT_LOCK_REMAINING_RETENTION_DAYS,
                        List.of(Long.toString(days)));
            }
        }
        return conditions.isEmpty() ? null : conditions;
    }

    /**
     * Days from {@code now} until {@code retainUntilDate}, a part day counted as a whole one, and 0
     * for a date that has passed. AWS documents the key but not its arithmetic. Rounding up keeps a
     * Deny on retention longer than N days, the user guide's example, from letting through a date
     * some hours past N days. Null for a date S3Controller would not parse either.
     */
    private static Long remainingRetentionDays(String retainUntilDate, Instant now) {
        Instant until;
        try {
            until = Instant.parse(retainUntilDate);
        } catch (DateTimeParseException e) {
            return null;
        }
        Duration remaining = Duration.between(now, until);
        if (remaining.isNegative() || remaining.isZero()) {
            return 0L;
        }
        long days = remaining.toDays();
        return remaining.minusDays(days).isZero() ? days : days + 1;
    }

    /**
     * Null for a value {@code URLDecoder} refuses, such as the {@code %zz} of a malformed
     * {@code x-amz-tagging} header. The caller drops the pair rather than letting the
     * {@code IllegalArgumentException} out of the check, where it becomes a 500 in place of the
     * 400 {@code InvalidTag} the handler returns for the same header.
     */
    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            LOG.debugv(e, "Undecodable tag component in the condition context: {0}", value);
            return null;
        }
    }
}
