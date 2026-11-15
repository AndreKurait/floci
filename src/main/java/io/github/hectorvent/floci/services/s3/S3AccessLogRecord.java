package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsCanonicalUserId;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** An observed HTTP response, without credentials, entities or mutable request context. */
record S3AccessLogRecord(Instant received, String remoteIp, String requester, String operation,
                         String key, String requestLine, String referer, String userAgent,
                         String versionId, String host, boolean headerVerified,
                         int status, String errorCode, String requestId, String hostId,
                         long bytesSent, Long objectSize) {

    static final int MAX_RECORD_BYTES = 16_384;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    byte[] bytes(String account, String bucket) {
        boolean authenticated = headerVerified;
        String line = String.join(" ",
                AwsCanonicalUserId.forAccount(account), field(bucket),
                "[" + TIME.format(received) + "]", field(remoteIp),
                field(authenticated ? requester : null), field(requestId), field(operation), field(key),
                quoted(requestLine), Integer.toString(status), field(errorCode),
                bytesSent > 0 ? Long.toString(bytesSent) : "-",
                objectSize == null ? "-" : Long.toString(objectSize),
                "-", "-", quoted(referer), quoted(userAgent), field(versionId), field(hostId),
                authenticated ? "SigV4" : "-", "-", authenticated ? "AuthHeader" : "-",
                field(host), "-", "-", "-", "-");
        // AWS quotes these fields without a backslash escape convention. HTTP rejects CR/LF;
        // refuse any remaining raw control rather than inventing a different public format.
        if (line.chars().anyMatch(character -> character < 32 || character == 127)) {
            return null;
        }
        for (String token : List.of(field(bucket), field(remoteIp), field(requester), field(operation),
                field(key), field(versionId), field(host), field(errorCode), field(requestId), field(hostId))) {
            if (token.chars().anyMatch(Character::isWhitespace)) {
                return null;
            }
        }
        byte[] result = (line + "\n").getBytes(StandardCharsets.UTF_8);
        return result.length <= MAX_RECORD_BYTES ? result : null;
    }

    private static String field(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static String quoted(String value) {
        return "\"" + field(value) + "\"";
    }
}
