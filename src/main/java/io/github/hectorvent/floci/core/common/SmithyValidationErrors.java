package io.github.hectorvent.floci.core.common;

import java.util.List;

/**
 * The message AWS's Smithy validation layer answers when a request breaks its model's constraints:
 * "1 validation error detected: a" or "N validation errors detected: a; b", each violation reading
 * "Value at 'path' failed to satisfy constraint: ...". The wording is Smithy's, shared by every
 * service built on it; only the error code differs by protocol.
 */
public final class SmithyValidationErrors {

    private SmithyValidationErrors() {}

    public static String message(List<String> violations) {
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("no violations to report");
        }
        String count = violations.size() == 1
                ? "1 validation error detected: "
                : violations.size() + " validation errors detected: ";
        return count + String.join("; ", violations);
    }

    /** The 400 error carrying {@link #message} under the protocol's validation error code. */
    public static AwsException exception(String errorCode, List<String> violations) {
        return new AwsException(errorCode, message(violations), 400);
    }
}
