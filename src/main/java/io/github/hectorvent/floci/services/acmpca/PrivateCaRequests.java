package io.github.hectorvent.floci.services.acmpca;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

final class PrivateCaRequests {

    private PrivateCaRequests() {}

    static AwsException invalid(String message) {
        return new AwsException("InvalidArgsException", message, 400);
    }

    static String text(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isTextual() || value.textValue().isEmpty()) {
            throw invalid(field + " must be a nonempty string");
        }
        return value.textValue();
    }

    static String optionalText(JsonNode object, String field) {
        return object.hasNonNull(field) ? text(object, field) : null;
    }

    static void fields(JsonNode object, String name, Set<String> allowed) {
        if (!object.isObject()) {
            throw invalid(name + " must be an object");
        }
        object.properties().forEach(entry -> {
            if (!allowed.contains(entry.getKey()) && !entry.getValue().isNull()) {
                throw invalid(name + "." + entry.getKey() + " is not supported");
            }
        });
    }

    static String token(JsonNode request) {
        String token = optionalText(request, "IdempotencyToken");
        if (token != null && (token.length() > 36 || !token.matches("[\\t\\n\\r\\u0020-\\u00ff]+"))) {
            throw invalid("IdempotencyToken must contain 1 to 36 supported characters");
        }
        return token;
    }

    static Map<String, String> configuration(JsonNode request) {
        fields(request, "request", Set.of("CertificateAuthorityType", "CertificateAuthorityConfiguration",
                "IdempotencyToken", "RevocationConfiguration", "UsageMode"));
        if (!"ROOT".equals(text(request, "CertificateAuthorityType"))) {
            throw invalid("Only ROOT certificate authorities are supported");
        }
        String usage = optionalText(request, "UsageMode");
        if (usage != null && !"GENERAL_PURPOSE".equals(usage)) {
            throw invalid("Only GENERAL_PURPOSE certificate authorities are supported");
        }
        revocation(request);
        JsonNode config = request.path("CertificateAuthorityConfiguration");
        fields(config, "CertificateAuthorityConfiguration", Set.of("KeyAlgorithm", "SigningAlgorithm", "Subject"));
        if (!"RSA_2048".equals(text(config, "KeyAlgorithm"))
                || !"SHA256WITHRSA".equals(text(config, "SigningAlgorithm"))) {
            throw invalid("Only RSA_2048 with SHA256WITHRSA certificate authorities are supported");
        }
        JsonNode subject = config.path("Subject");
        fields(subject, "Subject", PrivateCaCertificates.subjectFieldNames());
        Map<String, String> values = new LinkedHashMap<>();
        subject.properties().forEach(entry -> {
            if (!entry.getValue().isNull()) {
                String value = text(subject, entry.getKey());
                int maximum = switch (entry.getKey()) {
                    case "Country" -> 2;
                    case "CommonName" -> 64;
                    case "State", "Locality" -> 128;
                    case "SerialNumber" -> 64;
                    default -> 64;
                };
                if (value.codePointCount(0, value.length()) > maximum
                        || value.codePoints().anyMatch(code -> Character.isISOControl(code)
                                || code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE)
                        || "Country".equals(entry.getKey()) && !value.matches("[A-Za-z]{2}")
                        || "SerialNumber".equals(entry.getKey()) && !value.matches("[A-Za-z0-9 '()+,./:=?-]+")) {
                    throw invalid("Invalid Subject." + entry.getKey());
                }
                values.put(entry.getKey(), value);
            }
        });
        if (values.isEmpty()) {
            throw invalid("Subject must contain at least one supported name");
        }
        return values;
    }

    static void revocation(JsonNode request) {
        if (!request.hasNonNull("RevocationConfiguration")) {
            return;
        }
        JsonNode config = request.path("RevocationConfiguration");
        fields(config, "RevocationConfiguration", Set.of("CrlConfiguration", "OcspConfiguration"));
        for (String name : Set.of("CrlConfiguration", "OcspConfiguration")) {
            if (!config.hasNonNull(name)) {
                continue;
            }
            JsonNode mechanism = config.path(name);
            fields(mechanism, name, Set.of("Enabled"));
            if (!mechanism.path("Enabled").isBoolean() || mechanism.path("Enabled").booleanValue()) {
                throw invalid(name + " must contain only Enabled=false; revocation delivery is not supported");
            }
        }
    }

    static String blob(JsonNode request, String field, int maximum) {
        String encoded = text(request, field);
        if (encoded.length() > 4 * ((maximum + 2) / 3)) {
            throw invalid(field + " exceeds its size limit");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length == 0 || bytes.length > maximum) {
                throw invalid(field + " exceeds its size limit");
            }
            return new String(bytes, StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException failure) {
            throw invalid(field + " must be base64 encoded PEM");
        }
    }

    static long integer(JsonNode request, String field, long minimum, long maximum) {
        JsonNode value = request.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < minimum || value.longValue() > maximum) {
            throw invalid(field + " must be an integer between " + minimum + " and " + maximum);
        }
        return value.longValue();
    }

    static Instant validity(JsonNode request, Instant now, boolean start) {
        String name = start ? "ValidityNotBefore" : "Validity";
        if (start && !request.hasNonNull(name)) {
            return now.minus(1, ChronoUnit.HOURS);
        }
        JsonNode validity = request.path(name);
        fields(validity, name, Set.of("Type", "Value"));
        String type = text(validity, "Type");
        long value = integer(validity, "Value", 1, Long.MAX_VALUE);
        if (start && !"ABSOLUTE".equals(type)) {
            throw invalid("ValidityNotBefore supports only ABSOLUTE");
        }
        try {
            Instant result = switch (type) {
                case "ABSOLUTE" -> Instant.ofEpochSecond(value);
                case "DAYS" -> now.plus(value, ChronoUnit.DAYS);
                case "MONTHS" -> now.atZone(ZoneOffset.UTC).plusMonths(value).toInstant();
                case "YEARS" -> now.atZone(ZoneOffset.UTC).plusYears(value).toInstant();
                default -> throw invalid("Validity supports DAYS, MONTHS, YEARS and ABSOLUTE");
            };
            if (result.atZone(ZoneOffset.UTC).getYear() > 9999 || !start && !result.isAfter(now)) {
                throw invalid("The certificate validity is outside the supported time range");
            }
            return result;
        } catch (DateTimeException | ArithmeticException failure) {
            throw invalid("The certificate validity is outside the supported time range");
        }
    }
}
