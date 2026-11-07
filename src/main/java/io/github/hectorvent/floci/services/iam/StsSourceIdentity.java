package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.core.common.AwsException;

import java.util.regex.Pattern;

/** The immutable source identity passed into an STS role session. */
public final class StsSourceIdentity {
    private static final Pattern VALUE = Pattern.compile("[\\w+=,.@-]{2,64}");

    private StsSourceIdentity() {
    }

    public static String resolve(String requested, String inherited) {
        if (requested != null && (!VALUE.matcher(requested).matches() || requested.startsWith("aws:"))) {
            throw new AwsException("ValidationError", "SourceIdentity must contain 2 to 64 permitted characters.", 400);
        }
        if (inherited != null && requested != null && !inherited.equals(requested)) {
            throw new AwsException("AccessDenied", "SourceIdentity cannot be changed during role chaining.", 403);
        }
        return inherited == null ? requested : inherited;
    }
}
