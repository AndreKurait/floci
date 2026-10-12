package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ses.model.BulkEmailEntry;
import io.github.hectorvent.floci.services.ses.model.EmailContent;
import io.github.hectorvent.floci.services.ses.model.SendEmailRequest;
import org.apache.james.mime4j.dom.Message;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The address syntax check AWS applies to every address of a send ahead of the destination,
 * configuration-set and length checks (probe-confirmed). The messages are those of JavaMail's
 * strict {@code InternetAddress} check, which scans the addr-spec once from the left, and only
 * the four AWS was seen to return are reproduced: a control, whitespace or non-ASCII character in
 * the local part, a missing {@code @}, an empty domain, and such a character in the domain. A
 * display name or a comment may hold any character, and a quoted local part is not inspected
 * except for a CR or LF, which AWS rejects with its own wording even before whitespace.
 * In a raw message AWS checks the From, To, Cc and Bcc headers (probe-confirmed);
 * the Reply-To and Return-Path headers were not probed and are left unchecked. The v2 controller
 * remaps the code to BadRequestException. A v2 raw send checks its From header only without a
 * sender parameter, and then answers an invalid From address for every violation but a missing
 * {@code @}, which it reports later (probe-confirmed for the four JavaMail messages; a quoted CR
 * or LF is assumed to follow).
 */
final class SesAddressSyntax {

    private static final List<String> RAW_CHECKED_HEADERS = List.of("To", "Cc", "Bcc");
    private static final String MISSING_FINAL_DOMAIN = "Missing final '@domain'";

    private SesAddressSyntax() {
    }

    static void requireEnvelope(SendEmailRequest request) {
        SesSendAddresses.forEachEnvelope(request, SesAddressSyntax::require);
    }

    static void requireRaw(SendEmailRequest request, EmailContent.Raw raw, Message message) {
        SesSendAddresses.forEachRaw(request, message, RAW_CHECKED_HEADERS, fromHeaderCheck(request, raw),
                SesAddressSyntax::require);
    }

    private static Consumer<String> fromHeaderCheck(SendEmailRequest request, EmailContent.Raw raw) {
        if (!raw.checksFromHeader(request.hasSource())) {
            return SesSendAddresses.UNCHECKED;
        }
        return raw.fromHeaderAsFallback() ? SesAddressSyntax::requireFallbackFrom : SesAddressSyntax::require;
    }

    private static void requireFallbackFrom(String address) {
        String violation = violation(address);
        if (violation != null && !MISSING_FINAL_DOMAIN.equals(violation)) {
            throw new AwsException("InvalidParameterValue", "Invalid From address provided.", 400);
        }
    }

    /**
     * The missing {@code @} of a v2 raw From header read in place of the sender, which AWS reports
     * only after the configuration set and the missing-destination checks, and before the length
     * check (probe-confirmed).
     */
    static void requireFallbackFromAt(SendEmailRequest request, EmailContent.Raw raw, Message message) {
        if (raw.fromHeaderAsFallback() && !request.hasSource()) {
            SesSendAddresses.forEachHeaderAddress(message, "From", SesAddressSyntax::requireAt);
        }
    }

    private static void requireAt(String address) {
        if (MISSING_FINAL_DOMAIN.equals(violation(address))) {
            throw new AwsException("InvalidParameterValue", MISSING_FINAL_DOMAIN, 400);
        }
    }

    /**
     * Checks a bulk sender or return path (v2 FeedbackForwardingEmailAddress). A bulk send words a
     * malformed or over-long one as an invalid address for the whole request, echoing it as given,
     * and reports it before a missing stored template (probe-confirmed; an over-long display-name
     * address is assumed to be measured as on a single send). The v1 handler and the v2 controller
     * call it as soon as the address is read, ahead of the template lookup they perform.
     */
    static void requireBulkAddress(String address) {
        if (violation(address) != null || SesAddressLength.exceedsLimit(address)) {
            throw new AwsException("InvalidParameterValue", "Invalid email address<" + address + ">.", 400);
        }
    }

    /**
     * Checks the To, Cc and Bcc of one bulk entry, then the request's Reply-To. AWS reports a
     * malformed entry recipient as that entry's status, and a malformed Reply-To as the status of
     * every entry, not as a request error (probe-confirmed). An address list of the wrong JSON type
     * never reaches this check: the controller rejects the whole request with SerializationException.
     */
    static void requireBulkDestination(BulkEmailEntry entry, List<String> replyToAddresses) {
        for (List<String> addresses : Arrays.asList(entry.toAddresses(), entry.ccAddresses(), entry.bccAddresses(),
                replyToAddresses)) {
            if (addresses != null) {
                addresses.forEach(SesAddressSyntax::require);
            }
        }
    }

    static void require(String address) {
        String violation = violation(address);
        if (violation != null) {
            throw new AwsException("InvalidParameterValue", violation, 400);
        }
    }

    /**
     * Returns the message AWS answers for a malformed address, or null when the address passes or
     * is blank (a missing address is reported by the caller's own required-field check).
     */
    static String violation(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        String addrSpec = addrSpec(address);
        boolean quoted = false;
        int i = 0;
        for (; i < addrSpec.length(); i++) {
            char c = addrSpec.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                quoted = !quoted;
            } else if (quoted) {
                if (c == '\r' || c == '\n') {
                    return "Invalid email address " + address + ".";
                }
            } else if (c == '@') {
                break;
            } else if (isControlOrWhitespace(c)) {
                return "Local address contains control or whitespace";
            }
        }
        if (i >= addrSpec.length()) {
            return MISSING_FINAL_DOMAIN;
        }
        String domain = addrSpec.substring(i + 1);
        if (domain.isEmpty()) {
            return "Missing domain";
        }
        for (int j = 0; j < domain.length(); j++) {
            if (isControlOrWhitespace(domain.charAt(j))) {
                return "Domain contains control or whitespace";
            }
        }
        return null;
    }

    private static boolean isControlOrWhitespace(char c) {
        return c <= ' ' || c >= 0x7f;
    }

    private static String addrSpec(String mailbox) {
        String withoutComments = withoutComments(mailbox).trim();
        int open = SesSendAddresses.lastUnquotedAngle(withoutComments);
        if (open >= 0 && withoutComments.endsWith(">")) {
            return withoutComments.substring(open + 1, withoutComments.length() - 1).trim();
        }
        return withoutComments;
    }

    private static String withoutComments(String mailbox) {
        StringBuilder out = new StringBuilder();
        boolean quoted = false;
        int commentDepth = 0;
        for (int i = 0; i < mailbox.length(); i++) {
            char c = mailbox.charAt(i);
            if ((quoted || commentDepth > 0) && c == '\\' && i + 1 < mailbox.length()) {
                if (commentDepth == 0) {
                    out.append(c).append(mailbox.charAt(i + 1));
                }
                i++;
                continue;
            }
            if (commentDepth > 0) {
                if (c == '(') {
                    commentDepth++;
                } else if (c == ')') {
                    commentDepth--;
                }
                continue;
            }
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                commentDepth = 1;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
