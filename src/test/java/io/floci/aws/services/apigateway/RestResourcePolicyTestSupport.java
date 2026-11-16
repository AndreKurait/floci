package io.floci.aws.services.apigateway;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.CallerCallAuthorizer;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

final class RestResourcePolicyTestSupport {

    private RestResourcePolicyTestSupport() { }

    static CallerCallAuthorizer disabledCaller() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        return new CallerCallAuthorizer(config, null, null, null, null);
    }
}
