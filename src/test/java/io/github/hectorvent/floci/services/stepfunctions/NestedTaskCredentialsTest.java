package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NestedTaskCredentialsTest {
    @ParameterizedTest
    @CsvSource({"true,states:startExecution.sync:2", "false,lambda:invoke",
            "false,states:startExecution.waitForTaskToken", "false,aws-sdk:sfn:sendTaskSuccess"})
    void unsupportedAuthorizationOrIntegrationFailsBeforeRoleLookup(boolean enforcement, String resource) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().iam().enforcementEnabled()).thenReturn(enforcement);
        IamService iam = mock(IamService.class);
        NestedTaskCredentials credentials = new NestedTaskCredentials(iam, config);
        assertThrows(AslExecutor.FailStateException.class,
                () -> credentials.account(new ObjectMapper().createObjectNode().put(
                        "RoleArn", "arn:aws:iam::000000000222:role/target"),
                        "arn:aws:states:::" + resource, new StateMachine()));
        verifyNoInteractions(iam);
    }
}
