package io.floci.aws.services.appconfig;

import io.floci.aws.core.common.AwsException;

/** Stop rejections for which CloudFormation deletion owes no configuration change. */
public class DeploymentStopException extends AwsException {
    public enum Reason {
        ALREADY_REVERTED,
        TOO_OLD,
        NOT_LATEST
    }

    private final Reason reason;

    public DeploymentStopException(Reason reason, String message) {
        super("BadRequestException", message, 400);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
