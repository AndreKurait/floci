package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.core.common.AwsException;

/** An internal error. AWS answers it with the type in the header and an empty body. */
public final class LambdaWebInternalServerException extends AwsException {

    LambdaWebInternalServerException() {
        super("InternalServerException", null, 500);
    }
}
